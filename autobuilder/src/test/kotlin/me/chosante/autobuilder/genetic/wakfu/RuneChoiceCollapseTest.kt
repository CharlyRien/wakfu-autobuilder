package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class RuneChoiceCollapseTest {
    private val runes = WakfuBestBuildFinderAlgorithm.runes
    private val neutrality = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Neutralité III" }

    private fun params() =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, 230, 0, CharacterSkills(230)),
            targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE),
            useRunes = true,
            useSublimations = true
        )

    private fun item(
        id: Int,
        type: ItemType,
        sockets: Int,
        level: Int = 230,
        extraStats: Map<Characteristic, Int> = emptyMap(),
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = level,
        name = I18nText("item$id", "item$id", "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000) + extraStats,
        maxShardSlots = sockets
    )

    private fun solve(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        runeTypes: List<RuneType> = runes,
        subs: List<Sublimation> = listOf(neutrality),
    ) = WakfuBuildSolver.timedMaxDamageProfileForTest(
        params,
        pool,
        runeTypes,
        subs,
        workers = 1,
        seconds = 60.0,
        applyDomination = false,
        randomSeed = 1,
        deterministicLimit = 30.0,
        interleave = true
    )

    /**
     * v53 model-fidelity lock (2026-10-03): the collapse booked the cape's elemental rune as DISTANCE,
     * disabling Neutralité. An HP=0 row switched to the general fold and raised the real optimum by 14.8%.
     * The signed-secondary helmet variant also exercises world N's secondary-budget relief.
     */
    @Test
    fun `elemental rune collapse preserves Neutralite and the general-fold optimum`() {
        val base = listOf(item(1, ItemType.CAPE, 4), item(2, ItemType.BOOTS, 0))
        val helmet = item(3, ItemType.HELMET, 4, level = 200, extraStats = mapOf(Characteristic.MASTERY_BACK to -120))
        for ((items, expected) in listOf(base to 1_540_880L, (base + helmet) to 2_198_020L)) {
            val pool = items.groupBy { it.itemType }
            val freeParams = params()
            val generalParams = freeParams.copy(targetStats = TargetStats(freeParams.targetStats.toList() + TargetStat(Characteristic.HP, 0)))
            val general = solve(generalParams, pool)
            val collapse = solve(freeParams, pool)
            assertThat(general.status).isEqualTo("OPTIMAL")
            assertThat(collapse.status).isEqualTo("OPTIMAL")
            assertThat(general.rawObjective).describedAs("the general fold's known real optimum").isEqualTo(expected)
            assertThat(collapse.rawObjective).describedAs("elemental runes must not pay the secondary-mastery budget").isEqualTo(general.rawObjective)
            assertThat(collapse.selectedSublimationStateIds).contains(neutrality.stateId)
            assertThat(general.selectedSublimationStateIds).contains(neutrality.stateId)

            val (exact, fast, tier15) =
                WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(freeParams, pool, runes, listOf(neutrality))
            // Every cell must bound the corrected model, including world N's elemental default and crit swap.
            for (ap in exact.keys.sorted()) {
                val pinned = solve(freeParams.copy(maxDamageApTarget = ap), pool)
                if (!pinned.hasSolution) continue
                assertThat(pinned.status).describedAs("AP=%d: the tiny pool must prove its optimum", ap).isEqualTo("OPTIMAL")
                for ((tier, bound) in listOf("exact" to exact.getValue(ap), "fast" to fast.getValue(ap), "tier1.5" to tier15.getValue(ap))) {
                    assertThat(bound).describedAs("AP=%d: %s must bound the real rune optimum", ap, tier).isGreaterThanOrEqualTo(pinned.rawObjective)
                    assertThat(bound).describedAs("AP=%d: %s must certify this supported shape", ap, tier).isLessThan(Long.MAX_VALUE)
                }
            }
        }
    }

    @Test
    fun `elemental default is suppressed when the larger critical rune wins`() {
        // A doubled critical rune on an elemental-favoured carrier exercises the two-choice shape with
        // an ELEMENTAL default (44/socket), unlike today's catalog where that pair happens not to occur.
        val runeTypes =
            runes.map { rune ->
                if (rune.characteristic == Characteristic.MASTERY_CRITICAL) rune.copy(doubleBonusPosition = listOf(13)) else rune
            }
        val pool = listOf(item(1, ItemType.CAPE, 4, extraStats = mapOf(Characteristic.CRITICAL_HIT to 97))).groupBy { it.itemType }
        val freeParams = params().copy(useSublimations = false)
        val generalParams = freeParams.copy(targetStats = TargetStats(freeParams.targetStats.toList() + TargetStat(Characteristic.HP, 0)))
        val general = solve(generalParams, pool, runeTypes, emptyList())
        val collapse = solve(freeParams, pool, runeTypes, emptyList())
        assertThat(general.status).isEqualTo("OPTIMAL")
        assertThat(collapse.status).isEqualTo("OPTIMAL")
        assertThat(collapse.rawObjective).describedAs("the crit swap must suppress the elemental default").isEqualTo(general.rawObjective)
        val result =
            runBlocking {
                WakfuBuildSolver
                    .optimize(
                        freeParams,
                        pool,
                        runeTypes,
                        emptyList(),
                        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 30.0)
                    ).toList()
                    .last()
            }
        assertThat(result.isOptimal).isTrue()
        assertThat(
            result.individual.runes.values
                .flatten()
                .map { it.characteristic }
        ).describedAs("export must suppress the default and fill all four sockets with the critical choice")
            .containsExactly(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_CRITICAL)

        val (exact, fast, tier15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(freeParams, pool, runeTypes)
        for ((tier, cells) in listOf("exact" to exact, "fast" to fast, "tier1.5" to tier15)) {
            assertThat(cells.values.maxOrNull()).describedAs("%s must preserve the elemental-default crit option", tier).isGreaterThanOrEqualTo(collapse.rawObjective)
        }
    }
}
