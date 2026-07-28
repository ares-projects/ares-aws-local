package io.github.aresprojects.local.cli;

/** Reports a failed local Lambda deployment request. */
public final class AresDeploymentException extends Exception {
    public AresDeploymentException(String message) {
        super(message);
    }

    public AresDeploymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
