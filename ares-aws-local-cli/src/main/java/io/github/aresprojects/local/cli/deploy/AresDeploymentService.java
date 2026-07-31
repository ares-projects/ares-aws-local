package io.github.aresprojects.local.cli.deploy;

import io.github.aresprojects.local.cli.AresBuildException;
import io.github.aresprojects.local.cli.AresConfigurationException;
import io.github.aresprojects.local.cli.AresDeploymentException;
import io.github.aresprojects.local.cli.builder.AresBuildService;
import io.github.aresprojects.local.cli.builder.DeploymentResult;
import java.nio.file.Path;
import java.util.Objects;

/** Builds a project and reconciles its generated result with the local Lambda control plane. */
public final class AresDeploymentService {
    private final AresBuildService buildService;
    private final LocalLambdaClient client;

    /** Creates a deployment service using the default build service and local endpoint. */
    public AresDeploymentService() {
        this(new AresBuildService(), new LocalLambdaClient());
    }

    /** Creates a deployment service with injectable boundaries for tests. */
    public AresDeploymentService(AresBuildService buildService, LocalLambdaClient client) {
        this.buildService = Objects.requireNonNull(buildService, "buildService");
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Builds and reconciles one local Lambda function. */
    public DeploymentOutcome deploy(Path projectDirectory)
            throws AresConfigurationException, AresBuildException, AresDeploymentException {
        DeploymentResult result = buildService.build(projectDirectory);
        try {
            var existing = client.getFunction(result.functionName());
            if (existing.isEmpty()) {
                client.createFunction(result);
                return new DeploymentOutcome(
                        "Created Lambda function '" + result.functionName() + "'",
                        result.runtime(),
                        client.endpointDescription());
            }
            LambdaRemoteFunction function = existing.get();
            boolean codeChanged = !result.artifactSha256().equals(function.codeSha256());
            boolean configChanged = !result.runtime().equals(function.runtime())
                    || !result.architecture().equals(function.architecture())
                    || !result.handler().equals(function.handler())
                    || !result.environmentVariables().equals(function.environment());
            if (codeChanged) {
                client.updateFunctionCode(result);
            }
            if (configChanged) {
                client.updateFunctionConfiguration(result);
            }
            if (!codeChanged && !configChanged) {
                return new DeploymentOutcome(
                        "Lambda function '" + result.functionName() + "' is unchanged",
                        result.runtime(),
                        client.endpointDescription());
            }
            return new DeploymentOutcome(
                    "Updated Lambda function '" + result.functionName() + "'",
                    result.runtime(),
                    client.endpointDescription());
        } catch (LambdaClientException exception) {
            throw new AresDeploymentException(
                    "Could not deploy Lambda function '" + result.functionName() + "': " + exception.getMessage(),
                    exception);
        }
    }

    /** Describes the user-facing deployment result. */
    public record DeploymentOutcome(String summary, String runtime, String endpoint) {
        public DeploymentOutcome {
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(endpoint, "endpoint");
        }
    }
}
