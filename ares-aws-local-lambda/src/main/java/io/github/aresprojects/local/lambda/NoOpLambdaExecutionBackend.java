package io.github.aresprojects.local.lambda;

/** M3 execution boundary; Docker/RIE owns the real implementation in M4. */
public final class NoOpLambdaExecutionBackend implements LambdaExecutionBackend {
    @Override
    public void invalidate(String functionName, String revisionId) {
        // The first deployment slice has no execution environments to invalidate.
    }
}
