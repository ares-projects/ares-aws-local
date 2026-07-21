package io.github.aresprojects.local.runtime.service.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class InMemorySqsQueueStoreTest {
    @Test
    void createsIdempotentQueueAndStoresMessages() {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        String url = "http://127.0.0.1:4566/000000000000/orders";

        SqsQueue first = store.createQueue("orders", url);
        SqsQueue second = store.createQueue("orders", url);
        Optional<SqsMessage> message = store.sendMessage(url, "hello");

        assertEquals(first, second);
        assertTrue(message.isPresent());
        assertNotEquals("", message.orElseThrow().messageId());
        assertEquals("5d41402abc4b2a76b9719d911017c592", message.orElseThrow().md5OfMessageBody());
    }

    @Test
    void rejectsMessagesForUnknownQueues() {
        Optional<SqsMessage> message =
                new InMemorySqsQueueStore().sendMessage("http://127.0.0.1:4566/000000000000/missing", "hello");

        assertFalse(message.isPresent());
    }
}
