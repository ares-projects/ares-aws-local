package io.github.aresprojects.local.cloudformation;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe process-local stack state used by the default runtime. */
public final class InMemoryStackStateStore implements StackStateStore {
    private final ConcurrentMap<String, StackState> states = new ConcurrentHashMap<>();

    @Override
    public Optional<StackState> find(String stackName) {
        return Optional.ofNullable(states.get(Objects.requireNonNull(stackName, "stackName")));
    }

    @Override
    public void save(StackState state) {
        StackState value = Objects.requireNonNull(state, "state");
        states.put(value.stackName(), value);
    }
}
