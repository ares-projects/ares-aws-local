package io.github.aresprojects.local.runtime.service.sqs;

import java.util.Objects;
import java.util.Optional;

/** Receives one available SQS message without depending on an HTTP or JSON representation. */
public final class SqsReceiveMessageCommand {
    public static final int DEFAULT_VISIBILITY_TIMEOUT_SECONDS = 30;
    public static final int MAX_VISIBILITY_TIMEOUT_SECONDS = 43_200;

    private final String queueUrl;
    private final int visibilityTimeoutSeconds;

    public SqsReceiveMessageCommand(String queueUrl) {
        this(queueUrl, DEFAULT_VISIBILITY_TIMEOUT_SECONDS);
    }

    public SqsReceiveMessageCommand(String queueUrl, int visibilityTimeoutSeconds) {
        this.queueUrl = requireText(queueUrl, "queueUrl");
        if (visibilityTimeoutSeconds < 0 || visibilityTimeoutSeconds > MAX_VISIBILITY_TIMEOUT_SECONDS) {
            throw new SqsServiceException(
                    "InvalidParameterValue", "VisibilityTimeout must be between 0 and 43200 seconds");
        }
        this.visibilityTimeoutSeconds = visibilityTimeoutSeconds;
    }

    /** Returns one message, or an empty result when the queue currently has no messages. */
    public Optional<SqsReceivedMessage> execute(SqsQueueStore store) {
        Objects.requireNonNull(store, "store");
        store.findQueue(queueUrl)
                .orElseThrow(() -> new SqsServiceException(
                        "QueueDoesNotExist", "The queue identified by QueueUrl does not exist: " + queueUrl));
        return store.receiveMessage(queueUrl, visibilityTimeoutSeconds);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
