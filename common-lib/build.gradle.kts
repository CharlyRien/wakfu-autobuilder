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
    implementation(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}
