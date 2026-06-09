package me.chosante.ui.state

import me.chosante.common.Characteristic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EngineMasteryScoreTest {
    private val requested =
        setOf(
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_ELEMENTARY_FIRE,
            Characteristic.MASTERY_ELEMENTARY_WATER
        )

    @Test
    fun `elements count by their minimum, not their sum`() {
        // Real case from the compare view. Naively summing fire+water made A (balanced-high elements)
        // look better; the engine sums specialized + MIN of elements, which ranks B higher — matching
        // what the solver actually optimized.
        val buildA =
            mapOf(
                Characteristic.MASTERY_DISTANCE to 2295,
                Characteristic.MASTERY_CRITICAL to 1372,
                Characteristic.MASTERY_ELEMENTARY_FIRE to 3484,
                Characteristic.MASTERY_ELEMENTARY_WATER to 3484
            )
        val buildB =
            mapOf(
                Characteristic.MASTERY_DISTANCE to 2668,
                Characteristic.MASTERY_CRITICAL to 2586,
                Characteristic.MASTERY_ELEMENTARY_FIRE to 2081,
                Characteristic.MASTERY_ELEMENTARY_WATER to 2081
            )

        val a = engineMasteryScore(buildA, requested)
        val b = engineMasteryScore(buildB, requested)

        assertThat(a).isEqualTo(2295 + 1372 + 3484) // 7151
        assertThat(b).isEqualTo(2668 + 2586 + 2081) // 7335
        assertThat(b).isGreaterThan(a) // the looser-constrained build IS better by the engine's objective
    }

    @Test
    fun `aggregate elemental target counts the weakest element once`() {
        val requestedAggregate = setOf(Characteristic.MASTERY_ELEMENTARY, Characteristic.MASTERY_DISTANCE)
        val achieved =
            mapOf(
                Characteristic.MASTERY_DISTANCE to 1000,
                Characteristic.MASTERY_ELEMENTARY_FIRE to 800,
                Characteristic.MASTERY_ELEMENTARY_WATER to 500,
                Characteristic.MASTERY_ELEMENTARY_EARTH to 900,
                Characteristic.MASTERY_ELEMENTARY_WIND to 700
            )
        // distance + min(fire, water, earth, wind) = 1000 + 500
        assertThat(engineMasteryScore(achieved, requestedAggregate)).isEqualTo(1500)
    }

    @Test
    fun `specific elements win over a co-requested all-elements`() {
        // Combined request: the headline minimises over the requested fire/earth only, not all four —
        // matching the engine fix (TargetStats.masteryElementsToMinimize) so "what you see" equals
        // "what the solver maximised". Before the fix this returned min over all four = 50.
        val requestedCombined =
            setOf(
                Characteristic.MASTERY_ELEMENTARY,
                Characteristic.MASTERY_ELEMENTARY_FIRE,
                Characteristic.MASTERY_ELEMENTARY_EARTH
            )
        val achieved =
            mapOf(
                Characteristic.MASTERY_ELEMENTARY_FIRE to 800,
                Characteristic.MASTERY_ELEMENTARY_EARTH to 900,
                Characteristic.MASTERY_ELEMENTARY_WATER to 100,
                Characteristic.MASTERY_ELEMENTARY_WIND to 50
            )

        assertThat(engineMasteryScore(achieved, requestedCombined)).isEqualTo(800)
    }

    @Test
    fun `specialized masteries are summed`() {
        val achieved = mapOf(Characteristic.MASTERY_DISTANCE to 1000, Characteristic.MASTERY_CRITICAL to 500)
        assertThat(engineMasteryScore(achieved, setOf(Characteristic.MASTERY_DISTANCE, Characteristic.MASTERY_CRITICAL))).isEqualTo(1500)
    }
}
