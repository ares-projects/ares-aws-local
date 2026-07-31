package io.github.aresprojects.local.lambda.docker;

import io.github.aresprojects.local.lambda.LambdaExecutionBackend;
import io.github.aresprojects.local.lambda.LambdaFunctionSnapshot;
import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvocationResult;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Runs deployed ZIP artifacts in AWS Java Lambda base images through the local RIE endpoint. */
public final class DockerLambdaExecutionBackend implements LambdaExecutionBackend {
    private static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RESPONSE_BYTES = 6 * 1024 * 1024;

    private final ContainerRuntime containerRuntime;
    private final LambdaRuntimeProviderRegistry providers;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Duration startupTimeout;
    private final Map<String, ManagedContainer> containers = new HashMap<>();
    private boolean closed;

    /** Creates a Docker-backed backend using the pinned Java 21 provider. */
    public DockerLambdaExecutionBackend() {
        this(
                new DockerCliContainerRuntime(),
                LambdaRuntimeProviderRegistry.of(java.util.List.of(new Java21RuntimeProvider())),
                DEFAULT_STARTUP_TIMEOUT,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Injects runtime, providers, timeouts, and executor for lifecycle tests. */
    public DockerLambdaExecutionBackend(
            ContainerRuntime containerRuntime,
            LambdaRuntimeProviderRegistry providers,
            Duration startupTimeout,
            ExecutorService executor) {
        this.containerRuntime = Objects.requireNonNull(containerRuntime, "containerRuntime");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.startupTimeout = requirePositive(startupTimeout, "startupTimeout");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.httpClient = HttpClient.newBuilder().connectTimeout(startupTimeout).build();
    }

    @Override
    public CompletionStage<LambdaInvocationResult> invoke(LambdaFunctionSnapshot function, byte[] payload) {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(payload, "payload");
        byte[] copy = payload.clone();
        synchronized (containers) {
            if (closed) {
                return CompletableFuture.failedFuture(new DockerExecutionException(
                        DockerExecutionFailure.CONTAINER_EXITED, "Lambda execution backend is already closed"));
            }
        }
        return CompletableFuture.supplyAsync(() -> invokeBlocking(function, copy), executor);
    }

    @Override
    public void invalidate(String functionName, String revisionId) {
        Objects.requireNonNull(functionName, "functionName");
        Objects.requireNonNull(revisionId, "revisionId");
        ManagedContainer removed;
        synchronized (containers) {
            removed = containers.remove(functionName);
        }
        if (removed != null) {
            stopAndDelete(removed);
        }
    }

    @Override
    public void close() {
        ArrayList<ManagedContainer> active;
        synchronized (containers) {
            if (closed) {
                return;
            }
            closed = true;
            active = new ArrayList<>(containers.values());
            containers.clear();
        }
        active.forEach(this::stopAndDelete);
        containerRuntime.close();
        executor.close();
    }

    private LambdaInvocationResult invokeBlocking(LambdaFunctionSnapshot function, byte[] payload) {
        ManagedContainer container = containerFor(function);
        URI endpoint = container.handle().endpoint().resolve("/2015-03-31/functions/function/invocations");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(function.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        try {
            return invocationResult(sendWithReadiness(request, container), function);
        } catch (java.net.http.HttpConnectTimeoutException exception) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.STARTUP_TIMEOUT,
                    "Lambda container '" + container.handle().name() + "' did not become ready within "
                            + startupTimeout.toSeconds() + " seconds",
                    exception);
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.FUNCTION_TIMEOUT,
                    "Lambda function '" + function.functionName() + "' exceeded its " + function.timeoutSeconds()
                            + " second timeout",
                    exception);
        } catch (IOException exception) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.CONTAINER_EXITED,
                    "Lambda container for function '" + function.functionName()
                            + "' stopped before returning a response: " + containerRuntime.logs(container.handle()),
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DockerExecutionException(
                    DockerExecutionFailure.INTERRUPTED, "Lambda invocation was interrupted", exception);
        }
    }

    private static LambdaInvocationResult invocationResult(
            HttpResponse<byte[]> response, LambdaFunctionSnapshot function) {
        byte[] responseBody = response.body();
        if (responseBody.length > MAX_RESPONSE_BYTES) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.COMMAND_FAILED,
                    "Lambda function '" + function.functionName() + "' returned more than 6 MiB");
        }
        String functionError =
                response.headers().firstValue("X-Amz-Function-Error").orElse("");
        if (!functionError.isBlank()) {
            return LambdaInvocationResult.functionError(responseBody, functionError);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.CONTAINER_EXITED,
                    "Lambda runtime returned HTTP " + response.statusCode() + " for function '"
                            + function.functionName() + "': " + bounded(responseBody));
        }
        return LambdaInvocationResult.success(responseBody);
    }

    private HttpResponse<byte[]> sendWithReadiness(HttpRequest request, ManagedContainer container)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        while (true) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (ConnectException exception) {
                if (System.nanoTime() >= deadline) {
                    throw new DockerExecutionException(
                            DockerExecutionFailure.STARTUP_TIMEOUT,
                            "Lambda container '" + container.handle().name() + "' did not become ready within "
                                    + startupTimeout.toSeconds() + " seconds: "
                                    + containerRuntime.logs(container.handle()),
                            exception);
                }
                Thread.sleep(50);
            }
        }
    }

    private ManagedContainer containerFor(LambdaFunctionSnapshot function) {
        synchronized (containers) {
            if (closed) {
                throw new DockerExecutionException(
                        DockerExecutionFailure.CONTAINER_EXITED, "Lambda execution backend is already closed");
            }
            ManagedContainer existing = containers.get(function.functionName());
            if (existing != null && existing.revisionId().equals(function.revisionId())) {
                return existing;
            }
            if (existing != null) {
                containers.remove(function.functionName());
                stopAndDelete(existing);
            }
            LambdaRuntimeProvider provider = providers.resolve(function);
            Path taskDirectory = extractArtifact(function);
            String name = "ares-lambda-" + safeName(function.functionName()) + "-"
                    + UUID.randomUUID().toString().substring(0, 8);
            ContainerSpec specification = new ContainerSpec(
                    name,
                    provider.image(function),
                    function.architecture(),
                    function.functionName(),
                    function.handler(),
                    taskDirectory,
                    Map.of(
                            "AWS_LAMBDA_FUNCTION_NAME", function.functionName(),
                            "AWS_LAMBDA_FUNCTION_VERSION", "$LATEST",
                            "AWS_REGION", "us-east-1",
                            "AWS_DEFAULT_REGION", "us-east-1"),
                    Map.of(
                            "ares.local/managed", "true",
                            "ares.local/function", function.functionName(),
                            "ares.local/revision", function.revisionId()));
            try {
                ContainerHandle handle = containerRuntime.start(specification);
                ManagedContainer created = new ManagedContainer(function.revisionId(), taskDirectory, handle);
                containers.put(function.functionName(), created);
                return created;
            } catch (RuntimeException exception) {
                deleteDirectory(taskDirectory);
                throw exception;
            }
        }
    }

    private static Path extractArtifact(LambdaFunctionSnapshot function) {
        try {
            Path directory = Files.createTempDirectory("ares-lambda-task-");
            try (ZipInputStream zip =
                    new ZipInputStream(Files.newInputStream(function.artifact().path()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path destination = directory.resolve(entry.getName()).normalize();
                    if (!destination.startsWith(directory)) {
                        throw new DockerExecutionException(
                                DockerExecutionFailure.COMMAND_FAILED,
                                "Lambda artifact contains an unsafe entry path: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                    } else {
                        Path parent = Objects.requireNonNull(destination.getParent(), "destination parent");
                        Files.createDirectories(parent);
                        Files.copy(zip, destination);
                    }
                }
            }
            return directory;
        } catch (IOException exception) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.COMMAND_FAILED,
                    "Could not extract Lambda artifact for function '" + function.functionName() + "'",
                    exception);
        }
    }

    private void stopAndDelete(ManagedContainer managed) {
        try {
            containerRuntime.stop(managed.handle());
        } finally {
            deleteDirectory(managed.taskDirectory());
        }
    }

    private static void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new DockerExecutionException(
                            DockerExecutionFailure.COMMAND_FAILED,
                            "Could not remove Lambda task directory '" + directory + "'",
                            exception);
                }
            });
        } catch (IOException exception) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.COMMAND_FAILED,
                    "Could not inspect Lambda task directory '" + directory + "'",
                    exception);
        }
    }

    private static String safeName(String functionName) {
        return functionName.replaceAll("[^A-Za-z0-9_.-]", "-");
    }

    private static String bounded(byte[] bytes) {
        String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return value.length() <= 512 ? value : value.substring(0, 512) + "…";
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private record ManagedContainer(String revisionId, Path taskDirectory, ContainerHandle handle) {}
}
