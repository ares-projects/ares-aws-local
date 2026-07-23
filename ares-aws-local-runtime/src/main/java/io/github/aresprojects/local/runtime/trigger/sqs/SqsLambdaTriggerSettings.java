package io.github.aresprojects.local.runtime.trigger.sqs;

import io.github.aresprojects.local.runtime.trigger.PollingTriggerSettings;
import java.time.Duration;
import java.util.Objects;

/** Immutable SQS event-source mapping settings for a Lambda target. */
public record SqsLambdaTriggerSettings(
        String queueUrl,
        String queueArn,
        String region,
        String functionName,
        int batchSize,
        Duration batchingWindow,
        int visibilityTimeoutSeconds,
        int maximumConcurrency,
        boolean reportBatchItemFailures)
        implements PollingTriggerSettings {

    /** Validates the supported standard-queue event-source mapping subset. */
    public SqsLambdaTriggerSettings {
        queueUrl = requireText(queueUrl, "queueUrl");
        queueArn = requireText(queueArn, "queueArn");
        region = requireText(region, "region");
        functionName = requireText(functionName, "functionName");
        batchingWindow = Objects.requireNonNull(batchingWindow, "batchingWindow");
        requireRange(batchSize, 1, 10_000, "batchSize");
        requireWholeSeconds(batchingWindow);
        requireRange((int) batchingWindow.toSeconds(), 0, 300, "batchingWindow seconds");
        requireRange(visibilityTimeoutSeconds, 0, 43_200, "visibilityTimeoutSeconds");
        requireRange(maximumConcurrency, 1, 1_000, "maximumConcurrency");
        if (batchSize > 10 && batchingWindow.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(
                    "batchingWindow must be at least PT1S when batchSize exceeds 10; received " + batchingWindow);
        }
        if (queueArn.endsWith(".fifo") || queueUrl.endsWith(".fifo")) {
            throw new IllegalArgumentException(
                    "FIFO SQS event-source mappings are not supported yet; configure a standard queue");
        }
    }

    /**
     * Creates the local defaults matching Lambda's SQS batch and visibility behavior.
     *
     * @param queueUrl local queue URL used to claim messages
     * @param queueArn queue ARN emitted in Lambda event records
     * @param region AWS region emitted in Lambda event records
     * @param functionName function name or ARN passed to the Lambda invoker
     * @return settings with batch size 10, no batching window, 30-second visibility, and concurrency 1
     */
    public static SqsLambdaTriggerSettings defaults(
            String queueUrl, String queueArn, String region, String functionName) {
        return new SqsLambdaTriggerSettings(queueUrl, queueArn, region, functionName, 10, Duration.ZERO, 30, 1, false);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank; provide the mapped SQS or Lambda value");
        }
        return value;
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum + "; received " + value);
        }
    }

    private static void requireWholeSeconds(Duration value) {
        if (value.isNegative() || value.getNano() != 0) {
            throw new IllegalArgumentException(
                    "batchingWindow must be a non-negative whole number of seconds; received " + value);
        }
    }
}
