import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import java.awt.image.BufferedImage
import java.util.zip.ZipFile
import javax.imageio.ImageIO

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose)
    id("dev.hydraulic.conveyor") version "2.0"
    alias(libs.plugins.ktlint)
}

group = "me.chosante"
version = "1.8.0" // x-release-please-version

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

// What's-new resources: the app version (for the once-per-version gate) and the release-please
// CHANGELOG rendered by the in-app "What's new" dialog (ui/state/WhatsNew.kt). The changelog only
// exists once the first release PR has merged, so its copy tolerates absence.
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
        Extracts the GUI artwork from the LOCAL Wakfu client (contents/gui_jar/gui.jar) — the official,
        version-matched icon source (64x64 TGA, keyed by the same ids our data uses; the community
        Vertylo/wakassets repo was just a PNG mirror of this). Maintainer-local (needs the install), run on
        demand; the resulting PNGs are committed. Pass -Pwakfu.install=<path> (default /Applications/Ankama/Wakfu).

        It converts these sets into src/main/resources/assets (TGA -> PNG):
          - items/     filtered to the guiIds referenced by the current equipments JSON (also covers rune items)
          - spells/    filtered to the iconIds/gfxIds referenced by the current spells + passives JSON
          - states/    filtered to the appliedStateIds referenced by the passives JSON (buff/state icons)
          - breeds/    class artwork keyed by CharacterClass.breedId — icon/, illustration/ (male), background/
          - itemTypes/ equipment-slot icons re-sourced by their numeric ids (miscellaneous/itemTypes)
          - runes/     the 3 socket-colour shards (theme/images/shard{Red,Green,Blue}Full)
          - icons/     the 36 HUD stat icons mapped to miscellaneous/characteristics (by Characteristic)
        NOT extracted here (stay committed-static): monster portraits (200x200 renders; the client only keys
        monsters by gfx as 132x41 banners) and the 8 rarity gems in assets/rarities/. The gems ARE grounded on
        official client art, but via a separate maintainer script (scripts/generate-rarity-gems.py): gui.jar
        only ships a faceted gem for epic + relic (theme/images/pictos/Rarity{Epic,Relic}.tga), so epic/relic
        are copied verbatim and the other six are derived by recolouring the official epic gem to each rarity's
        in-game hue (the faceted shape + shading are the official gem's; only the colour changes).
        Existing files are never overwritten, except sets that opt in (itemTypes/runes/icons re-source from the client).
        """.trimIndent()
    group = "assets"

    doLast {
        val assetsDir = file("src/main/resources/assets").apply { mkdirs() }

        // guiIds referenced by the current equipments JSON shipped in the autobuilder module
        @Suppress("UNCHECKED_CAST")
        val guiIdsFromCurrentEquipmentJson =
            rootProject.projectDir
                .resolve("autobuilder/src/main/resources/equipments.json")
                .takeIf { it.isFile }
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
        fun readResourceJson(name: String): List<Map<String, Any>> {
            val file = autobuilderResources.resolve("$name.json")
            if (!file.isFile) error("generateAssets: missing ${file.name} under $autobuilderResources — run the extractor first?")
            return groovy.json.JsonSlurper().parseText(file.readText()) as List<Map<String, Any>>
        }

        val spellIconIds =
            readResourceJson("spells").mapNotNull { (it["iconId"] as? Number)?.toInt()?.toString() }.toSet()
        // Passive icons are spell sprites too — keyed by the passive's gfxId — and come from the same
        // gui.jar `icons/spells/64` set, so they are unioned into the spells filter below (the GUI renders
        // the chosen passives with their own icon). `states/` still covers the buff/state icons separately.
        val passiveGfxIds =
            readResourceJson("spell-passives").mapNotNull { (it["gfxId"] as? Number)?.toInt()?.toString() }.toSet()
        val passiveStateIds =
            readResourceJson("spell-passives")
                .flatMap { entry -> (entry["appliedStateIds"] as? List<*> ?: emptyList<Any>()).map { (it as Number).toInt().toString() } }
                .toSet()

        // The local client's GUI jar holds every icon as a 64x64 TGA keyed by the same ids as our data.
        val install = (project.findProperty("wakfu.install") as String?) ?: "/Applications/Ankama/Wakfu"
        val guiJar = file("$install/contents/gui_jar/gui.jar")
        if (!guiJar.isFile) error("generateAssets: gui.jar not found at $guiJar — pass -Pwakfu.install=<path-to-Wakfu>.")

        ZipFile(guiJar).use { zip ->
            // Convert the gui.jar `<jarDir>/<id>.tga` icons named by `ids` into assets/<category>/<id>.png.
            fun extract(
                category: String,
                jarDir: String,
                ids: Iterable<String>,
                overwrite: Boolean = false,
            ) {
                val targetDir = assetsDir.resolve(category).apply { mkdirs() }
                var copied = 0
                ids.toSet().forEach { id ->
                    val destination = targetDir.resolve("$id.png")
                    if (!overwrite && destination.exists()) return@forEach
                    val entry = zip.getEntry("$jarDir/$id.tga") ?: return@forEach
                    val image = zip.getInputStream(entry).use { tgaToImage(it.readBytes()) }
                    ImageIO.write(image, "png", destination)
                    copied++
                }
                logger.lifecycle("generateAssets: $category -> $copied file(s) from gui.jar")
            }

            extract("items", "icons/items/64", guiIdsFromCurrentEquipmentJson) // also covers runes (item shards)
            extract("spells", "icons/spells/64", spellIconIds + passiveGfxIds)
            extract("states", "icons/states", passiveStateIds)

            // Equipment-slot / item-type icons (keyed by Ankama's numeric itemType id), re-sourced from the
            // official client (overwrite=true, so a client bump refreshes them). Only the 14 ids that back the
            // ItemType enum are kept now — they are the empty-paperdoll-slot silhouettes (see DollSlots.kt);
            // the ~95 other dormant type icons were dropped. The off-hand id 112 is a real equippable slot but
            // gui.jar ships no miscellaneous/itemTypes/112.tga for it, so its icon stays committed-static (the
            // only one). Derive the id set from the icons we already ship.
            val itemTypeIds =
                assetsDir.resolve("itemTypes").listFiles { f -> f.extension == "png" }?.map { it.nameWithoutExtension } ?: emptyList()
            extract("itemTypes", "miscellaneous/itemTypes", itemTypeIds, overwrite = true)

            // The 3 rune socket-colour shards: same art as the client's, but our target names differ from the
            // gui.jar entry stems, so map them explicitly (the rune ITEM icons come via the items extract above).
            fun extractNamed(
                category: String,
                mapping: Map<String, String>,
            ) {
                val targetDir = assetsDir.resolve(category).apply { mkdirs() }
                var copied = 0
                mapping.forEach { (target, jarEntry) ->
                    val entry = zip.getEntry("$jarEntry.tga") ?: return@forEach
                    val image = zip.getInputStream(entry).use { tgaToImage(it.readBytes()) }
                    ImageIO.write(image, "png", targetDir.resolve("$target.png"))
                    copied++
                }
                logger.lifecycle("generateAssets: $category -> $copied file(s) from gui.jar")
            }
            extractNamed(
                "runes",
                mapOf(
                    "shard_red" to "theme/images/shardRedFull",
                    "shard_green" to "theme/images/shardGreenFull",
                    "shard_blue" to "theme/images/shardBlueFull"
                )
            )

            // HUD stat icons (assets/icons/<stat>.png, keyed by name in StatIcons.kt). The client's
            // miscellaneous/characteristics/<NAME> set is the official, colourful equivalent — mapped by the
            // Characteristic each represents (not guesswork): `di` = damage-inflicted / generic mastery ->
            // DMG_IN_PERCENT (NOT INDIRECT_DMG); `shield` = the Intelligence barrier line -> BARRIER; `control`
            // (summon control) -> LEADERSHIP; `lock` -> TACKLE. gui.jar has a single crit glyph, so crit chance
            // + crit mastery share it.
            extractNamed(
                "icons",
                mapOf(
                    "ap" to "AP",
                    "mp" to "MP",
                    "wp" to "WP",
                    "hp" to "HP",
                    "range" to "RANGE",
                    "init" to "INIT",
                    "control" to "LEADERSHIP",
                    "wisdom" to "WISDOM",
                    "prosp" to "PROSPECTION",
                    "will" to "WILLPOWER",
                    "dodge" to "DODGE",
                    "lock" to "TACKLE",
                    "block" to "BLOCK",
                    "critical" to "CRITICAL_BONUS",
                    "mastery_crit" to "CRITICAL_BONUS",
                    "res_crit" to "CRITICAL_RES",
                    "armor" to "ARMOR",
                    "armor_given" to "ARMOR_GIVEN",
                    "armor_received" to "ARMOR_RECEIVED",
                    "shield" to "BARRIER",
                    "heal" to "FINAL_HEAL_IN_PERCENT",
                    "mastery_heal" to "HEAL_IN_PERCENT",
                    "di" to "DMG_IN_PERCENT",
                    "fire" to "DMG_FIRE_PERCENT",
                    "water" to "DMG_WATER_PERCENT",
                    "earth" to "DMG_EARTH_PERCENT",
                    "air" to "DMG_AIR_PERCENT",
                    "fire_res" to "RES_FIRE_PERCENT",
                    "water_res" to "RES_WATER_PERCENT",
                    "earth_res" to "RES_EARTH_PERCENT",
                    "air_res" to "RES_AIR_PERCENT",
                    "mastery_dist" to "RANGED_DMG",
                    "mastery_mel" to "MELEE_DMG",
                    "mastery_back" to "BACKSTAB_BONUS",
                    "mastery_berserk" to "BERSERK_DMG",
                    "res_back" to "RES_BACKSTAB"
                ).mapValues { "miscellaneous/characteristics/${it.value}" }
            )
            // Monster portraits are NOT extracted: the boss picker shows only bosses (all 220 already have a
            // committed 200x200 portrait), and the client only keys monsters by gfx as 132x41 in-game banners,
            // not portraits — so there is nothing better to pull. The committed boss portraits stay as-is.

            // Class ("breed") artwork, keyed by CharacterClass.breedId. In gui.jar: breeds/icons/<id>,
            // breeds/illustrations/<id*10> (male; +1 is female), breeds/backgrounds/<id>. Re-keyed to the plain
            // breedId so BreedAssets resolves it directly. There is no breed 17.
            val breedIds = (1..16) + listOf(18, 19)

            fun extractBreed(
                kind: String,
                jarDir: String,
                jarId: (Int) -> Int,
            ) {
                val targetDir = assetsDir.resolve("breeds/$kind").apply { mkdirs() }
                var copied = 0
                breedIds.forEach { breedId ->
                    val destination = targetDir.resolve("$breedId.png")
                    if (destination.exists()) return@forEach
                    val entry = zip.getEntry("$jarDir/${jarId(breedId)}.tga") ?: return@forEach
                    val image = zip.getInputStream(entry).use { tgaToImage(it.readBytes()) }
                    ImageIO.write(image, "png", destination)
                    copied++
                }
                logger.lifecycle("generateAssets: breeds/$kind -> $copied new file(s) from gui.jar")
            }
            extractBreed("icon", "breeds/icons") { it }
            extractBreed("illustration", "breeds/illustrations") { it * 10 }
            extractBreed("background", "breeds/backgrounds") { it }
        }
    }
}

/**
 * Decodes a Wakfu client icon TGA (64x64, uncompressed true-colour, 32-bit BGRA or 24-bit BGR) into an ARGB
 * image for PNG re-encoding. Targa stores rows bottom-up unless the descriptor's top-origin bit (0x20) is set.
 */
fun tgaToImage(bytes: ByteArray): BufferedImage {
    fun u8(i: Int) = bytes[i].toInt() and 0xFF
    val idLength = u8(0)
    val imageType = u8(2)
    require(imageType == 2) { "generateAssets: unexpected TGA image type $imageType (only uncompressed true-colour)" }
    val width = u8(12) or (u8(13) shl 8)
    val height = u8(14) or (u8(15) shl 8)
    val depth = u8(16)
    require(depth == 32 || depth == 24) { "generateAssets: unexpected TGA pixel depth $depth" }
    val bytesPerPixel = depth / 8
    val topOrigin = (u8(17) and 0x20) != 0
    var offset = 18 + idLength
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (row in 0 until height) {
        val y = if (topOrigin) row else height - 1 - row
        for (x in 0 until width) {
            val b = u8(offset)
            val g = u8(offset + 1)
            val r = u8(offset + 2)
            val a = if (bytesPerPixel == 4) u8(offset + 3) else 0xFF
            offset += bytesPerPixel
            image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
        }
    }
    return image
}
