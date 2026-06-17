package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
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
 * Lot 4 — passive loadout. Oracle: FECA's **Ligne** is a fully-declarative passive granting +1 RANGE, so it
 * is both folded into the resolved stats and carried on the build.
 */
class PassiveSolverTest {
    private val ligne =
        PassiveCatalog.findByName(CharacterClass.FECA, "Ligne")
            ?: error("data oracle missing: FECA 'Ligne' passive (update if the data version changed)")

    @Test
    fun `a selected passive's flat stats fold into the recomputed characteristics`() {
        assertThat(ligne.flatStats).describedAs("Ligne grants +1 RANGE").containsEntry(Characteristic.RANGE, 1)
        val character = Character(CharacterClass.FECA, level = 200, minLevel = 1)
        val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(200), passives = listOf(ligne))

        val withPassive = computeCharacteristicsValues(build, character.baseCharacteristicValues, emptyMap(), emptyMap())
        val without =
            computeCharacteristicsValues(build.copy(passives = emptyList()), character.baseCharacteristicValues, emptyMap(), emptyMap())

        assertThat((withPassive[Characteristic.RANGE] ?: 0) - (without[Characteristic.RANGE] ?: 0))
            .describedAs("the passive's +1 RANGE shows up in the recomputed stats, matching the solver fold")
            .isEqualTo(1)
    }

    @Test
    fun `the solved build carries the selected passive loadout`(): Unit =
        runBlocking {
            val character = Character(CharacterClass.FECA, level = 200, minLevel = 1)
            val pool =
                listOf(
                    equipment(1, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100)),
                    equipment(2, ItemType.BELT, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 50))
                ).groupBy { it.itemType }
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1))),
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = false,
                    forcedPassives = listOf("Ligne")
                )

            val build =
                WakfuBuildSolver
                    .optimize(params, pool, WakfuBuildSolver.SolverTuning())
                    .toList()
                    .last()
                    .individual

            assertThat(build.passives.map { it.name })
                .describedAs("the forced passive loadout rides on the discovered build")
                .contains("Ligne")
            val achieved = computeCharacteristicsValues(build, character.baseCharacteristicValues, emptyMap(), emptyMap())
            assertThat(achieved[Characteristic.RANGE] ?: 0)
                .describedAs("no gear grants range in this pool, so the +1 is purely the passive")
                .isGreaterThanOrEqualTo(1)
        }

    @Test
    fun `a passive granting an element-specific mastery is reflected in the recomputed per-element value`() {
        // Regression guard: the re-scorer recomputes requested elemental masteries from the passive-inclusive
        // sum, so an element-specific passive bonus is not dropped (it was, when it read the pre-passive sum).
        val character = Character(CharacterClass.FECA, level = 200, minLevel = 1)
        val firePassive =
            me.chosante.common.Passive(
                spellId = -1,
                name = "Synthetic Fire",
                clazz = "FECA",
                gfxId = 0,
                flatBuildStats = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE.name to 50.0)
            )
        val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(200), passives = listOf(firePassive))

        val achieved =
            computeCharacteristicsValues(
                build,
                character.baseCharacteristicValues,
                masteryElementsWanted = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1),
                resistanceElementsWanted = emptyMap()
            )
        assertThat(achieved[Characteristic.MASTERY_ELEMENTARY_FIRE] ?: 0)
            .describedAs("the passive's +50 fire mastery survives the per-element recomputation")
            .isEqualTo(50)
    }

    private fun equipment(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
    ): Equipment =
        Equipment(
            equipmentId = id,
            guiId = id,
            level = 1,
            name = I18nText("e$id", "e$id", "e$id", "e$id"),
            rarity = Rarity.COMMON,
            itemType = type,
            characteristics = stats
        )
}
