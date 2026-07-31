package io.github.aresprojects.local.lambda;

import java.nio.file.Path;
import java.util.Objects;

/** Identifies a validated, process-local Lambda deployment artifact. */
public record LambdaArtifact(Path path, long sizeBytes, String sha256, String sha256Base64) {
    public LambdaArtifact {
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        sha256 = requireText(sha256, "sha256");
        sha256Base64 = requireText(sha256Base64, "sha256Base64");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive; provide a non-empty ZIP artifact");
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
