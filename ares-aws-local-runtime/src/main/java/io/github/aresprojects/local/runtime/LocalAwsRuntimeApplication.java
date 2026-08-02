package io.github.aresprojects.local.runtime;

import io.github.aresprojects.local.runtime.trigger.TriggerEngine;
import java.net.InetSocketAddress;
import java.util.Objects;

/** Composes the HTTP server and trigger engine under one runtime lifecycle. */
final class LocalAwsRuntimeApplication implements LocalAwsRuntimeProcess {
    private final LocalAwsServer server;
    private final TriggerEngine triggerEngine;
    private State state = State.NEW;

    LocalAwsRuntimeApplication(LocalAwsServer server, TriggerEngine triggerEngine) {
        this.server = Objects.requireNonNull(server, "server");
        this.triggerEngine = Objects.requireNonNull(triggerEngine, "triggerEngine");
    }

    @Override
    public synchronized InetSocketAddress start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "Cannot start runtime application from state " + state + "; create a new application instance");
        }
        try {
            InetSocketAddress address = server.start();
            triggerEngine.start();
            state = State.RUNNING;
            return address;
        } catch (RuntimeException exception) {
            state = State.CLOSED;
            try {
                triggerEngine.close();
            } finally {
                server.close();
            }
            throw exception;
        }
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        try {
            triggerEngine.close();
        } finally {
            server.close();
        }
    }

    private enum State {
        NEW,
        RUNNING,
        CLOSED
    }
}
