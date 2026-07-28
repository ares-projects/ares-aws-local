package io.github.aresprojects.local.cli;

import io.github.aresprojects.local.cli.builder.AresBuildService;
import io.github.aresprojects.local.cli.builder.DeploymentResult;
import io.github.aresprojects.local.cli.deploy.AresDeploymentService;
import io.github.aresprojects.local.cli.deploy.AresDeploymentService.DeploymentOutcome;
import io.github.aresprojects.local.runtime.LocalAwsRuntime;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Implements the CLI-first local Lambda workflow. */
public final class AresCli {
    private static final String USAGE =
            "Usage: ares local start | ares build <function-path> | ares deploy <function-path>";

    private final Consumer<String> output;
    private final Consumer<String> error;
    private final AresBuildService buildService;
    private final AresDeploymentService deploymentService;
    private final Runnable runtimeStarter;

    /** Creates a CLI using standard output, standard error, and production build boundaries. */
    public AresCli() {
        this(
                System.out::println,
                System.err::println,
                new AresBuildService(),
                new AresDeploymentService(),
                () -> LocalAwsRuntime.main(new String[0]));
    }

    AresCli(Consumer<String> output, Consumer<String> error, AresBuildService buildService) {
        this(
                output,
                error,
                buildService,
                new AresDeploymentService(
                        buildService, new io.github.aresprojects.local.cli.deploy.LocalLambdaClient()),
                () -> LocalAwsRuntime.main(new String[0]));
    }

    AresCli(Consumer<String> output, Consumer<String> error, AresBuildService buildService, Runnable runtimeStarter) {
        this(
                output,
                error,
                buildService,
                new AresDeploymentService(
                        buildService, new io.github.aresprojects.local.cli.deploy.LocalLambdaClient()),
                runtimeStarter);
    }

    AresCli(
            Consumer<String> output,
            Consumer<String> error,
            AresBuildService buildService,
            AresDeploymentService deploymentService,
            Runnable runtimeStarter) {
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
        this.buildService = Objects.requireNonNull(buildService, "buildService");
        this.deploymentService = Objects.requireNonNull(deploymentService, "deploymentService");
        this.runtimeStarter = Objects.requireNonNull(runtimeStarter, "runtimeStarter");
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
        if (args.size() == 2 && "deploy".equals(args.get(0))) {
            return deploy(args.get(1));
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

    private int deploy(String requestedPath) {
        Path projectPath;
        try {
            projectPath = Path.of(requestedPath);
        } catch (InvalidPathException exception) {
            error.accept("Invalid function path '" + requestedPath + "'; provide a valid project directory");
            return AresExitCode.USAGE_ERROR.value();
        }

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

    /** Starts the process entry point and preserves non-zero exit codes for scripts. */
    public static void main(String[] arguments) {
        int exitCode = new AresCli().execute(arguments);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }
}
