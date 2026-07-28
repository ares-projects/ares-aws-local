package io.github.aresprojects.local.cli.builder;

import java.nio.file.Path;
import java.util.List;

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
        List<String> environmentVariableNames) {

    public DeploymentResult {
        environmentVariableNames = List.copyOf(environmentVariableNames);
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
