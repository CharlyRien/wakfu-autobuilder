plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "wakfu-autobuilder"

include("autobuilder")
include("equipments-extractor")
include("spells-extractor")
include("bdata-extractor")
include("monsters-extractor")
include("common-lib")
include("gui-compose")
include("zenith-builder")

// The version catalog lives in the standard `gradle/libs.versions.toml`, which Gradle auto-loads as
// the `libs` catalog. It used to be declared inline here via `dependencyResolutionManagement {
// versionCatalogs { ... } }`, but that form hid most versions from Dependabot — see the header of
// gradle/libs.versions.toml.
