package io.github.aresprojects.local.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import io.github.aresprojects.local.runtime.http.AwsRequestHandler;
import io.github.aresprojects.local.runtime.service.AwsServiceAdapter;
import io.github.aresprojects.local.runtime.service.AwsServiceRegistry;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LocalAwsServerTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void healthEndpointRespondsWithoutInvokingHandler() throws Exception {
        AtomicReference<AwsRequestContext> handled = new AtomicReference<>();
        try (LocalAwsServer server = server(adapter("health-test", true, request -> {
            handled.set(request);
            return CompletableFuture.completedFuture(AwsHttpResponse.of(200, new byte[0]));
        }))) {
            InetSocketAddress address = server.start();
            HttpResponse<String> response = send(address, "GET", "/_ares/health", new byte[0]);

            assertEquals(200, response.statusCode());
            assertEquals("{\"status\":\"ok\"}", response.body());
            assertEquals(
                    "application/json",
                    response.headers().firstValue("content-type").orElseThrow());
            assertEquals(null, handled.get());
        }
    }

    @Test
    void handlerReceivesRequestContextAndCanRespondAsynchronously() throws Exception {
        AtomicReference<AwsRequestContext> handled = new AtomicReference<>();
        try (LocalAwsServer server = server(adapter("request-test", true, request -> {
            handled.set(request);
            return CompletableFuture.completedFuture(new AwsHttpResponse(
                    201,
                    java.util.Map.of("x-response", List.of("accepted")),
                    "created".getBytes(StandardCharsets.UTF_8)));
        }))) {
            HttpResponse<String> response = send(
                    server.start(),
                    "POST",
                    "/queues/test?Action=SendMessage",
                    "hello".getBytes(StandardCharsets.UTF_8));

            assertEquals(201, response.statusCode());
            assertEquals("created", response.body());
            assertEquals("accepted", response.headers().firstValue("x-response").orElseThrow());
            assertNotNull(handled.get());
            assertEquals("POST", handled.get().method());
            assertEquals("/queues/test?Action=SendMessage", handled.get().rawTarget());
            assertEquals("hello", new String(handled.get().body(), StandardCharsets.UTF_8));
            assertEquals("example", handled.get().firstHeader("X-Test").orElseThrow());
            assertNotNull(handled.get().requestId());
            assertNotNull(handled.get().receivedAt());
            assertNotNull(handled.get().remoteAddress());
            assertNotNull(handled.get().localAddress());
        }
    }

    @Test
    void unhandledAndFailedRequestsReturnUsefulStatuses() throws Exception {
        try (LocalAwsServer server = server()) {
            HttpResponse<String> response = send(server.start(), "GET", "/unknown", new byte[0]);
            assertEquals(404, response.statusCode());
        }

        try (LocalAwsServer server = server(adapter(
                "failed", true, request -> CompletableFuture.failedFuture(new IllegalStateException("boom"))))) {
            HttpResponse<String> response = send(server.start(), "GET", "/failure", new byte[0]);
            assertEquals(500, response.statusCode());
        }

        try (LocalAwsServer server = server(adapter("synchronous-failure", true, request -> {
            throw new IllegalStateException("synchronous failure");
        }))) {
            HttpResponse<String> response = send(server.start(), "GET", "/failure", new byte[0]);
            assertEquals(500, response.statusCode());
        }

        try (LocalAwsServer server = server(adapter("null-response", true, request -> null))) {
            HttpResponse<String> response = send(server.start(), "GET", "/null", new byte[0]);
            assertEquals(500, response.statusCode());
        }
    }

    @Test
    void oversizedRequestsAreRejectedBeforeHandlerInvocation() throws Exception {
        AtomicReference<AwsRequestContext> handled = new AtomicReference<>();
        LocalAwsServerConfig config = new LocalAwsServerConfig("127.0.0.1", 0, 4);
        try (LocalAwsServer server = new LocalAwsServer(
                config,
                AwsServiceRegistry.builder()
                        .register(adapter("size-test", true, request -> {
                            handled.set(request);
                            return CompletableFuture.completedFuture(AwsHttpResponse.of(200, new byte[0]));
                        }))
                        .build())) {
            HttpResponse<String> response =
                    send(server.start(), "POST", "/too-large", "12345".getBytes(StandardCharsets.UTF_8));
            assertEquals(413, response.statusCode());
            assertEquals(null, handled.get());
        }
    }

    @Test
    void malformedRequestsAreRejected() throws Exception {
        try (LocalAwsServer server = server(adapter(
                "malformed-test",
                true,
                request -> CompletableFuture.completedFuture(AwsHttpResponse.of(200, new byte[0]))))) {
            InetSocketAddress address = server.start();
            try (Socket socket = new Socket(address.getAddress(), address.getPort())) {
                socket.getOutputStream()
                        .write("GET / HTP/1.1\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(response.contains("400"), response);
            }
        }
    }

    @Test
    void lifecycleIsExplicitAndCloseIsIdempotent() {
        LocalAwsServer server = server(adapter(
                "lifecycle-test",
                true,
                request -> CompletableFuture.completedFuture(AwsHttpResponse.of(200, new byte[0]))));
        assertFalse(server.isRunning());
        assertThrows(IllegalStateException.class, server::localAddress);

        server.close();
        assertFalse(server.isRunning());
        assertThrows(IllegalStateException.class, server::start);

        server = server(adapter(
                "lifecycle-test-2",
                true,
                request -> CompletableFuture.completedFuture(AwsHttpResponse.of(200, new byte[0]))));
        server.start();
        assertTrue(server.isRunning());
        assertNotNull(server.localAddress());
        assertThrows(IllegalStateException.class, server::start);

        server.close();
        server.close();
        assertFalse(server.isRunning());
        assertThrows(IllegalStateException.class, server::localAddress);
    }

    private static LocalAwsServer server(AwsServiceAdapter... adapters) {
        AwsServiceRegistry.Builder registry = AwsServiceRegistry.builder();
        for (AwsServiceAdapter adapter : adapters) {
            registry.register(adapter);
        }
        return new LocalAwsServer(new LocalAwsServerConfig("127.0.0.1", 0, 1024 * 1024), registry.build());
    }

    private static AwsServiceAdapter adapter(String name, boolean supports, AwsRequestHandler handler) {
        return new AwsServiceAdapter() {
            @Override
            public String serviceName() {
                return name;
            }

            @Override
            public boolean supports(AwsRequestContext request) {
                return supports;
            }

            @Override
            public java.util.concurrent.CompletionStage<AwsHttpResponse> handle(AwsRequestContext request) {
                return handler.handle(request);
            }
        };
    }

    private static HttpResponse<String> send(InetSocketAddress address, String method, String path, byte[] body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://" + address.getHostString() + ":" + address.getPort() + path))
                .timeout(Duration.ofSeconds(5))
                .header("X-Test", "example")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
