plugins {
    id("ares.java-library-conventions")
}

dependencies {
    api(project(":ares-aws-local-spi"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

pitest {
    targetClasses.set(setOf("io.github.aresprojects.local.lambda.*"))
}
