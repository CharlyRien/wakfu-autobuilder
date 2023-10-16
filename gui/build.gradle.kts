plugins {
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("org.jlleitschuh.gradle.ktlint")
    kotlin("jvm")
    id("dev.hydraulic.conveyor") version "1.6"
    application
}

group = "me.chosante"
version = "0.0.1"

repositories {
    mavenCentral()
}

application {
    mainClass = "me.chosante.WakfuAutobuilderGUIKt"
}

dependencies {
    implementation(project(":autobuilder"))
    implementation(project(":common-lib"))
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-feather-pack:12.3.1")
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

javafx {
    version = "21"
    modules("javafx.controls", "javafx.fxml")
}
