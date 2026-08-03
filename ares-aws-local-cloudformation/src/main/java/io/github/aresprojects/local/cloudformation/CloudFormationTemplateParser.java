package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Parses the JSON templates emitted into a CDK cloud assembly. */
public final class CloudFormationTemplateParser {
    private final ObjectMapper mapper;

    public CloudFormationTemplateParser() {
        this(new ObjectMapper());
    }

    CloudFormationTemplateParser(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Reads one JSON template and validates its required CloudFormation structure. */
    public CloudFormationTemplate parse(Path templateFile) {
        Objects.requireNonNull(templateFile, "templateFile");
        try {
            return parse(mapper.readTree(Files.readString(templateFile)), templateFile);
        } catch (IOException exception) {
            throw new CloudFormationException(
                    "Could not read CloudFormation template '" + templateFile + "': " + exception.getMessage(),
                    exception);
        }
    }

    /** Parses one already-decoded JSON template. */
    public CloudFormationTemplate parse(JsonNode document, Path source) {
        if (document == null || !document.isObject()) {
            throw new CloudFormationException("CloudFormation template '" + source + "' must contain a JSON object");
        }
        JsonNode resourcesNode = document.path("Resources");
        if (!resourcesNode.isObject()) {
            throw new CloudFormationException("CloudFormation template '" + source + "' requires a Resources object");
        }
        List<TemplateResource> resources = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = resourcesNode.fields();
        int order = 0;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            resources.add(parseResource(field.getKey(), field.getValue(), source, order++));
        }
        return new CloudFormationTemplate(
                document,
                objectFields(document.path("Parameters")),
                objectFields(document.path("Mappings")),
                objectFields(document.path("Conditions")),
                resources,
                objectFields(document.path("Outputs")));
    }

    private static TemplateResource parseResource(String logicalId, JsonNode resource, Path source, int order) {
        if (!resource.isObject() || !resource.path("Type").isTextual()) {
            throw new CloudFormationException(
                    "Resource '" + logicalId + "' in '" + source + "' requires an object and a string Type");
        }
        return new TemplateResource(
                logicalId,
                resource.path("Type").textValue(),
                resource.path("Properties"),
                resource.path("Metadata"),
                dependsOn(logicalId, resource.path("DependsOn")),
                resource.path("Condition").asText(null),
                order);
    }

    private static List<String> dependsOn(String logicalId, JsonNode dependency) {
        List<String> dependencies = new ArrayList<>();
        if (dependency.isTextual()) {
            dependencies.add(dependency.textValue());
        } else if (dependency.isArray()) {
            dependency.forEach(value -> addDependency(logicalId, value, dependencies));
        } else if (!dependency.isMissingNode()) {
            throw new CloudFormationException(
                    "DependsOn for resource '" + logicalId + "' must be a string or array of strings");
        }
        return dependencies;
    }

    private static void addDependency(String logicalId, JsonNode value, List<String> dependencies) {
        if (!value.isTextual()) {
            throw new CloudFormationException("DependsOn for resource '" + logicalId + "' must contain strings");
        }
        dependencies.add(value.textValue());
    }

    private static Map<String, JsonNode> objectFields(JsonNode node) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue()));
        }
        return values;
    }
}
