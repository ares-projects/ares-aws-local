package io.github.aresprojects.local.cloudformation;

/** Planned action for one logical resource. */
public enum ResourceAction {
    CREATE,
    NO_OP,
    UPDATE_UNSUPPORTED,
    DELETE_UNSUPPORTED,
    SKIPPED_UNSUPPORTED,
    SKIPPED_CONDITION,
    BLOCKED
}
