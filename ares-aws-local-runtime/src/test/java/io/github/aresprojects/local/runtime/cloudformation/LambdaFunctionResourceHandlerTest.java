package io.github.aresprojects.local.runtime.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aresprojects.local.cloudformation.CloudAssemblyAssetResolver;
import io.github.aresprojects.local.cloudformation.CloudFormationException;
import io.github.aresprojects.local.cloudformation.ProvisionedResource;
import io.github.aresprojects.local.cloudformation.ResourceOperationContext;
import io.github.aresprojects.local.cloudformation.TemplateResource;
import io.github.aresprojects.local.lambda.InMemoryLambdaFunctionStore;
import io.github.aresprojects.local.lambda.LambdaService;
import io.github.aresprojects.local.lambda.NoOpLambdaExecutionBackend;
import io.github.aresprojects.local.lambda.TemporaryLambdaArtifactStore;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LambdaFunctionResourceHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsReadsAndDeletesAFunctionFromAnAssemblyAsset(@TempDir Path directory) throws Exception {
        Path asset = directory.resolve("asset.0123456789abcdef.zip");
        Files.write(asset, zip("example/HelloHandler.class"));
        try (LambdaService service = service(directory)) {
            LambdaFunctionResourceHandler handler = new LambdaFunctionResourceHandler(service);
            TemplateResource resource = resource();
            JsonNode properties = properties();

            handler.validate(context(directory), resource, properties);
            ProvisionedResource created = handler.create(context(directory), resource, properties);

            assertEquals("hello", created.referenceValue());
            assertEquals("hello", created.attributes().get("FunctionName"));
            assertTrue(created.attributes().get("Arn").endsWith(":function:hello"));
            assertTrue(handler.read(context(directory), created).isPresent());

            handler.delete(context(directory), created);
            assertTrue(handler.read(context(directory), created).isEmpty());
        }
    }

    @Test
    void rejectsUnsupportedPropertiesAndMissingAssets(@TempDir Path directory) throws Exception {
        try (LambdaService service = service(directory)) {
            LambdaFunctionResourceHandler handler = new LambdaFunctionResourceHandler(service);
            JsonNode unsupported = properties().deepCopy().set("Layers", mapper.createArrayNode());
            assertThrows(
                    CloudFormationException.class, () -> handler.validate(context(directory), resource(), unsupported));

            JsonNode missing = properties();
            TemplateResource missingAsset = new TemplateResource(
                    "Function",
                    "AWS::Lambda::Function",
                    missing,
                    mapper.createObjectNode().put("aws:asset:path", "asset.missing.zip"),
                    List.of(),
                    null,
                    0);
            assertThrows(
                    CloudFormationException.class, () -> handler.validate(context(directory), missingAsset, missing));
        }
    }

    @Test
    void rejectsUnsupportedUpdates(@TempDir Path directory) throws Exception {
        try (LambdaService service = service(directory)) {
            LambdaFunctionResourceHandler handler = new LambdaFunctionResourceHandler(service);
            ProvisionedResource current = new ProvisionedResource(
                    "Function", "AWS::Lambda::Function", "hello", "hello", Map.of(), properties());
            assertThrows(
                    CloudFormationException.class,
                    () -> handler.update(context(directory), resource(), properties(), current));
        }
    }

    @Test
    void validatesRequiredPropertiesDefaultsAndEnvironmentValues(@TempDir Path directory) throws Exception {
        Path asset = directory.resolve("asset.0123456789abcdef.zip");
        Files.write(asset, zip("example/HelloHandler.class"));
        try (LambdaService service = service(directory)) {
            LambdaFunctionResourceHandler handler = new LambdaFunctionResourceHandler(service);

            for (String required : List.of("Code", "Handler", "Role", "Runtime")) {
                ObjectNode missing = properties();
                missing.remove(required);
                assertThrows(
                        CloudFormationException.class, () -> handler.validate(context(directory), resource(), missing));
            }

            ObjectNode defaults = properties();
            defaults.remove("Architectures");
            defaults.remove("FunctionName");
            handler.validate(context(directory), resource(), defaults);
            ProvisionedResource created = handler.create(context(directory), resource(), defaults);
            assertTrue(created.physicalId().startsWith("Stack-Function-"));
            assertEquals(
                    "x86_64", service.find(created.physicalId()).orElseThrow().architecture());

            ObjectNode invalidArchitecture = properties();
            invalidArchitecture.set(
                    "Architectures", mapper.createArrayNode().add("arm64").add("x86_64"));
            assertThrows(
                    CloudFormationException.class,
                    () -> handler.validate(context(directory), resource(), invalidArchitecture));

            ObjectNode invalidEnvironment = properties();
            invalidEnvironment.set("Environment", mapper.createObjectNode().put("Variables", "not-an-object"));
            assertThrows(
                    CloudFormationException.class,
                    () -> handler.validate(context(directory), resource(), invalidEnvironment));

            ObjectNode invalidVariable = properties();
            invalidVariable.set(
                    "Environment",
                    mapper.createObjectNode()
                            .set("Variables", mapper.createObjectNode().put("COUNT", 1)));
            assertThrows(
                    CloudFormationException.class,
                    () -> handler.validate(context(directory), resource(), invalidVariable));
        }
    }

    @Test
    void rejectsNonObjectCodeAndReportsUnreadableAssets(@TempDir Path directory) {
        try (LambdaService service = service(directory)) {
            LambdaFunctionResourceHandler handler = new LambdaFunctionResourceHandler(service);
            ObjectNode nonObjectCode = properties();
            nonObjectCode.put("Code", "not-a-local-asset");
            assertThrows(
                    CloudFormationException.class,
                    () -> handler.validate(context(directory), resource(), nonObjectCode));

            ResourceOperationContext directoryAssetContext = new ResourceOperationContext(
                    "Stack",
                    "000000000000",
                    "us-east-1",
                    URI.create("http://127.0.0.1:4566"),
                    Clock.systemUTC(),
                    Map.of(),
                    ignored -> Optional.of(directory));
            assertThrows(
                    CloudFormationException.class,
                    () -> handler.create(directoryAssetContext, resource(), properties()));
        }
    }

    private LambdaService service(Path directory) {
        return new LambdaService(
                new InMemoryLambdaFunctionStore(),
                new TemporaryLambdaArtifactStore(directory.resolve("artifacts")),
                new NoOpLambdaExecutionBackend(),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                "us-east-1");
    }

    private TemplateResource resource() {
        return new TemplateResource(
                "Function",
                "AWS::Lambda::Function",
                properties(),
                mapper.createObjectNode().put("aws:asset:path", "asset.0123456789abcdef.zip"),
                List.of(),
                null,
                0);
    }

    private ObjectNode properties() {
        ObjectNode properties = mapper.createObjectNode();
        properties.put("FunctionName", "hello");
        properties.put("Handler", "example.HelloHandler");
        properties.put("Role", "arn:aws:iam::000000000000:role/lambda-local");
        properties.put("Runtime", "java21");
        properties.put("Timeout", 10);
        properties.put("MemorySize", 256);
        properties.set("Architectures", mapper.createArrayNode().add("arm64"));
        properties.set("Code", mapper.createObjectNode().put("S3Key", "0123456789abcdef.zip"));
        return properties;
    }

    private ResourceOperationContext context(Path directory) {
        return new ResourceOperationContext(
                "Stack",
                "000000000000",
                "us-east-1",
                URI.create("http://127.0.0.1:4566"),
                Clock.systemUTC(),
                Map.of(),
                new CloudAssemblyAssetResolver(directory));
    }

    private static byte[] zip(String entryName) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write("class".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
