package io.github.aresprojects.local.lambda.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aresprojects.local.lambda.LambdaArtifact;
import io.github.aresprojects.local.lambda.LambdaFunctionSnapshot;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Java21RuntimeProviderTest {
    private final Java21RuntimeProvider provider = new Java21RuntimeProvider();

    @Test
    void selectsPinnedImageForEachSupportedArchitecture() {
        assertEquals(Java21RuntimeProvider.ARM64_IMAGE, provider.image(function("arm64")));
        assertEquals(Java21RuntimeProvider.X86_64_IMAGE, provider.image(function("x86_64")));
    }

    @Test
    void rejectsUnsupportedRuntime() {
        LambdaFunctionSnapshot function = new LambdaFunctionSnapshot(
                "hello",
                "arn",
                "java17",
                "arm64",
                "Handler::handleRequest",
                "role",
                "",
                3,
                128,
                Map.of(),
                "rev",
                Instant.now(),
                artifact());

        assertThrows(DockerExecutionException.class, () -> provider.image(function));
    }

    @Test
    void registryUsesFirstProviderAndRejectsEmptyOrUnsupportedRegistries() {
        LambdaRuntimeProvider first = new LambdaRuntimeProvider() {
            @Override
            public boolean supports(LambdaFunctionSnapshot function) {
                return true;
            }

            @Override
            public String image(LambdaFunctionSnapshot function) {
                return "first";
            }
        };
        LambdaRuntimeProviderRegistry registry = LambdaRuntimeProviderRegistry.of(java.util.List.of(first, provider));

        assertEquals("first", registry.resolve(function("arm64")).image(function("arm64")));
        assertThrows(IllegalArgumentException.class, () -> LambdaRuntimeProviderRegistry.of(java.util.List.of()));
        assertTrue(assertThrows(
                        DockerExecutionException.class,
                        () -> LambdaRuntimeProviderRegistry.of(java.util.List.of(provider))
                                .resolve(new LambdaFunctionSnapshot(
                                        "hello",
                                        "arn",
                                        "java17",
                                        "arm64",
                                        "handler",
                                        "role",
                                        "",
                                        3,
                                        128,
                                        Map.of(),
                                        "rev",
                                        Instant.now(),
                                        artifact())))
                .getMessage()
                .contains("No local Lambda runtime provider"));
    }

    private static LambdaFunctionSnapshot function(String architecture) {
        return new LambdaFunctionSnapshot(
                "hello",
                "arn",
                "java21",
                architecture,
                "Handler::handleRequest",
                "role",
                "",
                3,
                128,
                Map.of(),
                "rev",
                Instant.now(),
                artifact());
    }

    private static LambdaArtifact artifact() {
        return new LambdaArtifact(Path.of("artifact.zip"), 1, "sha", "base64");
    }
}
