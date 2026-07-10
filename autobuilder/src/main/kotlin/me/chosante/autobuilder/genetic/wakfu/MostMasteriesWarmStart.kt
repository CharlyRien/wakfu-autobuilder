package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ELEMENTARY_MASTERIES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ELEMENTARY_RESISTANCES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.MASTERY_RANDOM_BY_COUNT
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.RESISTANCE_RANDOM_BY_COUNT
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.valueFor
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.assignRandomPoints

/**
 * C8(3): a cheap greedy warm-start build for the **most-masteries** search.
 *
 * The lvl-245 production profile (SOLVER_PERFORMANCE §7) showed the model build is ~0.15 s but CP-SAT's
 * presolve + first feasible solution take ~26–32 s — the GUI shows NOTHING for that long, then climbs from
 * a near-zero incumbent. This greedy is used two ways by [WakfuBuildSolver.optimize]:
 *  1. **Instant emission** — scored with the production scorer and streamed as the first [SolverResult],
 *     so the user sees a decent build ~immediately instead of after the presolve silence;
 *  2. **CP-SAT solution hint** — `addHint` on the equipment layer (a PARTIAL hint: CP-SAT completes
 *     runes / sublimations / skills / random-element rolls itself), so the search starts from a good
 *     incumbent instead of climbing from scratch.
 *
 * Both uses are OPTIMALITY-NEUTRAL by construction: an emission is just an extra streamed result (every
 * consumer keeps improving over it), and a CP-SAT hint can never change the optimum — only where the
 * search starts. Quality does not need to be high for the UX win; the greedy ignores target caps and
 * conditional-sublimation interplay on purpose (the solver refines all of that).
 *
 * Returns null when not applicable — forced items (the greedy can't honor slot pins; rare path), or an
 * empty request. A null simply skips the warm start; the search behaves exactly as before.
 */
internal object MostMasteriesWarmStart {
    /**
     * Per-characteristic linear weights derived from the request: each target credits its own stat plus
     * the FEEDERS the engine folds into it (generic elemental mastery/resistance + random-element lines),
     * and DAMAGE_INFLICTED is credited because the objective maximizes `mastery × (1 + DI/100)` — one DI%
     * is worth roughly 1% of the achievable requested mastery, approximated by a flat mastery-scale credit.
     */
    private fun weights(targetStats: TargetStats): Map<Characteristic, Double> {
        val w = mutableMapOf<Characteristic, Double>()

        fun add(
            c: Characteristic,
            weight: Double,
        ) {
            w[c] = (w[c] ?: 0.0) + weight
        }
        var maxMasteryWeight = 0.0
        for (t in targetStats) {
            val weight = targetStats.weight(t)
            when (val c = t.characteristic) {
                in ELEMENTARY_MASTERIES, Characteristic.MASTERY_ELEMENTARY -> {
                    add(c, weight)
                    add(Characteristic.MASTERY_ELEMENTARY, weight)
                    MASTERY_RANDOM_BY_COUNT.forEach { (rc, _) -> add(rc, weight) }
                    maxMasteryWeight = maxOf(maxMasteryWeight, weight)
                }

                in ELEMENTARY_RESISTANCES, Characteristic.RESISTANCE_ELEMENTARY -> {
                    add(c, weight)
                    add(Characteristic.RESISTANCE_ELEMENTARY, weight)
                    RESISTANCE_RANDOM_BY_COUNT.forEach { (rc, _) -> add(rc, weight) }
                }

                else -> {
                    add(c, weight)
                    if (c.isMaximizableMastery()) maxMasteryWeight = maxOf(maxMasteryWeight, weight)
                }
            }
        }
        // DI multiplies the whole requested-mastery score; ~30 mastery points per DI% is a fair flat
        // credit at the levels where this matters (the solver optimizes the real product anyway).
        if (maxMasteryWeight > 0.0) add(Characteristic.DAMAGE_INFLICTED, 30.0 * maxMasteryWeight)
        return w
    }

    // The scorer's required-target shortfall penalty is power-6 — missing a target multiplies the WHOLE
    // score down, so one point toward a small-valued target (1 AP of 12) is worth a large slice of the
    // total. The per-unit credit is therefore normalized by the target value onto the mastery scale
    // (covering a target fully ≈ worth a full mastery loadout), then boosted while unmet; overshoot
    // beyond the shortfall is score-neutral and gets no credit.
    private const val REQUIRED_SHORTFALL_BOOST = 4.0
    private const val MASTERY_SCALE = 3000.0

    private class Shortfall(
        var remaining: Int,
        val target: Int,
    )

    private fun itemValue(
        equip: Equipment,
        weights: Map<Characteristic, Double>,
        remainingShortfall: Map<Characteristic, Shortfall>,
    ): Double {
        var v = 0.0
        for ((char, weight) in weights) {
            val value = equip.valueFor(char)
            if (value == 0) continue
            val shortfall = remainingShortfall[char]
            v +=
                if (shortfall != null) {
                    val credited = minOf(value, shortfall.remaining).coerceAtLeast(0)
                    weight * credited * REQUIRED_SHORTFALL_BOOST * MASTERY_SCALE / shortfall.target.coerceAtLeast(1)
                } else {
                    weight * value
                }
        }
        return v
    }

    /** Greedy build: best-valued item per slot (top-2 distinct-name rings, best weapon combo), then a ≤1-epic / ≤1-relic repair. */
    fun greedyBuild(
        params: WakfuBestBuildParams,
        pool: List<Equipment>,
    ): BuildCombination? {
        if (params.forcedItems.isNotEmpty()) return null
        val weights = weights(params.targetStats)
        if (weights.isEmpty()) return null
        // Remaining shortfall per REQUIRED (non-maximizable) target after the character's base stats —
        // consumed slot by slot as picks land, so later slots stop over-paying for an already-met target.
        val base = params.character.baseCharacteristicValues
        val remaining = mutableMapOf<Characteristic, Shortfall>()
        for (t in params.targetStats) {
            val c = t.characteristic
            if (c.isMaximizableMastery()) continue
            val shortfall = t.target - (base[c] ?: 0)
            if (shortfall > 0) remaining[c] = Shortfall(shortfall, t.target)
        }

        fun consume(equip: Equipment) {
            for (c in remaining.keys.toList()) {
                val v = equip.valueFor(c)
                if (v != 0) {
                    val left = remaining.getValue(c).remaining - v
                    if (left > 0) remaining.getValue(c).remaining = left else remaining.remove(c)
                }
            }
        }

        fun itemValue(equip: Equipment) = itemValue(equip, weights, remaining)
        val byType: Map<ItemType, List<Equipment>> = pool.groupBy { it.itemType }

        // Sequential slot fill, re-ranking against the LIVE shortfalls: once a required target (AP, MP…)
        // is covered, later slots stop over-paying for it and go back to mastery. Weapons resolve as a
        // combo (2H vs 1H + off-hand); rings take the top two distinct-name picks.
        val weaponTypes = setOf(ItemType.ONE_HANDED_WEAPONS, ItemType.TWO_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS)
        val picks = mutableListOf<Equipment>()
        for ((type, items) in byType) {
            when {
                type == ItemType.RING -> {
                    val first = items.maxByOrNull { itemValue(it) } ?: continue
                    picks += first
                    consume(first)
                    items.filter { it.name != first.name }.maxByOrNull { itemValue(it) }?.let {
                        picks += it
                        consume(it)
                    }
                }

                type in weaponTypes -> Unit // resolved as a combo below

                else ->
                    items.maxByOrNull { itemValue(it) }?.let {
                        picks += it
                        consume(it)
                    }
            }
        }
        // Weapon combo: a two-handed weapon vs one-handed + off-hand, by total greedy value.
        val best2h = byType[ItemType.TWO_HANDED_WEAPONS]?.maxByOrNull { itemValue(it) }
        val best1h = byType[ItemType.ONE_HANDED_WEAPONS]?.maxByOrNull { itemValue(it) }
        val bestOff = byType[ItemType.OFF_HAND_WEAPONS]?.maxByOrNull { itemValue(it) }
        val v2h = best2h?.let { itemValue(it) } ?: Double.NEGATIVE_INFINITY
        val v1hOff = (best1h?.let { itemValue(it) } ?: 0.0) + (bestOff?.let { itemValue(it) } ?: 0.0)
        if (best2h != null && v2h >= v1hOff) {
            picks += best2h
            consume(best2h)
        } else {
            best1h?.let {
                picks += it
                consume(it)
            }
            bestOff?.let {
                picks += it
                consume(it)
            }
        }

        // Rarity-budget repair: while a scarce rarity is over budget, downgrade the pick whose best
        // same-slot non-scarce alternative loses the least greedy value (dropping the slot if none).
        fun repair(rarity: Rarity) {
            while (picks.count { it.rarity == rarity } > 1) {
                val candidates =
                    picks.filter { it.rarity == rarity }.map { pick ->
                        val alternative =
                            byType
                                .getValue(pick.itemType)
                                .filter { alt ->
                                    alt.rarity != rarity &&
                                        alt !in picks &&
                                        (pick.itemType != ItemType.RING || picks.none { it.itemType == ItemType.RING && it !== pick && it.name == alt.name })
                                }.maxByOrNull { itemValue(it) }
                        val loss = itemValue(pick) - (alternative?.let { itemValue(it) } ?: 0.0)
                        Triple(pick, alternative, loss)
                    }
                val cheapest = candidates.minByOrNull { it.third } ?: return
                picks.remove(cheapest.first)
                cheapest.second?.let { picks.add(it) }
            }
        }
        repair(Rarity.EPIC)
        repair(Rarity.RELIC)

        // Target-aware skill fill (deterministic seed): assign each branch's points among the skills
        // matching the requested characteristics — the AP/MP majors and %HP lines carry required targets.
        val skills = CharacterSkills(params.character.level)
        val targetCharacteristics = params.targetStats.map { it.characteristic }
        val random = kotlin.random.Random(1)
        skills.intelligence.assignRandomPoints(skills.intelligence.maxPointsToAssign, targetCharacteristics, random)
        skills.strength.assignRandomPoints(skills.strength.maxPointsToAssign, targetCharacteristics, random)
        skills.agility.assignRandomPoints(skills.agility.maxPointsToAssign, targetCharacteristics, random)
        skills.luck.assignRandomPoints(skills.luck.maxPointsToAssign, targetCharacteristics, random)
        skills.major.assignRandomPoints(skills.major.maxPointsToAssign, targetCharacteristics, random)

        val combination =
            BuildCombination(
                equipments = picks.toList(),
                characterSkills = skills,
                passives = WakfuBuildSolver.resolvedPassives(params)
            )
        // The greedy is best-effort: any rule it got wrong just cancels the warm start (never a wrong result).
        return combination.takeIf { it.isValid() }
    }
}
