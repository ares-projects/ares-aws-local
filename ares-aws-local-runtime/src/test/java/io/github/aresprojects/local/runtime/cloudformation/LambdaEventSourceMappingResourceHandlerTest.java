package io.github.aresprojects.local.runtime.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aresprojects.local.cloudformation.CloudFormationException;
import io.github.aresprojects.local.cloudformation.ProvisionedResource;
import io.github.aresprojects.local.cloudformation.ResourceOperationContext;
import io.github.aresprojects.local.cloudformation.TemplateResource;
import io.github.aresprojects.local.lambda.InMemoryLambdaFunctionStore;
import io.github.aresprojects.local.lambda.LambdaService;
import io.github.aresprojects.local.lambda.NoOpLambdaExecutionBackend;
import io.github.aresprojects.local.lambda.TemporaryLambdaArtifactStore;
import io.github.aresprojects.local.runtime.service.sqs.InMemorySqsQueueStore;
import io.github.aresprojects.local.runtime.trigger.TriggerEngine;
import io.github.aresprojects.local.runtime.trigger.TriggerRegistry;
import io.github.aresprojects.local.runtime.trigger.sqs.SqsLambdaPollingDriver;
import io.github.aresprojects.local.runtime.trigger.sqs.SqsLambdaTriggerSettings;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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

class LambdaEventSourceMappingResourceHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsReadsAndDeletesAnSqsMapping(@TempDir Path directory) {
        InMemorySqsQueueStore queueStore = new InMemorySqsQueueStore();
        String queueUrl = "http://127.0.0.1:4566/000000000000/orders";
        String queueArn = "arn:aws:sqs:us-east-1:000000000000:orders";
        queueStore.createQueue("orders", queueUrl);
        try (LambdaService lambdaService = lambdaService(directory)) {
            lambdaService.create(
                    "orders-function",
                    "java21",
                    "x86_64",
                    "example.Handler",
                    "arn:aws:iam::000000000000:role/lambda-local",
                    "",
                    3,
                    128,
                    Map.of(),
                    zip());
            try (TriggerEngine engine = engine(queueStore, lambdaService)) {
                engine.start();
                LambdaEventSourceMappingResourceHandler handler =
                        new LambdaEventSourceMappingResourceHandler(queueStore, lambdaService, engine);
                TemplateResource resource = resource();
                ObjectNode properties = properties(queueArn, "orders-function");
                properties.put("BatchSize", 25);
                properties.put("MaximumBatchingWindowInSeconds", 2);
                properties.put("Enabled", false);
                properties.set("FunctionResponseTypes", mapper.createArrayNode().add("ReportBatchItemFailures"));
                properties.set("ScalingConfig", mapper.createObjectNode().put("MaximumConcurrency", 4));

                handler.validate(context(), resource, properties);
                ProvisionedResource created = handler.create(context(), resource, properties);

                assertEquals("Stack/Mapping", created.physicalId());
                assertEquals(queueArn, created.attributes().get("EventSourceArn"));
                assertEquals("orders-function", created.attributes().get("FunctionName"));
                assertEquals("Disabled", created.attributes().get("State"));
                SqsLambdaTriggerSettings settings = (SqsLambdaTriggerSettings)
                        engine.findMapping(created.physicalId()).orElseThrow().settings();
                assertEquals(25, settings.batchSize());
                assertEquals(2, settings.batchingWindow().toSeconds());
                assertEquals(4, settings.maximumConcurrency());
                assertTrue(settings.reportBatchItemFailures());
                assertTrue(handler.read(context(), created).isPresent());

                handler.delete(context(), created);
                assertTrue(handler.read(context(), created).isEmpty());
            }
        }
    }

    @Test
    void rejectsUnsupportedPropertiesAndMissingLocalResources(@TempDir Path directory) {
        InMemorySqsQueueStore queueStore = new InMemorySqsQueueStore();
        try (LambdaService lambdaService = lambdaService(directory);
                TriggerEngine engine = engine(queueStore, lambdaService)) {
            engine.start();
            LambdaEventSourceMappingResourceHandler handler =
                    new LambdaEventSourceMappingResourceHandler(queueStore, lambdaService, engine);
            ObjectNode unsupported = properties("arn:aws:sqs:us-east-1:000000000000:orders", "missing");
            unsupported.put("FilterCriteria", "unsupported");
            assertThrows(CloudFormationException.class, () -> handler.validate(context(), resource(), unsupported));

            assertThrows(
                    CloudFormationException.class,
                    () -> handler.create(
                            context(), resource(), properties("arn:aws:sqs:us-east-1:000000000000:orders", "missing")));
        }
    }

    @Test
    void rejectsInvalidMappingProperties(@TempDir Path directory) {
        InMemorySqsQueueStore queueStore = new InMemorySqsQueueStore();
        try (LambdaService lambdaService = lambdaService(directory);
                TriggerEngine engine = engine(queueStore, lambdaService)) {
            engine.start();
            LambdaEventSourceMappingResourceHandler handler =
                    new LambdaEventSourceMappingResourceHandler(queueStore, lambdaService, engine);

            ObjectNode missingFunction = mapper.createObjectNode().put("EventSourceArn", "queue");
            assertThrows(CloudFormationException.class, () -> handler.validate(context(), resource(), missingFunction));

            ObjectNode missingQueue = mapper.createObjectNode().put("FunctionName", "function");
            assertThrows(CloudFormationException.class, () -> handler.validate(context(), resource(), missingQueue));

            ObjectNode blankFunction = properties("queue", " ");
            assertThrows(CloudFormationException.class, () -> handler.validate(context(), resource(), blankFunction));

            ObjectNode nonIntegerBatch = properties("queue", "function");
            nonIntegerBatch.put("BatchSize", "ten");
            assertThrows(CloudFormationException.class, () -> handler.validate(context(), resource(), nonIntegerBatch));

            ObjectNode outOfRangeWindow = properties("queue", "function");
            outOfRangeWindow.put("MaximumBatchingWindowInSeconds", 301);
            assertThrows(
                    CloudFormationException.class, () -> handler.validate(context(), resource(), outOfRangeWindow));

            ObjectNode maximumValues = properties("queue", "function");
            maximumValues.put("BatchSize", 10_000);
            maximumValues.put("MaximumBatchingWindowInSeconds", 300);
            maximumValues.set("ScalingConfig", mapper.createObjectNode().put("MaximumConcurrency", 1_000));
            handler.validate(context(), resource(), maximumValues);

            ObjectNode invalidEnabled = properties("queue", "function");
            invalidEnabled.put("Enabled", "yes");
            assertThrows(CloudFormationException.class, () -> handler.validate(context(), resource(), invalidEnabled));

            ObjectNode invalidResponseTypes = properties("queue", "function");
            invalidResponseTypes.put("FunctionResponseTypes", "ReportBatchItemFailures");
            assertThrows(
                    CloudFormationException.class, () -> handler.validate(context(), resource(), invalidResponseTypes));

            ObjectNode invalidResponseType = properties("queue", "function");
            invalidResponseType.set(
                    "FunctionResponseTypes", mapper.createArrayNode().add("Unknown"));
            assertThrows(
                    CloudFormationException.class, () -> handler.validate(context(), resource(), invalidResponseType));

            ObjectNode invalidScaling = properties("queue", "function");
            invalidScaling.put("ScalingConfig", "invalid");
            assertThrows(CloudFormationException.class, () -> handler.validate(context(), resource(), invalidScaling));

            ObjectNode unsupportedScaling = properties("queue", "function");
            unsupportedScaling.set("ScalingConfig", mapper.createObjectNode().put("MinimumConcurrency", 1));
            assertThrows(
                    CloudFormationException.class, () -> handler.validate(context(), resource(), unsupportedScaling));

            ObjectNode outOfRangeConcurrency = properties("queue", "function");
            outOfRangeConcurrency.set("ScalingConfig", mapper.createObjectNode().put("MaximumConcurrency", 0));
            assertThrows(
                    CloudFormationException.class,
                    () -> handler.validate(context(), resource(), outOfRangeConcurrency));

            ObjectNode nonAwsQueueArn = properties("https://queue", "function");
            assertThrows(CloudFormationException.class, () -> handler.create(context(), resource(), nonAwsQueueArn));

            ObjectNode malformedQueueArn = properties("arn:aws:sqs:us-east-1:000000000000", "function");
            assertThrows(CloudFormationException.class, () -> handler.create(context(), resource(), malformedQueueArn));

            ObjectNode emptyQueueArn = properties("arn:aws:sqs:", "function");
            CloudFormationException queueException = assertThrows(
                    CloudFormationException.class, () -> handler.create(context(), resource(), emptyQueueArn));
            assertTrue(queueException.getMessage().contains("must include a queue name"));

            assertThrows(
                    CloudFormationException.class,
                    () -> handler.update(
                            context(),
                            resource(),
                            properties("queue", "function"),
                            new ProvisionedResource(
                                    "Mapping",
                                    "AWS::Lambda::EventSourceMapping",
                                    "id",
                                    "id",
                                    Map.of(),
                                    mapper.createObjectNode())));
        }
    }

    @Test
    void createsMappingFromLambdaArn(@TempDir Path directory) {
        InMemorySqsQueueStore queueStore = new InMemorySqsQueueStore();
        queueStore.createQueue("orders", "http://127.0.0.1:4566/000000000000/orders");
        try (LambdaService lambdaService = lambdaService(directory);
                TriggerEngine engine = engine(queueStore, lambdaService)) {
            lambdaService.create(
                    "orders-function",
                    "java21",
                    "x86_64",
                    "example.Handler",
                    "arn:aws:iam::000000000000:role/lambda-local",
                    "",
                    3,
                    128,
                    Map.of(),
                    zip());
            engine.start();
            LambdaEventSourceMappingResourceHandler handler =
                    new LambdaEventSourceMappingResourceHandler(queueStore, lambdaService, engine);
            ObjectNode properties = properties(
                    "arn:aws:sqs:us-east-1:000000000000:orders",
                    "arn:aws:lambda:us-east-1:000000000000:function:orders-function");

            ProvisionedResource mapping = handler.create(context(), resource(), properties);

            assertEquals("orders-function", mapping.attributes().get("FunctionName"));
            assertEquals("Enabled", mapping.attributes().get("State"));
            SqsLambdaTriggerSettings settings = (SqsLambdaTriggerSettings)
                    engine.findMapping(mapping.physicalId()).orElseThrow().settings();
            assertFalse(settings.reportBatchItemFailures());

            ObjectNode emptyResponseTypes = properties("arn:aws:sqs:us-east-1:000000000000:orders", "orders-function");
            emptyResponseTypes.set("FunctionResponseTypes", mapper.createArrayNode());
            ProvisionedResource emptyResponseMapping =
                    handler.create(context(), resource("EmptyResponse"), emptyResponseTypes);
            SqsLambdaTriggerSettings emptyResponseSettings =
                    (SqsLambdaTriggerSettings) engine.findMapping(emptyResponseMapping.physicalId())
                            .orElseThrow()
                            .settings();
            assertFalse(emptyResponseSettings.reportBatchItemFailures());

            ObjectNode invalidFunctionArn = properties("arn:aws:sqs:us-east-1:000000000000:orders", "arn:");
            CloudFormationException functionException = assertThrows(
                    CloudFormationException.class,
                    () -> handler.create(context(), resource("InvalidFunction"), invalidFunctionArn));
            assertTrue(functionException.getMessage().contains("must include a function name"));
        }
    }

    private TriggerEngine engine(InMemorySqsQueueStore queueStore, LambdaService lambdaService) {
        return new TriggerEngine(TriggerRegistry.builder()
                .registerPollingDriver(new SqsLambdaPollingDriver(queueStore, lambdaService))
                .build());
    }

    private LambdaService lambdaService(Path directory) {
        return new LambdaService(
                new InMemoryLambdaFunctionStore(),
                new TemporaryLambdaArtifactStore(directory.resolve("artifacts")),
                new NoOpLambdaExecutionBackend(),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                "us-east-1");
    }

    private ObjectNode properties(String queueArn, String functionName) {
        return mapper.createObjectNode().put("EventSourceArn", queueArn).put("FunctionName", functionName);
    }

    private TemplateResource resource() {
        return resource("Mapping");
    }

    private TemplateResource resource(String logicalId) {
        return new TemplateResource(
                logicalId,
                "AWS::Lambda::EventSourceMapping",
                mapper.createObjectNode(),
                mapper.createObjectNode(),
                List.of(),
                null,
                0);
    }

    private ResourceOperationContext context() {
        return new ResourceOperationContext(
                "Stack",
                "000000000000",
                "us-east-1",
                URI.create("http://127.0.0.1:4566"),
                Clock.systemUTC(),
                Map.of(),
                ignored -> Optional.empty());
    }

    private static byte[] zip() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream archive = new ZipOutputStream(output)) {
                archive.putNextEntry(new ZipEntry("example/Handler.class"));
                archive.write("class".getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
            return output.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not create the Lambda test artifact", exception);
        }
    }
}
