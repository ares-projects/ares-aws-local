package io.github.aresprojects.local.cli.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Describes the argument-array build command and generated artifact for a function. */
public record AresBuildDescriptor(List<String> command, String artifact) {
    public AresBuildDescriptor {
        command = command == null ? null : Collections.unmodifiableList(new ArrayList<>(command));
    }
}
