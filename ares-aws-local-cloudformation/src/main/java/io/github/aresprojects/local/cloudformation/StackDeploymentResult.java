package io.github.aresprojects.local.cloudformation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured result returned by the stack planner or provisioner. */
public record StackDeploymentResult(
        String stackName,
        StackDeploymentStatus status,
        List<DeploymentDiagnostic> diagnostics,
        Map<String, String> outputs,
        StackState state) {
    public StackDeploymentResult {
        stackName = Objects.requireNonNull(stackName, "stackName");
        status = Objects.requireNonNull(status, "status");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        outputs = Map.copyOf(Objects.requireNonNull(outputs, "outputs"));
    }
}
