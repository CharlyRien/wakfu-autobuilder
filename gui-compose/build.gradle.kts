import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.konan.file.unzipTo
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.8.1"
    id("dev.hydraulic.conveyor") version "2.0"
    alias(libs.plugins.ktlint)
}

group = "me.chosante"
version = "1.0.0"

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
val projectJvmLauncher =
    javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(libs.versions.jvm.get())
    }

compose.desktop {
    application {
        mainClass = "me.chosante.ui.MainKt"
        javaHome =
            projectJvmLauncher
                .get()
                .metadata.installationPath.asFile.absolutePath
        jvmArgs +=
            listOf(
                "--enable-native-access=ALL-UNNAMED",
                "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
            )
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"
    )
}

// Downloads the artwork bundled with the Compose GUI from the community
// https://github.com/Vertylo/wakassets repository. Hits the network, so it is run on demand
// (not part of the normal build) and the resulting PNGs are committed alongside the others.
//
// It copies two sets into src/main/resources/assets:
//   - items/  filtered to the guiIds referenced by the current equipments JSON (large set)
//   - icons/  the HUD stat icons used to render characteristics + skill-tree lines (complete set)
// Existing files are never overwritten.
tasks.register("generateAssets") {
    group = "assets"

    val repoUrl = "https://github.com/Vertylo/wakassets/archive/refs/heads/main.zip"

    doLast {
        val assetsDir = file("src/main/resources/assets").apply { mkdirs() }

        // guiIds referenced by the current equipments JSON shipped in the autobuilder module
        @Suppress("UNCHECKED_CAST")
        val guiIdsFromCurrentEquipmentJson =
            rootProject.projectDir
                .resolve("autobuilder/src/main/resources")
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.minByOrNull { it.nameWithoutExtension }
                ?.let {
                    val items = groovy.json.JsonSlurper().parseText(it.readText()) as List<Map<String, Any>>
                    items.map { item -> (item["guiId"] as Int).toString() }.toSet()
                } ?: emptySet()

        // download + unzip the wakassets repository into a temp directory
        val destinationFile = createTempFile("wakassets", ".zip").toFile()
        downloadRepositoryAsZip(repoUrl, destinationFile)
        val unzippedTempDirectory = createTempDirectory()
        destinationFile.toPath().unzipTo(unzippedTempDirectory)
        val wakassetsRoot = unzippedTempDirectory.resolve("wakassets-main").toFile()

        // copy every PNG of a wakassets subdirectory into assets/<name>, skipping already-present files
        fun copyAssetDir(
            sourceDirName: String,
            keep: (File) -> Boolean = { true },
        ) {
            val targetDir = assetsDir.resolve(sourceDirName).apply { mkdirs() }
            var copied = 0
            wakassetsRoot
                .resolve(sourceDirName)
                .listFiles()
                ?.filter(keep)
                ?.forEach { asset ->
                    val destination = targetDir.resolve(asset.name)
                    if (!destination.exists()) {
                        asset.copyTo(destination)
                        copied++
                    }
                }
            logger.lifecycle("generateAssets: $sourceDirName -> $copied new file(s) added")
        }

        copyAssetDir("items") { it.nameWithoutExtension in guiIdsFromCurrentEquipmentJson }
        copyAssetDir("icons")

        destinationFile.delete()
        unzippedTempDirectory.toFile().deleteRecursively()
    }
}

fun downloadRepositoryAsZip(
    repoUrl: String,
    destinationFile: File,
) {
    val url = uri(repoUrl).toURL()
    url.openStream().use { input ->
        Files.copy(input, destinationFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
