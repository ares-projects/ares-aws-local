package io.github.aresprojects.local.lambda.docker;

/** Owns the lifecycle of containers started for local Lambda execution. */
public interface ContainerRuntime extends AutoCloseable {

    /** Starts a detached container and returns its host-side invocation endpoint. */
    ContainerHandle start(ContainerSpec specification);

    /** Stops and removes one container owned by this runtime. */
    default void stop(ContainerHandle container) {}

    /** Returns bounded container logs for an actionable failure message. */
    default String logs(ContainerHandle container) {
        return "";
    }

    @Override
    default void close() {}
}
