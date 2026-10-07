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
import me.chosante.common.Monster
import me.chosante.common.Passive
import me.chosante.common.Rarity
import me.chosante.common.RuneColor
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.history.BossSnapshot
import me.chosante.common.history.HistoryEntry
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Tr
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
                excludedRarities = setOf(Rarity.MYTHIC, Rarity.SOUVENIR),
                forcedPassives = listOf("Carnage"),
                forcedRunesByItem = mapOf("Cape" to listOf(27100, 27100, 27094)),
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
        // The full engine-affecting request state round-trips through the JSON codec — a save missing any
        // of these cannot reproduce the search (two identical-looking requests can differ in proven optimum).
        val rehydrated = historyJson.decodeFromString<HistoryEntry>(historyJson.encodeToString(HistoryEntry.serializer(), entry))
        assertThat(rehydrated.request.excludedRarities).containsExactlyInAnyOrder(Rarity.MYTHIC, Rarity.SOUVENIR)
        assertThat(rehydrated.request.forcedPassives).containsExactly("Carnage")
        assertThat(rehydrated.request.forcedRunesByItem).isEqualTo(mapOf("Cape" to listOf(27100, 27100, 27094)))
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
    fun `a saved build keeps the Dofus Pourpre's level-scaled Elemental Mastery, counted once`() {
        // A search's build carries the pool's copy of the item, resolved at the character's level (Equipment.atLevel).
        val pourpre =
            Equipment(
                equipmentId = 33395,
                guiId = 53133395,
                level = 170,
                name = I18nText(fr = "Dofus Pourpre", en = "Crimson Dofus", es = "", pt = ""),
                rarity = Rarity.RELIC,
                itemType = ItemType.EMBLEM,
                characteristics = mapOf(Characteristic.ACTION_POINT to 1, Characteristic.CRITICAL_HIT to 3),
                percentOfLevel = mapOf(Characteristic.MASTERY_ELEMENTARY to 100)
            ).atLevel(245)
        val ui = UiState(level = 245, build = BuildCombination(equipments = listOf(pourpre), characterSkills = CharacterSkills(245)))

        val entry = ui.toHistoryEntry(id = "id-3", name = "Pourpre", note = null, createdAt = 1L, dataVersion = "v")!!
        // Through the library / clipboard codec (it writes defaults: the copy's emptied line too).
        val restored = historyJson.decodeFromString(HistoryEntry.serializer(), historyJson.encodeToString(HistoryEntry.serializer(), entry))

        val reloaded = restored.toBuildCombination().equipments.single()
        assertThat(reloaded).isEqualTo(pourpre)
        assertThat(reloaded.characteristics).containsEntry(Characteristic.MASTERY_ELEMENTARY, 245)
        // Compare and the library read this copy as is: resolving it again (at any level) adds nothing.
        assertThat(reloaded.atLevel(245)).isEqualTo(pourpre)
        assertThat(reloaded.atLevel(200)).isEqualTo(pourpre)
    }

    @Test
    fun `sublimations and passives survive an export-import round-trip`() {
        val amulet =
            Equipment(
                equipmentId = 7,
                guiId = 7,
                level = 50,
                name = I18nText(fr = "Amulette", en = "Amulet", es = "", pt = ""),
                rarity = Rarity.EPIC,
                itemType = ItemType.AMULET,
                characteristics = emptyMap(),
                maxShardSlots = 3
            )
        val sub =
            Sublimation(
                stateId = 42,
                name = I18nText("Sub FR", "Sub EN", "", ""),
                rarity = SublimationRarity.EPIC,
                kind = SublimationKind.FLAT,
                rawText = "+50 fire mastery"
            )
        val passive = Passive(spellId = 6989, name = I18nText("Ligne", "Ligne", "Ligne", "Ligne"), clazz = "FECA", gfxId = 6989, flatBuildStats = mapOf("RANGE" to 1.0))
        val build =
            BuildCombination(
                equipments = listOf(amulet),
                characterSkills = CharacterSkills(110),
                sublimations = mapOf(amulet to listOf(sub)),
                passives = listOf(passive)
            )
        val ui =
            UiState(
                clazz = CharacterClass.FECA,
                level = 110,
                minLevel = 110,
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                maxRarity = Rarity.EPIC,
                duration = "30",
                stopAtMatch = false,
                build = build,
                achieved = emptyMap(),
                match = BigDecimal("100"),
                optimal = true
            )

        val entry = ui.toHistoryEntry(id = "id-sp", name = "Subs+Passives", note = null, createdAt = 1L, dataVersion = "v")!!
        // Through the exact JSON codec the clipboard export/import uses.
        val restored = historyJson.decodeFromString(HistoryEntry.serializer(), historyJson.encodeToString(HistoryEntry.serializer(), entry))
        val rebuilt = restored.toBuildCombination()

        val rebuiltAmulet = rebuilt.equipments.single()
        assertThat(rebuilt.sublimations[rebuiltAmulet].orEmpty().map { it.stateId })
            .describedAs("the sublimation reattaches to its carrier item")
            .containsExactly(42)
        assertThat(rebuilt.passives.mapNotNull { it.name?.fr })
            .describedAs("the passive loadout survives the round-trip")
            .containsExactly("Ligne")
        assertThat(rebuilt.passives.single().flatStats).containsEntry(Characteristic.RANGE, 1)
    }

    /**
     * Stacking round-trip: a CUMULABLE sub socketed on two carriers must come back as two copies. The saved
     * shape keys sublimations by `equipmentId`, so the copies survive precisely because they sit on DISTINCT
     * carrier items — a shape that collapsed them to a set/`distinctBy` would silently drop half a build's
     * damage. Also pins that `cumulable` + `maxStackLevel` survive the codec (the latter is serialized under
     * its legacy `maxLevel` key), so [Sublimation.maxCopies] still reads 2 after a clipboard round-trip.
     */
    @Test
    fun `a stacked cumulable sublimation survives the round-trip on both carriers`() {
        fun carrier(
            id: Int,
            type: ItemType,
            label: String,
        ) = Equipment(
            equipmentId = id,
            guiId = id,
            level = 50,
            name = I18nText(fr = label, en = label, es = "", pt = ""),
            rarity = Rarity.LEGENDARY,
            itemType = type,
            characteristics = emptyMap(),
            maxShardSlots = 3
        )
        val first = carrier(11, ItemType.AMULET, "Carrier1")
        val second = carrier(12, ItemType.CAPE, "Carrier2")
        // maxStackLevel 6 / maxTier 3 ⇒ maxCopies 2: one copy socketed on each carrier.
        val stacked =
            Sublimation(
                stateId = 99,
                name = I18nText("Carnage II", "Carnage II", "", ""),
                rarity = SublimationRarity.NORMAL,
                maxStackLevel = 6,
                maxTier = 3,
                cumulable = true,
                kind = SublimationKind.FLAT
            )
        val build =
            BuildCombination(
                equipments = listOf(first, second),
                characterSkills = CharacterSkills(110),
                sublimations = mapOf(first to listOf(stacked), second to listOf(stacked))
            )
        val ui =
            UiState(
                clazz = CharacterClass.FECA,
                level = 110,
                minLevel = 110,
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                maxRarity = Rarity.EPIC,
                duration = "30",
                stopAtMatch = false,
                build = build,
                achieved = emptyMap(),
                match = BigDecimal("100"),
                optimal = true
            )

        val entry = ui.toHistoryEntry(id = "id-stack", name = "Stacked", note = null, createdAt = 1L, dataVersion = "v")!!
        val restored = historyJson.decodeFromString(HistoryEntry.serializer(), historyJson.encodeToString(HistoryEntry.serializer(), entry))
        val rebuilt = restored.toBuildCombination()

        assertThat(
            rebuilt.sublimations.values
                .flatten()
                .count { it.stateId == 99 }
        ).describedAs("both socketed copies of the cumulable sub survive the round-trip")
            .isEqualTo(2)
        assertThat(rebuilt.sublimations.filterValues { subs -> subs.any { it.stateId == 99 } })
            .describedAs("the two copies stay keyed to two DISTINCT carrier items")
            .hasSize(2)
        assertThat(
            rebuilt.sublimations.values
                .flatten()
                .first { it.stateId == 99 }
                .maxCopies
        ).describedAs("cumulable + maxStackLevel survive the codec, so maxCopies still reads 2")
            .isEqualTo(2)
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
    fun `a pre-feature save without the request-state keys loads with neutral defaults`() {
        // A save exported before excludedRarities/forcedPassives/forcedRunesByItem existed (e.g. GUI 1.8.0)
        // must still load — with the neutral defaults, not an error.
        val legacyJson =
            """
            {
                "id": "id-old",
                "name": "Old",
                "createdAt": 1,
                "dataVersion": "1.92.1.58",
                "request": {
                    "clazz": "XELOR",
                    "level": 170,
                    "minLevel": 155,
                    "mode": "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT",
                    "maxRarity": "EPIC",
                    "duration": "120",
                    "stopAtMatch": false,
                    "targets": [],
                    "forcedItems": [],
                    "excludedItems": []
                },
                "result": {
                    "equipments": [],
                    "skills": {},
                    "achieved": {},
                    "match": 1516.0,
                    "optimal": true
                }
            }
            """.trimIndent()
        val loaded = historyJson.decodeFromString<HistoryEntry>(legacyJson)
        assertThat(loaded.request.excludedRarities).isEmpty()
        assertThat(loaded.request.forcedPassives).isEmpty()
        assertThat(loaded.request.forcedRunesByItem).isEmpty()
    }

    private val dungeonBoss =
        Monster(
            id = 4242,
            name = I18nText("Magik Riktus Dominant", "Dominant Magik Riktus", "Magik Riktus Dominante", "Magik Riktus Dominante"),
            level = 105,
            hp = 12_345,
            fireResistance = 10,
            waterResistance = -20,
            earthResistance = 30,
            airResistance = 0
        )

    private fun damageUi(
        mode: ScoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        boss: Monster? = dungeonBoss,
    ) = UiState(
        mode = mode,
        selectedBoss = boss,
        bossElement = SpellElement.WATER,
        bossDifficulty = "3",
        match = BigDecimal("10528.8028"),
        build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))
    )

    @Test
    fun `a max-damage build records the boss it was searched against`() {
        val entry = damageUi().toHistoryEntry(id = "id-boss", name = "Boss", note = null, createdAt = 1L, dataVersion = "v")!!

        assertThat(entry.request.boss).isEqualTo(BossSnapshot(monster = dungeonBoss, element = "WATER", difficulty = "3"))
        assertThat(entry.restoredBoss()).isEqualTo(dungeonBoss)
        assertThat(entry.restoredBossElement()).isEqualTo(SpellElement.WATER)
        assertThat(entry.restoredBossDifficulty()).isEqualTo("3")
    }

    @Test
    fun `the boss survives the clipboard export-import round-trip`() {
        val entry = damageUi().toHistoryEntry(id = "id-boss", name = "Boss", note = null, createdAt = 1L, dataVersion = "v")!!

        val reloaded = historyJson.decodeFromString<HistoryEntry>(historyJson.encodeToString(HistoryEntry.serializer(), entry))

        assertThat(reloaded.restoredBoss()).isEqualTo(dungeonBoss)
        assertThat(reloaded).isEqualTo(entry)
    }

    @Test
    fun `a boss left selected in another mode is not recorded, and no boss means none`() {
        val masteries = damageUi(mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)
        assertThat(masteries.toHistoryEntry("a", "A", null, 1L, "v")!!.request.boss).isNull()

        val manual = damageUi(boss = null)
        val entry = manual.toHistoryEntry("b", "B", null, 1L, "v")!!
        assertThat(entry.request.boss).isNull()
        assertThat(entry.restoredBoss()).isNull()
        assertThat(entry.restoredBossElement()).isNull()
        assertThat(entry.restoredBossDifficulty()).isEqualTo("1")
    }

    @Test
    fun `a save written before the boss was recorded loads without one`() {
        val legacyJson =
            """
            {
                "id": "id-old", "name": "Old", "createdAt": 1, "dataVersion": "1.92.1.58",
                "request": {
                    "clazz": "CRA", "level": 110, "minLevel": 0, "mode": "FIND_BUILD_WITH_MAX_DAMAGE",
                    "maxRarity": "EPIC", "duration": "120", "stopAtMatch": false,
                    "targets": [], "forcedItems": [], "excludedItems": []
                },
                "result": { "equipments": [], "skills": {}, "achieved": {}, "match": 9876.5, "optimal": false }
            }
            """.trimIndent()

        val loaded = historyJson.decodeFromString<HistoryEntry>(legacyJson)

        assertThat(loaded.request.boss).isNull()
        assertThat(loaded.restoredBoss()).isNull()
        assertThat(loaded.isDamageMode()).isTrue()
    }

    @Test
    fun `each mode has its own label and a damage build's match is its expected damage`() {
        fun entryIn(mode: ScoreComputationMode) = damageUi(mode = mode, boss = null).toHistoryEntry("id", "n", null, 1L, "v")!!

        val masteries = entryIn(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)
        val precision = entryIn(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT)
        val damage = entryIn(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)

        assertThat(listOf(masteries, precision, damage).map { it.modeLabel() }).containsExactly(Tr.MODE_MASTERIES, Tr.MODE_PRECISION, Tr.MODE_MAX_DAMAGE)
        assertThat(listOf(masteries, precision, damage).map { it.isDamageMode() }).containsExactly(false, false, true)
        assertThat(damage.expectedDamage()).isEqualTo(10_528L)
    }

    @Test
    fun `normalizeTags trims, drops blanks, and dedupes case-insensitively keeping first casing`() {
        val normalized = normalizeTags(listOf("  PvP ", "pvp", "", "   ", "Solo", "PVP", "solo"))
        assertThat(normalized).containsExactly("PvP", "Solo")
    }
}
