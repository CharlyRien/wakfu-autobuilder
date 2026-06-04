import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("dev.hydraulic.conveyor") version "2.0"
    alias(libs.plugins.ktlint)
}

group = "me.chosante"
version = "0.6.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":autobuilder"))
    implementation(project(":zenith-builder"))
    implementation(project(":common-lib"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(platform(libs.kotlinx.coroutine.bom))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(
        libs.versions.jvm
            .get()
            .toInt()
    )
}

ktlint {
    filter {
        exclude("**/generated/**")
    }
}

val javaToolchains = project.extensions.getByType<JavaToolchainService>()
val projectJvmLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(libs.versions.jvm.get())
}

compose.desktop {
    application {
        mainClass = "me.chosante.ui.MainKt"
        javaHome = projectJvmLauncher.get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
    }
}

tasks.test {
    useJUnitPlatform()
}
