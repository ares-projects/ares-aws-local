package io.github.aresprojects.local.runtime.trigger;

import java.util.Objects;

/** Connects one source resource to one target through a registered trigger driver. */
public record TriggerMapping(
        String id,
        String driverId,
        AwsResourceReference source,
        AwsResourceReference target,
        boolean enabled,
        TriggerSettings settings) {

    /** Validates the stable identifiers and required mapping values. */
    public TriggerMapping {
        id = requireText(id, "id");
        driverId = requireText(driverId, "driverId");
        source = Objects.requireNonNull(source, "source");
        target = Objects.requireNonNull(target, "target");
        settings = Objects.requireNonNull(settings, "settings");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank; provide a stable trigger identifier");
        }
        return value;
    }
}
