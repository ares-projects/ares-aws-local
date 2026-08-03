package io.github.aresprojects.local.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.aresprojects.local.cli.builder.AresBuildService;
import io.github.aresprojects.local.cli.deploy.AresDeploymentService;
import io.github.aresprojects.local.cli.deploy.AresDeploymentService.DeploymentOutcome;
import io.github.aresprojects.local.cli.deploy.LambdaClientException;
import io.github.aresprojects.local.cli.deploy.LocalLambdaClient;
import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvocationResult;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AresCliTest {
    @Test
    void rejectsUnknownCommandsWithUsageExitCode() {
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService());
        AresCli defaultCli = new AresCli();

        int exitCode = cli.execute("unknown", "function");

        assertEquals(AresExitCode.USAGE_ERROR.value(), exitCode);
        assertEquals(true, errors.getFirst().contains("Usage"));
        assertEquals(AresExitCode.USAGE_ERROR.value(), defaultCli.execute("unknown"));
    }

    @Test
    void mapsInvalidProjectPathToUsageError() {
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService());

        int exitCode = cli.execute("build", "does-not-exist");

        assertEquals(AresExitCode.USAGE_ERROR.value(), exitCode);
        assertEquals(true, errors.getFirst().contains("directory"));
    }

    @Test
    void startsAndReportsRuntimeFailuresThroughTheConfiguredLifecycle(@TempDir Path directory) {
        List<String> errors = new ArrayList<>();
        AresCli running = new AresCli(message -> {}, errors::add, new AresBuildService(), () -> {});
        AresCli failing = new AresCli(message -> {}, errors::add, new AresBuildService(), () -> {
            throw new IllegalStateException("port 4566 is unavailable");
        });

        assertEquals(AresExitCode.SUCCESS.value(), running.execute("local", "start"));
        assertEquals(AresExitCode.RUNTIME_UNAVAILABLE.value(), failing.execute("local", "start"));
        assertEquals(true, errors.getFirst().contains("port 4566 is unavailable"));
    }

    @Test
    void buildsAndPrintsTheDeploymentSummary(@TempDir Path directory) throws Exception {
        writeProject(directory, true);
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(output::add, errors::add, new AresBuildService());

        int exitCode = cli.execute("build", directory.toString());

        assertEquals(AresExitCode.SUCCESS.value(), exitCode, errors.toString());
        assertEquals(3, output.size());
        assertEquals(true, output.getFirst().contains("Built Lambda function 'hello'"));
    }

    @Test
    void mapsInvalidArtifactToBuildFailure(@TempDir Path directory) throws Exception {
        writeProject(directory, false);
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService());

        int exitCode = cli.execute("build", directory.toString());

        assertEquals(AresExitCode.BUILD_FAILED.value(), exitCode, errors.toString());
        assertEquals(true, errors.getFirst().contains("not a readable ZIP"));
    }

    @Test
    void deploysAndMapsDeploymentFailures(@TempDir Path directory) throws Exception {
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        AresDeploymentService deployment = mock(AresDeploymentService.class);
        when(deployment.deploy(any()))
                .thenReturn(new DeploymentOutcome("Created Lambda function 'hello'", "java21", "http://local"))
                .thenThrow(new AresConfigurationException("invalid configuration"))
                .thenThrow(new AresBuildException("build failed"))
                .thenThrow(new AresDeploymentException("deployment failed"));
        AresCli cli = new AresCli(output::add, errors::add, new AresBuildService(), deployment, () -> {});

        assertEquals(0, cli.execute("deploy", directory.toString()));
        assertEquals(2, cli.execute("deploy", directory.toString()));
        assertEquals(4, cli.execute("deploy", directory.toString()));
        assertEquals(5, cli.execute("deploy", directory.toString()));
        assertEquals(3, output.size());
        assertEquals("Created Lambda function 'hello'", output.getFirst());
        assertEquals("Runtime: java21", output.get(1));
        assertEquals("Endpoint: http://local", output.get(2));
        assertEquals("invalid configuration", errors.get(0));
        assertEquals("build failed", errors.get(1));
        assertEquals("deployment failed", errors.get(2));
        assertEquals(2, cli.execute("deploy", "bad\0path"));
    }

    @Test
    void handlesNullAndInvalidArgumentsAndConstructorDependencies() {
        AresCli cli = new AresCli(message -> {}, message -> {}, new AresBuildService());
        List<String> errors = new ArrayList<>();
        AresCli invalidPathCli = new AresCli(message -> {}, errors::add, new AresBuildService());

        assertEquals(AresExitCode.USAGE_ERROR.value(), cli.execute((String[]) null));
        assertEquals(2, invalidPathCli.execute("build", "bad\0path"));
        assertEquals(true, errors.getFirst().contains("Invalid function path"));
        assertThrows(NullPointerException.class, () -> new AresCli(null, message -> {}, new AresBuildService()));
        assertThrows(NullPointerException.class, () -> new AresCli(message -> {}, null, new AresBuildService()));
        assertThrows(NullPointerException.class, () -> new AresCli(message -> {}, message -> {}, null));
        assertEquals(2, AresExitCode.USAGE_ERROR.value());
        assertEquals(3, AresExitCode.RUNTIME_UNAVAILABLE.value());
        assertEquals(4, AresExitCode.BUILD_FAILED.value());
    }

    @Test
    void invokesFunctionAndWritesOnlyThePayloadToOutput() throws Exception {
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        LocalLambdaClient client = mock(LocalLambdaClient.class);
        when(client.invokeFunction("hello", "{}".getBytes(StandardCharsets.UTF_8)))
                .thenReturn(LambdaInvocationResult.success("{\"message\":\"Hello\"}".getBytes(StandardCharsets.UTF_8)));
        AresCli cli = new AresCli(output::add, errors::add, new AresBuildService(), mock(), client, () -> {});

        int exitCode = cli.execute("invoke", "hello");

        assertEquals(AresExitCode.SUCCESS.value(), exitCode);
        assertEquals(List.of("{\"message\":\"Hello\"}"), output);
        assertEquals(List.of(), errors);
    }

    @Test
    void preservesFunctionErrorsAndUsesTheFunctionErrorExitCode() throws Exception {
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        LocalLambdaClient client = mock(LocalLambdaClient.class);
        when(client.invokeFunction("hello", "{\"name\":\"Ada\"}".getBytes(StandardCharsets.UTF_8)))
                .thenReturn(LambdaInvocationResult.functionError(
                        "{\"errorMessage\":\"failed\"}".getBytes(StandardCharsets.UTF_8), "Unhandled"));
        AresCli cli = new AresCli(output::add, errors::add, new AresBuildService(), mock(), client, () -> {});

        int exitCode = cli.execute("invoke", "hello", "--payload", "{\"name\":\"Ada\"}");

        assertEquals(AresExitCode.FUNCTION_ERROR.value(), exitCode);
        assertEquals(List.of("{\"errorMessage\":\"failed\"}"), output);
        assertEquals(List.of("Lambda function 'hello' returned function error 'Unhandled'"), errors);
    }

    @Test
    void mapsInvalidInvocationAndInfrastructureFailures() throws Exception {
        List<String> errors = new ArrayList<>();
        LocalLambdaClient client = mock(LocalLambdaClient.class);
        when(client.invokeFunction("hello", "{}".getBytes(StandardCharsets.UTF_8)))
                .thenThrow(new LambdaClientException("LocalEndpointUnavailable", 0, "endpoint unavailable"));
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService(), mock(), client, () -> {});

        assertEquals(AresExitCode.USAGE_ERROR.value(), cli.execute("invoke", "hello", "--event"));
        assertEquals(AresExitCode.INFRASTRUCTURE_UNAVAILABLE.value(), cli.execute("invoke", "hello"));
        assertEquals(2, AresExitCode.USAGE_ERROR.value());
        assertEquals(6, AresExitCode.FUNCTION_ERROR.value());
        assertEquals(7, AresExitCode.INFRASTRUCTURE_UNAVAILABLE.value());
    }

    @Test
    void readsEventFilesAndRejectsMalformedInvocationOptions(@TempDir Path directory) throws Exception {
        Path event = directory.resolve("event.json");
        Files.writeString(event, "{\"name\":\"Ada\"}");
        LocalLambdaClient client = mock(LocalLambdaClient.class);
        when(client.invokeFunction("hello", "{\"name\":\"Ada\"}".getBytes(StandardCharsets.UTF_8)))
                .thenReturn(LambdaInvocationResult.success("{}".getBytes(StandardCharsets.UTF_8)));
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService(), mock(), client, () -> {});

        assertEquals(0, cli.execute("invoke", "hello", "--event", event.toString()));
        verify(client).invokeFunction("hello", "{\"name\":\"Ada\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(
                2,
                cli.execute(
                        "invoke",
                        "hello",
                        "--event",
                        directory.resolve("missing.json").toString()));
        assertEquals(2, cli.execute("invoke", "hello", "--payload", "{}", "--event", event.toString()));
        assertEquals(2, cli.execute("invoke", "hello", "--event", event.toString(), "--payload", "{}"));
        assertEquals(2, cli.execute("invoke", "hello", "--endpoint"));
        assertEquals(2, cli.execute("invoke", "hello", "--endpoint", "http://[invalid"));
        assertEquals(2, cli.execute("invoke", "hello", "--unknown"));
        assertEquals(6, errors.size());
    }

    @Test
    void invokesThroughAnExplicitEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/2015-03-31/functions/hello/invocations", exchange -> {
            byte[] response = "{\"message\":\"Hello\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            List<String> output = new ArrayList<>();
            AresCli cli = new AresCli(output::add, message -> {}, new AresBuildService(), mock(), mock(), () -> {});

            assertEquals(
                    0,
                    cli.execute(
                            "invoke",
                            "hello",
                            "--endpoint",
                            "http://127.0.0.1:" + server.getAddress().getPort()));
            assertEquals(List.of("{\"message\":\"Hello\"}"), output);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deploysCloudAssemblyUsingAutoDetectionAndOptions(@TempDir Path directory) throws Exception {
        Path assembly = directory.resolve("cdk.out");
        Files.createDirectories(assembly);
        writeCloudAssembly(assembly);
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_ares/cloudformation/plan", exchange -> {
            assertEquals("application/zip", exchange.getRequestHeaders().getFirst("content-type"));
            assertTrue(exchange.getRequestBody().readAllBytes().length > 0);
            byte[] response = "{\"status\":\"PLANNED\",\"outputs\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(response);
            }
        });
        server.start();
        try {
            AresCli cli = new AresCli(output::add, errors::add, new AresBuildService());

            int exitCode = cli.execute(
                    "deploy",
                    directory.toString(),
                    "--stack",
                    "Stack",
                    "--parameter",
                    "Environment=test",
                    "--dry-run",
                    "--endpoint",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/");

            assertEquals(AresExitCode.SUCCESS.value(), exitCode);
            assertEquals(2, output.size());
            assertTrue(output.getFirst().contains("PLANNED"));
            assertTrue(errors.isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsCloudAssemblyAndLambdaOptionMixing(@TempDir Path directory) throws Exception {
        Path assembly = directory.resolve("cdk.out");
        Files.createDirectories(assembly);
        writeCloudAssembly(assembly);
        Files.writeString(directory.resolve("ares.yaml"), "schemaVersion: 1\n");
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService());

        assertEquals(AresExitCode.USAGE_ERROR.value(), cli.execute("deploy", directory.toString()));
        assertTrue(errors.getFirst().contains("both ares.yaml"));
        assertEquals(AresExitCode.USAGE_ERROR.value(), cli.execute("deploy", directory.toString(), "--stack"));
        assertTrue(errors.get(1).contains("both ares.yaml"));
    }

    @Test
    void mapsCloudAssemblyPartialRollbackAndOptionFailures(@TempDir Path directory) throws Exception {
        Path assembly = directory.resolve("cdk.out");
        Files.createDirectories(assembly);
        writeCloudAssembly(assembly);
        List<String> errors = new ArrayList<>();
        List<String> output = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> status =
                new java.util.concurrent.atomic.AtomicReference<>("PARTIAL");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/_ares/cloudformation/deploy", exchange -> {
            byte[] response =
                    ("{\"status\":\"" + status.get() + "\",\"diagnostics\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(response);
            }
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            AresCli cli = new AresCli(output::add, errors::add, new AresBuildService());
            assertEquals(8, cli.execute("deploy", directory.toString(), "--endpoint", endpoint));
            status.set("ROLLBACK_COMPLETE");
            assertEquals(5, cli.execute("deploy", directory.toString(), "--endpoint", endpoint));
            assertEquals(2, cli.execute("deploy", directory.toString(), "--unknown"));
            assertEquals(2, cli.execute("deploy", directory.toString(), "--parameter", "invalid"));
            assertEquals(2, cli.execute("deploy", directory.toString(), "--stack", "Missing"));
            assertTrue(errors.size() >= 5);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAssemblyOptionsForLambdaProjects(@TempDir Path directory) {
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService());

        assertEquals(2, cli.execute("deploy", directory.toString(), "--stack", "Stack"));
        assertTrue(errors.getFirst().contains("does not accept Cloud Assembly options"));
    }

    @Test
    void mapsRemoteAndUnexpectedInvocationFailures() throws Exception {
        List<String> errors = new ArrayList<>();
        LocalLambdaClient remoteFailure = mock(LocalLambdaClient.class);
        when(remoteFailure.invokeFunction("hello", "{}".getBytes(StandardCharsets.UTF_8)))
                .thenThrow(new LambdaClientException("BadRequest", 400, "bad request"))
                .thenThrow(new LambdaClientException("ServiceException", 400, "service unavailable"))
                .thenThrow(new LambdaClientException("InternalFailure", 400, "internal failure"))
                .thenThrow(new LambdaClientException("ServerFailure", 500, "server failure"))
                .thenThrow(new IllegalStateException("unexpected failure"));
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService(), mock(), remoteFailure, () -> {});

        assertEquals(1, cli.execute("invoke", "hello"));
        assertEquals(7, cli.execute("invoke", "hello"));
        assertEquals(7, cli.execute("invoke", "hello"));
        assertEquals(7, cli.execute("invoke", "hello"));
        assertEquals(1, cli.execute("invoke", "hello"));
        assertEquals(5, errors.size());
    }

    private static void writeProject(Path directory, boolean validZip) throws Exception {
        Files.writeString(directory.resolve("ares.yaml"), """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.HelloHandler
                    build:
                      command: [/usr/bin/true]
                      artifact: build/function.zip
                """);
        Files.createDirectories(directory.resolve("build"));
        if (validZip) {
            try (ZipOutputStream zip =
                    new ZipOutputStream(Files.newOutputStream(directory.resolve("build/function.zip")))) {
                zip.putNextEntry(new ZipEntry("example/HelloHandler.class"));
                zip.write(new byte[] {1});
                zip.closeEntry();
            }
        } else {
            Files.writeString(directory.resolve("build/function.zip"), "not a zip");
        }
    }

    private static void writeCloudAssembly(Path directory) throws Exception {
        Files.writeString(directory.resolve("manifest.json"), """
                {"version":"36.0.0","artifacts":{"Stack":{"type":"aws:cloudformation:stack",
                "properties":{"templateFile":"template.json","stackName":"Stack"}}}}
                """);
        Files.writeString(directory.resolve("template.json"), """
                {"Resources":{"Queue":{"Type":"AWS::SQS::Queue"}}}
                """);
    }
}
