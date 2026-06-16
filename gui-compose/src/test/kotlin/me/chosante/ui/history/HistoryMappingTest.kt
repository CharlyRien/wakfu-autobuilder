package me.chosante.ui.history

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneColor
import me.chosante.common.RuneType
import me.chosante.common.history.HistoryEntry
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
        assertThat(entry.toForcedChips().single().matchName).isEqualTo("Cape")

        // The discovered build reconstructs with identical equipment and skill values.
        val rebuilt = entry.toBuildCombination()
        assertThat(rebuilt.equipments).isEqualTo(listOf(cape))
        assertThat(rebuilt.characterSkills.allCharacteristicValues).isEqualTo(skills.allCharacteristicValues)
    }

    @Test
    fun `socketed runes survive a clipboard export-import round-trip and reattach to their item`() {
        // A level-50 amulet enchanted with two distance runes: the enchant level is item-level-gated
        // (item 50 -> level 2), so it must be derivable after a full JSON round-trip.
        val amulet =
            Equipment(
                equipmentId = 7,
                guiId = 7,
                level = 50,
                name = I18nText(fr = "Amulette", en = "Amulet", es = "", pt = ""),
                rarity = Rarity.RARE,
                itemType = ItemType.AMULET,
                characteristics = mapOf(Characteristic.MASTERY_DISTANCE to 30),
                maxShardSlots = 2
            )
        val distanceRune =
            RuneType(
                id = 27098,
                name = I18nText("Distance", "Distance", "", ""),
                color = RuneColor.RED,
                characteristic = Characteristic.MASTERY_DISTANCE,
                doubleBonusPosition = listOf(10, 15),
                gfxId = 0
            )
        val build =
            BuildCombination(
                equipments = listOf(amulet),
                characterSkills = CharacterSkills(110),
                runes = mapOf(amulet to listOf(distanceRune, distanceRune))
            )
        val ui =
            UiState(
                clazz = CharacterClass.CRA,
                level = 110,
                minLevel = 110,
                mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                maxRarity = Rarity.EPIC,
                duration = "30",
                stopAtMatch = false,
                targets = listOfNotNull(statDefFor(Characteristic.MASTERY_DISTANCE)?.toRow("1")),
                build = build,
                achieved = mapOf(Characteristic.MASTERY_DISTANCE to 100),
                match = BigDecimal("100"),
                optimal = true
            )

        val entry = ui.toHistoryEntry(id = "id-2", name = "Runed", note = null, createdAt = 1L, dataVersion = "v")!!
        // Go through the very JSON codec used by the clipboard export/import.
        val restored = historyJson.decodeFromString(HistoryEntry.serializer(), historyJson.encodeToString(HistoryEntry.serializer(), entry))

        val rebuilt = restored.toBuildCombination()
        val rebuiltAmulet = rebuilt.equipments.single()
        val runes = rebuilt.runes[rebuiltAmulet].orEmpty()
        assertThat(runes).hasSize(2)
        assertThat(runes).allMatch { it.characteristic == Characteristic.MASTERY_DISTANCE }
        // Item-level-gated enchant level is preserved (derived from the carrier item's level 50 -> 2).
        assertThat(runes.first().maxLevel(rebuiltAmulet.level)).isEqualTo(2)
    }

    @Test
    fun `toHistoryEntry returns null without a build`() {
        assertThat(UiState().toHistoryEntry("id", "name", null, 0L, "v")).isNull()
    }

    @Test
    fun `damage scenario round-trips through the history entry`() {
        val scenario =
            DamageScenario(
                element = SpellElement.WATER,
                rangeBand = RangeBand.MELEE,
                orientation = Orientation.SIDE,
                berserk = true,
                healing = false,
                critCapPercent = 80,
                targetResistancePercent = 30,
                baseDamage = 250,
                // Boss-aware per-element resistances (incl. a weakness) must survive the round-trip — this
                // was silently dropped before, collapsing a saved boss-mode build back to single-element.
                elementResistances = mapOf(SpellElement.WATER to 30, SpellElement.FIRE to -50, SpellElement.EARTH to 50, SpellElement.AIR to 0)
            )
        val ui =
            UiState(
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                scenario = scenario,
                build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))
            )

        val entry = ui.toHistoryEntry(id = "id-dmg", name = "Dmg", note = null, createdAt = 1L, dataVersion = "v")!!

        assertThat(entry.request.scenario.element).isEqualTo("WATER")
        assertThat(entry.request.scenario.orientation).isEqualTo("SIDE")
        assertThat(entry.request.scenario.elementResistances).isEqualTo(mapOf("WATER" to 30, "FIRE" to -50, "EARTH" to 50, "AIR" to 0))
        assertThat(entry.restoredScenario()).isEqualTo(scenario)
    }

    @Test
    fun `restoredScenario falls back to the default for a pre-feature save`() {
        // An entry built straight from the DTO with the default scenario (as an old save would
        // deserialize) restores to the engine default without throwing.
        val ui = UiState(build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110)))
        val entry = ui.toHistoryEntry(id = "id-old", name = "Old", note = null, createdAt = 1L, dataVersion = "v")!!
        assertThat(entry.restoredScenario()).isEqualTo(DamageScenario())
    }

    @Test
    fun `normalizeTags trims, drops blanks, and dedupes case-insensitively keeping first casing`() {
        val normalized = normalizeTags(listOf("  PvP ", "pvp", "", "   ", "Solo", "PVP", "solo"))
        assertThat(normalized).containsExactly("PvP", "Solo")
    }
}
