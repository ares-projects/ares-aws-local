package io.github.aresprojects.local.lambda.docker;

import java.net.URI;
import java.util.Objects;

/** Identifies an owned container and its host-side RIE endpoint. */
public record ContainerHandle(String id, String name, URI endpoint) {
    public ContainerHandle {
        id = required(id, "id");
        name = required(name, "name");
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
