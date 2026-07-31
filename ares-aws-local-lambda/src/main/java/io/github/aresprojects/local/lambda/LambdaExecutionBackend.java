package io.github.aresprojects.local.lambda;

import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvocationResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Invalidates execution environments when a local Lambda revision changes. */
@FunctionalInterface
public interface LambdaExecutionBackend {

    /** Executes a deployed function with its raw JSON payload. */
    default CompletionStage<LambdaInvocationResult> invoke(LambdaFunctionSnapshot function, byte[] payload) {
        return CompletableFuture.failedFuture(new LambdaServiceException(
                "ServiceException", "No Lambda execution backend is configured for local function execution"));
    }

    /** Discards environments associated with a function revision. */
    void invalidate(String functionName, String revisionId);

    /** Releases containers and other execution resources owned by the backend. */
    default void close() {}
}
