package io.github.aresprojects.local.cli.builder;

/** Contains the validated size and digest of a generated ZIP artifact. */
record ArtifactMetadata(long sizeBytes, String sha256) {}
