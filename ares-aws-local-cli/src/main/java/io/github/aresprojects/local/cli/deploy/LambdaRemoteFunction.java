package io.github.aresprojects.local.cli.deploy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable subset of Lambda configuration used by local deployment reconciliation. */
public record LambdaRemoteFunction(
        String functionName,
        String runtime,
        String architecture,
        String handler,
        Map<String, String> environment,
        String codeSha256,
        String revisionId) {
    public LambdaRemoteFunction {
        functionName = required(functionName, "functionName");
        runtime = required(runtime, "runtime");
        architecture = required(architecture, "architecture");
        handler = required(handler, "handler");
        environment =
                Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(environment, "environment")));
        codeSha256 = required(codeSha256, "codeSha256");
        revisionId = required(revisionId, "revisionId");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
