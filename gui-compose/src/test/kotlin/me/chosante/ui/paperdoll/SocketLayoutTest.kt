package me.chosante.ui.paperdoll

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
 * [socketLayout] must surface EVERY socket the item has — the bug was it stopped at max(pattern, runes), so a
 * 4-socket item with a 3-colour sublimation pattern + 1 rune only drew 3 sockets (the 4th free one vanished
 * from both the card and the tooltip).
 */
class SocketLayoutTest {
    private fun amulet(slots: Int) =
        Equipment(
            equipmentId = 1,
            guiId = 1,
            level = 215,
            name = I18nText("Amu", "Amu", "Amu", "Amu"),
            rarity = Rarity.EPIC,
            itemType = ItemType.AMULET,
            characteristics = emptyMap(),
            maxShardSlots = slots
        )

    private val critRune =
        RuneType(1, I18nText("crit", "crit", "crit", "crit"), RuneColor.GREEN, Characteristic.MASTERY_CRITICAL, listOf(4), 0)

    // A NORMAL sublimation with a green/blue/green 3-socket pattern (codes 2,3,2).
    private val influence =
        Sublimation(
            stateId = 10,
            name = I18nText("Influence II", "Influence II", "Influence II", "Influence II"),
            rarity = SublimationRarity.NORMAL,
            slotColorPattern = listOf(2, 3, 2),
            kind = SublimationKind.FLAT
        )

    @Test
    fun `draws all four sockets including the free one beyond the pattern and runes`() {
        val layout = socketLayout(amulet(slots = 4), runes = listOf(critRune), subs = listOf(influence))
        assertThat(layout).describedAs("one cell per socket — the 4th (free) socket used to be dropped").hasSize(4)
        // Pattern paints the first three sockets green/blue/green.
        assertThat(layout.take(3).map { it.first }).containsExactly(RuneColor.GREEN, RuneColor.BLUE, RuneColor.GREEN)
        // The 4th socket is free: empty (null colour, no rune).
        assertThat(layout[3].first).describedAs("free socket has no colour").isNull()
        assertThat(layout[3].second).describedAs("free socket has no rune").isNull()
        // Exactly the one rune is placed (in the first socket).
        assertThat(layout.count { it.second != null }).isEqualTo(1)
    }

    @Test
    fun `an item with sockets but no runes or subs still shows all its sockets as empty`() {
        val layout = socketLayout(amulet(slots = 3), runes = emptyList(), subs = emptyList())
        assertThat(layout).hasSize(3)
        assertThat(layout.all { it.first == null && it.second == null }).isTrue()
    }

    @Test
    fun `a socketless item shows no sockets`() {
        assertThat(socketLayout(amulet(slots = 0), runes = emptyList(), subs = emptyList())).isEmpty()
    }
}
