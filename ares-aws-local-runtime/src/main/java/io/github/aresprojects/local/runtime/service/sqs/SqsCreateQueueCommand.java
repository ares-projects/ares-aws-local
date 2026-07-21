package io.github.aresprojects.local.runtime.service.sqs;

import java.util.Objects;

/** Creates or reuses an SQS queue without depending on an HTTP or JSON representation. */
public final class SqsCreateQueueCommand {
    private static final String QUEUE_NAME_PATTERN = "[A-Za-z0-9_-]{1,80}";
    private static final String FIFO_QUEUE_NAME_PATTERN = "[A-Za-z0-9_-]{1,75}\\.fifo";

    private final String queueName;
    private final String queueUrl;

    public SqsCreateQueueCommand(String queueName, String queueUrl) {
        this.queueName = requireText(queueName, "queueName");
        this.queueUrl = requireText(queueUrl, "queueUrl");
    }

    /** Stores the queue and returns the existing queue when this name was already created. */
    public SqsQueue execute(SqsQueueStore store) {
        Objects.requireNonNull(store, "store");
        if (!isValidQueueName(queueName)) {
            throw new SqsServiceException(
                    "InvalidAddress",
                    "QueueName must contain 1 to 80 letters, numbers, hyphens, or underscores; "
                            + "FIFO names may end in .fifo");
        }
        return store.createQueue(queueName, queueUrl);
    }

    private static boolean isValidQueueName(String value) {
        return value.matches(QUEUE_NAME_PATTERN) || value.matches(FIFO_QUEUE_NAME_PATTERN);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }
}
