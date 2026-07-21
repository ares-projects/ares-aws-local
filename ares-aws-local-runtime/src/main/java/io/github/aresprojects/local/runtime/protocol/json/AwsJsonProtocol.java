package io.github.aresprojects.local.runtime.protocol.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Decodes and encodes the transport-independent parts of AWS JSON 1.0 requests. */
public final class AwsJsonProtocol {
    public static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    public static final String TARGET_HEADER = "x-amz-target";

    private final ObjectMapper objectMapper;

    public AwsJsonProtocol() {
        this(new ObjectMapper());
    }

    AwsJsonProtocol(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Determines whether the request has the AWS JSON 1.0 transport shape.
     *
     * @param request the immutable request context
     * @return whether the request can be claimed by an AWS JSON adapter
     */
    public boolean supports(AwsRequestContext request) {
        Objects.requireNonNull(request, "request");
        return request.method().equalsIgnoreCase("POST")
                && request.rawTarget().equals("/")
                && request.firstHeader("content-type")
                        .map(AwsJsonProtocol::isJsonContentType)
                        .orElse(false)
                && target(request).isPresent();
    }

    /** Returns the parsed operation target when the request has a valid target header. */
    public Optional<AwsJsonTarget> target(AwsRequestContext request) {
        Objects.requireNonNull(request, "request");
        return request.firstHeader(TARGET_HEADER).flatMap(value -> {
            try {
                return Optional.of(parseTarget(value));
            } catch (AwsJsonProtocolException exception) {
                return Optional.empty();
            }
        });
    }

    /** Decodes a claimed request into an immutable JSON payload. */
    public AwsJsonRequest decode(AwsRequestContext request) {
        Objects.requireNonNull(request, "request");
        if (!supports(request)) {
            throw new AwsJsonProtocolException(
                    "InvalidRequest", "request must be POST / with AWS JSON 1.0 headers and a valid target");
        }
        AwsJsonTarget target = target(request).orElseThrow();
        JsonNode payload = decodePayload(request.body());
        if (!payload.isObject()) {
            throw new AwsJsonProtocolException("InvalidRequest", "AWS JSON request body must be a JSON object");
        }
        return new AwsJsonRequest(target.serviceName(), target.operationName(), payload);
    }

    /** Encodes a successful AWS JSON 1.0 response with the request identity metadata. */
    public AwsHttpResponse success(AwsRequestContext request, JsonNode payload) {
        return response(200, request, Objects.requireNonNull(payload, "payload"));
    }

    /** Encodes a JSON-compatible value as a successful AWS JSON 1.0 response. */
    public AwsHttpResponse success(AwsRequestContext request, Object payload) {
        return success(request, objectMapper.valueToTree(Objects.requireNonNull(payload, "payload")));
    }

    /** Encodes a client or service error using the AWS JSON 1.0 error shape. */
    public AwsHttpResponse error(AwsRequestContext request, int statusCode, String errorCode, String message) {
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(message, "message");
        var payload = objectMapper.createObjectNode();
        payload.put("__type", errorCode.contains("#") ? errorCode : "com.amazonaws.sqs#" + errorCode);
        payload.put("message", message);
        return response(statusCode, request, payload);
    }

    private AwsHttpResponse response(int statusCode, AwsRequestContext request, JsonNode payload) {
        try {
            return new AwsHttpResponse(
                    statusCode,
                    Map.of(
                            "content-type", List.of(CONTENT_TYPE),
                            "x-amzn-requestid", List.of(request.requestId())),
                    objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode AWS JSON response", exception);
        }
    }

    private JsonNode decodePayload(byte[] body) {
        if (body.length == 0) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode payload = objectMapper.readTree(body);
            return payload == null ? objectMapper.createObjectNode() : payload;
        } catch (IOException exception) {
            throw new AwsJsonProtocolException("InvalidRequest", "request body is not valid JSON");
        }
    }

    private AwsJsonTarget parseTarget(String value) {
        try {
            return AwsJsonTarget.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new AwsJsonProtocolException("InvalidRequest", exception.getMessage());
        }
    }

    private static boolean isJsonContentType(String value) {
        String mediaType = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals(CONTENT_TYPE);
    }
}
