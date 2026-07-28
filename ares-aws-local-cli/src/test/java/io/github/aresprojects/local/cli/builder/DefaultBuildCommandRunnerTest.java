package io.github.aresprojects.local.cli.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aresprojects.local.cli.AresBuildException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultBuildCommandRunnerTest {
    private final DefaultBuildCommandRunner runner = new DefaultBuildCommandRunner();

    @Test
    void executesArgumentArrayInTheRequestedDirectory() throws Exception {
        BuildProcessResult result = runner.run(List.of("/bin/pwd"), Path.of("/tmp"));

        assertEquals(0, result.exitCode());
        assertEquals(
                Path.of("/tmp").toRealPath().toString(), result.standardOutput().trim());
    }

    @Test
    void capturesFailureDiagnosticsWithoutChangingTheExitCode() throws Exception {
        BuildProcessResult result = runner.run(List.of("/bin/sh", "-c", "printf failed >&2; exit 3"), Path.of("/tmp"));

        assertEquals(3, result.exitCode());
        assertTrue(result.standardError().contains("failed"));
    }

    @Test
    void reportsMissingBuildToolsWithTheCommandAndCorrection() {
        AresBuildException exception = assertThrows(
                AresBuildException.class,
                () -> runner.run(List.of("/missing/ares-build-tool", "arg with spaces"), Path.of("/tmp")));

        assertTrue(exception.getMessage().contains("verify the first argument exists"));
        assertTrue(exception.getMessage().contains("'/missing/ares-build-tool' 'arg with spaces'"));
    }

    @Test
    void boundsCapturedOutput() throws Exception {
        BuildProcessResult result = runner.run(List.of("/bin/sh", "-c", "head -c 1100000 /dev/zero"), Path.of("/tmp"));

        assertEquals(0, result.exitCode());
        assertTrue(result.standardOutput().contains("[build output truncated]"));
    }

    @Test
    void destroysInterruptedBuilds() {
        try {
            Thread.currentThread().interrupt();
            AresBuildException exception = assertThrows(
                    AresBuildException.class, () -> runner.run(List.of("/bin/sleep", "10"), Path.of("/tmp")));
            assertTrue(exception.getMessage().contains("interrupted"));
        } finally {
            Thread.interrupted();
        }
    }
}
