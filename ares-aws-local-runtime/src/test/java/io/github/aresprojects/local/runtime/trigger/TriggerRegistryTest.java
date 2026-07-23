package io.github.aresprojects.local.runtime.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TriggerRegistryTest {
    private static final AwsResourceReference SOURCE = new AwsResourceReference("sqs", "source");
    private static final AwsResourceReference TARGET = new AwsResourceReference("lambda", "target");

    @Test
    void buildsImmutableSnapshotsInMappingOrderAndRunsDriverValidation() {
        AtomicBoolean validated = new AtomicBoolean();
        PollingTriggerDriver driver = pollingDriver("poll", TestPollingSettings.class, validated);
        TriggerRegistry.Builder builder = TriggerRegistry.builder().registerPollingDriver(driver);
        builder.registerMapping(mapping("first", "poll", new TestPollingSettings(1)));
        TriggerRegistry registry = builder.build();
        builder.registerMapping(mapping("second", "poll", new TestPollingSettings(1)));

        assertEquals(1, registry.mappings().size());
        assertEquals("first", registry.mappings().getFirst().id());
        assertThrows(
                UnsupportedOperationException.class, () -> registry.mappings().clear());
        assertTrue(validated.get());
    }

    @Test
    void rejectsDuplicateMappingsDriversAndUnknownDrivers() {
        TriggerMapping mapping = mapping("mapping", "poll", new TestPollingSettings(1));
        TriggerRegistry.Builder duplicateMapping = TriggerRegistry.builder().registerMapping(mapping);

        assertThrows(IllegalArgumentException.class, () -> duplicateMapping.registerMapping(mapping));

        TriggerRegistry.Builder duplicatePolling = TriggerRegistry.builder()
                .registerPollingDriver(pollingDriver("duplicate", TestPollingSettings.class, new AtomicBoolean()));
        assertThrows(
                IllegalArgumentException.class,
                () -> duplicatePolling.registerPollingDriver(
                        pollingDriver("duplicate", TestPollingSettings.class, new AtomicBoolean())));

        TriggerRegistry.Builder duplicateFamily = TriggerRegistry.builder()
                .registerPollingDriver(pollingDriver("duplicate", TestPollingSettings.class, new AtomicBoolean()));
        assertThrows(
                IllegalArgumentException.class,
                () -> duplicateFamily.registerPushDriver(pushDriver("duplicate", TestPushSettings.class)));

        assertThrows(
                IllegalArgumentException.class,
                () -> TriggerRegistry.builder().registerMapping(mapping).build());
    }

    @Test
    void rejectsSettingsThatDoNotMatchTheDriverContract() {
        TriggerRegistry.Builder builder = TriggerRegistry.builder()
                .registerPollingDriver(pollingDriver("poll", TestPollingSettings.class, new AtomicBoolean()))
                .registerMapping(mapping("mapping", "poll", new TestPushSettings("push")));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, builder::build);

        assertTrue(exception.getMessage().contains(TestPushSettings.class.getName()));
        assertTrue(exception.getMessage().contains(TestPollingSettings.class.getName()));
    }

    @Test
    void validatesDriverIdentifiersAndDependencies() {
        assertThrows(NullPointerException.class, () -> TriggerRegistry.builder().registerPollingDriver(null));
        assertThrows(NullPointerException.class, () -> TriggerRegistry.builder().registerPushDriver(null));
        assertThrows(NullPointerException.class, () -> TriggerRegistry.builder().registerMapping(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> TriggerRegistry.builder()
                        .registerPollingDriver(pollingDriver(" ", TestPollingSettings.class, new AtomicBoolean())));
        assertThrows(
                IllegalArgumentException.class,
                () -> TriggerRegistry.builder().registerPushDriver(pushDriver("", TestPushSettings.class)));
    }

    @Test
    void registersAndValidatesPushDrivers() {
        AtomicBoolean validated = new AtomicBoolean();
        PushTriggerDriver driver = new PushTriggerDriver() {
            @Override
            public String driverId() {
                return "push";
            }

            @Override
            public Class<TestPushSettings> settingsType() {
                return TestPushSettings.class;
            }

            @Override
            public void validate(TriggerMapping mapping) {
                validated.set(true);
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> dispatch(TriggerMapping mapping, IntegrationEvent event) {
                return CompletableFuture.completedFuture(null);
            }
        };

        TriggerRegistry registry = TriggerRegistry.builder()
                .registerPushDriver(driver)
                .registerMapping(mapping("mapping", "push", new TestPushSettings("push")))
                .build();

        assertEquals(1, registry.mappings().size());
        assertTrue(validated.get());
    }

    private static TriggerMapping mapping(String id, String driverId, TriggerSettings settings) {
        return new TriggerMapping(id, driverId, SOURCE, TARGET, true, settings);
    }

    private static PollingTriggerDriver pollingDriver(
            String id, Class<? extends PollingTriggerSettings> settingsType, AtomicBoolean validated) {
        return new PollingTriggerDriver() {
            @Override
            public String driverId() {
                return id;
            }

            @Override
            public Class<? extends PollingTriggerSettings> settingsType() {
                return settingsType;
            }

            @Override
            public void validate(TriggerMapping mapping) {
                validated.set(true);
            }

            @Override
            public java.util.concurrent.CompletionStage<PollingTriggerResult> poll(TriggerMapping mapping) {
                return CompletableFuture.completedFuture(PollingTriggerResult.IDLE);
            }
        };
    }

    private static PushTriggerDriver pushDriver(String id, Class<? extends TriggerSettings> settingsType) {
        return new PushTriggerDriver() {
            @Override
            public String driverId() {
                return id;
            }

            @Override
            public Class<? extends TriggerSettings> settingsType() {
                return settingsType;
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> dispatch(TriggerMapping mapping, IntegrationEvent event) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private record TestPollingSettings(int maximumConcurrency) implements PollingTriggerSettings {}

    private record TestPushSettings(String value) implements TriggerSettings {}
}
