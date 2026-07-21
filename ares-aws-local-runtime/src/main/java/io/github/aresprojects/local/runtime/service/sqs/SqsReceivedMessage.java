package io.github.aresprojects.local.runtime.service.sqs;

import java.util.Objects;

/** Pairs an SQS message with the receipt handle required to delete it. */
public record SqsReceivedMessage(SqsMessage message, String receiptHandle) {
    public SqsReceivedMessage {
        message = Objects.requireNonNull(message, "message");
        receiptHandle = requireText(receiptHandle, "receiptHandle");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
