package io.github.aresprojects.local.runtime;

import java.net.InetSocketAddress;

/** Owns the endpoint and integration resources of one local runtime process. */
interface LocalAwsRuntimeProcess extends AutoCloseable {

    /** Starts the process resources and returns the bound endpoint. */
    InetSocketAddress start();

    /** Releases all process resources. */
    @Override
    void close();
}
