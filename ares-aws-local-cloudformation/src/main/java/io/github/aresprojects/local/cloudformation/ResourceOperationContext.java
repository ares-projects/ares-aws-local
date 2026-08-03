package io.github.aresprojects.local.cloudformation;

import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/** Immutable context shared with resource handlers during one stack operation. */
public record ResourceOperationContext(
        String stackName,
        String accountId,
        String region,
        URI localEndpoint,
        Clock clock,
        Map<String, String> parameters,
        AssetResolver assetResolver) {
    public ResourceOperationContext {
        stackName = requireText(stackName, "stackName");
        accountId = requireText(accountId, "accountId");
        region = requireText(region, "region");
        localEndpoint = Objects.requireNonNull(localEndpoint, "localEndpoint");
        clock = Objects.requireNonNull(clock, "clock");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
        assetResolver = Objects.requireNonNull(assetResolver, "assetResolver");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
