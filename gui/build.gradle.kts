import java.nio.file.Files
import java.util.Properties

plugins {
    kotlin("jvm")
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("dev.hydraulic.conveyor") version "1.12"
    alias(libs.plugins.ktlint)
    application
}

group = "me.chosante"
version = "0.2.0"

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
