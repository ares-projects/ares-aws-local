plugins {
    base
    id("com.diffplug.spotless") version "7.0.2"
    id("ares.java-conventions") apply false
    id("ares.java-library-conventions") apply false
}

group = "io.github.ares-projects"
version = "0.1.0-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

spotless {
    format("misc") {
        target("*.md", "*.yml", "*.yaml", "*.json", ".gitignore", ".gitattributes")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.register("formatCheck") {
    dependsOn(subprojects.map { "${it.path}:spotlessCheck" })
    dependsOn("spotlessCheck")
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
    dependsOn("spotlessCheck")
}
