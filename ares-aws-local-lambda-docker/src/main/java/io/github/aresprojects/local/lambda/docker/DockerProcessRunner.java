package io.github.aresprojects.local.lambda.docker;

import java.time.Duration;
import java.util.List;

/** Executes one Docker CLI command with a bounded lifetime. */
@FunctionalInterface
public interface DockerProcessRunner {

    /** Runs the command and captures bounded stdout and stderr. */
    DockerProcessResult run(List<String> command, Duration timeout);
}
