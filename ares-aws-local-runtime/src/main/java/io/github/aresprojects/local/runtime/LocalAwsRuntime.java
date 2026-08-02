package io.github.aresprojects.local.runtime;

import io.github.aresprojects.local.lambda.LambdaService;
import io.github.aresprojects.local.lambda.docker.DockerLambdaExecutionBackend;
import io.github.aresprojects.local.runtime.service.AwsServiceRegistry;
import io.github.aresprojects.local.runtime.service.lambda.LambdaJsonAdapter;
import io.github.aresprojects.local.runtime.service.sqs.InMemorySqsQueueStore;
import io.github.aresprojects.local.runtime.service.sqs.SqsJsonAdapter;
import io.github.aresprojects.local.runtime.trigger.TriggerEngine;
import io.github.aresprojects.local.runtime.trigger.TriggerRegistry;
import io.github.aresprojects.local.runtime.trigger.sqs.SqsLambdaPollingDriver;
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
                () -> defaultApplication(config),
                Runtime.getRuntime()::addShutdownHook,
                new CountDownLatch(1),
                address -> LOGGER.log(System.Logger.Level.INFO, "Ares AWS Local listening on {0}", address));
    }

    static void run(
            LocalAwsServerConfig config,
            Supplier<? extends LocalAwsRuntimeProcess> processFactory,
            Consumer<Thread> shutdownHookRegistrar,
            CountDownLatch shutdown,
            Consumer<InetSocketAddress> startupLogger) {
        LocalAwsRuntimeProcess process = processFactory.get();
        shutdownHookRegistrar.accept(new Thread(
                () -> {
                    shutdown.countDown();
                    process.close();
                },
                "ares-aws-local-shutdown"));
        try (process) {
            InetSocketAddress address = process.start();
            startupLogger.accept(address);
            shutdown.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static LocalAwsRuntimeApplication defaultApplication(LocalAwsServerConfig config) {
        InMemorySqsQueueStore queueStore = new InMemorySqsQueueStore();
        LambdaService lambdaService = new LambdaService(new DockerLambdaExecutionBackend());
        AwsServiceRegistry services = AwsServiceRegistry.builder()
                .register(new SqsJsonAdapter(queueStore))
                .register(new LambdaJsonAdapter(lambdaService))
                .build();
        TriggerRegistry triggers = TriggerRegistry.builder()
                .registerPollingDriver(new SqsLambdaPollingDriver(queueStore, lambdaService))
                .build();
        return new LocalAwsRuntimeApplication(new LocalAwsServer(config, services), new TriggerEngine(triggers));
    }

    static AwsServiceRegistry defaultRegistry() {
        return AwsServiceRegistry.builder()
                .register(new SqsJsonAdapter(new InMemorySqsQueueStore()))
                .register(new LambdaJsonAdapter(new LambdaService(new DockerLambdaExecutionBackend())))
                .build();
    }
}
