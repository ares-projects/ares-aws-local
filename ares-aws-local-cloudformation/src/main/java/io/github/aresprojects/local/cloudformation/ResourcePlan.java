package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable action plan for one template resource. */
public record ResourcePlan(
        TemplateResource resource,
        ResourceAction action,
        JsonNode properties,
        List<String> dependencies,
        String reason,
        Optional<CloudFormationResourceHandler> handler,
        ProvisionedResource existing) {
    public ResourcePlan {
        resource = Objects.requireNonNull(resource, "resource");
        action = Objects.requireNonNull(action, "action");
        properties = Objects.requireNonNull(properties, "properties").deepCopy();
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        reason = Objects.requireNonNull(reason, "reason");
        handler = Objects.requireNonNull(handler, "handler");
    }

    public static ResourcePlan withHandler(
            TemplateResource resource,
            ResourceAction action,
            JsonNode properties,
            List<String> dependencies,
            String reason,
            CloudFormationResourceHandler handler,
            ProvisionedResource existing) {
        return new ResourcePlan(
                resource, action, properties, dependencies, reason, Optional.ofNullable(handler), existing);
    }

    public Optional<CloudFormationResourceHandler> handlerOptional() {
        return handler;
    }

    public Optional<ProvisionedResource> existingOptional() {
        return Optional.ofNullable(existing);
    }
}
