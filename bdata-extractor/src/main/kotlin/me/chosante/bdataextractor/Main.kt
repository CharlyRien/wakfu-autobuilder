package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.chosante.common.Spell
import me.chosante.common.Sublimation
import me.chosante.common.WakfuData
import me.chosante.common.findRepositoryRoot
import java.io.File

/** The data version — the single source [WakfuData.VERSION]. Resource files now have fixed names, so this
 *  is used only for the CDN `actions.json` fetch + the provenance log line. Overridable via the 2nd CLI arg. */
private const val DEFAULT_VERSION = WakfuData.VERSION
private const val DEFAULT_INSTALL = "/Applications/Ankama/Wakfu"

private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

/**
 * Regenerates the baked bdata artifacts from a local Wakfu install by decoding the scrambled static-data
 * binaries directly (no Rust, no external tool):
 *  - `spell-cast-limits.json`   (Spell table 66: per-turn / per-target / cooldown)
 *  - `spell-passives.json`      (Spell 66 `passive` flag + StaticEffect table 68 effects)
 *  - `sublimation-stacking.json` (State table 67: per-sublimation `max_level` + `is_cumulable`)
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
    // The oracle guard in verifyAndWrite blocks ANY semantic diff vs the committed artifact (drift safety).
    // For an INTENTIONAL change — e.g. adding a new field like `gfxId` — set BDATA_FORCE_WRITE=1 to accept
    // and rewrite; the diff is still printed first so you can eyeball that nothing unexpected changed.
    val force = System.getenv("BDATA_FORCE_WRITE") == "1"
    val repoRoot = findRepositoryRoot()
    val resources = File(repoRoot, "autobuilder/src/main/resources")

    println("Wakfu install : $install")
    println("Data version  : $version")

    val names = loadSpellInfo(File(resources, "spells.json"))
    println("Loaded ${names.size} encyclopedia spell names")

    println("Decoding Spell table (66)…")
    val spells = loadTable(install, Tables.SPELL, Tables.SPELL_SCHEMA)
    println("  ${spells.records.size} records (size-guard passed)")

    println("Decoding StaticEffect table (68)…")
    val effects = loadTable(install, Tables.STATIC_EFFECT, Tables.STATIC_EFFECT_SCHEMA)
    println("  ${effects.records.size} records (size-guard passed)")

    println("Decoding State table (67)…")
    val states = loadTable(install, Tables.STATE, Tables.STATE_SCHEMA)
    println("  ${states.records.size} records (size-guard passed)")

    println("Fetching actions.json from CDN…")
    val actions = ActionCatalog.fetch(version)

    val json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    val castLimits = buildCastLimits(spells, names)
    val castJson = json.encodeToString(ListSerializer(CastLimit.serializer()), castLimits)
    verifyAndWrite(File(resources, "spell-cast-limits.json"), castJson, "cast-limits", castLimits.size, force)

    val passives = buildPassives(spells, effects, actions, names)
    val passivesJson = json.encodeToString(ListSerializer(PassiveEntry.serializer()), passives)
    verifyAndWrite(File(resources, "spell-passives.json"), passivesJson, "passives", passives.size, force)

    // Sublimation stacking (State table 67): max_level + is_cumulable for each curated sublimation stateId,
    // straight from the local binary — replaces the third-party "Max" column. Cross-check the decoded
    // maxStackLevel against the current Sublimation.maxLevel to validate before switching the source over.
    val subStateIds = loadSublimationStateIds(File(resources, "sublimations.json"))
    println("Loaded ${subStateIds.size} sublimation stateIds")
    val stacking = buildSublimationStacking(states, subStateIds)
    if (stacking.size < subStateIds.size) {
        println("  WARN: ${subStateIds.size - stacking.size} sublimation stateId(s) absent from the State table.")
    }
    val stackingJson = json.encodeToString(ListSerializer(SublimationStacking.serializer()), stacking)
    verifyAndWrite(File(resources, "sublimation-stacking.json"), stackingJson, "sublimation-stacking", stacking.size, force)

    println("\nDone.")
}

/** The set of sublimation `stateId`s to decode stacking for — the curated identities in the committed
 *  sublimations resource (we replace the max-stack VALUE from bdata, not the identities). */
private fun loadSublimationStateIds(file: File): Set<Int> {
    if (!file.isFile) error("Missing $file — build the sublimations resource first.")
    val subs = LENIENT_JSON.decodeFromString(ListSerializer(Sublimation.serializer()), file.readText())
    return subs.map { it.stateId }.toSet()
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
    force: Boolean = false,
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
        val verb = if (force) "accepting (BDATA_FORCE_WRITE) and writing" else "NOT overwriting"
        println("  [$label] ✗ ${diffs.size} semantic difference(s) vs committed oracle — $verb. First 12:")
        diffs.take(12).forEach { println("      $it") }
        if (force) file.writeText(newJson + "\n")
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
