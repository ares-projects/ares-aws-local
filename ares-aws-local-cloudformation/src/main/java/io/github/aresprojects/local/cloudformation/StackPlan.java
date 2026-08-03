package io.github.aresprojects.local.cloudformation;

import java.util.List;
import java.util.Objects;

/** Immutable dependency-ordered plan for one stack. */
public record StackPlan(String stackName, List<ResourcePlan> resources, List<DeploymentDiagnostic> diagnostics) {
    public StackPlan {
        stackName = Objects.requireNonNull(stackName, "stackName");
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean partial() {
        return resources.stream()
                .anyMatch(plan -> plan.action() != ResourceAction.CREATE
                        && plan.action() != ResourceAction.NO_OP
                        && plan.action() != ResourceAction.SKIPPED_CONDITION);
    }
}
