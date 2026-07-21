package io.github.aresprojects.local.runtime;

import io.github.aresprojects.local.runtime.http.netty.NettyAwsHttpServer;
import io.github.aresprojects.local.runtime.service.AwsServiceRegistry;
import java.net.InetSocketAddress;
import java.util.Objects;

/** Coordinates server lifecycle so callers do not depend on the transport implementation. */
public final class LocalAwsServer implements AutoCloseable {
    private final NettyAwsHttpServer delegate;
    private State state = State.NEW;
    private InetSocketAddress localAddress;

    /** Creates a lifecycle owner with immutable configuration and a startup-built service registry. */
    public LocalAwsServer(LocalAwsServerConfig config, AwsServiceRegistry registry) {
        delegate = new NettyAwsHttpServer(
                Objects.requireNonNull(config, "config"), Objects.requireNonNull(registry, "registry"));
    }

    /** Binds before returning so callers can publish the actual endpoint, including an ephemeral port. */
    public synchronized InetSocketAddress start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "Cannot start server from state " + state + "; create a new LocalAwsServer instance");
        }
        try {
            localAddress = delegate.start();
            state = State.RUNNING;
            return localAddress;
        } catch (RuntimeException exception) {
            state = State.CLOSED;
            throw exception;
        }
    }

    /** Reports whether the endpoint is bound and can accept requests. */
    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    /** Exposes the bound address when the operating system selected the port. */
    public synchronized InetSocketAddress localAddress() {
        if (state != State.RUNNING) {
            throw new IllegalStateException(
                    "Cannot read local address while server is in state " + state + "; call start() first");
        }
        return localAddress;
    }

    /** Releases transport resources; idempotence supports try-with-resources and shutdown hooks. */
    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        delegate.close();
        state = State.CLOSED;
    }

    private enum State {
        NEW,
        RUNNING,
        CLOSED
    }
}
