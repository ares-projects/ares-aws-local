package io.github.aresprojects.local.runtime.trigger;

/** Receives structured trigger diagnostics without imposing a logging backend. */
@FunctionalInterface
public interface TriggerDiagnosticsObserver {

    /**
     * Observes one engine event.
     *
     * @param diagnostic the immutable diagnostic
     */
    void onDiagnostic(TriggerDiagnostic diagnostic);

    /**
     * Returns an observer that intentionally discards diagnostics.
     *
     * @return a no-op observer
     */
    static TriggerDiagnosticsObserver none() {
        return diagnostic -> {};
    }
}
