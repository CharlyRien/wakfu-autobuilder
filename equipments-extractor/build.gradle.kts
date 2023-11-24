plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.ktlint)
    application
}

group = "me.chosante"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation(libs.bundles.fuel)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":common-lib"))
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("me.chosante.equipmentextractor.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
