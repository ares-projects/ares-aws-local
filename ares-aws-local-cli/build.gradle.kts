plugins {
    id("ares.java-conventions")
    application
}

dependencies {
    implementation(project(":ares-aws-local-runtime"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation(project(":ares-aws-local-lambda"))
    testImplementation(project(":ares-aws-local-spi"))
    testImplementation("org.mockito:mockito-core:5.14.2")
}

application {
    mainClass.set("io.github.aresprojects.local.cli.AresCli")
    applicationName = "ares"
}

pitest {
    targetClasses.set(setOf("io.github.aresprojects.local.cli.*"))
    excludedMethods.set(setOf("main"))
}
