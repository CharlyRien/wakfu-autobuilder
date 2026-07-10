package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ELEMENTARY_RESISTANCES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.MASTERY_RANDOM_BY_COUNT
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.RANDOM_RESISTANCES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.scenarioGateMatches
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect

// Domination pre-filter (DominationFilter) extracted from the WakfuBuildSolver object (B1 of
// docs/code-review-followups.md): the pure monotone-objective item-domination relation + per-slot pool
// filter — no CP-SAT model or solver state. An item beaten on every relevant stat is never in an optimum,
// so it is dropped before the search.

/**
 * The characteristics to PIN to equality in the domination relation for these params — or `null` to not
 * apply the pre-filter at all. An empty set means full domination (no condition to respect).
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
 * conditional sub (unknown effect direction), or a condition that compares two build stats / is categorical
 * and can't be reduced to a stat pin.
 */
internal data class DominationShape(
    val pinned: Set<Characteristic>,
    val compared: Set<Characteristic>? = null,
    // Stats where LOWER is better for the swap proof, so a dominator must be `≤` (not `≥`). Used for the three
    // non-scenario elemental masteries when a best-element concentration sub (Elemental Concentration) is
    // choosable: in a single-element solve they do nothing for the scored element and only risk flipping which
    // element is "strongest", so more of them is never beneficial — see the swap proof in the sub's decode.
    val minimized: Set<Characteristic> = emptySet(),
)

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
            SublimationConditionType.SECONDARY_MASTERIES_AT_MOST -> {
                pinned += SECONDARY_MASTERY_CHARACTERISTICS
                conditionStats += SECONDARY_MASTERY_CHARACTERISTICS
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
    // The out-of-combat sheet caps (16 AP / 8 MP / 20 WP, [StatBuilder.applyOutOfCombatCaps]) are HARD
    // constraints in EVERY mode, so a dominator with strictly more AP/MP/WP could break the cap the evicted
    // item respected — pruning a cap-tight optimum. Pin them in all three modes (previously max-damage only:
    // most-masteries/precision compared them like any other stat, a latent unsoundness on cap-tight pools).
    pinned += Characteristic.ACTION_POINT
    pinned += Characteristic.MOVEMENT_POINT
    pinned += Characteristic.WAKFU_POINT

    if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
        return DominationShape(pinned)
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
            add(Characteristic.CRITICAL_HIT)
            add(Characteristic.DAMAGE_INFLICTED)
            add(Characteristic.MASTERY_CRITICAL)
            addAll(scenarioMasteryStats(scenario))
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
    val ecChoosable = sublimations.any { it.bestElementConcentration != null && it.solverChoosable && params.useSublimations }
    val minimized = mutableSetOf<Characteristic>()
    if (ecChoosable && scenario.candidateElements().size == 1) {
        val offElements = ELEMENT_MASTERY_CHARACTERISTICS - scenario.element.masteryCharacteristic
        if (offElements.any { it in compared }) return null
        minimized += offElements
    }
    return DominationShape(pinned, compared + minimized, minimized)
}

/** Apply [dominatedWithin] per slot — RING keeps 2 (two are co-equippable, distinct); every other slot 1. */
internal fun filterDominatedPool(
    pool: Map<ItemType, List<Equipment>>,
    pinned: Set<Characteristic>,
    compared: Set<Characteristic>? = null,
    minimized: Set<Characteristic> = emptySet(),
): Map<ItemType, List<Equipment>> = pool.mapValues { (slot, items) -> dominatedWithin(items, if (slot == ItemType.RING) 2 else 1, pinned, compared, minimized) }

/**
 * Keep only items NOT dominated by ≥[k] others in the same slot (k = slot capacity). B is removable iff
 * ≥k items A satisfy `A ≽ B`, with a deterministic tie-break (A strictly better, OR equal and lower id ⇒
 * exactly one of a set of identical items is kept). RING needs k=2 because one dominator may already be
 * worn in the other ring slot — see the proof in `docs/SOLVER_PERFORMANCE.md`.
 *
 * `A ≽ B` (A can replace B in any build of a monotone mode with no loss, no extra scarce-rarity budget,
 * and no conditional-sublimation flip):
 *  - `A.maxShardSlots ≥ B` — ≥ rune capacity AND sub-carrier eligibility (sockets are a colour-agnostic
 *    count in this model), so any rune/sub on B fits A;
 *  - **(A epic ⇒ B epic) ∧ (A relic ⇒ B relic)** — the swap never RAISES the build's ≤1-epic / ≤1-relic
 *    count, so an EPIC never dominates a non-epic (keeping the non-epic may be what frees the epic budget
 *    for a stronger epic elsewhere — the one case a naive stats-only filter gets wrong);
 *  - `A.characteristics ≥ B` on EVERY characteristic, AND **`A == B` on every [pinned] stat** — so every
 *    monotone objective term / ≥-type condition is still ≥, and every pinned ≤/exact/parity condition keeps
 *    its exact truth value (its build sum is unchanged by the swap).
 */
private fun dominatedWithin(
    items: List<Equipment>,
    k: Int,
    pinned: Set<Characteristic>,
    compared: Set<Characteristic>?,
    minimized: Set<Characteristic> = emptySet(),
): List<Equipment> =
    items.filter { b ->
        items.count { a ->
            a !== b &&
                a.dominates(b, pinned, compared, minimized) &&
                (!b.dominates(a, pinned, compared, minimized) || a.equipmentId < b.equipmentId)
        } < k
    }

private fun Equipment.dominates(
    other: Equipment,
    pinned: Set<Characteristic>,
    compared: Set<Characteristic>?,
    minimized: Set<Characteristic> = emptySet(),
): Boolean {
    if (maxShardSlots < other.maxShardSlots) return false
    if (rarity == Rarity.EPIC && other.rarity != Rarity.EPIC) return false
    if (rarity == Rarity.RELIC && other.rarity != Rarity.RELIC) return false
    val chars = compared ?: (characteristics.keys + other.characteristics.keys)
    return chars.all { c ->
        val mine = characteristics.getOrDefault(c, 0)
        val theirs = other.characteristics.getOrDefault(c, 0)
        when {
            c in pinned -> mine == theirs
            c in minimized -> mine <= theirs
            else -> mine >= theirs
        }
    }
}
