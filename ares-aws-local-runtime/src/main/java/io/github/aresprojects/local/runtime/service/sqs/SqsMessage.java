package io.github.aresprojects.local.runtime.service.sqs;

import java.util.Objects;

/** Stores the message values needed by SQS send responses and future receive operations. */
public record SqsMessage(String messageId, String body, String md5OfMessageBody) {
    public SqsMessage {
        messageId = requireText(messageId, "messageId");
        body = Objects.requireNonNull(body, "body");
        md5OfMessageBody = requireText(md5OfMessageBody, "md5OfMessageBody");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
