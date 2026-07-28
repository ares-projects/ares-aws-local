package io.github.aresprojects.local.cli.builder;

import io.github.aresprojects.local.cli.AresBuildException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Validates a generated ZIP and computes its stable SHA-256 digest. */
final class ArtifactInspector {

    ArtifactMetadata inspect(Path artifact) throws AresBuildException {
        if (!Files.isRegularFile(artifact)) {
            throw invalid(artifact, "the build did not produce a regular file; check build.artifact");
        }
        if (!Files.isReadable(artifact)) {
            throw invalid(artifact, "the artifact is not readable; check file permissions");
        }
        long size;
        try {
            size = Files.size(artifact);
        } catch (IOException exception) {
            throw invalid(artifact, "the artifact size could not be read; check file permissions", exception);
        }
        if (size == 0) {
            throw invalid(artifact, "the artifact is empty; produce a non-empty Lambda ZIP");
        }
        validateZip(artifact);
        return new ArtifactMetadata(size, digest(artifact));
    }

    private static void validateZip(Path artifact) throws AresBuildException {
        try (ZipFile zip = new ZipFile(artifact.toFile())) {
            if (!hasEntries(zip, artifact)) {
                throw invalid(artifact, "the artifact is an empty ZIP; include the compiled function and dependencies");
            }
        } catch (ZipException exception) {
            throw invalid(artifact, "the artifact is not a readable ZIP; run the build command again", exception);
        } catch (IOException exception) {
            throw invalid(artifact, "the artifact ZIP could not be read; check file permissions", exception);
        }
    }

    private static boolean hasEntries(ZipFile zip, Path artifact) throws IOException, AresBuildException {
        Set<String> entries = new HashSet<>();
        boolean hasEntry = false;
        var iterator = zip.entries();
        while (iterator.hasMoreElements()) {
            hasEntry = true;
            validateEntry(iterator.nextElement(), entries, artifact);
        }
        return hasEntry;
    }

    private static void validateEntry(ZipEntry entry, Set<String> entries, Path artifact) throws AresBuildException {
        String name = entry.getName();
        if (name == null || name.isBlank() || name.contains("\0")) {
            throw invalid(artifact, "the ZIP contains an empty or NUL entry name");
        }
        if (isUnsafeEntry(name)) {
            throw invalid(artifact, "the ZIP contains an unsafe entry '" + name + "'");
        }
        String normalizedName = name.replace('\\', '/');
        if (!entries.add(normalizedName)) {
            throw invalid(artifact, "the ZIP contains duplicate entry '" + name + "'");
        }
    }

    private static boolean isUnsafeEntry(String name) {
        String normalized = name.replace('\\', '/');
        return normalized.startsWith("/")
                || normalized.startsWith("../")
                || normalized.contains("/../")
                || normalized.endsWith("/..")
                || normalized.matches("^[A-Za-z]:/.*");
    }

    private static String digest(Path artifact) throws AresBuildException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(artifact);
                    DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                digestInput.transferTo(java.io.OutputStream.nullOutputStream());
            }
            StringBuilder value = new StringBuilder();
            for (byte part : digest.digest()) {
                value.append(String.format("%02x", part));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AresBuildException("SHA-256 is unavailable; the Java runtime is unsupported", exception);
        } catch (IOException exception) {
            throw new AresBuildException(
                    "Could not read artifact '" + artifact + "' for SHA-256; check file permissions", exception);
        }
    }

    private static AresBuildException invalid(Path artifact, String message) {
        return new AresBuildException("Invalid Lambda artifact '" + artifact + "': " + message);
    }

    private static AresBuildException invalid(Path artifact, String message, Throwable cause) {
        return new AresBuildException("Invalid Lambda artifact '" + artifact + "': " + message, cause);
    }
}
