import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.util.Properties

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose)
    id("dev.hydraulic.conveyor") version "2.0"
    alias(libs.plugins.ktlint)
}

group = "me.chosante"
version = "1.19.0" // x-release-please-version

repositories {
    mavenCentral()
    google()
}

// OR-Tools ships one native jar per OS/arch (all pulled in transitively via :autobuilder). Keep them
// off the shared classpath and attach each to its Conveyor machine config, so every installer bundles
// only the native it needs instead of all five.
val ortoolsVersion = libs.versions.ortools.get()

dependencies {
    implementation(project(":autobuilder")) {
        exclude(group = "com.google.ortools", module = "ortools-darwin-aarch64")
        exclude(group = "com.google.ortools", module = "ortools-darwin-x86-64")
        exclude(group = "com.google.ortools", module = "ortools-linux-aarch64")
        exclude(group = "com.google.ortools", module = "ortools-linux-x86-64")
        exclude(group = "com.google.ortools", module = "ortools-win32-x86-64")
    }
    implementation(project(":zenith-builder"))
    implementation(project(":common-lib"))
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.desktop.currentOs) // host Compose/Skiko for :gui-compose:run / :test
    implementation(libs.compose.material3)
    implementation(platform(libs.kotlinx.coroutine.bom))
    implementation(libs.kotlinx.coroutine.swing)
    implementation(libs.kotlinx.coroutine.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotlinx.coroutine.test)
    testImplementation(libs.compose.ui.test.junit4)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Per-machine artifacts Conveyor packages for each target: that OS's Compose Desktop (Skiko) +
    // OR-Tools native. Without these, Conveyor falls back to the build host's `currentOs` Skiko for
    // every OS (ships e.g. macOS Skiko in the Windows/Linux installers → crash). Resolving these
    // cleanly requires Skiko ≥ 0.144 (Compose ≥ 1.11), which drops the awt/android variant ambiguity
    // for JVM consumers (SKIKO-1013). The Conveyor plugin routes whichever matches the build host
    // into `implementation`, so local dev keeps working.
    "macAarch64"(libs.compose.desktop.macos.arm64)
    "macAarch64"("com.google.ortools:ortools-darwin-aarch64:$ortoolsVersion")
    "macAmd64"(libs.compose.desktop.macos.x64)
    "macAmd64"("com.google.ortools:ortools-darwin-x86-64:$ortoolsVersion")
    "linuxAmd64"(libs.compose.desktop.linux.x64)
    "linuxAmd64"("com.google.ortools:ortools-linux-x86-64:$ortoolsVersion")
    "windowsAmd64"(libs.compose.desktop.windows.x64)
    "windowsAmd64"("com.google.ortools:ortools-win32-x86-64:$ortoolsVersion")
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

// What's-new resources: the app version (for the once-per-version gate), the player-facing release
// notes (generateReleaseNotes, below) and the release-please CHANGELOG, which the in-app "What's new"
// dialog (ui/state/WhatsNew.kt) still renders for the releases before those notes existed (≤ 1.11).
// The changelog only exists once the first release PR has merged, so its copy tolerates absence.
val generateVersionResource =
    tasks.register("generateVersionResource") {
        val appVersion = version.toString()
        val outputDir = layout.buildDirectory.dir("generated-resources/version")
        inputs.property("appVersion", appVersion)
        outputs.dir(outputDir)
        doLast {
            outputDir
                .get()
                .asFile
                .resolve("app-version.txt")
                .writeText(appVersion)
        }
    }

// Player-facing release notes: every user-visible change adds one small UTF-8 note (EN + FR, ES optional) under
// <root>/changes/unreleased/, which the release PR files under changes/<version>/ (scripts/changesets/). This compiles
// them all into the release-notes.json the What's new dialog shows in the UI language; unreleased notes are labelled
// with the version being built. CONTRIBUTING.md › Release notes has the format; ChangeFragmentsTest enforces it.
val changesDir: File = rootDir.resolve("changes")
val generateReleaseNotes =
    tasks.register("generateReleaseNotes") {
        val appVersion = version.toString()
        val notes = fileTree(changesDir) { include("*/*.properties") }
        val outputDir = layout.buildDirectory.dir("generated-resources/release-notes")
        inputs.property("appVersion", appVersion)
        inputs
            .files(notes)
            .withPropertyName("releaseNotes")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(outputDir)
        doLast {
            val types = listOf("feat", "fix", "perf")
            val semver = Regex("""(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.-]+)?""")

            // Sort key: numeric parts, then 1 for a release / 0 for a pre-release ("1.13.0-dev" < "1.13.0").
            fun versionKey(version: String): List<Int> {
                val (major, minor, patch, preRelease) =
                    semver.matchEntire(version)?.destructured ?: throw GradleException("'$version' is not a version")
                return listOf(major.toInt(), minor.toInt(), patch.toInt(), if (preRelease.isEmpty()) 1 else 0)
            }

            fun versionOf(note: File): String =
                when (val dir = note.parentFile.name) {
                    "unreleased" -> appVersion
                    else -> dir.takeIf { semver.matches(it) } ?: throw GradleException("$note: a note lives in changes/unreleased/ or changes/<version>/")
                }

            fun parse(note: File): Map<String, Any?> {
                val fields = Properties().apply { note.reader(Charsets.UTF_8).use { load(it) } }
                val type = fields.getProperty("type")?.trim()
                if (type !in types) throw GradleException("$note: 'type' must be one of $types, not '$type'")
                // Every other key but `scope` is a language code: en + fr required, es (or any other) optional.
                val text = (fields.stringPropertyNames() - setOf("type", "scope")).sorted().associateWith { fields.getProperty(it).trim() }
                for (language in listOf("en", "fr")) {
                    if (text[language].isNullOrEmpty()) throw GradleException("$note: '$language' is required")
                }
                val scope = fields.getProperty("scope")?.trim()?.takeIf { it.isNotEmpty() }
                return mapOf("id" to note.nameWithoutExtension, "type" to type, "scope" to scope, "text" to text)
            }

            val newestFirst =
                Comparator<String> { a, b ->
                    versionKey(b).zip(versionKey(a)).firstNotNullOfOrNull { (x, y) -> x.compareTo(y).takeIf { it != 0 } } ?: 0
                }
            // Within a version: New, then Fixes, then Faster; each in file-name order (name notes by topic).
            val versions =
                notes.files
                    .groupBy(::versionOf, ::parse)
                    .toSortedMap(newestFirst)
                    .map { (version, parsed) ->
                        mapOf("version" to version, "notes" to parsed.sortedWith(compareBy({ types.indexOf(it["type"]) }, { it["id"] as String })))
                    }
            outputDir
                .get()
                .asFile
                .resolve("release-notes.json")
                .writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf("versions" to versions))) + "\n")
        }
    }

sourceSets {
    main {
        resources.srcDir(generateVersionResource)
        resources.srcDir(generateReleaseNotes)
    }
}

tasks.processResources {
    from(rootDir.resolve("CHANGELOG.md"))
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
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                // Silence protobuf-java's deprecated-Unsafe warnings (transitive via OR-Tools).
                "--sun-misc-unsafe-memory-access=allow"
            )
    }
}

tasks.test {
    useJUnitPlatform()
    // ChangeFragmentsTest validates every release note under <root>/changes: tracked as an input so a new or edited note
    // re-runs it instead of leaving the test task up to date.
    inputs
        .files(fileTree(changesDir))
        .withPropertyName("releaseNoteSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("wakfu.changesDir", changesDir.absolutePath)
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        // Silence protobuf-java's deprecated-Unsafe warnings (transitive via OR-Tools).
        "--sun-misc-unsafe-memory-access=allow"
    )
}
