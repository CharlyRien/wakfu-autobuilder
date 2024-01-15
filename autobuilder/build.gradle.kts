import java.io.ByteArrayOutputStream
import org.jetbrains.kotlin.konan.target.HostManager.Companion.hostIsLinux
import org.jetbrains.kotlin.konan.target.HostManager.Companion.hostIsMac
import org.jetbrains.kotlin.konan.target.HostManager.Companion.hostIsMingw

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("de.undercouch.download") version "5.5.0"
    alias(libs.plugins.ktlint)
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
    implementation(platform("org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.7.3"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.fuel)
    implementation("com.github.ajalt.clikt:clikt:4.2.2")
    implementation("me.tongfei:progressbar:0.10.0")
    implementation("de.vandermeer:asciitable:0.3.2")
    implementation("io.github.oshai:kotlin-logging-jvm:6.0.2")
    implementation("org.slf4j:slf4j-simple:2.0.11")
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
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
        manifest {
            attributes["Main-Class"] = application.mainClass
        }
        archiveFileName.set("wakfu-autobuilder-cli.jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    }

    val buildDirectoryAbsolutePath = layout.buildDirectory.get().asFile.absolutePath

    val downloadWarpWrapperTask by registering {
        doLast {
            // Warp for Linux:
            val baseWarpPackerUrl = "https://github.com/fintermobilityas/warp/releases/download/v0.4.5"
            if (hostIsLinux) {
                download.run {
                    src("$baseWarpPackerUrl/linux-x64.warp-packer")
                    dest(file("$buildDirectoryAbsolutePath/warp/warp-packer"))
                    overwrite(false)
                }
            }
            // Warp for macOS:
            if (hostIsMac) {
                download.run {
                    src("$baseWarpPackerUrl/macos-x64.warp-packer")
                    dest(file("$buildDirectoryAbsolutePath/warp/warp-packer"))
                    overwrite(false)
                }
            }
            // Warp for Windows:
            if (hostIsMingw) {
                download.run {
                    src("$baseWarpPackerUrl/windows-x64.warp-packer.exe")
                    dest(file("$buildDirectoryAbsolutePath/warp/warp-packer.exe"))
                    overwrite(false)
                }
            }
        }
    }
    val createWarpBundle by registering {
        dependsOn("jar")
        doLast {
            val jdkDownloadBaseURL = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21+35"
            val linuxJreFilePath = "$buildDirectoryAbsolutePath/jre/linux/jre.tar.gz"
            download.run {
                src("$jdkDownloadBaseURL/OpenJDK21U-jre_x64_linux_hotspot_21_35.tar.gz")
                dest(file(linuxJreFilePath))
                overwrite(false)
            }.also {
                val linuxWarpBundleDir = "$buildDirectoryAbsolutePath/warp-bundle/linux"
                bundleWarpNecessaryFiles(linuxJreFilePath, linuxWarpBundleDir)
                File(linuxWarpBundleDir, "exec").writeText(
                    """
                    #!/bin/sh
                    
                    DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" ; pwd -P)"
                    JAVA_EXE=${'$'}DIR/bin/java
                    chmod +x -R "${'$'}DIR"

                    exec "${'$'}JAVA_EXE" -jar "${'$'}DIR/wakfu-autobuilder-cli.jar" "${'$'}@"
                    """.trimIndent()
                )
            }
            val macOsJreFilePath = "$buildDirectoryAbsolutePath/jre/macos/jre.tar.gz"
            download.run {
                src("$jdkDownloadBaseURL/OpenJDK21U-jre_x64_mac_hotspot_21_35.tar.gz")
                dest(file(macOsJreFilePath))
                overwrite(false)
            }.also {
                val macosWarpBundlePathDir = "$buildDirectoryAbsolutePath/warp-bundle/macos"
                bundleWarpNecessaryFiles(macOsJreFilePath, macosWarpBundlePathDir)

                File(macosWarpBundlePathDir, "exec").writeText(
                    """
                    #!/bin/sh

                    DIR="${'$'}(cd "${'$'}(dirname "${'$'}0")" ; pwd -P)"
                    JAVA_EXE=${'$'}DIR/Contents/Home/bin/java
                    chmod +x -R "${'$'}JAVA_EXE"

                    exec "${'$'}JAVA_EXE" -jar "${'$'}DIR/wakfu-autobuilder-cli.jar" "${'$'}@"
                    """.trimIndent()
                )
            }
            val windowsJreFilePath = "$buildDirectoryAbsolutePath/jre/windows/jre.zip"
            download.run {
                src("$jdkDownloadBaseURL/OpenJDK21U-jre_x64_windows_hotspot_21_35.zip")
                dest(file(windowsJreFilePath))
                overwrite(false)
            }.also {
                val windowsWarpBundlePathDir = "$buildDirectoryAbsolutePath/warp-bundle/windows"
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
        dependsOn(downloadWarpWrapperTask, createWarpBundle)
        doLast {
            val cmd = when {
                hostIsMac -> "$buildDirectoryAbsolutePath/warp/warp-packer"
                hostIsLinux -> "$buildDirectoryAbsolutePath/warp/warp-packer"
                hostIsMingw -> "$buildDirectoryAbsolutePath/warp/warp-packer.exe"
                else -> throw IllegalStateException("OS not supported for this task")
            }

            // Build Linux binary:
            exec {
                val outputFileLinux = layout.buildDirectory.file("/make/linux/wakfu-autobuild-cli").get().asFile
                if (!outputFileLinux.parentFile.exists()) {
                    outputFileLinux.parentFile.mkdirs()
                }
                setWorkingDir(layout.buildDirectory.dir("warp-bundle/linux").get())

                setCommandLine(cmd)
                standardOutput = ByteArrayOutputStream()
                args(
                    "--arch",
                    "linux-x64",
                    "--input_dir",
                    ".",
                    "--exec",
                    "exec",
                    "--output",
                    outputFileLinux.absolutePath
                )
            }
            // Build macOS binary:
            exec {
                val outputFileMacOS = file("$buildDirectoryAbsolutePath/make/macos/wakfu-autobuild-cli")
                if (!outputFileMacOS.parentFile.exists()) {
                    outputFileMacOS.parentFile.mkdirs()
                }
                setWorkingDir(layout.buildDirectory.dir("warp-bundle/macos").get())
                setCommandLine(cmd)
                standardOutput = ByteArrayOutputStream()
                args(
                    "--arch",
                    "macos-x64",
                    "--input_dir",
                    ".",
                    "--exec",
                    "exec",
                    "--output",
                    outputFileMacOS.absolutePath
                )
            }
            // Build Windows binary:
            exec {
                val outputFileWindows = file("$buildDirectoryAbsolutePath/make/windows/wakfu-autobuild-cli.exe")
                if (!outputFileWindows.parentFile.exists()) {
                    outputFileWindows.parentFile.mkdirs()
                }
                setWorkingDir(layout.buildDirectory.dir("warp-bundle/windows").get())
                setCommandLine(cmd)
                standardOutput = ByteArrayOutputStream()
                args(
                    "--arch",
                    "windows-x64",
                    "--input_dir",
                    ".",
                    "--exec",
                    "launcher.cmd",
                    "--output",
                    outputFileWindows.absolutePath
                )
            }
        }
    }
}

fun bundleWarpNecessaryFiles(jreFilePath: String, destinationBundlePath: String): String {
    val unzipDir = layout.buildDirectory.dir("unzipTmp").get().asFile
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
        from(layout.buildDirectory.dir("libs").get().asFileTree)
        into(destinationBundlePath)
    }
    return destinationBundlePath
}
