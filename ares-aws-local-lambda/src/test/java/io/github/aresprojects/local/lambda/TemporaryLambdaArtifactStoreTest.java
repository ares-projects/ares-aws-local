package io.github.aresprojects.local.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporaryLambdaArtifactStoreTest {

    @Test
    void stagesValidZipAndCalculatesSha256(@TempDir Path directory) {
        try (TemporaryLambdaArtifactStore store = new TemporaryLambdaArtifactStore(directory.resolve("artifacts"))) {
            LambdaArtifact artifact = store.stage(zip("handler.class", new byte[] {1, 2, 3}));

            assertEquals(64, artifact.sha256().length());
            assertEquals(44, artifact.sha256Base64().length());
            assertEquals(true, artifact.path().startsWith(directory.resolve("artifacts")));
            store.delete(null);
            store.delete(artifact);
        }
    }

    @Test
    void rejectsEmptyOversizedAndTooManyEntryArchives(@TempDir Path directory) {
        Path root = directory.resolve("artifacts");
        try (TemporaryLambdaArtifactStore store = new TemporaryLambdaArtifactStore(root)) {
            assertThrows(LambdaServiceException.class, () -> store.stage(new byte[0]));
            assertThrows(LambdaServiceException.class, () -> store.stage(emptyZip()));
            assertThrows(
                    LambdaServiceException.class,
                    () -> store.stage(new byte[TemporaryLambdaArtifactStore.MAX_UPLOAD_BYTES + 1]));
            LambdaServiceException exactUpload = assertThrows(
                    LambdaServiceException.class,
                    () -> store.stage(new byte[TemporaryLambdaArtifactStore.MAX_UPLOAD_BYTES]));
            assertEquals("InvalidParameterValueException", exactUpload.errorCode());
            assertThrows(
                    LambdaServiceException.class,
                    () -> store.stage(manyEntries(TemporaryLambdaArtifactStore.MAX_ENTRIES + 1)));
            LambdaArtifact maximumEntries = store.stage(manyEntries(TemporaryLambdaArtifactStore.MAX_ENTRIES));
            store.delete(maximumEntries);
            assertThrows(
                    LambdaServiceException.class,
                    () -> store.stage("not a zip".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEmpty(root);
        }
    }

    @Test
    void rejectsAbsoluteAndDriveQualifiedZipEntries(@TempDir Path directory) {
        try (TemporaryLambdaArtifactStore store = new TemporaryLambdaArtifactStore(directory.resolve("artifacts"))) {
            assertThrows(LambdaServiceException.class, () -> store.stage(zip("/absolute.txt", new byte[] {1})));
            assertThrows(LambdaServiceException.class, () -> store.stage(zip("C:/absolute.txt", new byte[] {1})));
        }
    }

    @Test
    void createsAndCleansTheDefaultTemporaryStore() {
        try (TemporaryLambdaArtifactStore store = new TemporaryLambdaArtifactStore()) {
            LambdaArtifact artifact = store.stage(zip("handler.class", new byte[] {1}));
            store.delete(artifact);
        }
    }

    @Test
    void closesAndRemovesTheConfiguredArtifactDirectory(@TempDir Path directory) {
        Path root = directory.resolve("artifacts");
        try (TemporaryLambdaArtifactStore store = new TemporaryLambdaArtifactStore(root)) {
            store.stage(zip("handler.class", new byte[] {1}));
        }
        assertEquals(false, Files.exists(root));
    }

    @Test
    void reportsFilesystemFailuresWithActionableExceptions(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("not-a-directory");
        Files.writeString(file, "file");
        assertThrows(IllegalStateException.class, () -> new TemporaryLambdaArtifactStore(file));

        Path missing = directory.resolve("removed");
        TemporaryLambdaArtifactStore store = new TemporaryLambdaArtifactStore(missing);
        Files.delete(missing);
        assertThrows(java.io.UncheckedIOException.class, store::close);
    }

    @Test
    void validatesMultipleEntriesAndCleansFailedStaging(@TempDir Path directory) {
        Path root = directory.resolve("artifacts");
        try (TemporaryLambdaArtifactStore store = new TemporaryLambdaArtifactStore(root)) {
            LambdaArtifact artifact =
                    store.stage(zipWithDirectory("handler.class", new byte[] {1}, "resource.txt", new byte[] {2}));
            assertEquals(64, artifact.sha256().length());
            store.delete(artifact);
            assertThrows(LambdaServiceException.class, () -> store.stage(zip("../escape", new byte[] {1})));
            assertEmpty(root);
        }
    }

    private static byte[] zip(String name, byte[] content) {
        return zipEntries(new Object[] {name, content});
    }

    private static void assertEmpty(Path directory) {
        try (var entries = Files.list(directory)) {
            assertEquals(0, entries.count());
        } catch (Exception exception) {
            throw new AssertionError("Could not inspect staging directory", exception);
        }
    }

    private static byte[] emptyZip() {
        try (var output = new java.io.ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(output)) {}
            return output.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError("Could not create ZIP fixture", exception);
        }
    }

    private static byte[] manyEntries(int count) {
        try (var output = new java.io.ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(output)) {
                for (int index = 0; index < count; index++) {
                    zip.putNextEntry(new ZipEntry("entry-" + index));
                    zip.write(1);
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError("Could not create ZIP fixture", exception);
        }
    }

    private static byte[] zipEntries(Object[] values) {
        try (var output = new java.io.ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(output)) {
                for (int index = 0; index < values.length; index += 2) {
                    zip.putNextEntry(new ZipEntry((String) values[index]));
                    zip.write((byte[]) values[index + 1]);
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError("Could not create ZIP fixture", exception);
        }
    }

    private static byte[] zipWithDirectory(
            String firstName, byte[] firstContent, String secondName, byte[] secondContent) {
        try (var output = new java.io.ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry("lib/"));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(firstName));
                zip.write(firstContent);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry(secondName));
                zip.write(secondContent);
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError("Could not create ZIP fixture", exception);
        }
    }
}
