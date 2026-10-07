package me.chosante.autobuilder.genetic.wakfu

/**
 * A primitive open-addressing `Long → Long` map specialised for a certificate DP's stage advance, whose only
 * write is a MAX-MERGE (`next[k] = max(next[k], v)`).
 *
 * Replaces the boxed `HashMap<Long, Long>` sweep in [MostMasteriesCertificate] (perf next-steps P1): every
 * boxed transition boxed the probed key, every insert allocated a `HashMap.Node` + two boxed `Long`s (~72 B per
 * entry vs 32 B here at load 0.5), and a colliding packed key degraded the bucket chains. A max-merge is
 * order- and container-independent, so the swap is EXACT: same states, same values, same bound.
 *
 * Keys: any value except [EMPTY] (`Long.MIN_VALUE`, which no packed state key can equal — the packed fields are
 * non-negative and never reach bit 63). Linear probing, Fibonacci hashing, load factor ≤ 0.5.
 */
internal class LongLongMaxMap(
    expected: Int = 16,
) {
    private var keys: LongArray
    private var vals: LongArray
    private var shift: Int
    private var mask: Int
    var size: Int = 0
        private set
    private var threshold: Int

    init {
        val cap = capacityFor(expected)
        keys = LongArray(cap).also { it.fill(EMPTY) }
        vals = LongArray(cap)
        mask = cap - 1
        shift = 64 - Integer.numberOfTrailingZeros(cap)
        threshold = cap / 2
    }

    private fun slot(k: Long): Int = ((k * GOLDEN) ushr shift).toInt()

    /** `this[k] = max(this[k], v)` (insert when absent). */
    fun putMax(
        k: Long,
        v: Long,
    ) {
        var i = slot(k)
        val ks = keys
        while (true) {
            val cur = ks[i]
            if (cur == EMPTY) {
                ks[i] = k
                vals[i] = v
                if (++size > threshold) rehash()
                return
            }
            if (cur == k) {
                if (v > vals[i]) vals[i] = v
                return
            }
            i = (i + 1) and mask
        }
    }

    /** Value for [k], or [default] when absent. */
    fun get(
        k: Long,
        default: Long,
    ): Long {
        var i = slot(k)
        val ks = keys
        while (true) {
            val cur = ks[i]
            if (cur == EMPTY) return default
            if (cur == k) return vals[i]
            i = (i + 1) and mask
        }
    }

    /** Max-merges every entry of [other] into this map. */
    fun mergeMax(other: LongLongMaxMap) {
        val ks = other.keys
        val vs = other.vals
        for (i in ks.indices) {
            val k = ks[i]
            if (k != EMPTY) putMax(k, vs[i])
        }
    }

    /** Boxed copy for the code that still consumes `HashMap<Long, Long>` between stages (collapse, provenance). */
    fun toHashMap(): HashMap<Long, Long> {
        val out = HashMap<Long, Long>(((size / 0.75f) + 1).toInt())
        val ks = keys
        val vs = vals
        for (i in ks.indices) {
            val k = ks[i]
            if (k != EMPTY) out[k] = vs[i]
        }
        return out
    }

    private fun rehash() {
        val oldK = keys
        val oldV = vals
        val cap = oldK.size * 2
        keys = LongArray(cap).also { it.fill(EMPTY) }
        vals = LongArray(cap)
        mask = cap - 1
        shift = 64 - Integer.numberOfTrailingZeros(cap)
        threshold = cap / 2
        val ks = keys
        val vs = vals
        for (j in oldK.indices) {
            val k = oldK[j]
            if (k == EMPTY) continue
            var i = slot(k)
            while (ks[i] != EMPTY) i = (i + 1) and mask
            ks[i] = k
            vs[i] = oldV[j]
        }
    }

    companion object {
        const val EMPTY: Long = Long.MIN_VALUE
        private const val GOLDEN: Long = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15

        /** [advance]'s default chunk-worker count: every core but one. */
        fun defaultWorkers(): Int = ComputeBudget().chunkWorkers

        private fun capacityFor(expected: Int): Int {
            val want = (expected.coerceAtLeast(4).toLong() * 2).coerceAtMost(1L shl 30)
            var cap = 16
            while (cap < want) cap = cap shl 1
            return cap
        }

        /**
         * One stage advance over a source map's flat arrays: [sweep] max-merges the successors of sources
         * `[from, to)` into its target map. Big stages run CHUNKED across CPU cores — each worker sweeps its slice
         * into a LOCAL map, then the locals max-merge (max is order-independent, so the result is identical to a
         * sequential sweep). Unlike world-level parallelism (measured SLOWER: 4 full concurrent DPs = 4× the live
         * state maps, GC-bound), the chunk locals only duplicate the overlap of one stage's output.
         *
         * [workers] caps the chunk count (below 2 = sequential). A pure work knob: the max-merge is order-independent,
         * so every value yields the identical map — callers throttle it while another computation owns the cores.
         */
        inline fun advance(
            srcKeys: LongArray,
            srcVals: LongArray,
            count: Int,
            transitionsPerSource: Int,
            parallelMinTransitions: Long,
            workers: Int = defaultWorkers(),
            crossinline sweep: (from: Int, to: Int, into: LongLongMaxMap) -> Unit,
        ): LongLongMaxMap {
            require(srcKeys.size >= count && srcVals.size >= count) { "advance: $count sources over smaller arrays" }
            val transitions = count.toLong() * transitionsPerSource
            if (transitions < parallelMinTransitions || workers < 2) {
                val out = LongLongMaxMap(count * 2)
                sweep(0, count, out)
                return out
            }
            val chunkCount = minOf(workers, 8)
            val chunkSize = (count + chunkCount - 1) / chunkCount
            val locals =
                (0 until chunkCount)
                    .toList()
                    .parallelStream()
                    .map { c ->
                        val from = c * chunkSize
                        val to = minOf(count, from + chunkSize)
                        val local = LongLongMaxMap(maxOf(16, (to - from) * 2))
                        if (from < to) sweep(from, to, local)
                        local
                    }.collect(
                        java.util.stream.Collectors
                            .toList()
                    )
            val merged = locals.maxByOrNull { it.size } ?: LongLongMaxMap()
            for (local in locals) if (local !== merged) merged.mergeMax(local)
            return merged
        }
    }
}
