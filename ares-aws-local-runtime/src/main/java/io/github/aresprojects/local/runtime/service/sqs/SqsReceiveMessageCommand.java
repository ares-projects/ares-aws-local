package io.github.aresprojects.local.runtime.service.sqs;

import java.util.Objects;
import java.util.Optional;

/** Receives one available SQS message without depending on an HTTP or JSON representation. */
public final class SqsReceiveMessageCommand {
    private final String queueUrl;

    public SqsReceiveMessageCommand(String queueUrl) {
        this.queueUrl = requireText(queueUrl, "queueUrl");
    }

    /** Returns one message, or an empty result when the queue currently has no messages. */
    public Optional<SqsReceivedMessage> execute(SqsQueueStore store) {
        Objects.requireNonNull(store, "store");
        store.findQueue(queueUrl)
                .orElseThrow(() -> new SqsServiceException(
                        "QueueDoesNotExist", "The queue identified by QueueUrl does not exist: " + queueUrl));
        return store.receiveMessage(queueUrl);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
