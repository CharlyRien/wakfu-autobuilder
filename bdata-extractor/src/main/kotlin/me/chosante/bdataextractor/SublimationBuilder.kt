package me.chosante.bdataextractor

import me.chosante.common.Characteristic
import me.chosante.common.ScenarioGate
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import kotlin.math.floor

/*
 * Builds the `sublimations.json` engine resource entirely from **first-party** sources — no WakForge /
 * noredlace / hand-curated inputs:
 *  - **metadata** (identity, name, rarity, slot colours, Zenith id) from the CDN `items.json` ([ItemsCatalog]);
 *  - **effects / condition / max level** decoded from the local client's bdata State (67) → StaticEffect (68)
 *    tables, resolved through the same [ActionCatalog] the passives use, plus a small sublimation-local action
 *    overlay ([SUB_ACTION_OVERRIDE]) for the value actions Ankama ships with no `actions.json` description.
 *
 * **Best-achievable model:** a chosen sublimation contributes its **max-level** value
 * (`base + inc·(maxLevel−1)`, with the action's sign). A sublimation is [SublimationKind.solverChoosable] only
 * when every one of its effects is a cleanly-modelable static stat under a recognized build-static condition —
 * the conservative rule that keeps the solver-choosable set to the audited minimum (see
 * docs/SUBLIMATIONS_LOT3_RESEARCH.md): any trigger/proc, applied-state, unmapped/non-flat action, or
 * unrecognized criterion atom drops the whole sublimation to forced-input-only.
 *
 * A sublimation **is** a State (table 67) keyed by its `stateId`; its `effect_ids` strict subtree (effect_ids
 * + their `parent_id` descendants) holds the StaticEffect records. Build-static **conditions** ("at the start
 * of combat, if …") and per-scenario **gates** (berserk / ranged / min level) live in those effects'
 * `effect_criterion` scripts ([parseCriterion]); a condition holds for the whole sublimation, while a gate
 * attaches to the specific effect it (or an ancestor group) guards.
 */

/**
 * Value actions resolved sublimation-locally instead of via [ActionCatalog]. Either Ankama ships them with a
 * `null` `actions.json` description (so the catalog can't map them — the "declarative siblings" of the 330
 * group executor), or the `[#charac]` token discards a distinction the catalog needs (56/57 are "max AP/MP",
 * but the token is plain `AP`/`MP`). Kept here, NOT in the shared catalog, so `spell-passives.json` is
 * unaffected. Pair = (characteristic, sign).
 */
private val SUB_ACTION_OVERRIDE: Map<Int, Pair<Characteristic, Int>> =
    mapOf(
        126 to (Characteristic.DAMAGE_INFLICTED to 1), // % damage inflicted (gain)
        129 to (Characteristic.DAMAGE_INFLICTED to -1), // % damage inflicted (loss)
        1093 to (Characteristic.DAMAGE_INFLICTED to 1), // % damage inflicted, conditionally applied (on-crit / berserk)
        178 to (Characteristic.WILLPOWER to -1), // willpower (loss); 177 (gain) is described, the catalog maps it
        56 to (Characteristic.MAX_ACTION_POINT to -1), // "-N PA max"
        57 to (Characteristic.MAX_MOVEMENT_POINT to -1) // "-N PM max"
    )

private const val ACTION_APPLY_STATE = 304 // applies another state — a combat mechanic, never solver-clean
private const val ACTION_LEVEL_AS_LOCK_DODGE = 999 // "X% of level as Lock and Dodge" — the % value is the lone param > 1

/**
 * "Secondary" masteries (melee/distance/berserk/rear/critical/healing). A sublimation granting **two or more**
 * of these at once (the "Devastate"-style all-masteries buff) can't be safely valued by the solver: they model
 * mutually-exclusive playstyles, so summing them into the objective over-values the build. Such a sublimation is
 * dropped from the solver-choosable set (kept as forced-only) rather than inventing unreachable mastery.
 */
private val SECONDARY_MASTERY_CHARACS =
    setOf(
        Characteristic.MASTERY_MELEE,
        Characteristic.MASTERY_DISTANCE,
        Characteristic.MASTERY_BERSERK,
        Characteristic.MASTERY_BACK,
        Characteristic.MASTERY_CRITICAL,
        Characteristic.MASTERY_HEALING
    )

fun buildSublimations(
    states: Table,
    effects: Table,
    actions: ActionCatalog,
    meta: List<SublimationMeta>,
): List<Sublimation> {
    @Suppress("UNCHECKED_CAST")
    fun intList(v: Any?): List<Int> = (v as List<Any?>).map { it as Int }

    val stateById = states.records.associateBy { it["id"] as Int }
    val byEffectId = effects.records.associateBy { it["effect_id"] as Int }
    val childrenOf = HashMap<Int, MutableList<Int>>()
    for (r in effects.records) childrenOf.getOrPut(r["parent_id"] as Int) { mutableListOf() }.add(r["effect_id"] as Int)

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

    return meta.sortedBy { it.stateId }.map { m ->
        val state = stateById[m.stateId]
        val maxLevel = (state?.get("max_level") as? Int)?.coerceAtLeast(1) ?: 1
        val subtree = state?.let { strictSubtree(intList(it["effect_ids"])) } ?: emptyList()
        val subtreeSet = subtree.toHashSet()

        // Ancestor-chain (own + parent groups, within the subtree) scenario gates for a given effect.
        fun gatesFor(startId: Int): ScenarioGate? {
            var berserk = false
            var ranged = false
            var minLevel: Int? = null
            var cur = startId
            val guard = HashSet<Int>()
            while (cur in subtreeSet && guard.add(cur)) {
                val e = byEffectId.getValue(cur)
                for (atom in parseCriterion(e["effect_criterion"] as String)) {
                    if (atom is CritAtom.Gate) {
                        berserk = berserk || atom.berserk
                        ranged = ranged || atom.ranged
                        atom.minLevel?.let { minLevel = it }
                    }
                }
                val pid = e["parent_id"] as Int
                if (pid == cur) break
                cur = pid
            }
            return if (berserk || ranged || minLevel != null) {
                ScenarioGate(berserk = berserk.takeIf { it }, ranged = ranged.takeIf { it }, minCharacterLevel = minLevel)
            } else {
                null
            }
        }

        var dirty = state == null || subtree.isEmpty()
        var combatSignal = false
        val conditions = LinkedHashSet<SublimationCondition>()
        val resolved = ArrayList<SublimationEffect>()
        // The same value action+params appearing in multiple parallel groups (e.g. "-50% damage" twice) makes
        // the true magnitude ambiguous (−50 once, or −100 summed?) — not safely modelable, so drop to forced-only.
        val valueSignatures = HashSet<String>()

        for (eid in subtree) {
            val e = byEffectId.getValue(eid)
            val a = e["action_id"] as Int

            @Suppress("UNCHECKED_CAST")
            val params = (e["params"] as List<Any?>).map { (it as Float).toDouble() }
            val atoms = parseCriterion(e["effect_criterion"] as String)
            if (atoms.any { it is CritAtom.Unknown }) dirty = true
            atoms.filterIsInstance<CritAtom.Cond>().forEach { conditions.add(it.condition) }
            // Triggers/scripts on a STRUCTURAL group are its execution machinery (a conditional group fires its
            // children via `triggers_not_related_to_executions`), NOT a combat proc — only flag them on a value
            // effect, where they mean "this stat procs on an in-combat event".
            val structural = a in STRUCTURAL_ACTIONS
            if (!structural && TRIGGER_KEYS.any { (e[it] as List<*>).isNotEmpty() }) {
                dirty = true
                combatSignal = true
            }
            if (!structural && (e["script_file_id"] as Int) != 0) dirty = true
            // A value effect with a finite positive duration is a TEMPORARY combat buff (e.g. "+30 resistance
            // for 2 turns"), not a permanent build stat. Permanent stats are dur=-1; the instant %-damage / %-of-
            // level modifiers are dur=0. Only dur>=1 is temporary.
            if (!structural && (e["duration_base"] as Int) >= 1) {
                dirty = true
                combatSignal = true
            }
            if (!structural && a != ACTION_APPLY_STATE && !valueSignatures.add("$a:$params")) dirty = true

            fun base() = params.getOrNull(0) ?: 0.0

            fun inc() = params.getOrNull(1) ?: 0.0

            fun add(
                charac: Characteristic,
                sign: Int,
                value: Int = floor((base() + inc() * (maxLevel - 1)) * sign).toInt(),
            ) {
                if (value != 0) resolved.add(SublimationEffect(charac, value, gatesFor(eid)))
            }

            when {
                a in STRUCTURAL_ACTIONS -> Unit // structural group; criteria already harvested above
                a == ACTION_APPLY_STATE -> {
                    dirty = true
                    combatSignal = true
                }
                a == ACTION_LEVEL_AS_LOCK_DODGE -> {
                    // "X% of level as Lock and Dodge": params are 0/1 element flags plus the single percentage
                    // value (its index drifts — 14 here, 15 there), so take the lone param > 1. Modeled as flat
                    // LOCK X, mirroring the oracle's best-achievable approximation.
                    val pct = params.filter { it > 1.0 }.maxOrNull() ?: 0.0
                    add(Characteristic.LOCK, 1, value = floor(pct).toInt())
                }
                else -> {
                    val override = SUB_ACTION_OVERRIDE[a]
                    if (override != null) {
                        add(override.first, override.second)
                    } else {
                        when (val k = actions.kind(a)) {
                            is ActionKind.Stat -> add(k.characteristic, k.sign)
                            // Random-element / dynamic / spell damage / heal aren't flat build stats; an action
                            // absent from actions.json is a combat script. Any of these = not solver-clean.
                            else -> dirty = true
                        }
                    }
                }
            }
        }

        val condition =
            when (conditions.size) {
                0 -> null
                1 -> conditions.first()
                else -> {
                    dirty = true
                    null
                } // conflicting conditions -> can't model
            }
        // Coalesce same-(characteristic, gate) contributions (e.g. a 999 lock + its companion lock increment)
        // into one entry, then drop any that net to zero. Stable canonical order for a deterministic artifact.
        val merged =
            resolved
                .groupBy { it.characteristic to it.scenarioGate }
                .map { (k, list) -> SublimationEffect(k.first, list.sumOf { it.value }, k.second) }
                .filter { it.value != 0 }
                .sortedWith(compareBy({ it.characteristic.ordinal }, { it.value }))

        val hasEffect = merged.isNotEmpty()
        val multiMastery = merged.count { it.characteristic in SECONDARY_MASTERY_CHARACS } >= 2
        val solverChoosable = !dirty && hasEffect && !multiMastery
        val kind =
            when {
                combatSignal -> SublimationKind.COMBAT_CONDITIONAL
                condition != null -> SublimationKind.STATIC_CONDITIONAL
                hasEffect -> SublimationKind.FLAT
                else -> SublimationKind.COMBAT_CONDITIONAL
            }

        Sublimation(
            stateId = m.stateId,
            zenithId = m.zenithId,
            name = m.name,
            rarity = m.rarity,
            slotColorPattern = m.slotColorPattern,
            maxLevel = maxLevel,
            kind = kind,
            solverChoosable = solverChoosable,
            condition = condition,
            effects = merged,
            conversion = null,
            // Synthesized English (the client renders sub tooltips dynamically — there is no stored string).
            // Null when nothing is reconstructable: ~75 forced-only subs are pure combat scripts / state-appliers
            // whose effect text isn't in the declarative tables; the GUI degrades to name + rarity for those.
            rawText = synthesizeRawText(condition, merged).ifBlank { null }
        )
    }
}

// ----- criterion-script parsing -----

/** One parsed clause of an `effect_criterion` script. */
private sealed interface CritAtom {
    /** A build-static start-of-combat condition (holds for the whole sublimation). */
    data class Cond(
        val condition: SublimationCondition,
    ) : CritAtom

    /** A per-scenario gate (attaches to the guarded effect). */
    data class Gate(
        val berserk: Boolean = false,
        val ranged: Boolean = false,
        val minLevel: Int? = null,
    ) : CritAtom

    /** Recognized but irrelevant to the static model (anti-stack guards, on-crit triggers, `True`, …). */
    data object Ignore : CritAtom

    /** Unrecognized — forces the sublimation to forced-only (conservative). */
    data object Unknown : CritAtom
}

private val RE_AP_ODD = Regex("""GetCharac\("AP",\s*"\w+"\)\s*%\s*2\)?\s*==\s*1""")
private val RE_DODGE_LT_LEVEL = Regex("""GetCharac\("DODGE",\s*"\w+"\)\s*<\s*GetLevel""")
private val RE_MIN_LEVEL = Regex("""GetLevel\("\w+"\)\s*>=\s*(\d+)""")
private val RE_BERSERK = Regex("""GetCharacInPct\("HP",\s*"\w+"\)\s*<\s*50""")
private val RE_CMP = Regex("""GetCharac(?:Max)?\("(\w+)",\s*"\w+"\)\s*(<=|>=|==)\s*(-?\d+)""")
private val SECONDARY_MASTERY_CRITERION_TOKENS =
    setOf("MELEE_DMG", "RANGED_DMG", "BERSERK_DMG", "CRITICAL_BONUS", "BACKSTAB_BONUS", "HEAL_IN_PERCENT")

/**
 * Parses an `effect_criterion` script into [CritAtom]s. The script language for sublimations is a small,
 * finite vocabulary (`GetCharac`/`GetCharacMax`/`GetCharacInPct`/`GetLevel` comparisons, parity, state
 * guards). An empty/`True` criterion is unconditional; any clause we don't recognize yields [CritAtom.Unknown]
 * so the conservative classifier drops the sublimation from the solver-choosable set.
 */
private fun parseCriterion(criterion: String): List<CritAtom> {
    val crit = criterion.trim()
    if (crit.isEmpty() || crit.equals("True", ignoreCase = true)) return listOf(CritAtom.Ignore)
    return crit
        .replace('\n', ' ')
        .split(Regex("""\s+and\s+"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { atom -> classifyAtom(atom) }
}

private fun classifyAtom(atom: String): CritAtom {
    // Gates first (scenario-specific guards).
    if (atom.contains("IsTriggeredByMeleeEffect")) {
        return if (atom.startsWith("not ")) CritAtom.Gate(ranged = true) else CritAtom.Unknown
    }
    if (RE_BERSERK.containsMatchIn(atom)) return CritAtom.Gate(berserk = true)
    RE_MIN_LEVEL.find(atom)?.let { return CritAtom.Gate(minLevel = it.groupValues[1].toInt()) }

    // Build-static conditions.
    if (RE_AP_ODD.containsMatchIn(atom)) return CritAtom.Cond(SublimationCondition(SublimationConditionType.AP_ODD))
    if (RE_DODGE_LT_LEVEL.containsMatchIn(atom)) {
        return CritAtom.Cond(SublimationCondition(SublimationConditionType.DODGE_LT_PCT_OF_LEVEL, value = 100))
    }
    RE_CMP.find(atom)?.let { m ->
        val charac = m.groupValues[1]
        val op = m.groupValues[2]
        val n = m.groupValues[3].toInt()
        val type =
            when {
                charac == "AP" && op == "<=" -> SublimationConditionType.AP_AT_MOST
                charac == "AP" && op == ">=" -> SublimationConditionType.AP_AT_LEAST
                charac == "FEROCITY" && op == "<=" -> SublimationConditionType.CRIT_AT_MOST
                charac == "FEROCITY" && op == ">=" -> SublimationConditionType.CRIT_AT_LEAST
                charac == "BLOCK" && op == ">=" -> SublimationConditionType.BLOCK_AT_LEAST
                charac == "RANGE" && op == "<=" -> SublimationConditionType.RANGE_AT_MOST
                charac == "RANGE" && op == ">=" -> SublimationConditionType.RANGE_AT_LEAST
                charac == "RANGE" && op == "==" -> SublimationConditionType.RANGE_EXACT
                charac in SECONDARY_MASTERY_CRITERION_TOKENS && op == "<=" -> SublimationConditionType.SECONDARY_MASTERIES_AT_MOST
                else -> null
            }
        return type?.let { CritAtom.Cond(SublimationCondition(it, value = n)) } ?: CritAtom.Unknown
    }

    // Recognized-but-irrelevant guards.
    if (atom.startsWith("not HasState") ||
        atom.contains("IsTriggeringEffectCritical") ||
        atom.startsWith("HasState")
    ) {
        return CritAtom.Ignore
    }
    return CritAtom.Unknown
}

// ----- English rawText synthesis (display + searchable; the dynamic client tooltip has no static string) -----

private val PERCENT_CHARACS =
    setOf(Characteristic.DAMAGE_INFLICTED, Characteristic.BLOCK_PERCENTAGE, Characteristic.CRITICAL_HIT)

private val CHARAC_LABEL: Map<Characteristic, String> =
    mapOf(
        Characteristic.DAMAGE_INFLICTED to "Damage Inflicted",
        Characteristic.WILLPOWER to "Force of Will",
        Characteristic.RESISTANCE_CRITICAL to "Critical Resistance",
        Characteristic.RESISTANCE_BACK to "Rear Resistance",
        Characteristic.RESISTANCE_ELEMENTARY to "Elemental Resistance",
        Characteristic.CRITICAL_HIT to "Critical Hit",
        Characteristic.BLOCK_PERCENTAGE to "Block",
        Characteristic.ACTION_POINT to "AP",
        Characteristic.MOVEMENT_POINT to "MP",
        Characteristic.WAKFU_POINT to "WP",
        Characteristic.MAX_ACTION_POINT to "max AP",
        Characteristic.MAX_MOVEMENT_POINT to "max MP",
        Characteristic.RANGE to "Range",
        Characteristic.LOCK to "Lock",
        Characteristic.DODGE to "Dodge",
        Characteristic.CONTROL to "Control"
    )

internal fun synthesizeRawText(
    condition: SublimationCondition?,
    effects: List<SublimationEffect>,
): String {
    val parts = ArrayList<String>()
    condition?.let { parts.add(conditionText(it)) }
    effects.forEach { parts.add(effectText(it)) }
    return parts.joinToString(" | ")
}

private fun effectText(e: SublimationEffect): String {
    val label =
        CHARAC_LABEL[e.characteristic] ?: e.characteristic.name
            .lowercase()
            .replace('_', ' ')
    val unit = if (e.characteristic in PERCENT_CHARACS) "%" else ""
    val sign = if (e.value >= 0) "+" else ""
    val gate = gateText(e.scenarioGate)
    return "$sign${e.value}$unit $label$gate"
}

private fun gateText(g: ScenarioGate?): String {
    if (g == null) return ""
    val tags = ArrayList<String>()
    if (g.berserk == true) tags.add("berserk")
    if (g.ranged == true) tags.add("ranged")
    g.minCharacterLevel?.let { tags.add("lvl $it+") }
    return if (tags.isEmpty()) "" else " (" + tags.joinToString(" + ") + ")"
}

private fun conditionText(c: SublimationCondition): String =
    when (c.type) {
        SublimationConditionType.AP_AT_MOST -> "If AP ≤ ${c.value}"
        SublimationConditionType.AP_AT_LEAST -> "If AP ≥ ${c.value}"
        SublimationConditionType.AP_EXACT -> "If AP = ${c.value}"
        SublimationConditionType.AP_ODD -> "If odd AP"
        SublimationConditionType.CRIT_AT_MOST -> "If Critical Hit ≤ ${c.value}%"
        SublimationConditionType.CRIT_AT_LEAST -> "If Critical Hit ≥ ${c.value}%"
        SublimationConditionType.BLOCK_AT_LEAST -> "If Block ≥ ${c.value}%"
        SublimationConditionType.RANGE_AT_MOST -> "If Range ≤ ${c.value}"
        SublimationConditionType.RANGE_AT_LEAST -> "If Range ≥ ${c.value}"
        SublimationConditionType.RANGE_EXACT -> "If Range = ${c.value}"
        SublimationConditionType.DODGE_LT_PCT_OF_LEVEL -> "If Dodge < ${c.value}% of level"
        SublimationConditionType.SECONDARY_MASTERIES_AT_MOST -> "If secondary masteries ≤ ${c.value}"
        SublimationConditionType.WEAPON_TYPE_EQUIPPED -> "If ${c.text} equipped"
        SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_REAR -> "If highest elemental mastery > rear mastery"
        SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_HEALING -> "If highest elemental mastery > healing mastery"
        SublimationConditionType.OTHER -> "Conditional"
    }
