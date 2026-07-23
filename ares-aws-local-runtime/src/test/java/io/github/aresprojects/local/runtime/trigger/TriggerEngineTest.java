package io.github.aresprojects.local.runtime.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TriggerEngineTest {
    private static final AwsResourceReference SOURCE = new AwsResourceReference("sqs", "source");
    private static final AwsResourceReference TARGET = new AwsResourceReference("lambda", "target");

    @Test
    void runsVirtualPollingLanesWithoutExceedingMappingConcurrency() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        List<CompletableFuture<PollingTriggerResult>> polls = new CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicReference<Boolean> virtual = new AtomicReference<>(true);
        PollingTriggerDriver driver = pollingDriver("poll", mapping -> {
            virtual.set(virtual.get() && Thread.currentThread().isVirtual());
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            CompletableFuture<PollingTriggerResult> result = new CompletableFuture<>();
            result.whenComplete((ignored, failure) -> active.decrementAndGet());
            polls.add(result);
            entered.countDown();
            return result;
        });
        TriggerRegistry registry = TriggerRegistry.builder()
                .registerPollingDriver(driver)
                .registerMapping(mapping("enabled", "poll", true, 2))
                .registerMapping(mapping("disabled", "poll", false, 5))
                .build();

        try (TriggerEngine engine = new TriggerEngine(registry)) {
            engine.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(2, polls.size());
            assertEquals(2, maximumActive.get());
            assertTrue(virtual.get());
            polls.forEach(future -> future.complete(PollingTriggerResult.IDLE));
        }
    }

    @Test
    void waitsTheIdleIntervalBeforePollingAgain() throws Exception {
        CountDownLatch polledTwice = new CountDownLatch(2);
        PollingTriggerDriver driver = pollingDriver("poll", mapping -> {
            polledTwice.countDown();
            return CompletableFuture.completedFuture(PollingTriggerResult.IDLE);
        });
        TriggerRegistry registry = TriggerRegistry.builder()
                .registerPollingDriver(driver)
                .registerMapping(mapping("mapping", "poll", true, 1))
                .build();
        TriggerEngine engine = engine(registry, diagnostic -> {}, Duration.ofMillis(10), Duration.ofSeconds(1));

        engine.start();

        assertTrue(polledTwice.await(2, TimeUnit.SECONDS));
        engine.close();
    }

    @Test
    void reportsPollingFailuresAndContinuesTheLane() throws Exception {
        CountDownLatch failureObserved = new CountDownLatch(1);
        CountDownLatch retried = new CountDownLatch(2);
        List<TriggerDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
        PollingTriggerDriver driver = pollingDriver("poll", mapping -> {
            retried.countDown();
            return CompletableFuture.failedFuture(new IllegalStateException("source unavailable"));
        });
        TriggerRegistry registry = TriggerRegistry.builder()
                .registerPollingDriver(driver)
                .registerMapping(mapping("mapping", "poll", true, 1))
                .build();
        TriggerEngine engine = engine(
                registry,
                diagnostic -> {
                    diagnostics.add(diagnostic);
                    if (diagnostic.kind() == TriggerDiagnosticKind.POLL_FAILED) {
                        failureObserved.countDown();
                    }
                },
                Duration.ofMillis(10),
                Duration.ofSeconds(1));

        engine.start();

        assertTrue(failureObserved.await(2, TimeUnit.SECONDS));
        assertTrue(retried.await(2, TimeUnit.SECONDS));
        engine.close();
        TriggerDiagnostic failure = diagnostics.stream()
                .filter(diagnostic -> diagnostic.kind() == TriggerDiagnosticKind.POLL_FAILED)
                .findFirst()
                .orElseThrow();
        assertEquals("mapping", failure.mappingId());
        assertEquals("source unavailable", failure.cause().orElseThrow().getMessage());
    }

    @Test
    void fansOutPushEventsAndIsolatesTargetFailures() {
        List<String> targets = new CopyOnWriteArrayList<>();
        List<TriggerDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
        PushTriggerDriver driver = new PushTriggerDriver() {
            @Override
            public String driverId() {
                return "push";
            }

            @Override
            public Class<PushSettings> settingsType() {
                return PushSettings.class;
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> dispatch(TriggerMapping mapping, IntegrationEvent event) {
                targets.add(mapping.target().resourceIdentifier());
                if (mapping.target().resourceIdentifier().equals("failed")) {
                    return CompletableFuture.failedFuture(new IllegalStateException("target rejected event"));
                }
                return CompletableFuture.completedFuture(null);
            }
        };
        TriggerRegistry registry = TriggerRegistry.builder()
                .registerPushDriver(driver)
                .registerMapping(pushMapping("success", SOURCE, "success", true))
                .registerMapping(pushMapping("failed", SOURCE, "failed", true))
                .registerMapping(pushMapping("other-source", new AwsResourceReference("sns", "other"), "ignored", true))
                .registerMapping(pushMapping("disabled", SOURCE, "ignored-disabled", false))
                .build();
        TriggerEngine engine = engine(registry, diagnostics::add, Duration.ofMillis(10), Duration.ofSeconds(1));
        engine.start();

        engine.publish(new IntegrationEvent("event", SOURCE, "published", Instant.EPOCH, new byte[] {1}))
                .toCompletableFuture()
                .join();
        engine.close();

        assertEquals(2, targets.size());
        assertTrue(targets.containsAll(List.of("success", "failed")));
        assertEquals(
                1,
                diagnostics.stream()
                        .filter(diagnostic -> diagnostic.kind() == TriggerDiagnosticKind.PUSH_DELIVERY_FAILED)
                        .count());
    }

    @Test
    void validatesLifecycleAndConstructorDurations() {
        TriggerRegistry registry = TriggerRegistry.builder().build();
        List<TriggerDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
        TriggerEngine engine = engine(registry, diagnostics::add, Duration.ofMillis(10), Duration.ofSeconds(1));
        IntegrationEvent event = new IntegrationEvent(
                "event", new AwsResourceReference("sns", "topic"), "published", Instant.EPOCH, new byte[0]);

        assertFalse(engine.isRunning());
        assertThrows(IllegalStateException.class, () -> engine.publish(event));
        engine.start();
        assertTrue(engine.isRunning());
        assertThrows(IllegalStateException.class, engine::start);
        engine.close();
        engine.close();
        assertFalse(engine.isRunning());
        assertTrue(
                diagnostics.stream().anyMatch(diagnostic -> diagnostic.kind() == TriggerDiagnosticKind.ENGINE_STARTED));
        assertTrue(
                diagnostics.stream().anyMatch(diagnostic -> diagnostic.kind() == TriggerDiagnosticKind.ENGINE_STOPPED));
        assertThrows(IllegalStateException.class, engine::start);

        assertThrows(
                IllegalArgumentException.class,
                () -> engine(registry, diagnostic -> {}, Duration.ofMillis(-1), Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class, () -> engine(registry, diagnostic -> {}, Duration.ZERO, Duration.ZERO));
    }

    @Test
    void reportsWhenGracefulShutdownTimesOut() throws Exception {
        CountDownLatch pollStarted = new CountDownLatch(1);
        CompletableFuture<PollingTriggerResult> unfinished = new CompletableFuture<>();
        PollingTriggerDriver driver = pollingDriver("poll", mapping -> {
            pollStarted.countDown();
            return unfinished;
        });
        TriggerRegistry registry = TriggerRegistry.builder()
                .registerPollingDriver(driver)
                .registerMapping(mapping("mapping", "poll", true, 1))
                .build();
        List<TriggerDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
        TriggerEngine engine = engine(registry, diagnostics::add, Duration.ofMillis(10), Duration.ofMillis(10));
        engine.start();
        assertTrue(pollStarted.await(2, TimeUnit.SECONDS));

        engine.close();

        assertTrue(diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.kind() == TriggerDiagnosticKind.SHUTDOWN_TIMED_OUT));
    }

    @Test
    void reportsMissingPollResultsAndUnwrapsCompletionFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch diagnosticsObserved = new CountDownLatch(2);
        List<TriggerDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
        PollingTriggerDriver driver = pollingDriver("poll", mapping -> {
            if (calls.getAndIncrement() == 0) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.failedFuture(
                    new java.util.concurrent.CompletionException(new IllegalArgumentException("wrapped")));
        });
        TriggerRegistry registry = TriggerRegistry.builder()
                .registerPollingDriver(driver)
                .registerMapping(mapping("mapping", "poll", true, 1))
                .build();
        TriggerEngine engine = engine(
                registry,
                diagnostic -> {
                    diagnostics.add(diagnostic);
                    if (diagnostic.kind() == TriggerDiagnosticKind.POLL_FAILED) {
                        diagnosticsObserved.countDown();
                    }
                },
                Duration.ofMillis(1),
                Duration.ofSeconds(1));
        engine.start();

        assertTrue(diagnosticsObserved.await(2, TimeUnit.SECONDS));
        engine.close();

        assertTrue(diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic
                        .cause()
                        .map(Throwable::getMessage)
                        .filter("wrapped"::equals)
                        .isPresent()));
        assertTrue(diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.detail().contains("completed without a result")));
    }

    @Test
    void restoresInterruptWhenShutdownWaitIsInterrupted() throws Exception {
        CountDownLatch pollStarted = new CountDownLatch(1);
        PollingTriggerDriver driver = pollingDriver("poll", mapping -> {
            pollStarted.countDown();
            return new CompletableFuture<>();
        });
        TriggerRegistry registry = TriggerRegistry.builder()
                .registerPollingDriver(driver)
                .registerMapping(mapping("mapping", "poll", true, 1))
                .build();
        TriggerEngine engine = engine(registry, diagnostic -> {}, Duration.ofMillis(10), Duration.ofSeconds(1));
        engine.start();
        assertTrue(pollStarted.await(2, TimeUnit.SECONDS));

        Thread.currentThread().interrupt();
        try {
            engine.close();
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static TriggerEngine engine(
            TriggerRegistry registry,
            TriggerDiagnosticsObserver observer,
            Duration idleInterval,
            Duration shutdownTimeout) {
        return new TriggerEngine(
                registry,
                Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC),
                Executors.newSingleThreadScheduledExecutor(),
                Executors.newVirtualThreadPerTaskExecutor(),
                observer,
                idleInterval,
                shutdownTimeout);
    }

    private static PollingTriggerDriver pollingDriver(
            String driverId,
            java.util.function.Function<TriggerMapping, java.util.concurrent.CompletionStage<PollingTriggerResult>>
                    poller) {
        return new PollingTriggerDriver() {
            @Override
            public String driverId() {
                return driverId;
            }

            @Override
            public Class<TestPollingSettings> settingsType() {
                return TestPollingSettings.class;
            }

            @Override
            public java.util.concurrent.CompletionStage<PollingTriggerResult> poll(TriggerMapping mapping) {
                return poller.apply(mapping);
            }
        };
    }

    private static TriggerMapping mapping(String id, String driverId, boolean enabled, int maximumConcurrency) {
        return new TriggerMapping(id, driverId, SOURCE, TARGET, enabled, new TestPollingSettings(maximumConcurrency));
    }

    private static TriggerMapping pushMapping(String id, AwsResourceReference source, String target, boolean enabled) {
        return new TriggerMapping(
                id, "push", source, new AwsResourceReference("lambda", target), enabled, new PushSettings());
    }

    private record TestPollingSettings(int maximumConcurrency) implements PollingTriggerSettings {}

    private record PushSettings() implements TriggerSettings {}
}
