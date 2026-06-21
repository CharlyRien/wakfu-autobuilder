package me.chosante.bdataextractor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import me.chosante.common.Monster
import java.io.File
import kotlin.math.abs

/** Lenient JSON for reading committed artifacts/overlays — tolerates unknown keys. Shared module-wide. */
internal val LENIENT_JSON = Json { ignoreUnknownKeys = true }

/** breed_id → class label (matches the labels used in `spells-v*.json` `clazz`). */
val BREED_TO_CLASS =
    mapOf(
        1 to "FECA",
        2 to "OSAMODAS",
        3 to "ENUTROF",
        4 to "SRAM",
        5 to "XELOR",
        6 to "ECAFLIP",
        7 to "ENIRIPSA",
        8 to "IOP",
        9 to "CRA",
        10 to "SADIDA",
        11 to "SACRIEUR",
        12 to "PANDAWA",
        13 to "ROUBLARD",
        14 to "ZOBAL",
        15 to "OUGINAK",
        16 to "STEAMER",
        18 to "ELIOTROPE",
        19 to "HUPPERMAGE"
    )

/** The 18 player classes' binary `breed_id`s — exactly the keys of [BREED_TO_CLASS] (17 unused; Huppermage=19). */
val PLAYER_BREEDS: Set<Int> = BREED_TO_CLASS.keys

/** Effect actions skipped as structural/meta/script-containers (no declarative stat/damage value). */
internal val STRUCTURAL_ACTIONS = setOf(330, 400, 1020, 865, 843, 1202)

/** StaticEffect trigger lists; a non-empty one means the effect is a reactive proc (sets hasTriggeredEffects). */
internal val TRIGGER_KEYS =
    listOf(
        "triggers_before_computation",
        "triggers_before_execution",
        "triggers_for_unapplication",
        "triggers_after_execution",
        "triggers_after_all_executions",
        "triggers_not_related_to_executions",
        "triggers_additionnal"
    )

// ----- output contracts (mirror the committed baked artifacts) -----

@Serializable
data class CastLimit(
    val spellId: Int,
    val name: String?,
    val breedId: Int,
    val maxCastPerTurn: Int,
    val maxCastPerTurnIncr: Int,
    val maxCastPerTarget: Int,
    val cooldown: Int,
    /** WP (Wakfu Point) base cost — Ankama's `pw_base`. `0` = no WP cost. Carried for display + future
     *  rotation modelling; WP is a per-fight pool, see `docs/FULL_DAMAGE_MODE_STATUS.md` "Lot 1". */
    val wpCost: Int,
)

@Serializable
data class DeclaredEffect(
    val actionId: Int,
    val kind: String,
    val characteristic: String?,
    val base: JsonElement?,
    val inc: JsonElement?,
    val conditional: Boolean,
    val criterion: String?,
    val durationBase: Int,
    val permanent: Boolean,
)

@Serializable
data class PassiveEntry(
    val spellId: Int,
    val name: String?,
    val breedId: Int,
    @SerialName("class") val clazz: String,
    // Spell sprite id (Spell table `gfx_id`). Names the passive's icon PNG (`assets/spells/<gfxId>.png`,
    // extracted from the client's gui.jar — the same set the active-spell icons come from) so the GUI can render it.
    val gfxId: Int,
    val description: String?,
    val effectIds: List<Int>,
    val declaredEffects: List<DeclaredEffect>,
    val flatBuildStats: Map<String, JsonElement>,
    val appliedStateIds: List<Int>,
    val hasConditionalEffects: Boolean,
    val hasStateEffects: Boolean,
    val hasScriptedEffects: Boolean,
    val hasTriggeredEffects: Boolean,
    val fullyDeclarative: Boolean,
)

/**
 * Per-sublimation stacking facts decoded from the State table (66's sibling, TYPE_ID 67) — the
 * authoritative local-binary source, replacing the third-party "Max" column. [maxStackLevel] is the
 * state's `max_level` (the stacking/level cap), [cumulable] its `is_cumulable` flag (whether re-applying
 * the same sublimation across items accumulates). Keyed by [stateId] to join `sublimations-v*.json`.
 */
@Serializable
data class SublimationStacking(
    val stateId: Int,
    val maxStackLevel: Int,
    val cumulable: Boolean,
)

/** A spell's name/description/class as joined from the encyclopedia-scraped `spells-v*.json`. */
data class SpellInfo(
    val nameFr: String?,
    val descFr: String?,
)

// ----- builders -----

/**
 * Sublimation stacking artifact: for each sublimation [stateId] (the curated set in `sublimations-v*.json`),
 * its `max_level` + `is_cumulable` decoded straight from the State table — no third-party data. A sublimation
 * whose state is absent from the table (shouldn't happen) is dropped and reported by the caller.
 */
fun buildSublimationStacking(
    states: Table,
    sublimationStateIds: Set<Int>,
): List<SublimationStacking> {
    val byId = states.records.associateBy { it["id"] as Int }
    return sublimationStateIds.sorted().mapNotNull { stateId ->
        val record = byId[stateId] ?: return@mapNotNull null
        SublimationStacking(
            stateId = stateId,
            maxStackLevel = record["max_level"] as Int,
            cumulable = record["is_cumulable"] as Boolean
        )
    }
}

/** Cast-limit artifact: player-breed spells, raw decoded values (0 = no limit / no cooldown). */
fun buildCastLimits(
    spells: Table,
    names: Map<Int, SpellInfo>,
): List<CastLimit> =
    spells.records
        .filter { (it["breed_id"] as Int) in PLAYER_BREEDS }
        .sortedWith(compareBy({ it["breed_id"] as Int }, { it["id"] as Int }))
        .map { r ->
            val id = r["id"] as Int
            CastLimit(
                spellId = id,
                name = names[id]?.nameFr,
                breedId = r["breed_id"] as Int,
                maxCastPerTurn = (r["cast_max_per_turn"] as Float).toInt(),
                maxCastPerTurnIncr = (r["cast_max_per_turn_incr"] as Float).toInt(),
                maxCastPerTarget = r["cast_max_per_target"] as Int,
                cooldown = r["cast_min_interval"] as Int,
                wpCost = (r["pw_base"] as Float).toInt()
            )
        }

/**
 * Monster artifact (boss mode): everything except boss-tier `rank` is decoded straight from the Monster
 * table (42), whose schema is auto-derived from the client bytecode ([SchemaGenerator]) — `level`
 * (= `level_max`), `hp` (= `base_hp`), the four FLAT SIGNED elemental resistances, the localized name (i18n
 * `content.7`) + family (i18n `content.38`, only when `family_id > 0`), and the icon `gfx` (from the record;
 * `<= 0` means none). `rank` is the one fact absent from every client table (npc_rank/family_rank/
 * MonsterType.kind don't reproduce Ankama's editorial taxonomy), so it comes from the committed [ranks]
 * overlay; monsters with no entry default to 0 (regular, hidden from the GUI picker). Monsters without a
 * localized name are dropped.
 *
 * Combat fields are bounds-checked as a coarse desync net (the oracle diff in verifyAndWriteMonsters is the
 * real backstop): only IMPOSSIBLE values are flagged. Resistances stay flat+signed (engine converts flat→%
 * at use time). Sorted bosses-first.
 */
fun buildMonsters(
    monsterRecords: List<Map<String, Any?>>,
    i18n: I18nBundle,
    ranks: Map<Int, Int>,
): List<Monster> {
    var anomalies = 0
    val monsters =
        monsterRecords
            .mapNotNull { r ->
                val id = r["id"] as Int
                val name = i18n.text(7, id) ?: return@mapNotNull null
                val familyId = r["family_id"] as Int
                val level = r["level_max"] as Int
                val hp = r["base_hp"] as Int
                val fire = r["base_fire_resistance"] as Int
                val water = r["base_water_resistance"] as Int
                val earth = r["base_earth_resistance"] as Int
                val air = r["base_wind_resistance"] as Int
                // Coarse desync net: flag only IMPOSSIBLE values — negative, or absurdly large in the
                // Int-overflow range a positional desync produces. Legitimate edge monsters pass (NPCs hp=0;
                // special "colossal" monsters reach level ~30000 / hp ~1e8; resistances ~1000s).
                if (level !in 0..1_000_000 || hp !in 0..2_000_000_000 || listOf(fire, water, earth, air).any { abs(it) > 10_000_000 }) {
                    if (anomalies < 8) {
                        println("  WARN: monster $id (${name.fr}) decoded implausible stats (lvl=$level hp=$hp res=$fire/$water/$earth/$air) — possible schema drift.")
                    }
                    anomalies++
                }
                Monster(
                    id = id,
                    name = name,
                    level = level,
                    hp = hp,
                    family = if (familyId > 0) i18n.text(38, familyId) else null,
                    rank = ranks[id] ?: 0,
                    // gfx (icon sprite id) decoded from the record; -1/0 = none -> null so the GUI degrades.
                    gfx = (r["gfx"] as Int).takeIf { it > 0 },
                    fireResistance = fire,
                    waterResistance = water,
                    earthResistance = earth,
                    airResistance = air,
                    source = "bdata"
                )
            }.sortedWith(compareByDescending<Monster> { it.rank }.thenByDescending { it.level }.thenBy { it.name.en })
    if (anomalies > 0) println("  WARN: $anomalies monster(s) had implausible decoded stats (schema-drift suspect).")
    val builtIds = monsters.mapTo(HashSet()) { it.id }
    val unmatched = ranks.keys.filterNot { it in builtIds }.sorted()
    if (unmatched.isNotEmpty()) {
        println("  WARN: ${unmatched.size} rank-overlay id(s) matched no decoded monster (missing table record or i18n name): ${unmatched.take(20)}")
    }
    return monsters
}

/**
 * The committed boss-tier overlay (`monster-overlay.json`: `{"<id>": rank}`, rank ≥ 1 only). Boss-tier is the
 * one monster fact absent from every client table (npc_rank/family_rank/MonsterType.kind don't reproduce
 * Ankama's editorial taxonomy), so it is carried here; everything else (incl. `gfx`) is decoded from bdata.
 * Monsters with no entry default to rank 0 (regular, hidden from the GUI picker). An absent file yields an
 * empty map; entries with rank < 1 are skipped with a warning; a malformed file fails with a clear message.
 */
fun loadMonsterRanks(file: File): Map<Int, Int> {
    if (!file.isFile) return emptyMap()
    val obj =
        runCatching { LENIENT_JSON.parseToJsonElement(file.readText()).jsonObject }
            .getOrElse { error("Malformed ${file.name}: expected a JSON object {\"<id>\": rank} — ${it.message}") }
    val out = LinkedHashMap<Int, Int>()
    for ((k, v) in obj) {
        val id = k.toIntOrNull() ?: error("Malformed ${file.name}: key '$k' is not an integer monster id")
        val rank = (v as? JsonPrimitive)?.int ?: error("Malformed ${file.name}: entry $id rank must be an integer")
        if (rank < 1) {
            println("  WARN: ${file.name} entry $id has rank=$rank (< 1); overlay entries must be boss-tier (≥ 1) — skipping.")
            continue
        }
        out[id] = rank
    }
    return out
}

/** A declarative effect resolved with raw numeric values (before JSON formatting). */
private class Resolved(
    val actionId: Int,
    val kind: String,
    val charac: String?,
    val base: Double?,
    val inc: Double?,
    val conditional: Boolean,
    val criterion: String?,
    val durationBase: Int,
    val permanent: Boolean,
)

/** Passives artifact: catalogue + resolved declarative effects + a conservative permanent-stat rollup. */
fun buildPassives(
    spells: Table,
    effects: Table,
    actions: ActionCatalog,
    names: Map<Int, SpellInfo>,
): List<PassiveEntry> {
    @Suppress("UNCHECKED_CAST")
    fun intList(v: Any?): List<Int> = (v as List<Any?>).map { it as Int }

    // Index effects by id and by parent. The per-effect derivations (params, hasTriggers, …) are computed
    // lazily inside the loop below — only for the effects a passive's strict subtree actually visits —
    // rather than materialized for all ~173k records up front.
    val byEffectId = effects.records.associateBy { it["effect_id"] as Int }
    val childrenOf = HashMap<Int, MutableList<Int>>()
    for (r in effects.records) {
        childrenOf.getOrPut(r["parent_id"] as Int) { mutableListOf() }.add(r["effect_id"] as Int)
    }

    // strict subtree: effect_ids + their parent_id descendants only (no trigger refs, no spell-id children).
    fun strictSubtree(effectIds: List<Int>): List<Int> {
        val seen = HashSet<Int>()
        val out = ArrayList<Int>()
        val stack = ArrayDeque(effectIds)
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            if (x in seen || x !in byEffectId) continue
            seen.add(x)
            out.add(x)
            childrenOf[x]?.let { stack.addAll(it) }
        }
        return out
    }

    return spells.records
        .filter { (it["breed_id"] as Int) in PLAYER_BREEDS && (it["passive"] as Int) == 2 }
        .sortedWith(compareBy({ it["breed_id"] as Int }, { it["id"] as Int }))
        .map { r ->
            val id = r["id"] as Int
            val breed = r["breed_id"] as Int
            val gfx = r["gfx_id"] as Int
            val effectIds = intList(r["effect_ids"])
            val resolved = ArrayList<Resolved>()
            val states = sortedSetOf<Int>()
            var hasScript = false
            var hasDamage = false
            var hasCond = false
            var hasTrig = false

            for (eid in strictSubtree(effectIds)) {
                val e = byEffectId.getValue(eid)
                val a = e["action_id"] as Int

                @Suppress("UNCHECKED_CAST")
                val p = (e["params"] as List<Any?>).map { (it as Float).toDouble() }
                val crit = (e["effect_criterion"] as String).trim()
                val cond = crit.isNotEmpty() && crit != "True"
                val durationBase = e["duration_base"] as Int
                val perm = durationBase == -1 && !(e["ends_at_end_of_turn"] as Boolean)
                if (TRIGGER_KEYS.any { (e[it] as List<*>).isNotEmpty() }) hasTrig = true

                fun record(
                    kind: String,
                    charac: String?,
                    base: Double?,
                    inc: Double?,
                ) {
                    if (cond) hasCond = true
                    resolved += Resolved(a, kind, charac, base, inc, cond, crit.ifEmpty { null }, durationBase, perm)
                }

                when {
                    a == 304 -> {
                        if (p.isNotEmpty()) states.add(p[0].toInt())
                        if (cond) hasCond = true // a conditionally-applied state is still a conditional effect
                    }
                    a in STRUCTURAL_ACTIONS -> Unit
                    else ->
                        when (val k = actions.kind(a)) {
                            is ActionKind.Stat ->
                                record("stat", k.characteristic.name, p.getOrNull(0)?.times(k.sign), p.getOrNull(1)?.times(k.sign))
                            is ActionKind.RandomElement ->
                                record("stat", randomElementCharac(k.mastery, p), p.getOrNull(0), p.getOrNull(1))
                            is ActionKind.DynamicStat -> {
                                // 39/40: the characteristic is selected by params[4] (an Ankama charac id);
                                // base=params[0], inc=params[1]. Unmapped ids (class resources) -> characteristic=null.
                                val charac = p.getOrNull(4)?.toInt()?.let { ActionCatalog.CHARAC_ID[it]?.name }
                                record("stat", charac, p.getOrNull(0)?.times(k.sign), p.getOrNull(1)?.times(k.sign))
                            }
                            ActionKind.Damage -> {
                                hasDamage = true
                                record("damage", null, p.getOrNull(0), p.getOrNull(1))
                            }
                            ActionKind.Heal -> {
                                hasDamage = true
                                record("heal", null, p.getOrNull(0), p.getOrNull(1))
                            }
                            null -> if (a !in actions.allActionIds) hasScript = true
                        }
                }
            }

            val flat = LinkedHashMap<String, Double>()
            for (rsv in resolved) {
                if (rsv.kind == "stat" &&
                    rsv.charac != null &&
                    !rsv.conditional &&
                    rsv.permanent &&
                    (rsv.inc == null || rsv.inc == 0.0) &&
                    rsv.base != null &&
                    rsv.base > 0.0
                ) {
                    flat[rsv.charac] = (flat[rsv.charac] ?: 0.0) + rsv.base
                }
            }

            PassiveEntry(
                spellId = id,
                name = names[id]?.nameFr,
                breedId = breed,
                clazz = BREED_TO_CLASS.getValue(breed),
                gfxId = gfx,
                description = names[id]?.descFr,
                effectIds = effectIds,
                declaredEffects =
                    resolved.map {
                        DeclaredEffect(it.actionId, it.kind, it.charac, it.base?.let(::num), it.inc?.let(::num), it.conditional, it.criterion, it.durationBase, it.permanent)
                    },
                flatBuildStats = flat.toSortedMap().mapValues { num(it.value) },
                appliedStateIds = states.toList(),
                hasConditionalEffects = hasCond,
                hasStateEffects = states.isNotEmpty(),
                hasScriptedEffects = hasScript,
                hasTriggeredEffects = hasTrig,
                fullyDeclarative = !hasScript && states.isEmpty() && !hasCond && !hasTrig && resolved.isNotEmpty()
            )
        }
}

private fun randomElementCharac(
    mastery: Boolean,
    params: List<Double>,
): String {
    // params[2] = element count; if absent (unexpected param shape) fall back to the generic mastery/
    // resistance rather than silently guessing "one random element".
    val n = params.getOrNull(2)?.toInt()
    return when {
        mastery && n == 1 -> "MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT"
        mastery && n == 2 -> "MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT"
        mastery && n == 3 -> "MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT"
        mastery -> "MASTERY_ELEMENTARY"
        n == 1 -> "RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT"
        n == 2 -> "RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT"
        n == 3 -> "RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT"
        else -> "RESISTANCE_ELEMENTARY"
    }
}

/** Integral doubles become JSON integers (50.0 → 50); fractional ones round to 4 dp (matches the artifact). */
fun num(x: Double): JsonElement =
    if (x == Math.floor(x) && !x.isInfinite()) {
        JsonPrimitive(x.toLong())
    } else {
        JsonPrimitive(Math.round(x * 10_000.0) / 10_000.0)
    }

private val NUMERIC_ENTITY = Regex("&#(x?[0-9a-fA-F]+);")

/**
 * HTML-entity unescape covering the named entities present in `spells-v*.json` plus any numeric entity
 * (`&#NN;` / `&#xHH;`). Numeric handling is for forward-compatibility — the current data has none — so a
 * future name/description with a numeric entity decodes instead of leaking raw `&#…;` text. (Unlisted
 * *named* entities still pass through verbatim; extend [HTML_ENTITIES] if a new one appears.)
 */
fun unescapeHtml(s: String): String {
    var out = s
    for ((entity, ch) in HTML_ENTITIES) out = out.replace(entity, ch)
    out =
        NUMERIC_ENTITY.replace(out) { m ->
            val body = m.groupValues[1]
            val code = if (body[0].lowercaseChar() == 'x') body.drop(1).toInt(16) else body.toInt()
            String(Character.toChars(code))
        }
    return out
}

private val HTML_ENTITIES =
    listOf(
        "&eacute;" to "é",
        "&Eacute;" to "É",
        "&egrave;" to "è",
        "&ecirc;" to "ê",
        "&Ecirc;" to "Ê",
        "&icirc;" to "î",
        "&iuml;" to "ï",
        "&acirc;" to "â",
        "&agrave;" to "à",
        "& agrave;" to "à",
        "&ucirc;" to "û",
        "&ugrave;" to "ù",
        "&ocirc;" to "ô",
        "&ouml;" to "ö",
        "&euml;" to "ë",
        "&ccedil;" to "ç",
        "&OElig;" to "Œ",
        "&oelig;" to "œ",
        "&rsquo;" to "’",
        "&lsquo;" to "‘",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&lt;" to "<",
        "&gt;" to ">",
        "&amp;" to "&"
    )
