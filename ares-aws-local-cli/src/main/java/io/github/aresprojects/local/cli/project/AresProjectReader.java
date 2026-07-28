package io.github.aresprojects.local.cli.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.aresprojects.local.cli.AresConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Reads and validates the strict single-function {@code ares.yaml} project contract. */
public final class AresProjectReader {
    private static final String DESCRIPTOR_NAME = "ares.yaml";
    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Reads a project directory and rejects unsupported or ambiguous descriptors. */
    public AresProject read(Path requestedDirectory) throws AresConfigurationException {
        requireRequestedDirectory(requestedDirectory);
        Path directory = requestedDirectory.toAbsolutePath().normalize();
        requireProjectDirectory(directory, requestedDirectory);
        Path descriptorPath = directory.resolve(DESCRIPTOR_NAME);
        requireReadableDescriptor(descriptorPath);
        AresDescriptor descriptor = readDescriptor(descriptorPath);
        AresDescriptorValidator.validate(descriptor, descriptorPath);
        Map.Entry<String, AresFunctionDescriptor> function =
                descriptor.functions().entrySet().iterator().next();
        return new AresProject(
                directory,
                function.getKey(),
                function.getValue(),
                AresProject.sortedEnvironmentNames(function.getValue().environment()));
    }

    private static void requireRequestedDirectory(Path requestedDirectory) throws AresConfigurationException {
        if (requestedDirectory == null) {
            throw new AresConfigurationException("Function path is required; pass a directory containing ares.yaml");
        }
    }

    private static void requireProjectDirectory(Path directory, Path requestedDirectory)
            throws AresConfigurationException {
        if (!Files.isDirectory(directory)) {
            throw new AresConfigurationException(
                    "Function path '" + requestedDirectory + "' is not a directory; pass the Lambda project path");
        }
    }

    private static void requireReadableDescriptor(Path descriptorPath) throws AresConfigurationException {
        if (!Files.isRegularFile(descriptorPath) || !Files.isReadable(descriptorPath)) {
            throw new AresConfigurationException(
                    "Missing or unreadable '" + descriptorPath + "'; add a readable ares.yaml descriptor");
        }
    }

    private static AresDescriptor readDescriptor(Path descriptorPath) throws AresConfigurationException {
        try {
            return MAPPER.readValue(descriptorPath.toFile(), AresDescriptor.class);
        } catch (JsonProcessingException exception) {
            throw new AresConfigurationException(
                    "Invalid YAML in '" + descriptorPath + "': " + exception.getMessage()
                            + "; fix the descriptor and retry",
                    exception);
        } catch (IOException exception) {
            throw new AresConfigurationException(
                    "Could not read '" + descriptorPath + "'; check file permissions and retry", exception);
        }
    }
}
