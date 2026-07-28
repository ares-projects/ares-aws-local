package io.github.aresprojects.local.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aresprojects.local.cli.builder.AresBuildService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AresCliTest {

    @Test
    void rejectsUnknownCommandsWithUsageExitCode() {
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService());
        AresCli defaultCli = new AresCli();

        int exitCode = cli.execute("deploy", "function");

        assertEquals(AresExitCode.USAGE_ERROR.value(), exitCode);
        assertEquals(true, errors.getFirst().contains("Usage"));
        assertEquals(AresExitCode.USAGE_ERROR.value(), defaultCli.execute("unknown"));
    }

    @Test
    void mapsInvalidProjectPathToUsageError() {
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService());

        int exitCode = cli.execute("build", "does-not-exist");

        assertEquals(AresExitCode.USAGE_ERROR.value(), exitCode);
        assertEquals(true, errors.getFirst().contains("directory"));
    }

    @Test
    void startsAndReportsRuntimeFailuresThroughTheConfiguredLifecycle(@TempDir Path directory) {
        List<String> errors = new ArrayList<>();
        AresCli running = new AresCli(message -> {}, errors::add, new AresBuildService(), () -> {});
        AresCli failing = new AresCli(message -> {}, errors::add, new AresBuildService(), () -> {
            throw new IllegalStateException("port 4566 is unavailable");
        });

        assertEquals(AresExitCode.SUCCESS.value(), running.execute("local", "start"));
        assertEquals(AresExitCode.RUNTIME_UNAVAILABLE.value(), failing.execute("local", "start"));
        assertEquals(true, errors.getFirst().contains("port 4566 is unavailable"));
    }

    @Test
    void buildsAndPrintsTheDeploymentSummary(@TempDir Path directory) throws Exception {
        writeProject(directory, true);
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(output::add, errors::add, new AresBuildService());

        int exitCode = cli.execute("build", directory.toString());

        assertEquals(AresExitCode.SUCCESS.value(), exitCode, errors.toString());
        assertEquals(3, output.size());
        assertEquals(true, output.getFirst().contains("Built Lambda function 'hello'"));

        AresCli.main(new String[] {"build", directory.toString()});
    }

    @Test
    void mapsInvalidArtifactToBuildFailure(@TempDir Path directory) throws Exception {
        writeProject(directory, false);
        List<String> errors = new ArrayList<>();
        AresCli cli = new AresCli(message -> {}, errors::add, new AresBuildService());

        int exitCode = cli.execute("build", directory.toString());

        assertEquals(AresExitCode.BUILD_FAILED.value(), exitCode, errors.toString());
        assertEquals(true, errors.getFirst().contains("not a readable ZIP"));
    }

    @Test
    void handlesNullAndInvalidArgumentsAndConstructorDependencies() {
        AresCli cli = new AresCli(message -> {}, message -> {}, new AresBuildService());
        List<String> errors = new ArrayList<>();
        AresCli invalidPathCli = new AresCli(message -> {}, errors::add, new AresBuildService());

        assertEquals(AresExitCode.USAGE_ERROR.value(), cli.execute((String[]) null));
        assertEquals(2, invalidPathCli.execute("build", "bad\0path"));
        assertEquals(true, errors.getFirst().contains("Invalid function path"));
        assertThrows(NullPointerException.class, () -> new AresCli(null, message -> {}, new AresBuildService()));
        assertThrows(NullPointerException.class, () -> new AresCli(message -> {}, null, new AresBuildService()));
        assertThrows(NullPointerException.class, () -> new AresCli(message -> {}, message -> {}, null));
        assertEquals(2, AresExitCode.USAGE_ERROR.value());
        assertEquals(3, AresExitCode.RUNTIME_UNAVAILABLE.value());
        assertEquals(4, AresExitCode.BUILD_FAILED.value());
    }

    private static void writeProject(Path directory, boolean validZip) throws Exception {
        Files.writeString(directory.resolve("ares.yaml"), """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.HelloHandler
                    build:
                      command: [/usr/bin/true]
                      artifact: build/function.zip
                """);
        Files.createDirectories(directory.resolve("build"));
        if (validZip) {
            try (ZipOutputStream zip =
                    new ZipOutputStream(Files.newOutputStream(directory.resolve("build/function.zip")))) {
                zip.putNextEntry(new ZipEntry("example/HelloHandler.class"));
                zip.write(new byte[] {1});
                zip.closeEntry();
            }
        } else {
            Files.writeString(directory.resolve("build/function.zip"), "not a zip");
        }
    }
}
