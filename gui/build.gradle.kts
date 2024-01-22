plugins {
    kotlin("jvm")
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("dev.hydraulic.conveyor") version "1.8"
    alias(libs.plugins.ktlint)
    application
}

group = "me.chosante"
version = "0.0.3"

repositories {
    mavenCentral()
}

application {
    mainClass = "me.chosante.WakfuAutobuilderGUIKt"
}

dependencies {
    implementation(project(":autobuilder"))
    implementation(project(":common-lib"))
    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3"))
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-feather-pack:12.3.1")
    testImplementation(platform("org.junit:junit-bom:5.10.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}

javafx {
    version = "21"
    modules("javafx.controls", "javafx.fxml")
}
