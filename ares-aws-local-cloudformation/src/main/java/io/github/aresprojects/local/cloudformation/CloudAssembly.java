package io.github.aresprojects.local.cloudformation;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable view of a CDK cloud assembly. */
public record CloudAssembly(Path root, String schemaVersion, List<CloudAssemblyArtifact> artifacts) {
    public CloudAssembly {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
    }

    /** Returns CloudFormation stack artifacts in manifest order. */
    public List<CloudAssemblyArtifact> stacks() {
        return artifacts.stream()
                .filter(CloudAssemblyArtifact::cloudFormationStack)
                .toList();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
