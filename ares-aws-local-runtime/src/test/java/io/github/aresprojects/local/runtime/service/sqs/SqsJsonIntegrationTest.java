package io.github.aresprojects.local.runtime.service.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.runtime.LocalAwsServer;
import io.github.aresprojects.local.runtime.LocalAwsServerConfig;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

class SqsJsonIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void rawHttpCanCreateQueueAndSendMessage() throws Exception {
        try (LocalAwsServer server = server()) {
            InetSocketAddress address = server.start();
            String endpoint = "http://" + address.getHostString() + ":" + address.getPort() + "/";
            HttpResponse<String> created =
                    sendJson(endpoint, "AmazonSQS.CreateQueue", "{\"QueueName\":\"raw-orders\"}");
            String queueUrl = MAPPER.readTree(created.body()).get("QueueUrl").textValue();
            HttpResponse<String> sent = sendJson(
                    endpoint,
                    "AmazonSQS.SendMessage",
                    MAPPER.writeValueAsString(Map.of("QueueUrl", queueUrl, "MessageBody", "hello")));

            assertEquals(200, created.statusCode());
            assertEquals(
                    "application/x-amz-json-1.0",
                    created.headers().firstValue("content-type").orElseThrow());
            assertEquals(200, sent.statusCode());
            assertEquals(
                    "5d41402abc4b2a76b9719d911017c592",
                    MAPPER.readTree(sent.body()).get("MD5OfMessageBody").textValue());
        }
    }

    @Test
    void awsSdkV2CanCreateQueueAndSendMessage() {
        try (LocalAwsServer server = server()) {
            InetSocketAddress address = server.start();
            URI endpoint = URI.create("http://" + address.getHostString() + ":" + address.getPort());
            try (SqsClient client = SqsClient.builder()
                    .endpointOverride(endpoint)
                    .region(Region.US_EAST_1)
                    .credentialsProvider(
                            StaticCredentialsProvider.create(AwsBasicCredentials.create("access-key", "secret-key")))
                    .build()) {
                String queueUrl = client.createQueue(CreateQueueRequest.builder()
                                .queueName("sdk-orders")
                                .build())
                        .queueUrl();
                var response = client.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody("hello from sdk v2")
                        .build());

                assertFalse(queueUrl.isBlank());
                assertEquals("aab3c23d87dbc0b72e6c5ff268c9f011", response.md5OfMessageBody());
                assertFalse(response.messageId().isBlank());
            }
        }
    }

    private static LocalAwsServer server() {
        return new LocalAwsServer(
                new LocalAwsServerConfig("127.0.0.1", 0, 1024 * 1024),
                io.github.aresprojects.local.runtime.service.AwsServiceRegistry.builder()
                        .register(new SqsJsonAdapter(new InMemorySqsQueueStore()))
                        .build());
    }

    private static HttpResponse<String> sendJson(String endpoint, String target, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-amz-json-1.0")
                .header("X-Amz-Target", target)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
