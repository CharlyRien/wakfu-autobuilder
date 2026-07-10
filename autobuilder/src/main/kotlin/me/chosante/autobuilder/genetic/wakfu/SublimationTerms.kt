package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import me.chosante.autobuilder.domain.perElementDiMastery
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.mulRange
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.scenarioGateMatches
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.sumVar
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind

// SublimationTerms — turns the chosen/forced sublimations into CP-SAT stat terms (buildSublimationTerms /
// buildPermanentSubTerms + the reified-condition / per-stat-step / best-element machinery), extracted from
// StatBuilder as extension functions (B1 of docs/code-review-followups.md). The CP-SAT mirror of the scorers'
// sublimation handling. Verbatim move; reads StatBuilder state by bare name via the receiver.

internal fun StatBuilder.buildSublimationTerms(): Map<Characteristic, List<Term>> {
    val map = mutableMapOf<Characteristic, MutableList<Term>>()
    for ((sub, _) in subModel.subVars) {
        // Combat-conditional subs (only ever forced) reserve their slot/sockets but their
        // situational effects are not auto-credited to the build (could be penalties / unmet).
        if (sub.kind == SublimationKind.COMBAT_CONDITIONAL) continue
        val applies = appliesVar(sub)
        if (sub.kind == SublimationKind.CONVERSION) {
            val conv = sub.conversion ?: continue
            // moved = clamp(percent% of the pre-sub `from` stat, >=0), zeroed when not applied.
            val raw = percentOf(preSubStat(conv.from), conv.percent, "subConv_${sub.stateId}")
            val moved = tBoolGate("subConvMoved_${sub.stateId}", raw, applies, 0L..STAT_ABS_MAX)
            conversionMovedVars += moved
            map.getOrPut(conv.to.foldedToUsableStat()) { mutableListOf() }.add(Term(moved, 1L))
            map.getOrPut(conv.from.foldedToUsableStat()) { mutableListOf() }.add(Term(moved, -1L))
            continue
        }
        val bec = sub.bestElementConcentration
        if (bec != null) {
            // Elemental Concentration: +damageInflictedBonus% Damage Inflicted, sound-gated so the max-damage
            // scenario element is the build's strongest — constrain `subVar ≤ strongest`. When it is NOT
            // strongest the in-game −penalty lands on the scored element and makes the sub strictly worse, so
            // such a build is never optimal: the guard excludes only dominated builds and never over-credits
            // the DI. (Only reachable in max-damage single-element — see isModelableSublimation.)
            model.addLessOrEqual(subModel.subVars.getValue(sub), scenarioElementStrongestVar(sub))
            map.getOrPut(Characteristic.DAMAGE_INFLICTED) { mutableListOf() }.add(Term(applies, bec.damageInflictedBonus.toLong()))
            continue
        }
        for (effect in sub.effects.filterIsInstance<SublimationEffect.StatEffect>()) {
            if (!scenarioGateMatches(effect.scenarioGate, params)) continue
            val magnitude = effect.magnitudeAtLevel(subModel.characterLevel).toLong()
            // A cumulable sub is socketed up to maxCopies times; each copy adds one more single-copy value
            // (constant marginal — see [Sublimation.maxCopies]). The gate vars are the base [applies] plus every
            // copy var; all are unconditional (a stackable sub has condition == null), so no extra reification.
            val gateVars =
                buildList {
                    add(applies)
                    addAll(subModel.copyVars[sub].orEmpty())
                }
            // Per-element DI in most-masteries: route into the element's OWN bucket so it only multiplies
            // that element's damage fold (NOT the global DI). Other modes (max-damage) keep it global —
            // there the single scenario element IS the global DI, so the existing routing is correct.
            val diMastery = effect.scenarioGate?.perElementDiMastery()
            if (diMastery != null &&
                effect.characteristic == Characteristic.DAMAGE_INFLICTED &&
                params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
            ) {
                val bucket = elementDiTermsByMastery.getOrPut(diMastery) { mutableListOf() }
                gateVars.forEach { bucket.add(Term(it, magnitude)) }
                continue
            }
            val bucket = map.getOrPut(effect.characteristic.foldedToUsableStat()) { mutableListOf() }
            gateVars.forEach { bucket.add(Term(it, magnitude)) }
        }
    }
    return map
}

/**
 * The per-element % Damage Inflicted routed to [mastery]'s element (sum of the chosen Brûlure/Gel/… subs
 * for that element), 0 when none — read only inside that element's own factor in
 * [diAdjustedPerElementMasteryScore]. Most-masteries only; empty in other modes.
 */
internal fun StatBuilder.elementDiVar(mastery: Characteristic): IntVar = model.sumVar("mmElemDI_${mastery.name}", elementDiTermsByMastery[mastery].orEmpty(), 0L, 0L, DAMAGE_DI_MAX)

/**
 * The reified [Sublimation.perStatStep] contribution of [sub] to its target stat:
 * `clamp(perStep·(actualStat(source) − threshold), 0, cap)`, gated to 0 when the sub is not chosen.
 * Memoized per sub. Built lazily (on the first [prePercentTermsFor] of the target, during objective build),
 * so `actualStat(source)` is ready; source ≠ target keeps it acyclic (it never re-enters the target loop).
 */
internal fun StatBuilder.perStatStepGatedVar(sub: Sublimation): IntVar =
    perStatStepVarCache.getOrPut(sub.stateId) {
        val spec = sub.perStatStep!!
        // scaled = perStep·source − perStep·threshold  (= perStep·(source − threshold)); clamp to [0, cap].
        val scaled =
            tSumNaive(
                "fwScaled_${sub.stateId}",
                listOf(Term(actualStat(spec.source), spec.perStep.toLong())),
                -(spec.perStep.toLong() * spec.threshold),
                -CLAMP_INTERMEDIATE_MAX,
                CLAMP_INTERMEDIATE_MAX
            )
        val clamped = tClamp(scaled, 0L, spec.cap.toLong(), "fwClamp_${sub.stateId}")
        val gated = tBoolGate("fwGate_${sub.stateId}", clamped, appliesVar(sub), 0L..spec.cap.toLong())
        subDerivedVars[gated] = sub
        gated
    }

/**
 * Reified boolean: the max-damage SCENARIO element is (weakly) the build's strongest element — its pre-combat
 * elemental mastery ≥ every other element's. Gates [Sublimation.bestElementConcentration] (Elemental
 * Concentration): only then does the −penalty spare the scored element, so crediting the +DI is exact. The
 * generic "+all elements" mastery is common to all four and cancels in the comparison, so the per-element
 * [preCombatStat] suffices (and matches the re-scorer, which compares the same per-element pre-combat totals).
 */
internal fun StatBuilder.scenarioElementStrongestVar(sub: Sublimation): IntVar =
    bestElementStrongestCache.getOrPut(sub.stateId) {
        val scenarioMastery = params.damageScenario.element.masteryCharacteristic
        val others = ELEMENT_MASTERY_CHARACTERISTICS.filter { it != scenarioMastery }
        val maxOther = model.newIntVar(-STAT_ABS_MAX, STAT_ABS_MAX, "becMaxOther_${sub.stateId}")
        model.addMaxEquality(maxOther, others.map { preCombatStat(it) }.toTypedArray())
        // reify (scenario elemental mastery − maxOther ≥ 0) ⇔ scenario element weakly strongest.
        val diff =
            model.sumVar(
                "becDiff_${sub.stateId}",
                listOf(Term(preCombatStat(scenarioMastery), 1L), Term(maxOther, -1L)),
                0L,
                -2 * STAT_ABS_MAX,
                2 * STAT_ABS_MAX
            )
        reifyGe(diff, 0L, "becStrongest_${sub.stateId}")
    }

/**
 * The PERMANENT (before-combat) sublimation contributions, grouped by the AP/MP/WP-folded stat — only
 * effects flagged [SublimationEffect.appliesBeforeCombat]. These feed [preCombatStat], the value a
 * start-of-combat condition reads. Gated by the raw `subVar` (these effects live only on condition-less
 * FLAT subs, so `subVar == appliesVar`), which keeps [reifyCondition] → [preCombatStat] acyclic: it
 * never pulls in a conditional sub's own (or any other sub's) reified-condition variable.
 */
internal fun StatBuilder.buildPermanentSubTerms(): Map<Characteristic, List<Term>> {
    val map = mutableMapOf<Characteristic, MutableList<Term>>()
    for ((sub, subVar) in subModel.subVars) {
        if (sub.kind == SublimationKind.COMBAT_CONDITIONAL || sub.kind == SublimationKind.CONVERSION) continue
        for (effect in sub.effects.filterIsInstance<SublimationEffect.StatEffect>()) {
            if (!effect.appliesBeforeCombat) continue
            if (!scenarioGateMatches(effect.scenarioGate, params)) continue
            val magnitude = effect.magnitudeAtLevel(subModel.characterLevel).toLong()
            val bucket = map.getOrPut(effect.characteristic.foldedToUsableStat()) { mutableListOf() }
            bucket.add(Term(subVar, magnitude))
            // Each socketed copy adds one more single-copy value (stackable subs are condition-less FLAT subs).
            for (copyVar in subModel.copyVars[sub].orEmpty()) bucket.add(Term(copyVar, magnitude))
        }
    }
    return map
}

/**
 * Boolean that gates a sub's contributions. For a solver-chosen STATIC_CONDITIONAL/CONVERSION sub
 * with a supported condition we constrain `subVar ≤ condHolds`, so the solver may only choose the
 * sub when it arranges the build to satisfy the condition (this is what makes it trade stats to
 * unlock lucrative conditions, and means a chosen sub's effect always applies) — the gate stays
 * `subVar` itself. A FORCED sub with a supported condition must stay EQUIPPED (its pinned `subVar`
 * reserves the slot/sockets) even in a build that breaks the condition — in game it is then simply
 * inert — so its effect gate is `subVar ∧ condHolds` instead: the search may break the condition,
 * it just stops being credited for the effect. (Crediting it unconditionally let the solver return
 * invalid builds — e.g. a forced CRIT_AT_MOST-30 DI sub "applying" at 40 pre-combat crit.) A forced
 * sub with an UNSUPPORTED condition type keeps the optimistic unconditional credit, as before.
 */
internal fun StatBuilder.appliesVar(sub: Sublimation): IntVar =
    appliesVarCache.getOrPut(sub) {
        val subVar = subModel.subVars.getValue(sub)
        val cond = sub.condition
        when {
            cond == null || cond.type !in SUPPORTED_SUB_CONDITIONS -> subVar
            sub !in subModel.forced -> {
                model.addLessOrEqual(subVar, reifyCondition(cond))
                subVar
            }
            else -> {
                val gated = and(subVar, reifyCondition(cond), "subForcedApplies_${sub.stateId}")
                // Register like [fwGate] so the certifier attributes the gated term to its sub
                // ([perSubValue]'s derived path) instead of folding an untracked var at ±STAT_ABS_MAX.
                tracker.record(gated, 0L..1L, "subForcedApplies_${sub.stateId}")
                subDerivedVars[gated] = sub
                gated
            }
        }
    }

internal fun StatBuilder.reifyLe(
    value: IntVar,
    n: Long,
    tag: String,
): IntVar {
    val b = model.newBoolVar(tag)
    model.addLessOrEqual(value, n).onlyEnforceIf(b)
    model.addGreaterOrEqual(value, n + 1).onlyEnforceIf(b.not())
    return b
}

internal fun StatBuilder.reifyGe(
    value: IntVar,
    n: Long,
    tag: String,
): IntVar {
    val b = model.newBoolVar(tag)
    model.addGreaterOrEqual(value, n).onlyEnforceIf(b)
    model.addLessOrEqual(value, n - 1).onlyEnforceIf(b.not())
    return b
}

internal fun StatBuilder.and(
    a: IntVar,
    b: IntVar,
    tag: String,
): IntVar {
    val out = model.newBoolVar(tag)
    model.addMultiplicationEquality(out, arrayOf(a, b))
    return out
}

/**
 * A reified boolean for a supported [SublimationCondition], evaluated against the **pre-combat**
 * (character-sheet) build stats — [preCombatStat], i.e. base + items + runes + skills + permanent subs.
 * Start-of-combat / conditional sub effects are deliberately excluded (see [preCombatStat]). The condition's
 * *meaning* (stat, comparison, threshold) comes from the shared [subConditionSpec]; only the reification into an
 * `IntVar` lives here — the scalar re-scorer evaluates the SAME spec with int math, so the two can't disagree.
 */
internal fun StatBuilder.reifyCondition(cond: SublimationCondition): IntVar {
    val tag = "subCond_${cond.type}_${cond.value ?: 0}_${appliesVarCache.size}"
    return when (val spec = subConditionSpec(cond, subModel.characterLevel)) {
        is SubConditionSpec.StatBound -> reifyStatBound(spec, tag)
        SubConditionSpec.NoOffhandOrTwoHanded -> reifyNoOffhandOrTwoHanded(tag)
        SubConditionSpec.AlwaysApplies -> model.newConstant(1L) // unsupported -> always-on (best-achievable)
    }
}

/**
 * Reifies a [SubConditionSpec.StatBound] `sum(stats) <comparison> threshold` on the pre-combat stats. A single
 * stat reifies directly on its [preCombatStat] (no wrapper var); a multi-stat bound (secondary masteries) sums
 * first — reproducing the exact model the hand-written per-type `when` built (same var names, bounds and tags).
 */
private fun StatBuilder.reifyStatBound(
    spec: SubConditionSpec.StatBound,
    tag: String,
): IntVar {
    val value =
        if (spec.stats.size == 1) {
            preCombatStat(spec.stats.single())
        } else {
            model.sumVar("secMast_$tag", spec.stats.map { preCombatStat(it) }, -STAT_ABS_MAX, STAT_ABS_MAX)
        }
    val n = spec.threshold.toLong()
    return when (spec.comparison) {
        ConditionComparison.AT_MOST -> reifyLe(value, n, tag)
        ConditionComparison.AT_LEAST -> reifyGe(value, n, tag)
        ConditionComparison.EXACT -> and(reifyLe(value, n, "${tag}_le"), reifyGe(value, n, "${tag}_ge"), tag)
    }
}

/**
 * Reifies "no off-hand and no two-handed weapon equipped": the sum of those slots' pick vars (each 0/1) is 0.
 * An empty pool is trivially satisfiable.
 */
private fun StatBuilder.reifyNoOffhandOrTwoHanded(tag: String): IntVar {
    val picks =
        allEquips
            .filter { it.itemType == ItemType.OFF_HAND_WEAPONS || it.itemType == ItemType.TWO_HANDED_WEAPONS }
            .map { equipVars.getValue(it) }
    return if (picks.isEmpty()) {
        model.newConstant(1L)
    } else {
        reifyLe(model.sumVar("offOr2H_$tag", picks, 0L, picks.size.toLong()), 0L, tag)
    }
}

/** Non-negative `percent`% of [value] (integer-floored), as a fresh variable. */
internal fun StatBuilder.percentOf(
    value: IntVar,
    percent: Int,
    name: String,
): IntVar {
    val scaledReach = mulRange(tracker.of(value), percent.toLong()..percent.toLong())
    val (sLo, sHi) = tracker.decl(scaledReach, -PRODUCT_ABS_MAX, PRODUCT_ABS_MAX)
    val scaled = model.newIntVar(sLo, sHi, "${name}_scaled")
    model.addEquality(scaled, LinearExpr.term(value, percent.toLong()))
    tracker.record(scaled, scaledReach, "${name}_scaled")

    val quotientReach = scaledReach.first / 100..scaledReach.last / 100
    val (qLo, qHi) = tracker.decl(quotientReach, -(PRODUCT_ABS_MAX / 100) - 1, (PRODUCT_ABS_MAX / 100) + 1)
    val quotient = model.newIntVar(qLo, qHi, "${name}_q")
    model.addDivisionEquality(quotient, scaled, model.newConstant(100L))
    tracker.record(quotient, quotientReach, "${name}_q")

    val positiveReach = maxOf(0L, quotientReach.first)..maxOf(0L, quotientReach.last)
    val (pLo, pHi) = tracker.decl(positiveReach, 0L, STAT_ABS_MAX)
    val positive = model.newIntVar(pLo, pHi, "${name}_pos")
    model.addMaxEquality(positive, arrayOf(quotient, model.newConstant(0L)))
    return tracker.record(positive, positiveReach, "${name}_pos")
}
