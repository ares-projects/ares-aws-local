package io.github.aresprojects.local.cloudformation;

import java.nio.file.Path;
import java.util.Optional;

/** Resolves local files referenced by a Cloud Assembly without contacting AWS. */
@FunctionalInterface
public interface AssetResolver {
    /** Returns a staged local asset path for an assembly asset identifier. */
    Optional<Path> resolve(String assetIdentifier);
}
