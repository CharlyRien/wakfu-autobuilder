package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpModel
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.scenarioGateMatches
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.Rarity
import me.chosante.common.Sublimation
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity

// SublimationModelBuilder — modelability predicates (isModelableSublimation/…) + the CP-SAT sublimation model
// (createSublimationModel: per-sub booleans, epic/relic carrier gating) extracted from the WakfuBuildSolver
// object (B1 of docs/code-review-followups.md). scenarioGateMatches stays shared in WakfuBuildSolver.

/**
 * Pure SHAPE predicate (P5.3 Inc 0): whether the certifier's DP can model [sub]'s effect kind + condition
 * at ALL, independent of the solver-choosable POLICY. The choosable wrapper [isModelableSublimation] adds
 * the policy filters (solverChoosable + mode/scenario usability) on top of this. (FORCED subs no longer
 * route through here — the certifier pass classifies them directly, mirroring `appliesVar`:
 * combat-conditional = inert, unconditional/unsupported-condition = constants, supported condition =
 * per-state gated credit; see the forced-sub classification in `certifyMaxPerHitAtApPass`.)
 */
private fun isModelableSubShape(sub: Sublimation): Boolean =
    when (sub.kind) {
        // A conversion applies via appliesVar, which only enforces a SUPPORTED condition — a conversion carrying
        // an UNsupported condition would move stats unconditionally (over-credit), so its shape is unmodelable.
        SublimationKind.CONVERSION -> sub.conversion != null && (sub.condition == null || sub.condition!!.type in SUPPORTED_SUB_CONDITIONS)
        SublimationKind.STATIC_CONDITIONAL -> sub.condition != null && sub.condition!!.type in SUPPORTED_SUB_CONDITIONS
        SublimationKind.FLAT -> true
        SublimationKind.COMBAT_CONDITIONAL -> false
    }

/** A solver-choosable sub the engine can correctly model in this request's mode/scenario. */
private fun isModelableSublimation(
    sub: Sublimation,
    params: WakfuBestBuildParams,
): Boolean {
    if (!sub.solverChoosable) return false
    // Conversions are handled by a dedicated path; static-conditionals need a supported condition.
    if (!isModelableSubShape(sub)) return false
    // Backstop for the degenerate most-masteries/precision request with NO maximizable mastery to protect
    // (only AP/MP/HP/range targets). There the DI fold `mastery × (1 + DI/100)` is structurally 0 — nothing
    // for the DI factor to multiply — so a damage-reducing sub would otherwise be free for the overshoot
    // tie-breaker to grab (the old Enutrof failure mode for required-only requests). When ANY maximizable
    // mastery is requested the fold governs DI on merit, so this never fires. Max-damage models DI directly.
    if (dropsDamageWithNoMasteryToProtect(sub, params)) return false
    // Best-element concentration (Elemental Concentration): its sound model — "+DI gated so the scenario element
    // is the build's strongest" — needs a SINGLE fixed damage element to protect. That holds in max-damage with
    // one candidate element (MaxDamageSearch enumerates one element per solve). Elsewhere (multi-element / boss
    // max-damage, most-masteries, precision) it stays forced-input-only — a clean follow-up.
    if (sub.bestElementConcentration != null) {
        return params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE &&
            params.damageScenario.candidateElements().size == 1
    }
    // It must be able to contribute *something* in this mode/scenario. A perStatStep ramp (Featherweight)
    // contributes via a build-stat-driven term (no flat effect), so it counts too.
    val hasUsableEffect = sub.effects.filterIsInstance<SublimationEffect.StatEffect>().any { scenarioGateMatches(it.scenarioGate, params) }
    return hasUsableEffect || sub.conversion != null || sub.perStatStep != null
}

/**
 * True when [sub] reduces % Damage Inflicted in a non-max-damage request that maximizes **no** mastery,
 * so the DI fold can't weigh it and it must not be auto-chosen (it could only ever cut real damage). When a
 * maximizable mastery IS requested, [StatBuilder.diAdjustedPerElementMasteryScore] already prices DI in, so a −DI sub
 * is taken only when its mastery gain outweighs the loss — and this returns false.
 */
private fun dropsDamageWithNoMasteryToProtect(
    sub: Sublimation,
    params: WakfuBestBuildParams,
): Boolean {
    if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return false
    if (params.targetStats.any { it.characteristic.isMaximizableMastery() }) return false
    val netDamageInflicted =
        sub.effects
            .filterIsInstance<SublimationEffect.StatEffect>()
            .filter { it.characteristic == Characteristic.DAMAGE_INFLICTED }
            .sumOf { it.magnitudeAtLevel(params.character.level) }
    return netDamageInflicted < 0
}

/**
 * Models the chosen/forced sublimations. Each modeled sub gets a [SublimationModel.subVars] boolean.
 * Epic/relic subs are gated to an equipped epic/relic item — their dedicated slot comes from that carrier
 * ([gateSublimationsOnCarrierItems]). Normal subs all share the same carrier eligibility in this optimistic
 * model (any equipped item with ≥3 sockets, colours rerollable), so carrier assignment collapses to one
 * aggregate capacity constraint: `selected normal subs ≤ equipped normal carriers`. That avoids a huge
 * `(sub × carrier item)` boolean grid while preserving feasibility; [solutionToBuild] assigns selected
 * normal subs greedily to equipped carriers for display. Normal subs do NOT consume rune sockets — golden
 * runes (colour-agnostic) form their ordered colour pattern while still carrying their stat, so a carrier
 * keeps a full set of runes alongside the sub. At most 10 normal + 1 epic + 1 relic sublimations per build. Effect contributions fold
 * into the stat term loop by [StatBuilder]; forced subs apply unconditionally (the user takes responsibility).
 *
 * A sub is only ever *chosen* when it improves the active objective. In max-damage mode that includes its
 * DI and scenario-gated effects; in most-masteries / precision modes (which don't maximize damage) a sub is
 * taken only when it raises a requested mastery or helps meet a required target — DI-only subs pay off
 * solely in max-damage mode.
 */
internal fun CpModel.createSublimationModel(
    params: WakfuBestBuildParams,
    allEquips: List<Equipment>,
    equipVars: Map<Equipment, IntVar>,
    sublimations: List<Sublimation>,
): SublimationModel {
    if (sublimations.isEmpty()) return SublimationModel.EMPTY
    val forcedNames = params.forcedSublimations.map { it.lowercase() }.toSet()
    val forcedSubs =
        sublimations.filter { it.name.fr.lowercase() in forcedNames || it.name.en.lowercase() in forcedNames }
    val choosableSubs =
        if (!params.useSublimations) {
            emptyList()
        } else {
            sublimations.filter { it !in forcedSubs && isModelableSublimation(it, params) }
        }
    if (forcedSubs.isEmpty() && choosableSubs.isEmpty()) return SublimationModel.EMPTY

    val subVars = LinkedHashMap<Sublimation, IntVar>()
    for (sub in forcedSubs) {
        val v = newBoolVar("subForced_${sub.stateId}")
        addEquality(v, 1L)
        subVars[sub] = v
    }
    for (sub in choosableSubs) {
        subVars[sub] = newBoolVar("sub_${sub.stateId}")
    }

    // Epic / relic sublimations can ONLY be applied to an epic / relic ITEM — the dedicated sub slot
    // comes FROM the carrier item (Wakfu: "epic Sublimations can only be applied to epic items, relic
    // only to relic items"). So Σ epicSub ≤ Σ epicItems and Σ relicSub ≤ Σ relicItems, modeled as
    // (Σ sub − Σ carrier ≤ 0). This also caps each sub at ≤1 since epic/relic items are themselves ≤1
    // (addBuildValidityConstraints). Forcing such a sub therefore forces its carrier item to be
    // equipped; with no carrier in the pool the request is correctly infeasible (it cannot be hosted).
    gateSublimationsOnCarrierItems(subVars, allEquips, equipVars, SublimationRarity.EPIC, Rarity.EPIC)
    gateSublimationsOnCarrierItems(subVars, allEquips, equipVars, SublimationRarity.RELIC, Rarity.RELIC)

    // Cumulable NORMAL subs can be socketed MULTIPLE times (each on its own carrier). Model the extra copies as
    // ordered booleans b[1..maxCopies-1] with b[i] ≤ b[i-1] ≤ the base subVar ("fill copies in order"). Each copy
    // consumes a normal slot AND a carrier (both sums below) and adds one more single-copy value to the objective
    // (buildSublimationTerms). The FLOOR maxCopies keeps every copy at full value ⇒ a k-copy sub scores exactly k×.
    // Epic/relic are never cumulable (maxCopies == 1).
    //
    // FORCED subs stack too. Their base var is pinned to 1, so `b[1] ≤ base` leaves every copy free: the solver
    // adds a copy exactly when the slot/carrier it costs is worth its value. Without this, forcing a cumulable
    // sub was strictly WORSE than leaving it choosable (1 copy instead of 2) — a trap, since forcing means "I
    // want this sub", not "I want less of it". Only subs whose effects are actually MODELED get copies: a forced
    // COMBAT_CONDITIONAL sub contributes nothing to the objective, so its copies would be valueless dead vars
    // the solver would zero anyway (in-game it can still be stacked by hand; the model just cannot price it).
    val copyVars = LinkedHashMap<Sublimation, List<IntVar>>()
    for (sub in subVars.keys) {
        if (sub.rarity != SublimationRarity.NORMAL || sub.maxCopies <= 1) continue
        if (!isModelableSublimation(sub, params)) continue
        var prev = subVars.getValue(sub)
        val copies = ArrayList<IntVar>(sub.maxCopies - 1)
        for (i in 1 until sub.maxCopies) {
            val c = newBoolVar("subCopy_${sub.stateId}_$i")
            addLessOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(c, 1L)
                    .addTerm(prev, -1L)
                    .build(),
                0L
            ) // c ≤ prev
            copies.add(c)
            prev = c
        }
        copyVars[sub] = copies
    }

    // Sublimation caps (Wakfu: 10 NORMAL + 1 epic + 1 relic). Epic/relic are gated to ≤1 each by their carrier
    // ITEM above (gateSublimationsOnCarrierItems), so only the normal count needs an explicit cap — they do NOT
    // share the normal budget. Normal subs also need a distinct ≥3-socket carrier: Σ normalCopies ≤ Σ carriers —
    // the carrier choice is objective-neutral, so total selected ≤ total carriers is both necessary and sufficient
    // for the complete bipartite matching (no (sub × carrier) grid). A cumulable sub contributes its base var PLUS
    // each copy var (one socketed slot / carrier each). Normal subs do NOT consume rune sockets: golden runes form
    // their ordered colour pattern while still carrying their stat.
    val normalSubs = subVars.keys.filter { it.rarity == SublimationRarity.NORMAL }
    if (normalSubs.isNotEmpty()) {
        val allNormalVars = normalSubs.flatMap { listOf(subVars.getValue(it)) + copyVars[it].orEmpty() }
        addLessOrEqual(LinearExpr.sum(allNormalVars.toTypedArray()), MAX_NORMAL_SUBLIMATIONS)

        val carrierItems = allEquips.filter { it.maxShardSlots >= NORMAL_SUB_SOCKET_COST }
        val capacity = LinearExpr.newBuilder()
        allNormalVars.forEach { capacity.addTerm(it, 1L) }
        carrierItems.forEach { capacity.addTerm(equipVars.getValue(it), -1L) }
        addLessOrEqual(capacity.build(), 0L)
    }

    return SublimationModel(subVars, forcedSubs.toSet(), params.character.level, copyVars)
}

/** Σ(subs of [subRarity]) ≤ Σ(equipped items of [itemRarity]): an epic/relic sub's slot comes from its carrier item. */
private fun CpModel.gateSublimationsOnCarrierItems(
    subVars: Map<Sublimation, IntVar>,
    allEquips: List<Equipment>,
    equipVars: Map<Equipment, IntVar>,
    subRarity: SublimationRarity,
    itemRarity: Rarity,
) {
    val subsOfRarity = subVars.filterKeys { it.rarity == subRarity }.values
    if (subsOfRarity.isEmpty()) return
    val gate = LinearExpr.newBuilder()
    subsOfRarity.forEach { gate.addTerm(it, 1L) }
    allEquips.filter { it.rarity == itemRarity }.forEach { gate.addTerm(equipVars.getValue(it), -1L) }
    addLessOrEqual(gate.build(), 0L)
}
