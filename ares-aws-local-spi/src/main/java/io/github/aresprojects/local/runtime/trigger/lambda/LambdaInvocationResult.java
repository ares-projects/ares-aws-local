package io.github.aresprojects.local.runtime.trigger.lambda;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Captures the raw result of a synchronous Lambda invocation. */
public record LambdaInvocationResult(byte[] payload, Optional<String> functionError) {

    /** Takes an immutable payload snapshot and validates the explicit error marker. */
    public LambdaInvocationResult {
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
        functionError = Objects.requireNonNull(functionError, "functionError");
        functionError.ifPresent(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        "functionError must contain Lambda's error classification or be empty");
            }
        });
    }

    /**
     * Creates a successful invocation result.
     *
     * @param payload raw function response bytes
     * @return a successful result
     */
    public static LambdaInvocationResult success(byte[] payload) {
        return new LambdaInvocationResult(payload, Optional.empty());
    }

    /**
     * Creates a function-level failure result.
     *
     * @param payload raw function error payload
     * @param functionError Lambda's error classification
     * @return a failed function result
     */
    public static LambdaInvocationResult functionError(byte[] payload, String functionError) {
        return new LambdaInvocationResult(payload, Optional.of(functionError));
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
