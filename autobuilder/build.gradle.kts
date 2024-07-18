plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    alias(libs.plugins.ktlint)
    application
}

repositories {
    mavenCentral()
}

group = "me.chosante"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":common-lib"))
    implementation(project(":zenith-builder"))
    implementation(platform(libs.kotlinx.coroutine.bom))
    implementation(libs.kotlinx.coroutine.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("com.github.ajalt.mordant:mordant:2.4.0")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.23.1")
    implementation("org.slf4j:slf4j-api:2.0.13")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(
        libs.versions.jvm
            .get()
            .toInt()
    )
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("me.chosante.autobuilder.MainKt")
}

tasks {
    withType<Jar> {
        dependsOn(":common-lib:jar")
        dependsOn(":zenith-builder:jar")
        manifest {
            attributes["Main-Class"] = application.mainClass
        }
        archiveFileName.set("wakfu-autobuilder-cli.jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    }
}
