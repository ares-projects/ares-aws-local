package io.github.aresprojects.local.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LambdaServiceTest {

    @Test
    void createsReadsUpdatesAndDeletesAFunction(@TempDir Path directory) {
        TemporaryLambdaArtifactStore artifacts = new TemporaryLambdaArtifactStore(directory.resolve("staging"));
        AtomicReference<String> invalidated = new AtomicReference<>();
        AtomicInteger invalidationCount = new AtomicInteger();
        LambdaService service = new LambdaService(
                new InMemoryLambdaFunctionStore(),
                artifacts,
                (function, revision) -> {
                    invalidated.set(function + ":" + revision);
                    invalidationCount.incrementAndGet();
                },
                Clock.fixed(Instant.parse("2026-07-27T00:00:00Z"), ZoneOffset.UTC),
                "us-east-1");

        LambdaFunctionSnapshot created = service.create(
                "hello",
                "java21",
                "arm64",
                "example.HelloHandler",
                "arn:aws:iam::000000000000:role/local",
                "",
                3,
                128,
                Map.of("GREETING", "Ares"),
                zip("handler.class", new byte[] {1, 2, 3}));

        assertEquals("hello", service.get("hello").functionName());
        assertEquals("Ares", created.environment().get("GREETING"));
        assertTrue(Files.exists(created.artifact().path()));

        LambdaFunctionSnapshot configured = service.updateConfiguration(
                "hello",
                new LambdaConfigurationUpdate(
                        Optional.of("updated"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(5),
                        Optional.of(256),
                        Optional.of(Map.of("MODE", "test"))));
        assertEquals("updated", configured.description());
        assertEquals(5, configured.timeoutSeconds());
        assertEquals(Map.of("MODE", "test"), configured.environment());
        assertFalse(configured.revisionId().equals(created.revisionId()));
        assertEquals(1, invalidationCount.get());
        assertEquals("updated", service.get("hello").description());

        LambdaFunctionSnapshot updated =
                service.updateCode("hello", zip("new-handler.class", new byte[] {4, 5, 6}), Optional.of("x86_64"));
        assertEquals("x86_64", updated.architecture());
        assertFalse(updated.revisionId().equals(configured.revisionId()));
        assertTrue(invalidated.get().startsWith("hello:"));

        Path activeArtifact = updated.artifact().path();
        service.delete("hello");
        assertFalse(Files.exists(activeArtifact));
        assertEquals(3, invalidationCount.get());
        assertThrows(LambdaServiceException.class, () -> service.get("hello"));
    }

    @Test
    void rejectsDuplicateNamesUnsupportedConfigurationAndInvalidUpdates(@TempDir Path directory) {
        LambdaService service = service(directory);
        byte[] artifact = zip("handler.class", new byte[] {1});
        service.create("hello", "java21", "arm64", "Handler", "role", "", 3, 128, Map.of(), artifact);

        LambdaServiceException duplicate = assertThrows(
                LambdaServiceException.class,
                () -> service.create("hello", "java21", "arm64", "Handler", "role", "", 3, 128, Map.of(), artifact));
        assertEquals("ResourceConflictException", duplicate.errorCode());

        LambdaServiceException unsupported = assertThrows(
                LambdaServiceException.class,
                () -> service.updateConfiguration(
                        "hello",
                        new LambdaConfigurationUpdate(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("nodejs"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty())));
        assertEquals("InvalidParameterValueException", unsupported.errorCode());

        assertThrows(
                LambdaServiceException.class,
                () -> service.updateConfiguration(
                        "hello",
                        new LambdaConfigurationUpdate(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of("mips"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty())));

        LambdaFunctionSnapshot before = service.get("hello");
        assertThrows(
                LambdaServiceException.class,
                () -> service.updateCode("hello", "not-a-zip".getBytes(StandardCharsets.UTF_8), Optional.empty()));
        assertEquals(before.revisionId(), service.get("hello").revisionId());

        assertThrows(LambdaServiceException.class, () -> service.updateCode("hello", artifact, Optional.of("mips")));
        assertThrows(
                LambdaServiceException.class,
                () -> service.updateConfiguration(
                        "hello",
                        new LambdaConfigurationUpdate(
                                Optional.empty(),
                                Optional.of(""),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty())));
        assertThrows(
                LambdaServiceException.class,
                () -> service.updateConfiguration(
                        "hello",
                        new LambdaConfigurationUpdate(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(""),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty())));
        assertThrows(
                LambdaServiceException.class,
                () -> service.updateConfiguration(
                        "hello",
                        new LambdaConfigurationUpdate(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(0),
                                Optional.empty(),
                                Optional.empty())));
        assertThrows(
                LambdaServiceException.class,
                () -> service.updateConfiguration(
                        "hello",
                        new LambdaConfigurationUpdate(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(127),
                                Optional.empty())));
    }

    @Test
    void validatesNamesLimitsAndMissingResources(@TempDir Path directory) {
        LambdaService service = service(directory);
        byte[] artifact = zip("handler.class", new byte[] {1});

        assertThrows(
                LambdaServiceException.class,
                () -> service.create("bad name", "java21", "arm64", "Handler", "role", "", 3, 128, Map.of(), artifact));
        assertThrows(
                LambdaServiceException.class,
                () -> service.create("hello", "java21", "arm64", "Handler", "role", "", 0, 128, Map.of(), artifact));
        LambdaServiceException missing = assertThrows(LambdaServiceException.class, () -> service.delete("missing"));
        assertEquals("ResourceNotFoundException", missing.errorCode());
    }

    @Test
    void rejectsInvalidCreateFieldsAndEnvironmentValues(@TempDir Path directory) {
        byte[] artifact = zip("handler.class", new byte[] {1});
        assertCreateFailure(directory, null, "java21", "arm64", "Handler", "role", "", 3, 128, Map.of(), artifact);
        assertCreateFailure(directory, "hello", "nodejs", "arm64", "Handler", "role", "", 3, 128, Map.of(), artifact);
        assertCreateFailure(directory, "hello", "java21", "mips", "Handler", "role", "", 3, 128, Map.of(), artifact);
        assertCreateFailure(directory, "hello", "java21", "arm64", "", "role", "", 3, 128, Map.of(), artifact);
        assertCreateFailure(directory, "hello", "java21", "arm64", "Handler", "", "", 3, 128, Map.of(), artifact);
        assertCreateFailure(directory, "hello", "java21", "arm64", "Handler", "role", null, 3, 128, Map.of(), artifact);
        assertCreateFailure(directory, "hello", "java21", "arm64", "Handler", "role", "", 901, 128, Map.of(), artifact);
        assertCreateFailure(directory, "hello", "java21", "arm64", "Handler", "role", "", 3, 127, Map.of(), artifact);
        Map<String, String> invalidEnvironment = new HashMap<>();
        invalidEnvironment.put("A", null);
        assertCreateFailure(
                directory, "hello", "java21", "arm64", "Handler", "role", "", 3, 128, invalidEnvironment, artifact);
        Map<String, String> blankEnvironmentName = new HashMap<>();
        blankEnvironmentName.put("", "value");
        assertCreateFailure(
                directory, "hello", "java21", "arm64", "Handler", "role", "", 3, 128, blankEnvironmentName, artifact);
        assertCreateFailure(directory, "hello", "java21", "arm64", null, "role", "", 3, 128, Map.of(), artifact);
        assertCreateFailure(directory, "hello", "java21", "arm64", "Handler", null, "", 3, 128, Map.of(), artifact);
        assertCreateFailure(
                directory, "hello", "java21", "arm64", "Handler", "role", "", 3, 10_241, Map.of(), artifact);
        assertEquals(
                Map.of(),
                service(directory)
                        .create("hello", "java21", "arm64", "Handler", "role", "", 3, 128, null, artifact)
                        .environment());
    }

    @Test
    void createsAndClosesTheDefaultService() {
        try (LambdaService service = new LambdaService()) {
            assertThrows(LambdaServiceException.class, () -> service.get("missing"));
        }
    }

    @Test
    void rejectsUnsafeZipEntriesWithoutWritingOutsideStaging(@TempDir Path directory) {
        TemporaryLambdaArtifactStore artifacts = new TemporaryLambdaArtifactStore(directory.resolve("staging"));
        Path outside = directory.resolve("escaped.txt");
        assertThrows(LambdaServiceException.class, () -> artifacts.stage(zip("../escaped.txt", new byte[] {1})));
        assertFalse(Files.exists(outside));
    }

    @Test
    void cleansStagedArtifactsWhenMetadataMutationFails(@TempDir Path directory) {
        InMemoryLambdaFunctionStore delegate = new InMemoryLambdaFunctionStore();
        FailingReplaceStore store = new FailingReplaceStore(delegate);
        TrackingArtifactStore artifacts = new TrackingArtifactStore(directory.resolve("staging"));
        LambdaService service =
                new LambdaService(store, artifacts, (function, revision) -> {}, Clock.systemUTC(), "us-east-1");
        byte[] artifact = zip("handler.class", new byte[] {1});
        service.create("hello", "java21", "arm64", "Handler", "role", "", 3, 128, Map.of(), artifact);

        assertThrows(
                LambdaServiceException.class,
                () -> service.create("hello", "java21", "arm64", "Handler", "role", "", 3, 128, Map.of(), artifact));
        assertEquals(1, artifacts.deleted);

        store.failReplace = true;
        LambdaFunctionSnapshot before = service.get("hello");
        assertThrows(LambdaServiceException.class, () -> service.updateCode("hello", artifact, Optional.empty()));
        assertEquals(before.revisionId(), service.get("hello").revisionId());
        assertEquals(2, artifacts.deleted);

        store.failReplace = false;
        service.updateCode("hello", artifact, Optional.empty());
        assertEquals(3, artifacts.deleted);
    }

    @Test
    void rejectsBlankLookupNames(@TempDir Path directory) {
        assertThrows(IllegalArgumentException.class, () -> service(directory).get(""));
    }

    @Test
    void acceptsDocumentedLimitBoundaries(@TempDir Path directory) {
        byte[] artifact = zip("handler.class", new byte[] {1});
        for (int index = 0; index < 4; index++) {
            LambdaService service = service(directory.resolve("case-" + index));
            assertEquals(
                    "hello",
                    service.create(
                                    "hello",
                                    "java21",
                                    "arm64",
                                    "Handler",
                                    "role",
                                    "",
                                    index < 2 ? (index == 0 ? 1 : 900) : 3,
                                    index < 2 ? 128 : (index == 2 ? 128 : 10_240),
                                    Map.of(),
                                    artifact)
                            .functionName());
        }
    }

    private static LambdaService service(Path directory) {
        return new LambdaService(
                new InMemoryLambdaFunctionStore(),
                new TemporaryLambdaArtifactStore(directory.resolve("staging")),
                (function, revision) -> {},
                Clock.systemUTC(),
                "us-east-1");
    }

    private static void assertCreateFailure(
            Path directory,
            String functionName,
            String runtime,
            String architecture,
            String handler,
            String role,
            String description,
            int timeout,
            int memory,
            Map<String, String> environment,
            byte[] artifact) {
        assertThrows(
                LambdaServiceException.class,
                () -> service(directory)
                        .create(
                                functionName,
                                runtime,
                                architecture,
                                handler,
                                role,
                                description,
                                timeout,
                                memory,
                                environment,
                                artifact));
    }

    private static final class TrackingArtifactStore implements LambdaArtifactStore {
        private final TemporaryLambdaArtifactStore delegate;
        private int deleted;

        private TrackingArtifactStore(Path root) {
            delegate = new TemporaryLambdaArtifactStore(root);
        }

        @Override
        public LambdaArtifact stage(byte[] zipBytes) {
            return delegate.stage(zipBytes);
        }

        @Override
        public void delete(LambdaArtifact artifact) {
            deleted++;
            delegate.delete(artifact);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class FailingReplaceStore implements LambdaFunctionStore {
        private final LambdaFunctionStore delegate;
        private boolean failReplace;

        private FailingReplaceStore(LambdaFunctionStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<LambdaFunctionSnapshot> find(String functionName) {
            return delegate.find(functionName);
        }

        @Override
        public void create(LambdaFunctionSnapshot function) {
            delegate.create(function);
        }

        @Override
        public void replace(LambdaFunctionSnapshot function) {
            if (failReplace) {
                throw new LambdaServiceException("StorageFailure", "test replacement failure");
            }
            delegate.replace(function);
        }

        @Override
        public Optional<LambdaFunctionSnapshot> remove(String functionName) {
            return delegate.remove(functionName);
        }
    }

    private static byte[] zip(String name, byte[] content) {
        try (var output = new java.io.ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(content);
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (Exception exception) {
            throw new AssertionError("Could not create ZIP fixture", exception);
        }
    }
}
