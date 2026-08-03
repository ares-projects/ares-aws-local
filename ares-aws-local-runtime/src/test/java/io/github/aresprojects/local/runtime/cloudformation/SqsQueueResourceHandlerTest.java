package io.github.aresprojects.local.runtime.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.cloudformation.CloudFormationException;
import io.github.aresprojects.local.cloudformation.ProvisionedResource;
import io.github.aresprojects.local.cloudformation.ResourceOperationContext;
import io.github.aresprojects.local.cloudformation.TemplateResource;
import io.github.aresprojects.local.runtime.service.sqs.InMemorySqsQueueStore;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SqsQueueResourceHandlerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResourceOperationContext context = new ResourceOperationContext(
            "Stack",
            "000000000000",
            "us-east-1",
            URI.create("http://127.0.0.1:4566"),
            Clock.systemUTC(),
            Map.of(),
            ignored -> Optional.empty());
    private final TemplateResource resource = new TemplateResource(
            "Queue",
            "AWS::SQS::Queue",
            mapper.createObjectNode(),
            mapper.createObjectNode(),
            java.util.List.of(),
            null,
            0);

    @Test
    void createsReadsAndDeletesNamedQueues() {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        SqsQueueResourceHandler handler = new SqsQueueResourceHandler(store);
        var properties = mapper.createObjectNode().put("QueueName", "named-queue");

        ProvisionedResource created = handler.create(context, resource, properties);

        assertTrue(created.physicalId().endsWith("/named-queue"));
        assertEquals("named-queue", created.attributes().get("QueueName"));
        assertTrue(handler.read(context, created).isPresent());
        handler.delete(context, created);
        assertTrue(handler.read(context, created).isEmpty());
        assertThrows(CloudFormationException.class, () -> handler.delete(context, created));
    }

    @Test
    void generatesDeterministicNamesAndValidatesSupportedProperties() {
        InMemorySqsQueueStore store = new InMemorySqsQueueStore();
        SqsQueueResourceHandler handler = new SqsQueueResourceHandler(store);

        ProvisionedResource first = handler.create(context, resource, mapper.createObjectNode());
        ProvisionedResource second = handler.create(context, resource, mapper.createObjectNode());
        assertEquals(first.physicalId(), second.physicalId());
        assertTrue(first.attributes().get("QueueName").matches("[A-Za-z0-9_-]{1,80}"));

        assertThrows(
                CloudFormationException.class,
                () -> handler.validate(
                        context, resource, mapper.createObjectNode().put("VisibilityTimeout", 30)));
        assertThrows(
                CloudFormationException.class,
                () -> handler.validate(
                        context, resource, mapper.createObjectNode().put("QueueName", "bad.name")));
        assertThrows(
                CloudFormationException.class,
                () -> handler.validate(
                        context, resource, mapper.createObjectNode().put("QueueName", " ")));
        assertThrows(
                CloudFormationException.class,
                () -> handler.validate(
                        context, resource, mapper.createObjectNode().put("QueueName", "x".repeat(81))));
        handler.validate(
                context,
                resource,
                mapper.createObjectNode()
                        .set("QueueName", mapper.createObjectNode().put("Ref", "Name")));
    }

    @Test
    void rejectsUpdatesAndReturnsTheCurrentResourceOnRead() {
        SqsQueueResourceHandler handler = new SqsQueueResourceHandler(new InMemorySqsQueueStore());
        ProvisionedResource current = new ProvisionedResource(
                "Queue", "AWS::SQS::Queue", "http://missing", "http://missing", Map.of(), mapper.createObjectNode());
        assertThrows(
                CloudFormationException.class,
                () -> handler.update(context, resource, mapper.createObjectNode(), current));
    }

    @Test
    void truncatesGeneratedNamesToTheSqsLimit() {
        ResourceOperationContext longContext = new ResourceOperationContext(
                "stack-" + "s".repeat(100),
                context.accountId(),
                context.region(),
                context.localEndpoint(),
                context.clock(),
                Map.of(),
                ignored -> Optional.empty());

        ProvisionedResource generated = new SqsQueueResourceHandler(new InMemorySqsQueueStore())
                .create(longContext, resource, mapper.createObjectNode());

        assertEquals(80, generated.attributes().get("QueueName").length());
    }
}
