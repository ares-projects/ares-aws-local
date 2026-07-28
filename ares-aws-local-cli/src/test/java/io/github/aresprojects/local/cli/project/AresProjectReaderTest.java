package io.github.aresprojects.local.cli.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aresprojects.local.cli.AresConfigurationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AresProjectReaderTest {
    private final AresProjectReader reader = new AresProjectReader();

    @Test
    void readsTheSingleFunctionAndSortsEnvironmentNames(@TempDir Path directory) throws Exception {
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.HelloHandler
                    environment:
                      ZED: hidden
                      ALPHA: secret
                    build:
                      command: [./gradlew, lambdaZip]
                      artifact: build/distributions/hello.zip
                """);

        AresProject project = reader.read(directory);

        assertEquals("hello", project.functionName());
        assertEquals(
                Path.of("build/distributions/hello.zip"), project.directory().relativize(project.artifactPath()));
        assertEquals(java.util.List.of("ALPHA", "ZED"), project.environmentNames());
    }

    @Test
    void rejectsUnknownFields(@TempDir Path directory) throws Exception {
        write(directory, """
                schemaVersion: 1
                typo: true
                functions: {}
                """);

        AresConfigurationException exception =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, exception.getMessage().contains("Invalid YAML"));
    }

    @Test
    void rejectsMalformedYaml(@TempDir Path directory) throws Exception {
        write(directory, "schemaVersion: [");

        AresConfigurationException exception =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, exception.getMessage().contains("Invalid YAML"));
    }

    @Test
    void rejectsAnEmptyDocumentAndUnsupportedRuntime(@TempDir Path directory) throws Exception {
        write(directory, "null\n");
        AresConfigurationException empty = assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: node22
                    architecture: arm64
                    handler: example.Handler
                    build:
                      command: [./gradlew]
                      artifact: function.zip
                """);
        AresConfigurationException runtime =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, empty.getMessage().contains("document is empty"), empty.getMessage());
        assertEquals(true, runtime.getMessage().contains("unsupported runtime"), runtime.getMessage());
    }

    @Test
    void rejectsUnsupportedRuntimeAndMultipleFunctions(@TempDir Path directory) throws Exception {
        write(directory, """
                schemaVersion: 1
                functions:
                  one:
                    runtime: node22
                    architecture: arm64
                    handler: example.Handler
                    build:
                      command: [./gradlew, lambdaZip]
                      artifact: build/function.zip
                  two:
                    runtime: java21
                    architecture: arm64
                    handler: example.Handler
                    build:
                      command: [./gradlew, lambdaZip]
                      artifact: build/function.zip
                """);

        AresConfigurationException exception =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, exception.getMessage().contains("exactly one function"));
    }

    @Test
    void rejectsUnsupportedSchemaAndEmptyFunctions(@TempDir Path directory) throws Exception {
        write(directory, "schemaVersion: 2\nfunctions: {}\n");
        AresConfigurationException schema =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, "schemaVersion: 1\nfunctions: {}\n");
        AresConfigurationException functions =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, schema.getMessage().contains("schemaVersion must be 1"));
        assertEquals(true, functions.getMessage().contains("exactly one function"));
    }

    @Test
    void rejectsMissingFunctionSections(@TempDir Path directory) throws Exception {
        write(directory, "schemaVersion: 1\nfunctions:\n");
        AresConfigurationException missingFunctions =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, "schemaVersion: 1\nfunctions:\n  hello:\n");
        AresConfigurationException missingFunction =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, missingFunctions.getMessage().contains("functions must contain"));
        assertEquals(true, missingFunction.getMessage().contains("has no configuration"));
    }

    @Test
    void rejectsBlankFunctionNamesAndBlankCommandArguments(@TempDir Path directory) throws Exception {
        write(directory, """
                schemaVersion: 1
                functions:
                  "":
                    runtime: java21
                    architecture: arm64
                    handler: example.Handler
                    build:
                      command: [./gradlew]
                      artifact: function.zip
                """);
        AresConfigurationException blankName =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.Handler
                    build:
                      command: [""]
                      artifact: function.zip
                """);
        AresConfigurationException blankCommand =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, blankName.getMessage().contains("function names must not be blank"));
        assertEquals(true, blankCommand.getMessage().contains("blank or NUL build.command"));
    }

    @Test
    void rejectsInvalidArchitectureHandlerAndBuild(@TempDir Path directory) throws Exception {
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: sparc
                    handler: example.Handler
                    build:
                      command: [./gradlew]
                      artifact: function.zip
                """);
        AresConfigurationException architecture =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    build:
                      command: [./gradlew]
                      artifact: function.zip
                """);
        AresConfigurationException handler =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.Handler
                """);
        AresConfigurationException build = assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, architecture.getMessage().contains("arm64 or x86_64"));
        assertEquals(true, handler.getMessage().contains("non-blank handler"));
        assertEquals(true, build.getMessage().contains("build.command"));
    }

    @Test
    void rejectsInvalidCommandAndEnvironmentEntries(@TempDir Path directory) throws Exception {
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.Handler
                    build:
                      command: []
                      artifact: function.zip
                """);
        AresConfigurationException emptyCommand =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.Handler
                    environment:
                      AWS_REGION: forbidden
                    build:
                      command: [./gradlew]
                      artifact: function.zip
                """);
        AresConfigurationException reserved =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.Handler
                    environment:
                      PUBLIC:
                    build:
                      command: [./gradlew]
                      artifact: function.zip
                """);
        AresConfigurationException nullEnvironment =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.Handler
                    environment:
                      "": value
                    build:
                      command: [./gradlew]
                      artifact: function.zip
                """);
        AresConfigurationException blankEnvironment =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.Handler
                    environment:
                      " ": value
                    build:
                      command: [./gradlew]
                      artifact: function.zip
                """);
        AresConfigurationException whitespaceEnvironment =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, emptyCommand.getMessage().contains("non-empty build.command"));
        assertEquals(true, reserved.getMessage().contains("reserved environment"));
        assertEquals(true, nullEnvironment.getMessage().contains("invalid environment"));
        assertEquals(true, blankEnvironment.getMessage().contains("invalid environment"));
        assertEquals(true, whitespaceEnvironment.getMessage().contains("invalid environment"));
    }

    @Test
    void rejectsAbsoluteAndParentArtifactPaths(@TempDir Path directory) throws Exception {
        write(directory, validYaml("/outside.zip"));
        AresConfigurationException absolute =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, validYaml("build/../outside.zip"));
        AresConfigurationException parent =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        write(directory, validYaml(""));
        AresConfigurationException blank = assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, absolute.getMessage().contains("stay inside"));
        assertEquals(true, parent.getMessage().contains("stay inside"));
        assertEquals(true, blank.getMessage().contains("relative build.artifact"));
    }

    @Test
    void rejectsNulArtifactPaths(@TempDir Path directory) throws Exception {
        write(directory, validYaml("\0"));

        AresConfigurationException exception =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, exception.getMessage().contains("Invalid YAML"), exception.getMessage());
    }

    @Test
    void rejectsInvalidFunctionConfiguration(@TempDir Path directory) throws Exception {
        write(directory, """
                schemaVersion: 1
                functions:
                  hello:
                    runtime: java21
                    architecture: arm64
                    handler: example.HelloHandler
                    environment:
                      AWS_REGION: forbidden
                    build:
                      command: [./gradlew, lambdaZip]
                      artifact: ../outside.zip
                """);

        AresConfigurationException exception =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));

        assertEquals(true, exception.getMessage().contains("build.artifact"));
    }

    @Test
    void rejectsMissingDescriptorAndInvalidPath(@TempDir Path directory) {
        AresConfigurationException missing =
                assertThrows(AresConfigurationException.class, () -> reader.read(directory));
        AresConfigurationException nullPath = assertThrows(AresConfigurationException.class, () -> reader.read(null));

        assertEquals(true, missing.getMessage().contains("ares.yaml"));
        assertEquals(true, nullPath.getMessage().contains("Function path is required"));
    }

    private static void write(Path directory, String content) throws Exception {
        Files.writeString(directory.resolve("ares.yaml"), content);
    }

    private static String validYaml(String artifact) {
        return String.format(
                Locale.ROOT,
                "schemaVersion: 1%nfunctions:%n  hello:%n    runtime: java21%n    architecture: arm64%n"
                        + "    handler: example.Handler%n    build:%n      command: [./gradlew]%n      artifact: %s%n",
                artifact);
    }
}
