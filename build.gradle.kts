plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    alias(libs.plugins.ktlint) apply false
}

tasks.register<Exec>("conveyorRun") {
    group = "conveyor"
    description = "Run the wakfu autobuilder gui through conveyor"
    workingDir = file("$projectDir/gui")
    dependsOn(":gui:build")

    commandLine("bash", "-c", "conveyor -f conveyor-local.conf run")
}
