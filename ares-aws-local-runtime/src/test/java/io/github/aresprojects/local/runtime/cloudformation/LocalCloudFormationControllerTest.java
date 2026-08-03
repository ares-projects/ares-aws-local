package io.github.aresprojects.local.runtime.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import io.github.aresprojects.local.runtime.service.sqs.InMemorySqsQueueStore;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class LocalCloudFormationControllerTest {
    @Test
    void deploysQueueAndReportsUnsupportedResources() throws Exception {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        LocalCloudFormationController controller = new LocalCloudFormationController(store);

        AwsHttpResponse response = controller
                .handle(request("/_ares/cloudformation/deploy", bundle()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(200, response.statusCode());
        assertTrue(
                new String(response.body(), StandardCharsets.UTF_8).contains("PARTIAL"),
                new String(response.body(), StandardCharsets.UTF_8));
        assertTrue(store.findQueue("http://127.0.0.1:4566/000000000000/ares-cdk-sample")
                .isPresent());
    }

    @Test
    void dryRunDoesNotCreateResources() throws Exception {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        LocalCloudFormationController controller = new LocalCloudFormationController(store);

        AwsHttpResponse response = controller
                .handle(request("/_ares/cloudformation/plan", bundle()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(200, response.statusCode());
        assertTrue(store.findQueue("http://127.0.0.1:4566/000000000000/ares-cdk-sample")
                .isEmpty());
        assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("CREATE"));
    }

    @Test
    void rejectsUnsupportedBundleDescriptorVersionsAndPaths() throws Exception {
        LocalCloudFormationController controller = new LocalCloudFormationController(new InMemorySqsQueueStore());

        AwsHttpResponse version = controller
                .handle(request(
                        "/_ares/cloudformation/deploy",
                        descriptorBundle("{\"schemaVersion\":2,\"assemblyRoot\":\"assembly\",\"parameters\":{}}")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, version.statusCode());
        assertTrue(new String(version.body(), StandardCharsets.UTF_8).contains("schema version"));

        AwsHttpResponse path = controller
                .handle(request(
                        "/_ares/cloudformation/deploy",
                        descriptorBundle("{\"schemaVersion\":1,\"assemblyRoot\":\"../outside\",\"parameters\":{}}")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, path.statusCode());
        assertTrue(new String(path.body(), StandardCharsets.UTF_8).contains("assemblyRoot"));
    }

    @Test
    void rejectsWrongMethodContentTypeMalformedBundlesAndParameters() throws Exception {
        LocalCloudFormationController controller = new LocalCloudFormationController(new InMemorySqsQueueStore());
        AwsHttpResponse method = controller
                .handle(request("GET", "application/zip", "/_ares/cloudformation/deploy", bundle()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, method.statusCode());
        AwsHttpResponse content = controller
                .handle(request("POST", "application/json", "/_ares/cloudformation/deploy", bundle()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(415, content.statusCode());
        AwsHttpResponse malformed = controller
                .handle(request(
                        "POST",
                        "application/zip",
                        "/_ares/cloudformation/deploy",
                        "bad".getBytes(StandardCharsets.UTF_8)))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, malformed.statusCode());

        String template = """
                {"Parameters":{"Name":{"Type":"String","AllowedValues":["valid"]}},
                 "Resources":{"Queue":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":{"Ref":"Name"}}}}}
                """;
        String manifest = """
                {"version":"36.0.0","artifacts":{"Stack":{"type":"aws:cloudformation:stack",
                "properties":{"templateFile":"template.json","stackName":"Stack"}}}}
                """;
        AwsHttpResponse missing = controller
                .handle(request(
                        "POST",
                        "application/zip",
                        "/_ares/cloudformation/deploy",
                        bundle(
                                "{\"schemaVersion\":1,\"assemblyRoot\":\"assembly\",\"stackArtifactId\":\"Stack\","
                                        + "\"parameters\":{}}",
                                manifest,
                                template)))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, missing.statusCode());
        AwsHttpResponse invalid = controller
                .handle(request(
                        "POST",
                        "application/zip",
                        "/_ares/cloudformation/deploy",
                        bundle(
                                "{\"schemaVersion\":1,\"assemblyRoot\":\"assembly\",\"stackArtifactId\":\"Stack\","
                                        + "\"parameters\":{\"Name\":\"invalid\"}}",
                                manifest,
                                template)))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, invalid.statusCode());
    }

    @Test
    void resolvesParametersOutputsAndRejectsMultipleStacksWithoutSelection() throws Exception {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        LocalCloudFormationController controller = new LocalCloudFormationController(store);
        String descriptor = "{\"schemaVersion\":1,\"assemblyRoot\":\"assembly\",\"stackArtifactId\":\"Stack\","
                + "\"parameters\":{\"Name\":\"resolved-queue\"}}";
        String manifest = """
                {"version":"36.0.0","artifacts":{"Stack":{"type":"aws:cloudformation:stack",
                "properties":{"templateFile":"template.json","stackName":"Stack"}}}}
                """;
        String template = """
                {"Parameters":{"Name":{"Type":"String"}},"Resources":{
                 "Queue":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":{"Ref":"Name"}}}},
                 "Outputs":{"QueueUrl":{"Value":{"Ref":"Queue"}}}}
                """;
        AwsHttpResponse response = controller
                .handle(request(
                        "POST",
                        "application/zip",
                        "/_ares/cloudformation/deploy",
                        bundle(descriptor, manifest, template)))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertTrue(
                new String(response.body(), StandardCharsets.UTF_8).contains("resolved-queue"),
                new String(response.body(), StandardCharsets.UTF_8));

        String multiple = """
                {"version":"36.0.0","artifacts":{
                 "First":{"type":"aws:cloudformation:stack","properties":{"templateFile":"template.json"}},
                 "Second":{"type":"aws:cloudformation:stack","properties":{"templateFile":"template.json"}}}}
                """;
        AwsHttpResponse selection = controller
                .handle(request(
                        "POST",
                        "application/zip",
                        "/_ares/cloudformation/deploy",
                        bundle(
                                "{\"schemaVersion\":1,\"assemblyRoot\":\"assembly\",\"parameters\":{}}",
                                multiple,
                                template)))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, selection.statusCode());
    }

    @Test
    void validatesStackSelectionDescriptorParametersAndZipPaths() throws Exception {
        LocalCloudFormationController controller = new LocalCloudFormationController(new InMemorySqsQueueStore());
        String emptyManifest = "{\"version\":\"36.0.0\",\"artifacts\":{}}";
        AwsHttpResponse noStacks = controller
                .handle(request(
                        "/_ares/cloudformation/deploy",
                        bundle(
                                "{\"schemaVersion\":1,\"assemblyRoot\":\"assembly\",\"parameters\":{}}",
                                emptyManifest,
                                "{}")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, noStacks.statusCode());

        String oneManifest = """
                {"version":"36.0.0","artifacts":{"Stack":{"type":"aws:cloudformation:stack",
                "properties":{"templateFile":"template.json"}}}}
                """;
        AwsHttpResponse unknown = controller
                .handle(request(
                        "/_ares/cloudformation/deploy",
                        bundle(
                                "{\"schemaVersion\":1,\"assemblyRoot\":\"assembly\",\"stackArtifactId\":\"Missing\","
                                        + "\"parameters\":{}}",
                                oneManifest,
                                "{\"Resources\":{}}")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, unknown.statusCode());

        AwsHttpResponse badParameters = controller
                .handle(request(
                        "/_ares/cloudformation/deploy",
                        bundle(
                                "{\"schemaVersion\":1,\"assemblyRoot\":\"assembly\",\"stackArtifactId\":\"Stack\","
                                        + "\"parameters\":[]}",
                                oneManifest,
                                "{\"Resources\":{}}")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, badParameters.statusCode());

        AwsHttpResponse unknownParameter = controller
                .handle(request(
                        "/_ares/cloudformation/deploy",
                        bundle(
                                "{\"schemaVersion\":1,\"assemblyRoot\":\"assembly\",\"stackArtifactId\":\"Stack\","
                                        + "\"parameters\":{\"Extra\":\"value\"}}",
                                oneManifest,
                                "{\"Resources\":{}}")))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, unknownParameter.statusCode());

        AwsHttpResponse unsafeZip = controller
                .handle(request("/_ares/cloudformation/deploy", unsafeBundle()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(400, unsafeZip.statusCode());
    }

    @Test
    void identifiesControlPlanePathsAndUsesTheLocalAddressWhenHostIsAbsent() throws Exception {
        LocalCloudFormationController controller = new LocalCloudFormationController(new InMemorySqsQueueStore());
        assertTrue(controller.supports(request("/_ares/cloudformation/plan", bundle())));
        assertTrue(controller.supports(request("/_ares/cloudformation/deploy", bundle())));
        assertFalse(controller.supports(request("/_ares/other", bundle())));

        AwsHttpResponse response = controller
                .handle(requestWithoutHost("/_ares/cloudformation/plan", bundle()))
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
    }

    private static AwsRequestContext request(String path, byte[] body) {
        return request("POST", "application/zip", path, body);
    }

    private static AwsRequestContext request(String method, String contentType, String path, byte[] body) {
        return new AwsRequestContext(
                "request",
                Instant.now(),
                method,
                "HTTP/1.1",
                path,
                Map.of("host", java.util.List.of("127.0.0.1:4566"), "content-type", java.util.List.of(contentType)),
                body,
                InetSocketAddress.createUnresolved("127.0.0.1", 1234),
                InetSocketAddress.createUnresolved("127.0.0.1", 4566));
    }

    private static AwsRequestContext requestWithoutHost(String path, byte[] body) {
        return new AwsRequestContext(
                "request",
                Instant.now(),
                "POST",
                "HTTP/1.1",
                path,
                Map.of("content-type", java.util.List.of("application/zip")),
                body,
                InetSocketAddress.createUnresolved("127.0.0.1", 1234),
                InetSocketAddress.createUnresolved("127.0.0.1", 4566));
    }

    private static byte[] bundle() throws Exception {
        return descriptorBundle("""
                {"schemaVersion":1,"assemblyRoot":"assembly","stackArtifactId":"Stack","parameters":{}}
                """);
    }

    private static byte[] descriptorBundle(String descriptor) throws Exception {
        return bundle(descriptor, """
                {"version":"36.0.0","artifacts":{"Stack":{"type":"aws:cloudformation:stack",
                "properties":{"templateFile":"template.json","stackName":"Stack"}}}}
                """, """
                {"Resources":{"Queue":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"ares-cdk-sample"}},
                "Unsupported":{"Type":"AWS::S3::Bucket"}}}
                """);
    }

    private static byte[] bundle(String descriptor, String manifest, String template) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, "ares-deployment.json", descriptor.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assembly/manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
            entry(zip, "assembly/template.json", template.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static byte[] unsafeBundle() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            entry(zip, "../outside", "unsafe".getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] body) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }
}
