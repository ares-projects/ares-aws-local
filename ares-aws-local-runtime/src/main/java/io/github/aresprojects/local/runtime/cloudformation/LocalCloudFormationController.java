package io.github.aresprojects.local.runtime.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aresprojects.local.cloudformation.CloudAssembly;
import io.github.aresprojects.local.cloudformation.CloudAssemblyArtifact;
import io.github.aresprojects.local.cloudformation.CloudAssemblyReader;
import io.github.aresprojects.local.cloudformation.CloudFormationResourceHandlerRegistry;
import io.github.aresprojects.local.cloudformation.CloudFormationTemplate;
import io.github.aresprojects.local.cloudformation.CloudFormationTemplateParser;
import io.github.aresprojects.local.cloudformation.DeploymentDiagnostic;
import io.github.aresprojects.local.cloudformation.InMemoryStackStateStore;
import io.github.aresprojects.local.cloudformation.ProvisionedResource;
import io.github.aresprojects.local.cloudformation.ResourceOperationContext;
import io.github.aresprojects.local.cloudformation.ResourcePlan;
import io.github.aresprojects.local.cloudformation.StackDeploymentResult;
import io.github.aresprojects.local.cloudformation.StackDeploymentStatus;
import io.github.aresprojects.local.cloudformation.StackPlan;
import io.github.aresprojects.local.cloudformation.StackPlanner;
import io.github.aresprojects.local.cloudformation.StackProvisioner;
import io.github.aresprojects.local.cloudformation.StackState;
import io.github.aresprojects.local.cloudformation.StackStateStore;
import io.github.aresprojects.local.runtime.http.AwsHttpResponse;
import io.github.aresprojects.local.runtime.http.AwsRequestContext;
import io.github.aresprojects.local.runtime.service.sqs.SqsQueueStore;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Handles the local, bundle-based Cloud Assembly control-plane endpoints. */
public final class LocalCloudFormationController {
    private static final String PLAN_PATH = "/_ares/cloudformation/plan";
    private static final String DEPLOY_PATH = "/_ares/cloudformation/deploy";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CloudAssemblyReader assemblyReader = new CloudAssemblyReader();
    private final CloudFormationTemplateParser templateParser = new CloudFormationTemplateParser();
    private final CloudFormationResourceHandlerRegistry handlers;
    private final StackStateStore stateStore;
    private final ConcurrentMap<String, ReentrantLock> stackLocks = new ConcurrentHashMap<>();

    public LocalCloudFormationController(SqsQueueStore queueStore) {
        this(
                CloudFormationResourceHandlerRegistry.builder()
                        .register(new SqsQueueResourceHandler(queueStore))
                        .build(),
                new InMemoryStackStateStore());
    }

    LocalCloudFormationController(CloudFormationResourceHandlerRegistry handlers, StackStateStore stateStore) {
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    /** Returns whether the request belongs to the Ares Cloud Assembly control plane. */
    public boolean supports(AwsRequestContext request) {
        return PLAN_PATH.equals(request.rawTarget()) || DEPLOY_PATH.equals(request.rawTarget());
    }

    /** Plans or provisions one bundled stack. */
    public CompletionStage<AwsHttpResponse> handle(AwsRequestContext request) {
        Bundle bundle = null;
        try {
            if (!"POST".equals(request.method())) {
                return completed(400, "Ares Cloud Assembly endpoints require POST; received " + request.method());
            }
            if (request.firstHeader("content-type")
                    .filter(value -> value.startsWith("application/zip"))
                    .isEmpty()) {
                return completed(415, "Cloud Assembly deployment requires Content-Type application/zip");
            }
            bundle = unpack(request.body());
            CloudAssembly assembly = assemblyReader.read(bundle.assemblyRoot());
            CloudAssemblyArtifact artifact = select(assembly, bundle.stackArtifactId());
            CloudFormationTemplate template = templateParser.parse(artifact.templateFile());
            Map<String, String> parameters = resolveParameters(template, bundle.parameters());
            URI endpoint = endpoint(request);
            ResourceOperationContext context = new ResourceOperationContext(
                    artifact.stackName(),
                    "000000000000",
                    "us-east-1",
                    endpoint,
                    java.time.Clock.systemUTC(),
                    parameters,
                    identifier -> Optional.empty());
            StackState current = stateStore.find(artifact.stackName()).orElse(null);
            Map<String, ProvisionedResource> existing = current == null ? Map.of() : current.resources();
            StackPlan plan = new StackPlanner(handlers).plan(template, context, existing);
            if (PLAN_PATH.equals(request.rawTarget())) {
                return completed(planResponse(plan));
            }
            ReentrantLock lock = stackLocks.computeIfAbsent(artifact.stackName(), ignored -> new ReentrantLock());
            lock.lock();
            try {
                current = stateStore.find(artifact.stackName()).orElse(null);
                existing = current == null ? Map.of() : current.resources();
                plan = new StackPlanner(handlers).plan(template, context, existing);
                StackDeploymentResult result = new StackProvisioner(stateStore).provision(template, plan, context);
                return completed(resultResponse(result));
            } finally {
                lock.unlock();
                stackLocks.remove(artifact.stackName(), lock);
            }
        } catch (RuntimeException exception) {
            return completed(400, "Cloud Assembly deployment failed: " + exception.getMessage());
        } finally {
            if (bundle != null) {
                deleteTree(bundle.assemblyRoot().getParent());
            }
        }
    }

    private CloudAssemblyArtifact select(CloudAssembly assembly, String requested) {
        List<CloudAssemblyArtifact> stacks = assembly.stacks();
        if (stacks.isEmpty()) {
            throw new IllegalArgumentException("Cloud Assembly contains no CloudFormation stack artifacts");
        }
        if (requested != null && !requested.isBlank()) {
            return stacks.stream()
                    .filter(stack -> stack.id().equals(requested))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Cloud Assembly has no stack artifact '" + requested
                            + "'; available stacks: "
                            + stacks.stream().map(CloudAssemblyArtifact::id).toList()));
        }
        if (stacks.size() != 1) {
            throw new IllegalArgumentException("Cloud Assembly contains multiple stacks; select one with --stack: "
                    + stacks.stream().map(CloudAssemblyArtifact::id).toList());
        }
        return stacks.getFirst();
    }

    private Bundle unpack(byte[] bytes) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("ares-cloud-assembly-");
            extractBundle(bytes, directory);
            return readBundle(directory);
        } catch (IOException exception) {
            deleteTree(directory);
            throw new IllegalArgumentException(
                    "Could not unpack Cloud Assembly deployment bundle: " + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            deleteTree(directory);
            throw exception;
        }
    }

    private static void extractBundle(byte[] bytes, Path directory) throws IOException {
        try (InputStream input = new java.io.ByteArrayInputStream(bytes);
                ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                extractEntry(zip, entry, directory);
            }
        }
    }

    private static void extractEntry(ZipInputStream zip, ZipEntry entry, Path directory) throws IOException {
        if (entry.isDirectory()) {
            return;
        }
        Path target = directory.resolve(entry.getName()).normalize();
        if (!target.startsWith(directory)) {
            throw new IllegalArgumentException(
                    "Cloud Assembly bundle contains a path outside its root: " + entry.getName());
        }
        Path parent = Objects.requireNonNull(target.getParent(), "Cloud Assembly entry parent");
        Files.createDirectories(parent);
        Files.copy(zip, target);
    }

    private Bundle readBundle(Path directory) throws IOException {
        Path descriptor = directory.resolve("ares-deployment.json");
        JsonNode document = mapper.readTree(Files.readString(descriptor));
        validateDescriptorVersion(document);
        String assemblyRoot = document.path("assemblyRoot").asText("assembly");
        Path assemblyPath = Path.of(assemblyRoot).normalize();
        if (assemblyPath.isAbsolute() || assemblyPath.startsWith("..")) {
            throw new IllegalArgumentException(
                    "Ares deployment descriptor assemblyRoot must remain inside the bundle: " + assemblyRoot);
        }
        return new Bundle(
                directory.resolve(assemblyPath),
                document.path("stackArtifactId").asText(""),
                parameters(document));
    }

    private static void validateDescriptorVersion(JsonNode document) {
        int schemaVersion = document.path("schemaVersion").asInt(-1);
        if (schemaVersion != 1) {
            throw new IllegalArgumentException(
                    "Unsupported Ares deployment descriptor schema version " + schemaVersion + "; expected 1");
        }
    }

    private static Map<String, String> parameters(JsonNode document) {
        Map<String, String> parameters = new LinkedHashMap<>();
        JsonNode parameterNode = document.path("parameters");
        if (!parameterNode.isObject()) {
            throw new IllegalArgumentException("Ares deployment descriptor parameters must be an object");
        }
        parameterNode
                .fields()
                .forEachRemaining(
                        entry -> parameters.put(entry.getKey(), entry.getValue().asText()));
        return parameters;
    }

    private static Map<String, String> resolveParameters(
            CloudFormationTemplate template, Map<String, String> supplied) {
        Map<String, String> resolved = new LinkedHashMap<>();
        template.parameters().forEach((name, definition) -> {
            String value = supplied.get(name);
            if (value == null && definition.has("Default")) {
                value = definition.path("Default").asText();
            }
            if (value == null) {
                throw new IllegalArgumentException("Required CloudFormation parameter '" + name
                        + "' has no value; pass --parameter " + name + "=<value>");
            }
            JsonNode allowed = definition.path("AllowedValues");
            if (allowed.isArray()
                    && java.util.stream.StreamSupport.stream(allowed.spliterator(), false)
                            .map(JsonNode::asText)
                            .noneMatch(value::equals)) {
                throw new IllegalArgumentException("CloudFormation parameter '" + name + "' must be one of " + allowed
                        + "; received '" + value + "'");
            }
            resolved.put(name, value);
        });
        supplied.keySet().stream()
                .filter(name -> !template.parameters().containsKey(name))
                .forEach(name -> throwUnknownParameter(name));
        return resolved;
    }

    private static void throwUnknownParameter(String name) {
        throw new IllegalArgumentException("CloudFormation parameter '" + name + "' is not declared in the template");
    }

    private static void deleteTree(Path root) {
        try {
            if (root != null && Files.exists(root)) {
                try (var paths = Files.walk(root)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Temporary bundle cleanup is best effort; the request result is already complete.
                        }
                    });
                }
            }
        } catch (IOException ignored) {
            // Temporary bundle cleanup is best effort; the request result is already complete.
        }
    }

    private byte[] planResponse(StackPlan plan) {
        try {
            var response = mapper.createObjectNode();
            response.put("stackName", plan.stackName());
            response.put("status", StackDeploymentStatus.PLANNED.name());
            var actions = response.putArray("resources");
            for (ResourcePlan item : plan.resources()) {
                var action = actions.addObject();
                action.put("logicalId", item.resource().logicalId());
                action.put("type", item.resource().type());
                action.put("action", item.action().name());
                action.put("reason", item.reason());
            }
            return mapper.writeValueAsBytes(response);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode Cloud Assembly plan response", exception);
        }
    }

    private byte[] resultResponse(StackDeploymentResult result) {
        try {
            var response = mapper.createObjectNode();
            response.put("stackName", result.stackName());
            response.put("status", result.status().name());
            var outputs = response.putObject("outputs");
            result.outputs().forEach(outputs::put);
            var diagnostics = response.putArray("diagnostics");
            result.diagnostics().forEach(item -> diagnostic(diagnostics, item));
            return mapper.writeValueAsBytes(response);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode Cloud Assembly deployment response", exception);
        }
    }

    private static void diagnostic(com.fasterxml.jackson.databind.node.ArrayNode array, DeploymentDiagnostic item) {
        var diagnostic = array.addObject();
        diagnostic.put("logicalId", item.logicalId());
        diagnostic.put("action", item.action().name());
        diagnostic.put("message", item.message());
    }

    private static URI endpoint(AwsRequestContext request) {
        String authority = request.firstHeader("host").orElseGet(() -> {
            InetSocketAddress address = request.localAddress();
            return address.getHostString() + ":" + address.getPort();
        });
        return URI.create("http://" + authority);
    }

    private static CompletionStage<AwsHttpResponse> completed(int status, String message) {
        return CompletableFuture.completedFuture(AwsHttpResponse.json(
                status, "{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"));
    }

    private static CompletionStage<AwsHttpResponse> completed(byte[] body) {
        return CompletableFuture.completedFuture(
                new AwsHttpResponse(200, Map.of("content-type", List.of("application/json")), body));
    }

    private record Bundle(Path assemblyRoot, String stackArtifactId, Map<String, String> parameters) {}
}
