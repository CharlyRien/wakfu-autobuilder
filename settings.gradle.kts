plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}

rootProject.name = "wakfu-autobuilder"

include("autobuilder")
include("equipments-extractor")
include("common-lib")
include("gui")

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            library("kotlinx-serialization-json", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.1")
            library("fuel-core","com.github.kittinunf.fuel:fuel:2.3.1")
            library("fuel-coroutines","com.github.kittinunf.fuel:fuel-coroutines:2.3.1")
            library("fuel-kotlinx","com.github.kittinunf.fuel:fuel-kotlinx-serialization:2.3.1")

            plugin("ktlint", "org.jlleitschuh.gradle.ktlint").version("11.6.1")
            bundle("fuel", listOf("fuel-core", "fuel-coroutines", "fuel-kotlinx"))
        }
    }
}
