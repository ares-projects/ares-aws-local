package io.github.aresprojects.local.lambda;

/** Stores validated Lambda ZIP artifacts outside the emulator's source tree. */
public interface LambdaArtifactStore extends AutoCloseable {

    /** Stages and validates one ZIP artifact. */
    LambdaArtifact stage(byte[] zipBytes);

    /** Deletes an artifact that is no longer referenced by a function revision. */
    void delete(LambdaArtifact artifact);

    @Override
    void close();
}
