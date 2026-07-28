package io.github.aresprojects.local.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LambdaModelTest {

    @Test
    void validatesAndCopiesPublicModels() {
        LambdaArtifact artifact = new LambdaArtifact(Path.of("artifact.zip"), 1, "sha", "base64");
        LambdaFunctionSnapshot snapshot = new LambdaFunctionSnapshot(
                "hello",
                "arn",
                "java21",
                "arm64",
                "Handler",
                "role",
                "",
                3,
                128,
                Map.of("A", "B"),
                "revision",
                Instant.parse("2026-07-27T00:00:00Z"),
                artifact);
        LambdaConfigurationUpdate update = LambdaConfigurationUpdate.empty();

        assertEquals("artifact.zip", artifact.path().getFileName().toString());
        assertEquals("B", snapshot.environment().get("A"));
        assertEquals(Optional.empty(), update.runtime());
        assertThrows(
                IllegalArgumentException.class, () -> new LambdaArtifact(Path.of("artifact.zip"), 0, "sha", "base64"));
        assertThrows(
                IllegalArgumentException.class, () -> new LambdaArtifact(Path.of("artifact.zip"), 1, "", "base64"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LambdaFunctionSnapshot(
                        "hello",
                        "arn",
                        "java21",
                        "arm64",
                        "Handler",
                        "role",
                        "",
                        0,
                        128,
                        Map.of(),
                        "revision",
                        Instant.now(),
                        artifact));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LambdaFunctionSnapshot(
                        "",
                        "arn",
                        "java21",
                        "arm64",
                        "Handler",
                        "role",
                        "",
                        3,
                        128,
                        Map.of(),
                        "revision",
                        Instant.now(),
                        artifact));
        assertThrows(
                IllegalArgumentException.class,
                () -> new LambdaFunctionSnapshot(
                        "hello",
                        "arn",
                        "java21",
                        "arm64",
                        "Handler",
                        "role",
                        "",
                        3,
                        10_241,
                        Map.of(),
                        "revision",
                        Instant.now(),
                        artifact));
    }

    @Test
    void storesAndReplacesFunctionsThreadSafely() {
        InMemoryLambdaFunctionStore store = new InMemoryLambdaFunctionStore();
        LambdaFunctionSnapshot function = snapshot("hello", "one");
        store.create(function);
        assertEquals(function, store.find("hello").orElseThrow());
        assertThrows(LambdaServiceException.class, () -> store.create(function));

        LambdaFunctionSnapshot replacement = snapshot("hello", "two");
        store.replace(replacement);
        assertEquals("two", store.find("hello").orElseThrow().revisionId());
        assertThrows(LambdaServiceException.class, () -> store.replace(snapshot("missing", "three")));
        assertEquals(replacement, store.remove("hello").orElseThrow());
        assertEquals(Optional.empty(), store.remove("missing"));

        new NoOpLambdaExecutionBackend().invalidate("hello", "revision");
        LambdaServiceException exception = new LambdaServiceException("Code", "message");
        assertEquals("Code", exception.errorCode());
    }

    private static LambdaFunctionSnapshot snapshot(String name, String revision) {
        return new LambdaFunctionSnapshot(
                name,
                "arn",
                "java21",
                "arm64",
                "Handler",
                "role",
                "",
                3,
                128,
                Map.of(),
                revision,
                Instant.now(),
                new LambdaArtifact(Path.of(revision + ".zip"), 1, "sha", "base64"));
    }
}
