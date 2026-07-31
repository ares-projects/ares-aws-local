import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    id("ares.java-library-conventions")
}

dependencies {
    api(project(":ares-aws-local-lambda"))
    api(project(":ares-aws-local-spi"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
}

pitest {
    targetClasses.set(setOf("io.github.aresprojects.local.lambda.docker.*"))
    // Process and HTTP lifecycle paths are validated through deterministic fakes and Docker smoke tests.
    excludedClasses.set(
        setOf(
            "io.github.aresprojects.local.lambda.docker.DockerCliContainerRuntime",
            "io.github.aresprojects.local.lambda.docker.DockerLambdaExecutionBackend",
            "io.github.aresprojects.local.lambda.docker.SystemDockerProcessRunner",
        ),
    )
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(
        classDirectories.files.map { directory ->
            fileTree(directory) {
                // Keep external Docker behavior out of the unit coverage threshold.
                exclude(
                    "io/github/aresprojects/local/lambda/docker/DockerCliContainerRuntime.class",
                    "io/github/aresprojects/local/lambda/docker/DockerLambdaExecutionBackend.class",
                    "io/github/aresprojects/local/lambda/docker/SystemDockerProcessRunner.class",
                )
            }
        },
    )
}
