import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    java
    checkstyle
    jacoco
    pmd
    id("info.solidsoft.pitest")
    id("com.github.spotbugs")
    id("com.diffplug.spotless")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.12.2")
}

checkstyle {
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

spotbugs {
    ignoreFailures = false
}

jacoco { toolVersion = "0.8.14" }

pmd {
    toolVersion = "7.26.0"
    isIgnoreFailures = false
    ruleSetFiles = files(rootProject.file("config/pmd/pmd.xml"))
    ruleSets = emptyList()
}

pitest {
    junit5PluginVersion.set("1.2.3")
    pitestVersion.set("1.19.0")
    targetClasses.set(setOf("io.github.aresprojects.${project.name.removePrefix("ares-")}.*"))
    mutationThreshold.set(90)
    threads.set(2)
    outputFormats.set(setOf("HTML", "XML"))
    timestampedReports.set(false)
    failWhenNoMutations.set(true)
}

spotless {
    java {
        palantirJavaFormat("2.93.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle { ktlint() }
    format("misc") {
        target("*.md", "*.yml", "*.yaml", "*.json", ".gitignore", ".gitattributes")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named("check") { dependsOn("spotlessCheck") }
tasks.register("formatCheck") { dependsOn("spotlessCheck") }

tasks.named("jacocoTestReport") {
    dependsOn("test")
}

tasks.withType<JacocoCoverageVerification>().configureEach {
    dependsOn("test")
    violationRules {
        rule {
            limit {
                minimum = BigDecimal("0.95")
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
    dependsOn("pmdMain", "pmdTest")
    dependsOn("pitest")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
