package me.chosante.autobuilder.domain

import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Resistances
import me.chosante.common.Spell
import me.chosante.common.SpellDamage
import me.chosante.common.SpellElement

/** A spell scored for a specific build/scenario: its AP cost and its expected damage per cast. */
data class ScoredSpell(
    val spell: Spell,
    val apCost: Int,
    val expectedDamagePerCast: Double,
)

/** A spell cast [count] times in the rotation, with its per-cast and total expected damage. */
data class SpellCast(
    val spell: Spell,
    val count: Int,
    val apCost: Int,
    val expectedDamagePerCast: Double,
) {
    val totalApCost: Int get() = count * apCost
    val totalExpectedDamage: Double get() = count * expectedDamagePerCast
}

/**
 * The best per-turn spell rotation found for an AP budget: which spells to cast (and how often) to
 * maximize expected damage, plus the AP it uses and the total expected damage.
 */
data class SpellRotation(
    val element: SpellElement?,
    val apBudget: Int,
    val apUsed: Int,
    val casts: List<SpellCast>,
    val totalExpectedDamage: Double,
    /**
     * Resistance-reduction debuffs cast **first** this turn (each lowers the target's resistance for the
     * damage [casts] that follow). Empty when no debuff is worth its AP. Their AP and own-damage are
     * already included in [apUsed] and [totalExpectedDamage].
     */
    val debuffCasts: List<SpellCast> = emptyList(),
    /** Target's effective resistance % the damage [casts] are dealt at (after [debuffCasts]); null = unknown. */
    val effectiveResistancePercent: Int? = null,
) {
    val isEmpty: Boolean get() = casts.isEmpty() && debuffCasts.isEmpty()
    val apLeftOver: Int get() = apBudget - apUsed
}

/**
 * Post-processing pass on a discovered max-damage build: given the build's real AP and stats, it picks
 * the **best spells to cast this turn** from the class's actual kit (`spells-v*` via [SpellCatalog]),
 * maximizing expected damage under the AP budget.
 *
 * **Architecture (per `docs/SPELLS_AND_COMBO_RESEARCH.md`): this is deliberately a post-processing
 * step, not fused into the OR-Tools item model** — fusing spell sequencing into the build search would
 * blow up the constraint model. It reuses the existing [BuildSpellDamage] / [SpellDamage] /
 * [SpellCatalog] helpers, so the maths stays in one place.
 *
 * **Model.** Per-spell AP cost + expected damage are known; **per-turn cast limits and cooldowns are
 * not in the dataset yet** (parsed as null). So the optimum is an **unbounded knapsack** over the AP
 * budget — each spell may repeat — solved exactly by DP. The result is the true optimum *under the
 * known constraints*; when a single spell dominates on damage-per-AP the rotation spams it, which is
 * the honest answer until cast-limit data exists (then pass [maxCastsPerSpell]).
 */
object SpellRotationOptimizer {
    /**
     * Exact best rotation for [apBudget] AP over [scored] (unbounded knapsack DP). [maxCastsPerSpell]
     * optionally caps how often any one spell is used (for when cast-limit data lands); null = no cap.
     */
    fun bestRotation(
        scored: List<ScoredSpell>,
        apBudget: Int,
        element: SpellElement? = null,
        maxCastsPerSpell: Int? = null,
    ): SpellRotation {
        val items = scored.filter { it.apCost in 1..apBudget && it.expectedDamagePerCast > 0.0 }
        if (apBudget <= 0 || items.isEmpty()) {
            return SpellRotation(element, apBudget.coerceAtLeast(0), 0, emptyList(), 0.0)
        }

        val counts =
            if (maxCastsPerSpell == null) {
                unboundedKnapsack(items, apBudget)
            } else {
                boundedKnapsack(items, apBudget, maxCastsPerSpell)
            }

        val casts =
            counts
                .map { (i, c) -> SpellCast(items[i].spell, c, items[i].apCost, items[i].expectedDamagePerCast) }
                .sortedByDescending { it.totalExpectedDamage }
        return SpellRotation(
            element = element,
            apBudget = apBudget,
            apUsed = casts.sumOf { it.totalApCost },
            casts = casts,
            totalExpectedDamage = casts.sumOf { it.totalExpectedDamage }
        )
    }

    /** Exact unbounded knapsack (each spell may repeat freely): `itemIndex -> cast count`. */
    private fun unboundedKnapsack(
        items: List<ScoredSpell>,
        apBudget: Int,
    ): Map<Int, Int> {
        // dp[a] = max expected damage achievable with at most `a` AP; pick[a] = spell used to reach it
        // (or -1 = carry the optimum from `a-1`, i.e. leave 1 AP idle).
        val dp = DoubleArray(apBudget + 1)
        val pick = IntArray(apBudget + 1) { -1 }
        for (a in 1..apBudget) {
            dp[a] = dp[a - 1]
            for ((i, item) in items.withIndex()) {
                if (item.apCost > a) continue
                val candidate = dp[a - item.apCost] + item.expectedDamagePerCast
                if (candidate > dp[a]) {
                    dp[a] = candidate
                    pick[a] = i
                }
            }
        }
        val counts = HashMap<Int, Int>()
        var a = apBudget
        while (a > 0) {
            val i = pick[a]
            if (i < 0) {
                a -= 1
            } else {
                counts[i] = (counts[i] ?: 0) + 1
                a -= items[i].apCost
            }
        }
        return counts
    }

    /**
     * Exact bounded knapsack: each spell may be cast at most [cap] times. Solved as a 0/1 knapsack over
     * [cap] identical copies per spell, so it yields the **true** capped optimum — not the greedy
     * single-path approximation the old code used. This is the path the cast-limit data (`maxCastsPerSpell`)
     * feeds, so it must be exact.
     */
    private fun boundedKnapsack(
        items: List<ScoredSpell>,
        apBudget: Int,
        cap: Int,
    ): Map<Int, Int> {
        if (cap <= 0) return emptyMap()
        // Expand to `cap` copies of each spell, keeping the original index so copies aggregate per spell.
        val idx = ArrayList<Int>()
        val ap = ArrayList<Int>()
        val dmg = ArrayList<Double>()
        items.forEachIndexed { i, item ->
            repeat(cap) {
                idx += i
                ap += item.apCost
                dmg += item.expectedDamagePerCast
            }
        }
        val n = idx.size
        // dp[k][a] = max damage using the first k copies within `a` AP (classic 0/1 knapsack).
        val dp = Array(n + 1) { DoubleArray(apBudget + 1) }
        for (k in 1..n) {
            for (a in 0..apBudget) {
                dp[k][a] = dp[k - 1][a]
                if (ap[k - 1] <= a) {
                    val candidate = dp[k - 1][a - ap[k - 1]] + dmg[k - 1]
                    if (candidate > dp[k][a]) dp[k][a] = candidate
                }
            }
        }
        val counts = HashMap<Int, Int>()
        var a = apBudget
        for (k in n downTo 1) {
            if (dp[k][a] != dp[k - 1][a]) { // copy k-1 was taken
                counts[idx[k - 1]] = (counts[idx[k - 1]] ?: 0) + 1
                a -= ap[k - 1]
            }
        }
        return counts
    }

    /**
     * Best rotation for [build] played by [character] of [clazz] against [scenario]. The candidate
     * spells are the class's real damage spells in the scenario's element (the element-gating that
     * stops a Cra from being told to play Water); the AP budget defaults to the build's actual AP.
     */
    fun forBuild(
        build: BuildCombination,
        character: Character,
        clazz: CharacterClass,
        scenario: DamageScenario,
        apBudget: Int? = null,
        maxCastsPerSpell: Int? = null,
    ): SpellRotation {
        val element = SpellElement.valueOf(scenario.element.name)
        val budget = apBudget ?: resolvedActionPoints(build, character, scenario)
        val damageSpells = SpellCatalog.damageSpells(clazz).filter { it.element == element }
        val scored = scoreSpells(damageSpells, build, character, scenario, scenario.targetResistancePercent)
        return bestRotation(scored, budget, element, maxCastsPerSpell)
    }

    /** A [Spell] scored at a specific [resistancePercent] (overriding the scenario's), or null if it deals no damage. */
    private fun scoreSpells(
        spells: List<Spell>,
        build: BuildCombination,
        character: Character,
        scenario: DamageScenario,
        resistancePercent: Int,
    ): List<ScoredSpell> =
        spells.mapNotNull { spell ->
            val ap = spell.apCost ?: return@mapNotNull null
            val damage =
                BuildSpellDamage
                    .expectedDamage(
                        spell = spell,
                        build = build,
                        character = character,
                        rangeBand = scenario.rangeBand.toSpellDamageRangeBand(),
                        rearMastery = scenario.orientation.grantsRearMastery,
                        berserkMastery = scenario.berserk,
                        targetResistancePercent = resistancePercent,
                        critCapPercent = scenario.critCapPercent
                    )?.expected ?: return@mapNotNull null
            ScoredSpell(spell, ap, damage)
        }

    /**
     * Best per-turn rotation **including resistance-reduction debuff sequencing**, over all candidate
     * elements ([DamageScenario.candidateElements]). For each element it considers casting the class's
     * resistance debuffs **first** (each lowers the target's flat resistance for everything that
     * follows), then filling the remaining AP with that element's damage rotation at the reduced
     * resistance — and keeps whichever debuff subset (possibly none) maximizes total damage. This is the
     * precise, sequencing-aware valuation the external loop ranks builds by; debuffs are modelled in
     * FLAT (the unit they're applied in) via [Resistances].
     */
    fun bestSequencedRotation(
        build: BuildCombination,
        character: Character,
        clazz: CharacterClass,
        scenario: DamageScenario,
        apBudget: Int? = null,
    ): SpellRotation {
        val budget = apBudget ?: resolvedActionPoints(build, character, scenario)
        val debuffSpells =
            SpellCatalog
                .forClass(clazz)
                // Only CONFIRMED enemy debuffs may lower the boss's resistance — an unconfirmed one might be
                // a self/ally buff, so using it would invent an enemy debuff (never invent).
                .filter { it.isConfirmedResistanceDebuff && (it.apCost ?: 0) in 1..budget }
                // Keep the few strongest (reduction per AP) to bound the subset enumeration.
                .sortedByDescending { (it.targetResistanceReductionFlat ?: 0).toDouble() / (it.apCost ?: 1) }
                .take(MAX_DEBUFFS_CONSIDERED)

        return scenario
            .candidateElements()
            .map { (element, resistance) ->
                bestSequencedForElement(build, character, clazz, scenario, element, resistance, budget, debuffSpells)
            }.maxByOrNull { it.totalExpectedDamage }
            ?: SpellRotation(null, budget, 0, emptyList(), 0.0)
    }

    private fun bestSequencedForElement(
        build: BuildCombination,
        character: Character,
        clazz: CharacterClass,
        scenario: DamageScenario,
        element: me.chosante.autobuilder.domain.SpellElement,
        resistancePercent: Int,
        budget: Int,
        debuffSpells: List<Spell>,
    ): SpellRotation {
        val commonElement = SpellElement.valueOf(element.name)
        val damageSpells = SpellCatalog.damageSpells(clazz).filter { it.element == commonElement }
        // An element the class has no damage spells in is unplayable; don't attribute any damage to it —
        // including a cross-element debuff's own hit, which would otherwise rank an unplayable element.
        if (damageSpells.isEmpty()) return SpellRotation(commonElement, budget, 0, emptyList(), 0.0)
        val baseFlat = Resistances.percentToFlat(resistancePercent)

        var best: SpellRotation? = null
        // Each debuff applies at most once (its state doesn't stack with itself); enumerate subsets.
        for (subset in subsetsOf(debuffSpells)) {
            val apDebuff = subset.sumOf { it.apCost ?: 0 }
            if (apDebuff > budget) continue
            val totalReduction = subset.sumOf { it.targetResistanceReductionFlat ?: 0 }
            val effectiveResistance = Resistances.flatToPercent(baseFlat - totalReduction)

            // Damage rotation in the remaining AP, at the post-debuff resistance. Exclude the forced debuff
            // spells themselves so a dual-role (debuff + same-element damage) spell isn't both cast as the
            // debuff and additionally spammed by the knapsack (which would double-list it and over-count AP).
            val damagePool = damageSpells.filterNot { it in subset }
            val scored = scoreSpells(damagePool, build, character, scenario, effectiveResistance)
            val rotation = bestRotation(scored, budget - apDebuff, commonElement)

            // Each debuff's OWN hit lands at the resistance reduced by the OTHER debuffs in the subset, not
            // by its own reduction (a spell doesn't lower the target before its own hit — avoids the prior
            // over-claim of scoring own-damage at the fully-reduced resistance).
            val debuffCasts =
                subset.map { debuff ->
                    val resBeforeOwn =
                        Resistances.flatToPercent(baseFlat - (totalReduction - (debuff.targetResistanceReductionFlat ?: 0)))
                    val own =
                        scoreSpells(listOf(debuff), build, character, scenario, resBeforeOwn).firstOrNull()?.expectedDamagePerCast ?: 0.0
                    SpellCast(debuff, count = 1, apCost = debuff.apCost ?: 0, expectedDamagePerCast = own)
                }

            val total = rotation.totalExpectedDamage + debuffCasts.sumOf { it.totalExpectedDamage }
            if (best == null || total > best.totalExpectedDamage) {
                best =
                    SpellRotation(
                        element = commonElement,
                        apBudget = budget,
                        apUsed = rotation.apUsed + apDebuff,
                        casts = rotation.casts,
                        totalExpectedDamage = total,
                        debuffCasts = debuffCasts,
                        effectiveResistancePercent = effectiveResistance
                    )
            }
        }
        return best ?: SpellRotation(commonElement, budget, 0, emptyList(), 0.0)
    }

    /** All subsets of [items] (each item at most once). [items] is kept tiny ([MAX_DEBUFFS_CONSIDERED]). */
    private fun <T> subsetsOf(items: List<T>): List<List<T>> =
        (0 until (1 shl items.size)).map { mask ->
            items.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }
        }

    private const val MAX_DEBUFFS_CONSIDERED = 3

    /**
     * Best rotation over **all candidate elements** of [scenario] (see [DamageScenario.candidateElements]):
     * runs [forBuild] per element and keeps the highest-damage one. This is the boss-aware element choice
     * — the winning rotation's [SpellRotation.element] is the best *playable* element given both the boss's
     * per-element resistance and the class's spell kit. Used by the max-damage scorer; mirrors the solver
     * objective's `max over elements`.
     */
    fun bestAcrossElements(
        build: BuildCombination,
        character: Character,
        clazz: CharacterClass,
        scenario: DamageScenario,
        apBudget: Int? = null,
    ): SpellRotation =
        scenario
            .candidateElements()
            .map { (element, resistance) ->
                forBuild(
                    build = build,
                    character = character,
                    clazz = clazz,
                    scenario = scenario.copy(element = element, targetResistancePercent = resistance),
                    apBudget = apBudget
                )
            }.maxByOrNull { it.totalExpectedDamage }
            ?: SpellRotation(null, apBudget ?: 0, 0, emptyList(), 0.0)

    /**
     * Build-independent per-AP throughput table for [spells]: `table[ap]` = the maximum total **base**
     * damage castable within `ap` AP (unbounded knapsack on each spell's base damage / AP cost). Index
     * `0..maxAp`. This is the spell-selection the CP-SAT objective looks up by the build's AP variable —
     * the per-build mastery multiplier is common to all same-element spells, so it factors out and the
     * knapsack stays build-independent. Spells without an AP cost or base damage are skipped.
     */
    fun baseThroughputTable(
        spells: List<Spell>,
        maxAp: Int,
    ): LongArray {
        val items = spells.mapNotNull { s -> s.apCost?.let { ap -> s.baseDamage?.let { base -> ap to base.toLong() } } }
        val table = LongArray(maxAp + 1)
        for (ap in 1..maxAp) {
            var best = table[ap - 1]
            for ((cost, base) in items) {
                if (cost in 1..ap) best = maxOf(best, table[ap - cost] + base)
            }
            table[ap] = best
        }
        return table
    }

    private fun resolvedActionPoints(
        build: BuildCombination,
        character: Character,
        scenario: DamageScenario,
    ): Int =
        computeCharacteristicsValues(
            buildCombination = build,
            characterBaseCharacteristics = character.baseCharacteristicValues,
            masteryElementsWanted = mapOf(scenario.element.masteryCharacteristic to 1),
            resistanceElementsWanted = emptyMap()
        )[Characteristic.ACTION_POINT] ?: 0

    private fun RangeBand.toSpellDamageRangeBand(): SpellDamage.RangeBand =
        when (this) {
            RangeBand.MELEE -> SpellDamage.RangeBand.MELEE
            RangeBand.DISTANCE -> SpellDamage.RangeBand.DISTANCE
        }
}
