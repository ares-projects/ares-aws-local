package io.github.aresprojects.local.cli.builder;

import io.github.aresprojects.local.cli.AresBuildException;
import io.github.aresprojects.local.cli.AresConfigurationException;
import io.github.aresprojects.local.cli.project.AresProject;
import io.github.aresprojects.local.cli.project.AresProjectReader;
import java.nio.file.Path;

/** Builds one validated local Lambda project and emits its deployment metadata. */
public final class AresBuildService {
    private final AresProjectReader projectReader;
    private final BuildCommandRunner commandRunner;
    private final ArtifactInspector artifactInspector;
    private final DeploymentResultWriter resultWriter;

    /** Creates the production build service using real subprocess and filesystem boundaries. */
    public AresBuildService() {
        this(
                new AresProjectReader(),
                new DefaultBuildCommandRunner(),
                new ArtifactInspector(),
                new DeploymentResultWriter());
    }

    AresBuildService(
            AresProjectReader projectReader,
            BuildCommandRunner commandRunner,
            ArtifactInspector artifactInspector,
            DeploymentResultWriter resultWriter) {
        this.projectReader = projectReader;
        this.commandRunner = commandRunner;
        this.artifactInspector = artifactInspector;
        this.resultWriter = resultWriter;
    }

    /** Runs the configured argument-array build, validates the ZIP, and writes deployment.json. */
    public DeploymentResult build(Path projectDirectory) throws AresConfigurationException, AresBuildException {
        AresProject project = projectReader.read(projectDirectory);
        BuildProcessResult process =
                commandRunner.run(project.function().build().command(), project.directory());
        if (process.exitCode() != 0) {
            String diagnostics = process.standardError().isBlank() ? process.standardOutput() : process.standardError();
            throw new AresBuildException("Build command failed with exit code " + process.exitCode() + " for function '"
                    + project.functionName() + "'; fix the build output and retry\n" + diagnostics.trim());
        }

        Path artifact = project.artifactPath();
        ArtifactMetadata metadata = artifactInspector.inspect(artifact);
        DeploymentResult result = new DeploymentResult(
                1,
                project.functionName(),
                project.function().runtime(),
                project.function().architecture(),
                project.function().handler(),
                project.directory(),
                artifact,
                metadata.sha256(),
                metadata.sizeBytes(),
                project.environmentNames());
        resultWriter.write(project.deploymentPath(), result);
        return result;
    }
}
