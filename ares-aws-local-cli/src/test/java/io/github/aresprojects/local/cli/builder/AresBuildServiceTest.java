package io.github.aresprojects.local.cli.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aresprojects.local.cli.AresBuildException;
import io.github.aresprojects.local.cli.project.AresProjectReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AresBuildServiceTest {

    @Test
    void writesDeterministicMetadataWithoutEnvironmentValues(@TempDir Path directory) throws Exception {
        writeDescriptor(directory);
        AtomicBoolean invoked = new AtomicBoolean();
        BuildCommandRunner runner = (command, workingDirectory) -> {
            invoked.set(true);
            assertEquals(List.of("build-tool", "package"), command);
            assertEquals(directory.toAbsolutePath().normalize(), workingDirectory);
            try {
                writeZip(workingDirectory.resolve("build/distributions/function.zip"));
            } catch (Exception exception) {
                throw new AresBuildException("Test fixture could not create the ZIP", exception);
            }
            return new BuildProcessResult(0, "built", "");
        };
        AresBuildService service = new AresBuildService(
                new AresProjectReader(), runner, new ArtifactInspector(), new DeploymentResultWriter());

        DeploymentResult result = service.build(directory);
        String first = Files.readString(directory.resolve("build/ares/deployment.json"));
        DeploymentResult secondResult = service.build(directory);
        String second = Files.readString(directory.resolve("build/ares/deployment.json"));

        assertEquals(true, invoked.get());
        assertEquals("hello", result.functionName());
        assertEquals(result.artifactSha256(), secondResult.artifactSha256());
        assertEquals(first, second);
        assertEquals(true, first.contains("PUBLIC"));
        assertEquals(false, first.contains("do-not-write"));
    }

    @Test
    void reportsNonZeroBuildExitAndDoesNotWriteMetadata(@TempDir Path directory) throws Exception {
        writeDescriptor(directory);
        AresBuildService service = new AresBuildService(
                new AresProjectReader(),
                (command, workingDirectory) -> new BuildProcessResult(7, "stdout", "compiler failed"),
                new ArtifactInspector(),
                new DeploymentResultWriter());

        AresBuildException exception = assertThrows(AresBuildException.class, () -> service.build(directory));

        assertEquals(true, exception.getMessage().contains("exit code 7"));
        assertEquals(true, exception.getMessage().contains("compiler failed"));
        assertEquals(false, Files.exists(directory.resolve("build/ares/deployment.json")));
    }

    @Test
    void reportsMissingArtifactAfterSuccessfulBuild(@TempDir Path directory) throws Exception {
        writeDescriptor(directory);
        AresBuildService service = new AresBuildService(
                new AresProjectReader(),
                (command, workingDirectory) -> new BuildProcessResult(0, "", ""),
                new ArtifactInspector(),
                new DeploymentResultWriter());

        AresBuildException exception = assertThrows(AresBuildException.class, () -> service.build(directory));

        assertEquals(true, exception.getMessage().contains("regular file"));
    }

    @Test
    void supportsProjectPathsContainingSpaces(@TempDir Path directory) throws Exception {
        Path projectDirectory = directory.resolve("project with spaces");
        Files.createDirectories(projectDirectory);
        writeDescriptor(projectDirectory);
        AresBuildService service = new AresBuildService(
                new AresProjectReader(),
                (command, workingDirectory) -> {
                    try {
                        writeZip(workingDirectory.resolve("build/distributions/function.zip"));
                    } catch (Exception exception) {
                        throw new AresBuildException("Test fixture could not create the ZIP", exception);
                    }
                    return new BuildProcessResult(0, "", "");
                },
                new ArtifactInspector(),
                new DeploymentResultWriter());

        DeploymentResult result = service.build(projectDirectory);

        assertEquals(projectDirectory.toAbsolutePath().normalize(), result.sourceDirectory());
        assertEquals(true, Files.exists(projectDirectory.resolve("build/ares/deployment.json")));
    }

    private static void writeDescriptor(Path directory) throws Exception {
        Files.writeString(directory.resolve("ares.yaml"), """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: x86_64
                    handler: example.HelloHandler
                    environment:
                      PUBLIC: do-not-write
                    build:
                      command: [build-tool, package]
                      artifact: build/distributions/function.zip
                """);
    }

    private static void writeZip(Path artifact) throws Exception {
        Files.createDirectories(Objects.requireNonNull(artifact.getParent(), "artifact parent"));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(artifact))) {
            zip.putNextEntry(new ZipEntry("example/HelloHandler.class"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
    }
}
