package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * P&B-1 harness (plan §8.15): the restricted DD's best value must be a TRUE LOWER bound of the
 * banked optima (the CANARY — a violation is an exactness bug in the primal semantics), and the
 * measured question is how close the beam gets (and at what width/wall).
 *
 * ```shell
 * WAKFU_MM_PNB1=1 [WAKFU_MM_PNB1_DEBUG=1] \
 *   ./gradlew :autobuilder:test --tests '*MostMasteriesRestrictedDDTest*'
 * ```
 */
class MostMasteriesRestrictedDDTest {
    // Re-banked 2026-10-02 on data 1.93.1.62 (production portfolio, both OPTIMAL — S2 in 106 s, S3 in
    // 16 s); the 1.92.1.58 optima were 67_295_807_882_856 / 10_985.
    private val s2Optimum = 67_728_953_322_880L
    private val s3Optimum = 10_993L

    @Test
    fun `manual P&B-1 restricted DD beam on S2 and S3`() {
        assumeTrue(System.getenv("WAKFU_MM_PNB1") == "1")
        val debug = System.getenv("WAKFU_MM_PNB1_DEBUG") == "1"
        val level = 245

        fun params(requiredTargets: List<TargetStat>) =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)) + requiredTargets),
                searchDuration = 600.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = true,
                useSublimations = true
            )

        val frontier =
            listOf(
                TargetStat(Characteristic.ACTION_POINT, 16),
                TargetStat(Characteristic.MOVEMENT_POINT, 8),
                TargetStat(Characteristic.CRITICAL_HIT, 100),
                TargetStat(Characteristic.HP, 12000)
            )
        val widths = listOf(10_000, 50_000, 200_000)

        for (
        (label, required, optimum) in
        listOf(
            Triple("S3", emptyList(), s3Optimum),
            Triple("S2", frontier, s2Optimum)
        )
        ) {
            val p = params(required)
            val basePool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= p.maxRarity && it.rarity !in p.excludedRarities }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val shape = requireNotNull(dominationShape(p, WakfuBestBuildFinderAlgorithm.sublimations))
            val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool, shape)

            for (w in widths) {
                val r =
                    requireNotNull(
                        MostMasteriesRestrictedDD.bound(
                            p,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations,
                            beamWidth = w,
                            debug = debug
                        )
                    ) { "$label: bailed on a supported shape" }
                val value = if (required.isEmpty()) r.coreBest else r.foldedBest
                assertThat(value)
                    .describedAs("$label W=$w: PRIMAL CANARY — the restricted DD must never exceed the optimum")
                    .isLessThanOrEqualTo(optimum)
                val pct = 100.0 * value / optimum
                println(
                    "MM_PNB1 shape=$label W=$w value=$value optimum=$optimum " +
                        "reach=${"%.2f".format(Locale.ROOT, pct)}% states=${r.states} " +
                        "maxLayerWidth=${r.maxLayerWidth} wallMs=${r.wallMs} best=${r.bestState}"
                )
                if (w == widths.last()) {
                    // P&B-0 piggyback: exact-prefix width per stage (where would the cutset sit?).
                    r.layerWidths.forEach { (stage, width) -> println("MM_PNB0 shape=$label stage=$stage width=$width") }
                }
            }
        }
    }
}
