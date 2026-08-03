package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable manifest entry for one cloud assembly artifact. */
public record CloudAssemblyArtifact(
        String id, String type, Path templateFile, String stackName, List<String> dependencies, JsonNode properties) {
    public CloudAssemblyArtifact {
        id = requireText(id, "id");
        type = requireText(type, "type");
        templateFile = Objects.requireNonNull(templateFile, "templateFile").normalize();
        stackName = stackName == null || stackName.isBlank() ? id : stackName;
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        properties = Objects.requireNonNull(properties, "properties").deepCopy();
    }

    public boolean cloudFormationStack() {
        return "aws:cloudformation:stack".equals(type);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
