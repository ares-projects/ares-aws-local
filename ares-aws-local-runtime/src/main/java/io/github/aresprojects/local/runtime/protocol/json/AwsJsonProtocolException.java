package io.github.aresprojects.local.runtime.protocol.json;

/** Describes a client-side AWS JSON protocol failure that can be returned as an AWS-shaped error. */
public final class AwsJsonProtocolException extends RuntimeException {
    private final String errorCode;

    public AwsJsonProtocolException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** Returns the AWS-compatible error code for the malformed request. */
    public String errorCode() {
        return errorCode;
    }
}
