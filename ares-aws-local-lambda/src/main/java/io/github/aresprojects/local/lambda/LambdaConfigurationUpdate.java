package io.github.aresprojects.local.lambda;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Explicitly distinguishes omitted Lambda configuration fields from fields being cleared. */
public record LambdaConfigurationUpdate(
        Optional<String> description,
        Optional<String> handler,
        Optional<String> runtime,
        Optional<String> architecture,
        Optional<String> role,
        Optional<Integer> timeoutSeconds,
        Optional<Integer> memorySizeMb,
        Optional<Map<String, String>> environment) {
    public LambdaConfigurationUpdate {
        description = Objects.requireNonNull(description, "description");
        handler = Objects.requireNonNull(handler, "handler");
        runtime = Objects.requireNonNull(runtime, "runtime");
        architecture = Objects.requireNonNull(architecture, "architecture");
        role = Objects.requireNonNull(role, "role");
        timeoutSeconds = Objects.requireNonNull(timeoutSeconds, "timeoutSeconds");
        memorySizeMb = Objects.requireNonNull(memorySizeMb, "memorySizeMb");
        environment = Objects.requireNonNull(environment, "environment")
                .map(values -> Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    /** Creates an update with no fields selected. */
    public static LambdaConfigurationUpdate empty() {
        return new LambdaConfigurationUpdate(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
