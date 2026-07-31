package io.github.aresprojects.local.cli;

/** Defines the stable process exit codes exposed by the Ares CLI. */
public enum AresExitCode {
    /** An unexpected local failure occurred. */
    INTERNAL_ERROR(1),

    /** The command completed successfully. */
    SUCCESS(0),

    /** The command line or local project configuration is invalid. */
    USAGE_ERROR(2),

    /** The local runtime could not be started or reached. */
    RUNTIME_UNAVAILABLE(3),

    /** The function build or artifact validation failed. */
    BUILD_FAILED(4),

    /** Deployment API or local deployment reconciliation failed. */
    DEPLOYMENT_FAILED(5),

    /** The function returned a Lambda-level error response. */
    FUNCTION_ERROR(6),

    /** Required local infrastructure, such as Docker or the runtime endpoint, is unavailable. */
    INFRASTRUCTURE_UNAVAILABLE(7);

    private final int value;

    AresExitCode(int value) {
        this.value = value;
    }

    /** Returns the integer value suitable for a process exit status. */
    public int value() {
        return value;
    }
}
