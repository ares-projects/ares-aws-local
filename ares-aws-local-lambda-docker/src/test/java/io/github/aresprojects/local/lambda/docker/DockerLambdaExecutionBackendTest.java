package io.github.aresprojects.local.lambda.docker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.github.aresprojects.local.lambda.LambdaArtifact;
import io.github.aresprojects.local.lambda.LambdaFunctionSnapshot;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DockerLambdaExecutionBackendTest {
    private HttpServer server;
    private Path artifactPath;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (artifactPath != null) {
            Files.deleteIfExists(artifactPath);
        }
    }

    @Test
    void reusesContainerForAnUnchangedRevisionAndPreservesFunctionErrors() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2015-03-31/functions/function/invocations", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("X-Amz-Function-Error", "Handled");
            byte[] body = "{\"errorMessage\":\"boom\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        ContainerHandle handle = new ContainerHandle(
                "container",
                "ares-test",
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        ContainerRuntime runtime = new ContainerRuntime() {
            @Override
            public ContainerHandle start(ContainerSpec specification) {
                starts.incrementAndGet();
                return handle;
            }

            @Override
            public void stop(ContainerHandle container) {
                stops.incrementAndGet();
            }
        };
        DockerLambdaExecutionBackend backend = new DockerLambdaExecutionBackend(
                runtime,
                LambdaRuntimeProviderRegistry.of(java.util.List.of(new Java21RuntimeProvider())),
                Duration.ofSeconds(2),
                Executors.newVirtualThreadPerTaskExecutor());

        var first = backend.invoke(function(), "one".getBytes(StandardCharsets.UTF_8))
                .toCompletableFuture()
                .join();
        var second = backend.invoke(function(), "two".getBytes(StandardCharsets.UTF_8))
                .toCompletableFuture()
                .join();

        assertEquals(1, starts.get());
        assertEquals("Handled", first.functionError().orElseThrow());
        assertArrayEquals(second.payload(), first.payload());
        backend.close();
        assertEquals(1, stops.get());
    }

    @Test
    void defaultBackendCanBeClosedWithoutStartingDocker() {
        DockerLambdaExecutionBackend backend = new DockerLambdaExecutionBackend();
        backend.close();
    }

    @Test
    void invalidationStopsTheContainerBeforeTheNextRevisionStarts() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2015-03-31/functions/function/invocations", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        ContainerRuntime runtime = new ContainerRuntime() {
            @Override
            public ContainerHandle start(ContainerSpec specification) {
                starts.incrementAndGet();
                return new ContainerHandle(
                        "container-" + starts.get(),
                        specification.name(),
                        java.net.URI.create(
                                "http://127.0.0.1:" + server.getAddress().getPort()));
            }

            @Override
            public void stop(ContainerHandle container) {
                stops.incrementAndGet();
            }
        };
        DockerLambdaExecutionBackend backend = new DockerLambdaExecutionBackend(
                runtime,
                LambdaRuntimeProviderRegistry.of(java.util.List.of(new Java21RuntimeProvider())),
                Duration.ofSeconds(2),
                Executors.newVirtualThreadPerTaskExecutor());

        backend.invoke(function(), "{}".getBytes(StandardCharsets.UTF_8))
                .toCompletableFuture()
                .join();
        backend.invalidate("hello", "rev-1");
        backend.invoke(function("rev-2"), "{}".getBytes(StandardCharsets.UTF_8))
                .toCompletableFuture()
                .join();

        assertEquals(2, starts.get());
        assertEquals(1, stops.get());
        backend.close();
    }

    @Test
    void replacingRevisionWithoutExplicitInvalidationReplacesTheContainer() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2015-03-31/functions/function/invocations", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        ContainerRuntime runtime = new ContainerRuntime() {
            @Override
            public ContainerHandle start(ContainerSpec specification) {
                starts.incrementAndGet();
                return localHandle();
            }

            @Override
            public void stop(ContainerHandle container) {
                stops.incrementAndGet();
            }
        };
        DockerLambdaExecutionBackend backend = backend(runtime);

        backend.invoke(function("rev-1"), "{}".getBytes(StandardCharsets.UTF_8))
                .toCompletableFuture()
                .join();
        backend.invoke(function("rev-2"), "{}".getBytes(StandardCharsets.UTF_8))
                .toCompletableFuture()
                .join();

        assertEquals(2, starts.get());
        assertEquals(1, stops.get());
        backend.close();
    }

    @Test
    void cleansExtractedTaskDirectoryWhenContainerStartupFails() throws Exception {
        DockerLambdaExecutionBackend backend = backend(specification -> {
            throw new DockerExecutionException(DockerExecutionFailure.COMMAND_FAILED, "start failed");
        });

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> backend.invoke(function(), "{}".getBytes(StandardCharsets.UTF_8))
                        .toCompletableFuture()
                        .join());

        assertEquals(DockerExecutionFailure.COMMAND_FAILED, rootCause(exception).failure());
        backend.close();
    }

    @Test
    void rejectsUnsafeZipEntriesAndOversizedResponses() throws Exception {
        artifactPath = Files.createTempFile("unsafe-lambda-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(artifactPath))) {
            zip.putNextEntry(new ZipEntry("../escape.class"));
            zip.write(new byte[] {0});
            zip.closeEntry();
        }
        DockerLambdaExecutionBackend unsafeBackend = backend(specification -> localHandle());
        CompletionException unsafe = assertThrows(
                CompletionException.class,
                () -> unsafeBackend
                        .invoke(functionWithArtifact(artifactPath), "{}".getBytes(StandardCharsets.UTF_8))
                        .toCompletableFuture()
                        .join());
        assertEquals(DockerExecutionFailure.COMMAND_FAILED, rootCause(unsafe).failure());
        unsafeBackend.close();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2015-03-31/functions/function/invocations", exchange -> {
            byte[] body = new byte[6 * 1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        DockerLambdaExecutionBackend largeBackend = backend(specification -> localHandle());
        CompletionException large = assertThrows(
                CompletionException.class,
                () -> largeBackend
                        .invoke(function(), "{}".getBytes(StandardCharsets.UTF_8))
                        .toCompletableFuture()
                        .join());
        assertEquals(DockerExecutionFailure.COMMAND_FAILED, rootCause(large).failure());
        largeBackend.close();
    }

    @Test
    void returnsSuccessfulPayloadsAndRejectsNonSuccessResponses() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2015-03-31/functions/function/invocations", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        DockerLambdaExecutionBackend backend = backend(new ContainerRuntime() {
            @Override
            public ContainerHandle start(ContainerSpec specification) {
                return localHandle();
            }
        });

        var result = backend.invoke(function(), "{}".getBytes(StandardCharsets.UTF_8))
                .toCompletableFuture()
                .join();

        assertEquals("{\"ok\":true}", new String(result.payload(), StandardCharsets.UTF_8));
        backend.close();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2015-03-31/functions/function/invocations", exchange -> {
            byte[] body = "bad request".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        DockerLambdaExecutionBackend failingBackend = backend(new ContainerRuntime() {
            @Override
            public ContainerHandle start(ContainerSpec specification) {
                return localHandle();
            }
        });

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> failingBackend
                        .invoke(function(), "{}".getBytes(StandardCharsets.UTF_8))
                        .toCompletableFuture()
                        .join());
        assertEquals(
                DockerExecutionFailure.CONTAINER_EXITED, rootCause(exception).failure());
        failingBackend.close();
    }

    @Test
    void reportsStartupTimeoutAndFunctionTimeoutSeparately() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        DockerLambdaExecutionBackend backend = backend(specification -> new ContainerHandle(
                "container", specification.name(), java.net.URI.create("http://127.0.0.1:" + unusedPort)));
        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> backend.invoke(function(), "{}".getBytes(StandardCharsets.UTF_8))
                        .toCompletableFuture()
                        .join());
        assertEquals(
                DockerExecutionFailure.STARTUP_TIMEOUT, rootCause(exception).failure());
        backend.close();
    }

    @Test
    void rejectsUnsupportedRuntimeAndClosesIdempotently() throws Exception {
        LambdaFunctionSnapshot unsupported = new LambdaFunctionSnapshot(
                "hello",
                "arn",
                "java17",
                "arm64",
                "Handler::handleRequest",
                "role",
                "",
                3,
                128,
                Map.of(),
                "rev",
                Instant.now(),
                artifact());
        DockerLambdaExecutionBackend backend = backend(specification -> localHandle());

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> backend.invoke(unsupported, "{}".getBytes(StandardCharsets.UTF_8))
                        .toCompletableFuture()
                        .join());
        assertEquals(
                DockerExecutionFailure.ARCHITECTURE_MISMATCH,
                rootCause(exception).failure());
        backend.close();
        backend.close();
        CompletionException closed = assertThrows(
                CompletionException.class,
                () -> backend.invoke(function(), "{}".getBytes(StandardCharsets.UTF_8))
                        .toCompletableFuture()
                        .join());
        assertEquals(DockerExecutionFailure.CONTAINER_EXITED, rootCause(closed).failure());
    }

    private LambdaFunctionSnapshot function() throws Exception {
        return function("rev-1");
    }

    private DockerLambdaExecutionBackend backend(ContainerRuntime runtime) {
        return new DockerLambdaExecutionBackend(
                runtime,
                LambdaRuntimeProviderRegistry.of(java.util.List.of(new Java21RuntimeProvider())),
                Duration.ofMillis(100),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    private ContainerHandle localHandle() {
        return new ContainerHandle(
                "container",
                "ares-test",
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private static DockerExecutionException rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (!(current instanceof DockerExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return (DockerExecutionException) current;
    }

    private LambdaFunctionSnapshot function(String revision) throws Exception {
        artifactPath = Files.createTempFile("lambda-", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(artifactPath))) {
            zip.putNextEntry(new ZipEntry("Handler.class"));
            zip.write(new byte[] {0});
            zip.closeEntry();
        }
        return functionWithArtifact(artifactPath, revision);
    }

    private LambdaFunctionSnapshot functionWithArtifact(Path path) throws Exception {
        return functionWithArtifact(path, "rev-1");
    }

    private LambdaFunctionSnapshot functionWithArtifact(Path path, String revision) throws Exception {
        return new LambdaFunctionSnapshot(
                "hello",
                "arn",
                "java21",
                "arm64",
                "Handler::handleRequest",
                "role",
                "",
                3,
                128,
                Map.of(),
                revision,
                Instant.now(),
                new LambdaArtifact(path, Files.size(path), "sha", "base64"));
    }

    private static LambdaArtifact artifact() {
        return new LambdaArtifact(Path.of("artifact.zip"), 1, "sha", "base64");
    }
}
