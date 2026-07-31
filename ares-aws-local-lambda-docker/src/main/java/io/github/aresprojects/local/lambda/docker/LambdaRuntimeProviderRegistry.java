package io.github.aresprojects.local.lambda.docker;

import io.github.aresprojects.local.lambda.LambdaFunctionSnapshot;
import java.util.List;
import java.util.Objects;

/** Immutable, ordered registry for runtime providers. */
public final class LambdaRuntimeProviderRegistry {
    private final List<LambdaRuntimeProvider> providers;

    private LambdaRuntimeProviderRegistry(List<LambdaRuntimeProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    /** Creates a registry from providers in precedence order. */
    public static LambdaRuntimeProviderRegistry of(List<LambdaRuntimeProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("At least one Lambda runtime provider must be registered");
        }
        return new LambdaRuntimeProviderRegistry(providers);
    }

    /** Returns the first provider that supports the function. */
    public LambdaRuntimeProvider resolve(LambdaFunctionSnapshot function) {
        Objects.requireNonNull(function, "function");
        return providers.stream()
                .filter(provider -> provider.supports(function))
                .findFirst()
                .orElseThrow(() -> new DockerExecutionException(
                        DockerExecutionFailure.ARCHITECTURE_MISMATCH,
                        "No local Lambda runtime provider supports runtime '" + function.runtime()
                                + "' and architecture '" + function.architecture() + "'"));
    }
}
