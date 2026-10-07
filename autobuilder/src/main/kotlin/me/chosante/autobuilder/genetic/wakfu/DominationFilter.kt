package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.forbiddenItemIds
import me.chosante.autobuilder.domain.holdsOnEvery
import me.chosante.autobuilder.domain.requiredItemIds
import me.chosante.autobuilder.domain.sheetCharacteristic
import me.chosante.autobuilder.domain.statGates
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ELEMENTARY_RESISTANCES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.MASTERY_RANDOM_BY_COUNT
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.RANDOM_RESISTANCES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.scenarioGateMatches
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.valueFor
import me.chosante.common.Characteristic
import me.chosante.common.CriterionComparison
import me.chosante.common.Equipment
import me.chosante.common.ExclusiveGroup
import me.chosante.common.ItemStatGate
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity

// Domination pre-filter (DominationFilter) extracted from the WakfuBuildSolver object (B1 of
// docs/code-review-followups.md): the pure monotone-objective item-domination relation + per-slot pool
// filter — no CP-SAT model or solver state. An item beaten on every relevant stat is never in an optimum,
// so it is dropped before the search.
//
// The contract (CERTIFIER_VERSION 53 audit, docs/perf-review-backlog.md §E): A may replace B in EVERY build of the
// model with no loss. Besides the stats, that means A must offer at least what B offers on every OTHER dimension the
// CP-SAT model or a certificate reads from an item — the slot (per-slot filter), the ≤1-epic / ≤1-relic budget, the
// epic / relic sublimation carrier, the rune sockets AND their value (the item's level caps the rune level), for
// rings, the "never two rings of the same name" rule, and the item EQUIP conditions (CERTIFIER_VERSION 57: a required
// item is never evicted, A's requirements / conflict partners / stat gates must be a subset of B's, and a gated stat
// never moves the wrong way). Each is a clause of
// [dominates] / [dominatedWithin] below; the search and every certificate read the SAME reduced pool, so a clause that
// is missing makes CP-SAT's OPTIMAL and the certificate's bound both wrong at once (a wrong "proven optimal" badge).

/**
 * The domination relation's parameters for a request — or `null` (from [dominationShape]) to not apply the
 * pre-filter at all. Empty [pinned] / null [compared] means full domination on every stat.
 *
 * Applies to **all three modes** — each maximizes an objective that is **monotone non-decreasing in every
 * characteristic** (more of any stat is never worse), so an item beaten on every stat is never needed:
 *  - max-damage: `throughput × perHit`, both ≥ 0;
 *  - precision: a sum of `min(actual, target)` terms — capped at the target, so overshoot is *neutral*, not
 *    penalized (my earlier "penalizes overshoot" claim was wrong);
 *  - most-masteries: `masteryScore × penaltyMultiplier`, the penalty CAPPED at the target (shortfall only)
 *    with overshoot rewarded by a tie-breaker. The product is monotone *where it matters*: the optimum
 *    always has `masteryScore ≥ 0` (the empty build already scores objective ≥ 0, so a negative-mastery
 *    build is strictly worse), and for `masteryScore ≥ 0` the dominance swap `(m+Δm)(μ+Δμ) ≥ mμ` holds.
 *
 * A **conditional** sublimation makes some stats non-monotone: a build may keep a weaker (e.g. low-secondary)
 * item *specifically* to satisfy a cap like `SECONDARY_MASTERIES_AT_MOST`, which domination would remove.
 * Rather than gate off, we pin **every stat a dangerous (≤ / exact / parity) condition reads** to equality,
 * so the swap can't move that stat's build sum and no sub can flip — while domination still fires across
 * every stat no condition touches. A `≥`-type condition stays satisfied under a `≥` swap on a beneficial
 * choosable sub, so it needs no pin. Returns `null` (gate off) for a forced item / rune-carrier, a forced
 * conditional sub (unknown effect direction), a condition that compares two build stats / is categorical
 * and can't be reduced to a stat pin, or a best-element concentration sub outside a single-element max-damage solve.
 */
internal data class DominationShape(
    val pinned: Set<Characteristic>,
    val compared: Set<Characteristic>? = null,
    // Stats where LOWER is better for the swap proof, so a dominator must be `≤` (not `≥`). Used for the three
    // non-scenario elemental masteries when a best-element concentration sub (Elemental Concentration) is
    // modelled: in a single-element solve they do nothing for the scored element and only risk flipping which
    // element is "strongest", so more of them is never beneficial — see the swap proof in the sub's decode.
    val minimized: Set<Characteristic> = emptySet(),
    // An EPIC (RELIC) sublimation can be modelled in this request, and only an EPIC (RELIC) item can carry it (the
    // model's `Σ epicSub ≤ Σ epicItem`): evicting the slot's epic item for a non-epic one would silently forbid
    // every epic sub. So an EPIC (RELIC) item may then only be dominated by another EPIC (RELIC) item.
    val epicCarriers: Boolean = false,
    val relicCarriers: Boolean = false,
    // The rune contract, null when no rune can be modelled (the old count-only socket clause is then all it needs).
    val runes: RuneDomination? = null,
    // Per (usable) stat, a sound reach of the OUT-OF-COMBAT sheet's extras ([outOfCombatExtrasReach]: the modelled subs'
    // permanent effects + the passives' flat stats; a stat absent from the map has none). With the out-of-combat caps it
    // decides which item stat gates can fail at all ([gateCanFail]). null = unknown: every gate is treated as one that can fail.
    val outOfCombatExtras: Map<Characteristic, LongRange>? = null,
)

/**
 * Whether [gate] can fail on some build of the request's model. A gate that holds on EVERY build is no restriction at all, so
 * domination treats it as NO gate (it neither blocks an eviction nor bounds a stat): `GetCharac("FEROCITY") > -10`, on 89
 * items, is implied by the `≥ −9` out-of-combat crit cap whenever no out-of-combat extra carries negative crit.
 *
 * The test is the model's own ([StatBuilder.applyItemStatGates]: `holdsOnEvery` on the out-of-combat reach, narrowed by the caps
 * through the shared [outOfCombatSheetReach]) on a reach that holds for ANY pool: the pre-sub part's whole variable domain
 * ([UNKNOWN_PRE_SUB_REACH]) — domination runs before the model, on the pool it is about to shrink — plus the extras' reach. That
 * reach contains the model's (tracked) one, so a gate domination ignores is one the model adds no constraint for either.
 */
internal fun DominationShape.gateCanFail(gate: ItemStatGate): Boolean {
    val extras = outOfCombatExtras ?: return true
    val characteristic = gate.sheetCharacteristic
    return !gate.holdsOnEvery(outOfCombatSheetReach(characteristic, UNKNOWN_PRE_SUB_REACH, extras[characteristic] ?: 0L..0L))
}

/**
 * A sound reach, per usable stat, of the extras the out-of-combat sheet adds to the pre-sub value ([StatBuilder.outOfCombatStat]):
 * the selected passives' flat stats (constants, summed exactly) and the PERMANENT, scenario-free effects of the modelled subs
 * ([buildOutOfCombatSubTerms]: FLAT / STATIC_CONDITIONAL subs only). A sub may be socketed several times, so a sub effect of a
 * sign opens that side of the reach entirely (±[STAT_ABS_MAX]) — loose, but sound; only a stat no modelled sub lowers keeps a
 * finite floor (crit on the shipped data: nothing lowers it out of combat).
 */
private fun outOfCombatExtrasReach(
    params: WakfuBestBuildParams,
    modelled: List<Sublimation>,
): Map<Characteristic, LongRange> {
    val constant = HashMap<Characteristic, Long>()
    for (passive in WakfuBuildSolver.resolvedPassives(params)) {
        for ((characteristic, value) in passive.flatStats) constant.merge(characteristic.foldedToUsableStat(), value.toLong(), Long::plus)
    }
    val lowered = HashSet<Characteristic>()
    val raised = HashSet<Characteristic>()
    for (sub in modelled) {
        if (sub.kind == SublimationKind.COMBAT_CONDITIONAL || sub.kind == SublimationKind.CONVERSION) continue
        for (effect in sub.effects.filterIsInstance<SublimationEffect.StatEffect>()) {
            if (!effect.appliesBeforeCombat || effect.scenarioGate != null) continue
            val magnitude = effect.magnitudeAtLevel(params.character.level)
            val characteristic = effect.characteristic.foldedToUsableStat()
            if (magnitude < 0) lowered += characteristic
            if (magnitude > 0) raised += characteristic
        }
    }
    return (constant.keys + lowered + raised).associateWith { c ->
        val k = constant[c] ?: 0L
        (if (c in lowered) -STAT_ABS_MAX else 0L) + k..(if (c in raised) STAT_ABS_MAX else 0L) + k
    }
}

/**
 * How runes constrain domination when the request can model them. A rune's value is fixed by the CARRIER's level (the
 * item's level caps the rune level — [RuneType.maxLevelForItemLevel]) and its slot (doubling — same slot here), so a
 * dominator must carry B's runes at no lower value: its rune-level cap must be ≥ B's whenever B has sockets.
 *
 * [exact]: a modelled rune type is a stat a dangerous condition reads (pinned: crit mastery under Critical Secret, a
 * secondary mastery under the Neutralité family, dodge under Furie) or a minimized stat. Then B's rune contribution on
 * it must be replicated EXACTLY (a higher-level rune on A would add to a capped sum and could flip the sub) ⇒ equal
 * rune-level caps. [oneTypePerItem]: the max-damage single-type fold / choice collapse fills ALL of an item's sockets
 * with ONE type (the collapse may even offer only capped types), so with [exact] the socket counts must match too —
 * A's extra sockets would otherwise be forced to carry more of a capped stat.
 */
internal data class RuneDomination(
    val exact: Boolean,
    val oneTypePerItem: Boolean,
)

/** The MAX_* riders folded into usable AP/MP/WP ([foldedToUsableStat]); pinned like the stats they fold into. */
private val MAX_RIDER_STATS =
    setOf(Characteristic.MAX_ACTION_POINT, Characteristic.MAX_MOVEMENT_POINT, Characteristic.MAX_WAKFU_POINTS)

internal fun dominationShape(
    params: WakfuBestBuildParams,
    sublimations: List<Sublimation>,
): DominationShape? {
    if (params.forcedItems.isNotEmpty() || params.forcedRunesByItem.isNotEmpty()) return null
    val forcedNames = params.forcedSublimations.map { it.lowercase() }.toSet()
    val pinned = mutableSetOf<Characteristic>()
    val conditionStats = mutableSetOf<Characteristic>()
    val subStats = mutableSetOf<Characteristic>()
    for (sub in sublimations) {
        val choosable = sub.solverChoosable && params.useSublimations
        val forced = sub.name.fr.lowercase() in forcedNames || sub.name.en.lowercase() in forcedNames
        if (!choosable && !forced) continue
        sub.effects
            .filterIsInstance<SublimationEffect.StatEffect>()
            .filter { scenarioGateMatches(it.scenarioGate, params) }
            .forEach { subStats += it.characteristic.foldedToUsableStat() }
        sub.conversion?.let { conversion ->
            subStats += conversion.from.foldedToUsableStat()
            subStats += conversion.to.foldedToUsableStat()
        }
        // A per-stat-step ramp (Poids Plume: MP → DI) is monotone non-decreasing in its source, so its source only has
        // to be COMPARED (≥) in max-damage. The shipped ramp's source (MP) is pinned anyway; this keeps a future ramp
        // on a stat max-damage does not otherwise compare from being evicted with its source.
        sub.perStatStep?.let { ramp ->
            subStats += ramp.source.foldedToUsableStat()
            subStats += ramp.target.foldedToUsableStat()
        }
        val condition = sub.condition ?: continue
        if (forced) return null // forced conditional sub: unknown effect direction ⇒ can't pin soundly
        when (condition.type) {
            SublimationConditionType.AP_AT_MOST, SublimationConditionType.AP_EXACT, SublimationConditionType.AP_ODD -> {
                pinned += Characteristic.ACTION_POINT
                conditionStats += Characteristic.ACTION_POINT
            }
            SublimationConditionType.CRIT_AT_MOST -> {
                pinned += Characteristic.CRITICAL_HIT
                conditionStats += Characteristic.CRITICAL_HIT
            }
            SublimationConditionType.CRITICAL_MASTERY_AT_MOST -> {
                pinned += Characteristic.MASTERY_CRITICAL
                conditionStats += Characteristic.MASTERY_CRITICAL
            }
            SublimationConditionType.RANGE_AT_MOST, SublimationConditionType.RANGE_EXACT -> {
                pinned += Characteristic.RANGE
                conditionStats += Characteristic.RANGE
            }
            SublimationConditionType.DODGE_LT_PCT_OF_LEVEL -> {
                pinned += Characteristic.DODGE
                conditionStats += Characteristic.DODGE
            }
            // EACH secondary is capped on its own: pinning every one of them keeps each per-stat read unchanged.
            SublimationConditionType.SECONDARY_MASTERIES_AT_MOST -> {
                pinned += SECONDARY_MASTERY_CHARACTERISTICS
                conditionStats += SECONDARY_MASTERY_CHARACTERISTICS
            }
            // Not solver-modelled (no choosable sub carries it; a carrier's effects apply unconditionally) — pinned like
            // AP_ODD all the same, should a future data refresh model it.
            SublimationConditionType.HEALING_MASTERY_AT_MOST -> {
                pinned += Characteristic.MASTERY_HEALING
                conditionStats += Characteristic.MASTERY_HEALING
            }
            // ≥-type: a ≥ swap on a beneficial choosable sub keeps the condition satisfied ⇒ no pin needed.
            SublimationConditionType.AP_AT_LEAST -> conditionStats += Characteristic.ACTION_POINT
            SublimationConditionType.CRIT_AT_LEAST -> conditionStats += Characteristic.CRITICAL_HIT
            SublimationConditionType.BLOCK_AT_LEAST -> conditionStats += Characteristic.BLOCK_PERCENTAGE
            SublimationConditionType.RANGE_AT_LEAST -> conditionStats += Characteristic.RANGE
            // Slot-occupancy condition: domination swaps stay within one ItemType ([filterDominatedPool]
            // is per-slot), so the off-hand/two-handed pick-var sum — hence this condition's truth value —
            // is invariant under every swap. No pin, no compared stat needed. (Gating off here silently
            // disabled domination for EVERY subs-on request once Light Weapons Expert became choosable —
            // pool 7884 vs 6567 at lvl-245 — costing the ~2.8× domination win on the default path.)
            SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED -> {}
            // Compares two build stats / categorical / weapon-category / unknown ⇒ can't reduce to a stat
            // pin ⇒ gate off. (WEAPON_TYPE_EQUIPPED stays gated: one ItemType can host different weapon
            // categories, so a same-slot swap CAN flip it — unlike the occupancy condition above.)
            SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_REAR, SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_HEALING,
            SublimationConditionType.WEAPON_TYPE_EQUIPPED,
            SublimationConditionType.OTHER,
            -> return null
        }
    }
    // The epic / relic CARRIER contract — on exactly the subs the model gives a variable ([modelledSublimations]).
    val (forcedModelled, choosableModelled) = modelledSublimations(params, sublimations)
    val modelled = forcedModelled + choosableModelled
    val epicCarriers = modelled.any { it.rarity == SublimationRarity.EPIC }
    val outOfCombatExtras = outOfCombatExtrasReach(params, modelled)
    val relicCarriers = modelled.any { it.rarity == SublimationRarity.RELIC }
    // Best-element concentration (Elemental Concentration) constrains `subVar ≤ "the scenario element is the strongest"`
    // wherever it is modelled. Its sound pin (the off-scenario elemental masteries MINIMIZED, below) needs a single
    // scenario element to protect; a FORCED one elsewhere (most-masteries / precision, a multi-element solve) would make
    // more of an off-element mastery INFEASIBLE, which no `≥` swap respects ⇒ gate off.
    val ecModelled = modelled.any { it.bestElementConcentration != null }
    val singleElementMaxDamage =
        params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE &&
            params.damageScenario.candidateElements().size == 1
    if (ecModelled && !singleElementMaxDamage) return null
    // The out-of-combat sheet caps (16 AP / 8 MP / 20 WP, [StatBuilder.applyOutOfCombatCaps]) are HARD
    // constraints in EVERY mode, so a dominator with strictly more AP/MP/WP could break the cap the evicted
    // item respected — pruning a cap-tight optimum. Pin them in all three modes (previously max-damage only:
    // most-masteries/precision compared them like any other stat, a latent unsoundness on cap-tight pools).
    pinned += Characteristic.ACTION_POINT
    pinned += Characteristic.MOVEMENT_POINT
    pinned += Characteristic.WAKFU_POINT
    // ...and so are their MAX_* riders: the solver's usable AP/MP/WP is `valueFor` = raw + MAX_* (Les
    // Affamées' −1 max AP, Issé Sceau's −2 max WP). Pinning only the raw line let an item with equal raw AP
    // but a −1 MAX_AP evict one WITHOUT the debit — dropping a real +1 usable AP from the pool, so an
    // "optimal" over the reduced pool could sit below the true optimum (pre-release review 2026-10-01).
    pinned += MAX_RIDER_STATS

    if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
        return DominationShape(
            pinned,
            epicCarriers = epicCarriers,
            relicCarriers = relicCarriers,
            runes = runeDomination(params, pinned),
            outOfCombatExtras = outOfCombatExtras
        )
    }

    // Max-damage does not care about every sheet stat. Comparing only stats that can affect the objective,
    // out-of-combat caps, sublimation conditions/effects/conversions, or random-element folding makes
    // domination much sharper while preserving the swap proof.
    val scenario = params.damageScenario
    val compared =
        buildSet {
            add(Characteristic.ACTION_POINT)
            add(Characteristic.MOVEMENT_POINT)
            add(Characteristic.WAKFU_POINT)
            // A pin is only enforced on compared stats (see [dominates]).
            addAll(MAX_RIDER_STATS)
            add(Characteristic.CRITICAL_HIT)
            add(Characteristic.DAMAGE_INFLICTED)
            add(Characteristic.MASTERY_CRITICAL)
            addAll(scenarioMasteryStats(scenario))
            // The objective is a max over EVERY candidate element (one per solve on the production path — MaxDamageSearch
            // enumerates them — but a multi-element scenario would read each candidate's mastery).
            scenario.candidateElements().forEach { (element, _) -> add(element.masteryCharacteristic) }
            addAll(MASTERY_RANDOM_BY_COUNT.map { it.first })
            addAll(params.targetStats.map { it.characteristic })
            addAll(conditionStats)
            addAll(subStats)
            // When the survivability soft-floor is active the objective ALSO depends on effective-HP — HP and
            // the four elemental resistances (plus their generic / random-element sources), via
            // [StatBuilder.effectiveHpVar]. Those are not damage stats, so without comparing them a
            // higher-damage / lower-EHP item would dominate and evict the item a floor-clearing build needs,
            // pruning the true (survivability-constrained) optimum. Add them so per-slot domination stays
            // optimum-preserving when the floor is on.
            if (scenario.survivabilityFloor && scenario.minEffectiveHp > 0) {
                add(Characteristic.HP)
                addAll(ELEMENTARY_RESISTANCES)
                add(Characteristic.RESISTANCE_ELEMENTARY)
                addAll(RANDOM_RESISTANCES)
            }
            // A required RESISTANCE target (specific element or the min-of-four aggregate) is enforced on
            // [StatBuilder.requiredActualStat], which folds specific + generic + random-element resistance lines
            // into the constrained "actual". The target chars themselves are already compared (above), but their
            // FEEDERS — generic `RESISTANCE_ELEMENTARY` and the random-element lines — are not. Without comparing
            // them, an item carrying its resistance via a generic/random line reads 0-vs-0 on every compared stat
            // and is dominated away by a higher-mastery item, even when it is the ONLY item that lets the build
            // meet the resistance target — pruning the true constrained optimum (a WRONG "proven optimal" badge,
            // or a false INFEASIBLE hard leg). Add the feeders so per-slot domination stays optimum-preserving.
            // Conditional on a resistance target so the default (no-resistance) request keeps its full domination
            // win. (HP targets need no analogue: HP is a single characteristic on items — no feeder folding — so
            // an HP-carrying item is already compared directly via its own HP stat.)
            val hasResistanceTarget =
                params.targetStats.any {
                    it.characteristic == Characteristic.RESISTANCE_ELEMENTARY ||
                        it.characteristic in ELEMENTARY_RESISTANCES ||
                        it.characteristic in RANDOM_RESISTANCES
                }
            if (hasResistanceTarget) {
                addAll(ELEMENTARY_RESISTANCES)
                add(Characteristic.RESISTANCE_ELEMENTARY)
                addAll(RANDOM_RESISTANCES)
            }
        }
    // Best-element concentration (Elemental Concentration) breaks item domination's monotonicity: more OFF-scenario
    // elemental mastery can COST the "+DI when your element is strongest" bonus. In a single-element solve those
    // masteries do nothing for the scored element, so a dominator having MORE of them is never beneficial — mark
    // them MINIMIZED (dominator must be ≤). Sound and cheap (3 extra compared stats). If one is also a beneficial
    // target the two directions can't be reconciled by a pin, so gate domination off for that rare request.
    val minimized = mutableSetOf<Characteristic>()
    if (ecModelled) {
        val offElements = ELEMENT_MASTERY_CHARACTERISTICS - scenario.element.masteryCharacteristic
        if (offElements.any { it in compared }) return null
        minimized += offElements
    }
    return DominationShape(
        pinned,
        compared + minimized,
        minimized,
        epicCarriers = epicCarriers,
        relicCarriers = relicCarriers,
        runes = runeDomination(params, pinned + minimized),
        outOfCombatExtras = outOfCombatExtras
    )
}

/**
 * The rune clause of the relation, or null when no rune can be modelled. The modelled rune types are over-estimated
 * like [createRuneModel] picks them ([relevantRuneStats] over every rune-able characteristic; a global forced rune —
 * resolved by name there — counts as any of them), so [RuneDomination.exact] is never missed.
 */
private fun runeDomination(
    params: WakfuBestBuildParams,
    dangerous: Set<Characteristic>,
): RuneDomination? {
    val autoFilled = if (params.useRunes) relevantRuneStats(params, RuneType.VALUED_CHARACTERISTICS) else emptySet()
    val forced = if (params.forcedRunes.isNotEmpty()) RuneType.VALUED_CHARACTERISTICS else emptySet()
    val runeStats = autoFilled + forced
    if (runeStats.isEmpty()) return null
    return RuneDomination(
        exact = runeStats.any { it in dangerous },
        oneTypePerItem = params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
    )
}

/**
 * Apply [dominatedWithin] per slot (each slot's items only ever replace each other). The item EQUIP conditions are
 * read over the WHOLE pool first, since a requirement crosses slots (a sword needs a ring): every item another pool item
 * requires is KEPT — whatever dominates it can't stand in for it in its requirer's build (the four nation rings are
 * stat-identical, zero-stat EPIC rings: they used to evict each other and fall to any 4-socket ring) — and
 * [EquipConstraints] carries each item's conflict partners (the symmetric closure of `not HasEquipmentId`) and the stat
 * gates that can fail to [dominates].
 *
 * The stat gates are read to a FIXPOINT (CERTIFIER_VERSION 58), so that only the gates of items that SURVIVE constrain the
 * others — at level 245 most gated items (levels 180–215) fall to stronger ungated ones, and a gate of an evicted item no
 * longer pins its stat in every other slot. One pass ([dominationPass]) on a pool P picks a set S of gated items, builds the
 * "beside" gate clauses of [dominates] from the gates of S only (every other clause — requirements, conflicts, exclusivity,
 * runes, an item's OWN gates — from all of P, unchanged), filters P, and accepts the result only when every gated item that
 * survived is in S; otherwise it adds the gated survivors to S and filters P again. S starts EMPTY and only grows, so the pass
 * ends on the least such S (a filter with more gate sources evicts less, so the gated survivors only grow with S); it
 * terminates since S ⊆ the gated items of P.
 *
 * Why the accepted result P' keeps the optimum of P. Take an optimal build β of P (every gate of every item it wears holds) and
 * replace each of its evicted items B by a KEPT dominator A (a ≻-maximal one — the per-slot argument of [dominatedWithin]; the
 * relation is transitive). The objective cannot fall (monotone), and every non-gate clause holds as before. A gate of an item
 * g of the new build β' — g is in P', so g ∈ S whenever its gate can fail — held in β: either g was already worn, or g
 * replaced some B whose gates ⊇ g's ("A's gates ⊆ B's") and held. Every swap moves each stat a gate of S bounds only in the
 * safe direction (`A ≤ B` under an upper gate, `A ≥ B` under a lower one: an S item worn beside the swapped slot, or A's own
 * gate), so g's gate still holds in β'. So β' is a valid build of P' at least as good as β: opt(P') = opt(P). The gates of an
 * item NOT in S never need protecting: no build of P' wears it. A gate that can never fail ([DominationShape.gateCanFail]) is
 * no gate at all.
 *
 * The pass is then repeated on its own output until it evicts nothing (each pass is optimum-preserving for ANY input pool, so
 * the composition is: opt(P_{k+1}) = opt(P_k) = … = opt(P)); the requirement and conflict clauses are re-read from the smaller
 * pool. Deterministic: [dominatedWithin] keeps the input order.
 */
internal fun filterDominatedPool(
    pool: Map<ItemType, List<Equipment>>,
    shape: DominationShape,
): Map<ItemType, List<Equipment>> {
    var current = pool
    while (true) {
        val next = dominationPass(current, shape)
        if (next.values.sumOf { it.size } == current.values.sumOf { it.size }) return next
        current = next
    }
}

/** One pass of [filterDominatedPool]: the least set S of gate sources whose filter keeps no gated item outside S. */
private fun dominationPass(
    pool: Map<ItemType, List<Equipment>>,
    shape: DominationShape,
): Map<ItemType, List<Equipment>> {
    var sources: Set<Int> = emptySet()
    while (true) {
        val constraints = EquipConstraints.of(pool, shape) { it.equipmentId in sources }
        val kept = pool.mapValues { (slot, items) -> dominatedWithin(items, slot, shape, constraints) }
        val keptGated =
            kept.values
                .flatten()
                .filter { constraints.gates(it).isNotEmpty() }
                .mapTo(HashSet()) { it.equipmentId }
        if (sources.containsAll(keptGated)) return kept
        sources = sources + keptGated
    }
}

/**
 * The pool-wide side of the item EQUIP conditions domination must respect: [requiredKeys] (ids some pool item requires
 * — never evicted), each item's [conflictPartners] (pool ids it can't be worn with, either way round) and the STAT GATES
 * ([gatedStats]): the stats some pool item's gate bounds from above (`range ≤ 3`) or from below (`lock ≥ 500`), with the
 * slots of the gated items. Only the gates that CAN fail count ([gates], [DominationShape.gateCanFail]): a gate every build
 * meets is no gate.
 */
internal class EquipConstraints private constructor(
    val requiredKeys: Set<Int>,
    private val partners: Map<Int, Set<Int>>,
    // Per item id, its stat gates that can fail (absent: none).
    private val failableGates: Map<Int, List<ItemStatGate>>,
    // Per slot, the stats an upper (lower) gate of an item that can be worn BESIDE an item of that slot bounds: an item of
    // another slot, or a second ring. Precomputed: [gatedStats] runs once per compared pair.
    private val upperBySlot: Map<ItemType, Set<Characteristic>>,
    private val lowerBySlot: Map<ItemType, Set<Characteristic>>,
) {
    fun conflictPartners(item: Equipment): Set<Int> = partners[item.equipmentId].orEmpty()

    /** [item]'s stat gates that can fail in the request ([DominationShape.gateCanFail]); [item] must be a pool item. */
    fun gates(item: Equipment): List<ItemStatGate> = failableGates[item.equipmentId].orEmpty()

    /**
     * The sheet stats on which a swap of B by A in [slot] must not move the build's out-of-combat total: UP for [upper] (an
     * upper gate — of an item another slot holds, a second ring, or A's own, which B carries too — could break), DOWN for the
     * lower ones. An item of the same single-occupancy slot is never worn beside B, so its gate does not count.
     */
    fun gatedStats(
        slot: ItemType,
        a: Equipment,
        upper: Boolean,
    ): Set<Characteristic> {
        val beside = (if (upper) upperBySlot else lowerBySlot)[slot].orEmpty()
        val gates = gates(a)
        if (gates.isEmpty()) return beside
        return beside + gates.filter { if (upper) it.comparison.boundsAbove() else it.comparison.boundsBelow() }.map { it.sheetCharacteristic }
    }

    companion object {
        val NONE = EquipConstraints(emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

        /**
         * The constraints of [pool]; the "beside" gate clauses read only the gates of the items [gateSource] accepts (every
         * pool item by default — the fixpoint of [filterDominatedPool] passes its set S), an item's own gates always count.
         */
        fun of(
            pool: Map<ItemType, List<Equipment>>,
            shape: DominationShape,
            gateSource: (Equipment) -> Boolean = { true },
        ): EquipConstraints {
            val items = pool.values.flatten()
            val keys = items.flatMapTo(HashSet()) { it.requiredItemIds }
            val partners = HashMap<Int, MutableSet<Int>>()
            for (item in items) {
                for (other in item.forbiddenItemIds) {
                    partners.getOrPut(item.equipmentId) { HashSet() } += other
                    partners.getOrPut(other) { HashSet() } += item.equipmentId
                }
            }
            val failableGates = HashMap<Int, List<ItemStatGate>>()
            for (item in items) {
                val gates = item.statGates.filter { shape.gateCanFail(it) }
                if (gates.isNotEmpty()) failableGates[item.equipmentId] = gates
            }
            val upper = HashMap<Characteristic, MutableSet<ItemType>>()
            val lower = HashMap<Characteristic, MutableSet<ItemType>>()
            for (item in items) {
                if (!gateSource(item)) continue
                for (gate in failableGates[item.equipmentId].orEmpty()) {
                    if (gate.comparison.boundsAbove()) upper.getOrPut(gate.sheetCharacteristic) { HashSet() } += item.itemType
                    if (gate.comparison.boundsBelow()) lower.getOrPut(gate.sheetCharacteristic) { HashSet() } += item.itemType
                }
            }
            if (keys.isEmpty() && partners.isEmpty() && failableGates.isEmpty()) return NONE

            fun besideBySlot(gatedSlots: Map<Characteristic, Set<ItemType>>): Map<ItemType, Set<Characteristic>> =
                pool.keys.associateWith { slot -> gatedSlots.filterValues { slots -> slots.any { it != slot || slot == ItemType.RING } }.keys }
            return EquipConstraints(keys, partners, failableGates, besideBySlot(upper), besideBySlot(lower))
        }
    }
}

/** Whether a gate with this operator can fail on a HIGHER value (`≤`, `<`, `=`, `≠`). */
private fun CriterionComparison.boundsAbove(): Boolean = this != CriterionComparison.GE && this != CriterionComparison.GT

/** Whether a gate with this operator can fail on a LOWER value (`≥`, `>`, `=`, `≠`). */
private fun CriterionComparison.boundsBelow(): Boolean = this != CriterionComparison.LE && this != CriterionComparison.LT

/**
 * Keep only the items of [slot] NOT dominated. `A ≻ B` (A strictly dominates B) iff `A ≽ B` ([dominates]) and, when
 * B ≽ A too (equivalent items), A has the lower id — a strict partial order, so exactly one of a set of identical
 * items is kept and every removed item has a KEPT dominator (take a ≻-maximal one: transitivity).
 *  - A one-item slot: B is removable iff ≥ 1 item dominates it.
 *  - RING (two are worn, never two of the same French name — the model's same-name rule): B is removable iff its
 *    dominators span ≥ 2 distinct NAMES. Then its KEPT dominators do too (a ≻-maximal dominator of another name than
 *    the kept ones would be kept itself), so whatever B's partner ring X, a kept dominator has a name ≠ X's (and
 *    differs from X) — and if X is removed as well, its own kept dominators offer another name. Counting dominators
 *    by ITEM (the old `≥ 2`) let two rarity variants of ONE ring (both named N) evict B although a build wearing
 *    a ring named N can only pair it with B.
 *  - An item another pool item REQUIRES ([EquipConstraints.requiredKeys]) is always kept.
 * The EQUIP-condition clauses of [dominates] (A's requirements ⊆ B's, A's conflict partners ⊆ B's) keep the ring
 * argument whole: B's partner X conflicts with neither B nor (hence) its dominator A.
 */
private fun dominatedWithin(
    items: List<Equipment>,
    slot: ItemType,
    shape: DominationShape,
    constraints: EquipConstraints,
): List<Equipment> =
    items.filter { b ->
        if (b.equipmentId in constraints.requiredKeys) return@filter true

        fun strictlyDominates(a: Equipment) = a !== b && a.dominates(b, shape, constraints) && (!b.dominates(a, shape, constraints) || a.equipmentId < b.equipmentId)
        if (slot == ItemType.RING) {
            var firstName: String? = null
            items.none { a ->
                if (!strictlyDominates(a)) return@none false
                val name = a.name.fr.lowercase()
                if (firstName == null) firstName = name
                name != firstName
            }
        } else {
            items.none { strictlyDominates(it) }
        }
    }

/**
 * `A ≽ B`: A can replace B in any build of a monotone mode with no loss, no extra scarce-rarity budget, no lost
 * sublimation carrier, no weaker rune, and no conditional-sublimation flip:
 *  - `A.maxShardSlots ≥ B` — ≥ rune capacity AND normal-sub carrier eligibility (a ≥3-socket item; sockets are a
 *    colour-agnostic count in this model);
 *  - **A in an exclusivity group ⇒ B in the same one** ([Equipment.exclusiveGroup]: the EPIC group — every EPIC item
 *    and two COMMON ones — or the RELIC group) — the swap never RAISES the build's ≤1-per-group count, so an item that
 *    takes the epic budget never dominates one that does not (keeping the free one may be what frees the epic budget
 *    for a stronger epic elsewhere — the one case a naive stats-only filter gets wrong);
 *  - **(B epic ⇒ A epic) when an epic sub is modelled, (B relic ⇒ A relic) when a relic sub is** — nor LOWERS the
 *    count of epic / relic carriers: B may be the build's only carrier of its epic / relic sub ([DominationShape.epicCarriers]).
 *    The carrier is the RARITY (an EPIC-group COMMON item hosts no epic sub);
 *  - the rune clause ([RuneDomination]) when runes can be modelled;
 *  - `A.characteristics ≥ B` on EVERY compared characteristic, AND **`A == B` on every [DominationShape.pinned]
 *    stat**, `≤` on every minimized one — so every monotone objective term / ≥-type condition is still ≥, and every
 *    pinned ≤/exact/parity condition keeps its exact truth value (its build sum is unchanged by the swap);
 *  - the item EQUIP conditions: **A's required items ⊆ B's** (the build already wears B's, so A's are worn too — a
 *    sword that needs its ring never evicts a free weapon) and **A's conflict partners ⊆ B's** (no item the build wears
 *    beside B refuses A). Class-only and never-equippable items are out of the pool before domination runs;
 *  - the item STAT GATES that can fail ([DominationShape.gateCanFail]; one every build meets is no gate) of the items still in
 *    the pool (read on the out-of-combat sheet, `StatBuilder.applyItemStatGates`): **A's gates ⊆ B's** (a gated item
 *    never evicts an ungated one: its gate could fail where B was free), **`A ≤ B` on every stat an upper gate bounds** and
 *    **`A ≥ B` on every stat a lower gate bounds** ([EquipConstraints.gatedStats]: the gates of items worn beside B, and A's own)
 *    — so the swap never moves a gated total the wrong way; with the `≥` on compared stats an upper-gated one is in effect
 *    pinned, and its runes too.
 */
private fun Equipment.dominates(
    other: Equipment,
    shape: DominationShape,
    constraints: EquipConstraints,
): Boolean {
    if (!other.requiredItemIds.containsAll(requiredItemIds)) return false
    if (!constraints.conflictPartners(other).containsAll(constraints.conflictPartners(this))) return false
    if (!constraints.gates(other).containsAll(constraints.gates(this))) return false
    val upperGated = constraints.gatedStats(itemType, this, upper = true)
    if (upperGated.any { valueFor(it) > other.valueFor(it) }) return false
    if (constraints.gatedStats(itemType, this, upper = false).any { valueFor(it) < other.valueFor(it) }) return false
    if (maxShardSlots < other.maxShardSlots) return false
    if (exclusiveGroup != ExclusiveGroup.NONE && other.exclusiveGroup != exclusiveGroup) return false
    if (shape.epicCarriers && other.rarity == Rarity.EPIC && rarity != Rarity.EPIC) return false
    if (shape.relicCarriers && other.rarity == Rarity.RELIC && rarity != Rarity.RELIC) return false
    // A rune of an upper-gated stat must be replicated EXACTLY too: a higher-level one on A could break the gate.
    shape.runes?.let { rule ->
        val exact = rule.exact || upperGated.any { it in RuneType.VALUED_CHARACTERISTICS }
        if (!carriesRunesOf(other, if (exact) rule.copy(exact = true) else rule)) return false
    }
    val chars = shape.compared ?: (characteristics.keys + other.characteristics.keys)
    return chars.all { c ->
        val mine = characteristics.getOrDefault(c, 0)
        val theirs = other.characteristics.getOrDefault(c, 0)
        when {
            c in shape.pinned -> mine == theirs
            c in shape.minimized -> mine <= theirs
            else -> mine >= theirs
        }
    }
}

/**
 * The rune clause of `A ≽ B` (this = A), sockets ≥ already checked. Only B's runes matter when B has sockets: A carries
 * the same types at its own rune-level cap, which must be ≥ B's (or EQUAL under [RuneDomination.exact]). Under the
 * max-damage one-type-per-item model the choice collapse picks each item's rune TYPE by value, and at rune level 1
 * the elemental and the secondary-mastery runes tie (1 vs 1) — the type can differ from level 2 on — so a level-1
 * carrier is only replaced by another level-1 carrier there.
 */
private fun Equipment.carriesRunesOf(
    other: Equipment,
    rule: RuneDomination,
): Boolean {
    if (rule.exact && rule.oneTypePerItem && maxShardSlots != other.maxShardSlots) return false
    if (other.maxShardSlots == 0) return true
    val mine = RuneType.maxLevelForItemLevel(level)
    val theirs = RuneType.maxLevelForItemLevel(other.level)
    return when {
        rule.exact -> mine == theirs
        rule.oneTypePerItem && theirs == 1 -> mine == 1
        else -> mine >= theirs
    }
}
