package io.github.aresprojects.local.lambda.docker;

/** Actionable failure from Docker or the local Lambda container lifecycle. */
public final class DockerExecutionException extends RuntimeException {
    private final DockerExecutionFailure failure;

    public DockerExecutionException(DockerExecutionFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public DockerExecutionException(DockerExecutionFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public DockerExecutionFailure failure() {
        return failure;
    }
}
