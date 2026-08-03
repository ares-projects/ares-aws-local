package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/** Implements CloudFormation lifecycle behavior for one resource type. */
public interface CloudFormationResourceHandler {
    /** Returns the exact CloudFormation resource type handled by this implementation. */
    String resourceType();

    /** Validates resolved properties before any stack mutation. */
    void validate(ResourceOperationContext context, TemplateResource resource, JsonNode properties);

    /** Creates a resource and returns its physical identity and attributes. */
    ProvisionedResource create(ResourceOperationContext context, TemplateResource resource, JsonNode properties);

    /** Reads the current local resource state, or returns empty when it no longer exists. */
    Optional<ProvisionedResource> read(ResourceOperationContext context, ProvisionedResource resource);

    /** Updates a resource; unsupported updates must throw an actionable exception. */
    ProvisionedResource update(
            ResourceOperationContext context,
            TemplateResource resource,
            JsonNode properties,
            ProvisionedResource current);

    /** Deletes a resource created by the stack engine. */
    void delete(ResourceOperationContext context, ProvisionedResource resource);
}
