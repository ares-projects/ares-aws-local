package io.github.aresprojects.local.cloudformation;

/** Outcome of planning or provisioning a local stack. */
public enum StackDeploymentStatus {
    PLANNED,
    COMPLETE,
    PARTIAL,
    FAILED,
    ROLLBACK_COMPLETE,
    ROLLBACK_FAILED
}
