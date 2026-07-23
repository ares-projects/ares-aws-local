package io.github.aresprojects.local.runtime.service.sqs;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Owns SQS queue and message state independently from protocol and transport code. */
public interface SqsQueueStore {

    /**
     * Creates a queue or returns the existing queue with the same URL.
     *
     * @param queueName the case-sensitive queue name
     * @param queueUrl the case-sensitive queue URL
     * @return the existing or newly created queue
     */
    SqsQueue createQueue(String queueName, String queueUrl);

    /**
     * Finds a queue by its URL.
     *
     * @param queueUrl the case-sensitive queue URL
     * @return the queue, or empty when it does not exist
     */
    Optional<SqsQueue> findQueue(String queueUrl);

    /**
     * Appends a message to an existing queue.
     *
     * @param queueUrl the case-sensitive queue URL
     * @param body the message body
     * @return the stored message, or empty when the queue does not exist
     */
    Optional<SqsMessage> sendMessage(String queueUrl, String body);

    /**
     * Returns the next available message and a receipt handle.
     *
     * @param queueUrl the case-sensitive queue URL
     * @param visibilityTimeoutSeconds how long the message remains unavailable
     * @return a received message, or empty when the queue has no available messages
     */
    Optional<SqsReceivedMessage> receiveMessage(String queueUrl, int visibilityTimeoutSeconds);

    /**
     * Atomically claims up to the requested number of currently available messages.
     *
     * @param queueUrl the case-sensitive queue URL
     * @param maximumMessages a value from 1 through 10, matching one SQS source read
     * @param visibilityTimeoutSeconds how long claimed messages remain unavailable
     * @return claimed messages in source order, or an empty list when none are available
     */
    List<SqsLeasedMessage> claimMessages(String queueUrl, int maximumMessages, int visibilityTimeoutSeconds);

    /**
     * Deletes the message identified by a receipt handle.
     *
     * @param queueUrl the case-sensitive queue URL
     * @param receiptHandle the receipt handle returned by receiveMessage
     * @return whether a message was deleted
     */
    boolean deleteMessage(String queueUrl, String receiptHandle);

    /**
     * Deletes every message whose current receipt handle is supplied.
     *
     * @param queueUrl the case-sensitive queue URL
     * @param receiptHandles current receipt handles to acknowledge
     * @return the number of messages deleted
     */
    int deleteMessages(String queueUrl, Collection<String> receiptHandles);

    /**
     * Makes claimed but uninvoked records immediately available to another consumer.
     *
     * @param queueUrl the case-sensitive queue URL
     * @param receiptHandles current receipt handles to release
     * @return the number of messages released
     */
    int releaseMessages(String queueUrl, Collection<String> receiptHandles);
}
