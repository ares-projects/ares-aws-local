package io.github.aresprojects.local.lambda;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local, thread-safe Lambda metadata store. */
public final class InMemoryLambdaFunctionStore implements LambdaFunctionStore {
    private final ConcurrentMap<String, LambdaFunctionSnapshot> functions = new ConcurrentHashMap<>();

    @Override
    public Optional<LambdaFunctionSnapshot> find(String functionName) {
        return Optional.ofNullable(functions.get(Objects.requireNonNull(functionName, "functionName")));
    }

    @Override
    public void create(LambdaFunctionSnapshot function) {
        Objects.requireNonNull(function, "function");
        if (functions.putIfAbsent(function.functionName(), function) != null) {
            throw new LambdaServiceException(
                    "ResourceConflictException", "Function '" + function.functionName() + "' already exists");
        }
    }

    @Override
    public void replace(LambdaFunctionSnapshot function) {
        Objects.requireNonNull(function, "function");
        if (functions.replace(function.functionName(), function) == null) {
            throw notFound(function.functionName());
        }
    }

    @Override
    public Optional<LambdaFunctionSnapshot> remove(String functionName) {
        return Optional.ofNullable(functions.remove(Objects.requireNonNull(functionName, "functionName")));
    }

    private static LambdaServiceException notFound(String functionName) {
        return new LambdaServiceException(
                "ResourceNotFoundException", "Function '" + functionName + "' does not exist");
    }
}
