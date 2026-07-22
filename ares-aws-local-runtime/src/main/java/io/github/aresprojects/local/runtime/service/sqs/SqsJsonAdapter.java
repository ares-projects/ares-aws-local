package io.github.aresprojects.local.runtime.service.sqs;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import io.github.aresprojects.local.runtime.protocol.json.AwsJsonProtocol;
import io.github.aresprojects.local.runtime.protocol.json.AwsJsonProtocolException;
import io.github.aresprojects.local.runtime.protocol.json.AwsJsonRequest;
import io.github.aresprojects.local.runtime.service.AwsServiceAdapter;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Implements the SQS operations currently exposed through AWS JSON 1.0. */
public final class SqsJsonAdapter implements AwsServiceAdapter {
    private static final String AWS_SERVICE_NAME = "AmazonSQS";
    private static final String AWS_ACCOUNT_ID = "000000000000";
    private static final Set<String> CREATE_QUEUE_FIELDS = Set.of("QueueName");
    private static final Set<String> SEND_MESSAGE_FIELDS = Set.of("QueueUrl", "MessageBody");
    private static final Set<String> RECEIVE_MESSAGE_FIELDS = Set.of("QueueUrl", "VisibilityTimeout");
    private static final Set<String> DELETE_MESSAGE_FIELDS = Set.of("QueueUrl", "ReceiptHandle");

    private final SqsQueueStore store;
    private final AwsJsonProtocol protocol;

    public SqsJsonAdapter(SqsQueueStore store) {
        this(store, new AwsJsonProtocol());
    }

    SqsJsonAdapter(SqsQueueStore store, AwsJsonProtocol protocol) {
        this.store = Objects.requireNonNull(store, "store");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
    }

    @Override
    public String serviceName() {
        return "sqs";
    }

    @Override
    public boolean supports(AwsRequestContext request) {
        return protocol.supports(request)
                && protocol.target(request)
                        .map(target -> target.serviceName().equals(AWS_SERVICE_NAME))
                        .orElse(false);
    }

    @Override
    public CompletionStage<AwsHttpResponse> handle(AwsRequestContext request) {
        try {
            AwsJsonRequest decoded = protocol.decode(request);
            return CompletableFuture.completedFuture(dispatch(request, decoded));
        } catch (AwsJsonProtocolException exception) {
            return CompletableFuture.completedFuture(
                    protocol.error(request, 400, exception.errorCode(), exception.getMessage()));
        } catch (SqsServiceException exception) {
            return CompletableFuture.completedFuture(
                    protocol.error(request, 400, exception.errorCode(), exception.getMessage()));
        }
    }

    private AwsHttpResponse dispatch(AwsRequestContext request, AwsJsonRequest decoded) {
        return switch (decoded.operationName()) {
            case "CreateQueue" -> createQueue(request, decoded.payload());
            case "SendMessage" -> sendMessage(request, decoded.payload());
            case "ReceiveMessage" -> receiveMessage(request, decoded.payload());
            case "DeleteMessage" -> deleteMessage(request, decoded.payload());
            default ->
                protocol.error(
                        request,
                        400,
                        "UnsupportedOperation",
                        "SQS operation is not implemented: " + decoded.operationName());
        };
    }

    private AwsHttpResponse createQueue(AwsRequestContext request, JsonNode payload) {
        rejectUnexpectedFields(payload, CREATE_QUEUE_FIELDS);
        String queueName = requiredText(payload, "QueueName");
        String queueUrl = queueUrl(request, queueName);
        SqsQueue queue = new SqsCreateQueueCommand(queueName, queueUrl).execute(store);
        return protocol.success(request, Map.of("QueueUrl", queue.queueUrl()));
    }

    private AwsHttpResponse sendMessage(AwsRequestContext request, JsonNode payload) {
        rejectUnexpectedFields(payload, SEND_MESSAGE_FIELDS);
        String queueUrl = requiredText(payload, "QueueUrl");
        String messageBody = requiredText(payload, "MessageBody");
        SqsMessage message = new SqsSendMessageCommand(queueUrl, messageBody).execute(store);
        return protocol.success(
                request, Map.of("MD5OfMessageBody", message.md5OfMessageBody(), "MessageId", message.messageId()));
    }

    private AwsHttpResponse receiveMessage(AwsRequestContext request, JsonNode payload) {
        rejectUnexpectedFields(payload, RECEIVE_MESSAGE_FIELDS);
        String queueUrl = requiredText(payload, "QueueUrl");
        int visibilityTimeout = optionalInteger(
                payload, "VisibilityTimeout", SqsReceiveMessageCommand.DEFAULT_VISIBILITY_TIMEOUT_SECONDS);
        SqsReceivedMessage received = new SqsReceiveMessageCommand(queueUrl, visibilityTimeout)
                .execute(store)
                .orElse(null);
        if (received == null) {
            return protocol.success(request, Map.of("Messages", java.util.List.of()));
        }
        SqsMessage message = received.message();
        Map<String, String> responseMessage = Map.of(
                "MessageId", message.messageId(),
                "ReceiptHandle", received.receiptHandle(),
                "MD5OfBody", message.md5OfMessageBody(),
                "Body", message.body());
        return protocol.success(request, Map.of("Messages", java.util.List.of(responseMessage)));
    }

    private AwsHttpResponse deleteMessage(AwsRequestContext request, JsonNode payload) {
        rejectUnexpectedFields(payload, DELETE_MESSAGE_FIELDS);
        String queueUrl = requiredText(payload, "QueueUrl");
        String receiptHandle = requiredText(payload, "ReceiptHandle");
        new SqsDeleteMessageCommand(queueUrl, receiptHandle).execute(store);
        return protocol.success(request, Map.of());
    }

    private static String requiredText(JsonNode payload, String fieldName) {
        JsonNode value = payload.get(fieldName);
        if (value == null
                || value.isNull()
                || !value.isTextual()
                || value.textValue().isEmpty()) {
            throw new SqsServiceException("MissingParameter", "The request must contain parameter " + fieldName);
        }
        return value.textValue();
    }

    private static int optionalInteger(JsonNode payload, String fieldName, int defaultValue) {
        JsonNode value = payload.get(fieldName);
        if (value == null) {
            return defaultValue;
        }
        if (!value.canConvertToInt()) {
            throw new SqsServiceException("InvalidParameterValue", fieldName + " must be an integer");
        }
        return value.intValue();
    }

    private static void rejectUnexpectedFields(JsonNode payload, Set<String> allowedFields) {
        Set<String> fields = new HashSet<>();
        payload.fieldNames().forEachRemaining(fields::add);
        fields.removeAll(allowedFields);
        if (!fields.isEmpty()) {
            throw new SqsServiceException(
                    "UnsupportedOperation", "Request fields are not supported yet: " + String.join(", ", fields));
        }
    }

    private static String queueUrl(AwsRequestContext request, String queueName) {
        String authority = request.firstHeader("host").orElseGet(() -> authority(request.localAddress()));
        return "http://" + authority + "/" + AWS_ACCOUNT_ID + "/" + queueName;
    }

    private static String authority(InetSocketAddress address) {
        String host = address.getHostString();
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return host + ":" + address.getPort();
    }
}
