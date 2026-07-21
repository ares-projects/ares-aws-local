package io.github.aresprojects.local.runtime;

import java.util.Map;
import java.util.Objects;

/** Immutable bind and buffering limits shared by startup code and tests. */
public record LocalAwsServerConfig(String host, int port, int maxRequestBytes) {

    /** Keeps the default listener local to the development machine. */
    public static final String DEFAULT_HOST = "127.0.0.1";

    /** Matches the conventional local AWS endpoint used by clients. */
    public static final int DEFAULT_PORT = 4566;

    /** Bounds memory used while a request body is assembled for a handler. */
    public static final int DEFAULT_MAX_REQUEST_BYTES = 16 * 1024 * 1024;

    private static final String HOST_ENV = "ARES_AWS_LOCAL_HOST";
    private static final String PORT_ENV = "ARES_AWS_LOCAL_PORT";
    private static final String MAX_REQUEST_BYTES_ENV = "ARES_AWS_LOCAL_MAX_REQUEST_BYTES";

    /** Validates configuration once so every server instance obeys the same limits. */
    public LocalAwsServerConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank; set a bind address such as 127.0.0.1");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535; use 0 for an ephemeral port");
        }
        if (maxRequestBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBytes must be positive; increase the request limit");
        }
    }

    /** Provides predictable local defaults for the runnable runtime. */
    public static LocalAwsServerConfig defaults() {
        return new LocalAwsServerConfig(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_MAX_REQUEST_BYTES);
    }

    /** Reads environment overrides so local processes need no command-line-specific configuration. */
    public static LocalAwsServerConfig fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return new LocalAwsServerConfig(
                valueOrDefault(environment, HOST_ENV, DEFAULT_HOST),
                integerOrDefault(environment, PORT_ENV, DEFAULT_PORT),
                integerOrDefault(environment, MAX_REQUEST_BYTES_ENV, DEFAULT_MAX_REQUEST_BYTES));
    }

    private static String valueOrDefault(Map<String, String> environment, String name, String defaultValue) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int integerOrDefault(Map<String, String> environment, String name, int defaultValue) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer; received '" + value + "'", exception);
        }
    }
}
