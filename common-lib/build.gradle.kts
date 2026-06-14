plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.ktlint)
}

group = "me.chosante"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
    withSourcesJar()
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
}

kotlin {
    jvmToolchain(
        libs.versions.jvm
            .get()
            .toInt()
    )
}

tasks.test {
    useJUnitPlatform()
}
