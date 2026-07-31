package io.github.aresprojects.local.cli.testing;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Drives the installed {@code ares} command against a local runtime for end-to-end tests. */
public final class AresCli implements AutoCloseable {
    private static final URI HEALTH_ENDPOINT = URI.create("http://127.0.0.1:4566/_ares/health");
    private static final Path PROJECT_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final String ENDPOINT = "http://127.0.0.1:4566";

    private final Path executable;
    private final Path workingDirectory;
    private final Path runtimeLog;
    private Process runtime;

    public AresCli() throws IOException {
        executable = PROJECT_ROOT.resolve("ares-aws-local-cli/build/install/ares/bin/ares");
        if (!Files.isExecutable(executable)) {
            throw new IOException("Installed CLI not found or not executable: " + executable
                    + "; run :ares-aws-local-cli:installDist first");
        }
        workingDirectory = Files.createTempDirectory("ares-cli-e2e-");
        runtimeLog = workingDirectory.resolve("runtime.log");
    }

    /** Starts the local runtime and waits until its health endpoint responds successfully. */
    public void start() throws Exception {
        if (runtime != null) {
            throw new IllegalStateException("The local CLI runtime is already running");
        }
        runtime = process(List.of("local", "start"), Map.of("ARES_AWS_LOCAL_PORT", "4566"), runtimeLog);
        awaitHealth();
    }

    /** Builds a Lambda project with the installed CLI. */
    public ProcessResult build(Path function) throws Exception {
        return run(List.of("build", absolute(function)), Map.of("GRADLE_USER_HOME", gradleHome()));
    }

    /** Deploys a Lambda project to the local runtime. */
    public ProcessResult deploy(Path function) throws Exception {
        return run(List.of("deploy", absolute(function)), Map.of("ARES_AWS_LOCAL_ENDPOINT", ENDPOINT));
    }

    /** Invokes a deployed function with an event file. */
    public ProcessResult invoke(String functionName, Path event) throws Exception {
        return run(
                List.of("invoke", requireNonNull(functionName, "functionName"), "--event", absolute(event)),
                Map.of("ARES_AWS_LOCAL_ENDPOINT", ENDPOINT));
    }

    /** Returns captured runtime output for failures that need server-side diagnostics. */
    public String diagnostics() throws IOException {
        return Files.exists(runtimeLog) ? Files.readString(runtimeLog) : "";
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        try {
            stopRuntime();
        } catch (Exception exception) {
            failure = exception;
        }
        try {
            assertNoOwnedContainers();
        } catch (Exception exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        Files.deleteIfExists(runtimeLog);
        Files.deleteIfExists(workingDirectory);
        if (failure != null) {
            throw failure;
        }
    }

    private ProcessResult run(List<String> arguments, Map<String, String> environment) throws Exception {
        Process process = process(arguments, environment, null);
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("CLI command did not finish within two minutes: " + arguments);
        }
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private Process process(List<String> arguments, Map<String, String> environment, Path outputFile)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(executable.toString());
        builder.command().addAll(arguments);
        builder.directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        if (outputFile != null) {
            builder.redirectErrorStream(true);
            builder.redirectOutput(outputFile.toFile());
        }
        return builder.start();
    }

    private void awaitHealth() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(HEALTH_ENDPOINT)
                .timeout(Duration.ofMillis(250))
                .GET()
                .build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        IOException failure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return;
                }
            } catch (IOException exception) {
                failure = exception;
            }
            Thread.sleep(50);
        }
        throw new IOException("Timed out waiting for the local runtime; diagnostics: " + diagnostics(), failure);
    }

    private void stopRuntime() throws Exception {
        if (runtime == null) {
            return;
        }
        runtime.destroy();
        if (!runtime.waitFor(10, TimeUnit.SECONDS)) {
            runtime.destroyForcibly();
            if (!runtime.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("Local runtime did not stop within ten seconds");
            }
        }
        runtime = null;
    }

    private void assertNoOwnedContainers() throws Exception {
        Process result = new ProcessBuilder(
                        "docker", "ps", "-a", "--filter", "label=ares.local/managed=true", "--quiet")
                .directory(PROJECT_ROOT.toFile())
                .start();
        String containers = new String(result.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!result.waitFor(30, TimeUnit.SECONDS)) {
            result.destroyForcibly();
            throw new IOException("docker ps did not finish within thirty seconds");
        }
        if (result.exitValue() != 0) {
            throw new IOException("docker ps failed with exit code " + result.exitValue());
        }
        if (!containers.isEmpty()) {
            throw new IOException("Owned Lambda containers remained: " + containers);
        }
    }

    private static String absolute(Path path) {
        return requireNonNull(path, "path").toAbsolutePath().toString();
    }

    private static String gradleHome() {
        return System.getenv().getOrDefault("GRADLE_USER_HOME", "/tmp/ares-gradle-home");
    }

    public record ProcessResult(int exitCode, String stdout, String stderr) {}
}
