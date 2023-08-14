import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.target.HostManager.Companion.hostIsLinux
import org.jetbrains.kotlin.konan.target.HostManager.Companion.hostIsMac
import org.jetbrains.kotlin.konan.target.HostManager.Companion.hostIsMingw
import java.io.ByteArrayOutputStream

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("de.undercouch.download") version "5.4.0"
    application
}

group = "me.chosante"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":common-lib"))
    testImplementation(kotlin("test"))
    implementation("com.github.ajalt.clikt:clikt:4.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("me.tongfei:progressbar:0.9.5")
    implementation("com.github.kittinunf.fuel:fuel:2.3.1")
    implementation("com.github.kittinunf.fuel:fuel-coroutines:2.3.1")
    implementation("com.github.kittinunf.fuel:fuel-kotlinx-serialization:2.3.1")
    implementation("de.vandermeer:asciitable:0.3.2")
    implementation("io.github.oshai:kotlin-logging-jvm:5.0.0")
    implementation("org.slf4j:slf4j-simple:2.0.7")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "20"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "20"
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("me.chosante.autobuilder.MainKt")
}

tasks.withType<Jar> {
    dependsOn(":common-lib:jar")
    manifest {
        attributes["Main-Class"] = application.mainClass
    }
    archiveFileName.set("wakfu-autobuilder-cli.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks {
    val downloadWarpWrapperTask by registering {
        doLast {
            //Warp for Linux:
            if (hostIsLinux) {
                download.run {
                    src("https://github.com/dgiagio/warp/releases/download/v0.3.0/linux-x64.warp-packer")
                    dest(file("${buildDir.absolutePath}/warp/warp-packer"))
                    overwrite(false)
                }
            }
            //Warp for macOS:
            if (hostIsMac) {
                download.run {
                    src("https://github.com/dgiagio/warp/releases/download/v0.3.0/macos-x64.warp-packer")
                    dest(file("${buildDir.absolutePath}/warp/warp-packer"))
                    overwrite(false)
                }
            }
            //Warp for Windows:
            if (hostIsMingw) {
                download.run {
                    src("https://github.com/dgiagio/warp/releases/download/v0.3.0/windows-x64.warp-packer.exe")
                    dest(file("${buildDir.absolutePath}/warp/warp-packer.exe"))
                    overwrite(false)
                }
            }
        }
    }
    val createWarpBundleTask by registering {
        dependsOn("jar")
        doLast {
            val jdkDownloadBaseURL = "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.7+7"
            val linuxJreFilePath = "${buildDir.absolutePath}/jre/linux/jre.tar.gz"
            download.run {
                src("$jdkDownloadBaseURL/OpenJDK17U-jre_x64_linux_hotspot_17.0.7_7.tar.gz")
                dest(file(linuxJreFilePath))
                overwrite(false)
            }.also {
                val linuxWarpBundleDir = "${buildDir.absolutePath}/warp-bundle/linux"
                bundleWarpNecessaryFiles(linuxJreFilePath, linuxWarpBundleDir)
                File(linuxWarpBundleDir, "run.sh").writeText(
                    """
                    #!/usr/bin/env bash
                    HERE=${'$'}{BASH_SOURCE%/*}
                    "${'$'}HERE/bin/java" -jar "${'$'}HERE/wakfu-autobuilder-cli.jar" "${'$'}@"
                """.trimIndent()
                )
            }
            val macOsJreFilePath = "${buildDir.absolutePath}/jre/macos/jre.tar.gz"
            download.run {
                src("$jdkDownloadBaseURL/OpenJDK17U-jre_x64_mac_hotspot_17.0.7_7.tar.gz")
                dest(file(macOsJreFilePath))
                overwrite(false)
            }.also {
                val macosWarpBundlePathDir = "${buildDir.absolutePath}/warp-bundle/macos"
                bundleWarpNecessaryFiles(macOsJreFilePath, macosWarpBundlePathDir)

                File(macosWarpBundlePathDir, "run.sh").writeText(
                    """
                    #!/usr/bin/env bash
                    HERE=${'$'}{BASH_SOURCE%/*}
                    "${'$'}HERE/Contents/Home/bin/java" -jar "${'$'}HERE/wakfu-autobuilder-cli.jar" "${'$'}@"
                """.trimIndent()
                )
            }
            val windowsJreFilePath = "${buildDir.absolutePath}/jre/windows/jre.zip"
            download.run {
                src("$jdkDownloadBaseURL/OpenJDK17U-jre_x64_windows_hotspot_17.0.7_7.zip")
                dest(file(windowsJreFilePath))
                overwrite(false)
            }.also {
                val windowsWarpBundlePathDir = "${buildDir.absolutePath}/warp-bundle/windows"
                bundleWarpNecessaryFiles(windowsJreFilePath, windowsWarpBundlePathDir)

                File(windowsWarpBundlePathDir, "launcher.cmd").writeText(
                    """
                    @ECHO OFF
                    SETLOCAL
                    SET "JAVA_EXE=%~dp0\bin\java.exe"
                    SET "APP_JAR=%~dp0\wakfu-autobuilder-cli.jar"
                    CALL %JAVA_EXE% -jar %APP_JAR% %*
                    EXIT /B %ERRORLEVEL%
                """.trimIndent()
                )
            }
        }
    }

    /**
     * Builds native binaries for Linux, macOS and Windows.
     * The output will be in "$buildDir/make/".
     */
    val make by registering {
        dependsOn(downloadWarpWrapperTask, createWarpBundleTask)
        doLast {
            val cmd = when {
                hostIsMac -> "${buildDir.absolutePath}/warp/warp-packer"
                hostIsLinux -> "${buildDir.absolutePath}/warp/warp-packer"
                hostIsMingw -> "${buildDir.absolutePath}\\warp\\warp-packer.exe"
                else -> throw IllegalStateException("OS not supported for this task")
            }
            //Build Linux binary:
            exec {
                val outputFileLinux = file("${buildDir.absolutePath}/make/linux/wakfu-autobuild-cli")
                if (!outputFileLinux.parentFile.exists()) {
                    outputFileLinux.parentFile.mkdirs()
                }
                setWorkingDir("${buildDir.absolutePath}/warp/")
                setCommandLine(cmd)
                standardOutput = ByteArrayOutputStream()
                args(
                    "--arch", "linux-x64",
                    "--input_dir", "${buildDir.absolutePath}/warp-bundle/linux/",
                    "--exec", "${buildDir.absolutePath}/warp-bundle/linux/run.sh",
                    "--output", outputFileLinux.absolutePath
                )
            }
//            //Build macOS binary:
//            exec {
//                val outputFileMacOS = file("${buildDir.absolutePath}/make/macos/wakfu-autobuild-cli")
//                if (!outputFileMacOS.parentFile.exists()) {
//                    outputFileMacOS.parentFile.mkdirs()
//                }
//                setWorkingDir("${buildDir.absolutePath}/warp/")
//                setCommandLine(cmd)
//                standardOutput = ByteArrayOutputStream()
//                args(
//                    "--arch", "macos-x64",
//                    "--input_dir", "${buildDir.absolutePath}/warp-bundle/macos/",
//                    "--exec", "${buildDir.absolutePath}/warp-bundle/macos/run.sh",
//                    "--output", outputFileMacOS.absolutePath
//                )
//            }
            //Build Windows binary:
            exec {
                val outputFileWindows = file("${buildDir.absolutePath}/make/windows/wakfu-autobuild-cli.exe")
                if (!outputFileWindows.parentFile.exists()) {
                    outputFileWindows.parentFile.mkdirs()
                }
                setWorkingDir("${buildDir.absolutePath}/warp/")
                setCommandLine(cmd)
                standardOutput = ByteArrayOutputStream()
                args(
                    "--arch", "windows-x64",
                    "--input_dir", "${buildDir.absolutePath}/warp-bundle/windows/",
                    "--exec", "${buildDir.absolutePath}/warp-bundle/windows/launcher.cmd",
                    "--output", outputFileWindows.absolutePath
                )
            }
        }
    }
}

fun bundleWarpNecessaryFiles(jreFilePath: String, destinationBundlePath: String): String {
    val unzipDir = File("$buildDir/unzipTmp")
    copy {
        when {
            jreFilePath.endsWith(".tar.gz") -> from(tarTree(resources.gzip((jreFilePath))))
            jreFilePath.endsWith(".zip") -> from(zipTree(jreFilePath))
            else -> from(jreFilePath)
        }
        into(unzipDir)
    }
    val rootZipDir = unzipDir.listFiles()?.get(0)!!
    copy {
        from(fileTree(rootZipDir))
        into(destinationBundlePath)
    }
    unzipDir.deleteRecursively()

    copy {
        from(fileTree("${buildDir.absolutePath}/libs/"))
        into(destinationBundlePath)
    }
    return destinationBundlePath
}