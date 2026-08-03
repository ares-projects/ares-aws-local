package io.github.aresprojects.local.cloudformation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloudAssemblyAssetResolverTest {
    @Test
    void resolvesExactCdkAssetPathsAndS3ObjectKeys(@TempDir Path directory) throws Exception {
        Path asset = directory.resolve("asset.0123456789abcdef.zip");
        Files.writeString(asset, "asset");
        Files.createDirectories(directory.resolve("asset-directory"));
        Path nestedDirectory = directory.resolve("nested");
        Files.createDirectories(nestedDirectory);
        Path nested = nestedDirectory.resolve("asset.zip");
        Files.writeString(nested, "nested asset");
        CloudAssemblyAssetResolver resolver = new CloudAssemblyAssetResolver(directory);

        assertEquals(asset, resolver.resolve("asset.0123456789abcdef.zip").orElseThrow());
        assertEquals(asset, resolver.resolve("0123456789abcdef.zip").orElseThrow());
        assertEquals(asset, resolver.resolve("./asset.0123456789abcdef.zip").orElseThrow());
        assertEquals(nested, resolver.resolve("asset.zip").orElseThrow());
        assertTrue(resolver.resolve("asset-directory").isEmpty());
        assertTrue(resolver.resolve("missing.zip").isEmpty());
    }

    @Test
    void rejectsAmbiguousFileNamesAndBlankIdentifiers(@TempDir Path directory) throws Exception {
        Path first = directory.resolve("first").resolve("asset.zip");
        Path second = directory.resolve("second").resolve("asset.zip");
        Files.createDirectories(directory.resolve("first"));
        Files.createDirectories(directory.resolve("second"));
        Files.writeString(first, "first");
        Files.writeString(second, "second");

        CloudAssemblyAssetResolver resolver = new CloudAssemblyAssetResolver(directory);

        assertTrue(resolver.resolve("asset.zip").isEmpty());
        assertTrue(resolver.resolve(null).isEmpty());
        assertTrue(resolver.resolve(" ").isEmpty());
        assertTrue(resolver.resolve("/").isEmpty());
    }

    @Test
    void rejectsAssetRootsThatAreNotDirectories(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("asset.txt");
        Files.writeString(file, "asset");

        CloudFormationException exception =
                assertThrows(CloudFormationException.class, () -> new CloudAssemblyAssetResolver(file));
        assertTrue(exception.getMessage().contains("not a directory"));
    }
}
