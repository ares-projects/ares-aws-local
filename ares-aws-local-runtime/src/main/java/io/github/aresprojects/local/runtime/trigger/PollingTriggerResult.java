package io.github.aresprojects.local.runtime.trigger;

/** Reports whether a poll consumed source work so the engine can choose its next delay. */
public enum PollingTriggerResult {
    /** Source work was claimed and delivered or left leased after a delivery failure. */
    WORK_PROCESSED,
    /** No source work was available. */
    IDLE
}
