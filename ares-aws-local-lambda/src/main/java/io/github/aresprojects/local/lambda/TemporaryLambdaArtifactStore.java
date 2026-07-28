package io.github.aresprojects.local.lambda;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Provides bounded, process-local storage for Lambda ZIP uploads. */
public final class TemporaryLambdaArtifactStore implements LambdaArtifactStore {
    static final int MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
    static final long MAX_EXPANDED_BYTES = 250L * 1024 * 1024;
    static final int MAX_ENTRIES = 10_000;

    private final Path root;

    /** Creates an isolated temporary artifact directory. */
    public TemporaryLambdaArtifactStore() {
        try {
            root = Files.createTempDirectory("ares-lambda-artifacts-");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the temporary Lambda artifact directory", exception);
        }
    }

    /** Uses a caller-owned directory, primarily for deterministic storage tests. */
    public TemporaryLambdaArtifactStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Could not create Lambda artifact directory '" + this.root + "'", exception);
        }
    }

    @Override
    public synchronized LambdaArtifact stage(byte[] zipBytes) {
        Objects.requireNonNull(zipBytes, "zipBytes");
        if (zipBytes.length == 0) {
            throw new LambdaServiceException("InvalidParameterValueException", "Lambda ZIP artifact must not be empty");
        }
        if (zipBytes.length > MAX_UPLOAD_BYTES) {
            throw new LambdaServiceException(
                    "RequestTooLargeException", "Lambda ZIP artifact exceeds the 50 MiB local upload limit");
        }
        Path staged = null;
        try {
            staged = Files.createTempFile(root, "revision-", ".zip");
            Files.write(staged, zipBytes);
            validateZip(staged);
            byte[] digest = digest(zipBytes);
            return new LambdaArtifact(
                    staged, zipBytes.length, hex(digest), Base64.getEncoder().encodeToString(digest));
        } catch (LambdaServiceException exception) {
            deletePath(staged);
            throw exception;
        } catch (IOException exception) {
            deletePath(staged);
            throw new LambdaServiceException(
                    "InvalidParameterValueException",
                    "Lambda ZIP artifact could not be staged: " + exception.getMessage());
        }
    }

    @Override
    public synchronized void delete(LambdaArtifact artifact) {
        if (artifact != null) {
            deletePath(artifact.path());
        }
    }

    @Override
    public synchronized void close() {
        try (var paths = Files.walk(root)) {
            var orderedPaths =
                    paths.sorted((left, right) -> right.compareTo(left)).toList();
            for (Path path : orderedPaths) {
                deletePath(path);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not clean Lambda artifact directory '" + root + "'", exception);
        }
    }

    private static void validateZip(Path artifact) throws IOException {
        long expandedBytes = 0;
        int entryCount = 0;
        try (ZipFile zip = new ZipFile(artifact.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryCount++;
                validateEntryCount(entryCount);
                expandedBytes = addExpandedBytes(expandedBytes, validateEntry(entry));
                validateExpandedBytes(expandedBytes);
            }
        }
        if (entryCount == 0) {
            throw invalid("Lambda ZIP must contain at least one entry");
        }
    }

    private static void validateEntryCount(int entryCount) {
        if (entryCount > MAX_ENTRIES) {
            throw invalid("Lambda ZIP contains more than " + MAX_ENTRIES + " entries");
        }
    }

    private static long validateEntry(ZipEntry entry) {
        validateEntryName(entry.getName());
        if (entry.isDirectory()) {
            return 0;
        }
        long entrySize = entry.getSize();
        if (entrySize < 0) {
            throw invalid("Lambda ZIP entry '" + entry.getName() + "' has no declared size");
        }
        return entrySize;
    }

    private static long addExpandedBytes(long expandedBytes, long entrySize) {
        try {
            return Math.addExact(expandedBytes, entrySize);
        } catch (ArithmeticException exception) {
            throw invalid("Lambda ZIP expanded size exceeds the local staging limit");
        }
    }

    private static void validateExpandedBytes(long expandedBytes) {
        if (expandedBytes > MAX_EXPANDED_BYTES) {
            throw invalid("Lambda ZIP expands beyond the 250 MiB local staging limit");
        }
    }

    private static void validateEntryName(String name) {
        String portable = name.replace('\\', '/');
        if (portable.isBlank()
                || portable.startsWith("/")
                || portable.matches("^[A-Za-z]:/.*")
                || portable.indexOf('\0') >= 0) {
            throw invalid("Lambda ZIP contains an unsafe entry path '" + name + "'");
        }
        Path normalized = Path.of(portable).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw invalid("Lambda ZIP contains an unsafe entry path '" + name + "'");
        }
    }

    private static LambdaServiceException invalid(String message) {
        return new LambdaServiceException("InvalidParameterValueException", message);
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for Lambda artifact identity", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static void deletePath(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not delete Lambda artifact '" + path + "'", exception);
        }
    }
}
