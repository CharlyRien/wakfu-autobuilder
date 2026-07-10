package me.chosante.autobuilder.domain

import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneColor
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BuildCombinationTest {
    @Test
    fun `a build with a single ring is valid`() {
        val build =
            BuildCombination(
                equipments = listOf(equipment("Gelano")),
                characterSkills = CharacterSkills(level = 110)
            )

        assertThat(build.isValid()).isTrue()
    }

    @Test
    fun `a build with the same ring twice is invalid`() {
        val build =
            BuildCombination(
                equipments = listOf(equipment("Gelano"), equipment("Gelano")),
                characterSkills = CharacterSkills(level = 110)
            )

        assertThat(build.isValid()).isFalse()
    }

    @Test
    fun `a normal sublimation coexists with a full rune set (golden runes fill its pattern)`() {
        // Model since 54761dc6 ("keeps the item's full rune set"): a normal sub's colour pattern is drawn
        // from golden runes that STILL carry their stat, so a 4-socket carrier keeps all four runes AND
        // hosts the sub. The retired "reserve 3 sockets" rule would have rejected this (4 + 3·1 > 4);
        // isValid() must not — see the solver's "does not steal rune sockets" test.
        val carrier = socketedAmulet(sockets = 4)
        val build =
            BuildCombination(
                equipments = listOf(carrier),
                characterSkills = CharacterSkills(level = 245),
                runes = mapOf(carrier to List(4) { distanceRune }),
                sublimations = mapOf(carrier to listOf(normalSub))
            )

        assertThat(build.isValid()).isTrue()
    }

    @Test
    fun `a sub carrier with more runes than sockets is invalid`() {
        // The one rune/socket invariant isValid() still enforces on a sub carrier: no more runes than the
        // item has sockets (5 runes on a 4-socket carrier). Rune capacity for non-sub carriers is the
        // solver model's job (createRuneModel), so isValid() only re-checks it where a sub is hosted.
        val carrier = socketedAmulet(sockets = 4)
        val build =
            BuildCombination(
                equipments = listOf(carrier),
                characterSkills = CharacterSkills(level = 245),
                runes = mapOf(carrier to List(5) { distanceRune }),
                sublimations = mapOf(carrier to listOf(normalSub))
            )

        assertThat(build.isValid()).isFalse()
    }

    @Test
    fun `10 normal subs plus 1 epic and 1 relic is valid (the cap is 10 NORMAL, not 10 total)`() {
        // The cap fix: the 10 applies to NORMAL subs; epic and relic get their own dedicated slots, so a full
        // 10 normal + 1 epic + 1 relic = 12 sublimations is legal. The old `allSubs.size > 10` wrongly rejected it.
        val normalCarriers =
            listOf(
                ItemType.AMULET,
                ItemType.HELMET,
                ItemType.CHEST_PLATE,
                ItemType.SHOULDER_PADS,
                ItemType.BOOTS,
                ItemType.BELT,
                ItemType.CAPE,
                ItemType.RING,
                ItemType.RING,
                ItemType.ONE_HANDED_WEAPONS
            ).mapIndexed { i, type -> carrier(100 + i, type, Rarity.LEGENDARY) }
        val epicItem = carrier(200, ItemType.EMBLEM, Rarity.EPIC, sockets = 0)
        val relicItem = carrier(201, ItemType.MOUNTS, Rarity.RELIC, sockets = 0)
        val build =
            BuildCombination(
                equipments = normalCarriers + epicItem + relicItem,
                characterSkills = CharacterSkills(level = 245),
                sublimations =
                    normalCarriers.associateWith { listOf(normalSub) } +
                        mapOf(epicItem to listOf(epicSub), relicItem to listOf(relicSub))
            )

        assertThat(build.isValid()).isTrue()
    }

    @Test
    fun `11 normal subs is invalid (the 10-normal cap still binds)`() {
        val carriers =
            listOf(
                ItemType.AMULET,
                ItemType.HELMET,
                ItemType.CHEST_PLATE,
                ItemType.SHOULDER_PADS,
                ItemType.BOOTS,
                ItemType.BELT,
                ItemType.CAPE,
                ItemType.RING,
                ItemType.RING,
                ItemType.ONE_HANDED_WEAPONS,
                ItemType.EMBLEM
            ).mapIndexed { i, type -> carrier(300 + i, type, Rarity.LEGENDARY) }
        val build =
            BuildCombination(
                equipments = carriers,
                characterSkills = CharacterSkills(level = 245),
                sublimations = carriers.associateWith { listOf(normalSub) }
            )

        assertThat(build.isValid()).isFalse()
    }

    private fun equipment(name: String): Equipment =
        Equipment(
            equipmentId = 1,
            level = 65,
            name = I18nText(fr = name, en = name, es = "", pt = ""),
            rarity = Rarity.RELIC,
            itemType = ItemType.RING,
            characteristics = emptyMap(),
            guiId = 1
        )

    private fun socketedAmulet(sockets: Int): Equipment =
        Equipment(
            equipmentId = 1,
            level = 245,
            name = I18nText(fr = "Amu", en = "Amu", es = "", pt = ""),
            rarity = Rarity.LEGENDARY,
            itemType = ItemType.AMULET,
            characteristics = emptyMap(),
            guiId = 1,
            maxShardSlots = sockets
        )

    private val distanceRune =
        RuneType(1, I18nText("d", "d", "d", "d"), RuneColor.RED, Characteristic.MASTERY_DISTANCE, listOf(10, 15), 0)

    private val normalSub =
        Sublimation(
            stateId = 10,
            name = I18nText("DistNormal", "DistNormal", "", ""),
            rarity = SublimationRarity.NORMAL,
            slotColorPattern = listOf(1, 2, 3),
            kind = SublimationKind.FLAT
        )

    private val epicSub =
        Sublimation(stateId = 20, name = I18nText("Epic", "Epic", "", ""), rarity = SublimationRarity.EPIC, kind = SublimationKind.FLAT)

    private val relicSub =
        Sublimation(stateId = 21, name = I18nText("Relic", "Relic", "", ""), rarity = SublimationRarity.RELIC, kind = SublimationKind.FLAT)

    private fun carrier(
        id: Int,
        type: ItemType,
        rarity: Rarity,
        sockets: Int = 4,
    ): Equipment =
        Equipment(
            equipmentId = id,
            level = 245,
            name = I18nText(fr = "c$id", en = "c$id", es = "", pt = ""),
            rarity = rarity,
            itemType = type,
            characteristics = emptyMap(),
            guiId = id,
            maxShardSlots = sockets
        )
}
