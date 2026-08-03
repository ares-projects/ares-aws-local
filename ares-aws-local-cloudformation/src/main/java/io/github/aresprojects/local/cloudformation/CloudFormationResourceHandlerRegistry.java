package io.github.aresprojects.local.cloudformation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable registry of CloudFormation resource handlers. */
public final class CloudFormationResourceHandlerRegistry {
    private final Map<String, CloudFormationResourceHandler> handlers;

    private CloudFormationResourceHandlerRegistry(Map<String, CloudFormationResourceHandler> handlers) {
        this.handlers = Map.copyOf(handlers);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Returns the handler for a type, or empty when the type is not implemented locally. */
    public java.util.Optional<CloudFormationResourceHandler> find(String resourceType) {
        return java.util.Optional.ofNullable(handlers.get(resourceType));
    }

    public List<String> resourceTypes() {
        return List.copyOf(handlers.keySet());
    }

    public static final class Builder {
        private final Map<String, CloudFormationResourceHandler> handlers = new LinkedHashMap<>();

        private Builder() {}

        public Builder register(CloudFormationResourceHandler handler) {
            Objects.requireNonNull(handler, "handler");
            String type = Objects.requireNonNull(handler.resourceType(), "handler.resourceType()");
            if (type.isBlank()) {
                throw new IllegalArgumentException(
                        "handler.resourceType() must not be blank; provide a CloudFormation type");
            }
            if (handlers.putIfAbsent(type, handler) != null) {
                throw new IllegalArgumentException(
                        "CloudFormation resource handler for '" + type + "' is already registered");
            }
            return this;
        }

        public CloudFormationResourceHandlerRegistry build() {
            return new CloudFormationResourceHandlerRegistry(handlers);
        }
    }
}
