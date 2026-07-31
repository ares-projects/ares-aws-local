package io.github.aresprojects.local.cli.deploy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.cli.builder.DeploymentResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Calls the local Lambda AWS JSON API without requiring the AWS CLI or SDK. */
public final class LocalLambdaClient {
    static final String DEFAULT_ENDPOINT = "http://127.0.0.1:4566";
    static final String LOCAL_ROLE = "arn:aws:iam::000000000000:role/lambda-local";
    private static final String CONTENT_TYPE = "application/json";

    private final URI endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Creates a client using {@code ARES_AWS_LOCAL_ENDPOINT} or the default local endpoint. */
    public LocalLambdaClient() {
        this(resolveEndpoint(System.getenv().getOrDefault("ARES_AWS_LOCAL_ENDPOINT", DEFAULT_ENDPOINT)));
    }

    /** Creates a client for an explicit local endpoint. */
    public LocalLambdaClient(URI endpoint) {
        this(endpoint, HttpClient.newHttpClient(), new ObjectMapper());
    }

    LocalLambdaClient(URI endpoint, HttpClient httpClient, ObjectMapper objectMapper) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Returns a deployed function or an empty result when Lambda reports not found. */
    public Optional<LambdaRemoteFunction> getFunction(String functionName) throws LambdaClientException {
        try {
            return Optional.of(
                    toFunction(invoke("GET", functionPath(functionName), null).get("Configuration")));
        } catch (LambdaClientException exception) {
            if ("ResourceNotFoundException".equals(exception.errorCode())) {
                return Optional.empty();
            }
            throw exception;
        }
    }

    /** Creates a function from the generated build result. */
    public LambdaRemoteFunction createFunction(DeploymentResult result) throws LambdaClientException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Code", Map.of("ZipFile", encodedArtifact(result)));
        payload.put("FunctionName", result.functionName());
        payload.put("Handler", result.handler());
        payload.put("Role", LOCAL_ROLE);
        payload.put("Runtime", result.runtime());
        payload.put("Architectures", List.of(result.architecture()));
        payload.put("Environment", Map.of("Variables", result.environmentVariables()));
        return toFunction(invoke("POST", functionsPath(), payload));
    }

    /** Updates the code revision from the generated artifact. */
    public LambdaRemoteFunction updateFunctionCode(DeploymentResult result) throws LambdaClientException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("FunctionName", result.functionName());
        payload.put("ZipFile", encodedArtifact(result));
        payload.put("Architectures", List.of(result.architecture()));
        return toFunction(invoke("PUT", functionPath(result.functionName()) + "/code", payload));
    }

    /** Updates the function configuration from the generated build result. */
    public LambdaRemoteFunction updateFunctionConfiguration(DeploymentResult result) throws LambdaClientException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("FunctionName", result.functionName());
        payload.put("Handler", result.handler());
        payload.put("Runtime", result.runtime());
        payload.put("Architectures", List.of(result.architecture()));
        payload.put("Environment", Map.of("Variables", result.environmentVariables()));
        return toFunction(invoke("PUT", functionPath(result.functionName()) + "/configuration", payload));
    }

    /** Deletes a function by name. */
    public void deleteFunction(String functionName) throws LambdaClientException {
        invoke("DELETE", functionPath(functionName), null);
    }

    /** Returns the normalized endpoint shown in deployment diagnostics. */
    public String endpointDescription() {
        String value = endpoint.toString();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String encodedArtifact(DeploymentResult result) throws LambdaClientException {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(result.artifactPath()));
        } catch (IOException exception) {
            throw new LambdaClientException(
                    "LocalArtifactUnavailable", 0, "Could not read Lambda artifact '" + result.artifactPath() + "'");
        }
    }

    private JsonNode invoke(String method, String path, Object payload) throws LambdaClientException {
        byte[] body = encodePayload(payload);
        HttpRequest request = buildRequest(method, path, body);
        return send(request);
    }

    private byte[] encodePayload(Object payload) throws LambdaClientException {
        try {
            return payload == null ? new byte[0] : objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException exception) {
            throw new LambdaClientException("LocalRequestEncodingFailed", 0, "Could not encode Lambda request");
        }
    }

    private JsonNode send(HttpRequest request) throws LambdaClientException {
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new LambdaClientException(
                    "LocalEndpointUnavailable", 0, "Could not reach local Lambda endpoint '" + endpoint + "'");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LambdaClientException(
                    "LocalEndpointInterrupted",
                    0,
                    "Interrupted while calling local Lambda endpoint '" + endpoint + "'");
        }
        JsonNode payloadNode = readPayload(response.body());
        return checkResponse(response.statusCode(), payloadNode);
    }

    private static JsonNode checkResponse(int statusCode, JsonNode payload) throws LambdaClientException {
        if (statusCode < 400) {
            return payload;
        }
        String errorCode = payload.path("__type").asText("LocalRequestFailed");
        int separator = errorCode.indexOf('#');
        if (separator >= 0) {
            errorCode = errorCode.substring(separator + 1);
        }
        throw new LambdaClientException(
                errorCode, statusCode, payload.path("message").asText("Local Lambda request failed"));
    }

    private JsonNode readPayload(byte[] body) throws LambdaClientException {
        try {
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            throw new LambdaClientException("LocalResponseInvalid", 0, "Local Lambda returned invalid JSON");
        }
    }

    private LambdaRemoteFunction toFunction(JsonNode configuration) throws LambdaClientException {
        if (configuration == null || configuration.isMissingNode() || !configuration.isObject()) {
            throw new LambdaClientException(
                    "LocalResponseInvalid", 0, "Local Lambda response did not contain function configuration");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        JsonNode variables = configuration.path("Environment").path("Variables");
        variables
                .fields()
                .forEachRemaining(entry ->
                        environment.put(entry.getKey(), entry.getValue().asText()));
        String architecture = configuration.path("Architectures").path(0).asText();
        try {
            return new LambdaRemoteFunction(
                    configuration.path("FunctionName").asText(),
                    configuration.path("Runtime").asText(),
                    architecture,
                    configuration.path("Handler").asText(),
                    environment,
                    sha256Hex(configuration.path("CodeSha256").asText()),
                    configuration.path("RevisionId").asText());
        } catch (IllegalArgumentException exception) {
            throw new LambdaClientException(
                    "LocalResponseInvalid", 0, "Local Lambda response contained incomplete function configuration");
        }
    }

    private HttpRequest buildRequest(String method, String path, byte[] body) throws LambdaClientException {
        HttpRequest.Builder requestBuilder =
                HttpRequest.newBuilder(endpoint.resolve(path)).header("Content-Type", CONTENT_TYPE);
        return switch (method) {
            case "GET" -> requestBuilder.GET().build();
            case "DELETE" -> requestBuilder.DELETE().build();
            case "POST" ->
                requestBuilder
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
            case "PUT" ->
                requestBuilder.PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            default -> throw new LambdaClientException("LocalRequestInvalid", 0, "Unsupported Lambda HTTP method");
        };
    }

    private static String sha256Hex(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (IllegalArgumentException exception) {
            return base64;
        }
    }

    private static URI resolveEndpoint(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "ARES_AWS_LOCAL_ENDPOINT must be a valid HTTP URL; received '" + value + "'", exception);
        }
    }

    private String functionsPath() {
        return "2015-03-31/functions";
    }

    private String functionPath(String functionName) {
        return "2015-03-31/functions/" + functionName;
    }

    private static URI normalizeEndpoint(URI value) {
        Objects.requireNonNull(value, "endpoint");
        if (!"http".equalsIgnoreCase(value.getScheme()) && !"https".equalsIgnoreCase(value.getScheme())) {
            throw new IllegalArgumentException("Lambda endpoint must use http or https");
        }
        String text = value.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }
}
