package io.github.aresprojects.local.runtime.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aresprojects.local.cloudformation.CloudFormationException;
import io.github.aresprojects.local.cloudformation.CloudFormationResourceHandler;
import io.github.aresprojects.local.cloudformation.ProvisionedResource;
import io.github.aresprojects.local.cloudformation.ResourceOperationContext;
import io.github.aresprojects.local.cloudformation.TemplateResource;
import io.github.aresprojects.local.lambda.LambdaFunctionSnapshot;
import io.github.aresprojects.local.lambda.LambdaService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Provisions the local subset of {@code AWS::Lambda::Function} from Cloud Assembly file assets. */
public final class LambdaFunctionResourceHandler implements CloudFormationResourceHandler {
    private static final int MAX_FUNCTION_NAME_LENGTH = 64;
    private static final int DEFAULT_TIMEOUT_SECONDS = 3;
    private static final int DEFAULT_MEMORY_SIZE_MB = 128;
    private static final String DEFAULT_ARCHITECTURE = "x86_64";
    private static final List<String> SUPPORTED_PROPERTIES = List.of(
            "Code",
            "FunctionName",
            "Handler",
            "Role",
            "Runtime",
            "Architectures",
            "Description",
            "Environment",
            "MemorySize",
            "Timeout");

    private final LambdaService service;

    /** Creates a handler backed by the runtime's shared Lambda service. */
    public LambdaFunctionResourceHandler(LambdaService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String resourceType() {
        return "AWS::Lambda::Function";
    }

    @Override
    public void validate(ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
        validateSupportedProperties(properties);
        required(properties, "Code");
        required(properties, "Handler");
        required(properties, "Role");
        required(properties, "Runtime");
        codePath(context, resource, properties);
        architecture(properties);
        environment(properties);
    }

    @Override
    public ProvisionedResource create(
            ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
        String functionName = properties.path("FunctionName").asText();
        if (functionName.isBlank()) {
            functionName = generatedName(context.stackName(), resource.logicalId());
        }
        LambdaFunctionSnapshot function = service.create(
                functionName,
                properties.path("Runtime").asText(),
                architecture(properties),
                properties.path("Handler").asText(),
                properties.path("Role").asText(),
                properties.path("Description").asText(""),
                properties.path("Timeout").asInt(DEFAULT_TIMEOUT_SECONDS),
                properties.path("MemorySize").asInt(DEFAULT_MEMORY_SIZE_MB),
                environment(properties),
                readCode(context, resource, properties));
        return provisioned(resource.logicalId(), resource.type(), function, properties);
    }

    @Override
    public Optional<ProvisionedResource> read(ResourceOperationContext context, ProvisionedResource resource) {
        return service.find(resource.physicalId())
                .map(function -> provisioned(
                        resource.logicalId(), resource.resourceType(), function, resource.resolvedProperties()));
    }

    @Override
    public ProvisionedResource update(
            ResourceOperationContext context,
            TemplateResource resource,
            JsonNode properties,
            ProvisionedResource current) {
        throw new CloudFormationException(
                "Updates for AWS::Lambda::Function are not supported in the initial Cloud Assembly slice");
    }

    @Override
    public void delete(ResourceOperationContext context, ProvisionedResource resource) {
        service.delete(resource.physicalId());
    }

    private static void validateSupportedProperties(JsonNode properties) {
        Iterator<String> fields = properties.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!SUPPORTED_PROPERTIES.contains(field)) {
                throw new CloudFormationException("AWS::Lambda::Function property '" + field
                        + "' is not supported locally yet; remove it or wait for the Lambda property slice");
            }
        }
    }

    private static JsonNode required(JsonNode properties, String name) {
        JsonNode value = properties.path(name);
        if (value.isMissingNode() || value.isNull()) {
            throw new CloudFormationException("AWS::Lambda::Function requires property '" + name + "'");
        }
        return value;
    }

    private static String architecture(JsonNode properties) {
        JsonNode architectures = properties.path("Architectures");
        if (architectures.isMissingNode()) {
            return DEFAULT_ARCHITECTURE;
        }
        if (!architectures.isArray()
                || architectures.size() != 1
                || !architectures.get(0).isTextual()) {
            throw new CloudFormationException(
                    "AWS::Lambda::Function Architectures must contain exactly one architecture string");
        }
        return architectures.get(0).textValue();
    }

    private static Map<String, String> environment(JsonNode properties) {
        JsonNode environment = properties.path("Environment");
        if (environment.isMissingNode()) {
            return Map.of();
        }
        JsonNode variables = environment.path("Variables");
        if (!environment.isObject() || !variables.isObject()) {
            throw new CloudFormationException("AWS::Lambda::Function Environment must contain a Variables object");
        }
        Map<String, String> values = new LinkedHashMap<>();
        variables.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new CloudFormationException(
                        "AWS::Lambda::Function environment variable '" + entry.getKey() + "' must be a string");
            }
            values.put(entry.getKey(), entry.getValue().textValue());
        });
        return values;
    }

    private static Path codePath(ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
        JsonNode code = required(properties, "Code");
        if (!code.isObject()) {
            throw new CloudFormationException(
                    "AWS::Lambda::Function Code must reference a local Cloud Assembly file asset");
        }
        String metadataPath = resource.metadata().path("aws:asset:path").asText("");
        String s3Key = code.path("S3Key").asText("");
        return firstAsset(context, metadataPath, s3Key)
                .orElseThrow(() -> new CloudFormationException(
                        "Could not resolve AWS::Lambda::Function Code asset; expected a bundled file for '"
                                + (metadataPath.isBlank() ? s3Key : metadataPath) + "'"));
    }

    private static Optional<Path> firstAsset(ResourceOperationContext context, String... identifiers) {
        for (String identifier : identifiers) {
            if (!identifier.isBlank()) {
                Optional<Path> resolved = context.assetResolver().resolve(identifier);
                if (resolved.isPresent()) {
                    return resolved;
                }
            }
        }
        return Optional.empty();
    }

    private static byte[] readCode(ResourceOperationContext context, TemplateResource resource, JsonNode properties) {
        Path path = codePath(context, resource, properties);
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new CloudFormationException(
                    "Could not read AWS::Lambda::Function Code asset '" + path + "': " + exception.getMessage(),
                    exception);
        }
    }

    private static ProvisionedResource provisioned(
            String logicalId, String resourceType, LambdaFunctionSnapshot function, JsonNode properties) {
        Map<String, String> attributes = Map.of(
                "Arn", function.functionArn(),
                "FunctionName", function.functionName());
        return new ProvisionedResource(
                logicalId, resourceType, function.functionName(), function.functionName(), attributes, properties);
    }

    private static String generatedName(String stackName, String logicalId) {
        String prefix = (stackName + "-" + logicalId).replaceAll("[^A-Za-z0-9-_]", "-");
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
            throw new IllegalStateException("SHA-256 is required for deterministic local Lambda names", exception);
        }
        String result = prefix + "-" + suffix;
        return result.substring(0, Math.min(result.length(), MAX_FUNCTION_NAME_LENGTH));
    }
}
