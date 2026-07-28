package io.github.aresprojects.local.cli.project;

import io.github.aresprojects.local.cli.AresConfigurationException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates descriptor values separately from YAML I/O so each boundary remains small and testable. */
final class AresDescriptorValidator {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private AresDescriptorValidator() {}

    static void validate(AresDescriptor descriptor, Path descriptorPath) throws AresConfigurationException {
        requireDescriptor(descriptor, descriptorPath);
        requireSupportedSchema(descriptor, descriptorPath);
        Map<String, AresFunctionDescriptor> functions = requireSingleFunctionMap(descriptor, descriptorPath);
        Map.Entry<String, AresFunctionDescriptor> function =
                functions.entrySet().iterator().next();
        validateFunction(function.getKey(), function.getValue(), descriptorPath);
    }

    private static void requireDescriptor(AresDescriptor descriptor, Path descriptorPath)
            throws AresConfigurationException {
        if (descriptor == null) {
            throw invalid(descriptorPath, "the document is empty; define schemaVersion and functions");
        }
    }

    private static void requireSupportedSchema(AresDescriptor descriptor, Path descriptorPath)
            throws AresConfigurationException {
        if (descriptor.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw invalid(
                    descriptorPath,
                    "schemaVersion must be " + SUPPORTED_SCHEMA_VERSION + "; received " + descriptor.schemaVersion());
        }
    }

    private static Map<String, AresFunctionDescriptor> requireSingleFunctionMap(
            AresDescriptor descriptor, Path descriptorPath) throws AresConfigurationException {
        Map<String, AresFunctionDescriptor> functions = descriptor.functions();
        if (functions == null || functions.isEmpty()) {
            throw invalid(descriptorPath, "functions must contain exactly one function for this build slice");
        }
        if (functions.size() != 1) {
            throw invalid(
                    descriptorPath,
                    "functions contains " + functions.size()
                            + " entries; select exactly one function in this build slice");
        }
        return functions;
    }

    private static void validateFunction(String name, AresFunctionDescriptor function, Path descriptorPath)
            throws AresConfigurationException {
        requireFunction(name, function, descriptorPath);
        validateRuntime(name, function.runtime(), descriptorPath);
        validateArchitecture(name, function.architecture(), descriptorPath);
        validateHandler(name, function.handler(), descriptorPath);
        validateBuild(name, function.build(), descriptorPath);
        validateEnvironment(name, function.environment(), descriptorPath);
    }

    private static void requireFunction(String name, AresFunctionDescriptor function, Path descriptorPath)
            throws AresConfigurationException {
        if (name == null || name.isBlank()) {
            throw invalid(descriptorPath, "function names must not be blank; provide a stable function name");
        }
        if (function == null) {
            throw invalid(descriptorPath, "function '" + name + "' has no configuration");
        }
    }

    private static void validateRuntime(String name, String runtime, Path descriptorPath)
            throws AresConfigurationException {
        if (!"java21".equals(runtime)) {
            throw invalid(
                    descriptorPath,
                    "function '" + name + "' uses unsupported runtime '" + runtime + "'; use runtime java21");
        }
    }

    private static void validateArchitecture(String name, String architecture, Path descriptorPath)
            throws AresConfigurationException {
        if (!"arm64".equals(architecture) && !"x86_64".equals(architecture)) {
            throw invalid(
                    descriptorPath,
                    "function '" + name + "' uses unsupported architecture '" + architecture
                            + "'; use arm64 or x86_64");
        }
    }

    private static void validateHandler(String name, String handler, Path descriptorPath)
            throws AresConfigurationException {
        if (handler == null || handler.isBlank()) {
            throw invalid(descriptorPath, "function '" + name + "' must define a non-blank handler");
        }
    }

    private static void validateBuild(String name, AresBuildDescriptor build, Path descriptorPath)
            throws AresConfigurationException {
        if (build == null) {
            throw invalid(
                    descriptorPath, "function '" + name + "' must define a non-empty build.command argument array");
        }
        validateCommand(name, build.command(), descriptorPath);
        validateArtifact(name, build.artifact(), descriptorPath);
    }

    private static void validateCommand(String name, List<String> command, Path descriptorPath)
            throws AresConfigurationException {
        if (command == null || command.isEmpty()) {
            throw invalid(
                    descriptorPath, "function '" + name + "' must define a non-empty build.command argument array");
        }
        for (String argument : command) {
            validateCommandArgument(name, argument, descriptorPath);
        }
    }

    private static void validateCommandArgument(String name, String argument, Path descriptorPath)
            throws AresConfigurationException {
        if (argument == null || argument.isBlank() || argument.contains("\0")) {
            throw invalid(descriptorPath, "function '" + name + "' has a blank or NUL build.command argument");
        }
    }

    private static void validateArtifact(String name, String artifactName, Path descriptorPath)
            throws AresConfigurationException {
        if (artifactName == null || artifactName.isBlank()) {
            throw invalid(descriptorPath, "function '" + name + "' must define a relative build.artifact path");
        }
        Path artifact = parseArtifact(name, artifactName, descriptorPath);
        rejectAbsoluteArtifact(name, artifact, artifactName, descriptorPath);
        rejectParentArtifact(name, artifact, artifactName, descriptorPath);
    }

    private static Path parseArtifact(String name, String artifactName, Path descriptorPath)
            throws AresConfigurationException {
        try {
            return Path.of(artifactName);
        } catch (InvalidPathException exception) {
            throw invalid(descriptorPath, "function '" + name + "' has an invalid build.artifact path");
        }
    }

    private static void rejectAbsoluteArtifact(String name, Path artifact, String artifactName, Path descriptorPath)
            throws AresConfigurationException {
        if (artifact.isAbsolute()) {
            throw invalid(
                    descriptorPath,
                    "function '" + name + "' build.artifact must stay inside the project directory: " + artifactName);
        }
    }

    private static void rejectParentArtifact(String name, Path artifact, String artifactName, Path descriptorPath)
            throws AresConfigurationException {
        if (artifact.normalize().startsWith("..") || hasParentSegment(artifact)) {
            throw invalid(
                    descriptorPath,
                    "function '" + name + "' build.artifact must stay inside the project directory: " + artifactName);
        }
    }

    private static void validateEnvironment(String name, Map<String, String> environment, Path descriptorPath)
            throws AresConfigurationException {
        if (environment == null) {
            return;
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            validateEnvironmentEntry(name, entry, descriptorPath);
        }
    }

    private static void validateEnvironmentEntry(String name, Map.Entry<String, String> entry, Path descriptorPath)
            throws AresConfigurationException {
        String key = entry.getKey();
        if (key == null
                || key.isBlank()
                || entry.getValue() == null
                || entry.getValue().contains("\0")) {
            throw invalid(descriptorPath, "function '" + name + "' has an invalid environment entry");
        }
        if (key.startsWith("AWS_") || key.startsWith("ARES_AWS_LOCAL_")) {
            throw invalid(
                    descriptorPath,
                    "function '" + name + "' cannot override reserved environment variable '" + key + "'");
        }
    }

    private static boolean hasParentSegment(Path path) {
        for (Path segment : path) {
            if (Objects.equals(segment.toString(), "..")) {
                return true;
            }
        }
        return false;
    }

    private static AresConfigurationException invalid(Path descriptorPath, String detail) {
        return new AresConfigurationException("Invalid '" + descriptorPath + "': " + detail);
    }
}
