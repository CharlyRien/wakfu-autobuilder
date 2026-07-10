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

dependencies {
    implementation(project(":common-lib"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.fuel)
    implementation(platform(libs.kotlinx.coroutine.bom))
    implementation(libs.kotlinx.coroutine.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
