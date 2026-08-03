package io.github.aresprojects.local.cli.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.aresprojects.local.cli.AresConfigurationException;
import io.github.aresprojects.local.cli.AresDeploymentException;
import io.github.aresprojects.local.cloudformation.CloudAssemblyReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloudAssemblyDeploymentServiceTest {
    @Test
    void packagesAndSendsDeterministicAssemblyBundle(@TempDir Path directory) throws Exception {
        writeAssembly(directory, "Stack", "Stack", "Queue");
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = server(200, "{\"status\":\"COMPLETE\"}", path, body);
        try {
            CloudAssemblyDeploymentService.DeploymentOutcome outcome = new CloudAssemblyDeploymentService()
                    .deploy(directory, null, Map.of("Environment", "test"), false, endpoint(server));

            assertEquals("COMPLETE", outcome.status());
            assertEquals("/_ares/cloudformation/deploy", path.get());
            assertTrue(new String(body.get(), StandardCharsets.UTF_8).contains("ares-deployment.json"));
            assertTrue(new String(body.get(), StandardCharsets.UTF_8).contains("manifest.json"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void supportsDryRunAndAssemblyStackParameters(@TempDir Path directory) throws Exception {
        writeAssembly(directory, "Stack", "Stack", "Queue");
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = server(200, "{\"status\":\"PLANNED\"}", path, new AtomicReference<>());
        try {
            CloudAssemblyDeploymentService.DeploymentOutcome outcome =
                    new CloudAssemblyDeploymentService().deploy(directory, "Stack", Map.of(), true, endpoint(server));

            assertEquals("PLANNED", outcome.status());
            assertEquals("/_ares/cloudformation/plan", path.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnknownOrAmbiguousStackSelection(@TempDir Path directory) throws Exception {
        writeAssembly(directory, "First", "First", "Queue");
        Files.writeString(directory.resolve("manifest.json"), """
                        {"version":"36.0.0","artifacts":{
                          "First":{"type":"aws:cloudformation:stack","properties":{"templateFile":"first.json"}},
                          "Second":{"type":"aws:cloudformation:stack","properties":{"templateFile":"second.json"}}}}
                        """);
        Files.writeString(directory.resolve("first.json"), "{\"Resources\":{}}");
        Files.writeString(directory.resolve("second.json"), "{\"Resources\":{}}");
        CloudAssemblyDeploymentService service = new CloudAssemblyDeploymentService();

        AresConfigurationException ambiguous = assertThrows(
                AresConfigurationException.class,
                () -> service.deploy(directory, null, Map.of(), false, URI.create("http://127.0.0.1:1")));
        assertTrue(ambiguous.getMessage().contains("--stack"));

        AresConfigurationException unknown = assertThrows(
                AresConfigurationException.class,
                () -> service.deploy(directory, "Missing", Map.of(), false, URI.create("http://127.0.0.1:1")));
        assertTrue(unknown.getMessage().contains("Missing"));
    }

    @Test
    void rejectsAssemblySymlinksAndEndpointErrors(@TempDir Path directory) throws Exception {
        writeAssembly(directory, "Stack", "Stack", "Queue");
        Path linked = directory.resolve("linked.json");
        Files.createSymbolicLink(linked, directory.resolve("template.json"));
        Files.writeString(directory.resolve("manifest.json"), """
                        {"version":"36.0.0","artifacts":{"Stack":{"type":"aws:cloudformation:stack",
                        "properties":{"templateFile":"linked.json"}}}}
                        """);

        AresConfigurationException symlink = assertThrows(
                AresConfigurationException.class,
                () -> new CloudAssemblyDeploymentService()
                        .deploy(directory, null, Map.of(), false, URI.create("http://127.0.0.1:1")));
        assertTrue(symlink.getMessage().contains("symbolic link"));
    }

    @Test
    void reportsEndpointHttpErrors(@TempDir Path directory) throws Exception {
        writeAssembly(directory, "Stack", "Stack", "Queue");
        HttpServer server = server(400, "{\"error\":\"bad bundle\"}", new AtomicReference<>(), new AtomicReference<>());
        try {
            AresDeploymentException exception = assertThrows(
                    AresDeploymentException.class,
                    () -> new CloudAssemblyDeploymentService()
                            .deploy(directory, null, Map.of(), false, endpoint(server)));
            assertTrue(exception.getMessage().contains("bad bundle"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsConnectionAndInterruptionFailures(@TempDir Path directory) throws Exception {
        writeAssembly(directory, "Stack", "Stack", "Queue");
        AresDeploymentException connection = assertThrows(
                AresDeploymentException.class,
                () -> new CloudAssemblyDeploymentService()
                        .deploy(directory, null, Map.of(), false, URI.create("http://127.0.0.1:1")));
        assertTrue(connection.getMessage().contains("Could not reach"));

        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("test interruption"));
        AresDeploymentException interruption = assertThrows(
                AresDeploymentException.class,
                () -> new CloudAssemblyDeploymentService(client, new ObjectMapper(), new CloudAssemblyReader())
                        .deploy(directory, null, Map.of(), false, URI.create("http://127.0.0.1:4566")));
        assertTrue(interruption.getMessage().contains("Interrupted"));
    }

    @Test
    void rejectsAssemblyWithoutStacks(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("manifest.json"), "{\"version\":\"36.0.0\",\"artifacts\":{}}");

        AresConfigurationException exception = assertThrows(
                AresConfigurationException.class,
                () -> new CloudAssemblyDeploymentService()
                        .deploy(directory, null, Map.of(), false, URI.create("http://127.0.0.1:4566")));
        assertTrue(exception.getMessage().contains("no CloudFormation stack"));
    }

    private static HttpServer server(
            int status, String response, AtomicReference<String> path, AtomicReference<byte[]> body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(exchange.getRequestBody().readAllBytes());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static URI endpoint(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private static void writeAssembly(Path directory, String artifactId, String stackName, String logicalId)
            throws Exception {
        Files.writeString(
                directory.resolve("manifest.json"),
                ("{\"version\":\"36.0.0\",\"artifacts\":{%n"
                                + "  \"%s\":{\"type\":\"aws:cloudformation:stack\",\"properties\":{%n"
                                + "    \"templateFile\":\"template.json\",\"stackName\":\"%s\",%n"
                                + "    \"parameters\":{\"Environment\":\"dev\"}}}}}")
                        .formatted(artifactId, stackName));
        Files.writeString(
                directory.resolve("template.json"),
                ("{\"Resources\":{%n"
                                + "  \"%s\":{\"Type\":\"AWS::SQS::Queue\",\"Properties\":{\"QueueName\":\"queue\"}}}}")
                        .formatted(logicalId));
    }
}
