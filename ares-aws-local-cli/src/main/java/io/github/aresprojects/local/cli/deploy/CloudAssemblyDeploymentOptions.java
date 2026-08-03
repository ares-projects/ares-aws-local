package io.github.aresprojects.local.cli.deploy;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses command-line options that select and parameterize a Cloud Assembly deployment. */
public record CloudAssemblyDeploymentOptions(
        String stack, Map<String, String> parameters, boolean dryRun, URI endpoint) {
    public CloudAssemblyDeploymentOptions {
        parameters = Map.copyOf(parameters);
    }

    /** Parses options after the deployment path. */
    public static CloudAssemblyDeploymentOptions parse(List<String> arguments) {
        String stack = null;
        URI endpoint = null;
        boolean dryRun = false;
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int index = 2; index < arguments.size(); index++) {
            String option = arguments.get(index);
            if ("--stack".equals(option)) {
                stack = requiredOption(arguments, ++index, "--stack");
            } else if ("--parameter".equals(option)) {
                String value = requiredOption(arguments, ++index, "--parameter");
                int separator = value.indexOf('=');
                if (separator <= 0) {
                    throw new IllegalArgumentException("--parameter requires Key=Value");
                }
                parameters.put(value.substring(0, separator), value.substring(separator + 1));
            } else if ("--dry-run".equals(option)) {
                dryRun = true;
            } else if ("--endpoint".equals(option)) {
                endpoint = URI.create(requiredOption(arguments, ++index, "--endpoint"));
            } else {
                throw new IllegalArgumentException("unsupported Cloud Assembly deploy option '" + option + "'");
            }
        }
        return new CloudAssemblyDeploymentOptions(stack, parameters, dryRun, endpoint);
    }

    private static String requiredOption(List<String> arguments, int index, String option) {
        if (index >= arguments.size() || arguments.get(index).isBlank()) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return arguments.get(index);
    }
}
