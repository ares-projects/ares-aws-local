plugins { `kotlin-dsl` }

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:7.0.2")
    implementation("com.github.spotbugs:com.github.spotbugs.gradle.plugin:6.5.4")
    implementation("info.solidsoft.pitest:info.solidsoft.pitest.gradle.plugin:1.19.0")
}
