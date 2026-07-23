package io.github.aresprojects.local.runtime.trigger;

import java.util.concurrent.CompletionStage;

/** Publishes service events to matching push integrations without direct service-to-service calls. */
@FunctionalInterface
public interface IntegrationEventPublisher {

    /**
     * Dispatches an event to every matching enabled mapping.
     *
     * <p>A failed target is reported through diagnostics and does not prevent other targets from receiving the
     * event.
     *
     * @param event the immutable event to publish
     * @return completion after every matching delivery has finished
     */
    CompletionStage<Void> publish(IntegrationEvent event);
}
