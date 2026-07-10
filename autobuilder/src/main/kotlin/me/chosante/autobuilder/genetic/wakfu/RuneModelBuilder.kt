package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpModel
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ELEMENTARY_MASTERIES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ELEMENTARY_RESISTANCES
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.RuneType

// RuneModelBuilder — the per-search rune CP-SAT modelling (single-type fold vs per-stat counts, socket caps,
// equipped-only gating) extracted from the WakfuBuildSolver object (B1 of docs/code-review-followups.md).

/**
 * Models runes as extra per-item allocatable stats. For each socketable equipped item and each
 * requested rune-coverable stat, an integer var counts how many runes of that stat sit in the
 * item's sockets; the per-item sum is capped at the item's socket count and forced to 0 when the
 * item is not equipped. The rune *value* per (stat, item slot, character level) is a constant
 * (best-achievable: max rune level + WakForge doubling), so runes plug straight into the stat term
 * loop in [StatBuilder.prePercentStat] and need no special-casing in the objective or scorer.
 */
internal fun CpModel.createRuneModel(
    params: WakfuBestBuildParams,
    allEquips: List<Equipment>,
    equipVars: Map<Equipment, IntVar>,
    runes: List<RuneType>,
    allowRuneFold: Boolean,
    // Stats a dangerous (≤/exact/parity) conditional sub reads (null = un-analyzable / forced). A modeled
    // rune feeding one of these makes most-masteries exact-fill unsound — see [fillSockets] below.
    subPinnedStats: Set<Characteristic>?,
    // Test seam: force the rune cap back to `≤` (no exact fill), for the exact-fill==≤ soundness lock.
    forceRuneLeq: Boolean,
): RuneModel {
    if (runes.isEmpty()) return RuneModel.EMPTY
    val runeById = runes.associateBy { it.id }
    val runeByCharacteristic = runes.associateBy { it.characteristic }

    // Global forced runes (CLI --forced-runes): "≥1 rune of this stat socketed somewhere".
    val forcedNames = params.forcedRunes.map { it.lowercase() }.toSet()
    val globalForcedRuneStats =
        runes
            .filter { it.name.fr.lowercase() in forcedNames || it.name.en.lowercase() in forcedNames }
            .map { it.characteristic }
            .toSet()

    // Per-item forced runes (GUI): pin a multiset of rune ids onto a specific carrier item. Keyed by
    // the item's French name (like forcedItems); resolve each id to its characteristic and count the
    // required runes per characteristic.
    val perItemForced: Map<String, Map<Characteristic, Int>> =
        params.forcedRunesByItem
            .mapKeys { (name, _) -> name.lowercase() }
            .mapValues { (_, ids) ->
                ids
                    .mapNotNull { runeById[it]?.characteristic }
                    .groupingBy { it }
                    .eachCount()
            }.filterValues { it.isNotEmpty() }
    val perItemForcedStats = perItemForced.values.flatMapTo(mutableSetOf()) { it.keys }

    val forcedRuneStats = globalForcedRuneStats + perItemForcedStats
    // Auto-fill runes only when enabled; forced runes are modeled regardless of that toggle.
    if (!params.useRunes && forcedRuneStats.isEmpty()) return RuneModel.EMPTY

    val runeStats =
        (if (params.useRunes) relevantRuneStats(params, runeByCharacteristic.keys) else emptySet()) + forcedRuneStats
    if (runeStats.isEmpty()) return RuneModel.EMPTY

    // Exact socket fill — pin `Σ runeCount = slots·selected` (instead of `≤`) so the proven optimum never
    // leaves a socket empty. It removes every never-optimal "underfill" assignment from the integer search
    // (a pure search-space cut that helps the proof close) AND fixes the "fewer than max runes" builds.
    //  - MAX-DAMAGE: the generic elemental-mastery rune is NOT a secondary mastery and only ever raises the
    //    damage objective, so it backfills any socket without tripping a secondary/AP/crit/range "at most" sub
    //    condition, and overshooting a required target is unpenalised (the score caps actual at target —
    //    coerceAtMost in FindMaxDamageScoring). So filling is always free. (Unchanged.)
    //  - MOST-MASTERIES: the objective is monotone non-decreasing in every modeled rune stat — masteries,
    //    shortfall-only required targets, the DI fold, the min-over-elements — so underfill is never optimal.
    //    The only risk is a dangerous (≤/exact/parity) conditional sub: forcing fill could push the stat it
    //    reads over the cap and flip the sub off. But the per-stat distribution is free (mixing preserved),
    //    so as long as ONE modeled rune stat is *not* read by a dangerous condition, every socket can be
    //    filled with that "safe filler" rune (HP, elemental, …) — monotone-beneficial and breaks no sub — so
    //    exact-fill keeps the optimum. It is unsound only when EVERY modeled rune stat is dangerous (a
    //    self-contradictory request: the sole rune-relevant stat is itself the capped one). [subPinnedStats]
    //    is the dangerous set (null ⇒ un-analyzable/forced ⇒ stay safe with `≤`). Locked sound by an
    //    adversarial soundness review + the exact-fill==≤ optimum test. The single-type FOLD below stays
    //    max-damage-only, so most-masteries keeps the integer-count model (intra-item rune MIXING preserved).
    val maxDamageFreeFill =
        params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE &&
            Characteristic.MASTERY_ELEMENTARY in runeStats
    val mostMasteriesExactFill =
        params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
            subPinnedStats != null &&
            runeStats.any { it !in subPinnedStats }
    val fillSockets = !forceRuneLeq && (maxDamageFreeFill || mostMasteriesExactFill)
    // Single-type-per-item rune FOLD (max-damage, no forced runes, no secondary-cap>0 sub in play —
    // [allowRuneFold]): because a rune's value is uniform across an item's sockets (doubling is per item
    // SLOT, not per socket — see RuneType.valueOn), filling an item entirely with its single best-value
    // type is ≥ any mix, so mixing types within ONE item is freedom the optimum never uses. Modelling each
    // item's choice as ONE boolean pick per type (Σ pick = selected) instead of an integer count 0..slots
    // collapses the rune search to binary decisions — far easier for CP-SAT to PROVE — with the SAME
    // reachable stat contributions (a pick contributes slots·coeff; see baseTermsFor + the 0..1 leaf seed).
    // The solver still chooses the type per item (build-dependent: mastery vs crit-mastery, elemental vs a
    // secondary for the secondary=0 subs) and items still differ — only the never-optimal intra-item mix is
    // dropped. Gated to where it is provably sound; forced runes / secondary-cap>0 subs keep the count model.
    val singleTypePerItem = allowRuneFold && maxDamageFreeFill && forcedRuneStats.isEmpty()
    val maxDamageMasteryRuneStats =
        if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
            buildSet {
                val scenario = params.damageScenario
                // Intentionally NOT scenarioMasteryStats(): runes route all elemental mastery through the
                // single GENERIC elemental-mastery rune (no per-element rune exists), so the specific
                // element mastery is deliberately omitted here.
                add(Characteristic.MASTERY_ELEMENTARY)
                add(scenario.rangeBand.masteryCharacteristic)
                if (scenario.orientation.grantsRearMastery) add(Characteristic.MASTERY_BACK)
                if (scenario.berserk) add(Characteristic.MASTERY_BERSERK)
                if (scenario.healing) add(Characteristic.MASTERY_HEALING)
            }
        } else {
            emptySet()
        }
    val maxDamageRuneChoiceCollapse =
        singleTypePerItem &&
            params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE &&
            runeStats.all { it == Characteristic.MASTERY_CRITICAL || it in maxDamageMasteryRuneStats }
    val runeTypeByVar = HashMap<IntVar, RuneType>()
    val coefficientByVar = HashMap<IntVar, Long>()
    val extraTerms = mutableMapOf<Characteristic, MutableList<Term>>()
    val suppressedBy = HashMap<Pair<Equipment, Characteristic>, IntVar>()
    val runeVars = mutableMapOf<Equipment, Map<Characteristic, IntVar>>()
    for (equip in allEquips) {
        val slots = equip.maxShardSlots
        if (slots <= 0) continue
        if (maxDamageRuneChoiceCollapse) {
            // Pure max-damage only cares about two rune effects:
            //  - M-feeding mastery (elemental/range/back/berserk/healing all enter the same M sum);
            //  - critical mastery.
            // Pick the best M-feeding rune for this carrier, then keep the crit-mastery alternative only
            // when its carrier-specific value is larger. If crit's value is ≤ the M rune's value, M
            // dominates it for every crit rate in [0,100] because dGraw/dM = 400+crit ≥ 5*crit = dGraw/dK.
            // NOTE: this dominance is tied to perHitDamageScore's exact Graw coefficients (400·M, 5·K); if
            // those ever change, re-verify the inequality before trusting this fold.
            val choices = LinkedHashMap<Characteristic, Pair<RuneType, Long>>()
            val bestMasteryRune =
                maxDamageMasteryRuneStats
                    .mapNotNull { runeByCharacteristic[it] }
                    .map { it to it.valueOn(equip.itemType, equip.level).toLong() }
                    .maxByOrNull { it.second }
            if (bestMasteryRune != null) {
                choices[params.damageScenario.rangeBand.masteryCharacteristic] = bestMasteryRune
            }
            runeByCharacteristic[Characteristic.MASTERY_CRITICAL]?.let { critRune ->
                val critValue = critRune.valueOn(equip.itemType, equip.level).toLong()
                val masteryValue = bestMasteryRune?.second ?: 0L
                if (critValue > masteryValue) {
                    choices[Characteristic.MASTERY_CRITICAL] = critRune to critValue
                }
            }
            if (choices.isEmpty()) continue

            val perStat =
                if (choices.size == 1) {
                    // The single surviving choice is forced whenever the item is equipped: substitute the
                    // equipment variable directly and skip a redundant rune bool + equality.
                    mapOf(choices.keys.single() to equipVars.getValue(equip))
                } else if (
                    choices.size == 2 &&
                    choices.containsKey(params.damageScenario.rangeBand.masteryCharacteristic) &&
                    choices.containsKey(Characteristic.MASTERY_CRITICAL)
                ) {
                    val masteryStat = params.damageScenario.rangeBand.masteryCharacteristic
                    val masteryChoice = choices.getValue(masteryStat)
                    val critVar = newBoolVar("runePick_${equip.equipmentId}_${Characteristic.MASTERY_CRITICAL.name}")
                    addLessOrEqual(critVar, equipVars.getValue(equip))
                    // M is the default rune on the equipment var; choosing crit suppresses that default.
                    extraTerms
                        .getOrPut(masteryStat) { mutableListOf() }
                        .add(Term(critVar, -masteryChoice.second * slots.toLong()))
                    suppressedBy[equip to masteryStat] = critVar
                    mapOf(
                        masteryStat to equipVars.getValue(equip),
                        Characteristic.MASTERY_CRITICAL to critVar
                    )
                } else {
                    val vars = choices.keys.associateWith { stat -> newBoolVar("runePick_${equip.equipmentId}_${stat.name}") }
                    val pickExpr = LinearExpr.newBuilder()
                    vars.values.forEach { pickExpr.addTerm(it, 1L) }
                    pickExpr.addTerm(equipVars.getValue(equip), -1L)
                    addEquality(pickExpr.build(), 0L)
                    vars
                }
            for ((stat, choice) in choices) {
                val v = perStat.getValue(stat)
                runeTypeByVar[v] = choice.first
                coefficientByVar[v] = choice.second
            }
            runeVars[equip] = perStat
        } else if (singleTypePerItem) {
            // One boolean per type; exactly one type fills all the item's sockets when equipped, none otherwise.
            val perStat = runeStats.associateWith { stat -> newBoolVar("runePick_${equip.equipmentId}_${stat.name}") }
            val pickExpr = LinearExpr.newBuilder()
            perStat.values.forEach { pickExpr.addTerm(it, 1L) }
            pickExpr.addTerm(equipVars.getValue(equip), -1L)
            addEquality(pickExpr.build(), 0L)
            runeVars[equip] = perStat
        } else {
            val perStat = runeStats.associateWith { stat -> newIntVar(0, slots.toLong(), "rune_${equip.equipmentId}_${stat.name}") }
            // Sockets only count when the item is equipped: Σ runeCount {= max-damage | ≤ other modes} slots·selected.
            val capExpr = LinearExpr.newBuilder()
            perStat.values.forEach { capExpr.addTerm(it, 1L) }
            capExpr.addTerm(equipVars.getValue(equip), -slots.toLong())
            if (fillSockets) addEquality(capExpr.build(), 0L) else addLessOrEqual(capExpr.build(), 0L)
            runeVars[equip] = perStat
        }
    }
    // Global forced runes must be socketed at least once across the build.
    for (stat in globalForcedRuneStats) {
        val countExpr = LinearExpr.newBuilder()
        var any = false
        for ((_, perStat) in runeVars) {
            perStat[stat]?.let {
                countExpr.addTerm(it, 1L)
                any = true
            }
        }
        if (any) addGreaterOrEqual(countExpr.build(), 1L)
    }
    // Per-item forced runes: for each named carrier, the rune-count var(s) for the equipped item
    // matching that name must reach the required count. We sum over every same-named candidate (only
    // one can be equipped, and a non-equipped item's rune vars are pinned to 0 by the socket cap), so
    // this both pins the runes onto that item AND forces one such item to be equipped.
    for ((name, byCharacteristic) in perItemForced) {
        val matching = allEquips.filter { it.name.fr.lowercase() == name && it.maxShardSlots > 0 }
        if (matching.isEmpty()) continue
        for ((stat, count) in byCharacteristic) {
            val countExpr = LinearExpr.newBuilder()
            var any = false
            for (equip in matching) {
                runeVars[equip]?.get(stat)?.let {
                    countExpr.addTerm(it, 1L)
                    any = true
                }
            }
            if (any) addGreaterOrEqual(countExpr.build(), count.toLong())
        }
    }
    return RuneModel(runeByCharacteristic, runeVars, singleTypePerItem, runeTypeByVar, coefficientByVar, extraTerms, suppressedBy)
}

/**
 * The rune-coverable stats worth modelling for this request: requested stats that have a rune.
 * Elemental masteries (specific or generic) all route to the single generic elemental-mastery rune
 * (there is no per-element mastery rune); the aggregate resistance request expands to the four
 * per-element resistance runes. Mirrors the elemental folding the scorers/solver already do.
 */
private fun relevantRuneStats(
    params: WakfuBestBuildParams,
    runeCharacteristics: Set<Characteristic>,
): Set<Characteristic> {
    val result = mutableSetOf<Characteristic>()
    for (targetStat in params.targetStats) {
        when (val characteristic = targetStat.characteristic) {
            Characteristic.MASTERY_ELEMENTARY, in ELEMENTARY_MASTERIES -> result.add(Characteristic.MASTERY_ELEMENTARY)
            Characteristic.RESISTANCE_ELEMENTARY -> result.addAll(ELEMENTARY_RESISTANCES)
            else -> result.add(characteristic)
        }
    }
    // Max-damage mode socket-fills the masteries that drive the scenario's damage, even when they
    // are not in targetStats (which there only carry hard AP/MP/range/… constraints).
    if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
        val scenario = params.damageScenario
        // Intentionally NOT scenarioMasteryStats(): the rune-relevant set omits the specific element
        // mastery (generic-rune routing) and adds MASTERY_CRITICAL (a crit rune does exist).
        result.add(Characteristic.MASTERY_ELEMENTARY)
        result.add(scenario.rangeBand.masteryCharacteristic)
        result.add(Characteristic.MASTERY_CRITICAL)
        if (scenario.orientation.grantsRearMastery) result.add(Characteristic.MASTERY_BACK)
        if (scenario.berserk) result.add(Characteristic.MASTERY_BERSERK)
        if (scenario.healing) result.add(Characteristic.MASTERY_HEALING)
    }
    return result.intersect(runeCharacteristics)
}
