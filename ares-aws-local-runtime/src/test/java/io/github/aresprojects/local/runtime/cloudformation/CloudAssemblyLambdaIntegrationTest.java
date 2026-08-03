package io.github.aresprojects.local.runtime.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.lambda.InMemoryLambdaFunctionStore;
import io.github.aresprojects.local.lambda.LambdaService;
import io.github.aresprojects.local.lambda.NoOpLambdaExecutionBackend;
import io.github.aresprojects.local.lambda.TemporaryLambdaArtifactStore;
import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import io.github.aresprojects.local.runtime.service.sqs.InMemorySqsQueueStore;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class CloudAssemblyLambdaIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deploysTheSynthesizedCdkSqsLambdaAssembly() throws Exception {
        Path assembly = repositoryRoot().resolve("examples/cdk-sqs-lambda/cdk.out");
        assertTrue(Files.isRegularFile(assembly.resolve("manifest.json")), "run npm run synth for the CDK fixture");

        InMemorySqsQueueStore queueStore = new InMemorySqsQueueStore();
        try (LambdaService lambdaService = new LambdaService(
                new InMemoryLambdaFunctionStore(),
                new TemporaryLambdaArtifactStore(),
                new NoOpLambdaExecutionBackend(),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                "us-east-1")) {
            LocalCloudFormationController controller = new LocalCloudFormationController(queueStore, lambdaService);
            AwsHttpResponse response = controller
                    .handle(request(bundle(assembly)))
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(200, response.statusCode());
            JsonNode body = mapper.readTree(response.body());
            assertEquals("COMPLETE", body.path("status").asText());
            String functionName = body.path("outputs").path("FunctionName").asText();
            assertTrue(lambdaService.find(functionName).isPresent());
            assertTrue(body.path("outputs").path("QueueUrl").asText().contains("ares-cdk-sqs-lambda"));
        }
    }

    private AwsRequestContext request(byte[] body) {
        return new AwsRequestContext(
                "request",
                Instant.now(),
                "POST",
                "HTTP/1.1",
                "/_ares/cloudformation/deploy",
                Map.of(
                        "host", List.of("127.0.0.1:4566"),
                        "content-type", List.of("application/zip")),
                body,
                InetSocketAddress.createUnresolved("127.0.0.1", 1234),
                InetSocketAddress.createUnresolved("127.0.0.1", 4566));
    }

    private static byte[] bundle(Path assembly) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            String descriptor = """
                    {"schemaVersion":1,"assemblyRoot":"assembly","stackArtifactId":"CdkSqsLambdaStack","parameters":{}}
                    """;
            entry(zip, "ares-deployment.json", descriptor.getBytes(StandardCharsets.UTF_8));
            try (var files = Files.walk(assembly)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String relative = assembly.relativize(file)
                            .toString()
                            .replace(file.getFileSystem().getSeparator(), "/");
                    entry(zip, "assembly/" + relative, Files.readAllBytes(file));
                }
            }
        }
        return output.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] body) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Ares repository root from the test working directory");
    }
}
