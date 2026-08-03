package io.github.aresprojects.local.cloudformation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves CDK asset paths from files copied into a local Cloud Assembly bundle. */
public final class CloudAssemblyAssetResolver implements AssetResolver {
    private final Path root;
    private final Map<String, Path> files;
    private final Map<String, List<Path>> filesByName;

    /** Indexes regular files beneath one Cloud Assembly root. */
    public CloudAssemblyAssetResolver(Path root) {
        this.root = requireDirectory(root);
        Map<String, Path> indexedFiles = new HashMap<>();
        try (var paths = Files.walk(this.root)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String relative = this.root
                        .relativize(path)
                        .toString()
                        .replace(path.getFileSystem().getSeparator(), "/");
                indexedFiles.put(relative, path);
            });
        } catch (IOException exception) {
            throw new CloudFormationException(
                    "Could not index Cloud Assembly assets beneath '" + this.root + "': " + exception.getMessage(),
                    exception);
        }
        files = Map.copyOf(indexedFiles);
        Map<String, List<Path>> grouped = new HashMap<>();
        files.values()
                .forEach(path -> grouped.computeIfAbsent(path.getFileName().toString(), ignored -> new ArrayList<>())
                        .add(path));
        grouped.replaceAll((name, paths) -> List.copyOf(paths));
        filesByName = Map.copyOf(grouped);
    }

    /** Resolves an exact relative path, a CDK asset path, or a unique file name. */
    @Override
    public Optional<Path> resolve(String assetIdentifier) {
        if (assetIdentifier == null || assetIdentifier.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(assetIdentifier);
        Path exact = resolveExact(normalized);
        if (exact != null) {
            return Optional.of(exact);
        }
        return resolveUniqueFileName(normalized);
    }

    private Path resolveExact(String normalized) {
        Path exact = files.get(normalized);
        if (exact != null) {
            return exact;
        }
        String assetPath = normalized.startsWith("asset.") ? normalized : "asset." + normalized;
        return files.get(assetPath);
    }

    private Optional<Path> resolveUniqueFileName(String normalized) {
        Path path = Path.of(normalized);
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return Optional.empty();
        }
        String fileName = fileNamePath.toString();
        List<Path> candidates = filesByName.get(fileName);
        if (candidates == null && fileName.endsWith(".zip")) {
            candidates = filesByName.get("asset." + fileName);
        }
        return candidates == null || candidates.size() != 1 ? Optional.empty() : Optional.of(candidates.getFirst());
    }

    private static Path requireDirectory(Path value) {
        Path directory = Objects.requireNonNull(value, "root").toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw new CloudFormationException("Cloud Assembly asset root '" + directory + "' is not a directory");
        }
        return directory;
    }

    private static String normalize(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
