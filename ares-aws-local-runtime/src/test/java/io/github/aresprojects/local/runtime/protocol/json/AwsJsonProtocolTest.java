package io.github.aresprojects.local.runtime.protocol.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AwsJsonProtocolTest {
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 4566);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void recognizesValidAwsJsonRequest() {
        AwsJsonProtocol protocol = new AwsJsonProtocol();

        assertTrue(protocol.supports(request("POST", "/", "AmazonSQS.CreateQueue", "{}")));
    }

    @Test
    void rejectsWrongTransportShape() {
        AwsJsonProtocol protocol = new AwsJsonProtocol();

        assertFalse(protocol.supports(request("GET", "/", "AmazonSQS.CreateQueue", "{}")));
        assertFalse(protocol.supports(request("POST", "/queues", "AmazonSQS.CreateQueue", "{}")));
        assertFalse(protocol.supports(request("POST", "/", "AmazonSQS.CreateQueue", "{}", "application/json")));
        assertFalse(protocol.supports(request("POST", "/", "AmazonSQS", "{}")));
    }

    @Test
    void rejectsNonObjectPayloadsAndInvalidTransportOnDecode() {
        AwsJsonProtocol protocol = new AwsJsonProtocol();

        AwsJsonProtocolException arrayPayload = assertThrows(
                AwsJsonProtocolException.class,
                () -> protocol.decode(request("POST", "/", "AmazonSQS.CreateQueue", "[]")));
        AwsJsonProtocolException wrongTransport = assertThrows(
                AwsJsonProtocolException.class,
                () -> protocol.decode(request("GET", "/", "AmazonSQS.CreateQueue", "{}")));

        assertEquals("InvalidRequest", arrayPayload.errorCode());
        assertEquals("InvalidRequest", wrongTransport.errorCode());
    }

    @Test
    void decodesTargetAndPayload() {
        AwsJsonProtocol protocol = new AwsJsonProtocol();

        AwsJsonRequest decoded =
                protocol.decode(request("POST", "/", "AmazonSQS.CreateQueue", "{\"QueueName\":\"orders\"}"));

        assertEquals("AmazonSQS", decoded.serviceName());
        assertEquals("CreateQueue", decoded.operationName());
        assertEquals("orders", decoded.payload().get("QueueName").textValue());
    }

    @Test
    void acceptsEmptyBodyAsEmptyObject() {
        AwsJsonRequest decoded = new AwsJsonProtocol().decode(request("POST", "/", "AmazonSQS.CreateQueue", ""));

        assertTrue(decoded.payload().isObject());
        assertTrue(decoded.payload().isEmpty());
    }

    @Test
    void reportsMalformedJsonAsProtocolError() {
        AwsJsonProtocolException exception = assertThrows(
                AwsJsonProtocolException.class,
                () -> new AwsJsonProtocol().decode(request("POST", "/", "AmazonSQS.CreateQueue", "{")));

        assertEquals("InvalidRequest", exception.errorCode());
    }

    @Test
    void encodesSuccessAndErrorResponses() throws Exception {
        AwsJsonProtocol protocol = new AwsJsonProtocol();
        AwsRequestContext request = request("POST", "/", "AmazonSQS.CreateQueue", "{}");

        var success = protocol.success(request, Map.of("QueueUrl", "http://localhost/queue"));
        var error = protocol.error(request, 400, "QueueDoesNotExist", "queue not found");

        assertEquals(200, success.statusCode());
        assertEquals(
                "application/x-amz-json-1.0",
                success.headers().get("content-type").getFirst());
        assertEquals(
                request.requestId(), success.headers().get("x-amzn-requestid").getFirst());
        assertEquals(
                "http://localhost/queue",
                MAPPER.readTree(success.body()).get("QueueUrl").textValue());
        assertEquals(400, error.statusCode());
        assertEquals(
                "com.amazonaws.sqs#QueueDoesNotExist",
                MAPPER.readTree(error.body()).get("__type").textValue());
        assertEquals(
                "queue not found", MAPPER.readTree(error.body()).get("message").textValue());

        var namespacedError = protocol.error(request, 500, "custom#Failure", "failed");
        assertEquals(
                "custom#Failure",
                MAPPER.readTree(namespacedError.body()).get("__type").textValue());
    }

    @Test
    void parsesAndValidatesTargetShape() {
        assertEquals(new AwsJsonTarget("AmazonSQS", "CreateQueue"), AwsJsonTarget.parse("AmazonSQS.CreateQueue"));
        assertEquals("CreateQueue", AwsJsonTarget.parse("AmazonSQS.CreateQueue").operationName());

        assertThrows(NullPointerException.class, () -> AwsJsonTarget.parse(null));
        assertThrows(IllegalArgumentException.class, () -> AwsJsonTarget.parse(""));
        assertThrows(IllegalArgumentException.class, () -> AwsJsonTarget.parse(".CreateQueue"));
        assertThrows(IllegalArgumentException.class, () -> AwsJsonTarget.parse("AmazonSQS."));
        assertThrows(IllegalArgumentException.class, () -> AwsJsonTarget.parse("AmazonSQS.Create.Queue"));
    }

    @Test
    void decodedPayloadIsDefensivelyCopied() {
        AwsJsonRequest decoded = new AwsJsonProtocol()
                .decode(request("POST", "/", "AmazonSQS.CreateQueue", "{\"QueueName\":\"orders\"}"));

        JsonNode payload = decoded.payload();
        ((ObjectNode) payload).put("QueueName", "changed");

        assertEquals("orders", decoded.payload().get("QueueName").textValue());
        assertEquals(new AwsJsonTarget("AmazonSQS", "CreateQueue"), decoded.target());
    }

    private static AwsRequestContext request(String method, String target, String operation, String body) {
        return request(method, target, operation, body, AwsJsonProtocol.CONTENT_TYPE);
    }

    private static AwsRequestContext request(
            String method, String target, String operation, String body, String contentType) {
        return new AwsRequestContext(
                "request-id",
                Instant.parse("2026-07-21T00:00:00Z"),
                method,
                "HTTP/1.1",
                target,
                Map.of(
                        "Content-Type", List.of(contentType),
                        "X-Amz-Target", List.of(operation),
                        "Host", List.of("127.0.0.1:4566")),
                body.getBytes(StandardCharsets.UTF_8),
                ADDRESS,
                ADDRESS);
    }
}
