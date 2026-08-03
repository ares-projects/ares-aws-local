package io.github.aresprojects.local.cli;

import io.github.aresprojects.local.cli.builder.AresBuildService;
import io.github.aresprojects.local.cli.builder.DeploymentResult;
import io.github.aresprojects.local.cli.deploy.AresDeploymentService;
import io.github.aresprojects.local.cli.deploy.AresDeploymentService.DeploymentOutcome;
import io.github.aresprojects.local.cli.deploy.CloudAssemblyDeploymentOptions;
import io.github.aresprojects.local.cli.deploy.CloudAssemblyDeploymentService;
import io.github.aresprojects.local.cli.deploy.LambdaClientException;
import io.github.aresprojects.local.cli.deploy.LocalLambdaClient;
import io.github.aresprojects.local.runtime.LocalAwsRuntime;
import io.github.aresprojects.local.runtime.trigger.lambda.LambdaInvocationResult;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Implements the CLI-first local Lambda workflow. */
public final class AresCli {
    private static final String USAGE =
            "Usage: ares local start | ares build <function-path> | ares deploy <function-path>"
                    + " [--stack <id>] [--parameter Key=Value] [--dry-run]"
                    + " | ares invoke <function-name> [--event <path> | --payload <json>]";

    private final Consumer<String> output;
    private final Consumer<String> error;
    private final AresBuildService buildService;
    private final AresDeploymentService deploymentService;
    private final LocalLambdaClient lambdaClient;
    private final Runnable runtimeStarter;

    /** Creates a CLI using standard output, standard error, and production build boundaries. */
    public AresCli() {
        this(
                System.out::println,
                System.err::println,
                new AresBuildService(),
                new AresDeploymentService(),
                new LocalLambdaClient(),
                () -> LocalAwsRuntime.main(new String[0]));
    }

    AresCli(Consumer<String> output, Consumer<String> error, AresBuildService buildService) {
        this(
                output,
                error,
                buildService,
                new AresDeploymentService(buildService, new LocalLambdaClient()),
                new LocalLambdaClient(),
                () -> LocalAwsRuntime.main(new String[0]));
    }

    AresCli(Consumer<String> output, Consumer<String> error, AresBuildService buildService, Runnable runtimeStarter) {
        this(
                output,
                error,
                buildService,
                new AresDeploymentService(buildService, new LocalLambdaClient()),
                new LocalLambdaClient(),
                runtimeStarter);
    }

    AresCli(
            Consumer<String> output,
            Consumer<String> error,
            AresBuildService buildService,
            AresDeploymentService deploymentService,
            LocalLambdaClient lambdaClient,
            Runnable runtimeStarter) {
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
        this.buildService = Objects.requireNonNull(buildService, "buildService");
        this.deploymentService = Objects.requireNonNull(deploymentService, "deploymentService");
        this.lambdaClient = Objects.requireNonNull(lambdaClient, "lambdaClient");
        this.runtimeStarter = Objects.requireNonNull(runtimeStarter, "runtimeStarter");
    }

    AresCli(
            Consumer<String> output,
            Consumer<String> error,
            AresBuildService buildService,
            AresDeploymentService deploymentService,
            Runnable runtimeStarter) {
        this(output, error, buildService, deploymentService, new LocalLambdaClient(), runtimeStarter);
    }

    /** Runs a command and returns its documented process exit code without terminating the test process. */
    public int execute(String... arguments) {
        List<String> args = arguments == null ? List.of() : Arrays.asList(arguments);
        if (args.equals(List.of("local", "start"))) {
            return startRuntime();
        }
        if (args.size() == 2 && "build".equals(args.get(0))) {
            return build(args.get(1));
        }
        if (args.size() >= 2 && "deploy".equals(args.get(0))) {
            return deploy(args);
        }
        if (args.size() >= 2 && "invoke".equals(args.get(0))) {
            return invoke(args);
        }
        error.accept("Invalid command; " + USAGE);
        return AresExitCode.USAGE_ERROR.value();
    }

    /** Starts the existing runtime lifecycle for the foreground local endpoint. */
    private int startRuntime() {
        try {
            runtimeStarter.run();
            return AresExitCode.SUCCESS.value();
        } catch (RuntimeException exception) {
            error.accept("Could not start local runtime: " + exception.getMessage());
            return AresExitCode.RUNTIME_UNAVAILABLE.value();
        }
    }

    private int build(String requestedPath) {
        Path projectPath;
        try {
            projectPath = Path.of(requestedPath);
        } catch (InvalidPathException exception) {
            error.accept("Invalid function path '" + requestedPath + "'; provide a valid project directory");
            return AresExitCode.USAGE_ERROR.value();
        }
        try {
            DeploymentResult result = buildService.build(projectPath);
            output.accept("Built Lambda function '" + result.functionName() + "'");
            output.accept("Artifact: " + result.artifactPath());
            output.accept("Runtime: " + result.runtime());
            return AresExitCode.SUCCESS.value();
        } catch (AresConfigurationException exception) {
            error.accept(exception.getMessage());
            return AresExitCode.USAGE_ERROR.value();
        } catch (AresBuildException exception) {
            error.accept(exception.getMessage());
            return AresExitCode.BUILD_FAILED.value();
        }
    }

    private int deploy(List<String> arguments) {
        String requestedPath = arguments.get(1);
        Path projectPath;
        Path assemblyRoot;
        try {
            projectPath = Path.of(requestedPath);
        } catch (InvalidPathException exception) {
            error.accept("Invalid function path '" + requestedPath + "'; provide a valid project directory");
            return AresExitCode.USAGE_ERROR.value();
        }
        try {
            assemblyRoot = assemblyRoot(projectPath);
        } catch (IllegalArgumentException exception) {
            error.accept(exception.getMessage());
            return AresExitCode.USAGE_ERROR.value();
        }
        if (assemblyRoot != null) {
            return deployCloudAssembly(arguments, assemblyRoot);
        }
        if (arguments.size() != 2) {
            error.accept("Lambda project deployment does not accept Cloud Assembly options; " + USAGE);
            return AresExitCode.USAGE_ERROR.value();
        }
        return deployLambdaProject(projectPath);
    }

    private int deployCloudAssembly(List<String> arguments, Path assemblyRoot) {
        try {
            CloudAssemblyDeploymentOptions options = CloudAssemblyDeploymentOptions.parse(arguments);
            CloudAssemblyDeploymentService.DeploymentOutcome outcome = new CloudAssemblyDeploymentService()
                    .deploy(assemblyRoot, options.stack(), options.parameters(), options.dryRun(), options.endpoint());
            output.accept(outcome.summary());
            if (outcome.body().has("outputs")) {
                output.accept(outcome.body().path("outputs").toPrettyString());
            }
            if ("PARTIAL".equals(outcome.status())) {
                error.accept(outcome.body().path("diagnostics").toPrettyString());
                return AresExitCode.PARTIAL_DEPLOYMENT.value();
            }
            if ("ROLLBACK_COMPLETE".equals(outcome.status()) || "ROLLBACK_FAILED".equals(outcome.status())) {
                error.accept(outcome.body().path("diagnostics").toPrettyString());
                return AresExitCode.DEPLOYMENT_FAILED.value();
            }
            return AresExitCode.SUCCESS.value();
        } catch (AresConfigurationException exception) {
            error.accept(exception.getMessage());
            return AresExitCode.USAGE_ERROR.value();
        } catch (IllegalArgumentException exception) {
            error.accept("Invalid Cloud Assembly deployment options: " + exception.getMessage());
            return AresExitCode.USAGE_ERROR.value();
        } catch (AresDeploymentException exception) {
            error.accept(exception.getMessage());
            return AresExitCode.DEPLOYMENT_FAILED.value();
        }
    }

    private int deployLambdaProject(Path projectPath) {
        try {
            DeploymentOutcome outcome = deploymentService.deploy(projectPath);
            output.accept(outcome.summary());
            output.accept("Runtime: " + outcome.runtime());
            output.accept("Endpoint: " + outcome.endpoint());
            return AresExitCode.SUCCESS.value();
        } catch (AresConfigurationException exception) {
            error.accept(exception.getMessage());
            return AresExitCode.USAGE_ERROR.value();
        } catch (AresBuildException exception) {
            error.accept(exception.getMessage());
            return AresExitCode.BUILD_FAILED.value();
        } catch (AresDeploymentException exception) {
            error.accept(exception.getMessage());
            return AresExitCode.DEPLOYMENT_FAILED.value();
        }
    }

    private static Path assemblyRoot(Path requested) {
        Path path = requested.toAbsolutePath().normalize();
        boolean descriptor = Files.isRegularFile(path.resolve("ares.yaml"));
        Path direct = Files.isRegularFile(path.resolve("manifest.json")) ? path : null;
        Path nested = Files.isRegularFile(path.resolve("cdk.out/manifest.json")) ? path.resolve("cdk.out") : null;
        if (descriptor && (direct != null || nested != null)) {
            throw new IllegalArgumentException("Path '" + requested
                    + "' contains both ares.yaml and a Cloud Assembly; pass one deployment target explicitly");
        }
        return direct != null ? direct : nested;
    }

    private int invoke(List<String> arguments) {
        String functionName = arguments.get(1);
        try {
            InvocationOptions options = invocationOptions(arguments);
            LocalLambdaClient client =
                    options.endpoint() == null ? lambdaClient : new LocalLambdaClient(options.endpoint());
            LambdaInvocationResult result = client.invokeFunction(functionName, options.payload());
            output.accept(new String(result.payload(), StandardCharsets.UTF_8));
            if (result.functionError().isPresent()) {
                error.accept("Lambda function '" + functionName + "' returned function error '"
                        + result.functionError().orElseThrow()
                        + "'");
                return AresExitCode.FUNCTION_ERROR.value();
            }
            return AresExitCode.SUCCESS.value();
        } catch (IllegalArgumentException exception) {
            error.accept("Invalid invocation arguments for function '" + functionName + "': " + exception.getMessage());
            return AresExitCode.USAGE_ERROR.value();
        } catch (IOException exception) {
            error.accept("Could not read invocation event for function '" + functionName
                    + "'; check that the event path is readable: " + exception.getMessage());
            return AresExitCode.USAGE_ERROR.value();
        } catch (LambdaClientException exception) {
            error.accept("Could not invoke Lambda function '" + functionName + "': " + exception.getMessage());
            return isInfrastructureFailure(exception)
                    ? AresExitCode.INFRASTRUCTURE_UNAVAILABLE.value()
                    : AresExitCode.INTERNAL_ERROR.value();
        } catch (RuntimeException exception) {
            error.accept("Could not invoke Lambda function '" + functionName + "': " + exception.getMessage());
            return AresExitCode.INTERNAL_ERROR.value();
        }
    }

    private static InvocationOptions invocationOptions(List<String> arguments) throws IOException {
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        URI endpoint = null;
        boolean payloadProvided = false;
        for (int index = 2; index < arguments.size(); index++) {
            String option = arguments.get(index);
            if ("--event".equals(option)) {
                ensurePayloadNotProvided(payloadProvided);
                payload = Files.readAllBytes(Path.of(optionValue(arguments, index, "--event")));
                payloadProvided = true;
                index++;
            } else if ("--payload".equals(option)) {
                ensurePayloadNotProvided(payloadProvided);
                payload = optionValue(arguments, index, "--payload").getBytes(StandardCharsets.UTF_8);
                payloadProvided = true;
                index++;
            } else if ("--endpoint".equals(option)) {
                endpoint = URI.create(optionValue(arguments, index, "--endpoint"));
                index++;
            } else {
                throw new IllegalArgumentException("unsupported option '" + option + "'");
            }
        }
        return new InvocationOptions(payload, endpoint);
    }

    private static void ensurePayloadNotProvided(boolean payloadProvided) {
        if (payloadProvided) {
            throw new IllegalArgumentException("provide exactly one of --event or --payload");
        }
    }

    private static String optionValue(List<String> arguments, int optionIndex, String option) {
        if (optionIndex + 1 >= arguments.size()) {
            throw new IllegalArgumentException(
                    "--endpoint".equals(option)
                            ? "--endpoint requires an HTTP URL"
                            : "provide exactly one of --event or --payload");
        }
        return arguments.get(optionIndex + 1);
    }

    private static boolean isInfrastructureFailure(LambdaClientException exception) {
        return exception.statusCode() == 0
                || exception.statusCode() >= 500
                || exception.errorCode().equals("ServiceException")
                || exception.errorCode().equals("InternalFailure");
    }

    private record InvocationOptions(byte[] payload, URI endpoint) {}

    /** Starts the process entry point and preserves non-zero exit codes for scripts. */
    public static void main(String[] arguments) {
        int exitCode = new AresCli().execute(arguments);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
