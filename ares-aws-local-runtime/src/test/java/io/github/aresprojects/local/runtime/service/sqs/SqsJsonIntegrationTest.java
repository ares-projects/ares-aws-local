package io.github.aresprojects.local.runtime.service.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.lambda.InMemoryLambdaFunctionStore;
import io.github.aresprojects.local.lambda.LambdaExecutionBackend;
import io.github.aresprojects.local.lambda.LambdaFunctionSnapshot;
import io.github.aresprojects.local.lambda.LambdaService;
import io.github.aresprojects.local.lambda.TemporaryLambdaArtifactStore;
import io.github.aresprojects.local.runtime.LocalAwsServer;
import io.github.aresprojects.local.runtime.LocalAwsServerConfig;
import io.github.aresprojects.local.runtime.service.AwsServiceRegistry;
import io.github.aresprojects.local.runtime.service.lambda.LambdaJsonAdapter;
import io.github.aresprojects.local.runtime.trigger.AwsResourceReference;
import io.github.aresprojects.local.runtime.trigger.TriggerEngine;
import io.github.aresprojects.local.runtime.trigger.TriggerMapping;
import io.github.aresprojects.local.runtime.trigger.TriggerRegistry;
import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvocationResult;
import io.github.aresprojects.local.runtime.trigger.sqs.SqsLambdaPollingDriver;
import io.github.aresprojects.local.runtime.trigger.sqs.SqsLambdaTriggerSettings;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Architecture;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
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

            HttpResponse<String> received = sendJson(
                    endpoint, "AmazonSQS.ReceiveMessage", MAPPER.writeValueAsString(Map.of("QueueUrl", queueUrl)));
            String receiptHandle = MAPPER.readTree(received.body())
                    .get("Messages")
                    .get(0)
                    .get("ReceiptHandle")
                    .textValue();
            HttpResponse<String> deleted = sendJson(
                    endpoint,
                    "AmazonSQS.DeleteMessage",
                    MAPPER.writeValueAsString(Map.of("QueueUrl", queueUrl, "ReceiptHandle", receiptHandle)));

            assertEquals(200, received.statusCode());
            assertEquals(
                    "hello",
                    MAPPER.readTree(received.body())
                            .get("Messages")
                            .get(0)
                            .get("Body")
                            .textValue());
            assertEquals(200, deleted.statusCode());
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
                var received = client.receiveMessage(
                        ReceiveMessageRequest.builder().queueUrl(queueUrl).build());
                var message = received.messages().getFirst();
                client.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());

                assertFalse(queueUrl.isBlank());
                assertEquals("aab3c23d87dbc0b72e6c5ff268c9f011", response.md5OfMessageBody());
                assertFalse(response.messageId().isBlank());
                assertEquals("hello from sdk v2", message.body());
            }
        }
    }

    @Test
    void awsSdkV2CanReceiveAgainAfterImmediateVisibilityTimeout() {
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
                                .queueName("sdk-visibility")
                                .build())
                        .queueUrl();
                client.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody("visibility test")
                        .build());

                var first = client.receiveMessage(ReceiveMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .visibilityTimeout(0)
                                .build())
                        .messages()
                        .getFirst();
                var visibleAgain = client.receiveMessage(ReceiveMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .visibilityTimeout(0)
                                .build())
                        .messages()
                        .getFirst();

                client.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(visibleAgain.receiptHandle())
                        .build());

                assertEquals(first.messageId(), visibleAgain.messageId());
                assertNotEquals(first.receiptHandle(), visibleAgain.receiptHandle());
            }
        }
    }

    @Test
    void deployedFunctionReceivesSqsMessagesThroughTheTriggerEngine(@TempDir Path directory) throws Exception {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicReference<byte[]> event = new AtomicReference<>();
        AtomicReference<LambdaFunctionSnapshot> invokedFunction = new AtomicReference<>();
        LambdaExecutionBackend backend = new LambdaExecutionBackend() {
            @Override
            public CompletableFuture<LambdaInvocationResult> invoke(LambdaFunctionSnapshot function, byte[] payload) {
                invokedFunction.set(function);
                event.set(payload);
                invoked.countDown();
                return CompletableFuture.completedFuture(LambdaInvocationResult.success(new byte[0]));
            }

            @Override
            public void invalidate(String functionName, String revisionId) {}
        };
        LambdaService lambdaService = new LambdaService(
                new InMemoryLambdaFunctionStore(),
                new TemporaryLambdaArtifactStore(directory.resolve("artifacts")),
                backend,
                Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC),
                "us-east-1");
        try (LocalAwsServer server = server(store, lambdaService)) {
            InetSocketAddress address = server.start();
            URI endpoint = URI.create("http://" + address.getHostString() + ":" + address.getPort());
            try (LambdaClient lambda = lambdaClient(endpoint);
                    SqsClient client = sdkClient(endpoint)) {
                var deployed = lambda.createFunction(CreateFunctionRequest.builder()
                        .functionName("orders-function")
                        .runtime(Runtime.JAVA21)
                        .architectures(Architecture.ARM64)
                        .handler("example.HelloHandler")
                        .role("arn:aws:iam::000000000000:role/lambda-local")
                        .code(FunctionCode.builder()
                                .zipFile(SdkBytes.fromByteArray(zip("example/HelloHandler.class")))
                                .build())
                        .build());
                String queueUrl = client.createQueue(CreateQueueRequest.builder()
                                .queueName("trigger-orders")
                                .build())
                        .queueUrl();
                client.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody("from sdk to lambda")
                        .build());
                String queueArn = "arn:aws:sqs:us-east-1:000000000000:trigger-orders";
                SqsLambdaPollingDriver driver = new SqsLambdaPollingDriver(store, lambdaService);
                SqsLambdaTriggerSettings settings =
                        SqsLambdaTriggerSettings.defaults(queueUrl, queueArn, "us-east-1", deployed.functionName());
                TriggerRegistry triggers = TriggerRegistry.builder()
                        .registerPollingDriver(driver)
                        .registerMapping(new TriggerMapping(
                                "orders-mapping",
                                driver.driverId(),
                                new AwsResourceReference("sqs", queueArn),
                                new AwsResourceReference("lambda", "orders-function"),
                                true,
                                settings))
                        .build();

                try (TriggerEngine engine = new TriggerEngine(triggers)) {
                    engine.start();
                    assertEquals(true, invoked.await(2, TimeUnit.SECONDS));
                }

                JsonNode record = MAPPER.readTree(event.get()).get("Records").get(0);
                assertEquals(deployed.revisionId(), invokedFunction.get().revisionId());
                assertEquals("from sdk to lambda", record.get("body").textValue());
                assertEquals(queueArn, record.get("eventSourceARN").textValue());
                assertTrue(client.receiveMessage(ReceiveMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .visibilityTimeout(0)
                                .build())
                        .messages()
                        .isEmpty());
            }
        }
    }

    private static LocalAwsServer server() {
        return server(new InMemorySqsQueueStore());
    }

    private static LocalAwsServer server(InMemorySqsQueueStore store) {
        return new LocalAwsServer(
                new LocalAwsServerConfig("127.0.0.1", 0, 1024 * 1024),
                AwsServiceRegistry.builder().register(new SqsJsonAdapter(store)).build());
    }

    private static LocalAwsServer server(InMemorySqsQueueStore store, LambdaService lambdaService) {
        return new LocalAwsServer(
                new LocalAwsServerConfig("127.0.0.1", 0, 10 * 1024 * 1024),
                AwsServiceRegistry.builder()
                        .register(new SqsJsonAdapter(store))
                        .register(new LambdaJsonAdapter(lambdaService))
                        .build());
    }

    private static SqsClient sdkClient(URI endpoint) {
        return SqsClient.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("access-key", "secret-key")))
                .build();
    }

    private static LambdaClient lambdaClient(URI endpoint) {
        return LambdaClient.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("access-key", "secret-key")))
                .build();
    }

    private static byte[] zip(String entryName) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
        return bytes.toByteArray();
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
