package io.github.aresprojects.local.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CloudAssemblyReaderTest {
    @Test
    void readsStackArtifactAndRejectsFilesOutsideAssembly(@org.junit.jupiter.api.io.TempDir Path directory)
            throws Exception {
        Files.writeString(directory.resolve("manifest.json"), """
                {"version":"36.0.0","artifacts":{"Stack":{"type":"aws:cloudformation:stack",
                "properties":{"templateFile":"template.json","stackName":"LocalStack"}}}}
                """);
        Files.writeString(directory.resolve("template.json"), "{\"Resources\":{}}");

        CloudAssembly assembly = new CloudAssemblyReader().read(directory);

        assertEquals("36.0.0", assembly.schemaVersion());
        assertEquals("LocalStack", assembly.stacks().getFirst().stackName());
        assertThrows(CloudFormationException.class, () -> {
            Files.writeString(directory.resolve("manifest.json"), """
                    {"version":"36.0.0","artifacts":{"Stack":{"type":"aws:cloudformation:stack",
                    "properties":{"templateFile":"../template.json"}}}}
                    """);
            new CloudAssemblyReader().read(directory);
        });
    }

    @Test
    void validatesManifestPresenceVersionAndArtifactShape(@org.junit.jupiter.api.io.TempDir Path directory)
            throws Exception {
        CloudAssemblyReader reader = new CloudAssemblyReader();
        assertThrows(CloudFormationException.class, () -> reader.read(directory));

        Files.writeString(directory.resolve("manifest.json"), "{\"version\":\"55.0.0\",\"artifacts\":{}}");
        assertThrows(CloudFormationException.class, () -> reader.read(directory));

        Files.writeString(directory.resolve("manifest.json"), "{\"version\":\"54.0.0\",\"artifacts\":{}}");
        assertTrue(reader.read(directory).stacks().isEmpty());

        Files.writeString(directory.resolve("manifest.json"), "{\"version\":\"36.0.0\",\"artifacts\":[]}");
        assertThrows(CloudFormationException.class, () -> reader.read(directory));

        Files.writeString(directory.resolve("manifest.json"), "{\"version\":\"36.0.0\",\"artifacts\":{\"Broken\":{}}}");
        CloudFormationException exception = assertThrows(CloudFormationException.class, () -> reader.read(directory));
        assertTrue(exception.getMessage().contains("type"));
    }

    @Test
    void ignoresNonStackArtifactsAndUsesArtifactIdWhenStackNameIsAbsent(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("manifest.json"), """
                {"version":"36.0.0","artifacts":{
                  "Asset":{"type":"aws:cdk:asset","properties":{"path":"asset.zip"}},
                  "Stack":{"type":"aws:cloudformation:stack","properties":{"templateFile":"template.json"}}}}
                """);
        Files.writeString(directory.resolve("template.json"), "{\"Resources\":{}}");

        CloudAssembly assembly = new CloudAssemblyReader().read(directory);

        assertEquals(1, assembly.stacks().size());
        assertEquals("Stack", assembly.stacks().getFirst().stackName());
    }

    @Test
    void rejectsMalformedJsonMissingFilesAndInvalidVersions(@org.junit.jupiter.api.io.TempDir Path directory)
            throws Exception {
        CloudAssemblyReader reader = new CloudAssemblyReader();
        Files.writeString(directory.resolve("manifest.json"), "not-json");
        assertThrows(CloudFormationException.class, () -> reader.read(directory));

        Files.writeString(directory.resolve("manifest.json"), "{\"version\":\"invalid\",\"artifacts\":{}}");
        assertThrows(CloudFormationException.class, () -> reader.read(directory));

        Files.writeString(directory.resolve("manifest.json"), """
                {"version":"36.0.0","artifacts":{"Broken":{"type":"aws:cloudformation:stack"}}}
                """);
        assertThrows(CloudFormationException.class, () -> reader.read(directory));

        Files.writeString(directory.resolve("manifest.json"), """
                {"version":"36.0.0","artifacts":{"Broken":{"type":"aws:cloudformation:stack",
                "properties":{"templateFile":"missing.json"}}}}
                """);
        assertThrows(CloudFormationException.class, () -> reader.read(directory));
        assertThrows(CloudFormationException.class, () -> reader.read(directory.resolve("manifest.json")));
    }

    @Test
    void preservesManifestDependenciesAndRejectsVersionWithoutMinor(@org.junit.jupiter.api.io.TempDir Path directory)
            throws Exception {
        Files.writeString(directory.resolve("manifest.json"), """
                {"version":"36.0.0","artifacts":{"Stack":{"type":"aws:cloudformation:stack",
                "dependencies":["Asset"],"properties":{"templateFile":"template.json"}}}}
                """);
        Files.writeString(directory.resolve("template.json"), "{}");

        CloudAssemblyArtifact stack =
                new CloudAssemblyReader().read(directory).stacks().getFirst();

        assertEquals(java.util.List.of("Asset"), stack.dependencies());
        Files.writeString(directory.resolve("manifest.json"), "{\"version\":\"36\",\"artifacts\":{}}");
        assertThrows(CloudFormationException.class, () -> new CloudAssemblyReader().read(directory));
    }
}
