package io.github.aresprojects.local.cli.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Describes the first supported local Lambda function configuration. */
public record AresFunctionDescriptor(
        String runtime,
        String architecture,
        String handler,
        AresBuildDescriptor build,
        Map<String, String> environment) {
    public AresFunctionDescriptor {
        environment = environment == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(environment));
    }
}
