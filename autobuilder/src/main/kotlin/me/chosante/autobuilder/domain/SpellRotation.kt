package me.chosante.autobuilder.domain

import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
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
) {
    val isEmpty: Boolean get() = casts.isEmpty()
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

        // dp[a] = max expected damage achievable with at most `a` AP; pick[a] = spell used to reach it
        // (or -1 = carry the optimum from `a-1`, i.e. leave 1 AP idle). With a per-spell cap we also
        // track how many times each spell was used along the best path to forbid exceeding it.
        val dp = DoubleArray(apBudget + 1)
        val pick = IntArray(apBudget + 1) { -1 }
        for (a in 1..apBudget) {
            dp[a] = dp[a - 1]
            pick[a] = -1
            for ((i, item) in items.withIndex()) {
                if (item.apCost > a) continue
                if (maxCastsPerSpell != null && countOnPath(pick, items, a - item.apCost, i) >= maxCastsPerSpell) continue
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

    /** Times spell [i] is used along the best path that reaches budget [a] (only used when capping). */
    private fun countOnPath(
        pick: IntArray,
        items: List<ScoredSpell>,
        a: Int,
        i: Int,
    ): Int {
        var used = 0
        var cur = a
        while (cur > 0) {
            val p = pick[cur]
            if (p < 0) {
                cur -= 1
            } else {
                if (p == i) used++
                cur -= items[p].apCost
            }
        }
        return used
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

        val scored =
            SpellCatalog
                .damageSpells(clazz)
                .filter { it.element == element }
                .mapNotNull { spell ->
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
                                targetResistancePercent = scenario.targetResistancePercent,
                                critCapPercent = scenario.critCapPercent
                            )?.expected ?: return@mapNotNull null
                    ScoredSpell(spell, ap, damage)
                }
        return bestRotation(scored, budget, element, maxCastsPerSpell)
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
