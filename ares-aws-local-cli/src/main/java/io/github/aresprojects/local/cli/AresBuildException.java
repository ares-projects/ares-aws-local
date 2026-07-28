package io.github.aresprojects.local.cli;

/** Reports a failed build process or invalid generated Lambda artifact. */
public final class AresBuildException extends Exception {

    /** Creates a build failure with an actionable message. */
    public AresBuildException(String message) {
        super(message);
    }

    /** Creates a build failure while preserving the process or filesystem cause. */
    public AresBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
