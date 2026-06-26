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

/**
 * Per-turn expected damage of the discovered build under one attack scenario variant ([orientation] + the
 * optional [berserk] low-HP bonus). The max-damage result lists these so the player can read off how much
 * positioning / berserk play is worth — exactly the levers the mode optimizes — instead of a single number.
 */
data class ScenarioDamage(
    val orientation: Orientation,
    val berserk: Boolean,
    val totalExpectedDamage: Double,
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
 * **Model.** Per-spell AP cost + expected damage are known, and each spell's **per-turn cast limit**
 * ([Spell.maxCastsThisTurn], from the baked cast-limit data joined in [SpellCatalog]) bounds how often
 * it may repeat. So the optimum is a **bounded knapsack** over the AP budget — each spell repeats up to
 * its limit (a spell with no limit repeats freely, collapsing to the unbounded knapsack) — solved
 * exactly by DP. The result is the true optimum under those caps. WP cost is carried ([Spell.wpCost])
 * but not yet folded in — WP is a per-fight pool, not a per-turn cap; see `docs/FULL_DAMAGE_MODE_STATUS.md` "Lot 1".
 */
object SpellRotationOptimizer {
    /**
     * Exact best rotation for [apBudget] AP over [scored]. Each spell is capped at its real per-turn
     * cast limit ([Spell.maxCastsThisTurn], from the baked cast-limit data); [maxCastsPerSpell]
     * optionally tightens *every* spell further with a uniform cap. When nothing is capped below what
     * the AP budget would fit, this is the plain unbounded knapsack; otherwise a bounded one. The
     * result is the true optimum under those caps.
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

        // The most copies of each spell that fit in the AP budget — the single source of truth for both
        // the cap clamp and the "is anything actually capped?" test below.
        val maxFits = items.map { apBudget / it.apCost }
        // Per-spell cap = the spell's own per-turn limit, tightened by the optional uniform override,
        // and never more copies than fit. A null component (no data cap, no override) means "unbounded".
        val caps =
            items.mapIndexed { i, item ->
                val limit = listOfNotNull(item.spell.maxCastsThisTurn, maxCastsPerSpell).minOrNull()
                (limit ?: maxFits[i]).coerceIn(0, maxFits[i])
            }

        val counts =
            if (caps.indices.none { caps[it] < maxFits[it] }) {
                // Nothing is capped below what would fit, so the unbounded knapsack is already exact.
                unboundedKnapsack(items, apBudget)
            } else {
                boundedKnapsack(items, apBudget, caps)
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
     * Exact bounded knapsack: spell `i` may be cast at most `caps[i]` times (its real per-turn limit).
     * Uses the **same item-layered DP as [baseThroughputTable]** — keep the two in lockstep, since they
     * bound the rotation identically (that one builds the build-independent base-damage table the CP-SAT
     * objective reads; this one the per-build expected-damage rotation for display/scoring). Each `next`
     * layer reads the previous `dp`, so a spell is never used past its cap and an uncapped spell
     * (`caps[i] = budget/cost`) costs nothing extra — no copy expansion. `choice[i][a]` records how many
     * times spell `i` is cast in the optimum at `a` AP, used to reconstruct the per-spell counts.
     */
    private fun boundedKnapsack(
        items: List<ScoredSpell>,
        apBudget: Int,
        caps: List<Int>,
    ): Map<Int, Int> {
        val n = items.size
        var dp = DoubleArray(apBudget + 1)
        val choice = Array(n) { IntArray(apBudget + 1) }
        for (i in 0 until n) {
            val cost = items[i].apCost
            val value = items[i].expectedDamagePerCast
            val cap = caps[i].coerceAtLeast(0)
            val next = dp.copyOf()
            for (a in cost..apBudget) {
                var k = 1
                while (k <= cap && k * cost <= a) {
                    val candidate = dp[a - k * cost] + k * value
                    if (candidate > next[a]) {
                        next[a] = candidate
                        choice[i][a] = k
                    }
                    k++
                }
            }
            dp = next
        }
        // Reconstruct counts by walking the layers back, spending the AP each spell's chosen casts used.
        val counts = HashMap<Int, Int>()
        var a = apBudget
        for (i in n - 1 downTo 0) {
            val k = choice[i][a]
            if (k > 0) {
                counts[i] = k
                a -= k * items[i].apCost
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
                        // The positional multiplier (face 1.0 / side 1.10 / back 1.25) the displayed rotation
                        // was missing: only rear MASTERY was applied, so a back-hit scenario under-showed by
                        // ×1.25. A uniform constant per search, so it scales every build/element equally — it
                        // never changes which build the engine picks, only the (now precise) shown number.
                        orientationMultiplierPercent = scenario.orientation.multiplierPercent,
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
     * Per-turn expected damage of [build] under each attack-position variant, for the result breakdown:
     * **face**, **side** and **back** (each at no berserk), plus a **back + berserk** entry when [includeBerserk]
     * (the build actually carries berserk mastery, else it'd duplicate the back number), and ALWAYS the
     * configured `(scenario.orientation, scenario.berserk)` combo so the row matching the headline rotation is
     * present and can be highlighted — even for off-grid combos like side+berserk. Each entry re-runs the full
     * debuff-aware rotation under that orientation, so the entry matching the configured scenario equals the
     * headline rotation total. Orientation is the one lever the per-turn objective drops (a uniform factor), so
     * this is the cheapest honest way to surface "what the other positions are worth".
     */
    fun scenarioBreakdown(
        build: BuildCombination,
        character: Character,
        clazz: CharacterClass,
        scenario: DamageScenario,
        includeBerserk: Boolean,
        // The already-computed headline rotation total for the CONFIGURED (scenario.orientation, scenario.berserk)
        // combo, if the caller has it — reused instead of re-running that one rotation (the rest still compute).
        configuredRotationTotal: Double? = null,
    ): List<ScenarioDamage> {
        // Dedup so the configured combo (added last) doesn't repeat a base row it already equals.
        val combos = LinkedHashSet<Pair<Orientation, Boolean>>()
        combos.add(Orientation.FACE to false)
        combos.add(Orientation.SIDE to false)
        combos.add(Orientation.BACK to false)
        if (includeBerserk) combos.add(Orientation.BACK to true)
        combos.add(scenario.orientation to scenario.berserk)
        return combos.map { (orientation, berserk) ->
            val isConfigured = orientation == scenario.orientation && berserk == scenario.berserk
            ScenarioDamage(
                orientation = orientation,
                berserk = berserk,
                totalExpectedDamage =
                    if (isConfigured && configuredRotationTotal != null) {
                        configuredRotationTotal
                    } else {
                        bestSequencedRotation(
                            build,
                            character,
                            clazz,
                            scenario.copy(orientation = orientation, berserk = berserk)
                        ).totalExpectedDamage
                    }
            )
        }
    }

    /**
     * Build-independent per-AP throughput table for [spells]: `table[ap]` = the maximum total **base**
     * damage castable within `ap` AP, **bounded by each spell's real per-turn cast limit**
     * ([Spell.maxCastsThisTurn]). Index `0..maxAp`. This is the spell-selection the CP-SAT objective
     * looks up by the build's AP variable — the per-build mastery multiplier is common to all
     * same-element spells, so it factors out and the knapsack stays build-independent. Spells without an
     * AP cost or base damage are skipped.
     *
     * **Why bounded.** Without the cap a single high-throughput spell is spammed to fill the AP budget,
     * which the game disallows; the cap makes the modelled rotation realistic. The DP is the
     * item-layered bounded knapsack (each spell `s` used at most `c_s` times); a spell with no per-turn
     * limit gets `c_s = maxAp`, which is `>= maxAp/cost`, so it behaves exactly like the previous
     * unbounded table. The CP-SAT objective reads this table through the **unchanged**
     * `addElement(apVar, table, throughput)` call — only the table's *contents* change, so the
     * optimality argument is intact (see `docs/FULL_DAMAGE_MODE_STATUS.md` "Lot 1").
     */
    fun baseThroughputTable(
        spells: List<Spell>,
        maxAp: Int,
        casterLevel: Int,
    ): LongArray {
        // (apCost, baseDamage, perTurnCap). perTurnCap = the spell's per-turn cast limit, or maxAp when
        // unbounded (no more than maxAp/cost copies fit in maxAp AP anyway). The base hit is scaled to the
        // CASTER'S LEVEL ([Spell.baseDamageAt]) — exactly as the displayed rotation scores it — so the
        // objective's spell selection matches the rotation's at non-max levels (spells scale with different
        // slopes, so a max-level base would pick a different spell mix and diverge from the shown damage).
        val items =
            spells.mapNotNull { s ->
                val cost = s.apCost ?: return@mapNotNull null
                if (cost < 1) return@mapNotNull null
                val base = s.baseDamageAt(casterLevel)?.toLong() ?: return@mapNotNull null
                Triple(cost, base, s.maxCastsThisTurn ?: maxAp)
            }
        // Item-layered bounded knapsack (mirrored by [boundedKnapsack], which reconstructs per-spell counts
        // from the same recurrence — keep the two in lockstep): each `next` layer reads the *previous* `dp`,
        // so spell `s` is counted at most `cap` times across the whole table — never reused beyond its limit.
        var dp = LongArray(maxAp + 1)
        for ((cost, base, cap) in items) {
            val next = dp.copyOf()
            for (ap in cost..maxAp) {
                var k = 1
                while (k <= cap && k * cost <= ap) {
                    val candidate = dp[ap - k * cost] + k * base
                    if (candidate > next[ap]) next[ap] = candidate
                    k++
                }
            }
            dp = next
        }
        // Enforce the "≤ ap" contract (table[ap] = best castable within *at most* ap AP). The layered DP
        // above is already monotone non-decreasing, so this pass is normally a no-op; it is kept as a cheap,
        // explicit guarantee — the CP-SAT objective indexes the table by the AP variable and relies on more
        // AP never lowering throughput — that stays correct even if the DP recurrence is later changed.
        for (ap in 1..maxAp) dp[ap] = maxOf(dp[ap], dp[ap - 1])
        return dp
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
}
