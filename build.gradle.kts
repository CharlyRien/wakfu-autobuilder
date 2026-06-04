import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
    alias(libs.plugins.ktlint) apply false
}

val projectJvmLanguageVersion = JavaLanguageVersion.of(libs.versions.jvm.get())

// Regenerate gradle/gradle-daemon-jvm.properties from the shared JVM version.
tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion.set(projectJvmLanguageVersion)
}

subprojects {
    plugins.withType<JavaBasePlugin> {
        val javaToolchains = extensions.getByType<JavaToolchainService>()
        val projectJvmLauncher =
            javaToolchains.launcherFor {
                languageVersion.set(projectJvmLanguageVersion)
            }

        // Run Gradle-launched apps and tests with the same JVM as the project toolchain.
        tasks.withType<JavaExec>().configureEach {
            javaLauncher.set(projectJvmLauncher)
            jvmArgs("--enable-native-access=ALL-UNNAMED")
        }

        tasks.withType<Test>().configureEach {
            javaLauncher.set(projectJvmLauncher)
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
