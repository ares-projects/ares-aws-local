package io.github.aresprojects.local.runtime.service.sqs;

/** Reports a documented SQS validation or resource error from a service command. */
public final class SqsServiceException extends RuntimeException {
    private final String errorCode;

    public SqsServiceException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** Returns the AWS error code for the failed command. */
    public String errorCode() {
        return errorCode;
    }
}
