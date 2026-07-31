package io.github.aresprojects.local.cli.deploy;

/** Captures an HTTP or AWS-shaped failure from the local Lambda API. */
public final class LambdaClientException extends Exception {
    private final String errorCode;
    private final int statusCode;

    public LambdaClientException(String errorCode, int statusCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.statusCode = statusCode;
    }

    /** Returns the AWS error code returned by the local endpoint. */
    public String errorCode() {
        return errorCode;
    }

    /** Returns the HTTP status returned by the local endpoint. */
    public int statusCode() {
        return statusCode;
    }
}
