plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.7.0"
}
rootProject.name = "wakfu-autobuilder"

include("autobuilder")
include("equipments-extractor")
include("common-lib")
include("gui")
