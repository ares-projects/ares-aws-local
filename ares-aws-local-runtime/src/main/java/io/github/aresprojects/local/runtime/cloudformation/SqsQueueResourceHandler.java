package io.github.aresprojects.local.runtime.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aresprojects.local.cloudformation.CloudFormationException;
import io.github.aresprojects.local.cloudformation.CloudFormationResourceHandler;
import io.github.aresprojects.local.cloudformation.ProvisionedResource;
import io.github.aresprojects.local.cloudformation.ResourceOperationContext;
import io.github.aresprojects.local.cloudformation.TemplateResource;
import io.github.aresprojects.local.runtime.service.sqs.SqsQueue;
import io.github.aresprojects.local.runtime.service.sqs.SqsQueueStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Provisions the local subset of {@code AWS::SQS::Queue}. */
public final class SqsQueueResourceHandler implements CloudFormationResourceHandler {
    private static final int MAX_QUEUE_NAME_LENGTH = 80;
    private static final java.util.regex.Pattern QUEUE_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private final SqsQueueStore store;

    public SqsQueueResourceHandler(SqsQueueStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public String resourceType() {
        return "AWS::SQS::Queue";
    }

    @Override
    public void validate(ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
        validateSupportedProperties(properties);
        validateQueueName(properties.path("QueueName"));
    }

    private static void validateSupportedProperties(JsonNode properties) {
        Iterator<String> fields = properties.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!"QueueName".equals(field)) {
                throw new CloudFormationException("AWS::SQS::Queue property '" + field
                        + "' is not supported locally yet; remove it or wait for the SQS property slice");
            }
        }
    }

    private static void validateQueueName(JsonNode name) {
        if (name.isMissingNode() || name.isObject()) {
            return;
        }
        if (!name.isTextual()) {
            throw new CloudFormationException("AWS::SQS::Queue QueueName must resolve to a string");
        }
        validateQueueNameText(name.textValue());
    }

    private static void validateQueueNameText(String name) {
        if (name.isBlank()) {
            throw new CloudFormationException("AWS::SQS::Queue QueueName must resolve to a non-blank string");
        }
        if (!QUEUE_NAME.matcher(name).matches()) {
            throw new CloudFormationException(
                    "AWS::SQS::Queue QueueName must contain only letters, numbers, hyphens, or underscores and be"
                            + " at most " + MAX_QUEUE_NAME_LENGTH + " characters");
        }
    }

    @Override
    public ProvisionedResource create(
            ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
        String name = properties.path("QueueName").asText();
        if (name.isBlank()) {
            name = generatedName(context.stackName(), resource.logicalId());
        }
        String queueUrl =
                context.localEndpoint().toString().replaceAll("/$", "") + "/" + context.accountId() + "/" + name;
        SqsQueue queue = store.createQueue(name, queueUrl);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Arn", "arn:aws:sqs:" + context.region() + ":" + context.accountId() + ":" + queue.queueName());
        attributes.put("QueueName", queue.queueName());
        attributes.put("QueueUrl", queue.queueUrl());
        return new ProvisionedResource(
                resource.logicalId(), resource.type(), queue.queueUrl(), queue.queueUrl(), attributes, properties);
    }

    @Override
    public Optional<ProvisionedResource> read(ResourceOperationContext context, ProvisionedResource resource) {
        return store.findQueue(resource.physicalId())
                .map(queue -> new ProvisionedResource(
                        resource.logicalId(),
                        resource.resourceType(),
                        queue.queueUrl(),
                        queue.queueUrl(),
                        resource.attributes(),
                        resource.resolvedProperties()));
    }

    @Override
    public ProvisionedResource update(
            ResourceOperationContext context,
            TemplateResource resource,
            JsonNode properties,
            ProvisionedResource current) {
        throw new CloudFormationException(
                "Updates for AWS::SQS::Queue are not supported in the initial Cloud Assembly slice");
    }

    @Override
    public void delete(ResourceOperationContext context, ProvisionedResource resource) {
        if (!store.deleteQueue(resource.physicalId())) {
            throw new CloudFormationException(
                    "Could not delete local SQS queue '" + resource.physicalId() + "' during rollback");
        }
    }

    private static String generatedName(String stackName, String logicalId) {
        String prefix = (stackName + "-" + logicalId).replaceAll("[^A-Za-z0-9_-]", "-");
        String suffix;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((stackName + ":" + logicalId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                hex.append(String.format("%02x", digest[index]));
            }
            suffix = hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for deterministic local queue names", exception);
        }
        String result = prefix + "-" + suffix;
        return result.substring(0, Math.min(result.length(), 80));
    }
}
