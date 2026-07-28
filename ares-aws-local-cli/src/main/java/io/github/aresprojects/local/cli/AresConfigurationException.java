package io.github.aresprojects.local.cli;

/** Reports an invalid command-line or local Lambda project configuration. */
public final class AresConfigurationException extends Exception {

    /** Creates a configuration failure with an actionable message. */
    public AresConfigurationException(String message) {
        super(message);
    }

    /** Creates a configuration failure while preserving the parser or filesystem cause. */
    public AresConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
