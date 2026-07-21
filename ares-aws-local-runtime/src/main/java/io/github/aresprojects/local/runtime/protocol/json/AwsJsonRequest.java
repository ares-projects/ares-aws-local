package io.github.aresprojects.local.runtime.protocol.json;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Immutable JSON request decoded from an AWS HTTP request context. */
public record AwsJsonRequest(String serviceName, String operationName, JsonNode payload) {
    public AwsJsonRequest {
        serviceName = requireText(serviceName, "serviceName");
        operationName = requireText(operationName, "operationName");
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
    }

    /** Returns a defensive JSON tree copy so service code cannot mutate the decoded request. */
    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }

    /** Returns the target represented by this decoded request. */
    public AwsJsonTarget target() {
        return new AwsJsonTarget(serviceName, operationName);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
