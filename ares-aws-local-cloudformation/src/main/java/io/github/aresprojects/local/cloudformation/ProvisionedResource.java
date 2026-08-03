package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;

/** Durable-in-process state returned by a resource handler. */
public record ProvisionedResource(
        String logicalId,
        String resourceType,
        String physicalId,
        String referenceValue,
        Map<String, String> attributes,
        JsonNode resolvedProperties) {
    public ProvisionedResource {
        logicalId = requireText(logicalId, "logicalId");
        resourceType = requireText(resourceType, "resourceType");
        physicalId = requireText(physicalId, "physicalId");
        referenceValue = requireText(referenceValue, "referenceValue");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        resolvedProperties =
                Objects.requireNonNull(resolvedProperties, "resolvedProperties").deepCopy();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
