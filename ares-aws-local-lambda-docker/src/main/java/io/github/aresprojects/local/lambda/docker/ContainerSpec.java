package io.github.aresprojects.local.lambda.docker;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Immutable description of a Lambda execution container. */
public record ContainerSpec(
        String name,
        String image,
        String architecture,
        String functionName,
        String handler,
        Path taskDirectory,
        Map<String, String> environment,
        Map<String, String> labels) {
    public ContainerSpec {
        name = required(name, "name");
        image = required(image, "image");
        architecture = required(architecture, "architecture");
        functionName = required(functionName, "functionName");
        handler = required(handler, "handler");
        taskDirectory = Objects.requireNonNull(taskDirectory, "taskDirectory")
                .toAbsolutePath()
                .normalize();
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        labels = Map.copyOf(Objects.requireNonNull(labels, "labels"));
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
