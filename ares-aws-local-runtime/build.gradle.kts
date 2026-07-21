plugins {
    id("ares.java-conventions")
    application
}

dependencies {
    implementation(platform("io.netty:netty-bom:4.2.16.Final"))
    implementation("io.netty:netty-codec-http")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation(platform("software.amazon.awssdk:bom:2.46.8"))
    testImplementation("software.amazon.awssdk:sqs")
}

application {
    mainClass.set("io.github.aresprojects.local.runtime.LocalAwsRuntime")
}

pitest {
    targetClasses.set(setOf("io.github.aresprojects.local.runtime.*"))
}
