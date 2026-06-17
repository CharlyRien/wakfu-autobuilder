import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.konan.file.unzipTo
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose)
    id("dev.hydraulic.conveyor") version "2.0"
    alias(libs.plugins.ktlint)
}

group = "me.chosante"
version = "1.5.0" // x-release-please-version

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
    testImplementation(compose.desktop.uiTestJUnit4) // Compose UI test harness (runComposeUiTest) for widget tests
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

// What's-new resources: the app version (for the once-per-version gate) and the release-please
// CHANGELOG rendered by the in-app "What's new" dialog (ui/state/WhatsNew.kt). The changelog only
// exists once the first release PR has merged, so its copy tolerates absence.
val generateVersionResource by tasks.registering {
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

sourceSets {
    main {
        resources.srcDir(generateVersionResource)
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
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
        // Silence protobuf-java's deprecated-Unsafe warnings (transitive via OR-Tools).
        "--sun-misc-unsafe-memory-access=allow"
    )
}

tasks.register("generateAssets") {
    description =
        """
        Downloads the artwork bundled with the Compose GUI from the community
        https://github.com/Vertylo/wakassets repository. Hits the network, so it is run on demand
        (not part of the normal build) and the resulting PNGs are committed alongside the others.
        
        It copies these sets into src/main/resources/assets:
          - items/  filtered to the guiIds referenced by the current equipments JSON (large set)
          - icons/  the HUD stat icons used to render characteristics + skill-tree lines (complete set)
          - spells/ filtered to the iconIds referenced by the current spells JSON (covers active spells
                    and passives — both are spells; named by gfxId)
          - states/ filtered to the appliedStateIds referenced by the passives JSON (buff/state icons)
          - monsters/ filtered to the gfx (sprite) ids referenced by the current bestiary JSON (boss picker)
          - breeds/ class artwork keyed by CharacterClass.breedId — icon/, illustration/ (T-pose,
                    male variant), background/ (used on build cards, the TopBar and the compare view)
        Existing files are never overwritten.
        """.trimIndent()
    group = "assets"

    val repoUrl = "https://github.com/Vertylo/wakassets/archive/refs/heads/main.zip"

    doLast {
        val assetsDir = file("src/main/resources/assets").apply { mkdirs() }

        // guiIds referenced by the current equipments JSON shipped in the autobuilder module
        @Suppress("UNCHECKED_CAST")
        val guiIdsFromCurrentEquipmentJson =
            rootProject.projectDir
                .resolve("autobuilder/src/main/resources")
                .listFiles()
                ?.filter { it.extension == "json" }
                ?.minByOrNull { it.nameWithoutExtension }
                ?.let {
                    val items = groovy.json.JsonSlurper().parseText(it.readText()) as List<Map<String, Any>>
                    items.map { item -> (item["guiId"] as Int).toString() }.toSet()
                } ?: emptySet()

        // spell icon gfxIds + passive-applied state ids referenced by the current spell datasets.
        // Resolve the dataset JSON deterministically (highest version if several coexist) and FAIL LOUDLY
        // if it is missing/renamed — an empty id-set would otherwise silently copy 0 icons on a green build.
        // Numbers are read as Number (JsonSlurper boxes ints as Integer/Long/BigInteger by magnitude).
        val autobuilderResources = rootProject.projectDir.resolve("autobuilder/src/main/resources")

        @Suppress("UNCHECKED_CAST")
        fun readResourceJson(prefix: String): List<Map<String, Any>> {
            val file =
                autobuilderResources
                    .listFiles()
                    ?.filter { it.extension == "json" && it.name.startsWith(prefix) }
                    ?.maxByOrNull { it.nameWithoutExtension }
                    ?: error("generateAssets: no '$prefix*.json' under $autobuilderResources — run the extractor first?")
            return groovy.json.JsonSlurper().parseText(file.readText()) as List<Map<String, Any>>
        }

        val spellIconIds =
            readResourceJson("spells-v").mapNotNull { (it["iconId"] as? Number)?.toInt()?.toString() }.toSet()
        val passiveStateIds =
            readResourceJson("spell-passives-v")
                .flatMap { entry -> (entry["appliedStateIds"] as? List<*> ?: emptyList<Any>()).map { (it as Number).toInt().toString() } }
                .toSet()
        // gfx (sprite) ids referenced by the current bestiary JSON → the monster icons the boss picker needs.
        val monsterGfxIds =
            readResourceJson("monsters-v").mapNotNull { (it["gfx"] as? Number)?.toInt()?.toString() }.toSet()

        // download + unzip the wakassets repository into a temp directory
        val destinationFile = createTempFile("wakassets", ".zip").toFile()
        downloadRepositoryAsZip(repoUrl, destinationFile)
        val unzippedTempDirectory = createTempDirectory()
        destinationFile.toPath().unzipTo(unzippedTempDirectory)
        val wakassetsRoot = unzippedTempDirectory.resolve("wakassets-main").toFile()

        // copy every PNG of a wakassets subdirectory into assets/<name>, skipping already-present files
        fun copyAssetDir(
            sourceDirName: String,
            keep: (File) -> Boolean = { true },
        ) {
            val targetDir = assetsDir.resolve(sourceDirName).apply { mkdirs() }
            var copied = 0
            wakassetsRoot
                .resolve(sourceDirName)
                .listFiles()
                ?.filter(keep)
                ?.forEach { asset ->
                    val destination = targetDir.resolve(asset.name)
                    if (!destination.exists()) {
                        asset.copyTo(destination)
                        copied++
                    }
                }
            logger.lifecycle("generateAssets: $sourceDirName -> $copied new file(s) added")
        }

        copyAssetDir("items") { it.nameWithoutExtension in guiIdsFromCurrentEquipmentJson }
        copyAssetDir("icons")
        copyAssetDir("spells") { it.nameWithoutExtension in spellIconIds }
        copyAssetDir("states") { it.nameWithoutExtension in passiveStateIds }
        copyAssetDir("monsters") { it.nameWithoutExtension in monsterGfxIds }

        // Class ("breed") artwork, keyed by CharacterClass.breedId, baked under assets/breeds/.
        // Illustrations are id*10 (male) / id*10+1 (female) in wakassets; we keep the male variant and
        // re-key it to the plain breedId so BreedAssets can resolve it directly. There is no breed 17.
        val breedIds = (1..16) + listOf(18, 19)

        fun copyBreedAsset(
            kind: String,
            sourceDirName: String,
            wakassetsId: (Int) -> Int,
        ) {
            val targetDir = assetsDir.resolve("breeds/$kind").apply { mkdirs() }
            var copied = 0
            breedIds.forEach { breedId ->
                val source = wakassetsRoot.resolve("$sourceDirName/${wakassetsId(breedId)}.png")
                val destination = targetDir.resolve("$breedId.png")
                if (source.exists() && !destination.exists()) {
                    source.copyTo(destination)
                    copied++
                }
            }
            logger.lifecycle("generateAssets: breeds/$kind -> $copied new file(s) added")
        }
        copyBreedAsset("icon", "breedsIcons") { it }
        copyBreedAsset("illustration", "breedsIllusrations") { it * 10 }
        copyBreedAsset("background", "breedsBackgrounds") { it }

        destinationFile.delete()
        unzippedTempDirectory.toFile().deleteRecursively()
    }
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
