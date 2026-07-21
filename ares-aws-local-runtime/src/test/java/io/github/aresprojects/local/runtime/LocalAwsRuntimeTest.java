package io.github.aresprojects.local.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class LocalAwsRuntimeTest {
    @Test
    void runRegistersShutdownHookStartsAndClosesServer() {
        LocalAwsServerConfig config = LocalAwsServerConfig.defaults();
        LocalAwsServer server = mock(LocalAwsServer.class);
        Supplier<LocalAwsServer> serverFactory = mock();
        when(serverFactory.get()).thenReturn(server);
        Consumer<Thread> hookRegistrar = mock();
        Consumer<InetSocketAddress> startupLogger = mock();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 4566);
        when(server.start()).thenReturn(address);

        LocalAwsRuntime.run(config, serverFactory, hookRegistrar, new CountDownLatch(0), startupLogger);

        verify(hookRegistrar).accept(any(Thread.class));
        verify(server).start();
        verify(server).close();
        verify(startupLogger).accept(address);
    }

    @Test
    void runRestoresInterruptWhenShutdownWaitIsInterrupted() {
        LocalAwsServer server = mock(LocalAwsServer.class);
        when(server.start()).thenReturn(new InetSocketAddress("127.0.0.1", 4566));
        Thread.currentThread().interrupt();
        try {
            LocalAwsRuntime.run(
                    LocalAwsServerConfig.defaults(), () -> server, hook -> {}, new CountDownLatch(1), address -> {});
        } finally {
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
        }

        verify(server).start();
        verify(server).close();
    }

    @Test
    void defaultRegistryDescribesTheCurrentCapabilityBoundary() {
        var response = LocalAwsRuntime.defaultRegistry()
                .handle(mock(io.github.aresprojects.local.runtime.http.AwsRequestContext.class))
                .toCompletableFuture()
                .join();

        assertEquals(404, response.statusCode());
        assertEquals(
                "{\"error\":\"no AWS service adapter matched request\"}",
                new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void runClosesServerWhenStartupFails() {
        LocalAwsServer server = mock(LocalAwsServer.class);
        when(server.start()).thenThrow(new IllegalStateException("port 4566 is unavailable"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> LocalAwsRuntime.run(
                        LocalAwsServerConfig.defaults(),
                        () -> server,
                        hook -> {},
                        new CountDownLatch(0),
                        address -> {}));

        verify(server).close();
    }

    @Test
    void runWaitsForShutdownBeforeReturning() throws Exception {
        LocalAwsServer server = mock(LocalAwsServer.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch shutdown = new CountDownLatch(1);
        when(server.start()).thenAnswer(invocation -> {
            started.countDown();
            return new InetSocketAddress("127.0.0.1", 4566);
        });
        Thread runtimeThread = new Thread(() -> LocalAwsRuntime.run(
                LocalAwsServerConfig.defaults(), () -> server, hook -> {}, shutdown, address -> {}));

        runtimeThread.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(runtimeThread.isAlive());
        shutdown.countDown();
        runtimeThread.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(runtimeThread.isAlive());
        verify(server).close();
    }

    @Test
    void mainKeepsHealthEndpointAvailableUntilInterrupted() throws Exception {
        Thread mainThread = new Thread(() -> LocalAwsRuntime.main(new String[0]));
        mainThread.start();
        try {
            HttpResponse<String> response = waitForHealthEndpoint();
            assertEquals(200, response.statusCode());
        } finally {
            mainThread.interrupt();
            mainThread.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertFalse(mainThread.isAlive());
    }

    private static HttpResponse<String> waitForHealthEndpoint() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:4566/_ares/health"))
                .timeout(java.time.Duration.ofMillis(250))
                .GET()
                .build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException exception) {
                lastFailure = exception;
                Thread.sleep(20);
            }
        }
        throw new IOException("Timed out waiting for the local runtime at 127.0.0.1:4566", lastFailure);
    }
}
