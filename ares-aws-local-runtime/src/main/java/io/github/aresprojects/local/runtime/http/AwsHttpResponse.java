package io.github.aresprojects.local.runtime.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable response snapshot that can be completed asynchronously and written by the transport later. */
public final class AwsHttpResponse {

    private final int statusCode;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    /** Copies response data so handlers cannot mutate bytes after the transport begins writing. */
    public AwsHttpResponse(int statusCode, Map<String, ? extends List<String>> headers, byte[] body) {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode must be between 100 and 599; received " + statusCode);
        }
        this.statusCode = statusCode;
        this.headers = immutableHeaders(headers);
        this.body = Objects.requireNonNull(body, "body").clone();
    }

    /** Creates a body-only response for handlers that do not need custom headers. */
    public static AwsHttpResponse of(int statusCode, byte[] body) {
        return new AwsHttpResponse(statusCode, Map.of(), body);
    }

    /** Encodes JSON consistently for protocol and health-check responses. */
    public static AwsHttpResponse json(int statusCode, String body) {
        return new AwsHttpResponse(
                statusCode,
                Map.of("content-type", List.of("application/json")),
                Objects.requireNonNull(body, "body").getBytes(StandardCharsets.UTF_8));
    }

    public int statusCode() {
        return statusCode;
    }

    public Map<String, List<String>> headers() {
        return immutableHeaders(headers);
    }

    public byte[] body() {
        return body.clone();
    }

    private static Map<String, List<String>> immutableHeaders(Map<String, ? extends List<String>> source) {
        Objects.requireNonNull(source, "headers");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            String normalizedName = normalizeHeaderName(name);
            List<String> valueCopy = new ArrayList<>(Objects.requireNonNull(values, "header values"));
            valueCopy.forEach(value -> validateHeaderValue(Objects.requireNonNull(value, "header value")));
            copy.computeIfAbsent(normalizedName, ignored -> new ArrayList<>()).addAll(valueCopy);
        });
        copy.replaceAll((ignored, values) -> Collections.unmodifiableList(values));
        return Collections.unmodifiableMap(copy);
    }

    private static String normalizeHeaderName(String name) {
        Objects.requireNonNull(name, "header name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("header name must not be blank; provide a valid HTTP field name");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static void validateHeaderValue(String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("header value must not contain CR or LF characters");
        }
    }
}
