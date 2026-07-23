package io.github.aresprojects.local.runtime.service.sqs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe process-local SQS state used by the default runtime. */
public final class InMemorySqsQueueStore implements SqsQueueStore {
    private static final int MAXIMUM_SOURCE_READ_SIZE = 10;

    private final ConcurrentMap<String, QueueState> queues = new ConcurrentHashMap<>();
    private final Clock clock;

    /** Creates a store using the system UTC clock. */
    public InMemorySqsQueueStore() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a store with an injectable clock for deterministic expiration behavior.
     *
     * @param clock the source of message and lease timestamps
     */
    public InMemorySqsQueueStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SqsQueue createQueue(String queueName, String queueUrl) {
        QueueState candidate = new QueueState(new SqsQueue(queueName, queueUrl));
        return queues.computeIfAbsent(queueUrl, ignored -> candidate).queue();
    }

    @Override
    public Optional<SqsQueue> findQueue(String queueUrl) {
        return Optional.ofNullable(queues.get(queueUrl)).map(QueueState::queue);
    }

    @Override
    public Optional<SqsMessage> sendMessage(String queueUrl, String body) {
        QueueState queue = queues.get(queueUrl);
        if (queue == null) {
            return Optional.empty();
        }
        SqsMessage message = new SqsMessage(UUID.randomUUID().toString(), body, md5(body));
        queue.send(message, clock.instant());
        return Optional.of(message);
    }

    @Override
    public Optional<SqsReceivedMessage> receiveMessage(String queueUrl, int visibilityTimeoutSeconds) {
        return claimMessages(queueUrl, 1, visibilityTimeoutSeconds).stream()
                .findFirst()
                .map(leased -> new SqsReceivedMessage(leased.message(), leased.receiptHandle()));
    }

    @Override
    public List<SqsLeasedMessage> claimMessages(String queueUrl, int maximumMessages, int visibilityTimeoutSeconds) {
        if (maximumMessages < 1 || maximumMessages > MAXIMUM_SOURCE_READ_SIZE) {
            throw new IllegalArgumentException(
                    "maximumMessages must be between 1 and 10 for one SQS source read; received " + maximumMessages);
        }
        if (visibilityTimeoutSeconds < 0 || visibilityTimeoutSeconds > 43_200) {
            throw new IllegalArgumentException(
                    "visibilityTimeoutSeconds must be between 0 and 43200; received " + visibilityTimeoutSeconds);
        }
        QueueState queue = queues.get(queueUrl);
        if (queue == null) {
            return List.of();
        }
        return queue.claim(maximumMessages, visibilityTimeoutSeconds, clock.instant());
    }

    @Override
    public boolean deleteMessage(String queueUrl, String receiptHandle) {
        return deleteMessages(queueUrl, List.of(receiptHandle)) == 1;
    }

    @Override
    public int deleteMessages(String queueUrl, Collection<String> receiptHandles) {
        Objects.requireNonNull(receiptHandles, "receiptHandles");
        QueueState queue = queues.get(queueUrl);
        return queue == null ? 0 : queue.delete(receiptHandles);
    }

    @Override
    public int releaseMessages(String queueUrl, Collection<String> receiptHandles) {
        Objects.requireNonNull(receiptHandles, "receiptHandles");
        QueueState queue = queues.get(queueUrl);
        return queue == null ? 0 : queue.release(receiptHandles);
    }

    private static String md5(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is required for SQS message checksums", exception);
        }
    }

    private static final class QueueState {
        private final SqsQueue queue;
        private final Deque<StoredMessage> available = new ArrayDeque<>();
        private final Map<String, ReceiptState> receipts = new LinkedHashMap<>();

        private QueueState(SqsQueue queue) {
            this.queue = queue;
        }

        private SqsQueue queue() {
            return queue;
        }

        private synchronized void send(SqsMessage message, Instant sentAt) {
            available.addLast(new StoredMessage(message, sentAt));
        }

        private synchronized List<SqsLeasedMessage> claim(
                int maximumMessages, int visibilityTimeoutSeconds, Instant now) {
            requeueExpired(now);
            List<SqsLeasedMessage> claimed = new ArrayList<>(maximumMessages);
            while (claimed.size() < maximumMessages && !available.isEmpty()) {
                StoredMessage stored = available.removeFirst();
                stored.receive(now);
                String receiptHandle = UUID.randomUUID().toString();
                receipts.put(
                        receiptHandle,
                        new ReceiptState(stored, now.plus(Duration.ofSeconds(visibilityTimeoutSeconds))));
                claimed.add(stored.lease(receiptHandle));
            }
            return List.copyOf(claimed);
        }

        private synchronized int delete(Collection<String> receiptHandles) {
            int deleted = 0;
            for (String receiptHandle : receiptHandles) {
                if (receipts.remove(Objects.requireNonNull(receiptHandle, "receiptHandle")) != null) {
                    deleted++;
                }
            }
            return deleted;
        }

        private synchronized int release(Collection<String> receiptHandles) {
            List<StoredMessage> released = new ArrayList<>();
            for (String receiptHandle : receiptHandles) {
                ReceiptState receipt = receipts.remove(Objects.requireNonNull(receiptHandle, "receiptHandle"));
                if (receipt != null) {
                    released.add(receipt.message());
                }
            }
            for (int index = released.size() - 1; index >= 0; index--) {
                available.addFirst(released.get(index));
            }
            return released.size();
        }

        private void requeueExpired(Instant now) {
            var iterator = receipts.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, ReceiptState> entry = iterator.next();
                if (!entry.getValue().visibleAt().isAfter(now)) {
                    available.addLast(entry.getValue().message());
                    iterator.remove();
                }
            }
        }
    }

    private static final class StoredMessage {
        private final SqsMessage message;
        private final Instant sentAt;
        private Instant firstReceivedAt;
        private int receiveCount;

        private StoredMessage(SqsMessage message, Instant sentAt) {
            this.message = message;
            this.sentAt = sentAt;
        }

        private void receive(Instant receivedAt) {
            if (firstReceivedAt == null) {
                firstReceivedAt = receivedAt;
            }
            receiveCount++;
        }

        private SqsLeasedMessage lease(String receiptHandle) {
            return new SqsLeasedMessage(message, receiptHandle, sentAt, firstReceivedAt, receiveCount);
        }
    }

    private record ReceiptState(StoredMessage message, Instant visibleAt) {}
}
