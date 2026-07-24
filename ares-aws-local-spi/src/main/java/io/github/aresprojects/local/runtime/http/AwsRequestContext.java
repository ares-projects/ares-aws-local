package io.github.aresprojects.local.runtime.http;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable request snapshot that prevents transport buffers from leaking into service code. */
public final class AwsRequestContext {

    private final String requestId;
    private final Instant receivedAt;
    private final String method;
    private final String protocolVersion;
    private final String rawTarget;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final InetSocketAddress remoteAddress;
    private final InetSocketAddress localAddress;

    /** Copies mutable transport data so asynchronous handlers can safely outlive the Netty callback. */
    public AwsRequestContext(
            String requestId,
            Instant receivedAt,
            String method,
            String protocolVersion,
            String rawTarget,
            Map<String, ? extends List<String>> headers,
            byte[] body,
            InetSocketAddress remoteAddress,
            InetSocketAddress localAddress) {
        this.requestId = requireText(requestId, "requestId");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.method = requireText(method, "method");
        this.protocolVersion = requireText(protocolVersion, "protocolVersion");
        this.rawTarget = requireText(rawTarget, "rawTarget");
        this.headers = immutableHeaders(headers);
        this.body = Objects.requireNonNull(body, "body").clone();
        this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
        this.localAddress = Objects.requireNonNull(localAddress, "localAddress");
    }

    public String requestId() {
        return requestId;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public String method() {
        return method;
    }

    public String protocolVersion() {
        return protocolVersion;
    }

    public String rawTarget() {
        return rawTarget;
    }

    public Map<String, List<String>> headers() {
        return immutableHeaders(headers);
    }

    /** Resolves one header without exposing transport-specific header casing. */
    public Optional<String> firstHeader(String name) {
        String normalizedName = normalizeHeaderName(name);
        List<String> values = headers.get(normalizedName);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    public byte[] body() {
        return body.clone();
    }

    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    public InetSocketAddress localAddress() {
        return localAddress;
    }

    private static Map<String, List<String>> immutableHeaders(Map<String, ? extends List<String>> source) {
        Objects.requireNonNull(source, "headers");
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, values) -> {
            String normalizedName = normalizeHeaderName(name);
            List<String> valueCopy = new ArrayList<>(Objects.requireNonNull(values, "header values"));
            valueCopy.forEach(value -> Objects.requireNonNull(value, "header value"));
            copy.computeIfAbsent(normalizedName, ignored -> new ArrayList<>()).addAll(valueCopy);
        });
        copy.replaceAll((ignored, values) -> Collections.unmodifiableList(values));
        return Collections.unmodifiableMap(copy);
    }

    private static String normalizeHeaderName(String name) {
        return requireText(name, "header name").toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
