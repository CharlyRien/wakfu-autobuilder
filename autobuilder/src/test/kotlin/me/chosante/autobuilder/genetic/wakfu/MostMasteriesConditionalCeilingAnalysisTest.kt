package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Measurement-only screen for the proposed per-item hard-target feasibility filter (plan §7.5).
 * The per-item ceiling machinery lives in [CeilingAnalyzer] (shared with the §8.3 soft screen);
 * this test applies it to the four near-frontier hard targets: an item whose conditional ceiling
 * cannot reach a required target can never appear in a hard-feasible build.
 */
class MostMasteriesConditionalCeilingAnalysisTest {
    @Test
    fun `manual per-item hard-target ceiling screen at 245`() {
        assumeTrue(System.getenv("WAKFU_MM_ITEM_CEILING") == "1")
        val level = 245
        val targets =
            listOf(
                TargetStat(Characteristic.ACTION_POINT, 16),
                TargetStat(Characteristic.MOVEMENT_POINT, 8),
                TargetStat(Characteristic.CRITICAL_HIT, 100),
                TargetStat(Characteristic.HP, 12000)
            )
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)) + targets),
                searchDuration = 120.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = true,
                useSublimations = true
            )
        val basePool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= params.maxRarity && it.rarity !in params.excludedRarities }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val shape = requireNotNull(dominationShape(params, WakfuBestBuildFinderAlgorithm.sublimations))
        val productionPool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool, shape)
        println("MM_ITEM_CEILING basePool=${basePool.values.sumOf { it.size }} dominationPool=${productionPool.values.sumOf { it.size }}")

        val analyzer = CeilingAnalyzer(params, productionPool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations)
        val all = analyzer.all
        val rejectedBy = targets.associate { it.characteristic to mutableSetOf<Equipment>() }
        val unsupported = mutableSetOf<Characteristic>()
        for (item in all) {
            for (target in targets) {
                val ceiling = analyzer.ceiling(item, target.characteristic)
                if (ceiling == null) {
                    unsupported += target.characteristic
                } else if (ceiling < target.target) {
                    rejectedBy.getValue(target.characteristic) += item
                }
            }
        }
        val union = rejectedBy.values.flatten().toSet()
        println("MM_ITEM_CEILING pool=${all.size} rejected=${union.size} kept=${all.size - union.size} pct=${"%.2f".format(100.0 * union.size / all.size)}")
        for (target in targets) {
            val rejected = rejectedBy.getValue(target.characteristic)
            println(
                "MM_ITEM_CEILING stat=${target.characteristic} target=${target.target} rejected=${rejected.size} " +
                    "pct=${"%.2f".format(100.0 * rejected.size / all.size)}"
            )
        }
        if (unsupported.isNotEmpty()) println("MM_ITEM_CEILING unsupported=$unsupported")
        val byType =
            union
                .groupingBy { it.itemType }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
        println("MM_ITEM_CEILING rejectedByType=${byType.joinToString { "${it.first}:${it.second}" }}")
    }
}
