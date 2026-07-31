package io.github.aresprojects.local.lambda.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DockerCliContainerRuntimeTest {
    @Test
    void startsPublishesAndStopsAnOwnedContainerWithStructuredArguments() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        DockerProcessRunner runner = (command, timeout) -> {
            commands.add(command);
            if (command.get(1).equals("run")) {
                return new DockerProcessResult(0, "container-id\n", "");
            }
            if (command.get(1).equals("port")) {
                return new DockerProcessResult(0, "127.0.0.1:45678\n", "");
            }
            if (command.get(1).equals("logs")) {
                return new DockerProcessResult(0, "logs", "");
            }
            return new DockerProcessResult(0, "", "");
        };
        ContainerSpec specification = new ContainerSpec(
                "ares-test",
                "public.ecr.aws/lambda/java@sha256:digest",
                "arm64",
                "hello",
                "Handler::handleRequest",
                Files.createTempDirectory("task-"),
                Map.of("AWS_REGION", "us-east-1"),
                Map.of("ares.local/managed", "true"));
        DockerCliContainerRuntime runtime = new DockerCliContainerRuntime(runner);

        ContainerHandle handle = runtime.start(specification);

        assertEquals(URI.create("http://127.0.0.1:45678"), handle.endpoint());
        assertTrue(commands.get(0).contains("--platform"));
        assertTrue(commands.get(0).contains("--label=ares.local/managed=true"));
        assertEquals("logs", runtime.logs(handle));
        runtime.stop(handle);
        assertEquals(List.of("docker", "rm", "--force", "container-id"), commands.get(3));
    }

    @Test
    void supportsX8664AndReportsDockerDiagnostics() throws Exception {
        List<List<String>> commands = new ArrayList<>();
        DockerProcessRunner runner = (command, timeout) -> {
            commands.add(command);
            return command.get(1).equals("run")
                    ? new DockerProcessResult(0, "container-id", "")
                    : command.get(1).equals("port")
                            ? new DockerProcessResult(0, "127.0.0.1:45678", "")
                            : new DockerProcessResult(0, "", "diagnostic");
        };
        ContainerSpec specification = new ContainerSpec(
                "ares-test",
                "image",
                "x86_64",
                "hello",
                "Handler::handleRequest",
                Files.createTempDirectory("task-"),
                Map.of(),
                Map.of());
        DockerCliContainerRuntime runtime = new DockerCliContainerRuntime(runner);

        ContainerHandle handle = runtime.start(specification);

        assertTrue(commands.get(0).contains("linux/amd64"));
        assertEquals("diagnostic", runtime.logs(handle));
    }

    @Test
    void classifiesImagePullFailure() throws Exception {
        DockerProcessRunner runner = (command, timeout) -> command.get(1).equals("run")
                ? new DockerProcessResult(1, "", "no such image: missing")
                : new DockerProcessResult(0, "", "");
        ContainerSpec specification = new ContainerSpec(
                "ares-test",
                "missing",
                "arm64",
                "hello",
                "Handler::handleRequest",
                Files.createTempDirectory("task-"),
                Map.of(),
                Map.of());

        DockerExecutionException exception = org.junit.jupiter.api.Assertions.assertThrows(
                DockerExecutionException.class, () -> new DockerCliContainerRuntime(runner).start(specification));

        assertEquals(DockerExecutionFailure.IMAGE_UNAVAILABLE, exception.failure());
    }

    @Test
    void rejectsUnsupportedArchitectureBeforeCallingDocker() throws Exception {
        ContainerSpec specification = new ContainerSpec(
                "ares-test",
                "image",
                "ppc64le",
                "hello",
                "Handler::handleRequest",
                Files.createTempDirectory("task-"),
                Map.of(),
                Map.of());

        DockerExecutionException exception = assertThrows(
                DockerExecutionException.class,
                () -> new DockerCliContainerRuntime((command, timeout) -> {
                            throw new AssertionError("Docker must not be called");
                        })
                        .start(specification));

        assertEquals(DockerExecutionFailure.ARCHITECTURE_MISMATCH, exception.failure());
    }

    @Test
    void rejectsEmptyContainerIdAndMalformedPublishedPort() throws Exception {
        ContainerSpec specification = new ContainerSpec(
                "ares-test",
                "image",
                "arm64",
                "hello",
                "Handler::handleRequest",
                Files.createTempDirectory("task-"),
                Map.of(),
                Map.of());

        DockerExecutionException missingId = assertThrows(
                DockerExecutionException.class,
                () -> new DockerCliContainerRuntime((command, timeout) -> new DockerProcessResult(0, " \n", ""))
                        .start(specification));
        assertEquals(DockerExecutionFailure.COMMAND_FAILED, missingId.failure());

        DockerExecutionException malformedPort = assertThrows(
                DockerExecutionException.class,
                () -> new DockerCliContainerRuntime(
                                (command, timeout) -> command.get(1).equals("run")
                                        ? new DockerProcessResult(0, "container\n", "")
                                        : new DockerProcessResult(0, "not-a-port\n", ""))
                        .start(specification));
        assertEquals(DockerExecutionFailure.COMMAND_FAILED, malformedPort.failure());
    }

    @Test
    void rejectsMissingTaskDirectoryAndFailedPortPublication() throws Exception {
        ContainerSpec missingDirectory = new ContainerSpec(
                "ares-test", "image", "arm64", "hello", "handler", Path.of("missing-task"), Map.of(), Map.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> new DockerCliContainerRuntime((command, timeout) -> {
                            throw new AssertionError("Docker must not be called");
                        })
                        .start(missingDirectory));

        List<List<String>> commands = new ArrayList<>();
        DockerExecutionException exception = assertThrows(
                DockerExecutionException.class,
                () -> new DockerCliContainerRuntime((command, timeout) -> {
                            commands.add(command);
                            return command.get(1).equals("run")
                                    ? new DockerProcessResult(0, "container", "")
                                    : new DockerProcessResult(1, "", "platform mismatch");
                        })
                        .start(new ContainerSpec(
                                "ares-test",
                                "image",
                                "arm64",
                                "hello",
                                "handler",
                                Files.createTempDirectory("task-"),
                                Map.of(),
                                Map.of())));
        assertEquals(DockerExecutionFailure.ARCHITECTURE_MISMATCH, exception.failure());
        assertEquals("rm", commands.get(2).get(1));
    }
}
