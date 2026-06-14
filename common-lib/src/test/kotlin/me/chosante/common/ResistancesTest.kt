package me.chosante.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResistancesTest {
    @Test
    fun `zero flat is zero percent and the cap holds`() {
        assertEquals(0, Resistances.flatToPercent(0))
        assertTrue(Resistances.flatToPercent(100_000) <= Resistances.MAX_PERCENT)
    }

    @Test
    fun `negative flat is a weakness — negative percent`() {
        assertTrue(Resistances.flatToPercent(-100) < 0)
    }

    @Test
    fun `percent to flat round-trips back to within one point`() {
        for (percent in listOf(0, 20, 40, 60, 80)) {
            val flat = Resistances.percentToFlat(percent)
            // flatToPercent floors, so the round-trip can lose one point — that's the only tolerance.
            assertTrue(kotlin.math.abs(percent - Resistances.flatToPercent(flat)) <= 1, "round-trip for $percent%")
        }
    }

    @Test
    fun `removing flat resistance always lowers the effective percent (raises damage)`() {
        // The core invariant the sequencing relies on: a flat debuff strictly reduces effective
        // resistance at every level, so opening with it never hurts subsequent hits.
        for (flat in listOf(0, 100, 200, 400, 600)) {
            assertTrue(
                Resistances.flatToPercent(flat - 100) < Resistances.flatToPercent(flat),
                "removing 100 flat from $flat should reduce %"
            )
        }
    }
}
