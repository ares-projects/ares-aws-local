package io.github.aresprojects.local.runtime.trigger;

/** Classifies lifecycle and delivery diagnostics emitted by the trigger engine. */
public enum TriggerDiagnosticKind {
    ENGINE_STARTED,
    ENGINE_STOPPED,
    POLL_FAILED,
    PUSH_DELIVERY_FAILED,
    SHUTDOWN_TIMED_OUT
}
