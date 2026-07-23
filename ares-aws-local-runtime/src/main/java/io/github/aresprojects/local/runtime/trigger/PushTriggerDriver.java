package io.github.aresprojects.local.runtime.trigger;

import java.util.concurrent.CompletionStage;

/** Implements a push-based integration such as an SNS subscription. */
public interface PushTriggerDriver {

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
    Class<? extends TriggerSettings> settingsType();

    /**
     * Validates source, target, and settings relationships during registry construction.
     *
     * @param mapping a mapping whose settings already match {@link #settingsType()}
     */
    default void validate(TriggerMapping mapping) {}

    /**
     * Delivers one source event to the mapped target.
     *
     * @param mapping a validated mapping using this driver
     * @param event the immutable source event
     * @return completion of this target delivery
     */
    CompletionStage<Void> dispatch(TriggerMapping mapping, IntegrationEvent event);
}
