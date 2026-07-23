package io.github.aresprojects.local.runtime.trigger;

/** Supplies the concurrency limit shared by polling trigger settings. */
public interface PollingTriggerSettings extends TriggerSettings {

    /**
     * Returns the maximum number of simultaneous source batches for one mapping.
     *
     * @return a positive concurrency limit
     */
    int maximumConcurrency();
}
