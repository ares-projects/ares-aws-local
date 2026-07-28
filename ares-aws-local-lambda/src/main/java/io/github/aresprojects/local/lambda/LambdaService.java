package io.github.aresprojects.local.lambda;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Implements the process-local Lambda control-plane operations supported by M3. */
public final class LambdaService implements AutoCloseable {
    public static final String DEFAULT_REGION = "us-east-1";
    public static final String ACCOUNT_ID = "000000000000";

    private static final Pattern FUNCTION_NAME = Pattern.compile("[A-Za-z0-9-_]{1,64}");
    private static final String SUPPORTED_RUNTIME = "java21";

    private final LambdaFunctionStore functionStore;
    private final LambdaArtifactStore artifactStore;
    private final LambdaExecutionBackend executionBackend;
    private final Clock clock;
    private final String region;
    private final Object mutationLock = new Object();

    /** Creates a process-local service with temporary artifact storage and no execution backend. */
    public LambdaService() {
        this(
                new InMemoryLambdaFunctionStore(),
                new TemporaryLambdaArtifactStore(),
                new NoOpLambdaExecutionBackend(),
                Clock.systemUTC(),
                DEFAULT_REGION);
    }

    /** Creates a service with injectable boundaries for tests and future execution backends. */
    public LambdaService(
            LambdaFunctionStore functionStore,
            LambdaArtifactStore artifactStore,
            LambdaExecutionBackend executionBackend,
            Clock clock,
            String region) {
        this.functionStore = Objects.requireNonNull(functionStore, "functionStore");
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.executionBackend = Objects.requireNonNull(executionBackend, "executionBackend");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.region = required(region, "region");
    }

    /** Creates a function from a validated ZIP upload. */
    public LambdaFunctionSnapshot create(
            String functionName,
            String runtime,
            String architecture,
            String handler,
            String role,
            String description,
            int timeoutSeconds,
            int memorySizeMb,
            Map<String, String> environment,
            byte[] zipBytes) {
        validateFunctionName(functionName);
        validateRuntime(runtime);
        validateArchitecture(architecture);
        validateHandler(handler);
        validateRole(role);
        validateDescription(description);
        validateLimits(timeoutSeconds, memorySizeMb);
        Map<String, String> safeEnvironment = copyEnvironment(environment);
        LambdaArtifact artifact = artifactStore.stage(zipBytes);
        LambdaFunctionSnapshot function = snapshot(
                functionName,
                runtime,
                architecture,
                handler,
                role,
                description,
                timeoutSeconds,
                memorySizeMb,
                safeEnvironment,
                artifact,
                Instant.now(clock));
        try {
            synchronized (mutationLock) {
                functionStore.create(function);
            }
            return function;
        } catch (RuntimeException exception) {
            artifactStore.delete(artifact);
            throw exception;
        }
    }

    /** Returns the active function or an AWS-shaped not-found failure. */
    public LambdaFunctionSnapshot get(String functionName) {
        String requiredName = required(functionName, "functionName");
        return functionStore.find(requiredName).orElseThrow(() -> notFound(requiredName));
    }

    /** Replaces code and preserves the previous active revision if staging fails. */
    public LambdaFunctionSnapshot updateCode(String functionName, byte[] zipBytes, Optional<String> architecture) {
        LambdaFunctionSnapshot current = get(functionName);
        String nextArchitecture = architecture.orElse(current.architecture());
        validateArchitecture(nextArchitecture);
        LambdaArtifact artifact = artifactStore.stage(zipBytes);
        LambdaFunctionSnapshot replacement = snapshot(
                current.functionName(),
                current.runtime(),
                nextArchitecture,
                current.handler(),
                current.role(),
                current.description(),
                current.timeoutSeconds(),
                current.memorySizeMb(),
                current.environment(),
                artifact,
                Instant.now(clock));
        try {
            synchronized (mutationLock) {
                functionStore.replace(replacement);
            }
            artifactStore.delete(current.artifact());
            executionBackend.invalidate(current.functionName(), current.revisionId());
            return replacement;
        } catch (RuntimeException exception) {
            artifactStore.delete(artifact);
            throw exception;
        }
    }

    /** Replaces selected configuration fields while retaining the active artifact. */
    public LambdaFunctionSnapshot updateConfiguration(String functionName, LambdaConfigurationUpdate update) {
        LambdaFunctionSnapshot current = get(functionName);
        Objects.requireNonNull(update, "update");
        String description = update.description().orElse(current.description());
        String handler = update.handler().orElse(current.handler());
        String runtime = update.runtime().orElse(current.runtime());
        String architecture = update.architecture().orElse(current.architecture());
        String role = update.role().orElse(current.role());
        int timeout = update.timeoutSeconds().orElse(current.timeoutSeconds());
        int memory = update.memorySizeMb().orElse(current.memorySizeMb());
        validateRuntime(runtime);
        validateArchitecture(architecture);
        validateHandler(handler);
        validateRole(role);
        validateLimits(timeout, memory);
        Map<String, String> environment = update.environment().orElse(current.environment());
        LambdaFunctionSnapshot replacement = snapshot(
                current.functionName(),
                runtime,
                architecture,
                handler,
                role,
                description,
                timeout,
                memory,
                copyEnvironment(environment),
                current.artifact(),
                Instant.now(clock));
        synchronized (mutationLock) {
            functionStore.replace(replacement);
        }
        executionBackend.invalidate(current.functionName(), current.revisionId());
        return replacement;
    }

    /** Deletes a function, its active artifact, and its execution revision. */
    public void delete(String functionName) {
        String requiredName = required(functionName, "functionName");
        LambdaFunctionSnapshot removed;
        synchronized (mutationLock) {
            removed = functionStore.remove(requiredName).orElseThrow(() -> notFound(requiredName));
        }
        artifactStore.delete(removed.artifact());
        executionBackend.invalidate(removed.functionName(), removed.revisionId());
    }

    /** Releases process-local artifact storage owned by this service. */
    @Override
    public void close() {
        artifactStore.close();
    }

    private LambdaFunctionSnapshot snapshot(
            String functionName,
            String runtime,
            String architecture,
            String handler,
            String role,
            String description,
            int timeoutSeconds,
            int memorySizeMb,
            Map<String, String> environment,
            LambdaArtifact artifact,
            Instant lastModified) {
        return new LambdaFunctionSnapshot(
                functionName,
                "arn:aws:lambda:" + region + ":" + ACCOUNT_ID + ":function:" + functionName,
                runtime,
                architecture,
                handler,
                role,
                description,
                timeoutSeconds,
                memorySizeMb,
                environment,
                UUID.randomUUID().toString(),
                lastModified,
                artifact);
    }

    private static Map<String, String> copyEnvironment(Map<String, String> environment) {
        if (environment == null) {
            return Map.of();
        }
        environment.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new LambdaServiceException(
                        "InvalidParameterValueException", "Environment variable names and values must be non-null");
            }
        });
        return Map.copyOf(environment);
    }

    private static void validateFunctionName(String value) {
        if (value == null || !FUNCTION_NAME.matcher(value).matches()) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException",
                    "FunctionName must contain 1 to 64 letters, numbers, hyphens, or underscores");
        }
    }

    private static void validateRuntime(String value) {
        if (!SUPPORTED_RUNTIME.equals(value)) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", "Only runtime 'java21' is supported in this milestone");
        }
    }

    private static void validateArchitecture(String value) {
        if (!"arm64".equals(value) && !"x86_64".equals(value)) {
            throw new LambdaServiceException("InvalidParameterValueException", "Architecture must be arm64 or x86_64");
        }
    }

    private static void validateHandler(String value) {
        if (value == null || value.isBlank()) {
            throw new LambdaServiceException("InvalidParameterValueException", "Handler must not be blank");
        }
    }

    private static void validateRole(String value) {
        if (value == null || value.isBlank()) {
            throw new LambdaServiceException("InvalidParameterValueException", "Role must not be blank");
        }
    }

    private static void validateDescription(String value) {
        if (value == null) {
            throw new LambdaServiceException("InvalidParameterValueException", "Description must not be null");
        }
    }

    private static void validateLimits(int timeoutSeconds, int memorySizeMb) {
        if (timeoutSeconds < 1 || timeoutSeconds > 900) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", "Timeout must be between 1 and 900 seconds");
        }
        if (memorySizeMb < 128 || memorySizeMb > 10_240) {
            throw new LambdaServiceException(
                    "InvalidParameterValueException", "MemorySize must be between 128 and 10240 MB");
        }
    }

    private static LambdaServiceException notFound(String functionName) {
        return new LambdaServiceException(
                "ResourceNotFoundException", "Function '" + functionName + "' does not exist");
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
