package io.github.aresprojects.local.cli.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Holds a validated single-function project and its paths relative to the descriptor. */
public record AresProject(
        Path directory, String functionName, AresFunctionDescriptor function, List<String> environmentNames) {

    public AresProject {
        environmentNames = List.copyOf(environmentNames);
    }

    /** Returns the configured artifact path normalized against the project directory. */
    public Path artifactPath() {
        return directory.resolve(function.build().artifact()).normalize();
    }

    /** Returns the generated deployment metadata path. */
    public Path deploymentPath() {
        return directory.resolve("build/ares/deployment.json").normalize();
    }

    /** Returns a defensive copy of configured environment names without their values. */
    public static List<String> sortedEnvironmentNames(Map<String, String> environment) {
        return environment == null
                ? List.of()
                : environment.keySet().stream().sorted().toList();
    }
}
