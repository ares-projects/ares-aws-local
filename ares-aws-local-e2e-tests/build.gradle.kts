plugins {
    java
    id("com.diffplug.spotless") version "7.0.2"
}

dependencies {
    testImplementation(testFixtures(project(":ares-aws-local-cli")))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
    dependsOn(":ares-aws-local-cli:installDist")
    useJUnitPlatform()
}

spotless {
    java {
        palantirJavaFormat("2.93.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target("*.md", "*.yml", "*.yaml", "*.json")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
