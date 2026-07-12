package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.valueFor
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.Assignable
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.common.skills.UnitType
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Measurement-only screen for the proposed per-item hard-target feasibility filter.
 *
 * For the four simple near-frontier targets (AP/MP/crit/HP), computes a sound over-estimate of
 * `actual(stat) | item selected`:
 *  - equipment choices are exact for slot occupancy, weapon compatibility, distinct-name rings and the
 *    one-epic/one-relic budgets;
 *  - every carrier may take the best rune for the probed stat (ignores rune/sub colour competition);
 *  - fixed and percent skill budgets are maximized independently (they can spend the same branch twice);
 *  - sublimation conditions, carriers and colours are ignored, while the 10-normal/1-epic/1-relic caps remain;
 *  - positive stat effects are credited even when their build-static condition could not fire.
 *
 * All relaxations only ADD reach. A conversion into a probed stat deliberately bails instead of claiming
 * soundness; none currently targets these four stats. This is a dry-run only: it never edits the CP-SAT pool.
 */
class MostMasteriesConditionalCeilingAnalysisTest {
    private data class Option(
        val value: Long,
        val epic: Int = 0,
        val relic: Int = 0,
    )

    private data class SkillUpper(
        val fixed: Long,
        val percent: Long,
    )

    private class Analyzer(
        private val params: WakfuBestBuildParams,
        private val pool: Map<ItemType, List<Equipment>>,
        private val runes: List<RuneType>,
        private val sublimations: List<Sublimation>,
    ) {
        private val all = pool.values.flatten()
        private val weaponTypes =
            setOf(
                ItemType.ONE_HANDED_WEAPONS,
                ItemType.TWO_HANDED_WEAPONS,
                ItemType.OFF_HAND_WEAPONS
            )

        private fun rarityOption(
            value: Long,
            vararg items: Equipment,
        ): Option =
            Option(
                value = value,
                epic = items.count { it.rarity == Rarity.EPIC },
                relic = items.count { it.rarity == Rarity.RELIC }
            )

        private fun collapse(options: Iterable<Option>): List<Option> =
            options
                .filter { it.epic <= 1 && it.relic <= 1 }
                .groupBy { it.epic to it.relic }
                .values
                .map { group -> group.maxBy { it.value } }

        private fun itemValue(
            item: Equipment,
            stat: Characteristic,
        ): Long {
            val rune =
                if (params.useRunes) {
                    runes
                        .filter { it.characteristic == stat }
                        .maxOfOrNull { it.valueOn(item.itemType, item.level) }
                        ?.toLong()
                        ?: 0L
                } else {
                    0L
                }
            return item.valueFor(stat).toLong() + item.maxShardSlots * rune
        }

        private fun stageKey(type: ItemType): String =
            when {
                type == ItemType.RING -> "rings"
                type in weaponTypes -> "weapons"
                else -> type.name
            }

        private fun genericStageOptions(
            key: String,
            stat: Characteristic,
        ): List<Option> =
            when (key) {
                "rings" -> {
                    val rings = pool[ItemType.RING].orEmpty()
                    val out = mutableListOf(Option(0L))
                    for (i in rings.indices) {
                        val a = rings[i]
                        out += rarityOption(itemValue(a, stat), a)
                        for (j in i + 1 until rings.size) {
                            val b = rings[j]
                            if (a.name.fr.lowercase() == b.name.fr.lowercase()) continue
                            out += rarityOption(itemValue(a, stat) + itemValue(b, stat), a, b)
                        }
                    }
                    collapse(out)
                }

                "weapons" -> {
                    val one = pool[ItemType.ONE_HANDED_WEAPONS].orEmpty()
                    val two = pool[ItemType.TWO_HANDED_WEAPONS].orEmpty()
                    val off = pool[ItemType.OFF_HAND_WEAPONS].orEmpty()
                    val out = mutableListOf(Option(0L))
                    for (item in one + two + off) out += rarityOption(itemValue(item, stat), item)
                    for (a in one) {
                        for (b in off) out += rarityOption(itemValue(a, stat) + itemValue(b, stat), a, b)
                    }
                    collapse(out)
                }

                else -> {
                    val type = ItemType.valueOf(key)
                    collapse(listOf(Option(0L)) + pool[type].orEmpty().map { rarityOption(itemValue(it, stat), it) })
                }
            }

        private fun selectedStageOptions(
            selected: Equipment,
            stat: Characteristic,
        ): List<Option> =
            when (selected.itemType) {
                ItemType.RING -> {
                    val out = mutableListOf(rarityOption(itemValue(selected, stat), selected))
                    for (other in pool[ItemType.RING].orEmpty()) {
                        if (other == selected || other.name.fr.lowercase() == selected.name.fr.lowercase()) continue
                        out += rarityOption(itemValue(selected, stat) + itemValue(other, stat), selected, other)
                    }
                    collapse(out)
                }

                ItemType.TWO_HANDED_WEAPONS -> listOf(rarityOption(itemValue(selected, stat), selected))

                ItemType.ONE_HANDED_WEAPONS -> {
                    val out = mutableListOf(rarityOption(itemValue(selected, stat), selected))
                    for (other in pool[ItemType.OFF_HAND_WEAPONS].orEmpty()) {
                        out += rarityOption(itemValue(selected, stat) + itemValue(other, stat), selected, other)
                    }
                    collapse(out)
                }

                ItemType.OFF_HAND_WEAPONS -> {
                    val out = mutableListOf(rarityOption(itemValue(selected, stat), selected))
                    for (other in pool[ItemType.ONE_HANDED_WEAPONS].orEmpty()) {
                        out += rarityOption(itemValue(selected, stat) + itemValue(other, stat), selected, other)
                    }
                    collapse(out)
                }

                else -> listOf(rarityOption(itemValue(selected, stat), selected))
            }

        /** Exact item/rune-layer maximum for [stat], conditional on [selected] being equipped. */
        private fun itemLayerUpper(
            selected: Equipment,
            stat: Characteristic,
        ): Long {
            val keys = pool.keys.map(::stageKey).toSet()
            val selectedKey = stageKey(selected.itemType)
            var dp = mapOf((0 to 0) to 0L)
            for (key in keys) {
                val options =
                    if (key == selectedKey) {
                        selectedStageOptions(selected, stat)
                    } else {
                        genericStageOptions(key, stat)
                    }
                val next = mutableMapOf<Pair<Int, Int>, Long>()
                for ((state, value) in dp) {
                    for (option in options) {
                        val e = state.first + option.epic
                        val r = state.second + option.relic
                        if (e > 1 || r > 1) continue
                        val keyState = e to r
                        next[keyState] = maxOf(next[keyState] ?: Long.MIN_VALUE, value + option.value)
                    }
                }
                dp = next
            }
            return dp.values.max()
        }

        private fun skillValuePerPoint(
            skill: SkillCharacteristic,
            stat: Characteristic,
            unit: UnitType,
        ): Int =
            if (skill is SkillCharacteristic.PairedCharacteristic) {
                skillValuePerPoint(skill.first, stat, unit) + skillValuePerPoint(skill.second, stat, unit)
            } else if (skill.characteristic == stat && skill.unitType == unit) {
                skill.unitValue
            } else {
                0
            }

        private fun branchUpper(
            branch: Assignable<*>,
            stat: Characteristic,
            unit: UnitType,
        ): Long {
            var remaining = branch.maxPointsToAssign
            var result = 0L
            for (skill in branch.getCharacteristics().sortedByDescending { skillValuePerPoint(it, stat, unit) }) {
                val perPoint = skillValuePerPoint(skill, stat, unit)
                if (perPoint <= 0 || remaining == 0) break
                val points = minOf(remaining, skill.maxPointsAssignable)
                result += points.toLong() * perPoint
                remaining -= points
            }
            return result
        }

        private fun skillUpper(stat: Characteristic): SkillUpper {
            val skills = params.character.characterSkills
            val branches = listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major)
            // Deliberately maximize FIXED and PERCENT in separate fantasy allocations. Their sum is an upper bound.
            return SkillUpper(
                fixed = branches.sumOf { branchUpper(it, stat, UnitType.FIXED) },
                percent = branches.sumOf { branchUpper(it, stat, UnitType.PERCENT) }
            )
        }

        private fun sublimationUpper(stat: Characteristic): Long? {
            val byRarity = mutableMapOf<SublimationRarity, MutableList<Long>>()
            for (sub in sublimations) {
                if (!params.useSublimations || !sub.solverChoosable) continue
                if (params.maxSublimationTier != null && sub.nameTier > params.maxSublimationTier) continue
                var value = 0L
                for (effect in sub.effects) {
                    if (!WakfuBuildSolver.scenarioGateMatches(effect.scenarioGate, params)) continue
                    when (effect) {
                        is SublimationEffect.StatEffect ->
                            if (effect.characteristic == stat) value += maxOf(effect.magnitudeAtLevel(params.character.level), 0)

                        is SublimationEffect.PerStatStep ->
                            if (effect.target == stat) value += maxOf(effect.cap, 0)

                        is SublimationEffect.Conversion ->
                            if (effect.to == stat) return null // unsupported: refusing an unsafe ceiling

                        is SublimationEffect.BestElementConcentration -> Unit
                    }
                }
                repeat(sub.maxCopies) { byRarity.getOrPut(sub.rarity) { mutableListOf() } += value }
            }

            fun top(
                rarity: SublimationRarity,
                count: Int,
            ): Long =
                byRarity[rarity]
                    .orEmpty()
                    .sortedDescending()
                    .take(count)
                    .sum()

            return top(SublimationRarity.NORMAL, MAX_NORMAL_SUBLIMATIONS.toInt()) +
                top(SublimationRarity.EPIC, 1) +
                top(SublimationRarity.RELIC, 1)
        }

        fun ceiling(
            selected: Equipment,
            stat: Characteristic,
        ): Long? {
            val skills = skillUpper(stat)
            val sub = sublimationUpper(stat) ?: return null
            var preSub =
                (params.character.baseCharacteristicValues[stat] ?: 0).toLong() +
                    itemLayerUpper(selected, stat) + skills.fixed

            // These are hard constraints on preSubStat in the real model. Applying them before the relaxed
            // sublimation layer only tightens the over-estimate; in-combat subs may still exceed the sheet cap.
            preSub =
                when (stat) {
                    Characteristic.ACTION_POINT -> minOf(preSub, MAX_OUT_OF_COMBAT_AP)
                    Characteristic.MOVEMENT_POINT -> minOf(preSub, MAX_OUT_OF_COMBAT_MP)
                    else -> preSub
                }
            val prePercent = preSub + sub
            val percentGain =
                if (prePercent > 0L && skills.percent > 0L) {
                    // ceil instead of the model's nearest-integer rounding: still an upper bound.
                    (prePercent * skills.percent + 99L) / 100L
                } else {
                    0L
                }
            return prePercent + percentGain
        }

        fun analyze(targets: List<TargetStat>) {
            val rejectedBy = targets.associate { it.characteristic to mutableSetOf<Equipment>() }
            val unsupported = mutableSetOf<Characteristic>()
            for (item in all) {
                for (target in targets) {
                    val ceiling = ceiling(item, target.characteristic)
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
        Analyzer(params, productionPool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations).analyze(targets)
    }
}
