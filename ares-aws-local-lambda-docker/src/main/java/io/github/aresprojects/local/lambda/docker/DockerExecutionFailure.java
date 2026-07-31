package io.github.aresprojects.local.lambda.docker;

/** Machine-readable categories for local container failures. */
public enum DockerExecutionFailure {
    DOCKER_UNAVAILABLE,
    IMAGE_UNAVAILABLE,
    ARCHITECTURE_MISMATCH,
    STARTUP_TIMEOUT,
    FUNCTION_TIMEOUT,
    CONTAINER_EXITED,
    TIMEOUT,
    INTERRUPTED,
    COMMAND_FAILED
}
