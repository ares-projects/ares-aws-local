package io.github.aresprojects.local.lambda.docker;

import io.github.aresprojects.local.lambda.LambdaFunctionSnapshot;

/** Maps a deployed Lambda runtime and architecture to an immutable container image. */
public interface LambdaRuntimeProvider {

    /** Returns whether this provider owns the function runtime. */
    boolean supports(LambdaFunctionSnapshot function);

    /** Returns the pinned image reference for the function architecture. */
    String image(LambdaFunctionSnapshot function);
}
