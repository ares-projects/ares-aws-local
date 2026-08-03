package io.github.aresprojects.local.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads the versioned JSON manifest produced by {@code cdk synth}. */
public final class CloudAssemblyReader {
    private static final String MANIFEST = "manifest.json";
    private static final int MAX_SUPPORTED_MAJOR = 54;
    private final ObjectMapper mapper;

    public CloudAssemblyReader() {
        this(new ObjectMapper());
    }

    CloudAssemblyReader(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Reads and validates one assembly directory. */
    public CloudAssembly read(Path assemblyRoot) {
        Path root = requireDirectory(assemblyRoot);
        Path manifest = root.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            throw new CloudFormationException(
                    "Cloud Assembly is missing '" + manifest + "'; run 'cdk synth' before deploying");
        }
        try {
            JsonNode document = mapper.readTree(Files.readString(manifest));
            String version = text(document, "version", manifest);
            int major = parseMajor(version, manifest);
            if (major > MAX_SUPPORTED_MAJOR) {
                throw new CloudFormationException("Unsupported Cloud Assembly schema version '" + version
                        + "'; Ares supports major versions through " + MAX_SUPPORTED_MAJOR);
            }
            JsonNode artifacts = requiredObject(document, "artifacts", manifest);
            List<CloudAssemblyArtifact> entries = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = artifacts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                readArtifact(root, manifest, entry, entries);
            }
            return new CloudAssembly(root, version, entries);
        } catch (IOException | IllegalArgumentException exception) {
            throw new CloudFormationException(
                    "Could not read Cloud Assembly manifest '" + manifest + "': " + exception.getMessage(), exception);
        }
    }

    private static void readArtifact(
            Path root, Path manifest, Map.Entry<String, JsonNode> entry, List<CloudAssemblyArtifact> entries) {
        JsonNode artifact = entry.getValue();
        String type = text(artifact, "type", manifest);
        JsonNode properties = artifact.path("properties");
        if (!artifact.isObject() || properties.isMissingNode()) {
            throw new CloudFormationException(
                    "Artifact '" + entry.getKey() + "' in " + manifest + " has no properties object");
        }
        if (!"aws:cloudformation:stack".equals(type)) {
            return;
        }
        String templateFile = text(properties, "templateFile", manifest);
        Path template = safeAssemblyPath(root, templateFile);
        List<String> dependencies = new ArrayList<>();
        artifact.path("dependencies").forEach(node -> dependencies.add(node.asText()));
        entries.add(new CloudAssemblyArtifact(
                entry.getKey(),
                type,
                template,
                properties.path("stackName").asText(entry.getKey()),
                dependencies,
                properties));
    }

    private static Path requireDirectory(Path path) {
        Objects.requireNonNull(path, "assemblyRoot");
        if (!Files.isDirectory(path)) {
            throw new CloudFormationException("Cloud Assembly path '" + path + "' is not a directory");
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path safeAssemblyPath(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new CloudFormationException(
                    "Cloud Assembly file '" + relative + "' escapes assembly root '" + root + "'");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new CloudFormationException("Cloud Assembly file '" + resolved + "' does not exist or is not a file");
        }
        return resolved;
    }

    private static JsonNode requiredObject(JsonNode parent, String field, Path source) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) {
            throw new CloudFormationException(
                    "Assembly manifest '" + source + "' requires an object field '" + field + "'");
        }
        return value;
    }

    private static String text(JsonNode parent, String field, Path source) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new CloudFormationException(
                    "Assembly manifest '" + source + "' requires a non-blank '" + field + "'");
        }
        return value.textValue();
    }

    private static int parseMajor(String version, Path source) {
        String[] parts = version.split("\\.");
        if (parts.length < 2) {
            throw new CloudFormationException(
                    "Assembly manifest '" + source + "' has invalid schema version '" + version + "'");
        }
        try {
            return Integer.parseInt(parts[0]);
        } catch (NumberFormatException exception) {
            throw new CloudFormationException(
                    "Assembly manifest '" + source + "' has invalid schema version '" + version + "'", exception);
        }
    }
}
