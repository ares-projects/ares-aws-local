package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/** One logical resource declaration from a CloudFormation template. */
public record TemplateResource(
        String logicalId,
        String type,
        JsonNode properties,
        JsonNode metadata,
        List<String> dependsOn,
        String condition,
        int declarationOrder) {
    public TemplateResource {
        logicalId = requireText(logicalId, "logicalId");
        type = requireText(type, "type");
        properties = Objects.requireNonNull(properties, "properties").deepCopy();
        metadata = Objects.requireNonNull(metadata, "metadata").deepCopy();
        dependsOn = List.copyOf(Objects.requireNonNull(dependsOn, "dependsOn"));
        condition = condition == null || condition.isBlank() ? null : condition;
        if (declarationOrder < 0) {
            throw new IllegalArgumentException("declarationOrder must not be negative");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
