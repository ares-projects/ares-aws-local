package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Immutable CloudFormation template model retaining resource declaration order. */
public final class CloudFormationTemplate {
    private final JsonNode document;
    private final Map<String, JsonNode> parameters;
    private final Map<String, JsonNode> mappings;
    private final Map<String, JsonNode> conditions;
    private final List<TemplateResource> resources;
    private final Map<String, JsonNode> outputs;

    public CloudFormationTemplate(
            JsonNode document,
            Map<String, JsonNode> parameters,
            Map<String, JsonNode> mappings,
            Map<String, JsonNode> conditions,
            List<TemplateResource> resources,
            Map<String, JsonNode> outputs) {
        this.document = Objects.requireNonNull(document, "document").deepCopy();
        this.parameters = copy(parameters);
        this.mappings = copy(mappings);
        this.conditions = copy(conditions);
        this.resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        this.outputs = copy(outputs);
    }

    public JsonNode document() {
        return document.deepCopy();
    }

    public Map<String, JsonNode> parameters() {
        return copy(parameters);
    }

    public Map<String, JsonNode> mappings() {
        return copy(mappings);
    }

    public Map<String, JsonNode> conditions() {
        return copy(conditions);
    }

    public List<TemplateResource> resources() {
        return resources;
    }

    public Map<String, JsonNode> outputs() {
        return copy(outputs);
    }

    private static Map<String, JsonNode> copy(Map<String, JsonNode> values) {
        Objects.requireNonNull(values, "values");
        return values.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().deepCopy()));
    }
}
