package io.github.aresprojects.local.cloudformation;

import java.util.Optional;

/** Owns process-local CloudFormation stack state independently from HTTP transport. */
public interface StackStateStore {
    Optional<StackState> find(String stackName);

    void save(StackState state);
}
