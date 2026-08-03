package io.github.aresprojects.local.cloudformation;

import java.util.Map;
import java.util.Objects;

/** Immutable process-local state for one provisioned stack. */
public record StackState(String stackName, Map<String, ProvisionedResource> resources, Map<String, String> outputs) {
    public StackState {
        stackName = Objects.requireNonNull(stackName, "stackName");
        resources = Map.copyOf(Objects.requireNonNull(resources, "resources"));
        outputs = Map.copyOf(Objects.requireNonNull(outputs, "outputs"));
    }
}
