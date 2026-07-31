package io.github.aresprojects.local.lambda.docker;

/** Result of a Docker CLI invocation. */
public record DockerProcessResult(int exitCode, String stdout, String stderr) {
    public boolean succeeded() {
        return exitCode == 0;
    }
}
