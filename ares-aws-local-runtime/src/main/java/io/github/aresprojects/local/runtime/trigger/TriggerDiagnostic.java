package io.github.aresprojects.local.runtime.trigger;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Describes one structured trigger lifecycle or delivery observation. */
public record TriggerDiagnostic(
        Instant occurredAt,
        TriggerDiagnosticKind kind,
        String mappingId,
        String driverId,
        String detail,
        Optional<Throwable> cause) {

    /** Validates diagnostic fields while allowing engine-wide events to omit mapping identifiers. */
    public TriggerDiagnostic {
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        kind = Objects.requireNonNull(kind, "kind");
        mappingId = Objects.requireNonNull(mappingId, "mappingId");
        driverId = Objects.requireNonNull(driverId, "driverId");
        detail = Objects.requireNonNull(detail, "detail");
        cause = Objects.requireNonNull(cause, "cause");
    }
}
