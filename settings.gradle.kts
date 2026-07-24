pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

rootProject.name = "ares-aws-local"
includeBuild("build-logic")
include("ares-aws-local-spi")
include("ares-aws-local-lambda")
include("ares-aws-local-lambda-docker")
include("ares-aws-local-runtime")
include("ares-aws-local-cli")
include("ares-aws-local-e2e-tests")
