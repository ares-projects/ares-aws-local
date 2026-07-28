package io.github.aresprojects.local.cli.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.aresprojects.local.cli.AresBuildException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactInspectorTest {
    private final ArtifactInspector inspector = new ArtifactInspector();

    @Test
    void validatesZipAndCalculatesSha256(@TempDir Path directory) throws Exception {
        Path artifact = zip(directory, "handler/Handler.class", new byte[] {1, 2, 3});

        ArtifactMetadata metadata = inspector.inspect(artifact);

        assertEquals(Files.size(artifact), metadata.sizeBytes());
        assertEquals(64, metadata.sha256().length());
    }

    @Test
    void rejectsMissingEmptyAndNonZipArtifacts(@TempDir Path directory) throws Exception {
        AresBuildException missing =
                assertThrows(AresBuildException.class, () -> inspector.inspect(directory.resolve("missing.zip")));
        Path empty = directory.resolve("empty.zip");
        Files.createFile(empty);
        AresBuildException emptyFailure = assertThrows(AresBuildException.class, () -> inspector.inspect(empty));
        Path text = directory.resolve("text.zip");
        Files.writeString(text, "not a zip");
        AresBuildException zipFailure = assertThrows(AresBuildException.class, () -> inspector.inspect(text));

        assertEquals(true, missing.getMessage().contains("regular file"));
        assertEquals(true, emptyFailure.getMessage().contains("empty"));
        assertEquals(true, zipFailure.getMessage().contains("not a readable ZIP"));
    }

    @Test
    void rejectsUnreadableArtifacts(@TempDir Path directory) throws Exception {
        Path artifact = zip(directory, "handler.class", new byte[] {1});
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(artifact);
        try {
            Files.setPosixFilePermissions(artifact, Set.of());
            AresBuildException exception = assertThrows(AresBuildException.class, () -> inspector.inspect(artifact));

            assertEquals(true, exception.getMessage().contains("not readable"));
        } finally {
            Files.setPosixFilePermissions(artifact, original);
        }
    }

    @Test
    void rejectsUnsafeAndEmptyZipEntries(@TempDir Path directory) throws Exception {
        Path unsafe = zip(directory, "../handler.class", new byte[] {1});
        Path emptyZip = directory.resolve("empty-archive.zip");
        try (OutputStream output = Files.newOutputStream(emptyZip);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.finish();
        }

        AresBuildException unsafeFailure = assertThrows(AresBuildException.class, () -> inspector.inspect(unsafe));
        AresBuildException emptyFailure = assertThrows(AresBuildException.class, () -> inspector.inspect(emptyZip));

        assertEquals(true, unsafeFailure.getMessage().contains("unsafe entry"));
        assertEquals(true, emptyFailure.getMessage().contains("empty ZIP"));
    }

    @Test
    void rejectsAbsoluteWindowsAndDuplicateNormalizedEntries(@TempDir Path directory) throws Exception {
        Path absolute = zip(directory, "/handler.class", new byte[] {1});
        Path windows = zip(directory, "C:/handler.class", new byte[] {1});
        Path duplicate = directory.resolve("duplicate.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(duplicate))) {
            zip.putNextEntry(new ZipEntry("handler/class"));
            zip.write(new byte[] {1});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("handler\\class"));
            zip.write(new byte[] {2});
            zip.closeEntry();
        }

        AresBuildException absoluteFailure = assertThrows(AresBuildException.class, () -> inspector.inspect(absolute));
        AresBuildException windowsFailure = assertThrows(AresBuildException.class, () -> inspector.inspect(windows));
        AresBuildException duplicateFailure =
                assertThrows(AresBuildException.class, () -> inspector.inspect(duplicate));

        assertEquals(true, absoluteFailure.getMessage().contains("unsafe entry"));
        assertEquals(true, windowsFailure.getMessage().contains("unsafe entry"));
        assertEquals(true, duplicateFailure.getMessage().contains("duplicate entry"));
    }

    private static Path zip(Path directory, String name, byte[] content) throws Exception {
        Path artifact = directory.resolve(name.replace('/', '_').replace('.', '-') + ".zip");
        try (OutputStream output = Files.newOutputStream(artifact);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return artifact;
    }
}
