package io.github.aresprojects.local.lambda.docker;

import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Implements container lifecycle operations through the installed Docker CLI. */
public final class DockerCliContainerRuntime implements ContainerRuntime {
    private static final int CONTAINER_PORT = 8080;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final DockerProcessRunner processRunner;

    /** Creates a runtime backed by the system Docker CLI. */
    public DockerCliContainerRuntime() {
        this(new SystemDockerProcessRunner());
    }

    /** Injects command execution for deterministic lifecycle tests. */
    public DockerCliContainerRuntime(DockerProcessRunner processRunner) {
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
    }

    @Override
    public ContainerHandle start(ContainerSpec specification) {
        Objects.requireNonNull(specification, "specification");
        if (!Files.isDirectory(specification.taskDirectory())) {
            throw new IllegalArgumentException(
                    "Lambda task directory does not exist: " + specification.taskDirectory());
        }
        List<String> command = new ArrayList<>(List.of(
                "docker",
                "run",
                "--detach",
                "--rm",
                "--name",
                specification.name(),
                "--platform",
                "linux/" + dockerArchitecture(specification.architecture()),
                "--publish",
                "127.0.0.1::" + CONTAINER_PORT,
                "--volume",
                specification.taskDirectory() + ":/var/task:ro"));
        specification.labels().forEach((key, value) -> command.add("--label=" + key + "=" + value));
        specification.environment().forEach((key, value) -> command.add("--env=" + key + "=" + value));
        command.add(specification.image());
        command.add(specification.handler());

        DockerProcessResult started = processRunner.run(command, COMMAND_TIMEOUT);
        if (!started.succeeded()) {
            throw classifyFailure("Docker could not start Lambda container '" + specification.name() + "'", started);
        }
        String id = started.stdout().trim();
        if (id.isBlank()) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.COMMAND_FAILED,
                    "Docker started Lambda container without returning a container ID");
        }
        DockerProcessResult port =
                processRunner.run(List.of("docker", "port", id, CONTAINER_PORT + "/tcp"), COMMAND_TIMEOUT);
        if (!port.succeeded()) {
            stop(new ContainerHandle(id, specification.name(), URI.create("http://127.0.0.1:0")));
            throw classifyFailure("Docker did not publish the Lambda RIE port", port);
        }
        int hostPort = parseHostPort(port.stdout());
        return new ContainerHandle(id, specification.name(), URI.create("http://127.0.0.1:" + hostPort));
    }

    @Override
    public void stop(ContainerHandle container) {
        Objects.requireNonNull(container, "container");
        processRunner.run(List.of("docker", "rm", "--force", container.id()), COMMAND_TIMEOUT);
    }

    @Override
    public String logs(ContainerHandle container) {
        Objects.requireNonNull(container, "container");
        DockerProcessResult result = processRunner.run(List.of("docker", "logs", container.id()), COMMAND_TIMEOUT);
        return result.stdout().isBlank() ? result.stderr() : result.stdout();
    }

    private static int parseHostPort(String output) {
        String lastLine = output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .reduce((first, second) -> second)
                .orElseThrow(() -> new DockerExecutionException(
                        DockerExecutionFailure.COMMAND_FAILED, "Docker returned no published Lambda RIE port"));
        int separator = lastLine.lastIndexOf(':');
        try {
            int port = Integer.parseInt(lastLine.substring(separator + 1).trim());
            if (separator < 0 || port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.COMMAND_FAILED,
                    "Docker returned an invalid Lambda RIE port: '" + lastLine + "'",
                    exception);
        }
    }

    private static String dockerArchitecture(String architecture) {
        return switch (architecture) {
            case "arm64" -> "arm64";
            case "x86_64" -> "amd64";
            default ->
                throw new DockerExecutionException(
                        DockerExecutionFailure.ARCHITECTURE_MISMATCH,
                        "Lambda architecture '" + architecture + "' is not supported by the Docker backend");
        };
    }

    private static DockerExecutionException classifyFailure(String context, DockerProcessResult result) {
        String detail = result.stderr().isBlank()
                ? result.stdout().trim()
                : result.stderr().trim();
        String message = context + (detail.isBlank() ? "" : ": " + detail);
        String normalized = detail.toLowerCase();
        DockerExecutionFailure failure =
                normalized.contains("no such image") || normalized.contains("pull access denied")
                        ? DockerExecutionFailure.IMAGE_UNAVAILABLE
                        : normalized.contains("platform") || normalized.contains("architecture")
                                ? DockerExecutionFailure.ARCHITECTURE_MISMATCH
                                : DockerExecutionFailure.COMMAND_FAILED;
        return new DockerExecutionException(failure, message);
    }
}
