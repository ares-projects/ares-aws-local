package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds deterministic CloudFormation resource plans without mutating service state. */
public final class StackPlanner {
    private final CloudFormationResourceHandlerRegistry handlers;
    private final TemplateExpressionResolver resolver;

    public StackPlanner(CloudFormationResourceHandlerRegistry handlers) {
        this(handlers, new TemplateExpressionResolver());
    }

    StackPlanner(CloudFormationResourceHandlerRegistry handlers, TemplateExpressionResolver resolver) {
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Builds an ordered plan and reports unsupported resources rather than hiding them. */
    public StackPlan plan(
            CloudFormationTemplate template,
            ResourceOperationContext context,
            Map<String, ProvisionedResource> existing) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(context, "context");
        existing = Map.copyOf(Objects.requireNonNull(existing, "existing"));
        Map<String, TemplateResource> byId = indexResources(template.resources());
        DependencyScan dependencyScan = scanDependencies(template, byId);
        Map<String, Set<String>> dependencies = dependencyScan.dependencies();
        List<DeploymentDiagnostic> diagnostics = new ArrayList<>(dependencyScan.diagnostics());
        List<TemplateResource> ordered = topologicalOrder(template.resources(), dependencies);
        List<ResourcePlan> plans = new ArrayList<>();
        if (template.document().has("Transform")) {
            return blockedTransformPlan(ordered, dependencies, diagnostics, existing, context);
        }
        Map<String, ResourceAction> actions = new HashMap<>();
        for (TemplateResource resource : ordered) {
            plans.add(planResource(
                    template, resource, dependencies, dependencyScan.missingResources(), actions, existing, context));
        }
        existing.keySet().stream()
                .filter(logicalId -> !byId.containsKey(logicalId))
                .forEach(logicalId -> diagnostics.add(new DeploymentDiagnostic(
                        logicalId,
                        ResourceAction.DELETE_UNSUPPORTED,
                        "Resource was removed from the template; deletion is not implemented for reconciliation")));
        return new StackPlan(context.stackName(), plans, diagnostics);
    }

    private ResourcePlan planResource(
            CloudFormationTemplate template,
            TemplateResource resource,
            Map<String, Set<String>> dependencies,
            Set<String> missingResources,
            Map<String, ResourceAction> actions,
            Map<String, ProvisionedResource> existing,
            ResourceOperationContext context) {
        try {
            if (conditionIsFalse(template, resource, existing, context)) {
                actions.put(resource.logicalId(), ResourceAction.SKIPPED_CONDITION);
                return ResourcePlan.withHandler(
                        resource,
                        ResourceAction.SKIPPED_CONDITION,
                        resource.properties(),
                        dependencies.get(resource.logicalId()).stream().toList(),
                        "Condition evaluated to false",
                        null,
                        existing.get(resource.logicalId()));
            }
        } catch (ConditionEvaluationException exception) {
            actions.put(resource.logicalId(), ResourceAction.BLOCKED);
            return blocked(resource, dependencies, exception.getCause().getMessage(), existing);
        }
        if (missingResources.contains(resource.logicalId())) {
            actions.put(resource.logicalId(), ResourceAction.BLOCKED);
            return blocked(resource, dependencies, "Resource has an unknown dependency", existing);
        }
        Optional<String> blockedDependency = dependencies.get(resource.logicalId()).stream()
                .filter(dependency -> actions.get(dependency) != null
                        && actions.get(dependency) != ResourceAction.CREATE
                        && actions.get(dependency) != ResourceAction.NO_OP)
                .findFirst();
        if (blockedDependency.isPresent()) {
            actions.put(resource.logicalId(), ResourceAction.BLOCKED);
            return blocked(
                    resource,
                    dependencies,
                    "Dependency '" + blockedDependency.orElseThrow() + "' was not provisioned",
                    existing);
        }
        if ("AWS::CDK::Metadata".equals(resource.type())) {
            actions.put(resource.logicalId(), ResourceAction.NO_OP);
            return ResourcePlan.withHandler(
                    resource,
                    ResourceAction.NO_OP,
                    resource.properties(),
                    dependencies.get(resource.logicalId()).stream().toList(),
                    "CDK metadata is local no-op",
                    null,
                    existing.get(resource.logicalId()));
        }
        return handlerPlan(resource, dependencies, actions, existing, context);
    }

    private ResourcePlan handlerPlan(
            TemplateResource resource,
            Map<String, Set<String>> dependencies,
            Map<String, ResourceAction> actions,
            Map<String, ProvisionedResource> existing,
            ResourceOperationContext context) {
        CloudFormationResourceHandler handler = handlers.find(resource.type()).orElse(null);
        if (handler == null) {
            actions.put(resource.logicalId(), ResourceAction.SKIPPED_UNSUPPORTED);
            return ResourcePlan.withHandler(
                    resource,
                    ResourceAction.SKIPPED_UNSUPPORTED,
                    resource.properties(),
                    dependencies.get(resource.logicalId()).stream().toList(),
                    "No local handler is registered for resource type '" + resource.type() + "'",
                    null,
                    existing.get(resource.logicalId()));
        }
        ProvisionedResource current = existing.get(resource.logicalId());
        ResourceAction action = current == null ? ResourceAction.CREATE : ResourceAction.NO_OP;
        String reason =
                current == null ? "Resource is not present in local stack state" : "Resolved resource is unchanged";
        if (current != null && !current.resolvedProperties().equals(resource.properties())) {
            action = ResourceAction.UPDATE_UNSUPPORTED;
            reason = "Resource properties changed; update handlers are not implemented for this slice";
        }
        try {
            handler.validate(context, resource, resource.properties());
        } catch (CloudFormationException exception) {
            action = ResourceAction.SKIPPED_UNSUPPORTED;
            reason = exception.getMessage();
        }
        actions.put(resource.logicalId(), action);
        return ResourcePlan.withHandler(
                resource,
                action,
                resource.properties(),
                dependencies.get(resource.logicalId()).stream().toList(),
                reason,
                handler,
                current);
    }

    private boolean conditionIsFalse(
            CloudFormationTemplate template,
            TemplateResource resource,
            Map<String, ProvisionedResource> existing,
            ResourceOperationContext context) {
        if (resource.condition() == null) {
            return false;
        }
        try {
            return !resolver.evaluateCondition(
                    resource.condition(),
                    new TemplateExpressionResolver.EvaluationContext(
                            context.parameters(),
                            template.mappings(),
                            template.conditions(),
                            existing,
                            pseudo(context)));
        } catch (CloudFormationException exception) {
            throw new ConditionEvaluationException(exception);
        }
    }

    private static StackPlan blockedTransformPlan(
            List<TemplateResource> ordered,
            Map<String, Set<String>> dependencies,
            List<DeploymentDiagnostic> diagnostics,
            Map<String, ProvisionedResource> existing,
            ResourceOperationContext context) {
        diagnostics.add(new DeploymentDiagnostic(
                "<stack>",
                ResourceAction.BLOCKED,
                "CloudFormation transforms and macros are not supported; synthesize a template without Transform"
                        + " before deploying locally"));
        List<ResourcePlan> plans = new ArrayList<>();
        for (TemplateResource resource : ordered) {
            plans.add(blocked(
                    resource,
                    dependencies,
                    "Resource is blocked because the template requires an unsupported Transform or macro",
                    existing));
        }
        return new StackPlan(context.stackName(), plans, diagnostics);
    }

    private static Map<String, TemplateResource> indexResources(List<TemplateResource> resources) {
        Map<String, TemplateResource> byId = new LinkedHashMap<>();
        for (TemplateResource resource : resources) {
            if (byId.putIfAbsent(resource.logicalId(), resource) != null) {
                throw new CloudFormationException("Duplicate logical resource id '" + resource.logicalId() + "'");
            }
        }
        return byId;
    }

    private static DependencyScan scanDependencies(
            CloudFormationTemplate template, Map<String, TemplateResource> byId) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        Set<String> missingResources = new HashSet<>();
        List<DeploymentDiagnostic> diagnostics = new ArrayList<>();
        for (TemplateResource resource : template.resources()) {
            Set<String> refs = resourceReferences(template, resource);
            for (String dependency : refs) {
                if (!byId.containsKey(dependency)) {
                    missingResources.add(resource.logicalId());
                    diagnostics.add(new DeploymentDiagnostic(
                            resource.logicalId(),
                            ResourceAction.BLOCKED,
                            "Resource references unknown dependency '" + dependency + "'"));
                }
            }
            dependencies.put(resource.logicalId(), refs);
        }
        return new DependencyScan(dependencies, missingResources, diagnostics);
    }

    private static Set<String> resourceReferences(CloudFormationTemplate template, TemplateResource resource) {
        Set<String> refs = new LinkedHashSet<>(resource.dependsOn());
        collectReferences(resource.properties(), refs);
        refs.removeAll(template.parameters().keySet());
        refs.removeAll(Set.of(
                "AWS::AccountId",
                "AWS::Region",
                "AWS::Partition",
                "AWS::StackName",
                "AWS::StackId",
                "AWS::URLSuffix",
                "AWS::NoValue"));
        refs.remove(resource.logicalId());
        return refs;
    }

    private record DependencyScan(
            Map<String, Set<String>> dependencies,
            Set<String> missingResources,
            List<DeploymentDiagnostic> diagnostics) {}

    private static final class ConditionEvaluationException extends RuntimeException {
        private ConditionEvaluationException(CloudFormationException cause) {
            super(cause);
        }
    }

    private static ResourcePlan blocked(
            TemplateResource resource,
            Map<String, Set<String>> dependencies,
            String reason,
            Map<String, ProvisionedResource> existing) {
        return ResourcePlan.withHandler(
                resource,
                ResourceAction.BLOCKED,
                resource.properties(),
                dependencies.get(resource.logicalId()).stream().toList(),
                reason,
                null,
                existing.get(resource.logicalId()));
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

    private static List<TemplateResource> topologicalOrder(
            List<TemplateResource> resources, Map<String, Set<String>> dependencies) {
        Map<String, TemplateResource> byId = new LinkedHashMap<>();
        resources.forEach(resource -> byId.put(resource.logicalId(), resource));
        Map<String, Integer> state = new HashMap<>();
        List<TemplateResource> ordered = new ArrayList<>();
        for (TemplateResource resource : resources) {
            visit(resource.logicalId(), byId, dependencies, state, ordered);
        }
        return ordered;
    }

    private static void visit(
            String id,
            Map<String, TemplateResource> byId,
            Map<String, Set<String>> dependencies,
            Map<String, Integer> state,
            List<TemplateResource> ordered) {
        Integer current = state.get(id);
        if (current != null) {
            if (current == 1) {
                throw new CloudFormationException("CloudFormation resource dependency cycle includes '" + id + "'");
            }
            return;
        }
        state.put(id, 1);
        for (String dependency : dependencies.getOrDefault(id, Set.of())) {
            if (byId.containsKey(dependency)) {
                visit(dependency, byId, dependencies, state, ordered);
            }
        }
        state.put(id, 2);
        ordered.add(byId.get(id));
    }

    private static void collectReferences(JsonNode node, Set<String> references) {
        if (node == null || node.isMissingNode() || node.isValueNode()) {
            return;
        }
        if (node.isObject()) {
            collectObjectReferences(node, references);
        } else if (node.isArray()) {
            node.forEach(value -> collectReferences(value, references));
        }
    }

    private static void collectObjectReferences(JsonNode node, Set<String> references) {
        collectRef(node, references);
        collectGetAtt(node, references);
        collectSub(node, references);
        node.fields().forEachRemaining(entry -> collectReferences(entry.getValue(), references));
    }

    private static void collectRef(JsonNode node, Set<String> references) {
        if (node.size() == 1 && node.has("Ref") && node.path("Ref").isTextual()) {
            references.add(node.path("Ref").textValue());
        }
    }

    private static void collectGetAtt(JsonNode node, Set<String> references) {
        if (node.size() == 1
                && node.has("Fn::GetAtt")
                && node.path("Fn::GetAtt").isArray()
                && node.path("Fn::GetAtt").size() > 0
                && node.path("Fn::GetAtt").get(0).isTextual()) {
            references.add(node.path("Fn::GetAtt").get(0).textValue());
        }
    }

    private static void collectSub(JsonNode node, Set<String> references) {
        if (node.size() != 1 || !node.has("Fn::Sub")) {
            return;
        }
        JsonNode sub = node.path("Fn::Sub");
        String value = sub.isTextual() ? sub.textValue() : sub.path(0).asText("");
        Set<String> suppliedVariables = suppliedVariables(sub);
        Matcher matcher = Pattern.compile("\\$\\{([^}.]+)(?:\\.[^}]+)?}").matcher(value);
        while (matcher.find()) {
            addSubReference(matcher.group(1), suppliedVariables, references);
        }
        if (sub.isArray() && sub.size() == 2) {
            collectReferences(sub.get(1), references);
        }
    }

    private static Set<String> suppliedVariables(JsonNode sub) {
        Set<String> variables = new HashSet<>();
        if (sub.isArray() && sub.size() == 2 && sub.get(1).isObject()) {
            sub.get(1).fieldNames().forEachRemaining(variables::add);
        }
        return variables;
    }

    private static void addSubReference(String reference, Set<String> suppliedVariables, Set<String> references) {
        if (!suppliedVariables.contains(reference)) {
            references.add(reference);
        }
    }
}
