package io.github.aresprojects.local.runtime.service.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aresprojects.local.lambda.LambdaConfigurationUpdate;
import io.github.aresprojects.local.lambda.LambdaFunctionSnapshot;
import io.github.aresprojects.local.lambda.LambdaService;
import io.github.aresprojects.local.lambda.LambdaServiceException;
import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import io.github.aresprojects.local.runtime.protocol.json.AwsJsonProtocol;
import io.github.aresprojects.local.runtime.protocol.json.AwsJsonProtocolException;
import io.github.aresprojects.local.runtime.service.AwsServiceAdapter;
import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvocationResult;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Exposes the M3 Lambda control-plane subset through AWS JSON 1.1. */
@SuppressWarnings("PMD.CyclomaticComplexity")
public final class LambdaJsonAdapter implements AwsServiceAdapter, AutoCloseable {
    private static final String ERROR_NAMESPACE = "com.amazonaws.lambda";
    private static final Set<String> CREATE_FIELDS = Set.of(
            "Code",
            "FunctionName",
            "Handler",
            "Role",
            "Runtime",
            "Architectures",
            "Description",
            "Timeout",
            "MemorySize",
            "Environment",
            "Publish",
            "PackageType");
    private static final Set<String> CODE_FIELDS =
            Set.of("FunctionName", "ZipFile", "Architectures", "Publish", "DryRun", "RevisionId");
    private static final Set<String> CONFIGURATION_FIELDS = Set.of(
            "FunctionName",
            "Description",
            "Handler",
            "Runtime",
            "Role",
            "Timeout",
            "MemorySize",
            "Environment",
            "Architectures");
    private static final Set<String> NAME_FIELDS = Set.of("FunctionName", "Qualifier");

    private final LambdaService service;
    private final AwsJsonProtocol protocol;
    private final ObjectMapper objectMapper;

    /** Creates an adapter backed by a process-local Lambda service. */
    public LambdaJsonAdapter(LambdaService service) {
        this(service, new AwsJsonProtocol());
    }

    LambdaJsonAdapter(LambdaService service, AwsJsonProtocol protocol) {
        this.service = Objects.requireNonNull(service, "service");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String serviceName() {
        return "lambda";
    }

    @Override
    public boolean supports(AwsRequestContext request) {
        return route(request).isPresent();
    }

    @Override
    public CompletionStage<AwsHttpResponse> handle(AwsRequestContext request) {
        try {
            Route route = route(request)
                    .orElseThrow(() ->
                            new AwsJsonProtocolException("InvalidRequest", "unsupported Lambda HTTP method or path"));
            if ("Invoke".equals(route.operation())) {
                return invoke(request, route.functionName());
            }
            JsonNode payload = decodePayload(request.body());
            if (route.functionName() != null && payload instanceof ObjectNode object) {
                object.put("FunctionName", route.functionName());
            }
            return CompletableFuture.completedFuture(dispatch(request, route.operation(), payload));
        } catch (AwsJsonProtocolException exception) {
            return CompletableFuture.completedFuture(
                    error(request, 400, exception.errorCode(), exception.getMessage()));
        } catch (LambdaServiceException exception) {
            return CompletableFuture.completedFuture(
                    error(request, statusCode(exception.errorCode()), exception.errorCode(), exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(
                    error(request, 400, "InvalidParameterValueException", exception.getMessage()));
        }
    }

    /** Releases the Lambda service's artifact and execution resources during runtime shutdown. */
    @Override
    public void close() {
        service.close();
    }

    private AwsHttpResponse dispatch(AwsRequestContext request, String operation, JsonNode payload) {
        return switch (operation) {
            case "CreateFunction" -> createFunction(request, payload);
            case "GetFunction" -> getFunction(request, payload, true);
            case "GetFunctionConfiguration" -> getFunction(request, payload, false);
            case "UpdateFunctionCode" -> updateFunctionCode(request, payload);
            case "UpdateFunctionConfiguration" -> updateFunctionConfiguration(request, payload);
            case "DeleteFunction" -> deleteFunction(request, payload);
            default ->
                error(
                        request,
                        400,
                        "UnsupportedOperationException",
                        "Lambda operation is not implemented: " + operation);
        };
    }

    private CompletionStage<AwsHttpResponse> invoke(AwsRequestContext request, String functionName) {
        try {
            CompletionStage<LambdaInvocationResult> invocation = Objects.requireNonNull(
                    service.invoke(functionName, request.body()),
                    "Lambda execution backend returned a null completion stage");
            return invocation.thenApply(result -> invocationResponse(request, result));
        } catch (LambdaServiceException exception) {
            return CompletableFuture.completedFuture(
                    error(request, statusCode(exception.errorCode()), exception.errorCode(), exception.getMessage()));
        }
    }

    private static AwsHttpResponse invocationResponse(AwsRequestContext request, LambdaInvocationResult result) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("content-type", List.of("application/json"));
        headers.put("x-amzn-requestid", List.of(request.requestId()));
        headers.put("x-amz-executed-version", List.of("$LATEST"));
        result.functionError().ifPresent(value -> headers.put("x-amz-function-error", List.of(value)));
        return new AwsHttpResponse(200, headers, result.payload());
    }

    private Optional<Route> route(AwsRequestContext request) {
        Objects.requireNonNull(request, "request");
        String path = request.rawTarget();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String prefix = "/2015-03-31/functions";
        if (!path.equals(prefix) && !path.startsWith(prefix + "/")) {
            return Optional.empty();
        }
        String suffix = path.substring(prefix.length());
        if (request.method().equalsIgnoreCase("POST") && suffix.isEmpty()) {
            return Optional.of(new Route("CreateFunction", null));
        }
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = suffix.substring(1).split("/");
        if (parts.length == 1 && request.method().equalsIgnoreCase("GET")) {
            return Optional.of(new Route("GetFunction", decodePath(parts[0])));
        }
        if (parts.length == 2
                && "configuration".equals(parts[1])
                && request.method().equalsIgnoreCase("GET")) {
            return Optional.of(new Route("GetFunctionConfiguration", decodePath(parts[0])));
        }
        if (parts.length == 2
                && "invocations".equals(parts[1])
                && request.method().equalsIgnoreCase("POST")) {
            return Optional.of(new Route("Invoke", decodePath(parts[0])));
        }
        if (parts.length == 1 && request.method().equalsIgnoreCase("DELETE")) {
            return Optional.of(new Route("DeleteFunction", decodePath(parts[0])));
        }
        if (parts.length == 2 && "code".equals(parts[1]) && request.method().equalsIgnoreCase("PUT")) {
            return Optional.of(new Route("UpdateFunctionCode", decodePath(parts[0])));
        }
        if (parts.length == 2
                && "configuration".equals(parts[1])
                && request.method().equalsIgnoreCase("PUT")) {
            return Optional.of(new Route("UpdateFunctionConfiguration", decodePath(parts[0])));
        }
        return Optional.empty();
    }

    private JsonNode decodePayload(byte[] body) {
        if (body.length == 0) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode payload = objectMapper.readTree(body);
            if (payload == null || !payload.isObject()) {
                throw new AwsJsonProtocolException("InvalidRequest", "Lambda request body must be a JSON object");
            }
            return payload;
        } catch (java.io.IOException exception) {
            throw new AwsJsonProtocolException("InvalidRequest", "Lambda request body is not valid JSON");
        }
    }

    private static String decodePath(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private record Route(String operation, String functionName) {}

    private AwsHttpResponse createFunction(AwsRequestContext request, JsonNode payload) {
        rejectUnexpectedFields(payload, CREATE_FIELDS);
        String packageType = optionalText(payload, "PackageType").orElse("Zip");
        if (!"Zip".equals(packageType)) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", "Only PackageType 'Zip' is supported in this milestone");
        }
        JsonNode code = requiredObject(payload, "Code");
        rejectUnexpectedFields(code, Set.of("ZipFile"));
        LambdaFunctionSnapshot function = service.create(
                requiredText(payload, "FunctionName"),
                requiredText(payload, "Runtime"),
                architecture(payload).orElse("arm64"),
                requiredText(payload, "Handler"),
                requiredText(payload, "Role"),
                optionalText(payload, "Description").orElse(""),
                optionalInteger(payload, "Timeout", 3),
                optionalInteger(payload, "MemorySize", 128),
                environment(payload).orElse(Map.of()),
                decodeZip(code));
        return protocol.success(request, response(function, true));
    }

    private AwsHttpResponse getFunction(AwsRequestContext request, JsonNode payload, boolean includeCode) {
        rejectUnexpectedFields(payload, NAME_FIELDS);
        rejectQualifier(payload);
        LambdaFunctionSnapshot function = service.get(requiredText(payload, "FunctionName"));
        if (!includeCode) {
            return protocol.success(request, configuration(function));
        }
        return protocol.success(
                request,
                Map.of(
                        "Configuration",
                        configuration(function),
                        "Code",
                        Map.of(
                                "CodeSha256", function.artifact().sha256Base64(),
                                "CodeSize", function.artifact().sizeBytes(),
                                "RepositoryType", "S3")));
    }

    private AwsHttpResponse updateFunctionCode(AwsRequestContext request, JsonNode payload) {
        rejectUnexpectedFields(payload, CODE_FIELDS);
        JsonNode zip = payload.get("ZipFile");
        if (zip == null || !zip.isTextual()) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", "ZipFile is required and must contain base64 ZIP bytes");
        }
        LambdaFunctionSnapshot function = service.updateCode(
                requiredText(payload, "FunctionName"), decodeBase64(zip.textValue()), architecture(payload));
        return protocol.success(request, configuration(function));
    }

    private AwsHttpResponse updateFunctionConfiguration(AwsRequestContext request, JsonNode payload) {
        rejectUnexpectedFields(payload, CONFIGURATION_FIELDS);
        rejectQualifier(payload);
        LambdaConfigurationUpdate update = new LambdaConfigurationUpdate(
                optionalText(payload, "Description"),
                optionalText(payload, "Handler"),
                optionalText(payload, "Runtime"),
                architecture(payload),
                optionalText(payload, "Role"),
                optionalIntegerValue(payload, "Timeout"),
                optionalIntegerValue(payload, "MemorySize"),
                environment(payload));
        LambdaFunctionSnapshot function = service.updateConfiguration(requiredText(payload, "FunctionName"), update);
        return protocol.success(request, configuration(function));
    }

    private AwsHttpResponse deleteFunction(AwsRequestContext request, JsonNode payload) {
        rejectUnexpectedFields(payload, NAME_FIELDS);
        rejectQualifier(payload);
        service.delete(requiredText(payload, "FunctionName"));
        return protocol.success(request, Map.of());
    }

    private Map<String, Object> response(LambdaFunctionSnapshot function, boolean includeCode) {
        Map<String, Object> result = new LinkedHashMap<>(configuration(function));
        if (includeCode) {
            result.put(
                    "Code",
                    Map.of(
                            "CodeSha256", function.artifact().sha256Base64(),
                            "CodeSize", function.artifact().sizeBytes(),
                            "RepositoryType", "S3"));
        }
        return result;
    }

    private Map<String, Object> configuration(LambdaFunctionSnapshot function) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("FunctionName", function.functionName());
        result.put("FunctionArn", function.functionArn());
        result.put("Runtime", function.runtime());
        result.put("Role", function.role());
        result.put("Handler", function.handler());
        result.put("Description", function.description());
        result.put("Timeout", function.timeoutSeconds());
        result.put("MemorySize", function.memorySizeMb());
        result.put("LastModified", function.lastModified().toString());
        result.put("CodeSha256", function.artifact().sha256Base64());
        result.put("CodeSize", function.artifact().sizeBytes());
        result.put("Version", "$LATEST");
        result.put("State", "Active");
        result.put("StateReason", "Function deployed locally");
        result.put("StateReasonCode", "Idle");
        result.put("LastUpdateStatus", "Successful");
        result.put("RevisionId", function.revisionId());
        result.put("PackageType", "Zip");
        result.put("Architectures", List.of(function.architecture()));
        result.put("Environment", Map.of("Variables", function.environment()));
        return result;
    }

    private AwsHttpResponse error(AwsRequestContext request, int status, String code, String message) {
        return protocol.error(request, status, code, message, ERROR_NAMESPACE);
    }

    private static Optional<Map<String, String>> environment(JsonNode payload) {
        JsonNode environment = payload.get("Environment");
        if (environment == null) {
            return Optional.empty();
        }
        if (!environment.isObject()) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", "Environment must be an object containing Variables");
        }
        JsonNode variables = environment.get("Variables");
        if (variables == null) {
            return Optional.of(Map.of());
        }
        if (!variables.isObject()) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", "Environment.Variables must be a JSON object");
        }
        Map<String, String> values = new LinkedHashMap<>();
        variables.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new LambdaServiceException(
                        "InvalidParameterValueException", "Environment variable '" + entry.getKey() + "' must be text");
            }
            values.put(entry.getKey(), entry.getValue().textValue());
        });
        return Optional.of(values);
    }

    private static Optional<String> architecture(JsonNode payload) {
        JsonNode architectures = payload.get("Architectures");
        if (architectures == null) {
            return Optional.empty();
        }
        if (!architectures.isArray()
                || architectures.size() != 1
                || !architectures.get(0).isTextual()) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", "Architectures must contain exactly one architecture");
        }
        return Optional.of(architectures.get(0).textValue());
    }

    private static JsonNode requiredObject(JsonNode payload, String fieldName) {
        JsonNode value = payload.get(fieldName);
        if (value == null || !value.isObject()) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", fieldName + " is required and must be a JSON object");
        }
        return value;
    }

    private static String requiredText(JsonNode payload, String fieldName) {
        return optionalText(payload, fieldName)
                .orElseThrow(() -> new LambdaServiceException(
                        "InvalidParameterValueException", fieldName + " is required and must be a non-blank string"));
    }

    private static Optional<String> optionalText(JsonNode payload, String fieldName) {
        JsonNode value = payload.get(fieldName);
        if (value == null) {
            return Optional.empty();
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", fieldName + " must be a non-blank string");
        }
        return Optional.of(value.textValue());
    }

    private static Optional<Integer> optionalIntegerValue(JsonNode payload, String fieldName) {
        if (payload.get(fieldName) == null) {
            return Optional.empty();
        }
        return Optional.of(optionalInteger(payload, fieldName, 0));
    }

    private static int optionalInteger(JsonNode payload, String fieldName, int defaultValue) {
        JsonNode value = payload.get(fieldName);
        if (value == null) {
            return defaultValue;
        }
        if (!value.canConvertToInt()) {
            throw new LambdaServiceException("InvalidParameterValueException", fieldName + " must be an integer");
        }
        return value.intValue();
    }

    private static byte[] decodeZip(JsonNode code) {
        JsonNode zip = code.get("ZipFile");
        if (zip == null || !zip.isTextual()) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", "Code.ZipFile is required and must contain base64 ZIP bytes");
        }
        return decodeBase64(zip.textValue());
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", "ZipFile must contain valid base64-encoded ZIP bytes");
        }
    }

    private static void rejectUnexpectedFields(JsonNode payload, Set<String> allowedFields) {
        Set<String> fields = new HashSet<>();
        payload.fieldNames().forEachRemaining(fields::add);
        fields.removeAll(allowedFields);
        if (!fields.isEmpty()) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException",
                    "Unsupported Lambda request fields: " + String.join(", ", fields));
        }
    }

    private static void rejectQualifier(JsonNode payload) {
        if (payload.has("Qualifier")) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException",
                    "Qualified function versions are not supported in this milestone");
        }
    }

    private static int statusCode(String errorCode) {
        return switch (errorCode) {
            case "ResourceNotFoundException" -> 404;
            case "ResourceConflictException" -> 409;
            case "RequestTooLargeException" -> 413;
            default -> 400;
        };
    }
}
