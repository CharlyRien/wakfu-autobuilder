package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Regression locks for the FLAT soft objective (1.11 follow-up, 2026-10-02): when every build's
 * weighted achieved/requested ratio of the required targets sits below ~10%, the integer power-6 table
 * (`i⁶ / (maxIndex⁶ / MAX_PENALTY_MULTIPLIER)`) floored every reachable bucket to 0, so the soft objective
 * `core × multiplier` was 0 for EVERY build — the empty build tied the optimum and multi-worker solves
 * returned it (measured on this pool + AP 99: 20/30 runs at 2 workers, 18/30 at 3).
 *
 * The pool carries no AP, so a build tops out at base 6 + the Major point: AP 99 is ~14× out of reach and
 * every build lands in the floored region — where the soft optimum must now rank by the core alone, i.e.
 * coincide with the unconstrained optimum.
 */
class SoftPenaltyFloorTest {
    private fun item(
        id: Int,
        type: ItemType,
        rarity: Rarity = Rarity.LEGENDARY,
        stats: Map<Characteristic, Int>,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 200,
        name = I18nText("floor$id", "floor$id", "", ""),
        rarity = rarity,
        itemType = type,
        characteristics = stats,
        maxShardSlots = 4
    )

    // MostMasteriesBoundPrototypeTest's small pool (the CI flake's): distance-mastery gear, no AP anywhere.
    private val pool: Map<ItemType, List<Equipment>> =
        listOf(
            item(1, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 120)),
            item(2, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 80, Characteristic.HP to 200)),
            item(3, ItemType.CAPE, stats = mapOf(Characteristic.MASTERY_DISTANCE to 90)),
            item(4, ItemType.CAPE, rarity = Rarity.EPIC, stats = mapOf(Characteristic.MASTERY_DISTANCE to 150)),
            item(5, ItemType.BELT, rarity = Rarity.EPIC, stats = mapOf(Characteristic.MASTERY_DISTANCE to 140)),
            item(6, ItemType.BELT, stats = mapOf(Characteristic.MASTERY_DISTANCE to 70)),
            item(7, ItemType.RING, stats = mapOf(Characteristic.MASTERY_DISTANCE to 60)),
            item(8, ItemType.RING, stats = mapOf(Characteristic.MASTERY_DISTANCE to 55)),
            item(9, ItemType.ONE_HANDED_WEAPONS, stats = mapOf(Characteristic.MASTERY_DISTANCE to 110)),
            item(10, ItemType.OFF_HAND_WEAPONS, stats = mapOf(Characteristic.MASTERY_DISTANCE to 50)),
            item(11, ItemType.TWO_HANDED_WEAPONS, stats = mapOf(Characteristic.MASTERY_DISTANCE to 175)),
            item(12, ItemType.AMULET, stats = mapOf(Characteristic.HP to 100)),
            item(13, ItemType.BOOTS, stats = mapOf(Characteristic.HP to 100))
        ).groupBy { it.itemType }

    private val farTarget = TargetStat(Characteristic.ACTION_POINT, 99)

    private fun mmParams(targets: List<TargetStat>) =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, 200, 0, CharacterSkills(200)),
            targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)) + targets),
            searchDuration = 10.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
            useRunes = false,
            useSublimations = false
        )

    private fun mdParams(targets: List<TargetStat>) =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, 200, 0, CharacterSkills(200)),
            targetStats = TargetStats(targets),
            searchDuration = 10.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            useRunes = false,
            useSublimations = false,
            damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
        )

    // The deterministic protocol (engine-test determinism): 1 worker + interleave + fixed seed.
    private val tuning =
        WakfuBuildSolver.SolverTuning(
            numSearchWorkers = 1,
            randomSeed = 1,
            interleaveSearch = true,
            maxDeterministicTime = 30.0
        )

    private fun solveSoft(params: WakfuBestBuildParams): SolverResult<BuildCombination> =
        runBlocking {
            WakfuBuildSolver
                .optimize(params, pool, emptyList(), emptyList(), tuning, hardConstraints = false)
                .toList()
                .last()
        }

    /**
     * The shared multiplier ([penaltyMultiplier]) every mirror prices — solver table, both certificates:
     * never 0, monotone (the certificates' soundness argument), and identical to the legacy `index⁶ /
     * powScale` wherever that was already ≥ 1 — so only the flat region moves and every banked bound
     * outside it is bit-identical.
     */
    @Test
    fun `penalty multiplier is floored at 1 and unchanged wherever it was already positive`() {
        for (maxIndex in listOf(1L, 6L, 10L, 11L, 57L, 200L, 1999L, MAX_POWER_TABLE_INDEX.toLong())) {
            val powScale = penaltyPowScale(maxIndex)
            var previous = 0L
            for (index in 0L..maxIndex) {
                val multiplier = penaltyMultiplier(index, powScale)
                val legacy =
                    java.math.BigInteger
                        .valueOf(index)
                        .pow(6)
                        .divide(powScale)
                        .toLong()
                assertThat(multiplier).describedAs("maxIndex=$maxIndex index=$index: never 0").isGreaterThanOrEqualTo(MIN_PENALTY_MULTIPLIER)
                assertThat(multiplier).describedAs("maxIndex=$maxIndex index=$index: legacy entry kept").isEqualTo(maxOf(legacy, MIN_PENALTY_MULTIPLIER))
                assertThat(multiplier).describedAs("maxIndex=$maxIndex index=$index: monotone").isGreaterThanOrEqualTo(previous)
                previous = multiplier
            }
            if (maxIndex > 10L) {
                assertThat(previous).describedAs("maxIndex=$maxIndex: top bucket ≈ MAX_PENALTY_MULTIPLIER").isGreaterThanOrEqualTo(MAX_PENALTY_MULTIPLIER)
            }
        }
    }

    /**
     * The re-scorers' continuous factor gets the same floor relative to a target-meeting build: the
     * max-damage external loop RANKS probe results by this score, and an uncapped `(100/6)⁶ ≈ 2e7`
     * divisor read every far-out-of-reach build as 0.0000 — the same flat tie, one layer up.
     */
    @Test
    fun `re-scorers cap the penalty factor where the solver floors its multiplier`() {
        val ap = Characteristic.ACTION_POINT
        assertThat(FindMaxDamageScoring.requiredConstraintPenaltyFactor(TargetStats(listOf(farTarget)), mapOf(ap to 6)))
            .describedAs("6 AP of 99 (~6%): capped at the solver's floor ratio")
            .isEqualByComparingTo(MAX_PENALTY_MULTIPLIER.toBigDecimal())
        assertThat(FindMaxDamageScoring.requiredConstraintPenaltyFactor(TargetStats(listOf(TargetStat(ap, 12))), mapOf(ap to 6)))
            .describedAs("6 AP of 12 (50%): outside the floored region the factor is untouched")
            .isEqualByComparingTo("64")
    }

    /**
     * Deterministic core of the bug: the soft most-masteries objective must keep the core's gradient. Every
     * build is far out of reach (multiplier floored), so the soft optimum is the unconstrained max-mastery
     * build: `core × 1 × SCALE + 0 overshoot` — before the fix it was 0 for every build (empty included).
     */
    @Test
    fun `soft most-masteries objective keeps the core gradient when targets are far out of reach`() {
        val unconstrained = solveSoft(mmParams(emptyList()))
        val soft = solveSoft(mmParams(listOf(farTarget)))
        assertThat(unconstrained.isOptimal).isTrue()
        assertThat(soft.isOptimal).describedAs("the tiny pool must be PROVEN, so the lock compares optima").isTrue()
        val unconstrainedCore = requireNotNull(unconstrained.mostMasteriesObjective)
        assertThat(unconstrainedCore).describedAs("the reference build must carry mastery").isPositive()
        assertThat(soft.mostMasteriesObjective)
            .describedAs("floored region: the soft optimum ranks by the core alone (multiplier 1, no overshoot)")
            .isEqualTo(unconstrainedCore * WakfuBuildSolver.OVERSHOOT_SCALE)
        assertThat(soft.individual.equipments).describedAs("the empty build must never tie the optimum").isNotEmpty()
    }

    /** Same lock on the max-damage soft leg: the floored region ranks by the raw damage core. */
    @Test
    fun `soft max-damage objective keeps the damage gradient when targets are far out of reach`() {
        val unconstrained = solveSoft(mdParams(emptyList()))
        val soft = solveSoft(mdParams(listOf(farTarget)))
        assertThat(unconstrained.isOptimal).isTrue()
        assertThat(soft.isOptimal).describedAs("the tiny pool must be PROVEN, so the lock compares optima").isTrue()
        val unconstrainedDamage = requireNotNull(unconstrained.maxDamageObjective)
        assertThat(unconstrainedDamage).isPositive()
        assertThat(soft.maxDamageObjective)
            .describedAs("floored region: the soft optimum is the unconstrained max-damage build (multiplier 1)")
            .isEqualTo(unconstrainedDamage)
        assertThat(soft.maxDamageRawProxy).isEqualTo(unconstrained.maxDamageRawProxy)
        assertThat(soft.individual.equipments).describedAs("the empty build must never tie the optimum").isNotEmpty()
    }

    /**
     * The opt-in survivability soft-floor wraps the damage core in a sibling power-2 table with the same
     * integer floor: a min-EHP far above every build's EHP proxy (the GUI field takes up to 999 999) mapped
     * every bucket to 0 and flattened the max-damage objective just like an unreachable required target.
     * Floored region ⇒ every build pays the same maximal tax, so the optimum is the max-damage build.
     */
    @Test
    fun `survivability floor keeps the damage gradient when the EHP floor is far out of reach`() {
        val unconstrained = solveSoft(mdParams(emptyList()))
        val farFloor = mdParams(emptyList()).let { it.copy(damageScenario = it.damageScenario.copy(survivabilityFloor = true, minEffectiveHp = 999_999)) }
        val soft = solveSoft(farFloor)
        assertThat(soft.isOptimal).describedAs("the tiny pool must be PROVEN, so the lock compares optima").isTrue()
        val unconstrainedDamage = requireNotNull(unconstrained.maxDamageObjective)
        assertThat(soft.maxDamageObjective)
            .describedAs("floored region: every build keeps damage × 1 / MAX_SURVIVABILITY_MULTIPLIER")
            .isEqualTo(unconstrainedDamage / 1_000L)
        assertThat(soft.individual.equipments).describedAs("the empty build must never tie the optimum").isNotEmpty()
    }

    /**
     * The CI flake's production path: the hard leg is INFEASIBLE, the soft fallback runs on the wall-clock
     * portfolio with 2 workers — the worker count that returned the empty build 2/3 of the time. Every run
     * must now deliver the max-mastery build (the soft optimum is unique in core, so worker interleaving
     * cannot change the delivered objective).
     */
    @Test
    fun `production most-masteries fallback never delivers the empty build with 2 workers`() {
        val expected = requireNotNull(solveSoft(mmParams(emptyList())).mostMasteriesObjective) * WakfuBuildSolver.OVERSHOOT_SCALE
        val p = mmParams(listOf(farTarget)).copy(solverWorkers = 2)
        repeat(PRODUCTION_RUNS) { run ->
            val last =
                runBlocking {
                    WakfuBestBuildFinderAlgorithm
                        .mostMasteriesHardThenSoft(p, pool, emptyList(), emptyList())
                        .toList()
                        .last()
                }
            assertThat(last.progressPercentage).describedAs("run $run: the soft fallback's guaranteed final send").isEqualTo(100)
            assertThat(last.individual.equipments).describedAs("run $run: the empty build must never be delivered").isNotEmpty()
            assertThat(last.mostMasteriesObjective).describedAs("run $run: the delivered build is the soft optimum").isEqualTo(expected)
        }
    }

    /** Max-damage counterpart: the hard→soft probe the external loop runs per element, 2 workers. */
    @Test
    fun `production max-damage fallback never delivers the empty build with 2 workers`() {
        val expected = requireNotNull(solveSoft(mdParams(emptyList())).maxDamageObjective)
        val p = mdParams(listOf(farTarget)).copy(solverWorkers = 2)
        repeat(PRODUCTION_RUNS) { run ->
            val last =
                runBlocking {
                    MaxDamageSearch
                        .optimizeHardThenSoft(p, pool, emptyList(), emptyList(), tuning = null)
                        .toList()
                        .last()
                }
            assertThat(last.progressPercentage).describedAs("run $run: the soft fallback's guaranteed final send").isEqualTo(100)
            assertThat(last.individual.equipments).describedAs("run $run: the empty build must never be delivered").isNotEmpty()
            assertThat(last.maxDamageObjective).describedAs("run $run: the delivered build is the soft optimum").isEqualTo(expected)
        }
    }

    /**
     * Lockstep guard: both certificates mirror the solver's multiplier. Had the solver's floor not been
     * mirrored, the certificate would price every floored state at 0 (+ the overshoot slack) — strictly
     * BELOW the now-positive optimum, i.e. an under-count (a false badge). Bound ≥ the proven optimum.
     */
    @Test
    fun `certificates price the floored multiplier exactly like the solver`() {
        val mm = mmParams(listOf(farTarget))
        val mmOptimum = requireNotNull(solveSoft(mm).mostMasteriesObjective)
        val mmBound = requireNotNull(MostMasteriesCertificate.bound(mm, pool, emptyList(), emptyList())) { "MM certificate bailed" }
        assertThat(mmBound.foldedBound).describedAs("SOUNDNESS — MM certificate vs the floored soft optimum").isGreaterThanOrEqualTo(mmOptimum)

        val md = mdParams(listOf(farTarget))
        val mdOptimum = requireNotNull(solveSoft(md).maxDamageObjective)
        val mdBound =
            requireNotNull(MaxDamageSoftCertificate.bound(md, pool, emptyList(), emptyList(), blockGate = false)) {
                "max-damage soft certificate bailed"
            }
        assertThat(mdBound.foldedBound).describedAs("SOUNDNESS — soft certificate vs the floored soft optimum").isGreaterThanOrEqualTo(mdOptimum)
    }

    private companion object {
        // Before the fix one 2-worker run delivered the empty build with p ≈ 2/3, so 6 clean runs in a row
        // had p ≈ 0.14% — the lock fails on a regression without a flaky single-shot assertion.
        const val PRODUCTION_RUNS = 6
    }
}
