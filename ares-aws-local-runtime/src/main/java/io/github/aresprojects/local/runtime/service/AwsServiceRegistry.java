package io.github.aresprojects.local.runtime.service;

import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import io.github.aresprojects.local.runtime.http.AwsRequestHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Routes immutable request snapshots to the first registered adapter that supports them.
 *
 * <p>The registry is assembled once during startup. Future protocol-specific AWS adapters can then be wired into
 * the server without changing the transport:
 *
 * <pre>{@code
 * AwsServiceRegistry registry = AwsServiceRegistry.builder()
 *         .register(new SqsJsonAdapter(new InMemorySqsQueueStore()))
 *         .build();
 *
 * try (LocalAwsServer server = new LocalAwsServer(config, registry)) {
 *     server.start();
 * }
 * }</pre>
 *
 * <p>Adapters are evaluated in registration order. This lets protocol-specific adapters coexist for one AWS
 * service, provided their {@link AwsServiceAdapter#supports(AwsRequestContext)} checks are appropriately scoped.
 */
public final class AwsServiceRegistry implements AwsRequestHandler {
    private static final AwsHttpResponse NO_MATCH_RESPONSE =
            AwsHttpResponse.json(404, "{\"error\":\"no AWS service adapter matched request\"}");

    private final List<AwsServiceAdapter> adapters;

    private AwsServiceRegistry(List<AwsServiceAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    /** Starts an immutable registry definition for wiring service adapters during startup. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Routes a request in registration order so protocol-specific adapters can coexist for one service.
     *
     * @param request the immutable request context
     * @return the selected adapter response or a completed 404 response when no adapter matches
     */
    @Override
    public CompletionStage<AwsHttpResponse> handle(AwsRequestContext request) {
        Objects.requireNonNull(request, "request");
        for (AwsServiceAdapter adapter : adapters) {
            if (adapter.supports(request)) {
                return adapter.handle(request);
            }
        }
        return CompletableFuture.completedFuture(NO_MATCH_RESPONSE);
    }

    /** Builds an immutable registry while retaining adapter registration order. */
    public static final class Builder {
        private final List<AwsServiceAdapter> adapters = new ArrayList<>();

        private Builder() {}

        /**
         * Registers an adapter for future requests.
         *
         * @param adapter the adapter to register
         * @return this builder
         * @throws NullPointerException when adapter or its service name is null
         * @throws IllegalArgumentException when the service name is blank
         */
        public Builder register(AwsServiceAdapter adapter) {
            Objects.requireNonNull(adapter, "adapter");
            String serviceName = Objects.requireNonNull(adapter.serviceName(), "adapter.serviceName()");
            if (serviceName.isBlank()) {
                throw new IllegalArgumentException(
                        "adapter.serviceName() must not be blank; provide a stable service identifier");
            }
            adapters.add(adapter);
            return this;
        }

        /**
         * Creates a registry snapshot; later builder changes do not affect the result.
         *
         * @return an immutable service registry
         */
        public AwsServiceRegistry build() {
            return new AwsServiceRegistry(adapters);
        }
    }
}
