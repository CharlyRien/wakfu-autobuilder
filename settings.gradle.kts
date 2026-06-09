plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "wakfu-autobuilder"

include("autobuilder")
include("equipments-extractor")
include("common-lib")
include("gui-compose")
include("zenith-builder")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("jvm", "25")
            // Kotlin: drives the kotlin-jvm / kotlin-serialization / compose-compiler plugins (they
            // are released in lockstep with the Kotlin version) across every module.
            version("kotlin", "2.3.21")
            // OR-Tools: the JVM lib (autobuilder) and the per-OS native artifacts (gui-compose
            // packaging) share this version.
            version("ortools", "9.15.6755")
            val coroutineVersion = "1.11.0"
            val fuelVersion = "2.3.1"
            library("kotlinx-serialization-json", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            library("kotlinx-coroutine-bom", "org.jetbrains.kotlinx:kotlinx-coroutines-bom:$coroutineVersion")
            library("kotlinx-coroutine-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutineVersion")
            library("kotlinx-coroutine-swing", "org.jetbrains.kotlinx:kotlinx-coroutines-swing:$coroutineVersion")
            library("fuel-core", "com.github.kittinunf.fuel:fuel:$fuelVersion")
            library("fuel-coroutines", "com.github.kittinunf.fuel:fuel-coroutines:$fuelVersion")
            library("fuel-kotlinx", "com.github.kittinunf.fuel:fuel-kotlinx-serialization:$fuelVersion")
            library("ortools-java", "com.google.ortools", "ortools-java").versionRef("ortools")
            library("assertj-core", "org.assertj:assertj-core:3.27.7")

            library("junit-bom", "org.junit:junit-bom:6.1.0")
            library("junit-jupiter", "org.junit.jupiter", "junit-jupiter").withoutVersion()
            plugin("ktlint", "org.jlleitschuh.gradle.ktlint").version("14.2.0")
            plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
            plugin("kotlin-serialization", "org.jetbrains.kotlin.plugin.serialization").versionRef("kotlin")
            plugin("kotlin-compose-compiler", "org.jetbrains.kotlin.plugin.compose").versionRef("kotlin")
            bundle("fuel", listOf("fuel-core", "fuel-coroutines", "fuel-kotlinx"))

            // Compose Multiplatform. The `compose` version drives the `org.jetbrains.compose` plugin
            // and the per-OS Desktop artifacts (they share that version). Compose Material3 ships on a
            // separate cadence, hence its own version. These replace the deprecated `compose.material3`
            // / `compose.desktop.<platform>` Gradle DSL accessors.
            version("compose", "1.11.1")
            version("composeMaterial3", "1.9.0")
            plugin("compose", "org.jetbrains.compose").versionRef("compose")
            library("compose-material3", "org.jetbrains.compose.material3", "material3").versionRef("composeMaterial3")
            library("compose-desktop-macos-arm64", "org.jetbrains.compose.desktop", "desktop-jvm-macos-arm64").versionRef("compose")
            library("compose-desktop-macos-x64", "org.jetbrains.compose.desktop", "desktop-jvm-macos-x64").versionRef("compose")
            library("compose-desktop-linux-x64", "org.jetbrains.compose.desktop", "desktop-jvm-linux-x64").versionRef("compose")
            library("compose-desktop-windows-x64", "org.jetbrains.compose.desktop", "desktop-jvm-windows-x64").versionRef("compose")
        }
    }
}
