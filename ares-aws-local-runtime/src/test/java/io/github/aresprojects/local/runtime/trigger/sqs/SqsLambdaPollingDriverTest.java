package io.github.aresprojects.local.runtime.trigger.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.runtime.service.sqs.InMemorySqsQueueStore;
import io.github.aresprojects.local.runtime.service.sqs.SqsLeasedMessage;
import io.github.aresprojects.local.runtime.trigger.AwsResourceReference;
import io.github.aresprojects.local.runtime.trigger.PollingTriggerResult;
import io.github.aresprojects.local.runtime.trigger.TriggerMapping;
import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvocationResult;
import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvoker;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SqsLambdaPollingDriverTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUEUE_URL = "http://localhost:4566/000000000000/orders";
    private static final String QUEUE_ARN = "arn:aws:sqs:us-east-1:000000000000:orders";
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    @Test
    void emitsCanonicalDeterministicSqsEventAndAcknowledgesTenMessages() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        InMemorySqsQueueStore store = store(clock);
        for (int index = 0; index < 10; index++) {
            store.sendMessage(QUEUE_URL, "message-" + index);
        }
        AtomicReference<byte[]> invocation = new AtomicReference<>();
        LambdaInvoker invoker = (functionName, payload) -> {
            assertEquals("orders-function", functionName);
            invocation.set(payload);
            return CompletableFuture.completedFuture(
                    LambdaInvocationResult.success("{}".getBytes(StandardCharsets.UTF_8)));
        };
        SqsLambdaPollingDriver driver = driver(store, invoker, clock, duration -> {});

        PollingTriggerResult result = driver.poll(mapping(settings(10, Duration.ZERO, 30, 1, false)))
                .toCompletableFuture()
                .join();

        JsonNode root = MAPPER.readTree(invocation.get());
        JsonNode first = root.get("Records").get(0);
        assertEquals(PollingTriggerResult.WORK_PROCESSED, result);
        assertEquals(10, root.get("Records").size());
        assertEquals("message-0", first.get("body").textValue());
        assertFalse(first.get("messageId").textValue().isBlank());
        assertFalse(first.get("receiptHandle").textValue().isBlank());
        assertEquals("1", first.get("attributes").get("ApproximateReceiveCount").textValue());
        assertEquals(
                Long.toString(NOW.toEpochMilli()),
                first.get("attributes").get("SentTimestamp").textValue());
        assertEquals("000000000000", first.get("attributes").get("SenderId").textValue());
        assertEquals(
                Long.toString(NOW.toEpochMilli()),
                first.get("attributes").get("ApproximateFirstReceiveTimestamp").textValue());
        assertTrue(first.get("messageAttributes").isEmpty());
        assertEquals("aws:sqs", first.get("eventSource").textValue());
        assertEquals(QUEUE_ARN, first.get("eventSourceARN").textValue());
        assertEquals("us-east-1", first.get("awsRegion").textValue());
        assertTrue(store.claimMessages(QUEUE_URL, 10, 0).isEmpty());
    }

    @Test
    void returnsIdleWithoutInvokingLambdaWhenTheQueueIsEmpty() {
        InMemorySqsQueueStore store = store(Clock.systemUTC());
        AtomicInteger invocations = new AtomicInteger();
        SqsLambdaPollingDriver driver = new SqsLambdaPollingDriver(store, (functionName, payload) -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(LambdaInvocationResult.success(new byte[0]));
        });

        PollingTriggerResult result =
                driver.poll(mapping(defaultSettings())).toCompletableFuture().join();

        assertEquals(PollingTriggerResult.IDLE, result);
        assertEquals(0, invocations.get());
    }

    @Test
    void waitsForAdditionalMessagesAcrossSourceReads() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        InMemorySqsQueueStore store = store(clock);
        store.sendMessage(QUEUE_URL, "first");
        AtomicInteger waits = new AtomicInteger();
        AtomicReference<byte[]> invocation = new AtomicReference<>();
        SqsLambdaPollingDriver driver = driver(
                store,
                (functionName, payload) -> {
                    invocation.set(payload);
                    return CompletableFuture.completedFuture(LambdaInvocationResult.success(new byte[0]));
                },
                clock,
                duration -> {
                    if (waits.getAndIncrement() == 0) {
                        store.sendMessage(QUEUE_URL, "second");
                    }
                    clock.advance(duration);
                });

        driver.poll(mapping(settings(2, Duration.ofSeconds(1), 30, 1, false)))
                .toCompletableFuture()
                .join();

        assertEquals(2, MAPPER.readTree(invocation.get()).get("Records").size());
        assertTrue(waits.get() >= 1);
    }

    @Test
    void invokesAtBatchingWindowExpiryWithTheAvailableRecords() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        InMemorySqsQueueStore store = store(clock);
        store.sendMessage(QUEUE_URL, "only");
        AtomicReference<byte[]> invocation = new AtomicReference<>();
        SqsLambdaPollingDriver driver = driver(
                store,
                (functionName, payload) -> {
                    invocation.set(payload);
                    return CompletableFuture.completedFuture(LambdaInvocationResult.success(new byte[0]));
                },
                clock,
                clock::advance);

        driver.poll(mapping(settings(10, Duration.ofSeconds(1), 30, 1, false)))
                .toCompletableFuture()
                .join();

        assertEquals(1, MAPPER.readTree(invocation.get()).get("Records").size());
        assertEquals(NOW.plusSeconds(1), clock.instant());
    }

    @Test
    void aggregatesTenThousandRecordsThroughRepeatedSourceReads() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        InMemorySqsQueueStore store = store(clock);
        for (int index = 0; index < 10_000; index++) {
            store.sendMessage(QUEUE_URL, "x");
        }
        AtomicReference<byte[]> invocation = new AtomicReference<>();
        SqsLambdaPollingDriver driver = driver(
                store,
                (functionName, payload) -> {
                    invocation.set(payload);
                    return CompletableFuture.completedFuture(LambdaInvocationResult.success(new byte[0]));
                },
                clock,
                duration -> {
                    throw new AssertionError("A full queue must not wait between source reads");
                });

        driver.poll(mapping(settings(10_000, Duration.ofSeconds(1), 30, 1, false)))
                .toCompletableFuture()
                .join();

        assertEquals(10_000, MAPPER.readTree(invocation.get()).get("Records").size());
    }

    @Test
    void stopsAtSixMibAndReleasesRecordsThatWereNotInvoked() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        InMemorySqsQueueStore store = store(clock);
        String largeBody = "x".repeat(900_000);
        for (int index = 0; index < 7; index++) {
            store.sendMessage(QUEUE_URL, largeBody);
        }
        AtomicReference<byte[]> invocation = new AtomicReference<>();
        SqsLambdaPollingDriver driver = driver(
                store,
                (functionName, payload) -> {
                    invocation.set(payload);
                    return CompletableFuture.completedFuture(LambdaInvocationResult.success(new byte[0]));
                },
                clock,
                duration -> {});

        driver.poll(mapping(settings(10, Duration.ZERO, 30, 1, false)))
                .toCompletableFuture()
                .join();

        assertTrue(invocation.get().length <= SqsLambdaPollingDriver.MAXIMUM_INVOCATION_PAYLOAD_BYTES);
        assertEquals(6, MAPPER.readTree(invocation.get()).get("Records").size());
        assertEquals(1, store.claimMessages(QUEUE_URL, 10, 30).size());
    }

    @Test
    void leavesTheWholeBatchLeasedAfterInvocationOrFunctionFailure() {
        assertWholeBatchFailure((functionName, payload) ->
                CompletableFuture.failedFuture(new IllegalStateException("runtime unavailable")));
        assertWholeBatchFailure((functionName, payload) -> CompletableFuture.completedFuture(
                LambdaInvocationResult.functionError("{}".getBytes(StandardCharsets.UTF_8), "Unhandled")));
    }

    @Test
    void preservesSynchronousInvokerFailuresAndNullStages() {
        MutableClock clock = new MutableClock(NOW);
        InMemorySqsQueueStore store = store(clock);
        store.sendMessage(QUEUE_URL, "message");
        SqsLambdaPollingDriver failed = driver(
                store,
                (functionName, payload) -> {
                    throw new IllegalArgumentException("invalid function");
                },
                clock,
                duration -> {});

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> failed.poll(mapping(settings(1, Duration.ZERO, 10, 1, false)))
                        .toCompletableFuture()
                        .join());
        assertEquals("invalid function", failure.getCause().getMessage());

        clock.advance(Duration.ofSeconds(10));
        SqsLambdaPollingDriver nullStage = driver(store, (functionName, payload) -> null, clock, duration -> {});
        CompletionException nullFailure = assertThrows(
                CompletionException.class,
                () -> nullStage
                        .poll(mapping(settings(1, Duration.ZERO, 10, 1, false)))
                        .toCompletableFuture()
                        .join());
        assertTrue(nullFailure.getCause().getMessage().contains("null completion stage"));
    }

    @Test
    void restoresInterruptWhenBatchCollectionIsInterrupted() {
        MutableClock clock = new MutableClock(NOW);
        InMemorySqsQueueStore store = store(clock);
        store.sendMessage(QUEUE_URL, "message");
        SqsLambdaPollingDriver driver = driver(
                store,
                (functionName, payload) ->
                        CompletableFuture.completedFuture(LambdaInvocationResult.success(new byte[0])),
                clock,
                duration -> {
                    throw new InterruptedException("shutdown");
                });

        try {
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> driver.poll(mapping(settings(2, Duration.ofSeconds(1), 10, 1, false)))
                            .toCompletableFuture()
                            .join());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(failure.getCause().getMessage().contains("claimed messages remain leased"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void deletesOnlySuccessfulRecordsForAValidPartialBatchResponse() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        InMemorySqsQueueStore store = store(clock);
        store.sendMessage(QUEUE_URL, "first");
        store.sendMessage(QUEUE_URL, "failed");
        store.sendMessage(QUEUE_URL, "third");
        AtomicReference<String> failedId = new AtomicReference<>();
        SqsLambdaPollingDriver driver = driver(
                store,
                (functionName, payload) -> {
                    try {
                        JsonNode records = MAPPER.readTree(payload).get("Records");
                        failedId.set(records.get(1).get("messageId").textValue());
                        byte[] response = MAPPER.writeValueAsBytes(java.util.Map.of(
                                "batchItemFailures", List.of(java.util.Map.of("itemIdentifier", failedId.get()))));
                        return CompletableFuture.completedFuture(LambdaInvocationResult.success(response));
                    } catch (java.io.IOException exception) {
                        return CompletableFuture.failedFuture(exception);
                    }
                },
                clock,
                duration -> {});

        PollingTriggerResult result = driver.poll(mapping(settings(3, Duration.ZERO, 10, 1, true)))
                .toCompletableFuture()
                .join();
        assertTrue(store.claimMessages(QUEUE_URL, 10, 10).isEmpty());
        clock.advance(Duration.ofSeconds(10));
        List<SqsLeasedMessage> retried = store.claimMessages(QUEUE_URL, 10, 10);

        assertEquals(PollingTriggerResult.WORK_PROCESSED, result);
        assertEquals(1, retried.size());
        assertEquals(failedId.get(), retried.getFirst().message().messageId());
        assertEquals(2, retried.getFirst().approximateReceiveCount());
    }

    @Test
    void treatsMalformedPartialResponsesAsWholeBatchFailures() throws Exception {
        List<String> responses = new ArrayList<>();
        responses.add("not-json");
        responses.add("{}");
        responses.add("{\"batchItemFailures\":{}}");
        responses.add("{\"batchItemFailures\":[{}]}");
        responses.add("{\"batchItemFailures\":[{\"itemIdentifier\":\"unknown\"}]}");

        for (String response : responses) {
            assertMalformedPartialResponse(response, false);
        }
        assertMalformedPartialResponse(null, true);
    }

    @Test
    void validatesSettingsAndMappingRelationships() {
        SqsLambdaTriggerSettings defaults = defaultSettings();
        assertEquals(10, defaults.batchSize());
        assertEquals(Duration.ZERO, defaults.batchingWindow());
        assertEquals(30, defaults.visibilityTimeoutSeconds());
        assertEquals(1, defaults.maximumConcurrency());
        assertFalse(defaults.reportBatchItemFailures());

        assertInvalidSettings(0, Duration.ZERO, 30, 1);
        assertInvalidSettings(10_001, Duration.ofSeconds(1), 30, 1);
        assertInvalidSettings(11, Duration.ZERO, 30, 1);
        assertInvalidSettings(10, Duration.ofSeconds(301), 30, 1);
        assertInvalidSettings(10, Duration.ofNanos(1), 30, 1);
        assertInvalidSettings(10, Duration.ZERO, -1, 1);
        assertInvalidSettings(10, Duration.ZERO, 43_201, 1);
        assertInvalidSettings(10, Duration.ZERO, 30, 0);
        assertInvalidSettings(10, Duration.ZERO, 30, 1_001);
        assertThrows(
                IllegalArgumentException.class,
                () -> new SqsLambdaTriggerSettings(
                        QUEUE_URL + ".fifo",
                        QUEUE_ARN + ".fifo",
                        "us-east-1",
                        "function",
                        10,
                        Duration.ZERO,
                        30,
                        1,
                        false));

        SqsLambdaPollingDriver driver = new SqsLambdaPollingDriver(
                store(Clock.systemUTC()),
                (functionName, payload) ->
                        CompletableFuture.completedFuture(LambdaInvocationResult.success(new byte[0])));
        assertEquals(SqsLambdaPollingDriver.DRIVER_ID, driver.driverId());
        assertEquals(SqsLambdaTriggerSettings.class, driver.settingsType());
        assertThrows(
                IllegalArgumentException.class,
                () -> driver.validate(new TriggerMapping(
                        "mapping",
                        driver.driverId(),
                        new AwsResourceReference("sns", QUEUE_ARN),
                        new AwsResourceReference("lambda", "orders-function"),
                        true,
                        defaults)));
        assertThrows(
                IllegalArgumentException.class,
                () -> driver.validate(new TriggerMapping(
                        "mapping",
                        driver.driverId(),
                        new AwsResourceReference("sqs", QUEUE_ARN),
                        new AwsResourceReference("sqs", "orders-function"),
                        true,
                        defaults)));
    }

    private static void assertWholeBatchFailure(LambdaInvoker invoker) {
        MutableClock clock = new MutableClock(NOW);
        InMemorySqsQueueStore store = store(clock);
        store.sendMessage(QUEUE_URL, "message");
        SqsLambdaPollingDriver driver = driver(store, invoker, clock, duration -> {});

        assertThrows(
                CompletionException.class,
                () -> driver.poll(mapping(settings(1, Duration.ZERO, 10, 1, false)))
                        .toCompletableFuture()
                        .join());
        assertTrue(store.claimMessages(QUEUE_URL, 1, 10).isEmpty());
        clock.advance(Duration.ofSeconds(10));
        assertEquals(1, store.claimMessages(QUEUE_URL, 1, 10).size());
    }

    private static void assertMalformedPartialResponse(String response, boolean duplicate) throws Exception {
        MutableClock clock = new MutableClock(NOW);
        InMemorySqsQueueStore store = store(clock);
        store.sendMessage(QUEUE_URL, "message");
        AtomicReference<String> invokedId = new AtomicReference<>();
        SqsLambdaPollingDriver driver = driver(
                store,
                (functionName, payload) -> {
                    try {
                        String messageId = MAPPER.readTree(payload)
                                .get("Records")
                                .get(0)
                                .get("messageId")
                                .textValue();
                        invokedId.set(messageId);
                        String body = duplicate
                                ? "{\"batchItemFailures\":[{\"itemIdentifier\":\"" + messageId
                                        + "\"},{\"itemIdentifier\":\"" + messageId + "\"}]}"
                                : response;
                        return CompletableFuture.completedFuture(
                                LambdaInvocationResult.success(body.getBytes(StandardCharsets.UTF_8)));
                    } catch (java.io.IOException exception) {
                        return CompletableFuture.failedFuture(exception);
                    }
                },
                clock,
                duration -> {});

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> driver.poll(mapping(settings(1, Duration.ZERO, 10, 1, true)))
                        .toCompletableFuture()
                        .join());
        assertEquals(IllegalStateException.class, exception.getCause().getClass());
        assertTrue(exception.getCause().getMessage().contains("entire batch remains leased"));
        assertFalse(invokedId.get().isBlank());
        clock.advance(Duration.ofSeconds(10));
        assertEquals(1, store.claimMessages(QUEUE_URL, 1, 10).size());
    }

    private static void assertInvalidSettings(int batchSize, Duration window, int visibilityTimeout, int concurrency) {
        assertThrows(
                IllegalArgumentException.class,
                () -> settings(batchSize, window, visibilityTimeout, concurrency, false));
    }

    private static InMemorySqsQueueStore store(Clock clock) {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore(clock);
        store.createQueue("orders", QUEUE_URL);
        return store;
    }

    private static SqsLambdaPollingDriver driver(
            InMemorySqsQueueStore store, LambdaInvoker invoker, Clock clock, SqsLambdaBatchWaiter waiter) {
        return new SqsLambdaPollingDriver(store, invoker, MAPPER, clock, waiter);
    }

    private static TriggerMapping mapping(SqsLambdaTriggerSettings settings) {
        return new TriggerMapping(
                "orders-mapping",
                SqsLambdaPollingDriver.DRIVER_ID,
                new AwsResourceReference("sqs", QUEUE_ARN),
                new AwsResourceReference("lambda", "orders-function"),
                true,
                settings);
    }

    private static SqsLambdaTriggerSettings defaultSettings() {
        return SqsLambdaTriggerSettings.defaults(QUEUE_URL, QUEUE_ARN, "us-east-1", "orders-function");
    }

    private static SqsLambdaTriggerSettings settings(
            int batchSize,
            Duration batchingWindow,
            int visibilityTimeout,
            int maximumConcurrency,
            boolean reportBatchItemFailures) {
        return new SqsLambdaTriggerSettings(
                QUEUE_URL,
                QUEUE_ARN,
                "us-east-1",
                "orders-function",
                batchSize,
                batchingWindow,
                visibilityTimeout,
                maximumConcurrency,
                reportBatchItemFailures);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
