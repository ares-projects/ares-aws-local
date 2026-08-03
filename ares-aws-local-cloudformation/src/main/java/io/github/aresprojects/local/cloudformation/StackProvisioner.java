package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes a deterministic stack plan and rolls back resources created by a failed attempt. */
public final class StackProvisioner {
    private final TemplateExpressionResolver resolver;
    private final StackStateStore stateStore;

    public StackProvisioner(StackStateStore stateStore) {
        this(new TemplateExpressionResolver(), stateStore);
    }

    StackProvisioner(TemplateExpressionResolver resolver, StackStateStore stateStore) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    /** Applies a plan; dry-run callers should stop before invoking this method. */
    public StackDeploymentResult provision(
            CloudFormationTemplate template, StackPlan plan, ResourceOperationContext context) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(context, "context");
        Map<String, ProvisionedResource> resources = new LinkedHashMap<>();
        stateStore.find(plan.stackName()).ifPresent(state -> resources.putAll(state.resources()));
        List<ProvisionedResource> created = new ArrayList<>();
        List<DeploymentDiagnostic> diagnostics = new ArrayList<>(plan.diagnostics());
        try {
            for (ResourcePlan resourcePlan : plan.resources()) {
                if (resourcePlan.action() == ResourceAction.NO_OP) {
                    resourcePlan.existingOptional().ifPresent(value -> resources.put(value.logicalId(), value));
                } else if (resourcePlan.action() == ResourceAction.CREATE) {
                    CloudFormationResourceHandler handler =
                            resourcePlan.handlerOptional().orElseThrow();
                    JsonNode properties = resolveProperties(template, resourcePlan, context, resources);
                    handler.validate(context, resourcePlan.resource(), properties);
                    ProvisionedResource resource = handler.create(context, resourcePlan.resource(), properties);
                    resources.put(resource.logicalId(), resource);
                    created.add(resource);
                } else {
                    diagnostics.add(new DeploymentDiagnostic(
                            resourcePlan.resource().logicalId(), resourcePlan.action(), resourcePlan.reason()));
                }
            }
            Map<String, String> outputs = resolveOutputs(template, context, resources);
            StackState state = new StackState(plan.stackName(), resources, outputs);
            stateStore.save(state);
            StackDeploymentStatus status = plan.partial() || !diagnostics.isEmpty()
                    ? StackDeploymentStatus.PARTIAL
                    : StackDeploymentStatus.COMPLETE;
            return new StackDeploymentResult(plan.stackName(), status, diagnostics, outputs, state);
        } catch (RuntimeException failure) {
            List<DeploymentDiagnostic> rollbackDiagnostics = new ArrayList<>(diagnostics);
            for (int index = created.size() - 1; index >= 0; index--) {
                ProvisionedResource resource = created.get(index);
                try {
                    CloudFormationResourceHandler handler = findHandler(plan, resource.resourceType());
                    handler.delete(context, resource);
                } catch (RuntimeException rollbackFailure) {
                    rollbackDiagnostics.add(new DeploymentDiagnostic(
                            resource.logicalId(),
                            ResourceAction.BLOCKED,
                            "Rollback failed for '" + resource.logicalId() + "': " + rollbackFailure.getMessage()));
                    return new StackDeploymentResult(
                            plan.stackName(),
                            StackDeploymentStatus.ROLLBACK_FAILED,
                            rollbackDiagnostics,
                            Map.of(),
                            null);
                }
            }
            rollbackDiagnostics.add(new DeploymentDiagnostic(
                    "<stack>",
                    ResourceAction.BLOCKED,
                    "Provisioning failed and created resources were rolled back: " + failure.getMessage()));
            return new StackDeploymentResult(
                    plan.stackName(), StackDeploymentStatus.ROLLBACK_COMPLETE, rollbackDiagnostics, Map.of(), null);
        }
    }

    private static CloudFormationResourceHandler findHandler(StackPlan plan, String type) {
        return plan.resources().stream()
                .filter(resource -> resource.resource().type().equals(type)
                        && resource.handlerOptional().isPresent())
                .map(resource -> resource.handlerOptional().orElseThrow())
                .findFirst()
                .orElseThrow(() -> new CloudFormationException("No handler is available to roll back '" + type + "'"));
    }

    private JsonNode resolveProperties(
            CloudFormationTemplate template,
            ResourcePlan resourcePlan,
            ResourceOperationContext context,
            Map<String, ProvisionedResource> resources) {
        return resolver.removeNoValue(resolver.resolve(
                resourcePlan.properties(),
                new TemplateExpressionResolver.EvaluationContext(
                        context.parameters(), template.mappings(), template.conditions(), resources, pseudo(context))));
    }

    private Map<String, String> resolveOutputs(
            CloudFormationTemplate template,
            ResourceOperationContext context,
            Map<String, ProvisionedResource> resources) {
        Map<String, String> outputs = new LinkedHashMap<>();
        TemplateExpressionResolver.EvaluationContext evaluation = new TemplateExpressionResolver.EvaluationContext(
                context.parameters(), template.mappings(), template.conditions(), resources, pseudo(context));
        template.outputs().forEach((name, value) -> {
            JsonNode condition = value.path("Condition");
            if (condition.isTextual() && !resolver.evaluateCondition(condition.textValue(), evaluation)) {
                return;
            }
            JsonNode resolved = resolver.resolve(value.path("Value"), evaluation);
            if (!resolved.isMissingNode()) {
                outputs.put(name, resolved.asText());
            }
        });
        return outputs;
    }

    private static Map<String, String> pseudo(ResourceOperationContext context) {
        return Map.of(
                "AWS::AccountId",
                context.accountId(),
                "AWS::Region",
                context.region(),
                "AWS::Partition",
                "aws",
                "AWS::StackName",
                context.stackName(),
                "AWS::StackId",
                "arn:aws:cloudformation:" + context.region() + ":" + context.accountId() + ":stack/"
                        + context.stackName() + "/local",
                "AWS::URLSuffix",
                "amazonaws.com");
    }
}
