package io.github.aresprojects.local.runtime.trigger;

import java.util.concurrent.CompletionStage;

/** Implements a pull-based integration such as SQS or DynamoDB Streams to Lambda. */
public interface PollingTriggerDriver {

    /**
     * Returns the identifier referenced by {@link TriggerMapping#driverId()}.
     *
     * @return a non-blank stable driver identifier
     */
    String driverId();

    /**
     * Returns the settings type accepted by this driver.
     *
     * @return the concrete immutable settings type
     */
    Class<? extends PollingTriggerSettings> settingsType();

    /**
     * Validates source, target, and settings relationships during registry construction.
     *
     * @param mapping a mapping whose settings already match {@link #settingsType()}
     */
    default void validate(TriggerMapping mapping) {}

    /**
     * Claims and delivers at most one source batch for the mapping.
     *
     * @param mapping a validated mapping using this driver
     * @return the asynchronous poll outcome
     */
    CompletionStage<PollingTriggerResult> poll(TriggerMapping mapping);
}
