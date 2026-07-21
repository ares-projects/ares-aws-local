package io.github.aresprojects.local.runtime.service.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqsJsonAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 4566);

    @Test
    void createsQueueAndReturnsTheSameUrlForRepeatedRequests() throws Exception {
        SqsJsonAdapter adapter = new SqsJsonAdapter(new InMemorySqsQueueStore());
        AwsRequestContext request = request("CreateQueue", "{\"QueueName\":\"orders\"}");

        JsonNode first = body(adapter.handle(request).toCompletableFuture().join());
        JsonNode second = body(adapter.handle(request).toCompletableFuture().join());

        assertEquals(
                "http://127.0.0.1:4566/000000000000/orders",
                first.get("QueueUrl").textValue());
        assertEquals(first.get("QueueUrl"), second.get("QueueUrl"));
    }

    @Test
    void sendsMessageAndReturnsItsDigestAndId() throws Exception {
        SqsJsonAdapter adapter = new SqsJsonAdapter(new InMemorySqsQueueStore());
        String queueUrl = body(adapter.handle(request("CreateQueue", "{\"QueueName\":\"orders\"}"))
                        .toCompletableFuture()
                        .join())
                .get("QueueUrl")
                .textValue();

        JsonNode response = body(
                adapter.handle(request("SendMessage", "{\"QueueUrl\":\"" + queueUrl + "\",\"MessageBody\":\"hello\"}"))
                        .toCompletableFuture()
                        .join());

        assertEquals(
                "5d41402abc4b2a76b9719d911017c592",
                response.get("MD5OfMessageBody").textValue());
        assertFalse(response.get("MessageId").textValue().isBlank());
    }

    @Test
    void returnsAwsShapedErrors() throws Exception {
        SqsJsonAdapter adapter = new SqsJsonAdapter(new InMemorySqsQueueStore());

        var missingQueue = adapter.handle(request(
                        "SendMessage",
                        "{\"QueueUrl\":\"http://127.0.0.1:4566/000000000000/missing\",\"MessageBody\":\"hello\"}"))
                .toCompletableFuture()
                .join();
        var unsupportedField = adapter.handle(request("CreateQueue", "{\"QueueName\":\"orders\",\"Attributes\":{}}"))
                .toCompletableFuture()
                .join();

        assertEquals(400, missingQueue.statusCode());
        assertEquals(
                "QueueDoesNotExist",
                body(missingQueue).get("__type").textValue().substring("com.amazonaws.sqs#".length()));
        assertEquals(400, unsupportedField.statusCode());
        assertEquals(
                "UnsupportedOperation",
                body(unsupportedField).get("__type").textValue().substring("com.amazonaws.sqs#".length()));
    }

    @Test
    void rejectsUnsupportedAndUnknownOperations() throws Exception {
        SqsJsonAdapter adapter = new SqsJsonAdapter(new InMemorySqsQueueStore());

        var unsupportedSendField = adapter.handle(
                        request("SendMessage", "{\"QueueUrl\":\"url\",\"MessageBody\":\"hello\",\"DelaySeconds\":1}"))
                .toCompletableFuture()
                .join();
        var unknownOperation = adapter.handle(request("DeleteQueue", "{}"))
                .toCompletableFuture()
                .join();

        assertEquals("UnsupportedOperation", errorCode(unsupportedSendField));
        assertEquals("UnsupportedOperation", errorCode(unknownOperation));
    }

    @Test
    void rejectsInvalidQueueNamesAndMissingFields() throws Exception {
        SqsJsonAdapter adapter = new SqsJsonAdapter(new InMemorySqsQueueStore());

        var invalidName = adapter.handle(request("CreateQueue", "{\"QueueName\":\"bad name\"}"))
                .toCompletableFuture()
                .join();
        var missingName = adapter.handle(request("CreateQueue", "{}"))
                .toCompletableFuture()
                .join();
        var blankName = adapter.handle(request("CreateQueue", "{\"QueueName\":\"\"}"))
                .toCompletableFuture()
                .join();
        var numericBody = adapter.handle(request("SendMessage", "{\"QueueUrl\":\"url\",\"MessageBody\":123}"))
                .toCompletableFuture()
                .join();

        assertEquals("InvalidAddress", errorCode(invalidName));
        assertEquals("MissingParameter", errorCode(missingName));
        assertEquals("MissingParameter", errorCode(blankName));
        assertEquals("MissingParameter", errorCode(numericBody));
    }

    @Test
    void rejectsInvalidMessageContentsAndOversizedMessages() throws Exception {
        SqsJsonAdapter adapter = new SqsJsonAdapter(new InMemorySqsQueueStore());
        String queueUrl = body(adapter.handle(request("CreateQueue", "{\"QueueName\":\"orders\"}"))
                        .toCompletableFuture()
                        .join())
                .get("QueueUrl")
                .textValue();

        String invalidBody = MAPPER.writeValueAsString(Map.of("QueueUrl", queueUrl, "MessageBody", "bad\u0001"));
        String oversizedBody =
                MAPPER.writeValueAsString(Map.of("QueueUrl", queueUrl, "MessageBody", "x".repeat(1_048_577)));
        var invalidCharacters = adapter.handle(request("SendMessage", invalidBody))
                .toCompletableFuture()
                .join();
        var oversized = adapter.handle(request("SendMessage", oversizedBody))
                .toCompletableFuture()
                .join();

        assertEquals("InvalidMessageContents", errorCode(invalidCharacters));
        assertEquals("InvalidParameterValue", errorCode(oversized));
    }

    @Test
    void rejectsMalformedJsonAndUsesLocalAddressWhenHostIsAbsent() throws Exception {
        SqsJsonAdapter adapter = new SqsJsonAdapter(new InMemorySqsQueueStore());

        var malformed = adapter.handle(requestWithoutHost("CreateQueue", "{"))
                .toCompletableFuture()
                .join();
        var queue = body(adapter.handle(requestWithoutHost("CreateQueue", "{\"QueueName\":\"fallback\"}"))
                        .toCompletableFuture()
                        .join())
                .get("QueueUrl")
                .textValue();

        assertEquals("InvalidRequest", errorCode(malformed));
        assertEquals("http://127.0.0.1:4566/000000000000/fallback", queue);
    }

    @Test
    void onlyClaimsSqsJsonTargets() {
        SqsJsonAdapter adapter = new SqsJsonAdapter(new InMemorySqsQueueStore());

        assertTrue(adapter.supports(request("CreateQueue", "{}")));
        assertFalse(adapter.supports(request("CreateQueue", "{}", "AmazonSNS.CreateTopic")));
    }

    @Test
    void acceptsAllowedUnicodeMessageCharacters() throws Exception {
        SqsJsonAdapter adapter = new SqsJsonAdapter(new InMemorySqsQueueStore());
        String queueUrl = body(adapter.handle(request("CreateQueue", "{\"QueueName\":\"unicode\"}"))
                        .toCompletableFuture()
                        .join())
                .get("QueueUrl")
                .textValue();
        String sendBody =
                MAPPER.writeValueAsString(Map.of("QueueUrl", queueUrl, "MessageBody", "line\n\t\uE000\uD83D\uDE00"));

        var response = adapter.handle(request("SendMessage", sendBody))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.statusCode());
        assertFalse(body(response).get("MessageId").textValue().isBlank());
    }

    @Test
    void valueRecordsRejectMissingIdentityFields() {
        assertThrows(NullPointerException.class, () -> new SqsQueue(null, "url"));
        assertThrows(IllegalArgumentException.class, () -> new SqsQueue("", "url"));
        assertThrows(NullPointerException.class, () -> new SqsMessage(null, "body", "md5"));
        assertThrows(IllegalArgumentException.class, () -> new SqsMessage("id", "body", ""));
    }

    private static JsonNode body(io.github.aresprojects.local.runtime.http.AwsHttpResponse response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static String errorCode(io.github.aresprojects.local.runtime.http.AwsHttpResponse response)
            throws Exception {
        return body(response).get("__type").textValue().substring("com.amazonaws.sqs#".length());
    }

    private static AwsRequestContext request(String operation, String body) {
        return request(operation, body, "AmazonSQS." + operation);
    }

    private static AwsRequestContext request(String operation, String body, String target) {
        return new AwsRequestContext(
                "request-id",
                Instant.parse("2026-07-21T00:00:00Z"),
                "POST",
                "HTTP/1.1",
                "/",
                Map.of(
                        "Content-Type", List.of("application/x-amz-json-1.0"),
                        "X-Amz-Target", List.of(target),
                        "Host", List.of("127.0.0.1:4566")),
                body.getBytes(StandardCharsets.UTF_8),
                ADDRESS,
                ADDRESS);
    }

    private static AwsRequestContext requestWithoutHost(String operation, String body) {
        return new AwsRequestContext(
                "request-id",
                Instant.parse("2026-07-21T00:00:00Z"),
                "POST",
                "HTTP/1.1",
                "/",
                Map.of(
                        "Content-Type", List.of("application/x-amz-json-1.0"),
                        "X-Amz-Target", List.of("AmazonSQS." + operation)),
                body.getBytes(StandardCharsets.UTF_8),
                ADDRESS,
                ADDRESS);
    }
}
