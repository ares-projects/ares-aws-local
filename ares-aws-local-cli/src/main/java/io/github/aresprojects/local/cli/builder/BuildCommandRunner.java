package io.github.aresprojects.local.cli.builder;

import io.github.aresprojects.local.cli.AresBuildException;
import java.nio.file.Path;
import java.util.List;

/** Runs a validated build argument array in the function project directory. */
@FunctionalInterface
interface BuildCommandRunner {

    /** Executes the command without invoking a shell. */
    BuildProcessResult run(List<String> command, Path workingDirectory) throws AresBuildException;
}
