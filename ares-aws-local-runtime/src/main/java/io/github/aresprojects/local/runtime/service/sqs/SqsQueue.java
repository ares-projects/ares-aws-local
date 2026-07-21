package io.github.aresprojects.local.runtime.service.sqs;

import java.util.Objects;

/** Identifies an in-memory SQS queue. */
public record SqsQueue(String queueName, String queueUrl) {
    public SqsQueue {
        queueName = requireText(queueName, "queueName");
        queueUrl = requireText(queueUrl, "queueUrl");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
