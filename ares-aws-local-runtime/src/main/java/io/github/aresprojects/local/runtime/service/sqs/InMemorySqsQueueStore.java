package io.github.aresprojects.local.runtime.service.sqs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe process-local SQS state used by the default runtime. */
public final class InMemorySqsQueueStore implements SqsQueueStore {
    private final ConcurrentMap<String, QueueState> queues = new ConcurrentHashMap<>();

    @Override
    public SqsQueue createQueue(String queueName, String queueUrl) {
        QueueState candidate = new QueueState(new SqsQueue(queueName, queueUrl));
        return queues.computeIfAbsent(queueUrl, ignored -> candidate).queue();
    }

    @Override
    public Optional<SqsMessage> sendMessage(String queueUrl, String body) {
        QueueState queue = queues.get(queueUrl);
        if (queue == null) {
            return Optional.empty();
        }
        SqsMessage message = new SqsMessage(UUID.randomUUID().toString(), body, md5(body));
        queue.messages().add(message);
        return Optional.of(message);
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

    private record QueueState(SqsQueue queue, Queue<SqsMessage> messages) {
        private QueueState(SqsQueue queue) {
            this(queue, new ConcurrentLinkedQueue<>());
        }
    }
}
