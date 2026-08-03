package io.github.aresprojects.local.runtime.service.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SqsCommandTest {
    @Test
    void createsStandardAndFifoQueuesThroughTheStoreBoundary() {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();

        SqsQueue standard = new SqsCreateQueueCommand("orders", "http://localhost/orders").execute(store);
        SqsQueue fifo = new SqsCreateQueueCommand("orders.fifo", "http://localhost/orders.fifo").execute(store);

        assertEquals("orders", standard.queueName());
        assertEquals("orders.fifo", fifo.queueName());
    }

    @Test
    void rejectsQueueNamesOutsideTheSqsDocumentedShape() {
        SqsServiceException exception = assertThrows(
                SqsServiceException.class,
                () -> new SqsCreateQueueCommand("orders.fifo.extra", "http://localhost/orders")
                        .execute(new InMemorySqsQueueStore()));

        assertEquals("InvalidAddress", exception.errorCode());
    }

    @Test
    void sendsMessagesWithoutAnHttpOrJsonDependency() {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        new SqsCreateQueueCommand("orders", "http://localhost/orders").execute(store);

        SqsMessage message = new SqsSendMessageCommand("http://localhost/orders", "hello").execute(store);

        assertEquals("hello", message.body());
        assertEquals("5d41402abc4b2a76b9719d911017c592", message.md5OfMessageBody());
    }

    @Test
    void validatesVisibilityTimeoutRange() {
        assertThrows(SqsServiceException.class, () -> new SqsReceiveMessageCommand("url", -1));
        assertThrows(SqsServiceException.class, () -> new SqsReceiveMessageCommand("url", 43_201));
        new SqsReceiveMessageCommand("url", 0);
        new SqsReceiveMessageCommand("url", 43_200);
    }

    @Test
    void acceptsTheDocumentedMessageCharacterAndSizeBoundaries() {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        new SqsCreateQueueCommand("orders", "http://localhost/orders").execute(store);
        String prefix =
                new String(new char[] {(char) 0x9, (char) 0xA, (char) 0xD, (char) 0xD7FF, (char) 0xE000, (char) 0xFFFD})
                        + new String(Character.toChars(0x10000))
                        + new String(Character.toChars(0x10FFFF));
        String valid = prefix + "x".repeat(1_048_576 - prefix.getBytes(StandardCharsets.UTF_8).length);

        SqsMessage message = new SqsSendMessageCommand("http://localhost/orders", valid).execute(store);

        assertEquals(valid, message.body());
        assertThrows(
                SqsServiceException.class,
                () -> new SqsSendMessageCommand("http://localhost/orders", "x".repeat(1_048_577)).execute(store));
        assertThrows(
                SqsServiceException.class,
                () -> new SqsSendMessageCommand("http://localhost/orders", String.valueOf((char) 0x8)).execute(store));
    }
}
