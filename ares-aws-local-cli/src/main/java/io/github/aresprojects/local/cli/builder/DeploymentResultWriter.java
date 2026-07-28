package io.github.aresprojects.local.cli.builder;

import io.github.aresprojects.local.cli.AresBuildException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Writes deployment metadata atomically so a failed build never leaves a partial result. */
final class DeploymentResultWriter {

    void write(Path target, DeploymentResult result) throws AresBuildException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(result, "result");
        Path parent = Objects.requireNonNull(target.getParent(), "target parent");
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "deployment-", ".tmp");
            try {
                Files.writeString(temporary, result.toJson(), StandardCharsets.UTF_8);
                move(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new AresBuildException(
                    "Could not write deployment metadata '" + target + "'; check the project directory is writable",
                    exception);
        }
    }

    private static void move(Path temporary, Path target) throws IOException {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
