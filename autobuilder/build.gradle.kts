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
    implementation("com.github.ajalt.clikt:clikt:5.1.0")
    implementation("com.github.ajalt.mordant:mordant:3.1.0")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.4")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.26.1")
    implementation("org.slf4j:slf4j-api:2.0.19")
    implementation(libs.ortools.java)
    testImplementation(libs.assertj.core)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(
        libs.versions.jvm
            .get()
            .toInt()
    )
}

// OR-Tools loads a native library at runtime, so every test JVM needs these (see CLAUDE.md §9).
val orToolsTestJvmArgs =
    listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        // Silence the "terminally deprecated sun.misc.Unsafe::arrayBaseOffset" warnings emitted by
        // protobuf-java (pulled in transitively by OR-Tools) — nothing we can fix in our own code.
        "--sun-misc-unsafe-memory-access=allow"
    )

tasks.test {
    // The heavy full-pool OR-Tools OPTIMAL *proof* tests are tagged @Tag("slow") and EXCLUDED here: each
    // requests 8 solver workers and burns a large deterministic-time budget, so on a 2-core CI runner they
    // oversubscribe and take ~15 min. The default `test` (every push/PR) stays fast; they run via `slowTest`
    // (nightly + on-demand — see .github/workflows/build.yml).
    useJUnitPlatform { excludeTags("slow") }
    jvmArgs(orToolsTestJvmArgs)
    // Manual measurement harnesses only (e.g. the M3-v2 DP at fine grids): lets a local run raise
    // the test-worker heap without touching CI (unset ⇒ Gradle's default).
    System.getenv("WAKFU_TEST_MAX_HEAP")?.let { maxHeapSize = it }
    // Manual measurement harnesses only: extra test-JVM args, e.g. "-XX:ActiveProcessorCount=4" to mimic
    // a 4-core laptop or a JFR recording. Unset ⇒ nothing changes.
    System.getenv("WAKFU_TEST_JVM_ARGS")?.let { extra -> jvmArgs(extra.split(' ').filter { it.isNotBlank() }) }
}

tasks.register<Test>("slowTest") {
    description = "Runs ONLY the @Tag(\"slow\") tests (the heavy full-pool OR-Tools OPTIMAL proofs)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("slow") }
    jvmArgs(orToolsTestJvmArgs)
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
