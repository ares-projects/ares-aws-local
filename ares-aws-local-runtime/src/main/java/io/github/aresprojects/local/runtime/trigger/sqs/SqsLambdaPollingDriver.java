package io.github.aresprojects.local.runtime.trigger.sqs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.runtime.service.sqs.SqsLeasedMessage;
import io.github.aresprojects.local.runtime.service.sqs.SqsQueueStore;
import io.github.aresprojects.local.runtime.trigger.PollingTriggerDriver;
import io.github.aresprojects.local.runtime.trigger.PollingTriggerResult;
import io.github.aresprojects.local.runtime.trigger.TriggerMapping;
import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvocationResult;
import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvoker;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Polls a standard SQS queue and invokes Lambda with AWS-compatible batched event JSON. */
public final class SqsLambdaPollingDriver implements PollingTriggerDriver {
    /** Stable registry identifier for this integration. */
    public static final String DRIVER_ID = "sqs-lambda";

    /** Lambda's synchronous request payload quota. */
    public static final int MAXIMUM_INVOCATION_PAYLOAD_BYTES = 6 * 1024 * 1024;

    private static final byte[] EVENT_PREFIX = "{\"Records\":[".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVENT_SUFFIX = "]}".getBytes(StandardCharsets.UTF_8);
    private static final Duration SOURCE_READ_INTERVAL = Duration.ofMillis(10);
    private static final String LOCAL_SENDER_ID = "000000000000";

    private final SqsQueueStore store;
    private final LambdaInvoker lambdaInvoker;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SqsLambdaBatchWaiter batchWaiter;

    /** Creates a driver with system time and interruptible batching waits. */
    public SqsLambdaPollingDriver(SqsQueueStore store, LambdaInvoker lambdaInvoker) {
        this(store, lambdaInvoker, new ObjectMapper(), Clock.systemUTC(), duration -> Thread.sleep(duration));
    }

    SqsLambdaPollingDriver(
            SqsQueueStore store,
            LambdaInvoker lambdaInvoker,
            ObjectMapper objectMapper,
            Clock clock,
            SqsLambdaBatchWaiter batchWaiter) {
        this.store = Objects.requireNonNull(store, "store");
        this.lambdaInvoker = Objects.requireNonNull(lambdaInvoker, "lambdaInvoker");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.batchWaiter = Objects.requireNonNull(batchWaiter, "batchWaiter");
    }

    @Override
    public String driverId() {
        return DRIVER_ID;
    }

    @Override
    public Class<SqsLambdaTriggerSettings> settingsType() {
        return SqsLambdaTriggerSettings.class;
    }

    @Override
    public void validate(TriggerMapping mapping) {
        SqsLambdaTriggerSettings settings = (SqsLambdaTriggerSettings) mapping.settings();
        if (!mapping.source().service().equals("sqs")
                || !mapping.source().resourceIdentifier().equals(settings.queueArn())) {
            throw new IllegalArgumentException("Trigger mapping '" + mapping.id()
                    + "' must use service 'sqs' and source identifier '" + settings.queueArn() + "'");
        }
        if (!mapping.target().service().equals("lambda")
                || !mapping.target().resourceIdentifier().equals(settings.functionName())) {
            throw new IllegalArgumentException("Trigger mapping '" + mapping.id()
                    + "' must use service 'lambda' and target identifier '" + settings.functionName() + "'");
        }
    }

    @Override
    public CompletionStage<PollingTriggerResult> poll(TriggerMapping mapping) {
        SqsLambdaTriggerSettings settings = (SqsLambdaTriggerSettings) mapping.settings();
        final InvocationBatch batch;
        try {
            batch = collectBatch(settings);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "SQS batch collection was interrupted; claimed messages remain leased", exception));
        }
        if (batch.messages().isEmpty()) {
            return CompletableFuture.completedFuture(PollingTriggerResult.IDLE);
        }
        final CompletionStage<LambdaInvocationResult> invocation;
        try {
            invocation = Objects.requireNonNull(
                    lambdaInvoker.invoke(settings.functionName(), batch.payload()),
                    "Lambda invoker returned a null completion stage for function '" + settings.functionName() + "'");
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return invocation.thenApply(result -> acknowledge(settings, batch.messages(), result));
    }

    private InvocationBatch collectBatch(SqsLambdaTriggerSettings settings) throws InterruptedException {
        List<SqsLeasedMessage> messages = new ArrayList<>(settings.batchSize());
        List<byte[]> records = new ArrayList<>(settings.batchSize());
        int payloadSize = EVENT_PREFIX.length + EVENT_SUFFIX.length;
        Instant deadline = null;
        while (messages.size() < settings.batchSize()) {
            int sourceReadSize = Math.min(10, settings.batchSize() - messages.size());
            List<SqsLeasedMessage> claimed =
                    store.claimMessages(settings.queueUrl(), sourceReadSize, settings.visibilityTimeoutSeconds());
            if (!claimed.isEmpty() && deadline == null) {
                deadline = clock.instant().plus(settings.batchingWindow());
            }
            PayloadAddition addition = addRecords(settings, claimed, messages, records, payloadSize);
            payloadSize = addition.payloadSize();
            if (!addition.overflow().isEmpty()) {
                store.releaseMessages(settings.queueUrl(), receiptHandles(addition.overflow()));
                break;
            }
            if (shouldInvoke(messages, settings)) {
                break;
            }
            if (!awaitNextSourceRead(claimed, deadline)) {
                break;
            }
        }
        return new InvocationBatch(messages, encodeEvent(records, payloadSize));
    }

    private static boolean shouldInvoke(List<SqsLeasedMessage> messages, SqsLambdaTriggerSettings settings) {
        return messages.isEmpty()
                || messages.size() == settings.batchSize()
                || settings.batchingWindow().isZero();
    }

    private boolean awaitNextSourceRead(List<SqsLeasedMessage> claimed, Instant deadline) throws InterruptedException {
        Duration remaining = Duration.between(clock.instant(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            return false;
        }
        if (claimed.isEmpty()) {
            batchWaiter.await(shorter(remaining, SOURCE_READ_INTERVAL));
        }
        return true;
    }

    private PayloadAddition addRecords(
            SqsLambdaTriggerSettings settings,
            List<SqsLeasedMessage> claimed,
            List<SqsLeasedMessage> accepted,
            List<byte[]> records,
            int currentPayloadSize) {
        for (int index = 0; index < claimed.size(); index++) {
            SqsLeasedMessage message = claimed.get(index);
            byte[] record = encodeRecord(settings, message);
            int separatorSize = records.isEmpty() ? 0 : 1;
            if (currentPayloadSize + separatorSize + record.length > MAXIMUM_INVOCATION_PAYLOAD_BYTES) {
                return new PayloadAddition(currentPayloadSize, claimed.subList(index, claimed.size()));
            }
            accepted.add(message);
            records.add(record);
            currentPayloadSize += separatorSize + record.length;
        }
        return new PayloadAddition(currentPayloadSize, List.of());
    }

    private byte[] encodeRecord(SqsLambdaTriggerSettings settings, SqsLeasedMessage leased) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("ApproximateReceiveCount", Integer.toString(leased.approximateReceiveCount()));
        attributes.put("SentTimestamp", Long.toString(leased.sentAt().toEpochMilli()));
        attributes.put("SenderId", LOCAL_SENDER_ID);
        attributes.put(
                "ApproximateFirstReceiveTimestamp",
                Long.toString(leased.firstReceivedAt().toEpochMilli()));

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("messageId", leased.message().messageId());
        record.put("receiptHandle", leased.receiptHandle());
        record.put("body", leased.message().body());
        record.put("attributes", attributes);
        record.put("messageAttributes", Map.of());
        record.put("md5OfBody", leased.message().md5OfMessageBody());
        record.put("eventSource", "aws:sqs");
        record.put("eventSourceARN", settings.queueArn());
        record.put("awsRegion", settings.region());
        try {
            return objectMapper.writeValueAsBytes(record);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not encode SQS message '" + leased.message().messageId() + "' for Lambda", exception);
        }
    }

    private static byte[] encodeEvent(List<byte[]> records, int payloadSize) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(payloadSize);
            output.write(EVENT_PREFIX);
            for (int index = 0; index < records.size(); index++) {
                if (index > 0) {
                    output.write(',');
                }
                output.write(records.get(index));
            }
            output.write(EVENT_SUFFIX);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not assemble the SQS Lambda event payload", exception);
        }
    }

    private PollingTriggerResult acknowledge(
            SqsLambdaTriggerSettings settings, List<SqsLeasedMessage> messages, LambdaInvocationResult result) {
        Objects.requireNonNull(result, "Lambda invocation result");
        if (result.functionError().isPresent()) {
            throw new IllegalStateException(
                    "Lambda function '" + settings.functionName() + "' returned function error '"
                            + result.functionError().orElseThrow() + "'; the entire SQS batch remains leased");
        }
        if (!settings.reportBatchItemFailures()) {
            store.deleteMessages(settings.queueUrl(), receiptHandles(messages));
            return PollingTriggerResult.WORK_PROCESSED;
        }
        Set<String> failedMessageIds = parseFailedMessageIds(result.payload(), messages, settings.functionName());
        List<String> successfulReceipts = messages.stream()
                .filter(message -> !failedMessageIds.contains(message.message().messageId()))
                .map(SqsLeasedMessage::receiptHandle)
                .toList();
        store.deleteMessages(settings.queueUrl(), successfulReceipts);
        return PollingTriggerResult.WORK_PROCESSED;
    }

    private Set<String> parseFailedMessageIds(byte[] payload, List<SqsLeasedMessage> messages, String functionName) {
        JsonNode failures = readFailures(payload, functionName);
        Set<String> knownIds = new HashSet<>();
        messages.forEach(message -> knownIds.add(message.message().messageId()));
        Set<String> failedIds = new HashSet<>();
        for (JsonNode failure : failures) {
            String failedId = readFailureIdentifier(failure, functionName);
            validateFailureIdentifier(failedId, knownIds, failedIds, functionName);
            failedIds.add(failedId);
        }
        return failedIds;
    }

    private JsonNode readFailures(byte[] payload, String functionName) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (IOException exception) {
            throw invalidPartialResponse(functionName, "response is not valid JSON", exception);
        }
        JsonNode failures = root == null ? null : root.get("batchItemFailures");
        if (failures == null || !failures.isArray()) {
            throw invalidPartialResponse(functionName, "response must contain a batchItemFailures array", null);
        }
        return failures;
    }

    private static String readFailureIdentifier(JsonNode failure, String functionName) {
        JsonNode identifier = failure.get("itemIdentifier");
        if (identifier == null
                || !identifier.isTextual()
                || identifier.textValue().isBlank()) {
            throw invalidPartialResponse(
                    functionName, "each batchItemFailures entry must contain a non-blank itemIdentifier", null);
        }
        return identifier.textValue();
    }

    private static void validateFailureIdentifier(
            String failedId, Set<String> knownIds, Set<String> failedIds, String functionName) {
        if (!knownIds.contains(failedId)) {
            throw invalidPartialResponse(
                    functionName, "itemIdentifier '" + failedId + "' does not belong to the invoked batch", null);
        }
        if (failedIds.contains(failedId)) {
            throw invalidPartialResponse(
                    functionName, "itemIdentifier '" + failedId + "' appears more than once", null);
        }
    }

    private static IllegalStateException invalidPartialResponse(String functionName, String reason, Throwable cause) {
        return new IllegalStateException(
                "Lambda function '" + functionName
                        + "' returned an invalid SQS partial batch response: " + reason
                        + "; the entire batch remains leased",
                cause);
    }

    private static Collection<String> receiptHandles(List<SqsLeasedMessage> messages) {
        return messages.stream().map(SqsLeasedMessage::receiptHandle).toList();
    }

    private static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private record PayloadAddition(int payloadSize, List<SqsLeasedMessage> overflow) {}

    private record InvocationBatch(List<SqsLeasedMessage> messages, byte[] payload) {
        private InvocationBatch {
            messages = List.copyOf(messages);
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
