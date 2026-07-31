package io.github.aresprojects.local.lambda;

/** Invalidates execution environments when a local Lambda revision changes. */
@FunctionalInterface
public interface LambdaExecutionBackend {

    /** Discards environments associated with a function revision. */
    void invalidate(String functionName, String revisionId);
}
