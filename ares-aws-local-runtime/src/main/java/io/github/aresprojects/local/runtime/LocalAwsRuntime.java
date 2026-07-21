package io.github.aresprojects.local.runtime;

import io.github.aresprojects.local.runtime.service.AwsServiceRegistry;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Provides the process-level lifecycle needed by the runnable local AWS runtime. */
public final class LocalAwsRuntime {
    private static final System.Logger LOGGER = System.getLogger(LocalAwsRuntime.class.getName());

    private LocalAwsRuntime() {}

    /** Keeps the endpoint alive until the shutdown hook releases the process latch. */
    public static void main(String[] args) {
        LocalAwsServerConfig config = LocalAwsServerConfig.fromEnvironment(System.getenv());
        run(
                config,
                () -> new LocalAwsServer(config, defaultRegistry()),
                Runtime.getRuntime()::addShutdownHook,
                new CountDownLatch(1),
                address -> LOGGER.log(System.Logger.Level.INFO, "Ares AWS Local listening on {0}", address));
    }

    static void run(
            LocalAwsServerConfig config,
            Supplier<LocalAwsServer> serverFactory,
            Consumer<Thread> shutdownHookRegistrar,
            CountDownLatch shutdown,
            Consumer<InetSocketAddress> startupLogger) {
        shutdownHookRegistrar.accept(new Thread(shutdown::countDown, "ares-aws-local-shutdown"));
        try (LocalAwsServer server = serverFactory.get()) {
            InetSocketAddress address = server.start();
            startupLogger.accept(address);
            shutdown.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    static AwsServiceRegistry defaultRegistry() {
        return AwsServiceRegistry.builder().build();
    }
}
