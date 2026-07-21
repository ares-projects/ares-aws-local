package io.github.aresprojects.local.runtime.service.sqs;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Sends an SQS message without depending on an HTTP or JSON representation. */
public final class SqsSendMessageCommand {
    private static final int MAX_MESSAGE_BYTES = 1_048_576;

    private final String queueUrl;
    private final String messageBody;

    public SqsSendMessageCommand(String queueUrl, String messageBody) {
        this.queueUrl = requireText(queueUrl, "queueUrl");
        this.messageBody = requireText(messageBody, "messageBody");
    }

    /** Validates and stores the message, returning its generated identifier and checksum. */
    public SqsMessage execute(SqsQueueStore store) {
        Objects.requireNonNull(store, "store");
        byte[] messageBytes = messageBody.getBytes(StandardCharsets.UTF_8);
        if (messageBytes.length > MAX_MESSAGE_BYTES) {
            throw new SqsServiceException("InvalidParameterValue", "MessageBody must not exceed 1 MiB in UTF-8");
        }
        if (!hasValidMessageCharacters(messageBody)) {
            throw new SqsServiceException(
                    "InvalidMessageContents", "MessageBody contains characters outside the SQS allowed set");
        }
        return store.sendMessage(queueUrl, messageBody)
                .orElseThrow(() -> new SqsServiceException(
                        "QueueDoesNotExist", "The queue identified by QueueUrl does not exist: " + queueUrl));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    private static boolean hasValidMessageCharacters(String value) {
        return value.codePoints()
                .allMatch(codePoint -> codePoint == 0x9
                        || codePoint == 0xA
                        || codePoint == 0xD
                        || codePoint >= 0x20 && codePoint <= 0xD7FF
                        || codePoint >= 0xE000 && codePoint <= 0xFFFD
                        || codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }
}
