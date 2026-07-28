package io.github.aresprojects.local.cli.builder;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable generated metadata consumed by later local deployment milestones. */
public record DeploymentResult(
        int schemaVersion,
        String functionName,
        String runtime,
        String architecture,
        String handler,
        Path sourceDirectory,
        Path artifactPath,
        String artifactSha256,
        long artifactSizeBytes,
        List<String> environmentVariableNames,
        Map<String, String> environmentVariables) {

    public DeploymentResult {
        environmentVariableNames = List.copyOf(environmentVariableNames);
        environmentVariables = Collections.unmodifiableMap(
                new LinkedHashMap<>(environmentVariables == null ? Map.of() : environmentVariables));
    }

    /** Retains the M2 constructor while keeping deployment values in memory only. */
    public DeploymentResult(
            int schemaVersion,
            String functionName,
            String runtime,
            String architecture,
            String handler,
            Path sourceDirectory,
            Path artifactPath,
            String artifactSha256,
            long artifactSizeBytes,
            List<String> environmentVariableNames) {
        this(
                schemaVersion,
                functionName,
                runtime,
                architecture,
                handler,
                sourceDirectory,
                artifactPath,
                artifactSha256,
                artifactSizeBytes,
                environmentVariableNames,
                Map.of());
    }

    /** Returns deterministic JSON field content for the generated deployment file. */
    public String toJson() {
        return "{\n"
                + "  \"schemaVersion\": " + schemaVersion + ",\n"
                + "  \"functionName\": \"" + escape(functionName) + "\",\n"
                + "  \"runtime\": \"" + escape(runtime) + "\",\n"
                + "  \"architecture\": \"" + escape(architecture) + "\",\n"
                + "  \"handler\": \"" + escape(handler) + "\",\n"
                + "  \"sourceDirectory\": \"" + escape(sourceDirectory.toString()) + "\",\n"
                + "  \"artifactPath\": \"" + escape(artifactPath.toString()) + "\",\n"
                + "  \"artifactSha256\": \"" + escape(artifactSha256) + "\",\n"
                + "  \"artifactSizeBytes\": " + artifactSizeBytes + ",\n"
                + "  \"environmentVariableNames\": ["
                + String.join(
                        ", ",
                        environmentVariableNames.stream()
                                .map(name -> "\"" + escape(name) + "\"")
                                .toList())
                + "]\n"
                + "}\n";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
