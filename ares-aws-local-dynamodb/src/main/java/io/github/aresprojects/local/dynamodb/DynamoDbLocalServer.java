package io.github.aresprojects.local.dynamodb;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import software.amazon.dynamodb.services.local.server.DynamoDBProxyServer;
import software.amazon.dynamodb.services.local.server.LocalDynamoDBRequestHandler;
import software.amazon.dynamodb.services.local.server.LocalDynamoDBServerHandler;

/** Owns an in-memory DynamoDB Local server used as the DynamoDB service backend. */
public final class DynamoDbLocalServer implements AutoCloseable {
    private final int port;
    private LocalDynamoDBRequestHandler requestHandler;
    private LocalDynamoDBServerHandler serverHandler;
    private DynamoDBProxyServer proxyServer;
    private boolean started;
    private boolean closed;

    /** Creates a server on an available loopback port. */
    public DynamoDbLocalServer() {
        this(findAvailablePort());
    }

    /** Allocates an in-memory server on the supplied port without starting its backend. */
    public DynamoDbLocalServer(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535; received " + port);
        }
        this.port = port;
        requestHandler = new LocalDynamoDBRequestHandler(port, true, null, true, false);
        serverHandler = new LocalDynamoDBServerHandler(requestHandler, "DynamoDB_20120810");
        proxyServer = new DynamoDBProxyServer(port, serverHandler);
    }

    /** Starts DynamoDB Local and returns its private loopback endpoint. */
    public synchronized URI start() {
        if (closed) {
            throw new IllegalStateException("Cannot start DynamoDB Local after the backend has been closed");
        }
        if (started) {
            throw new IllegalStateException("DynamoDB Local is already running at " + endpoint());
        }
        requestHandler = new LocalDynamoDBRequestHandler(port, true, null, true, false);
        serverHandler = new LocalDynamoDBServerHandler(requestHandler, "DynamoDB_20120810");
        proxyServer = new DynamoDBProxyServer(port, serverHandler);
        try {
            proxyServer.start();
            started = true;
            return endpoint();
        } catch (Exception exception) {
            requestHandler.shutdown();
            requestHandler = null;
            serverHandler = null;
            proxyServer = null;
            throw new IllegalStateException(
                    "Could not start the embedded DynamoDB Local backend on port " + port, exception);
        }
    }

    /** Returns the backend endpoint after startup. */
    public synchronized URI endpoint() {
        if (!started || closed) {
            throw new IllegalStateException("DynamoDB Local is not running; call start() first");
        }
        return URI.create("http://127.0.0.1:" + port);
    }

    /** Stops the backend and releases its database resources. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (started) {
            try {
                proxyServer.stop();
            } catch (Exception exception) {
                throw new IllegalStateException("Could not stop the embedded DynamoDB Local backend", exception);
            }
            serverHandler.close();
            started = false;
        }
    }

    private static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not allocate a local port for DynamoDB Local", exception);
        }
    }
}
