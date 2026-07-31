package io.github.aresprojects.local.lambda.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Runs Docker using structured process arguments rather than a shell command. */
public final class SystemDockerProcessRunner implements DockerProcessRunner {
    static final int MAX_OUTPUT_CHARS = 32_768;

    @Override
    public DockerProcessResult run(List<String> command, Duration timeout) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(timeout, "timeout");
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(false).start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new DockerExecutionException(
                        DockerExecutionFailure.TIMEOUT, "Docker command timed out: " + String.join(" ", command));
            }
            return new DockerProcessResult(
                    process.exitValue(),
                    bounded(process.getInputStream().readAllBytes()),
                    bounded(process.getErrorStream().readAllBytes()));
        } catch (IOException exception) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.DOCKER_UNAVAILABLE,
                    "Docker CLI could not be started; install Docker and ensure 'docker' is on PATH",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DockerExecutionException(
                    DockerExecutionFailure.INTERRUPTED, "Docker command was interrupted", exception);
        }
    }

    private static String bounded(byte[] output) {
        String text = new String(output, StandardCharsets.UTF_8);
        return text.length() <= MAX_OUTPUT_CHARS ? text : text.substring(0, MAX_OUTPUT_CHARS) + "…";
    }
}
