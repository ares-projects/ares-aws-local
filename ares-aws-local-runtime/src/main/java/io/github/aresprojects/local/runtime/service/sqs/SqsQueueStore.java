package io.github.aresprojects.local.runtime.service.sqs;

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
     * @return a received message, or empty when the queue has no messages
     */
    Optional<SqsReceivedMessage> receiveMessage(String queueUrl);

    /**
     * Deletes the message identified by a receipt handle.
     *
     * @param queueUrl the case-sensitive queue URL
     * @param receiptHandle the receipt handle returned by receiveMessage
     * @return whether a message was deleted
     */
    boolean deleteMessage(String queueUrl, String receiptHandle);
}
