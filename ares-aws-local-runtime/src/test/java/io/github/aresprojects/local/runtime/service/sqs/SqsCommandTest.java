package io.github.aresprojects.local.runtime.service.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    }
}
