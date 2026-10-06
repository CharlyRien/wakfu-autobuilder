import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.tasks.JavaExec
import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File
import java.net.URLClassLoader

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
}

val projectJvmLanguageVersion = JavaLanguageVersion.of(libs.versions.jvm.get())

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    languageVersion.set(projectJvmLanguageVersion)
}

subprojects {
    // Jupiter only logs a warning for non-void @Test methods and silently omits them. Inspect the
    // compiled signatures before discovery, so inferred Kotlin expression-body returns fail CI.
    tasks.withType<Test>().configureEach {
        // A failed test's assertion message and stack in the console: CI keeps no JUnit XML for the per-push shards, and
        // Gradle's default prints only the exception class and one line, which hides WHY it failed.
        testLogging {
            events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        doFirst {
            URLClassLoader(classpath.files.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
                val invalid =
                    testClassesDirs.files.flatMap { root ->
                        root.walkTopDown().filter { it.isFile && it.extension == "class" }.flatMap { file ->
                            val name = file.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
                            Class.forName(name, false, loader).declaredMethods.asSequence()
                                .filter { method ->
                                    method.annotations.any { it.annotationClass.java.name == "org.junit.jupiter.api.Test" } &&
                                        method.returnType != Void.TYPE
                                }.map { "$name.${it.name}: ${it.returnType.typeName}" }
                        }.toList()
                    }
                check(invalid.isEmpty()) {
                    "JUnit @Test methods must return void/Unit. Add an explicit : Unit to expression bodies:\n" + invalid.joinToString("\n")
                }
            }
        }
    }

    plugins.withType<JavaBasePlugin> {
        // Enable native access (OR-Tools) for Gradle-launched apps. The JVM version itself is fixed
        // by each module's `kotlin { jvmToolchain(...) }`; we intentionally do NOT set javaLauncher
        // here. Forcing it conflicts with the `executable` the IDE injects when launching a task
        // ("Toolchain from `executable` does not match toolchain from `javaLauncher`"), which broke
        // running apps (e.g. equipments-extractor) from the IDE.
        tasks.withType<JavaExec>().configureEach {
            jvmArgs(
                "--enable-native-access=ALL-UNNAMED",
                // Silence protobuf-java's "deprecated sun.misc.Unsafe::arrayBaseOffset" warnings
                // (transitive via OR-Tools) on launched apps (CLI / equipments-extractor).
                "--sun-misc-unsafe-memory-access=allow"
            )
        }
    }
}

tasks.register<Exec>("conveyorRun") {
    group = "conveyor"
    description = "Run the wakfu autobuilder gui through conveyor"
    workingDir = file("$projectDir/gui-compose")
    dependsOn(":gui-compose:build")

    commandLine("bash", "-c", "conveyor -f conveyor-local.conf run")
}
