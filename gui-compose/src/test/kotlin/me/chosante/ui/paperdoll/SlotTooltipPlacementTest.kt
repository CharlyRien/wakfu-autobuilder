package me.chosante.ui.paperdoll

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Geometry guard-rail for the equipment tooltip placement. The flicker reported on Windows came from
 * the stock cursor-anchored popup being clamped *over* the pointer; [slotTooltipOffset] must keep the
 * card off the slot it describes (and thus off the pointer that sits inside that slot), placing it
 * below when there is room and flipping above otherwise.
 */
class SlotTooltipPlacementTest {
    private val gapPx = 6
    private val marginPx = 8
    private val window = IntSize(1280, 800)
    private val popup = IntSize(280, 320)

    @Test
    fun `upper slot - card sits below the slot`() {
        val slot = IntRect(left = 40, top = 100, right = 400, bottom = 184)

        val offset = slotTooltipOffset(slot, window, popup, gapPx, marginPx)

        assertThat(offset.y).isEqualTo(slot.bottom + gapPx)
        assertThat(offset.x).isEqualTo(slot.left)
        assertSlotNeverCovered(slot, popup, offset)
    }

    @Test
    fun `bottom slot with no room below - card flips above the slot`() {
        val slot = IntRect(left = 40, top = 680, right = 400, bottom = 764)

        val offset = slotTooltipOffset(slot, window, popup, gapPx, marginPx)

        // Card bottom edge lands on/above the slot top, so the pointer is never under it.
        assertThat(offset.y + popup.height).isEqualTo(slot.top - gapPx)
        assertSlotNeverCovered(slot, popup, offset)
    }

    @Test
    fun `slot near the right edge - card is clamped inside the window`() {
        val slot = IntRect(left = 1100, top = 100, right = 1260, bottom = 184)

        val offset = slotTooltipOffset(slot, window, popup, gapPx, marginPx)

        assertThat(offset.x + popup.width).isLessThanOrEqualTo(window.width - marginPx)
        assertThat(offset.x).isGreaterThanOrEqualTo(marginPx)
    }

    @Test
    fun `card never vertically overlaps the slot, across the whole column and tooltip sizes`() {
        // The exact flicker trigger is a tall card with the pointer low in the window: sweep every
        // slot position and both a short and a tall card; the no-overlap invariant must always hold.
        for (top in 0..720 step 20) {
            val slot = IntRect(left = 40, top = top, right = 400, bottom = top + 84)
            for (height in intArrayOf(70, 320, 620)) {
                val card = IntSize(280, height)
                val offset = slotTooltipOffset(slot, window, card, gapPx, marginPx)
                assertSlotNeverCovered(slot, card, offset)
            }
        }
    }

    /** The card must be entirely below the slot's bottom or entirely above its top — never across it. */
    private fun assertSlotNeverCovered(
        slot: IntRect,
        card: IntSize,
        offset: IntOffset,
    ) {
        val below = offset.y >= slot.bottom
        val above = offset.y + card.height <= slot.top
        assertThat(below || above)
            .withFailMessage(
                "card top=%d height=%d overlaps slot [%d, %d]",
                offset.y,
                card.height,
                slot.top,
                slot.bottom
            ).isTrue()
    }
}
