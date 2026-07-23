package io.github.aresprojects.local.runtime.service.sqs;

import java.time.Instant;
import java.util.Objects;

/** Captures the SQS delivery metadata needed by event-source integrations. */
public record SqsLeasedMessage(
        SqsMessage message,
        String receiptHandle,
        Instant sentAt,
        Instant firstReceivedAt,
        int approximateReceiveCount) {

    /** Validates metadata captured atomically when the message is claimed. */
    public SqsLeasedMessage {
        message = Objects.requireNonNull(message, "message");
        receiptHandle = requireText(receiptHandle, "receiptHandle");
        sentAt = Objects.requireNonNull(sentAt, "sentAt");
        firstReceivedAt = Objects.requireNonNull(firstReceivedAt, "firstReceivedAt");
        if (approximateReceiveCount < 1) {
            throw new IllegalArgumentException(
                    "approximateReceiveCount must be positive; claim the message before constructing its lease");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank; provide the active SQS receipt handle");
        }
        return value;
    }
}
