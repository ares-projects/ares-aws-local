package io.github.aresprojects.local.runtime.protocol.json;

import java.util.Objects;

/** Identifies the service and operation carried by an AWS JSON {@code X-Amz-Target} header. */
public record AwsJsonTarget(String serviceName, String operationName) {
    public AwsJsonTarget {
        serviceName = requireText(serviceName, "serviceName");
        operationName = requireText(operationName, "operationName");
    }

    /** Parses the {@code Service.Operation} target form required by AWS JSON protocols. */
    public static AwsJsonTarget parse(String value) {
        Objects.requireNonNull(value, "target");
        int separator = value.indexOf('.');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf('.', separator + 1) >= 0) {
            throw new IllegalArgumentException(
                    "X-Amz-Target must use the Service.Operation form; received '" + value + "'");
        }
        return new AwsJsonTarget(value.substring(0, separator), value.substring(separator + 1));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
