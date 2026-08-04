plugins {
    id("ares.java-library-conventions")
}

dependencies {
    api(project(":ares-aws-local-spi"))
    implementation("software.amazon.dynamodb:DynamoDBLocal:3.3.0")
    testImplementation(platform("software.amazon.awssdk:bom:2.46.8"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("software.amazon.awssdk:dynamodb")
}

pitest {
    targetClasses.set(setOf("io.github.aresprojects.local.dynamodb.*"))
}
