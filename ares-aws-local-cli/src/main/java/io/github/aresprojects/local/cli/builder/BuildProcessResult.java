package io.github.aresprojects.local.cli.builder;

/** Captures bounded output from a completed build process. */
record BuildProcessResult(int exitCode, String standardOutput, String standardError) {}
