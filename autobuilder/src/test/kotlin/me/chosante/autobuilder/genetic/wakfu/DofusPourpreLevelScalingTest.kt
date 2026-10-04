package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

/**
 * The Dofus Pourpre's "100% of the level as Elemental Mastery" (Ankama action 999), which the equipments extractor used to
 * drop: the item read as a bare 1 AP + 3 crit relic emblem. It is stored level-independent ([Equipment.percentOfLevel]) and
 * resolved into the item's stats when the request's pool is built ([WakfuBestBuildFinderAlgorithm.poolFor] →
 * [Equipment.atLevel]), so the search, the scorers, both certificates and the domination pre-filter all read it.
 */
class DofusPourpreLevelScalingTest {
    private val pourpreId = 33395
    private val dofushuId = 29456
    private val catalogPourpre: Equipment get() = WakfuBestBuildFinderAlgorithm.equipments.single { it.equipmentId == pourpreId }

    /** The investigation's request: CRA, most-masteries distance + fire with AP / MP / range / crit rows, no runes / subs. */
    private fun params(
        level: Int,
        forced: List<String> = emptyList(),
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats =
            TargetStats(
                listOf(
                    TargetStat(Characteristic.ACTION_POINT, 11),
                    TargetStat(Characteristic.MOVEMENT_POINT, 4),
                    TargetStat(Characteristic.RANGE, 4),
                    TargetStat(Characteristic.CRITICAL_HIT, 25),
                    TargetStat(Characteristic.MASTERY_DISTANCE, 1),
                    TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1)
                )
            ),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = forced,
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes = false,
        useSublimations = false
    )

    private fun Map<ItemType, List<Equipment>>.pourpre(): Equipment? = this[ItemType.EMBLEM]?.singleOrNull { it.equipmentId == pourpreId }

    @Test
    fun `the catalog stores the Pourpre's level-scaled Elemental Mastery, and no other item has such a line`() {
        val pourpre = catalogPourpre
        assertThat(pourpre.characteristics).isEqualTo(mapOf(Characteristic.ACTION_POINT to 1, Characteristic.CRITICAL_HIT to 3))
        assertThat(pourpre.percentOfLevel).isEqualTo(mapOf(Characteristic.MASTERY_ELEMENTARY to 100))
        assertThat(WakfuBestBuildFinderAlgorithm.equipments.filter { it.percentOfLevel.isNotEmpty() }.map { it.equipmentId })
            .containsExactly(pourpreId)
    }

    @Test
    fun `the search pool resolves it at the character's level`() {
        assertThat(WakfuBestBuildFinderAlgorithm.poolFor(params(169)).pourpre()).describedAs("a level-170 item").isNull()
        for (level in listOf(170, 200, 245)) {
            val pooled = requireNotNull(WakfuBestBuildFinderAlgorithm.poolFor(params(level)).pourpre()) { "level $level" }
            assertThat(pooled.characteristics)
                .describedAs("level $level")
                .isEqualTo(mapOf(Characteristic.ACTION_POINT to 1, Characteristic.CRITICAL_HIT to 3, Characteristic.MASTERY_ELEMENTARY to level))
            assertThat(pooled.percentOfLevel).isEmpty()
        }
        // Every production entry builds its pool with the same function: a forced search included.
        assertThat(WakfuBestBuildFinderAlgorithm.poolFor(params(245, forced = listOf("Dofus Pourpre"))).getValue(ItemType.EMBLEM))
            .containsExactly(catalogPourpre.atLevel(245))
    }

    @Test
    fun `domination compares the resolved stats, so Dofushu no longer evicts the Pourpre`() {
        val dofushu = WakfuBestBuildFinderAlgorithm.equipments.single { it.equipmentId == dofushuId }
        for (level in listOf(230, 245)) {
            val p = params(level)
            val shape = requireNotNull(dominationShape(p, emptyList())) { "most-masteries domination is on" }

            fun keptBesideDofushu(pourpre: Equipment) = pourpre in filterDominatedPool(mapOf(ItemType.EMBLEM to listOf(dofushu, pourpre)), shape).getValue(ItemType.EMBLEM)

            // The unresolved item (1 AP, 3 crit) is beaten by Dofushu (1 AP, 6 crit, 444 HP, 306 mastery on three random
            // elements) on every stat: the eviction the investigation measured.
            assertThat(keptBesideDofushu(catalogPourpre)).describedAs("level $level, unresolved").isFalse()
            // +level Elemental Mastery is a line Dofushu lacks: not dominated.
            assertThat(keptBesideDofushu(catalogPourpre.atLevel(level))).describedAs("level $level, resolved").isTrue()
            // And the production pool keeps it.
            val kept = filterDominatedPool(WakfuBestBuildFinderAlgorithm.poolFor(p), shape)
            assertThat(kept.pourpre()).describedAs("level $level, production pool").isEqualTo(catalogPourpre.atLevel(level))
        }
    }

    @Test
    fun `the multi-element prefilter ranks the Pourpre by its resolved Elemental Mastery`() {
        // Two elements ⇒ the heuristic top-8-per-stat prefilter runs. Elemental Mastery feeds both, and at 245 the Pourpre
        // has the most of it of any emblem.
        val p =
            params(245).copy(
                targetStats =
                    TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1), TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 1)))
            )
        assertThat(WakfuBuildSolver.needsItemPrefilter(p.targetStats)).isTrue()
        val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
        assertThat(WakfuBuildSolver.prefilteredPoolForTest(p, pool).pourpre()).isEqualTo(catalogPourpre.atLevel(245))
        // Unresolved, the item has no line this request reads (its AP and crit are not asked for): the prefilter dropped it.
        val unresolved = pool + (ItemType.EMBLEM to pool.getValue(ItemType.EMBLEM).map { if (it.equipmentId == pourpreId) catalogPourpre else it })
        assertThat(WakfuBuildSolver.prefilteredPoolForTest(p, unresolved).pourpre()).isNull()
    }

    /**
     * The investigation's numbers on the deterministic protocol (1 worker, fixed seed, interleaved search): forcing the
     * Pourpre at level 245 scores 7 772 = (3 289 distance + 3 532 fire + its 245) × 1.10 DI, against 7 503 with the unresolved
     * item. The free optimum is 7 860 either way (the `manual numbers` harness), so forcing it costs 1.1 % instead of 4.5 %.
     */
    @Test
    fun `forcing the Pourpre at level 245 now credits its Elemental Mastery`() {
        val forced = params(245, forced = listOf("Dofus Pourpre"))
        val pool = WakfuBestBuildFinderAlgorithm.poolFor(forced)
        val resolved = solveHardLeg(forced, pool)
        assertThat(resolved.isOptimal).isTrue()
        assertThat(resolved.individual.equipments.map { it.equipmentId }).contains(pourpreId)
        assertThat(resolved.matchPercentage).isEqualByComparingTo(BigDecimal(7_772))

        // The same request on the unresolved item: the pre-fix optimum.
        val unresolved = solveHardLeg(forced, pool + (ItemType.EMBLEM to listOf(catalogPourpre)))
        assertThat(unresolved.isOptimal).isTrue()
        assertThat(unresolved.matchPercentage).isEqualByComparingTo(BigDecimal(7_503))
    }

    /**
     * Before → after for the report: free and forced, levels 200 and 245, the unresolved item vs the resolved one.
     * Manual: `WAKFU_POURPRE_NUMBERS=1` (levels via `WAKFU_POURPRE_LEVELS`, det budget via `WAKFU_POURPRE_DET`).
     */
    @Test
    @Tag("manual")
    fun `manual numbers`() {
        assumeTrue(System.getenv("WAKFU_POURPRE_NUMBERS") == "1")
        val levels = System.getenv("WAKFU_POURPRE_LEVELS")?.split(',')?.map { it.trim().toInt() } ?: listOf(200, 245)
        val det = System.getenv("WAKFU_POURPRE_DET")?.toDoubleOrNull() ?: 600.0
        for (level in levels) {
            for ((variant, forcedNames) in listOf("free" to emptyList(), "forced" to listOf("Dofus Pourpre"))) {
                val p = params(level, forcedNames)
                val resolvedPool = WakfuBestBuildFinderAlgorithm.poolFor(p)
                val unresolvedPool =
                    resolvedPool.mapValues { (_, items) -> items.map { if (it.equipmentId == pourpreId) catalogPourpre else it } }
                for ((label, pool) in listOf("unresolved" to unresolvedPool, "resolved" to resolvedPool)) {
                    val t0 = System.nanoTime()
                    val r = solveHardLeg(p, pool, det)
                    println(
                        "POURPRE level=$level $variant $label score=${r.matchPercentage} optimal=${r.isOptimal} " +
                            "wearsPourpre=${r.individual.equipments.any { it.equipmentId == pourpreId }} " +
                            "emblem=${r.individual.equipments.firstOrNull { it.itemType == ItemType.EMBLEM }?.name?.fr} " +
                            "ms=${(System.nanoTime() - t0) / 1_000_000}"
                    )
                }
            }
        }
    }

    /** The production most-masteries hard leg (the targets are reachable) on the deterministic protocol. */
    private fun solveHardLeg(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        det: Double = 300.0,
    ): SolverResult<BuildCombination> =
        runBlocking {
            WakfuBuildSolver
                .optimize(
                    p,
                    pool,
                    emptyList(),
                    emptyList(),
                    WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = det),
                    hardConstraints = true
                ).toList()
                .last()
        }
}
