package io.github.aresprojects.local.lambda;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable active Lambda function revision. */
public record LambdaFunctionSnapshot(
        String functionName,
        String functionArn,
        String runtime,
        String architecture,
        String handler,
        String role,
        String description,
        int timeoutSeconds,
        int memorySizeMb,
        Map<String, String> environment,
        String revisionId,
        Instant lastModified,
        LambdaArtifact artifact) {
    public LambdaFunctionSnapshot {
        functionName = required(functionName, "functionName");
        functionArn = required(functionArn, "functionArn");
        runtime = required(runtime, "runtime");
        architecture = required(architecture, "architecture");
        handler = required(handler, "handler");
        role = required(role, "role");
        description = Objects.requireNonNull(description, "description");
        environment =
                Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(environment, "environment")));
        revisionId = required(revisionId, "revisionId");
        lastModified = Objects.requireNonNull(lastModified, "lastModified");
        artifact = Objects.requireNonNull(artifact, "artifact");
        if (timeoutSeconds < 1 || timeoutSeconds > 900) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 900");
        }
        if (memorySizeMb < 128 || memorySizeMb > 10_240) {
            throw new IllegalArgumentException("memorySizeMb must be between 128 and 10240");
        }
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
