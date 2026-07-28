package io.github.aresprojects.local.cli.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Represents the top-level schema of an {@code ares.yaml} project descriptor. */
public record AresDescriptor(int schemaVersion, Map<String, AresFunctionDescriptor> functions) {
    public AresDescriptor {
        functions = functions == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(functions));
    }
}
