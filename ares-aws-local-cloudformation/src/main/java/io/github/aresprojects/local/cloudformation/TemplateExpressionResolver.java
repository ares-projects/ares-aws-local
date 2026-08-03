package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evaluates the CloudFormation intrinsic subset needed by synthesized local stacks. */
public final class TemplateExpressionResolver {
    private static final Pattern SUBSTITUTION = Pattern.compile("\\$\\{([^}]+)}");
    private static final Set<String> SHORT_INTRINSICS = Set.of(
            "Ref",
            "Condition",
            "GetAtt",
            "Sub",
            "Join",
            "Split",
            "Select",
            "FindInMap",
            "If",
            "Equals",
            "And",
            "Or",
            "Not");
    private final Set<String> evaluatingConditions = new HashSet<>();
    private final ConditionResolver conditionResolver;
    private final Map<String, IntrinsicHandler> intrinsicHandlers;

    public TemplateExpressionResolver() {
        conditionResolver = new ConditionResolver();
        Map<String, IntrinsicHandler> handlers = new HashMap<>();
        register(handlers, this::ref, "Ref");
        register(handlers, this::getAtt, "Fn::GetAtt", "GetAtt");
        register(handlers, this::sub, "Fn::Sub", "Sub");
        register(handlers, this::join, "Fn::Join", "Join");
        register(handlers, this::split, "Fn::Split", "Split");
        register(handlers, this::select, "Fn::Select", "Select");
        register(handlers, this::findInMap, "Fn::FindInMap", "FindInMap");
        register(handlers, conditionResolver::ifValue, "Fn::If", "If");
        register(handlers, conditionResolver::equalsValue, "Fn::Equals", "Equals");
        handlers.put("Fn::And", conditionResolver::and);
        handlers.put("And", conditionResolver::and);
        handlers.put("Fn::Or", conditionResolver::or);
        handlers.put("Or", conditionResolver::or);
        handlers.put("Fn::Not", conditionResolver::not);
        handlers.put("Not", conditionResolver::not);
        handlers.put(
                "Condition", (value, context) -> BooleanNode.valueOf(conditionResolver.conditionValue(value, context)));
        intrinsicHandlers = Map.copyOf(handlers);
    }

    /** Resolves a JSON expression against parameters, mappings, conditions, and resource values. */
    public JsonNode resolve(JsonNode expression, EvaluationContext context) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(context, "context");
        if (expression.isValueNode()) {
            return expression.deepCopy();
        }
        if (expression.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            expression.forEach(value -> result.add(resolve(value, context)));
            return result;
        }
        if (!expression.isObject()) {
            return expression.deepCopy();
        }
        if (expression.size() == 1) {
            Map.Entry<String, JsonNode> intrinsic = expression.fields().next();
            if (isIntrinsic(intrinsic.getKey())) {
                return resolveIntrinsic(intrinsic.getKey(), intrinsic.getValue(), context);
            }
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        expression.fields().forEachRemaining(entry -> result.set(entry.getKey(), resolve(entry.getValue(), context)));
        return result;
    }

    private static boolean isIntrinsic(String name) {
        return name.startsWith("Fn::") || SHORT_INTRINSICS.contains(name);
    }

    /** Removes properties that resolved to the CloudFormation {@code AWS::NoValue} sentinel. */
    public JsonNode removeNoValue(JsonNode value) {
        return NoValueRemover.clean(value);
    }

    private static final class NoValueRemover {
        private NoValueRemover() {}

        private static JsonNode clean(JsonNode value) {
            if (value.isObject()) {
                ObjectNode object = (ObjectNode) value.deepCopy();
                Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
                List<String> remove = new ArrayList<>();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (field.getValue().isMissingNode()) {
                        remove.add(field.getKey());
                    } else {
                        object.set(field.getKey(), clean(field.getValue()));
                    }
                }
                remove.forEach(object::remove);
                return object;
            }
            if (value.isArray()) {
                ArrayNode array = JsonNodeFactory.instance.arrayNode();
                value.forEach(element -> {
                    if (!element.isMissingNode()) {
                        array.add(clean(element));
                    }
                });
                return array;
            }
            return value;
        }
    }

    private JsonNode resolveIntrinsic(String name, JsonNode value, EvaluationContext context) {
        IntrinsicHandler handler = intrinsicHandlers.get(name);
        if (handler == null) {
            throw new CloudFormationException("Unsupported CloudFormation intrinsic '" + name + "'");
        }
        return handler.resolve(value, context);
    }

    private static void register(Map<String, IntrinsicHandler> handlers, IntrinsicHandler handler, String... names) {
        for (String name : names) {
            handlers.put(name, handler);
        }
    }

    private JsonNode ref(JsonNode value, EvaluationContext context) {
        String reference = requiredText(value, "Ref");
        int attributeSeparator = reference.indexOf('.');
        if (attributeSeparator > 0 && attributeSeparator < reference.length() - 1) {
            return getAtt(
                    JsonNodeFactory.instance
                            .arrayNode()
                            .add(reference.substring(0, attributeSeparator))
                            .add(reference.substring(attributeSeparator + 1)),
                    context);
        }
        String pseudo = context.pseudoParameters().get(reference);
        if (pseudo != null) {
            return TextNode.valueOf(pseudo);
        }
        if ("AWS::NoValue".equals(reference)) {
            return JsonNodeFactory.instance.missingNode();
        }
        String parameter = context.parameters().get(reference);
        if (parameter != null) {
            return TextNode.valueOf(parameter);
        }
        ProvisionedResource resource = context.resources().get(reference);
        if (resource != null) {
            return TextNode.valueOf(resource.referenceValue());
        }
        throw new CloudFormationException("Ref references unknown parameter or resource '" + reference + "'");
    }

    private JsonNode getAtt(JsonNode value, EvaluationContext context) {
        if (!value.isArray() || value.size() != 2) {
            throw new CloudFormationException("Fn::GetAtt requires [logicalId, attributeName]");
        }
        String logicalId = requiredText(value.get(0), "Fn::GetAtt logicalId");
        String attribute = requiredText(value.get(1), "Fn::GetAtt attributeName");
        ProvisionedResource resource = context.resources().get(logicalId);
        if (resource == null) {
            throw new CloudFormationException("Fn::GetAtt references unknown resource '" + logicalId + "'");
        }
        String result = resource.attributes().get(attribute);
        if (result == null) {
            throw new CloudFormationException(
                    "Resource '" + logicalId + "' does not expose attribute '" + attribute + "'");
        }
        return TextNode.valueOf(result);
    }

    private JsonNode sub(JsonNode value, EvaluationContext context) {
        String template;
        Map<String, JsonNode> variables = Map.of();
        if (value.isTextual()) {
            template = value.textValue();
        } else if (value.isArray()
                && value.size() == 2
                && value.get(0).isTextual()
                && value.get(1).isObject()) {
            template = value.get(0).textValue();
            Map<String, JsonNode> supplied = new LinkedHashMap<>();
            value.get(1)
                    .fields()
                    .forEachRemaining(entry -> supplied.put(entry.getKey(), resolve(entry.getValue(), context)));
            variables = supplied;
        } else {
            throw new CloudFormationException("Fn::Sub requires a string or [string, variables]");
        }
        Matcher matcher = SUBSTITUTION.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = variables.containsKey(key)
                    ? scalarText(variables.get(key), "Fn::Sub variable '" + key + "'")
                    : scalarText(ref(TextNode.valueOf(key), context), "Fn::Sub reference '" + key + "'");
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return TextNode.valueOf(output.toString());
    }

    private JsonNode join(JsonNode value, EvaluationContext context) {
        if (!value.isArray()
                || value.size() != 2
                || !value.get(0).isTextual()
                || !value.get(1).isArray()) {
            throw new CloudFormationException("Fn::Join requires [delimiter, values]");
        }
        String delimiter = value.get(0).textValue();
        List<String> parts = new ArrayList<>();
        value.get(1).forEach(element -> parts.add(scalarText(resolve(element, context), "Fn::Join value")));
        return TextNode.valueOf(String.join(delimiter, parts));
    }

    private JsonNode split(JsonNode value, EvaluationContext context) {
        if (!value.isArray() || value.size() != 2 || !value.get(0).isTextual()) {
            throw new CloudFormationException("Fn::Split requires [delimiter, source]");
        }
        String source = scalarText(resolve(value.get(1), context), "Fn::Split source");
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        for (String part : source.split(Pattern.quote(value.get(0).textValue()), -1)) {
            result.add(part);
        }
        return result;
    }

    private JsonNode select(JsonNode value, EvaluationContext context) {
        if (!value.isArray() || value.size() != 2 || !value.get(0).canConvertToInt()) {
            throw new CloudFormationException("Fn::Select requires [index, list]");
        }
        JsonNode list = resolve(value.get(1), context);
        int index = value.get(0).intValue();
        if (!list.isArray() || index < 0 || index >= list.size()) {
            throw new CloudFormationException("Fn::Select index " + index + " is outside the resolved list");
        }
        return list.get(index).deepCopy();
    }

    private JsonNode findInMap(JsonNode value, EvaluationContext context) {
        if (!value.isArray() || value.size() != 3) {
            throw new CloudFormationException("Fn::FindInMap requires [mapName, topLevelKey, secondLevelKey]");
        }
        String map = requiredText(resolve(value.get(0), context), "Fn::FindInMap mapName");
        String top = scalarText(resolve(value.get(1), context), "Fn::FindInMap top key");
        String second = scalarText(resolve(value.get(2), context), "Fn::FindInMap second key");
        JsonNode result = context.mappings()
                .getOrDefault(map, JsonNodeFactory.instance.objectNode())
                .path(top)
                .path(second);
        if (result.isMissingNode()) {
            throw new CloudFormationException("Fn::FindInMap could not resolve " + map + "." + top + "." + second);
        }
        return result.deepCopy();
    }

    /** Evaluates a named template condition. */
    public boolean evaluateCondition(String name, EvaluationContext context) {
        return conditionResolver.named(name, context);
    }

    private static String requiredText(JsonNode value, String name) {
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new CloudFormationException(name + " requires a non-blank string");
        }
        return value.textValue();
    }

    private static String scalarText(JsonNode value, String name) {
        if (value == null || value.isObject() || value.isArray() || value.isNull() || value.isMissingNode()) {
            throw new CloudFormationException(name + " must resolve to a scalar value");
        }
        return value.asText();
    }

    private final class ConditionResolver {
        private JsonNode ifValue(JsonNode value, EvaluationContext context) {
            if (!value.isArray() || value.size() != 3) {
                throw new CloudFormationException("Fn::If requires [conditionName, trueValue, falseValue]");
            }
            return resolve(value.get(value(conditionValue(value.get(0), context))), context);
        }

        private BooleanNode and(JsonNode value, EvaluationContext context) {
            return booleanAggregate(value, context, true, "Fn::And");
        }

        private BooleanNode or(JsonNode value, EvaluationContext context) {
            return booleanAggregate(value, context, false, "Fn::Or");
        }

        private JsonNode not(JsonNode value, EvaluationContext context) {
            if (!value.isArray() || value.size() != 1) {
                throw new CloudFormationException("Fn::Not requires a one-item array");
            }
            return BooleanNode.valueOf(!conditionValue(value.get(0), context));
        }

        private BooleanNode booleanAggregate(JsonNode value, EvaluationContext context, boolean all, String name) {
            if (!value.isArray() || value.isEmpty()) {
                throw new CloudFormationException(name + " requires a non-empty array");
            }
            boolean result = all;
            for (JsonNode element : value) {
                boolean current = conditionValue(element, context);
                result = all ? result && current : result || current;
            }
            return BooleanNode.valueOf(result);
        }

        private BooleanNode equalsValue(JsonNode value, EvaluationContext context) {
            if (!value.isArray() || value.size() != 2) {
                throw new CloudFormationException("Fn::Equals requires exactly 2 values");
            }
            return BooleanNode.valueOf(resolve(value.get(0), context).equals(resolve(value.get(1), context)));
        }

        private boolean named(String name, EvaluationContext context) {
            if (!context.conditions().containsKey(name)) {
                throw new CloudFormationException("Condition '" + name + "' is not declared");
            }
            return conditionValue(TextNode.valueOf(name), context);
        }

        private boolean conditionValue(JsonNode value, EvaluationContext context) {
            if (value.isTextual() && context.conditions().containsKey(value.textValue())) {
                String name = value.textValue();
                if (!evaluatingConditions.add(name)) {
                    throw new CloudFormationException("Condition cycle detected at '" + name + "'");
                }
                try {
                    return conditionValue(resolve(context.conditions().get(name), context), context);
                } finally {
                    evaluatingConditions.remove(name);
                }
            }
            JsonNode resolved = resolve(value, context);
            if (!resolved.isBoolean()) {
                throw new CloudFormationException("Condition must resolve to boolean but was " + resolved);
            }
            return resolved.booleanValue();
        }

        private static int value(boolean condition) {
            return condition ? 1 : 2;
        }
    }

    @FunctionalInterface
    private interface IntrinsicHandler {
        JsonNode resolve(JsonNode value, EvaluationContext context);
    }

    /** Inputs needed while evaluating a template expression. */
    public record EvaluationContext(
            Map<String, String> parameters,
            Map<String, JsonNode> mappings,
            Map<String, JsonNode> conditions,
            Map<String, ProvisionedResource> resources,
            Map<String, String> pseudoParameters) {
        public EvaluationContext {
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
            mappings = Map.copyOf(Objects.requireNonNull(mappings, "mappings"));
            conditions = Map.copyOf(Objects.requireNonNull(conditions, "conditions"));
            resources = Map.copyOf(Objects.requireNonNull(resources, "resources"));
            pseudoParameters = Map.copyOf(Objects.requireNonNull(pseudoParameters, "pseudoParameters"));
        }
    }
}
