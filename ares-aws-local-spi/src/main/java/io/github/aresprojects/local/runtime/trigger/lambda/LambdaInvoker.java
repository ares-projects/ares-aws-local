package io.github.aresprojects.local.runtime.trigger.lambda;

import java.util.concurrent.CompletionStage;

/** Invokes a Lambda target without exposing function packaging or execution internals to trigger drivers. */
@FunctionalInterface
public interface LambdaInvoker {

    /**
     * Invokes a function synchronously with its raw JSON event.
     *
     * @param functionName the configured function name or ARN
     * @param payload the raw JSON invocation payload
     * @return the asynchronous function result, including function-level errors
     */
    CompletionStage<LambdaInvocationResult> invoke(String functionName, byte[] payload);
}
