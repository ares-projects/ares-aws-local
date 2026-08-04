package io.github.aresprojects.local.dynamodb;

import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import io.github.aresprojects.local.runtime.service.AwsServiceAdapter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Bridges Ares AWS JSON requests to an embedded DynamoDB Local backend. */
public final class DynamoDbJsonAdapter implements AwsServiceAdapter, AutoCloseable {
    private static final String SERVICE_TARGET_PREFIX = "DynamoDB_20120810.";
    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final Set<String> SKIPPED_REQUEST_HEADERS = Set.of("connection", "content-length", "host");

    private final DynamoDbLocalServer server;
    private final HttpClient client;
    private boolean closed;

    /** Creates a DynamoDB adapter with an isolated in-memory backend. */
    public DynamoDbJsonAdapter() {
        this(new DynamoDbLocalServer());
    }

    /** Creates an adapter backed by the supplied DynamoDB Local lifecycle owner. */
    public DynamoDbJsonAdapter(DynamoDbLocalServer server) {
        this.server = Objects.requireNonNull(server, "server");
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String serviceName() {
        return "dynamodb";
    }

    @Override
    public boolean supports(AwsRequestContext request) {
        Objects.requireNonNull(request, "request");
        return request.method().equalsIgnoreCase("POST")
                && request.rawTarget().equals("/")
                && request.firstHeader("content-type")
                        .map(DynamoDbJsonAdapter::isJsonContentType)
                        .orElse(false)
                && request.firstHeader("x-amz-target")
                        .map(value -> value.startsWith(SERVICE_TARGET_PREFIX)
                                && value.length() > SERVICE_TARGET_PREFIX.length())
                        .orElse(false);
    }

    @Override
    public CompletionStage<AwsHttpResponse> handle(AwsRequestContext request) {
        if (!supports(request)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "DynamoDB request must be POST / with application/x-amz-json-1.0 and a DynamoDB_20120810 target"));
        }
        URI endpoint;
        try {
            synchronized (this) {
                if (closed) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Cannot handle DynamoDB requests after the adapter is closed"));
                }
                if (!isRunning()) {
                    server.start();
                }
                endpoint = server.endpoint();
            }
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach((name, values) -> {
            if (!SKIPPED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> new AwsHttpResponse(
                        response.statusCode(), response.headers().map(), response.body()))
                .exceptionallyCompose(failure -> CompletableFuture.failedFuture(new IllegalStateException(
                        "Could not forward DynamoDB request to local backend at " + endpoint, failure)));
    }

    /** Stops the embedded backend. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        server.close();
    }

    private synchronized boolean isRunning() {
        try {
            server.endpoint();
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private static boolean isJsonContentType(String value) {
        String mediaType = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals(JSON_CONTENT_TYPE);
    }
}
