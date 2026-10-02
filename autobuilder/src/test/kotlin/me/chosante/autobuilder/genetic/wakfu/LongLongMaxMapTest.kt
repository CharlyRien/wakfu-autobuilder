package me.chosante.autobuilder.genetic.wakfu

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** [LongLongMaxMap] must be a drop-in for the certificate's boxed `HashMap<Long, Long>` max-merge. */
class LongLongMaxMapTest {
    private fun HashMap<Long, Long>.putMax(
        k: Long,
        v: Long,
    ) {
        val cur = this[k]
        if (cur == null || v > cur) this[k] = v
    }

    @Test
    fun `putMax keeps the max per key across growth like a HashMap`() {
        val rng = Random(7)
        val map = LongLongMaxMap(expected = 4)
        val reference = HashMap<Long, Long>()
        repeat(200_000) {
            // Packed-key-like values: few distinct high fields, many repeats, plus keys that only differ in high
            // bits (they collide on a naive low-bit hash) and the extremes of the non-negative range.
            val k =
                when (it % 4) {
                    0 -> rng.nextLong(0, 5_000)
                    1 -> rng.nextLong(0, 64) shl 45
                    2 -> (rng.nextLong(0, 1_000) shl 28) or rng.nextLong(0, 8)
                    else -> if (rng.nextBoolean()) 0L else Long.MAX_VALUE - rng.nextLong(0, 16)
                }
            val v = rng.nextLong(-1_000_000, 1_000_000)
            map.putMax(k, v)
            reference.putMax(k, v)
        }
        assertThat(map.size).isEqualTo(reference.size)
        assertThat(map.toHashMap()).isEqualTo(reference)
        for ((k, v) in reference) assertThat(map.get(k, Long.MIN_VALUE)).isEqualTo(v)
        assertThat(map.get(-1L, 42L)).describedAs("absent key ⇒ default").isEqualTo(42L)
    }

    @Test
    fun `mergeMax is the per-key max of both maps`() {
        val a = LongLongMaxMap()
        val b = LongLongMaxMap()
        a.putMax(1L, 10L)
        a.putMax(2L, 5L)
        b.putMax(2L, 7L)
        b.putMax(3L, -4L)
        a.mergeMax(b)
        assertThat(a.toHashMap()).isEqualTo(hashMapOf(1L to 10L, 2L to 7L, 3L to -4L))
    }

    @Test
    fun `advance gives the same map sequentially and chunked in parallel`() {
        val rng = Random(11)
        val count = 50_000
        val keys = LongArray(count) { rng.nextLong(0, 1L shl 40) }
        val vals = LongArray(count) { rng.nextLong(0, 1_000_000) }
        val deltas = LongArray(6) { rng.nextLong(0, 1L shl 20) }

        fun run(
            parallelMinTransitions: Long,
            workers: Int = LongLongMaxMap.defaultWorkers(),
        ) = LongLongMaxMap
            .advance(keys, vals, count, deltas.size, parallelMinTransitions, workers) { from, to, into ->
                for (i in from until to) {
                    for (d in deltas) into.putMax((keys[i] xor d) and ((1L shl 22) - 1), vals[i] + d)
                }
            }.toHashMap()

        val sequential = run(Long.MAX_VALUE)
        val reference = HashMap<Long, Long>()
        for (i in 0 until count) {
            for (d in deltas) reference.putMax((keys[i] xor d) and ((1L shl 22) - 1), vals[i] + d)
        }
        assertThat(sequential).isEqualTo(reference)
        // The default chunked path only engages with ≥ 3 cores; on smaller runners it is sequential (still equal).
        assertThat(run(0L)).isEqualTo(reference)
        // An explicit worker count is a pure work knob (the search-time warm-up throttles to 1): every chunking
        // yields the identical map, whatever the runner's core count.
        for (workers in listOf(1, 2, 3, 8)) {
            assertThat(run(0L, workers)).describedAs("workers=$workers").isEqualTo(reference)
        }
    }
}
