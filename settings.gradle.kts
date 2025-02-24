plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "wakfu-autobuilder"

include("autobuilder")
include("equipments-extractor")
include("common-lib")
include("gui")
include("zenith-builder")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            version("jvm", "21")
            val coroutineVersion = "1.10.1"
            val fuelVersion = "2.3.1"
            library("kotlinx-serialization-json", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            library("kotlinx-coroutine-bom", "org.jetbrains.kotlinx:kotlinx-coroutines-bom:$coroutineVersion")
            library("kotlinx-coroutine-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutineVersion")
            library("fuel-core", "com.github.kittinunf.fuel:fuel:$fuelVersion")
            library("fuel-coroutines", "com.github.kittinunf.fuel:fuel-coroutines:$fuelVersion")
            library("fuel-kotlinx", "com.github.kittinunf.fuel:fuel-kotlinx-serialization:$fuelVersion")

            library("junit-bom", "org.junit:junit-bom:5.12.0")
            plugin("ktlint", "org.jlleitschuh.gradle.ktlint").version("11.6.1")
            bundle("fuel", listOf("fuel-core", "fuel-coroutines", "fuel-kotlinx"))
        }
    }
}
