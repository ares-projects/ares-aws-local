package io.github.aresprojects.local.cli.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.aresprojects.local.cli.builder.AresBuildService;
import io.github.aresprojects.local.lambda.InMemoryLambdaFunctionStore;
import io.github.aresprojects.local.lambda.LambdaService;
import io.github.aresprojects.local.lambda.TemporaryLambdaArtifactStore;
import io.github.aresprojects.local.runtime.LocalAwsServer;
import io.github.aresprojects.local.runtime.LocalAwsServerConfig;
import io.github.aresprojects.local.runtime.service.AwsServiceRegistry;
import io.github.aresprojects.local.runtime.service.lambda.LambdaJsonAdapter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AresDeploymentServiceTest {

    @Test
    void createsReconcilesAndUpdatesAFunction(@TempDir Path directory) throws Exception {
        writeProject(directory, new byte[] {1});
        try (LambdaService lambdaService = new LambdaService(
                        new InMemoryLambdaFunctionStore(),
                        new TemporaryLambdaArtifactStore(directory.resolve("staging")),
                        (function, revision) -> {},
                        Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC),
                        "us-east-1");
                LocalAwsServer server = new LocalAwsServer(
                        new LocalAwsServerConfig("127.0.0.1", 0, 10 * 1024 * 1024),
                        AwsServiceRegistry.builder()
                                .register(new LambdaJsonAdapter(lambdaService))
                                .build())) {
            InetSocketAddress address = server.start();
            AresDeploymentService deployment = new AresDeploymentService(
                    new AresBuildService(),
                    new LocalLambdaClient(URI.create("http://" + address.getHostString() + ":" + address.getPort())));

            assertEquals(
                    "Created Lambda function 'hello'",
                    deployment.deploy(directory).summary());
            assertEquals(
                    "Lambda function 'hello' is unchanged",
                    deployment.deploy(directory).summary());

            Files.writeString(directory.resolve("ares.yaml"), descriptor("example.UpdatedHandler"));
            assertEquals(
                    "Updated Lambda function 'hello'",
                    deployment.deploy(directory).summary());

            writeProject(directory, new byte[] {2});
            assertEquals(
                    "Updated Lambda function 'hello'",
                    deployment.deploy(directory).summary());
        }
    }

    private static void writeProject(Path directory, byte[] content) throws Exception {
        Files.writeString(directory.resolve("ares.yaml"), descriptor("example.HelloHandler"));
        Path artifactDirectory = directory.resolve("build");
        Files.createDirectories(artifactDirectory);
        Path artifact = artifactDirectory.resolve("function.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(artifact))) {
            zip.putNextEntry(new ZipEntry("example/HelloHandler.class"));
            zip.write(content);
            zip.closeEntry();
        }
    }

    private static String descriptor(String handler) {
        return """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: %s
                    build:
                      command: [/usr/bin/true]
                      artifact: build/function.zip
                """.replace("%s", handler);
    }
}
