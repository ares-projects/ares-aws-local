package io.github.aresprojects.local.runtime.service.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aresprojects.local.lambda.InMemoryLambdaFunctionStore;
import io.github.aresprojects.local.lambda.LambdaService;
import io.github.aresprojects.local.lambda.TemporaryLambdaArtifactStore;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LambdaJsonAdapterTest {
    private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 4566);

    @Test
    void claimsOnlySupportedLambdaRestRoutes(@TempDir Path directory) {
        try (LambdaService service = service(directory)) {
            LambdaJsonAdapter adapter = new LambdaJsonAdapter(service);

            assertTrue(adapter.supports(request("POST", "/2015-03-31/functions", "{}")));
            assertTrue(adapter.supports(request("GET", "/2015-03-31/functions/hello/", "{}")));
            assertTrue(adapter.supports(request("PUT", "/2015-03-31/functions/hello/configuration", "{}")));
            assertFalse(adapter.supports(request("GET", "/", "{}")));
            assertFalse(adapter.supports(request("POST", "/2015-03-31/functions/hello", "{}")));
            assertFalse(adapter.supports(request("GET", "/2015-03-31/functions/hello/code", "{}")));
        }
    }

    @Test
    void handlesCreateConfigurationAndCodeOperations(@TempDir Path directory) throws Exception {
        try (LambdaService service = service(directory)) {
            LambdaJsonAdapter adapter = new LambdaJsonAdapter(service);
            String code = Base64.getEncoder().encodeToString(zip("handler.class"));
            var created = adapter.handle(request(
                            "POST",
                            "/2015-03-31/functions",
                            "{\"FunctionName\":\"hello\",\"Runtime\":\"java21\","
                                    + "\"Architectures\":[\"arm64\"],\"Handler\":\"Handler\","
                                    + "\"Role\":\"role\",\"Code\":{\"ZipFile\":\""
                                    + code
                                    + "\"}}"))
                    .toCompletableFuture()
                    .join();
            assertEquals(200, created.statusCode());

            var configuration = adapter.handle(request("GET", "/2015-03-31/functions/hello/configuration", "{}"))
                    .toCompletableFuture()
                    .join();
            assertEquals(200, configuration.statusCode());

            var updatedConfiguration = adapter.handle(request(
                            "PUT",
                            "/2015-03-31/functions/hello/configuration",
                            "{\"Description\":\"updated\",\"Environment\":{\"Variables\":{" + "\"MODE\":\"test\"}}}"))
                    .toCompletableFuture()
                    .join();
            assertEquals(200, updatedConfiguration.statusCode());

            var updatedCode = adapter.handle(request(
                            "PUT",
                            "/2015-03-31/functions/hello/code",
                            "{\"ZipFile\":\"" + code + "\",\"Architectures\":[\"x86_64\"]}"))
                    .toCompletableFuture()
                    .join();
            assertEquals(200, updatedCode.statusCode());
            assertEquals(400, status(adapter, "PUT", "/2015-03-31/functions/hello/code", "{\"ZipFile\":\"bad\"}"));
        }
    }

    @Test
    void reportsMalformedBodiesAndUnsupportedFields(@TempDir Path directory) throws Exception {
        try (LambdaService service = service(directory)) {
            LambdaJsonAdapter adapter = new LambdaJsonAdapter(service);
            assertEquals(400, status(adapter, "POST", "/2015-03-31/functions", "{"));
            assertEquals(400, status(adapter, "POST", "/2015-03-31/functions", "[]"));
            assertEquals(400, status(adapter, "POST", "/2015-03-31/functions", "{\"Unknown\":true}"));
            assertEquals(400, status(adapter, "POST", "/2015-03-31/functions", "{\"Code\":{}}"));
            assertEquals(404, status(adapter, "PUT", "/2015-03-31/functions/hello/code", "{\"ZipFile\":\"bad\"}"));
            assertEquals(
                    400, status(adapter, "PUT", "/2015-03-31/functions/hello/configuration", "{\"Qualifier\":\"1\"}"));
        }
    }

    @Test
    void validatesTypedFieldsAndMissingFunctions(@TempDir Path directory) throws Exception {
        try (LambdaService service = service(directory)) {
            LambdaJsonAdapter adapter = new LambdaJsonAdapter(service);
            assertEquals(
                    400,
                    status(
                            adapter,
                            "POST",
                            "/2015-03-31/functions",
                            "{\"FunctionName\":\"hello\","
                                    + "\"Runtime\":\"java21\",\"Handler\":\"Handler\",\"Role\":\"role\","
                                    + "\"Code\":{\"ZipFile\":\"bad\"}}"));
            assertEquals(
                    400,
                    status(
                            adapter,
                            "POST",
                            "/2015-03-31/functions",
                            "{\"FunctionName\":\"hello\","
                                    + "\"Runtime\":\"java21\",\"Handler\":\"Handler\",\"Role\":\"role\","
                                    + "\"Architectures\":[],\"Code\":{\"ZipFile\":\"bad\"}}"));
            assertEquals(
                    400,
                    status(
                            adapter,
                            "POST",
                            "/2015-03-31/functions",
                            "{\"FunctionName\":\"hello\","
                                    + "\"Runtime\":\"java21\",\"Handler\":\"Handler\",\"Role\":\"role\","
                                    + "\"Timeout\":\"three\",\"Code\":{\"ZipFile\":\"bad\"}}"));
            assertEquals(
                    400,
                    status(
                            adapter,
                            "POST",
                            "/2015-03-31/functions",
                            "{\"FunctionName\":\"hello\","
                                    + "\"Runtime\":\"java21\",\"Handler\":\"Handler\",\"Role\":\"role\","
                                    + "\"Environment\":{\"Variables\":{\"A\":true}},"
                                    + "\"Code\":{\"ZipFile\":\"bad\"}}"));
            assertEquals(404, status(adapter, "GET", "/2015-03-31/functions/missing", "{}"));
            assertEquals(404, status(adapter, "DELETE", "/2015-03-31/functions/missing", "{}"));
        }
    }

    private static LambdaService service(Path directory) {
        return new LambdaService(
                new InMemoryLambdaFunctionStore(),
                new TemporaryLambdaArtifactStore(directory.resolve("artifacts")),
                (function, revision) -> {},
                Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC),
                "us-east-1");
    }

    private static int status(LambdaJsonAdapter adapter, String method, String path, String body) {
        return adapter.handle(request(method, path, body))
                .toCompletableFuture()
                .join()
                .statusCode();
    }

    private static AwsRequestContext request(String method, String path, String body) {
        return new AwsRequestContext(
                "request-id",
                Instant.parse("2026-07-27T00:00:00Z"),
                method,
                "HTTP/1.1",
                path,
                Map.of(
                        "Content-Type", List.of("application/json"),
                        "Host", List.of("127.0.0.1:4566")),
                body.getBytes(StandardCharsets.UTF_8),
                ADDRESS,
                ADDRESS);
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
