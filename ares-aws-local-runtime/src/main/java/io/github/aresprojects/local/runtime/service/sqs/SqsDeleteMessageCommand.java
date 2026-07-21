package io.github.aresprojects.local.runtime.service.sqs;

import java.util.Objects;

/** Deletes an SQS message without depending on an HTTP or JSON representation. */
public final class SqsDeleteMessageCommand {
    private final String queueUrl;
    private final String receiptHandle;

    public SqsDeleteMessageCommand(String queueUrl, String receiptHandle) {
        this.queueUrl = requireText(queueUrl, "queueUrl");
        this.receiptHandle = requireText(receiptHandle, "receiptHandle");
    }

    /** Deletes the message or reports the documented missing-queue or invalid-handle error. */
    public void execute(SqsQueueStore store) {
        Objects.requireNonNull(store, "store");
        store.findQueue(queueUrl)
                .orElseThrow(() -> new SqsServiceException(
                        "QueueDoesNotExist", "The queue identified by QueueUrl does not exist: " + queueUrl));
        if (!store.deleteMessage(queueUrl, receiptHandle)) {
            throw new SqsServiceException("ReceiptHandleIsInvalid", "The receipt handle is invalid");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
