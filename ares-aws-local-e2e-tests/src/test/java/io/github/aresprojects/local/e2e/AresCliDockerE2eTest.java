package io.github.aresprojects.local.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aresprojects.local.cli.testing.AresCli;
import io.github.aresprojects.local.cli.testing.AresCli.ProcessResult;
import io.github.aresprojects.local.cli.testing.AresCliExtension;
import io.github.aresprojects.local.cli.testing.Cli;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

@EnabledIfEnvironmentVariable(named = "ARES_RUN_DOCKER_E2E", matches = "true")
@ExtendWith(AresCliExtension.class)
class AresCliDockerE2eTest {
    @Cli
    private AresCli cli;

    @Test
    void installedCliBuildsDeploysAndInvokesThroughDockerRie() throws Exception {
        Path function = Path.of("../examples/hello-lambda").toAbsolutePath().normalize();

        cli.start();
        assertSuccess(cli.build(function));

        ProcessResult firstDeployment = cli.deploy(function);
        assertSuccess(firstDeployment);
        assertTrue(firstDeployment.stdout().contains("Created Lambda function 'hello'"), firstDeployment.stdout());

        ProcessResult unchangedDeployment = cli.deploy(function);
        assertSuccess(unchangedDeployment);
        assertTrue(
                unchangedDeployment.stdout().contains("Lambda function 'hello' is unchanged"),
                unchangedDeployment.stdout());

        ProcessResult invocation = cli.invoke("hello", function.resolve("event.json"));
        assertEquals(0, invocation.exitCode(), invocation.stdout() + invocation.stderr() + cli.diagnostics());
        assertEquals("{\"message\":\"Hello, Ares\"}", invocation.stdout().trim());
        assertTrue(invocation.stderr().isBlank(), invocation.stderr());
    }

    private static void assertSuccess(ProcessResult result) {
        assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
    }
}
