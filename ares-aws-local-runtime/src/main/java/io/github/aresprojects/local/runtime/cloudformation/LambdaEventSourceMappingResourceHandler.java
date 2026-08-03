package io.github.aresprojects.local.runtime.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aresprojects.local.cloudformation.CloudFormationException;
import io.github.aresprojects.local.cloudformation.CloudFormationResourceHandler;
import io.github.aresprojects.local.cloudformation.ProvisionedResource;
import io.github.aresprojects.local.cloudformation.ResourceOperationContext;
import io.github.aresprojects.local.cloudformation.TemplateResource;
import io.github.aresprojects.local.lambda.LambdaFunctionSnapshot;
import io.github.aresprojects.local.lambda.LambdaService;
import io.github.aresprojects.local.runtime.service.sqs.SqsQueue;
import io.github.aresprojects.local.runtime.service.sqs.SqsQueueStore;
import io.github.aresprojects.local.runtime.trigger.AwsResourceReference;
import io.github.aresprojects.local.runtime.trigger.TriggerEngine;
import io.github.aresprojects.local.runtime.trigger.TriggerMapping;
import io.github.aresprojects.local.runtime.trigger.sqs.SqsLambdaPollingDriver;
import io.github.aresprojects.local.runtime.trigger.sqs.SqsLambdaTriggerSettings;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Provisions the local subset of {@code AWS::Lambda::EventSourceMapping} for SQS sources. */
public final class LambdaEventSourceMappingResourceHandler implements CloudFormationResourceHandler {
    private static final String RESOURCE_TYPE = "AWS::Lambda::EventSourceMapping";
    private static final String REPORT_BATCH_ITEM_FAILURES = "ReportBatchItemFailures";
    private static final Set<String> SUPPORTED_PROPERTIES = Set.of(
            "EventSourceArn",
            "FunctionName",
            "BatchSize",
            "MaximumBatchingWindowInSeconds",
            "FunctionResponseTypes",
            "Enabled",
            "ScalingConfig");

    private final SqsQueueStore queueStore;
    private final LambdaService lambdaService;
    private final TriggerEngine triggerEngine;

    /** Creates an event-source mapping handler backed by the running trigger engine. */
    public LambdaEventSourceMappingResourceHandler(
            SqsQueueStore queueStore, LambdaService lambdaService, TriggerEngine triggerEngine) {
        this.queueStore = Objects.requireNonNull(queueStore, "queueStore");
        this.lambdaService = Objects.requireNonNull(lambdaService, "lambdaService");
        this.triggerEngine = Objects.requireNonNull(triggerEngine, "triggerEngine");
    }

    @Override
    public String resourceType() {
        return RESOURCE_TYPE;
    }

    @Override
    public void validate(ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
        validateSupportedProperties(properties);
        requiredReference(properties, "EventSourceArn");
        requiredReference(properties, "FunctionName");
        batchSize(properties);
        batchingWindow(properties);
        responseTypes(properties);
        enabled(properties);
        maximumConcurrency(properties);
    }

    @Override
    public ProvisionedResource create(
            ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
        SqsLambdaTriggerSettings settings = settings(context, properties);
        String mappingId = mappingId(context.stackName(), resource.logicalId());
        TriggerMapping mapping = new TriggerMapping(
                mappingId,
                SqsLambdaPollingDriver.DRIVER_ID,
                new AwsResourceReference("sqs", settings.queueArn()),
                new AwsResourceReference("lambda", settings.functionName()),
                enabled(properties),
                settings);
        triggerEngine.registerMapping(mapping);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("EventSourceArn", settings.queueArn());
        attributes.put("FunctionName", settings.functionName());
        attributes.put("State", mapping.enabled() ? "Enabled" : "Disabled");
        return new ProvisionedResource(
                resource.logicalId(), RESOURCE_TYPE, mappingId, mappingId, attributes, properties);
    }

    @Override
    public Optional<ProvisionedResource> read(ResourceOperationContext context, ProvisionedResource resource) {
        return triggerEngine.findMapping(resource.physicalId()).map(ignored -> resource);
    }

    @Override
    public ProvisionedResource update(
            ResourceOperationContext context,
            TemplateResource resource,
            JsonNode properties,
            ProvisionedResource current) {
        throw new CloudFormationException("Updates for AWS::Lambda::EventSourceMapping are not supported locally yet; "
                + "delete and recreate the mapping");
    }

    @Override
    public void delete(ResourceOperationContext context, ProvisionedResource resource) {
        triggerEngine.removeMapping(resource.physicalId());
    }

    private SqsLambdaTriggerSettings settings(ResourceOperationContext context, JsonNode properties) {
        String queueArn = requiredText(properties, "EventSourceArn");
        String queueName = queueName(queueArn);
        SqsQueue queue = queueStore
                .findQueueByName(queueName)
                .orElseThrow(() -> new CloudFormationException("AWS::Lambda::EventSourceMapping EventSourceArn '"
                        + queueArn + "' does not identify a provisioned local SQS queue"));
        String requestedFunction = requiredText(properties, "FunctionName");
        String functionName = functionName(requestedFunction);
        LambdaFunctionSnapshot function = lambdaService
                .find(functionName)
                .orElseThrow(() -> new CloudFormationException("AWS::Lambda::EventSourceMapping FunctionName '"
                        + requestedFunction + "' does not identify a provisioned local Lambda function"));
        Duration window = Duration.ofSeconds(batchingWindow(properties));
        return new SqsLambdaTriggerSettings(
                queue.queueUrl(),
                queueArn,
                context.region(),
                function.functionName(),
                batchSize(properties),
                window,
                30,
                maximumConcurrency(properties),
                responseTypes(properties));
    }

    private static void validateSupportedProperties(JsonNode properties) {
        Iterator<String> fields = properties.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!SUPPORTED_PROPERTIES.contains(field)) {
                throw new CloudFormationException("AWS::Lambda::EventSourceMapping property '" + field
                        + "' is not supported locally yet; remove it or wait for the event-source mapping slice");
            }
        }
    }

    private static String requiredText(JsonNode properties, String name) {
        JsonNode value = properties.path(name);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new CloudFormationException(
                    "AWS::Lambda::EventSourceMapping property '" + name + "' must resolve to a non-blank string");
        }
        return value.textValue();
    }

    private static void requiredReference(JsonNode properties, String name) {
        JsonNode value = properties.path(name);
        if (value.isObject()) {
            return;
        }
        requiredText(properties, name);
    }

    private static int batchSize(JsonNode properties) {
        return integer(properties, "BatchSize", 10, 1, 10_000);
    }

    private static int batchingWindow(JsonNode properties) {
        return integer(properties, "MaximumBatchingWindowInSeconds", 0, 0, 300);
    }

    private static int maximumConcurrency(JsonNode properties) {
        JsonNode scalingConfig = properties.path("ScalingConfig");
        if (scalingConfig.isMissingNode()) {
            return 1;
        }
        if (!scalingConfig.isObject()) {
            throw new CloudFormationException(
                    "AWS::Lambda::EventSourceMapping ScalingConfig must resolve to an object");
        }
        Iterator<String> fields = scalingConfig.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!"MaximumConcurrency".equals(field)) {
                throw new CloudFormationException("AWS::Lambda::EventSourceMapping ScalingConfig property '" + field
                        + "' is not supported locally yet; remove it");
            }
        }
        return integer(scalingConfig, "MaximumConcurrency", 1, 1, 1_000);
    }

    private static boolean enabled(JsonNode properties) {
        JsonNode value = properties.path("Enabled");
        if (value.isMissingNode()) {
            return true;
        }
        if (!value.isBoolean()) {
            throw new CloudFormationException("AWS::Lambda::EventSourceMapping Enabled must resolve to a boolean");
        }
        return value.booleanValue();
    }

    private static boolean responseTypes(JsonNode properties) {
        JsonNode value = properties.path("FunctionResponseTypes");
        if (value.isMissingNode()) {
            return false;
        }
        if (!value.isArray()) {
            throw new CloudFormationException(
                    "AWS::Lambda::EventSourceMapping FunctionResponseTypes must resolve to an array");
        }
        boolean reportFailures = false;
        for (JsonNode item : value) {
            if (!item.isTextual() || !REPORT_BATCH_ITEM_FAILURES.equals(item.textValue())) {
                throw new CloudFormationException(
                        "AWS::Lambda::EventSourceMapping supports only FunctionResponseTypes value '"
                                + REPORT_BATCH_ITEM_FAILURES + "'");
            }
            reportFailures = true;
        }
        return reportFailures;
    }

    private static int integer(JsonNode properties, String name, int defaultValue, int minimum, int maximum) {
        JsonNode value = properties.path(name);
        if (value.isMissingNode()) {
            return defaultValue;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new CloudFormationException(
                    "AWS::Lambda::EventSourceMapping property '" + name + "' must resolve to an integer");
        }
        int result = value.intValue();
        if (result < minimum || result > maximum) {
            throw new CloudFormationException("AWS::Lambda::EventSourceMapping property '" + name + "' must be between "
                    + minimum + " and " + maximum + "; received " + result);
        }
        return result;
    }

    private static String queueName(String queueArn) {
        if (!queueArn.startsWith("arn:aws:sqs:")) {
            throw new CloudFormationException(
                    "AWS::Lambda::EventSourceMapping EventSourceArn must be an AWS SQS ARN; received '" + queueArn
                            + "'");
        }
        int separator = queueArn.lastIndexOf(':');
        if (separator < 0 || separator == queueArn.length() - 1) {
            throw new CloudFormationException(
                    "AWS::Lambda::EventSourceMapping EventSourceArn must include a queue name; received '" + queueArn
                            + "'");
        }
        return queueArn.substring(separator + 1);
    }

    private static String functionName(String functionIdentifier) {
        if (!functionIdentifier.startsWith("arn:")) {
            return functionIdentifier;
        }
        int separator = functionIdentifier.lastIndexOf(':');
        if (separator < 0 || separator == functionIdentifier.length() - 1) {
            throw new CloudFormationException(
                    "AWS::Lambda::EventSourceMapping FunctionName ARN must include a function name; received '"
                            + functionIdentifier + "'");
        }
        return functionIdentifier.substring(separator + 1);
    }

    private static String mappingId(String stackName, String logicalId) {
        return stackName + "/" + logicalId;
    }
}
