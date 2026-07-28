package io.github.aresprojects.local.cli.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aresprojects.local.cli.AresBuildException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentResultWriterTest {

    @Test
    void reportsAnUnwritableMetadataParent(@TempDir Path directory) throws Exception {
        Path parent = directory.resolve("metadata-parent");
        Files.writeString(parent, "not a directory");
        Path target = parent.resolve("deployment.json");
        DeploymentResult result = new DeploymentResult(
                1,
                "hello",
                "java21",
                "arm64",
                "example.HelloHandler",
                directory,
                directory.resolve("function.zip"),
                "sha256",
                42,
                List.of());

        AresBuildException exception =
                assertThrows(AresBuildException.class, () -> new DeploymentResultWriter().write(target, result));

        assertEquals(true, exception.getMessage().contains("Could not write deployment metadata"));
    }

    @Test
    void escapesMetadataValuesAndWritesAnEmptyEnvironmentList(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("build/ares/deployment.json");
        DeploymentResult result = new DeploymentResult(
                1,
                "hello\"function",
                "java21",
                "arm64",
                "example\\Handler",
                directory,
                directory.resolve("function.zip"),
                "sha256",
                42,
                List.of());

        new DeploymentResultWriter().write(target, result);
        String content = Files.readString(target);

        assertEquals(true, content.contains("hello\\\"function"));
        assertEquals(true, content.contains("example\\\\Handler"));
        assertEquals(true, content.contains("environmentVariableNames\": []"));
    }
}
