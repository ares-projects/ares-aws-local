package io.github.aresprojects.local.cli.builder;

import io.github.aresprojects.local.cli.AresBuildException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** Executes build commands with bounded diagnostic capture and no shell interpretation. */
final class DefaultBuildCommandRunner implements BuildCommandRunner {
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    @Override
    public BuildProcessResult run(List<String> command, Path workingDirectory) throws AresBuildException {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .start();
        } catch (IOException exception) {
            throw new AresBuildException(
                    "Could not start build command " + display(command) + " in '" + workingDirectory
                            + "'; verify the first argument exists and is executable",
                    exception);
        }

        OutputCapture standardOutput = new OutputCapture();
        OutputCapture standardError = new OutputCapture();
        Thread outputReader = Thread.startVirtualThread(() -> standardOutput.read(process.getInputStream()));
        Thread errorReader = Thread.startVirtualThread(() -> standardError.read(process.getErrorStream()));
        try {
            int exitCode = process.waitFor();
            outputReader.join();
            errorReader.join();
            return new BuildProcessResult(exitCode, standardOutput.text(), standardError.text());
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AresBuildException(
                    "Build command " + display(command) + " was interrupted; rerun the build", exception);
        }
    }

    private static String display(List<String> command) {
        return command.stream()
                .map(DefaultBuildCommandRunner::quote)
                .reduce((left, right) -> left + " " + right)
                .orElse("<empty>");
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final class OutputCapture {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean truncated;

        private void read(InputStream input) {
            byte[] buffer = new byte[8192];
            try (input) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = MAX_OUTPUT_BYTES - output.size();
                    if (remaining > 0) {
                        output.write(buffer, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        truncated = true;
                    }
                }
            } catch (IOException exception) {
                truncated = true;
            }
        }

        private String text() {
            String value = output.toString(StandardCharsets.UTF_8);
            return truncated ? value + "\n[build output truncated]" : value;
        }
    }
}
