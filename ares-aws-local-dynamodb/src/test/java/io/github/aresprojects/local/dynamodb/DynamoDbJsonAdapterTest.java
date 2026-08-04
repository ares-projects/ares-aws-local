package io.github.aresprojects.local.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;
import software.amazon.dynamodb.services.local.server.DynamoDBProxyServer;

class DynamoDbJsonAdapterTest {

    @Test
    void forwardsDynamoDbJsonRequestsToTheLocalBackend() {
        try (DynamoDbJsonAdapter adapter = new DynamoDbJsonAdapter()) {
            AwsRequestContext request = request("DynamoDB_20120810.ListTables", "{}");
            assertEquals("dynamodb", adapter.serviceName());
            assertTrue(adapter.supports(request));

            CompletionStage<AwsHttpResponse> response = adapter.handle(request);

            assertEquals(200, response.toCompletableFuture().join().statusCode());
        }
    }

    @Test
    void rejectsRequestsOutsideTheDynamoDbJsonBoundary() {
        try (DynamoDbJsonAdapter adapter = new DynamoDbJsonAdapter()) {
            AwsRequestContext wrongTarget = request("AmazonSQS.CreateQueue", "{}");
            AwsRequestContext wrongMethod = request("DynamoDB_20120810.ListTables", "{}", "GET", "/");
            AwsRequestContext wrongPath = request("DynamoDB_20120810.ListTables", "{}", "POST", "/other");
            AwsRequestContext missingOperation = request("DynamoDB_20120810.", "{}");
            AwsRequestContext wrongContentType = new AwsRequestContext(
                    "request-id",
                    Instant.parse("2026-08-04T00:00:00Z"),
                    "POST",
                    "HTTP/1.1",
                    "/",
                    Map.of(
                            "content-type",
                            List.of("application/json"),
                            "x-amz-target",
                            List.of("DynamoDB_20120810.ListTables")),
                    "{}".getBytes(StandardCharsets.UTF_8),
                    new InetSocketAddress("127.0.0.1", 1234),
                    new InetSocketAddress("127.0.0.1", 4566));

            assertFalse(adapter.supports(wrongTarget));
            assertFalse(adapter.supports(wrongMethod));
            assertFalse(adapter.supports(wrongPath));
            assertFalse(adapter.supports(missingOperation));
            assertFalse(adapter.supports(wrongContentType));
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> adapter.handle(wrongTarget).toCompletableFuture().join());
            assertEquals(IllegalArgumentException.class, failure.getCause().getClass());
        }
    }

    @Test
    void rejectsRequestsAfterClose() {
        DynamoDbLocalServer server = new DynamoDbLocalServer();
        server.start();
        DynamoDbJsonAdapter adapter = new DynamoDbJsonAdapter(server);
        adapter.close();
        adapter.close();
        assertThrows(IllegalStateException.class, server::endpoint);

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> adapter.handle(request("DynamoDB_20120810.ListTables", "{}"))
                        .toCompletableFuture()
                        .join());
        assertEquals(
                "Cannot handle DynamoDB requests after the adapter is closed",
                failure.getCause().getMessage());
    }

    @Test
    void acceptsJsonContentTypeParametersAndSkipsTransportHeaders() {
        try (DynamoDbJsonAdapter adapter = new DynamoDbJsonAdapter()) {
            AwsRequestContext request = request("DynamoDB_20120810.ListTables", "{}");
            AwsRequestContext withTransportHeaders = new AwsRequestContext(
                    request.requestId(),
                    request.receivedAt(),
                    request.method(),
                    request.protocolVersion(),
                    request.rawTarget(),
                    Map.of(
                            "content-type",
                            List.of("application/x-amz-json-1.0; charset=utf-8"),
                            "x-amz-target",
                            List.of("DynamoDB_20120810.ListTables"),
                            "authorization",
                            List.of("AWS4-HMAC-SHA256 Credential=accesskey/20260804/us-east-1/dynamodb/"
                                    + "aws4_request, SignedHeaders=host;x-amz-target, Signature=invalid"),
                            "connection",
                            List.of("keep-alive"),
                            "content-length",
                            List.of("2"),
                            "host",
                            List.of("localhost")),
                    request.body(),
                    request.remoteAddress(),
                    request.localAddress());

            assertTrue(adapter.supports(withTransportHeaders));
            assertEquals(
                    200,
                    adapter.handle(withTransportHeaders)
                            .toCompletableFuture()
                            .join()
                            .statusCode());
        }
    }

    @Test
    void handlesRequestsUsingAnAlreadyRunningBackend() {
        try (DynamoDbLocalServer server = new DynamoDbLocalServer();
                DynamoDbJsonAdapter adapter = new DynamoDbJsonAdapter(server)) {
            server.start();

            assertEquals(
                    200,
                    adapter.handle(request("DynamoDB_20120810.ListTables", "{}"))
                            .toCompletableFuture()
                            .join()
                            .statusCode());
        }
    }

    @Test
    void reportsBackendStartupFailure() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            try (DynamoDbJsonAdapter adapter =
                    new DynamoDbJsonAdapter(new DynamoDbLocalServer(socket.getLocalPort()))) {
                CompletionException failure = assertThrows(
                        CompletionException.class,
                        () -> adapter.handle(request("DynamoDB_20120810.ListTables", "{}"))
                                .toCompletableFuture()
                                .join());

                assertEquals(
                        "Could not start the embedded DynamoDB Local backend on port " + socket.getLocalPort(),
                        failure.getCause().getMessage());
            }
        }
    }

    @Test
    void reportsBackendForwardingFailure() throws Exception {
        DynamoDbLocalServer server = new DynamoDbLocalServer();
        try (DynamoDbJsonAdapter adapter = new DynamoDbJsonAdapter(server)) {
            server.start();
            Field proxyField = DynamoDbLocalServer.class.getDeclaredField("proxyServer");
            proxyField.setAccessible(true);
            ((DynamoDBProxyServer) proxyField.get(server)).stop();

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> adapter.handle(request("DynamoDB_20120810.ListTables", "{}"))
                            .toCompletableFuture()
                            .join());
            assertTrue(failure.getCause().getMessage().startsWith("Could not forward DynamoDB request"));
        }
    }

    private static AwsRequestContext request(String target, String body) {
        return request(target, body, "POST", "/");
    }

    private static AwsRequestContext request(String target, String body, String method, String path) {
        return new AwsRequestContext(
                "request-id",
                Instant.parse("2026-08-04T00:00:00Z"),
                method,
                "HTTP/1.1",
                path,
                Map.of(
                        "content-type", List.of("application/x-amz-json-1.0"),
                        "x-amz-target", List.of(target),
                        "authorization",
                                List.of("AWS4-HMAC-SHA256 Credential=accesskey/20260804/us-east-1/dynamodb/"
                                        + "aws4_request, SignedHeaders=host;x-amz-target, Signature=invalid")),
                body.getBytes(StandardCharsets.UTF_8),
                new InetSocketAddress("127.0.0.1", 1234),
                new InetSocketAddress("127.0.0.1", 4566));
    }
}
