package me.chosante.autobuilder.genetic.wakfu

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ComputeBudgetTest {
    @Test
    fun `every production worker formula fits the budget including small machines`() {
        for (n in 1..ComputeBudget.availableCores) {
            val budget = ComputeBudget(n)
            assertThat(budget.searchWorkers).isBetween(1, n)
            assertThat(budget.oracleWorkers).isBetween(1, n)
            assertThat(budget.warmupWorkers).isBetween(1, n)
            assertThat(budget.chunkWorkers).isBetween(0, n)
            assertThat(budget.cap(Int.MAX_VALUE)).isEqualTo(n)
            for (heapGiB in listOf(1L, 3L, 4L, 8L, 64L)) {
                val heap = heapGiB * 1024 * 1024 * 1024
                assertThat(WakfuBuildSolver.certifierThreadsForHeap(heap, n)).isBetween(1, n)
                assertThat(WakfuBuildSolver.certifierTier15ThreadsForHeap(heap, n)).isBetween(1, n)
                assertThat(WakfuBuildSolver.certifierFastWorldThreadsForHeap(heap, n)).isBetween(1, n)
            }
            for (probes in 1..12) {
                val plan = MaxDamageSearch.probePlan(probes, budget.searchWorkers, kotlin.time.Duration.parse("60s"))
                assertThat(plan.concurrency * plan.workersPerProbe).isLessThanOrEqualTo(n)
            }
        }
    }

    @Test
    fun `maximum preserves the existing thread formulas`() {
        val cores = Runtime.getRuntime().availableProcessors()
        val budget = ComputeBudget()
        assertThat(budget.logicalCores).isEqualTo(cores)
        assertThat(budget.chunkWorkers).isEqualTo(cores - 1)
        assertThat(budget.searchWorkers).isEqualTo((cores - 1).coerceAtLeast(1))
        // The legacy oracle's minimum of four oversubscribed machines with fewer than four cores.
        assertThat(budget.oracleWorkers).isEqualTo(minOf(cores, (cores - 2).coerceIn(4, 8)))
        assertThat(budget.warmupWorkers).isEqualTo(minOf(cores, 2))
        val heap = Runtime.getRuntime().maxMemory()
        assertThat(WakfuBuildSolver.certifierDefaultThreads(budget)).isEqualTo(WakfuBuildSolver.certifierThreadsForHeap(heap, cores))
        assertThat(WakfuBuildSolver.certifierTier15Threads(budget)).isEqualTo(WakfuBuildSolver.certifierTier15ThreadsForHeap(heap, cores))
        assertThat(WakfuBuildSolver.certifierFastWorldThreads(budget)).isEqualTo(WakfuBuildSolver.certifierFastWorldThreadsForHeap(heap, cores))
    }

    @Test
    fun `invalid budgets fail rather than silently using extra cores`() {
        assertThrows<IllegalArgumentException> { ComputeBudget(0) }
        assertThrows<IllegalArgumentException> { ComputeBudget(ComputeBudget.availableCores + 1) }
    }
}
