package io.github.aresprojects.local.cloudformation;

import java.util.Objects;

/** Structured explanation for a planned or executed stack result. */
public record DeploymentDiagnostic(String logicalId, ResourceAction action, String message) {
    public DeploymentDiagnostic {
        logicalId = Objects.requireNonNull(logicalId, "logicalId");
        action = Objects.requireNonNull(action, "action");
        message = Objects.requireNonNull(message, "message");
    }
}
