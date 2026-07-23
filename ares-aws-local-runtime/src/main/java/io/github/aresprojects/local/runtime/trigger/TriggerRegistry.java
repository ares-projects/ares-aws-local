package io.github.aresprojects.local.runtime.trigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable startup snapshot of trigger mappings and their polling or push drivers. */
public final class TriggerRegistry {
    private final List<TriggerMapping> mappings;
    private final Map<String, PollingTriggerDriver> pollingDrivers;
    private final Map<String, PushTriggerDriver> pushDrivers;

    private TriggerRegistry(
            List<TriggerMapping> mappings,
            Map<String, PollingTriggerDriver> pollingDrivers,
            Map<String, PushTriggerDriver> pushDrivers) {
        this.mappings = List.copyOf(mappings);
        this.pollingDrivers = Map.copyOf(pollingDrivers);
        this.pushDrivers = Map.copyOf(pushDrivers);
    }

    /**
     * Starts a registry definition for application startup wiring.
     *
     * @return a mutable builder whose builds are immutable snapshots
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns mappings in registration order.
     *
     * @return an immutable mapping list
     */
    public List<TriggerMapping> mappings() {
        return mappings;
    }

    PollingTriggerDriver pollingDriver(String driverId) {
        return pollingDrivers.get(driverId);
    }

    PushTriggerDriver pushDriver(String driverId) {
        return pushDrivers.get(driverId);
    }

    /** Builds a validated registry while preserving mapping registration order. */
    public static final class Builder {
        private final List<TriggerMapping> mappings = new ArrayList<>();
        private final Map<String, PollingTriggerDriver> pollingDrivers = new LinkedHashMap<>();
        private final Map<String, PushTriggerDriver> pushDrivers = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Registers one polling integration implementation.
         *
         * @param driver the driver to register
         * @return this builder
         */
        public Builder registerPollingDriver(PollingTriggerDriver driver) {
            Objects.requireNonNull(driver, "driver");
            String driverId = requireText(driver.driverId(), "driver.driverId()");
            Objects.requireNonNull(driver.settingsType(), "driver.settingsType()");
            rejectDuplicateDriver(driverId);
            pollingDrivers.put(driverId, driver);
            return this;
        }

        /**
         * Registers one push integration implementation.
         *
         * @param driver the driver to register
         * @return this builder
         */
        public Builder registerPushDriver(PushTriggerDriver driver) {
            Objects.requireNonNull(driver, "driver");
            String driverId = requireText(driver.driverId(), "driver.driverId()");
            Objects.requireNonNull(driver.settingsType(), "driver.settingsType()");
            rejectDuplicateDriver(driverId);
            pushDrivers.put(driverId, driver);
            return this;
        }

        /**
         * Registers a mapping for validation when {@link #build()} creates the snapshot.
         *
         * @param mapping the mapping to register
         * @return this builder
         */
        public Builder registerMapping(TriggerMapping mapping) {
            TriggerMapping candidate = Objects.requireNonNull(mapping, "mapping");
            if (mappings.stream().anyMatch(existing -> existing.id().equals(candidate.id()))) {
                throw new IllegalArgumentException(
                        "Trigger mapping id '" + candidate.id() + "' is already registered; use a unique mapping id");
            }
            mappings.add(candidate);
            return this;
        }

        /**
         * Validates driver references and settings types before creating the immutable registry.
         *
         * @return an immutable registry snapshot
         */
        public TriggerRegistry build() {
            mappings.forEach(this::validateMapping);
            return new TriggerRegistry(mappings, pollingDrivers, pushDrivers);
        }

        private void validateMapping(TriggerMapping mapping) {
            PollingTriggerDriver pollingDriver = pollingDrivers.get(mapping.driverId());
            PushTriggerDriver pushDriver = pushDrivers.get(mapping.driverId());
            if (pollingDriver == null && pushDriver == null) {
                throw new IllegalArgumentException("Trigger mapping '" + mapping.id() + "' references unknown driver '"
                        + mapping.driverId() + "'; register that driver before building the registry");
            }
            Class<? extends TriggerSettings> settingsType =
                    pollingDriver == null ? pushDriver.settingsType() : pollingDriver.settingsType();
            if (!settingsType.isInstance(mapping.settings())) {
                throw new IllegalArgumentException("Trigger mapping '" + mapping.id() + "' uses settings type "
                        + mapping.settings().getClass().getName() + " but driver '" + mapping.driverId()
                        + "' requires " + settingsType.getName());
            }
            if (pollingDriver == null) {
                pushDriver.validate(mapping);
            } else {
                pollingDriver.validate(mapping);
            }
        }

        private void rejectDuplicateDriver(String driverId) {
            if (pollingDrivers.containsKey(driverId) || pushDrivers.containsKey(driverId)) {
                throw new IllegalArgumentException(
                        "Trigger driver id '" + driverId + "' is already registered; use a unique driver id");
            }
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank; provide a stable driver identifier");
            }
            return value;
        }
    }
}
