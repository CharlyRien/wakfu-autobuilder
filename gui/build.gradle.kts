import org.jetbrains.kotlin.konan.file.unzipTo
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile

plugins {
    kotlin("jvm")
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("dev.hydraulic.conveyor") version "1.12"
    alias(libs.plugins.ktlint)
    application
}

group = "me.chosante"
version = "0.4.0"

repositories {
    mavenCentral()
}

application {
    mainClass = "me.chosante.WakfuAutobuilderGUIKt"
}

dependencies {
    implementation(project(":autobuilder"))
    implementation(project(":zenith-builder"))
    implementation(project(":common-lib"))
    implementation(platform(libs.kotlinx.coroutine.bom))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-feather-pack:12.3.1")
    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks {
    val generateKotlinI18nKeys by registering {
        group = "I18n"
        val propertiesFile = file("src/main/resources/i18n_en.properties")
        val properties = Properties()
        properties.load(propertiesFile.inputStream())

        val enumName = "I18nKey"
        val constantsClass =
            buildString {
                appendLine("package generated")
                appendLine("enum class $enumName(val key: String) {")
                properties.forEach { key, _ ->
                    val constantKeyName = key.toString().replace("[^a-zA-Z0-9]".toRegex(), "_").uppercase()
                    appendLine("""    $constantKeyName("$key"), """)
                }
                appendLine("}")
            }

        Files.createDirectories(file("src/main/kotlin/generated").toPath())
        file("src/main/kotlin/generated/$enumName.kt").writeText(constantsClass)
    }

    val generateAssets by register("generateAssets") {
        group = "assets"
        val assetsDir =
            file("src/main/resources/assets").apply {
                mkdirs()
            }
        val assetsItemsDir =
            file(assetsDir.absolutePath + "/items").apply {
                mkdirs()
            }

        // take the equipment json file and parse it to get all the items name from the other module autobuilder
        @Suppress("UNCHECKED_CAST")
        val guiIdsFromCurrentEquipmentJson =
            parent
                ?.projectDir
                ?.resolve("autobuilder/src/main/resources")
                ?.listFiles()
                ?.minByOrNull { it.nameWithoutExtension }
                ?.let {
                    val items = groovy.json.JsonSlurper().parseText(it.readText()) as List<Map<String, Any>>
                    items.map { item -> (item["guiId"] as Int).toString() }
                } ?: emptyList()

        // take all file name and check if it exists in a github project
        // if it does, download it and put it in the assets folder
        val repoUrl = "https://github.com/Vertylo/wakassets/archive/refs/heads/main.zip"
        val destinationFile = createTempFile("wakassets", ".zip").toFile()
        downloadRepositoryAsZip(repoUrl, destinationFile)

        // unzip the file
        val unzippedTempDirectory = createTempDirectory()
        destinationFile.toPath().unzipTo(unzippedTempDirectory)
        val allExistingGuiId =
            unzippedTempDirectory
                .resolve("wakassets-main/items")
                .toFile()
                .listFiles()
                .toList()

        // filter the items that are in the equipment json file
        val itemsExistingInEquipmentJson = allExistingGuiId.filter { it.nameWithoutExtension in guiIdsFromCurrentEquipmentJson }

        // copy the items to the assets folder
        // but does not override existing files
        itemsExistingInEquipmentJson.forEach { item ->
            val destination = assetsItemsDir.resolve(item.name)
            if (!destination.exists()) {
                item.copyTo(destination)
            }
        }

        destinationFile.delete()
        unzippedTempDirectory.toFile().deleteRecursively()
    }

    compileKotlin {
        dependsOn(generateKotlinI18nKeys)
    }

    test {
        useJUnitPlatform()
    }
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

javafx {
    version = "21"
    modules("javafx.controls", "javafx.fxml")
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
