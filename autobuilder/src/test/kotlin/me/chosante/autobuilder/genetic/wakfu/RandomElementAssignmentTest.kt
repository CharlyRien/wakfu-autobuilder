package me.chosante.autobuilder.genetic.wakfu

import me.chosante.common.Characteristic
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_EARTH
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_FIRE
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WATER
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WIND
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Locks the optimality claims the multi-element redesign depends on, by brute force against exhaustive search: the
 * exact assignments reach the TRUE optimum that no greedy does (the old deficit-greedy AND a naive water-fill are
 * both beatable for atomic k-of-m rolls) — which is exactly why the CP-SAT model is freed to find it and the scorer
 * mirrors it. [assignMaxMinMasteryRandomValues] for the most-masteries MIN objective; [assignMaxCappedMasteryRandomValues]
 * for precision's CAPPED-sum objective.
 */
class RandomElementAssignmentTest {
    private val elements = listOf(MASTERY_ELEMENTARY_FIRE, MASTERY_ELEMENTARY_WATER, MASTERY_ELEMENTARY_EARTH, MASTERY_ELEMENTARY_WIND)
    private val oneTwoThree = listOf(MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT, MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT, MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT)

    private data class Roll(
        val value: Int,
        val count: Int,
    )

    /** All k-element subsets of [items]. */
    private fun <T> combinations(
        items: List<T>,
        k: Int,
    ): List<List<T>> {
        if (k == 0) return listOf(emptyList())
        if (k > items.size) return emptyList()
        val head = items.first()
        val tail = items.drop(1)
        return combinations(tail, k - 1).map { listOf(head) + it } + combinations(tail, k)
    }

    /** Exhaustive best objective over EVERY way to place each roll on min(count, m) distinct wanted elements. */
    private fun exhaustiveBest(
        base: Map<Characteristic, Int>,
        wanted: List<Characteristic>,
        rolls: List<Roll>,
        objective: (Map<Characteristic, Int>) -> Int,
    ): Int {
        fun rec(
            idx: Int,
            current: Map<Characteristic, Int>,
        ): Int {
            if (idx == rolls.size) return objective(current)
            val (value, count) = rolls[idx]
            val eff = minOf(count, wanted.size)
            if (eff == 0 || value == 0) return rec(idx + 1, current)
            var best = Int.MIN_VALUE
            for (combo in combinations(wanted, eff)) {
                val next = current.toMutableMap()
                combo.forEach { next[it] = next.getValue(it) + value }
                best = maxOf(best, rec(idx + 1, next))
            }
            return best
        }
        return rec(0, base)
    }

    private fun rollsToRandomMap(rolls: List<Roll>): Map<Characteristic, List<Int>> =
        rolls
            .groupBy { it.count }
            .mapValues { (_, rs) -> rs.map { it.value } }
            .mapKeys { (count, _) -> oneTwoThree[count - 1] }

    @Test
    fun `exact max-min assignment attains the optimal min for atomic random rolls`() {
        val random = Random(20260622)
        repeat(4000) {
            val m = 2 + random.nextInt(3) // 2..4 wanted elements
            val wanted = elements.take(m)
            val base = wanted.associateWith { random.nextInt(0, 25) }
            val rolls = List(1 + random.nextInt(5)) { Roll(value = 1 + random.nextInt(10), count = 1 + random.nextInt(3)) }
            // A random non-empty subset to maximize the min over (covers the strict-subset edge case).
            val subset = wanted.filter { random.nextBoolean() }.ifEmpty { listOf(wanted.first()) }

            val assigned =
                assignMaxMinMasteryRandomValues(
                    rollsToRandomMap(rolls),
                    base,
                    wanted.associateWith { 9999 },
                    subset
                )
            val waterFillMin = subset.minOf { assigned.getValue(it) }
            val optimalMin = exhaustiveBest(base, wanted, rolls) { state -> subset.minOf { state.getValue(it) } }

            assertThat(waterFillMin)
                .describedAs("exact max-min must reach the optimal min; base=%s rolls=%s subset=%s", base, rolls, subset)
                .isEqualTo(optimalMin)
        }
    }

    @Test
    fun `exact capped assignment attains the optimal capped-sum for precision`() {
        val random = Random(13572468)
        repeat(4000) {
            val m = 2 + random.nextInt(3)
            val wanted = elements.take(m)
            // Small targets + ample roll mass ⇒ overshoot, where the capped-sum optimum is non-trivial.
            val targets = wanted.associateWith { 3 + random.nextInt(15) }
            val base = wanted.associateWith { random.nextInt(0, 10) }
            val rolls = List(1 + random.nextInt(5)) { Roll(value = 1 + random.nextInt(8), count = 1 + random.nextInt(3)) }

            val assigned = assignMaxCappedMasteryRandomValues(rollsToRandomMap(rolls), base, targets)

            fun cappedSum(state: Map<Characteristic, Int>) = wanted.sumOf { minOf(state.getValue(it), targets.getValue(it)) }
            val exactCapped = cappedSum(assigned)
            val optimalCapped = exhaustiveBest(base, wanted, rolls) { cappedSum(it) }

            assertThat(exactCapped)
                .describedAs("exact max-capped must reach the optimal capped-sum; base=%s targets=%s rolls=%s", base, targets, rolls)
                .isEqualTo(optimalCapped)
        }
    }

    @Test
    fun `exact WEIGHTED max-min assignment attains the optimal weighted min (per-element DI fold)`() {
        // The per-element-DI multi-element most-masteries fold makes the element factors UNEQUAL, so the random-
        // element roll placement maximizes `min_e weight_e·max(0, offset + value_e)`, not the plain min. The solver
        // frees this; the scorer must reach the same optimum. Brute-force property test (mirrors the unweighted one).
        val random = Random(98765432)
        repeat(4000) {
            val m = 2 + random.nextInt(3) // 2..4 wanted elements
            val wanted = elements.take(m)
            val base = wanted.associateWith { random.nextInt(0, 25) }
            val rolls = List(1 + random.nextInt(5)) { Roll(value = 1 + random.nextInt(10), count = 1 + random.nextInt(3)) }
            val subset = wanted.filter { random.nextBoolean() }.ifEmpty { listOf(wanted.first()) }
            // Factors 100..150 (a +X% <element> damage sub makes one element's factor higher); a non-negative offset.
            val weights = subset.associateWith { (100 + random.nextInt(51)).toLong() }
            val offset = random.nextInt(0, 30).toLong()

            val assigned =
                assignMaxMinMasteryRandomValues(
                    rollsToRandomMap(rolls),
                    base,
                    wanted.associateWith { 9999 },
                    subset,
                    weights = weights,
                    offset = offset
                )

            fun weightedMin(state: Map<Characteristic, Int>) = subset.minOf { (weights.getValue(it) * maxOf(0L, offset + state.getValue(it))).toInt() }
            val assignedWeightedMin = weightedMin(assigned)
            val optimalWeightedMin = exhaustiveBest(base, wanted, rolls) { weightedMin(it) }

            assertThat(assignedWeightedMin)
                .describedAs("exact weighted max-min must reach the optimal weighted min; base=%s rolls=%s subset=%s weights=%s offset=%s", base, rolls, subset, weights, offset)
                .isEqualTo(optimalWeightedMin)
        }
    }
}
