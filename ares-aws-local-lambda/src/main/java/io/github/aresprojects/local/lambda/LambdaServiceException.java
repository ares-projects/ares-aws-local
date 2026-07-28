package io.github.aresprojects.local.lambda;

/** Reports a documented Lambda control-plane failure. */
public final class LambdaServiceException extends RuntimeException {
    private final String errorCode;

    public LambdaServiceException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** Returns the AWS-shaped error code for this failure. */
    public String errorCode() {
        return errorCode;
    }
}
