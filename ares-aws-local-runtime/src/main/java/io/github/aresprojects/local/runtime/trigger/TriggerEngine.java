package io.github.aresprojects.local.runtime.trigger;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs polling and push integrations from one immutable startup registry. */
public final class TriggerEngine implements AutoCloseable, IntegrationEventPublisher {
    /** Delay before an idle polling lane checks its source again. */
    public static final Duration DEFAULT_IDLE_POLL_INTERVAL = Duration.ofMillis(100);

    /** Maximum time close waits for active deliveries. */
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    private final TriggerRegistry registry;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;
    private final TriggerDiagnosticsObserver diagnostics;
    private final Duration idlePollInterval;
    private final Duration shutdownTimeout;
    private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();
    private State state = State.NEW;

    /** Creates an engine using a shared scheduler and virtual threads for deliveries. */
    public TriggerEngine(TriggerRegistry registry) {
        this(
                registry,
                Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("ares-trigger-scheduler").factory()),
                Executors.newVirtualThreadPerTaskExecutor(),
                TriggerDiagnosticsObserver.none(),
                DEFAULT_IDLE_POLL_INTERVAL,
                DEFAULT_SHUTDOWN_TIMEOUT);
    }

    /**
     * Creates an engine with injectable lifecycle dependencies for deterministic tests and embedding.
     *
     * <p>The engine owns the supplied scheduler and executor and shuts both down when closed.
     *
     * @param registry immutable trigger wiring
     * @param clock clock used for diagnostics
     * @param scheduler shared polling scheduler
     * @param executor target invocation executor
     * @param diagnostics structured diagnostics observer
     * @param idlePollInterval delay after an empty source poll
     * @param shutdownTimeout maximum graceful shutdown wait
     */
    public TriggerEngine(
            TriggerRegistry registry,
            Clock clock,
            ScheduledExecutorService scheduler,
            ExecutorService executor,
            TriggerDiagnosticsObserver diagnostics,
            Duration idlePollInterval,
            Duration shutdownTimeout) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.idlePollInterval = requireNonNegative(idlePollInterval, "idlePollInterval");
        this.shutdownTimeout = requirePositive(shutdownTimeout, "shutdownTimeout");
    }

    /** Starts one non-overlapping polling lane for every configured concurrency slot. */
    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "Cannot start trigger engine from state " + state + "; create a new TriggerEngine instance");
        }
        state = State.RUNNING;
        for (TriggerMapping mapping : registry.mappings()) {
            PollingTriggerDriver driver = registry.pollingDriver(mapping.driverId());
            if (mapping.enabled() && driver != null) {
                PollingTriggerSettings settings = (PollingTriggerSettings) mapping.settings();
                for (int lane = 0; lane < settings.maximumConcurrency(); lane++) {
                    schedulePoll(mapping, driver, Duration.ZERO);
                }
            }
        }
        emit(TriggerDiagnosticKind.ENGINE_STARTED, "", "", "Trigger engine started", null);
    }

    /**
     * Reports whether trigger dispatch is active.
     *
     * @return whether the engine has started and has not begun shutdown
     */
    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    @Override
    public CompletionStage<Void> publish(IntegrationEvent event) {
        IntegrationEvent published = Objects.requireNonNull(event, "event");
        synchronized (this) {
            if (state != State.RUNNING) {
                throw new IllegalStateException("Cannot publish integration events while trigger engine is in state "
                        + state + "; call start() first");
            }
        }
        List<CompletableFuture<Void>> deliveries = new ArrayList<>();
        for (TriggerMapping mapping : registry.mappings()) {
            PushTriggerDriver driver = registry.pushDriver(mapping.driverId());
            if (mapping.enabled() && driver != null && mapping.source().equals(published.source())) {
                deliveries.add(submitPush(mapping, driver, published));
            }
        }
        return CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new));
    }

    /** Stops new work, waits for active deliveries, and then releases engine threads. */
    @Override
    public void close() {
        CompletableFuture<?>[] active;
        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            scheduler.shutdown();
            active = inFlight.toArray(CompletableFuture[]::new);
        }
        awaitActiveDeliveries(active);
        scheduler.shutdownNow();
        executor.shutdownNow();
        emit(TriggerDiagnosticKind.ENGINE_STOPPED, "", "", "Trigger engine stopped", null);
    }

    private synchronized void schedulePoll(TriggerMapping mapping, PollingTriggerDriver driver, Duration delay) {
        if (state != State.RUNNING) {
            return;
        }
        scheduler.schedule(() -> submitPoll(mapping, driver), delay.toNanos(), TimeUnit.NANOSECONDS);
    }

    private void submitPoll(TriggerMapping mapping, PollingTriggerDriver driver) {
        CompletableFuture<Void> operation = registerOperation();
        if (operation == null) {
            return;
        }
        try {
            executor.execute(() -> invokePoll(mapping, driver, operation));
        } catch (RuntimeException exception) {
            finishPoll(mapping, driver, operation, null, exception);
        }
    }

    private void invokePoll(TriggerMapping mapping, PollingTriggerDriver driver, CompletableFuture<Void> operation) {
        try {
            CompletionStage<PollingTriggerResult> stage = Objects.requireNonNull(
                    driver.poll(mapping),
                    "Polling driver '" + driver.driverId() + "' returned a null completion stage");
            stage.whenComplete((result, failure) -> finishPoll(mapping, driver, operation, result, failure));
        } catch (RuntimeException exception) {
            finishPoll(mapping, driver, operation, null, exception);
        }
    }

    private void finishPoll(
            TriggerMapping mapping,
            PollingTriggerDriver driver,
            CompletableFuture<Void> operation,
            PollingTriggerResult result,
            Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause != null) {
            emit(
                    TriggerDiagnosticKind.POLL_FAILED,
                    mapping.id(),
                    driver.driverId(),
                    "Polling driver failed; leased source records will become available after their visibility timeout",
                    cause);
        } else if (result == null) {
            cause = new IllegalStateException("Polling driver '" + driver.driverId() + "' completed without a result");
            emit(TriggerDiagnosticKind.POLL_FAILED, mapping.id(), driver.driverId(), cause.getMessage(), cause);
        }
        operation.complete(null);
        inFlight.remove(operation);
        Duration delay =
                result == PollingTriggerResult.WORK_PROCESSED && cause == null ? Duration.ZERO : idlePollInterval;
        schedulePoll(mapping, driver, delay);
    }

    private CompletableFuture<Void> submitPush(
            TriggerMapping mapping, PushTriggerDriver driver, IntegrationEvent event) {
        CompletableFuture<Void> operation = registerOperation();
        if (operation == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            executor.execute(() -> invokePush(mapping, driver, event, operation));
        } catch (RuntimeException exception) {
            finishPush(mapping, driver, operation, exception);
        }
        return operation;
    }

    private void invokePush(
            TriggerMapping mapping,
            PushTriggerDriver driver,
            IntegrationEvent event,
            CompletableFuture<Void> operation) {
        try {
            CompletionStage<Void> stage = Objects.requireNonNull(
                    driver.dispatch(mapping, event),
                    "Push driver '" + driver.driverId() + "' returned a null completion stage");
            stage.whenComplete((ignored, failure) -> finishPush(mapping, driver, operation, failure));
        } catch (RuntimeException exception) {
            finishPush(mapping, driver, operation, exception);
        }
    }

    private void finishPush(
            TriggerMapping mapping, PushTriggerDriver driver, CompletableFuture<Void> operation, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause != null) {
            emit(
                    TriggerDiagnosticKind.PUSH_DELIVERY_FAILED,
                    mapping.id(),
                    driver.driverId(),
                    "Push delivery failed for mapping '" + mapping.id() + "'; other targets were not interrupted",
                    cause);
        }
        operation.complete(null);
        inFlight.remove(operation);
    }

    private synchronized CompletableFuture<Void> registerOperation() {
        if (state != State.RUNNING) {
            return null;
        }
        CompletableFuture<Void> operation = new CompletableFuture<>();
        inFlight.add(operation);
        return operation;
    }

    private void awaitActiveDeliveries(CompletableFuture<?>[] active) {
        try {
            CompletableFuture.allOf(active).get(shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            emit(
                    TriggerDiagnosticKind.SHUTDOWN_TIMED_OUT,
                    "",
                    "",
                    "Trigger shutdown exceeded " + shutdownTimeout + "; unfinished source records remain leased",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("Trigger delivery tracking failed during shutdown", exception.getCause());
        }
    }

    private void emit(TriggerDiagnosticKind kind, String mappingId, String driverId, String detail, Throwable cause) {
        try {
            diagnostics.onDiagnostic(new TriggerDiagnostic(
                    clock.instant(), kind, mappingId, driverId, detail, Optional.ofNullable(cause)));
        } catch (RuntimeException ignored) {
            // Diagnostics must not change source acknowledgement or retry behavior.
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException completionException && completionException.getCause() != null) {
            return completionException.getCause();
        }
        return failure;
    }

    private static Duration requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative; use Duration.ZERO or a positive delay");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive; provide a bounded shutdown wait");
        }
        return value;
    }

    private enum State {
        NEW,
        RUNNING,
        CLOSED
    }
}
