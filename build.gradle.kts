import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.JavaExec
import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    alias(libs.plugins.ktlint) apply false
}

val projectJvmLanguageVersion = JavaLanguageVersion.of(libs.versions.jvm.get())

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion.set(projectJvmLanguageVersion)
}

subprojects {
    plugins.withType<JavaBasePlugin> {
        // Enable native access (OR-Tools) for Gradle-launched apps. The JVM version itself is fixed
        // by each module's `kotlin { jvmToolchain(...) }`; we intentionally do NOT set javaLauncher
        // here. Forcing it conflicts with the `executable` the IDE injects when launching a task
        // ("Toolchain from `executable` does not match toolchain from `javaLauncher`"), which broke
        // running apps (e.g. equipments-extractor) from the IDE.
        tasks.withType<JavaExec>().configureEach {
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }
    }
}

tasks.register<Exec>("conveyorRun") {
    group = "conveyor"
    description = "Run the wakfu autobuilder gui through conveyor"
    workingDir = file("$projectDir/gui")
    dependsOn(":gui:build")

    commandLine("bash", "-c", "conveyor -f conveyor-local.conf run")
}
