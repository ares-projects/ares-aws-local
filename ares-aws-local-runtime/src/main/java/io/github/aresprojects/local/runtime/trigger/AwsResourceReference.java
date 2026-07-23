package io.github.aresprojects.local.runtime.trigger;

import java.util.Objects;

/** Identifies an emulated AWS resource without coupling integrations to its implementation. */
public record AwsResourceReference(String service, String resourceIdentifier) {

    /** Validates that both parts can be used for deterministic trigger matching. */
    public AwsResourceReference {
        service = requireText(service, "service");
        resourceIdentifier = requireText(resourceIdentifier, "resourceIdentifier");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank; provide a stable AWS resource value");
        }
        return value;
    }
}
