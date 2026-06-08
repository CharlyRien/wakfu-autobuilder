package me.chosante.ui.history

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuSolver
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.state.ItemChip
import me.chosante.ui.state.UiState
import me.chosante.ui.state.statDefFor
import me.chosante.ui.state.toRow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class HistoryMappingTest {
    @Test
    fun `reconstructSkills round-trips a flat allocation`() {
        val original = CharacterSkills(110)
        // Assign a few points to concrete skill lines, then flatten and rebuild.
        original.intelligence
            .getCharacteristics()
            .first()
            .setPointAssigned(3)
        original.strength
            .getCharacteristics()
            .first()
            .setPointAssigned(2)

        val flat = original.toFlatMap()
        val rebuilt = reconstructSkills(110, flat)

        assertThat(rebuilt.allCharacteristicValues).isEqualTo(original.allCharacteristicValues)
        assertThat(rebuilt.toFlatMap()).isEqualTo(flat)
    }

    @Test
    fun `reconstructSkills ignores unknown skill names`() {
        val rebuilt = reconstructSkills(110, mapOf("Totally Made Up Skill" to 99))
        // No crash, no points assigned for the bogus name.
        assertThat(
            rebuilt.allCharacteristicValues.fixedValues.values
                .sum()
        ).isEqualTo(0)
    }

    @Test
    fun `toHistoryEntry then toBuildCombination preserves the request and result`() {
        val skills =
            CharacterSkills(110).also {
                it.intelligence
                    .getCharacteristics()
                    .first()
                    .setPointAssigned(4)
            }
        val cape =
            Equipment(
                equipmentId = 42,
                guiId = 99,
                level = 110,
                name = I18nText(fr = "Cape", en = "Cape", es = "", pt = ""),
                rarity = Rarity.LEGENDARY,
                itemType = ItemType.CAPE,
                characteristics = mapOf(Characteristic.MASTERY_DISTANCE to 60)
            )
        val build = BuildCombination(equipments = listOf(cape), characterSkills = skills)
        val ui =
            UiState(
                clazz = CharacterClass.IOP,
                level = 110,
                minLevel = 90,
                mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                solver = WakfuSolver.OR_TOOLS,
                maxRarity = Rarity.RELIC,
                duration = "30",
                stopAtMatch = true,
                targets = listOfNotNull(statDefFor(Characteristic.MASTERY_DISTANCE)?.toRow("1")),
                forcedItems = listOf(ItemChip(name = "Cape", rarity = Rarity.LEGENDARY, matchName = "Cape")),
                build = build,
                achieved = mapOf(Characteristic.MASTERY_DISTANCE to 1280),
                match = BigDecimal("97"),
                optimal = true,
                zenithUrl = "https://zenithwakfu.com/builder/xyz"
            )

        val entry = ui.toHistoryEntry(id = "id-1", name = "Iop Distance", note = "  ", createdAt = 123L, dataVersion = "1.91.1.54")!!

        assertThat(entry.name).isEqualTo("Iop Distance")
        assertThat(entry.note).isNull() // blank note normalized away
        assertThat(entry.dataVersion).isEqualTo("1.91.1.54")
        assertThat(entry.request.clazz).isEqualTo("IOP")
        assertThat(entry.request.mode).isEqualTo("FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT")
        assertThat(entry.request.solver).isEqualTo("OR_TOOLS")
        assertThat(entry.request.maxRarity).isEqualTo(Rarity.RELIC)
        assertThat(entry.request.duration).isEqualTo("30")
        assertThat(entry.request.stopAtMatch).isTrue()
        assertThat(
            entry.request.forcedItems
                .single()
                .matchName
        ).isEqualTo("Cape")
        assertThat(entry.result.match).isEqualTo(97.0)
        assertThat(entry.result.optimal).isTrue()
        assertThat(entry.zenithUrl).isEqualTo("https://zenithwakfu.com/builder/xyz")

        // Restoration helpers reflect the stored request.
        assertThat(entry.restoredClass()).isEqualTo(CharacterClass.IOP)
        assertThat(entry.restoredMode()).isEqualTo(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)
        assertThat(entry.restoredSolver()).isEqualTo(WakfuSolver.OR_TOOLS)
        assertThat(entry.toForcedChips().single().matchName).isEqualTo("Cape")

        // The discovered build reconstructs with identical equipment and skill values.
        val rebuilt = entry.toBuildCombination()
        assertThat(rebuilt.equipments).isEqualTo(listOf(cape))
        assertThat(rebuilt.characterSkills.allCharacteristicValues).isEqualTo(skills.allCharacteristicValues)
    }

    @Test
    fun `toHistoryEntry returns null without a build`() {
        assertThat(UiState().toHistoryEntry("id", "name", null, 0L, "v")).isNull()
    }
}
