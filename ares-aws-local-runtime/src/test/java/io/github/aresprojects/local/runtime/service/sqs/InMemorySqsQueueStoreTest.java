package io.github.aresprojects.local.runtime.service.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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

    @Test
    void receivesMessagesWithReceiptHandlesAndDeletesThem() {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        String url = "http://127.0.0.1:4566/000000000000/orders";
        store.createQueue("orders", url);
        store.sendMessage(url, "hello");

        SqsReceivedMessage received = store.receiveMessage(url, 30).orElseThrow();

        assertEquals("hello", received.message().body());
        assertFalse(received.receiptHandle().isBlank());
        assertTrue(store.receiveMessage(url, 30).isEmpty());
        assertTrue(store.deleteMessage(url, received.receiptHandle()));
        assertTrue(store.receiveMessage(url, 30).isEmpty());
    }

    @Test
    void rejectsUnknownReceiptHandlesAndQueues() {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        String url = "http://127.0.0.1:4566/000000000000/orders";

        assertFalse(store.deleteMessage(url, "missing"));
        assertTrue(store.findQueue(url).isEmpty());
        assertTrue(store.receiveMessage(url, 30).isEmpty());
    }

    @Test
    void makesUnacknowledgedMessagesAvailableAfterVisibilityTimeout() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"));
        InMemorySqsQueueStore store = new InMemorySqsQueueStore(clock);
        String url = "http://127.0.0.1:4566/000000000000/orders";
        store.createQueue("orders", url);
        store.sendMessage(url, "hello");

        SqsReceivedMessage first = store.receiveMessage(url, 10).orElseThrow();
        clock.advance(Duration.ofSeconds(10));
        SqsReceivedMessage second = store.receiveMessage(url, 10).orElseThrow();

        assertEquals("hello", second.message().body());
        assertFalse(first.receiptHandle().equals(second.receiptHandle()));
    }

    @Test
    void atomicallyClaimsDeletesAndReleasesMessageBatches() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"));
        InMemorySqsQueueStore store = new InMemorySqsQueueStore(clock);
        String url = "http://127.0.0.1:4566/000000000000/orders";
        store.createQueue("orders", url);
        store.sendMessage(url, "first");
        store.sendMessage(url, "second");
        store.sendMessage(url, "third");

        List<SqsLeasedMessage> claimed = store.claimMessages(url, 3, 30);

        assertEquals(
                List.of("first", "second", "third"),
                claimed.stream().map(message -> message.message().body()).toList());
        assertEquals(Instant.parse("2026-07-22T00:00:00Z"), claimed.getFirst().sentAt());
        assertEquals(claimed.getFirst().sentAt(), claimed.getFirst().firstReceivedAt());
        assertEquals(1, claimed.getFirst().approximateReceiveCount());
        assertEquals(1, store.deleteMessages(url, List.of(claimed.getFirst().receiptHandle(), "missing")));
        assertEquals(
                2,
                store.releaseMessages(
                        url,
                        claimed.subList(1, 3).stream()
                                .map(SqsLeasedMessage::receiptHandle)
                                .toList()));
        assertEquals(
                "second", store.receiveMessage(url, 30).orElseThrow().message().body());
        assertEquals(
                "third", store.receiveMessage(url, 30).orElseThrow().message().body());
    }

    @Test
    void incrementsReceiveMetadataWhenAnExpiredLeaseIsClaimedAgain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"));
        InMemorySqsQueueStore store = new InMemorySqsQueueStore(clock);
        String url = "http://127.0.0.1:4566/000000000000/orders";
        store.createQueue("orders", url);
        store.sendMessage(url, "hello");

        SqsLeasedMessage first = store.claimMessages(url, 1, 10).getFirst();
        clock.advance(Duration.ofSeconds(10));
        SqsLeasedMessage second = store.claimMessages(url, 1, 10).getFirst();
        assertTrue(store.deleteMessage(url, second.receiptHandle()));

        assertEquals(2, second.approximateReceiveCount());
        assertEquals(first.firstReceivedAt(), second.firstReceivedAt());
        assertNotEquals(first.receiptHandle(), second.receiptHandle());
        assertTrue(store.claimMessages(url, 1, 10).isEmpty());
    }

    @Test
    void validatesBatchLeaseInputsAndHandlesMissingQueues() {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> store.claimMessages("missing", 0, 30));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> store.claimMessages("missing", 11, 30));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> store.claimMessages("missing", 1, -1));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> store.claimMessages("missing", 1, 43_201));
        assertTrue(store.claimMessages("missing", 1, 30).isEmpty());
        assertTrue(store.claimMessages("missing", 10, 43_200).isEmpty());
        assertEquals(0, store.deleteMessages("missing", List.of("receipt")));
        assertEquals(0, store.releaseMessages("missing", List.of("receipt")));
    }

    @Test
    void validatesLeasedMessageMetadata() {
        SqsMessage message = new SqsMessage("id", "body", "md5");

        assertThrows(
                IllegalArgumentException.class,
                () -> new SqsLeasedMessage(message, "", Instant.EPOCH, Instant.EPOCH, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SqsLeasedMessage(message, "receipt", Instant.EPOCH, Instant.EPOCH, 0));
        assertThrows(
                NullPointerException.class,
                () -> new SqsLeasedMessage(null, "receipt", Instant.EPOCH, Instant.EPOCH, 1));
        assertThrows(
                NullPointerException.class, () -> new SqsLeasedMessage(message, "receipt", null, Instant.EPOCH, 1));
        assertThrows(
                NullPointerException.class, () -> new SqsLeasedMessage(message, "receipt", Instant.EPOCH, null, 1));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
