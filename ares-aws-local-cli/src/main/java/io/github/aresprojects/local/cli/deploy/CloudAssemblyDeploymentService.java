package io.github.aresprojects.local.cli.deploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.cli.AresConfigurationException;
import io.github.aresprojects.local.cli.AresDeploymentException;
import io.github.aresprojects.local.cloudformation.CloudAssembly;
import io.github.aresprojects.local.cloudformation.CloudAssemblyArtifact;
import io.github.aresprojects.local.cloudformation.CloudAssemblyReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packages a local CDK assembly and sends it to the Ares stack control plane. */
public final class CloudAssemblyDeploymentService {
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:4566";
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final CloudAssemblyReader reader;

    public CloudAssemblyDeploymentService() {
        this(HttpClient.newHttpClient(), new ObjectMapper(), new CloudAssemblyReader());
    }

    CloudAssemblyDeploymentService(HttpClient client, ObjectMapper mapper, CloudAssemblyReader reader) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** Deploys or previews one selected stack from an existing Cloud Assembly. */
    public DeploymentOutcome deploy(
            Path assemblyRoot, String requestedStack, Map<String, String> parameters, boolean dryRun, URI endpoint)
            throws AresConfigurationException, AresDeploymentException {
        CloudAssembly assembly = reader.read(assemblyRoot);
        CloudAssemblyArtifact stack = select(assembly, requestedStack);
        Map<String, String> mergedParameters = new LinkedHashMap<>();
        stack.properties()
                .path("parameters")
                .fields()
                .forEachRemaining(entry ->
                        mergedParameters.put(entry.getKey(), entry.getValue().asText()));
        mergedParameters.putAll(parameters);
        byte[] bundle = bundle(assembly, stack.id(), mergedParameters);
        String endpointValue = endpoint == null ? resolveEndpoint() : trimEndpoint(endpoint);
        URI target =
                URI.create(endpointValue + (dryRun ? "/_ares/cloudformation/plan" : "/_ares/cloudformation/deploy"));
        try {
            HttpRequest request = HttpRequest.newBuilder(target)
                    .header("content-type", "application/zip")
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bundle))
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            JsonNode body = mapper.readTree(response.body());
            if (response.statusCode() >= 400) {
                throw new AresDeploymentException("Cloud Assembly endpoint rejected the deployment: "
                        + body.path("error").asText(new String(response.body(), StandardCharsets.UTF_8)));
            }
            return new DeploymentOutcome(body.path("status").asText("UNKNOWN"), body);
        } catch (IOException exception) {
            throw new AresDeploymentException(
                    "Could not reach local Cloud Assembly endpoint '" + target + "': " + exception.getMessage(),
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AresDeploymentException(
                    "Interrupted while deploying Cloud Assembly to '" + target + "'", exception);
        }
    }

    private byte[] bundle(CloudAssembly assembly, String stackId, Map<String, String> parameters)
            throws AresConfigurationException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                var descriptor = mapper.createObjectNode();
                descriptor.put("schemaVersion", 1);
                descriptor.put("assemblyRoot", "assembly");
                descriptor.put("stackArtifactId", stackId);
                var values = descriptor.putObject("parameters");
                parameters.forEach(values::put);
                addEntry(zip, "ares-deployment.json", mapper.writeValueAsBytes(descriptor));
                List<Path> files;
                try (var stream = Files.walk(assembly.root())) {
                    files = stream.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(Path::toString))
                            .toList();
                }
                for (Path file : files) {
                    if (Files.isSymbolicLink(file)) {
                        throw new AresConfigurationException(
                                "Cloud Assembly contains symbolic link '" + file + "'; remove it");
                    }
                    String relative = assembly.root()
                            .relativize(file)
                            .toString()
                            .replace(file.getFileSystem().getSeparator(), "/");
                    addEntry(zip, "assembly/" + relative, Files.readAllBytes(file));
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new AresConfigurationException(
                    "Could not package Cloud Assembly '" + assembly.root() + "': " + exception.getMessage(), exception);
        }
    }

    private static void addEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static CloudAssemblyArtifact select(CloudAssembly assembly, String requestedStack)
            throws AresConfigurationException {
        List<CloudAssemblyArtifact> stacks = assembly.stacks();
        if (stacks.isEmpty()) {
            throw new AresConfigurationException("Cloud Assembly contains no CloudFormation stack artifacts");
        }
        if (requestedStack != null) {
            return stacks.stream()
                    .filter(stack -> stack.id().equals(requestedStack))
                    .findFirst()
                    .orElseThrow(() -> new AresConfigurationException("Unknown Cloud Assembly stack '" + requestedStack
                            + "'; available: "
                            + stacks.stream().map(CloudAssemblyArtifact::id).toList()));
        }
        if (stacks.size() != 1) {
            throw new AresConfigurationException(
                    "Cloud Assembly contains multiple stacks; pass --stack <artifact-id>. Available: "
                            + stacks.stream().map(CloudAssemblyArtifact::id).toList());
        }
        return stacks.getFirst();
    }

    private static String resolveEndpoint() {
        return System.getenv().getOrDefault("ARES_AWS_LOCAL_ENDPOINT", DEFAULT_ENDPOINT);
    }

    private static String trimEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String value = endpoint.toString();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record DeploymentOutcome(String status, JsonNode body) {
        public DeploymentOutcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(body, "body");
        }

        public String summary() {
            return "Cloud Assembly deployment status: " + status;
        }
    }
}
