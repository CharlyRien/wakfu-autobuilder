package me.chosante.ui.state

import me.chosante.autobuilder.domain.TargetStat
import me.chosante.common.Characteristic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The GUI splits a single "all resistances" target into four per-element ones before handing the
 * request to the engine, so the solver gets four graceful constraints instead of one brittle
 * min-over-four (see [expandGlobalResistance]).
 */
class ResistanceExpansionTest {
    private val elementResistances =
        setOf(
            Characteristic.RESISTANCE_ELEMENTARY_WATER,
            Characteristic.RESISTANCE_ELEMENTARY_FIRE,
            Characteristic.RESISTANCE_ELEMENTARY_EARTH,
            Characteristic.RESISTANCE_ELEMENTARY_WIND
        )

    @Test
    fun `a global resistance becomes four per-element targets`() {
        val expanded = expandGlobalResistance(listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 400)))

        assertThat(expanded.map { it.characteristic }).containsExactlyInAnyOrderElementsOf(elementResistances)
        assertThat(expanded.map { it.target }.toSet()).containsExactly(400)
        assertThat(expanded.none { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY }).isTrue()
    }

    @Test
    fun `an explicit per-element resistance keeps its own value`() {
        val expanded =
            expandGlobalResistance(
                listOf(
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 400),
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 600)
                )
            )

        val byChar = expanded.associate { it.characteristic to it.target }
        assertThat(byChar[Characteristic.RESISTANCE_ELEMENTARY_FIRE]).isEqualTo(600)
        assertThat(byChar[Characteristic.RESISTANCE_ELEMENTARY_WATER]).isEqualTo(400)
        assertThat(byChar[Characteristic.RESISTANCE_ELEMENTARY_EARTH]).isEqualTo(400)
        assertThat(byChar[Characteristic.RESISTANCE_ELEMENTARY_WIND]).isEqualTo(400)
        assertThat(byChar).doesNotContainKey(Characteristic.RESISTANCE_ELEMENTARY)
    }

    @Test
    fun `a zero-valued element placeholder is overridden by the global, not preserved`() {
        // The default request carries RESISTANCE_ELEMENTARY_WIND=0; "all resistances=400" must still
        // push wind to 400 (a 0 target is inert), and never leave two wind targets behind.
        val expanded =
            expandGlobalResistance(
                listOf(
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WIND, 0),
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 400)
                )
            )

        assertThat(expanded.count { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY_WIND }).isEqualTo(1)
        assertThat(expanded.single { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY_WIND }.target).isEqualTo(400)
        assertThat(expanded.map { it.target }.toSet()).containsExactly(400)
    }

    @Test
    fun `targets without a global resistance are left unchanged`() {
        val input =
            listOf(
                TargetStat(Characteristic.ACTION_POINT, 12),
                TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 300)
            )

        assertThat(expandGlobalResistance(input)).isEqualTo(input)
    }
}
