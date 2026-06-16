package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.chosante.common.Spell
import me.chosante.common.findRepositoryRoot
import java.io.File

/**
 * Wakfu data version this dataset is stamped with — must match `VERSION` in `autobuilder/.../Main.kt`
 * (resources are loaded as `…-v$VERSION.json`). Overridable via the 2nd CLI arg.
 */
private const val DEFAULT_VERSION = "1.91.1.54"
private const val DEFAULT_INSTALL = "/Applications/Ankama/Wakfu"

private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

/**
 * Regenerates the baked bdata artifacts from a local Wakfu install by decoding the scrambled static-data
 * binaries directly (no Rust, no external tool):
 *  - `spell-cast-limits-v<version>.json` (Spell table 66: per-turn / per-target / cooldown)
 *  - `spell-passives-v<version>.json`    (Spell 66 `passive` flag + StaticEffect table 68 effects)
 *
 * The source binaries live ONLY in the local install (`contents/bdata/`), never on the CDN, so this
 * tool is maintainer-local (it cannot run in CI) — the JSON it produces stays committed. `actions.json`
 * (action_id semantics) is fetched from the CDN, the same source `equipments-extractor` uses.
 *
 * Run with: `./gradlew :bdata-extractor:run --args="[installRoot] [version]"`.
 */
fun main(args: Array<String>) {
    val install = File(args.getOrNull(0) ?: DEFAULT_INSTALL)
    val version = args.getOrNull(1) ?: DEFAULT_VERSION
    val repoRoot = findRepositoryRoot()
    val resources = File(repoRoot, "autobuilder/src/main/resources")

    println("Wakfu install : $install")
    println("Data version  : $version")

    val names = loadSpellInfo(File(resources, "spells-v$version.json"))
    println("Loaded ${names.size} encyclopedia spell names")

    println("Decoding Spell table (66)…")
    val spells = loadTable(install, Tables.SPELL, Tables.SPELL_SCHEMA)
    println("  ${spells.records.size} records (size-guard passed)")

    println("Decoding StaticEffect table (68)…")
    val effects = loadTable(install, Tables.STATIC_EFFECT, Tables.STATIC_EFFECT_SCHEMA)
    println("  ${effects.records.size} records (size-guard passed)")

    println("Fetching actions.json from CDN…")
    val actions = ActionCatalog.fetch(version)

    val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    val castLimits = buildCastLimits(spells, names)
    val castJson = json.encodeToString(ListSerializer(CastLimit.serializer()), castLimits)
    verifyAndWrite(File(resources, "spell-cast-limits-v$version.json"), castJson, "cast-limits", castLimits.size)

    val passives = buildPassives(spells, effects, actions, names)
    val passivesJson = json.encodeToString(ListSerializer(PassiveEntry.serializer()), passives)
    verifyAndWrite(File(resources, "spell-passives-v$version.json"), passivesJson, "passives", passives.size)

    println("\nDone.")
}

/** Reads the encyclopedia-scraped catalogue and joins FR name/description (HTML-unescaped) by spell id. */
private fun loadSpellInfo(file: File): Map<Int, SpellInfo> {
    if (!file.isFile) error("Missing $file — run :spells-extractor first.")
    val spells = LENIENT_JSON.decodeFromString(ListSerializer(Spell.serializer()), file.readText())
    return spells.associate { s ->
        s.id to
            SpellInfo(
                s.name.fr
                    .takeIf { it.isNotBlank() }
                    ?.let(::unescapeHtml),
                s.description?.fr?.let(::unescapeHtml)
            )
    }
}

/**
 * Compares freshly-generated [newJson] against the committed artifact (the validated oracle) **before**
 * overwriting it, reporting whether the regeneration reproduces it semantically (numbers by value, keys
 * structurally — ignoring cosmetic whitespace/number-format). Then writes the canonical file.
 */
private fun verifyAndWrite(
    file: File,
    newJson: String,
    label: String,
    count: Int,
) {
    val fresh = Json.parseToJsonElement(newJson)
    if (!file.isFile) {
        println("  [$label] no existing oracle to compare; writing $count entries.")
        file.writeText(newJson + "\n")
        return
    }
    // The oracle is a committed, human-touchable file; parse defensively so a stray hand-edit yields a
    // clear message rather than aborting the whole extraction with a raw SerializationException.
    val oracle =
        runCatching { Json.parseToJsonElement(file.readText()) }.getOrElse {
            println("  [$label] existing artifact is not valid JSON (${it.message}); skipping diff, NOT overwriting.")
            return
        }
    val diffs = mutableListOf<String>()
    semanticDiff("$", oracle, fresh, diffs)
    if (diffs.isEmpty()) {
        println("  [$label] ✓ reproduces the committed oracle exactly ($count entries, semantically identical) — writing canonical.")
        file.writeText(newJson + "\n")
    } else {
        println("  [$label] ✗ ${diffs.size} semantic difference(s) vs committed oracle — NOT overwriting. First 12:")
        diffs.take(12).forEach { println("      $it") }
    }
}

/** Deep semantic comparison: numbers compared by value (1 == 1.0), objects by key set, arrays by order. */
private fun semanticDiff(
    path: String,
    a: JsonElement,
    b: JsonElement,
    out: MutableList<String>,
) {
    when {
        a is JsonArray && b is JsonArray -> {
            if (a.size != b.size) {
                out.add("$path: array size ${a.size} vs ${b.size}")
                return
            }
            for (i in a.indices) semanticDiff("$path[$i]", a[i], b[i], out)
        }
        a is JsonObject && b is JsonObject -> {
            if (a.keys != b.keys) {
                out.add("$path: keys ${a.keys - b.keys} vs ${b.keys - a.keys}")
                return
            }
            for (k in a.keys) semanticDiff("$path.$k", a.getValue(k), b.getValue(k), out)
        }
        a is JsonPrimitive && b is JsonPrimitive -> {
            // Compare numerically (1 == 1.0) only when BOTH sides are non-string numbers; otherwise compare
            // verbatim, so a string field whose content happens to be numeric is not masked.
            val an = if (!a.isString) a.contentOrNull?.toDoubleOrNull() else null
            val bn = if (!b.isString) b.contentOrNull?.toDoubleOrNull() else null
            val equal = if (an != null && bn != null) an == bn else a == b
            if (!equal) out.add("$path: $a vs $b")
        }
        else -> if (a != b) out.add("$path: type/$a vs $b")
    }
}
