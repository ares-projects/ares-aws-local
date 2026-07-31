package io.github.aresprojects.local.lambda;

import java.util.Optional;

/** Thread-safe metadata boundary for active local Lambda revisions. */
public interface LambdaFunctionStore {

    /** Returns a function snapshot when the name exists. */
    Optional<LambdaFunctionSnapshot> find(String functionName);

    /** Adds a function and rejects an existing name. */
    void create(LambdaFunctionSnapshot function);

    /** Replaces the active revision for an existing function. */
    void replace(LambdaFunctionSnapshot function);

    /** Removes a function and returns its last active revision. */
    Optional<LambdaFunctionSnapshot> remove(String functionName);
}
