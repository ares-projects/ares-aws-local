plugins {
    java
}

group = "io.github.aresprojects.examples"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.jar {
    archiveFileName.set("hello.jar")
}

tasks.register<Zip>("lambdaZip") {
    dependsOn(tasks.jar)
    archiveFileName.set("hello.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(tasks.jar.map { zipTree(it.archiveFile) })
}
