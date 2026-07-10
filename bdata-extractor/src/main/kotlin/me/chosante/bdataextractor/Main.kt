package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.chosante.common.Monster
import me.chosante.common.RuneType
import me.chosante.common.Spell
import me.chosante.common.SpellDamageScaling
import me.chosante.common.SpellLocalization
import me.chosante.common.Sublimation
import me.chosante.common.WakfuData
import me.chosante.common.findRepositoryRoot
import java.io.File

/** The data version — the single source [WakfuData.VERSION]. Resource files now have fixed names, so this
 *  is used only for the CDN `actions.json` fetch + the provenance log line. Overridable via the 2nd CLI arg. */
private const val DEFAULT_VERSION = WakfuData.VERSION
private const val DEFAULT_INSTALL = "/Applications/Ankama/Wakfu"

/**
 * Regenerates the baked bdata artifacts from a local Wakfu install by decoding the scrambled static-data
 * binaries directly (no Rust, no external tool):
 *  - `spell-cast-limits.json`   (Spell table 66: per-turn / per-target / cooldown)
 *  - `spell-passives.json`      (Spell 66 `passive` flag + StaticEffect table 68 effects)
 *  - `sublimation-stacking.json` (State table 67: per-sublimation `max_level` + `is_cumulable`)
 *  - `sublimations.json`        (State 67 → StaticEffect 68 effects/condition/max-level + CDN items.json metadata)
 *  - `spell-damage.json`        (Spell 66 → StaticEffect 68 per-level `[base, inc]` formula, anchored on the
 *                                encyclopedia `spells.json` so spell damage scales to the caster's level)
 *  - `monsters.json`            (Monster table 42 + i18n names — boss-mode data)
 *
 * The source binaries live ONLY in the local install (`contents/bdata/`), never on the CDN, so this
 * tool is maintainer-local (it cannot run in CI) — the JSON it produces stays committed. `actions.json`
 * (action_id semantics) and `items.json` (sublimation metadata) are fetched from the CDN, the same source
 * `equipments-extractor` uses.
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

    // Localization bundle, loaded once and reused for monsters below: spell name (3) + spell description (4)
    // for passives, monster name (7) + family (38) for the bestiary.
    val i18n = I18nBundle.load(install, namespaces = setOf(3, 4, 7, 38))
    val passives = buildPassives(spells, effects, actions, names, i18n)
    val passivesJson = json.encodeToString(ListSerializer(PassiveEntry.serializer()), passives)
    verifyAndWrite(File(resources, "spell-passives.json"), passivesJson, "passives", passives.size, force)

    // Per-spell localized name + description (i18n namespaces 3 + 4), joined onto spells.json by id in
    // SpellCatalog so the GUI shows translated spell text instead of the encyclopedia's English-only descriptions.
    val spellLocalizations = buildSpellLocalizations(spells, i18n)
    val spellI18nJson = json.encodeToString(ListSerializer(SpellLocalization.serializer()), spellLocalizations)
    verifyAndWrite(File(resources, "spell-i18n.json"), spellI18nJson, "spell-i18n", spellLocalizations.size, force)

    // Spell damage scalings: the per-level [base, inc] formula from bdata, anchored on the encyclopedia's
    // max-level value so SpellDamage scales each hit to the caster's level (the encyclopedia only knows one
    // level). Reads the committed encyclopedia spells.json as the anchor; produces spell-damage.json.
    val encyclopediaSpells =
        LENIENT_JSON.decodeFromString(ListSerializer(Spell.serializer()), File(resources, "spells.json").readText())
    val spellDamageScalings = buildSpellDamageScalings(spells, effects, encyclopediaSpells)
    val matchedScalings = spellDamageScalings.count { it.matched }
    println("  ${spellDamageScalings.size} spell damage scalings ($matchedScalings exact bdata anchor-match, ${spellDamageScalings.size - matchedScalings} linear-approx fallback)")
    val scalingJson = json.encodeToString(ListSerializer(SpellDamageScaling.serializer()), spellDamageScalings)
    verifyAndWrite(File(resources, "spell-damage.json"), scalingJson, "spell-damage", spellDamageScalings.size, force)

    // Sublimations + runes: fully first-party now — identity/metadata from the CDN items.json (item shards,
    // itemTypeId 812 / 811), sublimation effects/condition/max-level decoded from the State (67) →
    // StaticEffect (68) tables. Replaces the third-party WakForge/noredlace/manual pipeline AND the previously
    // hand-maintained runes.json. The items.json body is fetched once and parsed for both.
    println("Fetching CDN items.json (sublimation + rune metadata)…")
    val itemsJson = ItemsCatalog.fetchItemsJson(version)
    val subMeta = ItemsCatalog.parse(itemsJson)
    println("  ${subMeta.size} sublimations (itemTypeId ${ItemsCatalog.SUBLIMATION_ITEM_TYPE})")
    // First-party characteristic-id map (read from the client's obfuscated characteristic enum) — lets the
    // sublimation decoder resolve an action-913 ramp's source stat (e.g. Featherweight's MP) without a hand table.
    val characIds = CharacIdCatalog.load(install)
    val sublimations = buildSublimations(states, effects, actions, subMeta, characIds)
    println("  ${sublimations.count { it.solverChoosable }} solver-choosable (rest forced-input-only)")
    val sublimationsJson = json.encodeToString(ListSerializer(Sublimation.serializer()), sublimations)
    verifyAndWrite(File(resources, "sublimations.json"), sublimationsJson, "sublimations", sublimations.size, force)

    // Stacking artifact (State table 67): max_level + is_cumulable for the same sublimation set.
    val stacking = buildSublimationStacking(states, subMeta.map { it.stateId }.toSet())
    if (stacking.size < subMeta.size) {
        println("  WARN: ${subMeta.size - stacking.size} sublimation stateId(s) absent from the State table.")
    }
    val stackingJson = json.encodeToString(ListSerializer(SublimationStacking.serializer()), stacking)
    verifyAndWrite(File(resources, "sublimation-stacking.json"), stackingJson, "sublimation-stacking", stacking.size, force)

    // Runes (itemTypeId 811 shards from the same items.json): colour + double-bonus slots from shardParameters,
    // boosted stat from the equip-effect action (ActionCatalog). Replaces the hand-maintained runes.json.
    val runes = ItemsCatalog.parseRunes(itemsJson, actions)
    println("  ${runes.size} runes (itemTypeId ${ItemsCatalog.RUNE_ITEM_TYPE})")
    val runesJson = json.encodeToString(ListSerializer(RuneType.serializer()), runes)
    verifyAndWrite(File(resources, "runes.json"), runesJson, "runes", runes.size, force)

    // Monsters (boss mode): decoded from the local Monster table (42) + i18n names, replacing the third-party
    // MethodWakfu/Fandom scrape. The table's positional schema is AUTO-DERIVED from the client bytecode
    // (SchemaGenerator) — so a layout change between client versions (e.g. the Vec Ankama inserted into the
    // record in 1.92.x) needs no hand-RE. All combat data + the icon `gfx` come from bdata; only the editorial
    // boss-tier `rank` (absent from every client table) is carried by the committed monster-overlay.json.
    println("Deriving the Monster table schema from the client bytecode…")
    val monsterSchema = SchemaGenerator.load(install).monsterSchema(install)
    println("Decoding Monster table (42)…")
    val monsterRecords = loadTable(install, Tables.MONSTER, monsterSchema).records
    println("  ${monsterRecords.size} records (size-guard passed)")
    val ranks = loadMonsterRanks(File(resources, "monster-overlay.json"))
    println("Loaded ${ranks.size} boss rank overrides")
    val monsters = buildMonsters(monsterRecords, i18n, ranks)
    val monstersJson = json.encodeToString(ListSerializer(Monster.serializer()), monsters)
    verifyAndWriteMonsters(File(resources, "monsters.json"), monsters, monstersJson, force)

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

/**
 * Validates the freshly-built monster list against the committed `monsters.json` (the prior oracle) before
 * overwriting: every id present in BOTH must reproduce the combat-critical fields — `level`, `hp`, and the
 * four flat resistances — exactly. The dataset legitimately GROWS (bdata carries more monsters) and changes
 * `source`/`gfx`, so this is not a full semantic match: only the overlap's combat data must be reproduced.
 * All match → write; any mismatch → block unless BDATA_FORCE_WRITE=1 (mismatches printed first).
 */
private fun verifyAndWriteMonsters(
    file: File,
    monsters: List<Monster>,
    newJson: String,
    force: Boolean,
) {
    println("  built ${monsters.size} monsters (bosses rank≥1: ${monsters.count { it.isBoss }})")
    if (file.isFile) {
        val old =
            runCatching {
                LENIENT_JSON.decodeFromString(ListSerializer(Monster.serializer()), file.readText())
            }.getOrElse {
                println("  [monsters] existing artifact unreadable (${it.message}); skipping diff, NOT overwriting.")
                return
            }
        val newById = monsters.associateBy { it.id }
        val samples = mutableListOf<String>()
        var overlap = 0
        var mismatchCount = 0

        fun Monster.combat() = listOf(level, hp, fireResistance, waterResistance, earthResistance, airResistance)
        for (o in old) {
            val n = newById[o.id] ?: continue
            overlap++
            if (n.combat() == o.combat()) continue
            mismatchCount++
            if (samples.size < 12) {
                samples.add(
                    "id=${o.id} ${o.name.fr}: lvl ${n.level}/${o.level} hp ${n.hp}/${o.hp} " +
                        "res(${n.fireResistance},${n.waterResistance},${n.earthResistance},${n.airResistance}) vs " +
                        "(${o.fireResistance},${o.waterResistance},${o.earthResistance},${o.airResistance})"
                )
            }
        }
        val absent = old.count { it.id !in newById }
        if (mismatchCount == 0) {
            println("  [monsters] ✓ all $overlap overlapping ids reproduce level/hp/resistances exactly (${old.size}→${monsters.size}; $absent prior id(s) absent from bdata).")
        } else {
            val verb = if (force) "accepting (BDATA_FORCE_WRITE) and writing" else "NOT overwriting"
            println(
                "  [monsters] $mismatchCount/$overlap overlapping ids differ from the (older-version) prior oracle (bdata = current client, authoritative). $absent prior id(s) absent from bdata. $verb. Sample:"
            )
            samples.forEach { println("      $it") }
            if (!force) return
        }
    }
    file.writeText(newJson + "\n")
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
