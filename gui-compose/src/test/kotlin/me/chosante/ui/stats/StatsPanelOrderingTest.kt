package me.chosante.ui.stats

import me.chosante.common.Characteristic
import me.chosante.ui.state.TargetRow
import me.chosante.ui.state.statDefFor
import me.chosante.ui.state.toRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The "Desired vs Achieved" panel must show targets in a stable, canonical order — independent of the
 * order the user added them — so removing and re-adding a constraint never shuffles it to the bottom
 * of its group (which is how the model re-appends a re-added target).
 */
class StatsPanelOrderingTest {
    private fun rowsFor(vararg characteristics: Characteristic): List<TargetRow> = characteristics.map { statDefFor(it)!!.toRow("1") }

    private fun flattenedOrder(targets: List<TargetRow>): List<Characteristic> = groupedTargets(targets).flatMap { group -> group.targets.map { it.characteristic } }

    @Test
    fun `order is canonical and independent of insertion order`() {
        // Spans several groups; deliberately scrambled on input.
        val scrambled =
            rowsFor(
                Characteristic.HP,
                Characteristic.MASTERY_CRITICAL,
                Characteristic.ACTION_POINT,
                Characteristic.MASTERY_MELEE
            )
        val differentlyScrambled =
            rowsFor(
                Characteristic.MASTERY_MELEE,
                Characteristic.ACTION_POINT,
                Characteristic.MASTERY_CRITICAL,
                Characteristic.HP
            )

        val expected =
            listOf(
                // Core group, catalog order.
                Characteristic.ACTION_POINT,
                Characteristic.HP,
                // Specialized masteries, catalog order: melee before critical (mirrors the request panel).
                Characteristic.MASTERY_MELEE,
                Characteristic.MASTERY_CRITICAL
            )
        assertEquals(expected, flattenedOrder(scrambled))
        assertEquals(expected, flattenedOrder(differentlyScrambled))
    }

    @Test
    fun `re-adding a constraint keeps its original position`() {
        val initial = rowsFor(Characteristic.ACTION_POINT, Characteristic.MOVEMENT_POINT, Characteristic.RANGE)
        // What the model produces after the user removes AP and adds it back: it is re-appended last.
        val afterRemoveAndReadd = rowsFor(Characteristic.MOVEMENT_POINT, Characteristic.RANGE, Characteristic.ACTION_POINT)

        assertEquals(flattenedOrder(initial), flattenedOrder(afterRemoveAndReadd))
        assertEquals(
            listOf(Characteristic.ACTION_POINT, Characteristic.MOVEMENT_POINT, Characteristic.RANGE),
            flattenedOrder(afterRemoveAndReadd)
        )
    }
}
