package me.chosante

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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the SHAPE of the `/shard/add` calls the Zenith export emits, without touching the network.
 * [plannedShards] and [sideAssignments] are the two pure halves of [createZenithBuild]'s per-item work.
 */
class ZenithShardPlanTest {
    private fun equipment(
        id: Int,
        type: ItemType,
        label: String,
        level: Int = 200,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = level,
        name = I18nText(fr = label, en = label, es = "", pt = ""),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = emptyMap(),
        maxShardSlots = 4
    )

    private fun sublimation(
        stateId: Int,
        zenithId: Int,
        label: String,
    ) = Sublimation(
        stateId = stateId,
        zenithId = zenithId,
        name = I18nText(label, label, "", ""),
        rarity = SublimationRarity.NORMAL,
        maxStackLevel = 6,
        maxTier = 3,
        cumulable = true,
        kind = SublimationKind.FLAT
    )

    private fun rune(id: Int) =
        RuneType(
            id = id,
            name = I18nText("R$id", "R$id", "", ""),
            color = RuneColor.RED,
            characteristic = Characteristic.MASTERY_ELEMENTARY_FIRE,
            doubleBonusPosition = emptyList(),
            gfxId = id
        )

    /**
     * The stacking export contract: a cumulable sub socketed on TWO carriers yields one `/shard/add` per
     * carrier — same `id_shard`, different `side`. Nothing dedupes by shard id, which is what lets Zenith
     * mirror the in-game "one copy per ≥3-socket item".
     */
    @Test
    fun `a stacked cumulable sublimation emits one shard call per carrier with the same shard id`() {
        val amulet = equipment(1, ItemType.AMULET, "Carrier1")
        val cape = equipment(2, ItemType.CAPE, "Carrier2")
        val carnage = sublimation(stateId = 99, zenithId = 5150, label = "Carnage II")

        val sides = sideAssignments(listOf(amulet, cape)).toMap()
        val onAmulet = plannedShards(amulet, sides.getValue(amulet), runes = emptyList(), subs = listOf(carnage))
        val onCape = plannedShards(cape, sides.getValue(cape), runes = emptyList(), subs = listOf(carnage))

        val calls = onAmulet + onCape
        assertThat(calls.map { it.shardId })
            .describedAs("both copies socket the SAME sublimation shard id")
            .containsExactly(5150, 5150)
        assertThat(calls.map { it.side })
            .describedAs("each copy targets its OWN carrier item (distinct sides)")
            .containsExactly(ItemType.AMULET.id, ItemType.CAPE.id)
        assertThat(calls.map { it.level })
            .describedAs("sublimations socket like runes but carry no level")
            .containsExactly(null, null)
    }

    /** A sublimation's socket position starts after the carrier's runes, so the two never collide. */
    @Test
    fun `sublimation positions follow the item's runes without colliding`() {
        val belt = equipment(3, ItemType.BELT, "Belt")
        val shards =
            plannedShards(
                belt,
                side = ItemType.BELT.id,
                runes = listOf(rune(10), rune(11)),
                subs = listOf(sublimation(1, 7001, "SubA"))
            )

        assertThat(shards.map { it.position })
            .describedAs("runes take positions 0..n-1, the sublimation takes n")
            .containsExactly(0, 1, 2)
        assertThat(shards.map { it.shardId }).containsExactly(10, 11, 7001)
        assertThat(shards.first().level)
            .describedAs("a rune is socketed at the max level its CARRIER's item level allows")
            .isEqualTo(rune(10).maxLevel(belt.level))
        assertThat(shards.last().level).describedAs("the sublimation has no level").isNull()
    }

    /**
     * The two rings must claim the two dedicated ring sides. The ring-side iterator used to be advanced from
     * inside the per-item `async`, so the rings raced for it; [sideAssignments] now resolves every side up
     * front, sequentially, in list order.
     */
    @Test
    fun `the two rings claim the two distinct dedicated ring sides`() {
        val assignments =
            sideAssignments(
                listOf(
                    equipment(4, ItemType.RING, "RingA"),
                    equipment(5, ItemType.AMULET, "Amulet"),
                    equipment(6, ItemType.RING, "RingB"),
                    equipment(7, ItemType.ONE_HANDED_WEAPONS, "Sword"),
                    equipment(8, ItemType.OFF_HAND_WEAPONS, "Dagger")
                )
            )

        val byLabel = assignments.associate { (equip, side) -> equip.name.en to side }
        assertThat(listOf(byLabel.getValue("RingA"), byLabel.getValue("RingB")))
            .describedAs("the two rings never share a side")
            .containsExactly(23, 24)
        assertThat(byLabel.getValue("Sword")).describedAs("weapons use the fixed weapon side").isEqualTo(540)
        assertThat(byLabel.getValue("Dagger")).describedAs("the off-hand has its own side").isEqualTo(520)
        assertThat(byLabel.getValue("Amulet")).isEqualTo(ItemType.AMULET.id)
    }
}
