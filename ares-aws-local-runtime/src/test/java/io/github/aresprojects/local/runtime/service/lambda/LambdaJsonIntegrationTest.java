package io.github.aresprojects.local.runtime.service.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aresprojects.local.lambda.InMemoryLambdaFunctionStore;
import io.github.aresprojects.local.lambda.LambdaService;
import io.github.aresprojects.local.lambda.TemporaryLambdaArtifactStore;
import io.github.aresprojects.local.runtime.LocalAwsServer;
import io.github.aresprojects.local.runtime.LocalAwsServerConfig;
import io.github.aresprojects.local.runtime.service.AwsServiceRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Architecture;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetFunctionRequest;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.lambda.model.UpdateFunctionCodeRequest;
import software.amazon.awssdk.services.lambda.model.UpdateFunctionConfigurationRequest;

class LambdaJsonIntegrationTest {

    @Test
    void sdkV2CanCreateReadUpdateAndDeleteFunction(@TempDir Path directory) {
        LambdaService service = new LambdaService(
                new InMemoryLambdaFunctionStore(),
                new TemporaryLambdaArtifactStore(directory.resolve("artifacts")),
                (function, revision) -> {},
                Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC),
                "us-east-1");
        try (LocalAwsServer server = server(service)) {
            InetSocketAddress address = server.start();
            try (LambdaClient client = client(address)) {
                var created = client.createFunction(CreateFunctionRequest.builder()
                        .functionName("hello")
                        .runtime(Runtime.JAVA21)
                        .architectures(Architecture.ARM64)
                        .handler("example.HelloHandler")
                        .role("arn:aws:iam::000000000000:role/lambda-local")
                        .code(FunctionCode.builder()
                                .zipFile(SdkBytes.fromByteArray(zip("handler.class")))
                                .build())
                        .environment(builder -> builder.variables(environment("GREETING", "Ares")))
                        .build());

                assertEquals("hello", created.functionName());
                assertEquals(Runtime.JAVA21, created.runtime());
                assertEquals(Architecture.ARM64, created.architectures().getFirst());
                String revision = created.revisionId();

                var read = client.getFunction(
                        GetFunctionRequest.builder().functionName("hello").build());
                assertEquals(
                        "Ares", read.configuration().environment().variables().get("GREETING"));
                assertEquals(created.codeSha256(), read.configuration().codeSha256());

                var updated = client.updateFunctionConfiguration(UpdateFunctionConfigurationRequest.builder()
                        .functionName("hello")
                        .handler("example.UpdatedHandler")
                        .build());
                assertEquals("example.UpdatedHandler", updated.handler());
                assertNotEquals(revision, updated.revisionId());

                var updatedCode = client.updateFunctionCode(UpdateFunctionCodeRequest.builder()
                        .functionName("hello")
                        .zipFile(SdkBytes.fromByteArray(zip("updated.class")))
                        .build());
                assertNotEquals(created.codeSha256(), updatedCode.codeSha256());

                client.deleteFunction(
                        DeleteFunctionRequest.builder().functionName("hello").build());
                assertThrows(
                        software.amazon.awssdk.services.lambda.model.ResourceNotFoundException.class,
                        () -> client.getFunction(GetFunctionRequest.builder()
                                .functionName("hello")
                                .build()));
            }
        }
    }

    private static LocalAwsServer server(LambdaService service) {
        return new LocalAwsServer(
                new LocalAwsServerConfig("127.0.0.1", 0, 10 * 1024 * 1024),
                AwsServiceRegistry.builder()
                        .register(new LambdaJsonAdapter(service))
                        .build());
    }

    private static LambdaClient client(InetSocketAddress address) {
        return LambdaClient.builder()
                .endpointOverride(URI.create("http://" + address.getHostString() + ":" + address.getPort()))
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create("access-key", "secret-key")))
                .build();
    }

    private static Map<String, String> environment(String key, String value) {
        return Map.of(key, value);
    }

    private static byte[] zip(String name) {
        try (var output = new java.io.ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(new byte[] {1, 2, 3});
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError("Could not create ZIP fixture", exception);
        }
    }
}
