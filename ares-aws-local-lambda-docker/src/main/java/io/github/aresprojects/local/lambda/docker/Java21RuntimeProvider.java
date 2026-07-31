package io.github.aresprojects.local.lambda.docker;

import io.github.aresprojects.local.lambda.LambdaFunctionSnapshot;

/** Provides AWS's Java 21 Lambda base images, pinned by architecture-specific digest. */
public final class Java21RuntimeProvider implements LambdaRuntimeProvider {
    public static final String ARM64_IMAGE =
            "public.ecr.aws/lambda/java@sha256:6891daaf2cb5dd45a15523d8803ef7dc53bb253cbfb8064c3a6fc662a5988062";
    public static final String X86_64_IMAGE =
            "public.ecr.aws/lambda/java@sha256:3f5ba631b75fd14f4193f01992259e26c746d645d42c4c5c0d6e5038beae320f";

    @Override
    public boolean supports(LambdaFunctionSnapshot function) {
        return "java21".equals(function.runtime())
                && ("arm64".equals(function.architecture()) || "x86_64".equals(function.architecture()));
    }

    @Override
    public String image(LambdaFunctionSnapshot function) {
        if (!supports(function)) {
            throw new DockerExecutionException(
                    DockerExecutionFailure.ARCHITECTURE_MISMATCH,
                    "Java 21 provider cannot execute runtime '" + function.runtime() + "' with architecture '"
                            + function.architecture() + "'");
        }
        return "arm64".equals(function.architecture()) ? ARM64_IMAGE : X86_64_IMAGE;
    }
}
