plugins {
    id("ares.java-library-conventions")
}

dependencies {
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")
}

pitest {
    targetClasses.set(setOf("io.github.aresprojects.local.runtime.*"))
}
