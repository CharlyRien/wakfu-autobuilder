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
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation(platform(libs.kotlinx.coroutine.bom))
    implementation(libs.bundles.fuel)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":common-lib"))
}

kotlin {
    jvmToolchain(
        libs.versions.jvm
            .get()
            .toInt()
    )
}

application {
    mainClass.set("me.chosante.equipmentextractor.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
