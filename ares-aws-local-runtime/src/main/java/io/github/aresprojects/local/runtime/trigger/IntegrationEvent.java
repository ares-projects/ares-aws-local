package io.github.aresprojects.local.runtime.trigger;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** Carries a service-owned event to matching push trigger mappings. */
public record IntegrationEvent(
        String id, AwsResourceReference source, String eventType, Instant occurredAt, byte[] payload) {

    /** Takes an immutable snapshot of the event payload. */
    public IntegrationEvent {
        id = requireText(id, "id");
        source = Objects.requireNonNull(source, "source");
        eventType = requireText(eventType, "eventType");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        payload = Arrays.copyOf(Objects.requireNonNull(payload, "payload"), payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank; provide an identifiable event value");
        }
        return value;
    }
}
