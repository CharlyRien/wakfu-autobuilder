package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Locks of the max-damage E8 construct rescue ([WakfuBuildSolver.dpConstructProvenOptimum]) after its gate was
 * relaxed from "no row with a target" to "no row that constrains the problem", and its open-ended full-pool fallback
 * was given a wall-clock cap + cooperative cancellation:
 *  - the gate admits MAXIMIZED-mastery rows (which max-damage ignores — the GUI's default "distance mastery 1") and
 *    still refuses every required target ([isFreeMaxDamageShape]); the model-level test pins the ASSUMPTION the gate
 *    rests on (such a row changes neither the objective nor the certificate ledger);
 *  - the bounded collection ([collectWithinBudget]) stops a flow at its budget / on cancel and tears the upstream
 *    down — for a real `optimize` flow that means the native CP-SAT solve is stopped.
 */
class E8ConstructGateTest {
    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /** A CRA fire / distance max-damage request (no sockets, no sublimations) carrying [rows] as its target stats. */
    private fun fireParams(
        level: Int = 50,
        rows: List<TargetStat> = emptyList(),
    ): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
            targetStats = TargetStats(rows),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            useRunes = false,
            useSublimations = false,
            damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
        )

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
    ): Equipment =
        Equipment(
            equipmentId = id,
            guiId = id,
            level = 1,
            name = I18nText("item$id", "item$id", "item$id", "item$id"),
            rarity = Rarity.COMMON,
            itemType = type,
            characteristics = stats,
            maxShardSlots = 0,
            levelRestricted = false
        )

    /** A tiny single-element pool (fire mastery, critical mastery, crit, AP, MP, DI) — the exactly-certified shape family. */
    private fun tinyPool(): Map<ItemType, List<Equipment>> {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        return listOf(
            item(1, ItemType.AMULET, mapOf(fire to 800, Characteristic.DAMAGE_INFLICTED to 20)),
            item(2, ItemType.AMULET, mapOf(fire to 600, Characteristic.MASTERY_CRITICAL to 300)),
            item(3, ItemType.BELT, mapOf(fire to 700, Characteristic.ACTION_POINT to 1)),
            item(4, ItemType.BELT, mapOf(fire to 900)),
            item(5, ItemType.CAPE, mapOf(fire to 500, Characteristic.DAMAGE_INFLICTED to 40)),
            item(6, ItemType.CAPE, mapOf(fire to 650)),
            item(7, ItemType.BOOTS, mapOf(fire to 400, Characteristic.CRITICAL_HIT to 8)),
            item(8, ItemType.BOOTS, mapOf(fire to 550, Characteristic.MOVEMENT_POINT to 1)),
            item(9, ItemType.HELMET, mapOf(fire to 600, Characteristic.MASTERY_CRITICAL to 200)),
            item(10, ItemType.HELMET, mapOf(fire to 500, Characteristic.DAMAGE_INFLICTED to 30)),
            item(11, ItemType.RING, mapOf(fire to 300, Characteristic.ACTION_POINT to 1)),
            item(12, ItemType.RING, mapOf(fire to 450)),
            item(13, ItemType.RING, mapOf(fire to 350, Characteristic.MASTERY_CRITICAL to 150))
        ).groupBy { it.itemType }
    }

    private fun shape(vararg rows: Pair<Characteristic, Int>): Boolean = isFreeMaxDamageShape(TargetStats(rows.map { (c, t) -> TargetStat(c, t) }))

    // ---------------------------------------------------------------------------------------------
    // The gate
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a maximized-mastery row leaves the max-damage shape free`() {
        assertThat(shape()).describedAs("no rows").isTrue()
        assertThat(shape(Characteristic.MASTERY_DISTANCE to 1)).describedAs("the GUI's default row").isTrue()
        assertThat(shape(Characteristic.MASTERY_DISTANCE to 500)).describedAs("the CLI's --mastery-distance 500").isTrue()
        assertThat(
            shape(
                Characteristic.MASTERY_DISTANCE to 500,
                Characteristic.MASTERY_CRITICAL to 300,
                Characteristic.MASTERY_BACK to 200,
                Characteristic.MASTERY_MELEE to 100,
                Characteristic.MASTERY_BERSERK to 100,
                Characteristic.MASTERY_HEALING to 100
            )
        ).describedAs("every non-elemental maximizable mastery at once").isTrue()
        assertThat(shape(Characteristic.MASTERY_ELEMENTARY_FIRE to 1)).describedAs("ONE elemental mastery folds onto a single element: no prefilter").isTrue()
    }

    @Test
    fun `a required target row refuses the construct`() {
        for ((characteristic, target) in listOf(
            Characteristic.ACTION_POINT to 11,
            Characteristic.MOVEMENT_POINT to 4,
            Characteristic.RANGE to 4,
            Characteristic.CRITICAL_HIT to 25,
            Characteristic.HP to 2000,
            Characteristic.RESISTANCE_ELEMENTARY_FIRE to 100,
            Characteristic.DAMAGE_INFLICTED to 10,
            Characteristic.DODGE to 1,
            Characteristic.WAKFU_POINT to 1,
            // A NEGATIVE target is no "free" row either (the old `target > 0` gate waved it through).
            Characteristic.ACTION_POINT to -1
        )) {
            assertThat(shape(characteristic to target)).describedAs("%s=%d alone", characteristic, target).isFalse()
            assertThat(shape(Characteristic.MASTERY_DISTANCE to 1, characteristic to target))
                .describedAs("%s=%d next to a maximized row: ANY constraining row refuses", characteristic, target)
                .isFalse()
        }
    }

    @Test
    fun `zero-valued rows stay inert like before`() {
        // The GUI's seeded placeholder rows (weight 0 ⇒ no constraint, no penalty) never refused the construct.
        assertThat(shape(Characteristic.HP to 0, Characteristic.RESISTANCE_ELEMENTARY_WIND to 0, Characteristic.DODGE to 0, Characteristic.MASTERY_DISTANCE to 1)).isTrue()
    }

    @Test
    fun `rows that trigger the multi-element item prefilter are refused even when maximized`() {
        // The ledger AND the re-solve would run on a heuristically REDUCED pool — proves nothing globally.
        assertThat(shape(Characteristic.MASTERY_ELEMENTARY to 1)).describedAs("aggregate elemental mastery = 4 elements").isFalse()
        assertThat(shape(Characteristic.MASTERY_ELEMENTARY_FIRE to 1, Characteristic.MASTERY_ELEMENTARY_WATER to 1)).describedAs("two elements").isFalse()
        assertThat(shape(Characteristic.MASTERY_ELEMENTARY to 0)).describedAs("even 0-valued (expands to 4 elements)").isFalse()
    }

    @Test
    fun `random-element stats with a target are refused`() {
        assertThat(shape(Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 1)).isFalse()
        assertThat(shape(Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT to 1)).isFalse()
    }

    @Test
    fun `the gate admits exactly the rows the max-damage model ignores`() {
        // Row semantics, exhaustive: a single row of every characteristic with target 1. The model reads a row only
        // through isRequiredMostMasteriesTarget (hard leg, shortfall penalty, scorer) — so a row the gate ADMITS must
        // be one that filter excludes; and the only admitted kind beyond "ignored" is the prefilter exclusion.
        for (characteristic in Characteristic.entries) {
            val rows = TargetStats(listOf(TargetStat(characteristic, 1)))
            val admitted = isFreeMaxDamageShape(rows)
            assertThat(admitted)
                .describedAs("%s: admitted ⇔ maximized mastery that does not trigger the prefilter", characteristic)
                .isEqualTo(characteristic.isMaximizableMastery() && !WakfuBuildSolver.needsItemPrefilter(rows))
            if (admitted) {
                assertThat(characteristic.isRequiredMostMasteriesTarget())
                    .describedAs("%s is admitted, so the model must not read it as a required target", characteristic)
                    .isFalse()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The assumption behind the gate, on the model
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a maximized-mastery row leaves the max-damage optimum and the certificate ledger unchanged`() {
        val pool = tinyPool()
        val free = fireParams()
        val maximized = fireParams(rows = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 500), TargetStat(Characteristic.MASTERY_CRITICAL, 300)))

        // The certificate — production setting (domination ON), every cell confirmed exactly.
        val ledgerFree = WakfuBuildSolver.certifyLedgerForTest(free, pool, applyDomination = true, forceTier2All = true)
        val ledgerMaximized = WakfuBuildSolver.certifyLedgerForTest(maximized, pool, applyDomination = true, forceTier2All = true)
        assertThat(ledgerFree.maxCellObjective).describedAs("the fixture must certify (not bail)").isNotNull.isGreaterThan(0L)
        assertThat(ledgerMaximized.cellObjectives).isEqualTo(ledgerFree.cellObjectives)
        assertThat(ledgerMaximized.maxCellObjective).isEqualTo(ledgerFree.maxCellObjective)

        // The CP-SAT optimum — deterministic protocol, penalized objective AND raw proxy.
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, interleaveSearch = true, maxDeterministicTime = 60.0)
        val resultFree = runBlocking { WakfuBuildSolver.optimize(free, pool, tuning).toList().last() }
        val resultMaximized = runBlocking { WakfuBuildSolver.optimize(maximized, pool, tuning).toList().last() }
        assertThat(resultFree.isOptimal).isTrue()
        assertThat(resultMaximized.isOptimal).isTrue()
        assertThat(resultMaximized.maxDamageObjective).isEqualTo(resultFree.maxDamageObjective)
        assertThat(resultMaximized.maxDamageRawProxy).isEqualTo(resultFree.maxDamageRawProxy)

        // Negative control (the comparison can see a difference): a REQUIRED row does move the objective.
        val required = fireParams(rows = listOf(TargetStat(Characteristic.ACTION_POINT, 14)))
        val resultRequired = runBlocking { WakfuBuildSolver.optimize(required, pool, tuning).toList().last() }
        assertThat(resultRequired.maxDamageObjective).describedAs("an unreachable required AP target is penalized").isNotEqualTo(resultFree.maxDamageObjective)
    }

    // ---------------------------------------------------------------------------------------------
    // The construct end to end (tiny pool, in-process ledger)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the construct serves a request with a maximized row exactly like the free one and refuses a required target`(): Unit =
        runBlocking {
            val pool = tinyPool()
            val free = WakfuBuildSolver.dpConstructProvenOptimum(fireParams(), pool)
            assertThat(free).describedAs("the free request constructs its proven optimum on the tiny pool").isNotNull
            assertThat(free!!.isOptimal).isTrue()
            assertThat(free.individual.isValid()).isTrue()

            val maximized = WakfuBuildSolver.dpConstructProvenOptimum(fireParams(rows = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))), pool)
            assertThat(maximized).describedAs("a maximized-mastery row no longer gates the construct off").isNotNull
            assertThat(maximized!!.isOptimal).isTrue()
            assertThat(maximized.maxDamageRawProxy).isEqualTo(free.maxDamageRawProxy)

            // With a perfectly usable ledger at hand the required row still refuses — it is the GATE that says no.
            val ledger = WakfuBuildSolver.certifyLedgerForTest(fireParams(), pool, applyDomination = true, forceTier2All = true)
            val required =
                WakfuBuildSolver.dpConstructProvenOptimum(
                    fireParams(rows = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.ACTION_POINT, 7))),
                    pool,
                    precomputedLedger = ledger
                )
            assertThat(required).describedAs("a required AP target constrains the build: the DP bound does not certify it").isNull()
        }

    // ---------------------------------------------------------------------------------------------
    // The fallback tier: reachable, wall-capped, cancellable
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the full-pool fallback is wall-capped and cancellable`(): Unit =
        runBlocking {
            val pool = tinyPool()
            val params = fireParams()
            val ledger = WakfuBuildSolver.certifyLedgerForTest(params, pool, applyDomination = true, forceTier2All = true)
            val argmax =
                ledger.cellObjectives.entries
                    .filter { it.value >= 0 }
                    .maxByOrNull { it.value }!!
                    .key
            // A provenance pointer into a world that does not exist replays to NO items: the restricted fast tier is
            // skipped, so the construct can only succeed through the full-pool feasibility fallback.
            val fallbackOnly = ledger.copy(cellProvenance = ledger.cellProvenance + (argmax to CellProvenance(worldIndex = 99, c = 0)))

            val built = WakfuBuildSolver.dpConstructProvenOptimum(params, pool, precomputedLedger = fallbackOnly)
            assertThat(built).describedAs("the fallback reaches the bound under the default cap").isNotNull
            assertThat(built!!.isOptimal).isTrue()
            assertThat(built.maxDamageRawProxy).isGreaterThanOrEqualTo(ledger.cellObjectives.getValue(argmax))

            assertThat(WakfuBuildSolver.dpConstructProvenOptimum(params, pool, precomputedLedger = fallbackOnly, fallbackWallCapSeconds = 0.0))
                .describedAs("a spent wall-clock budget makes the fallback give up at once — the rescue answers null")
                .isNull()
            assertThat(WakfuBuildSolver.dpConstructProvenOptimum(params, pool, precomputedLedger = fallbackOnly, isCancelled = { true }))
                .describedAs("a cancelled rescue does no work and answers null")
                .isNull()
            assertThat(WakfuBuildSolver.dpConstructProvenOptimum(params, pool, precomputedLedger = ledger, isCancelled = { true }))
                .describedAs("…whichever tier it would have used")
                .isNull()
        }

    @Test
    fun `the fallback cap is a minute at most`() {
        assertThat(WakfuBuildSolver.E8_FALLBACK_WALL_CAP_SECONDS).isGreaterThan(0.0).isLessThanOrEqualTo(60.0)
    }

    // ---------------------------------------------------------------------------------------------
    // collectWithinBudget
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a flow that completes in time is collected whole`(): Unit =
        runBlocking {
            val capped = collectWithinBudget(flowOf(1, 2, 3), budgetMillis = 10_000)
            assertThat(capped.items).containsExactly(1, 2, 3)
            assertThat(capped.end).isEqualTo(CollectEnd.COMPLETED)

            val slow =
                flow {
                    delay(300)
                    emit(7)
                }
            val uncapped = collectWithinBudget(slow, budgetMillis = null)
            assertThat(uncapped.items).containsExactly(7)
            assertThat(uncapped.end).isEqualTo(CollectEnd.COMPLETED)
        }

    @Test
    fun `the budget stops a flow that never completes, keeps its partial emissions and tears the upstream down`(): Unit =
        runBlocking {
            val torn = CountDownLatch(1)
            val hanging =
                flow {
                    try {
                        emit(1)
                        awaitCancellation()
                    } finally {
                        torn.countDown()
                    }
                }
            val startedAt = System.nanoTime()
            val capped = collectWithinBudget(hanging, budgetMillis = 300)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertThat(capped.end).isEqualTo(CollectEnd.TIMED_OUT)
            assertThat(capped.items).describedAs("what the flow emitted before the deadline is kept").containsExactly(1)
            assertThat(elapsedMs).describedAs("gave up near the 300 ms budget, not whenever the flow would end").isLessThan(20_000)
            assertThat(torn.await(10, TimeUnit.SECONDS)).describedAs("the upstream (the native solve, for optimize) was torn down").isTrue()
        }

    @Test
    fun `the cancel hook stops a flow promptly and tears the upstream down`(): Unit =
        runBlocking {
            val torn = CountDownLatch(1)
            val hanging =
                flow {
                    try {
                        emit(1)
                        awaitCancellation()
                    } finally {
                        torn.countDown()
                    }
                }
            val cancelled = AtomicBoolean(false)
            thread(isDaemon = true) {
                Thread.sleep(300)
                cancelled.set(true)
            }
            val startedAt = System.nanoTime()
            val run = collectWithinBudget(hanging, budgetMillis = null, isCancelled = { cancelled.get() })
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertThat(run.end).isEqualTo(CollectEnd.CANCELLED)
            assertThat(run.items).containsExactly(1)
            assertThat(elapsedMs).describedAs("noticed the flag within a poll tick or two").isLessThan(20_000)
            assertThat(torn.await(10, TimeUnit.SECONDS)).isTrue()
        }

    @Test
    fun `a spent budget or a raised flag never even starts the flow`(): Unit =
        runBlocking {
            val started = AtomicBoolean(false)
            val probe = flow<Int> { started.set(true) }
            assertThat(collectWithinBudget(probe, budgetMillis = 0).end).isEqualTo(CollectEnd.TIMED_OUT)
            assertThat(collectWithinBudget(probe, budgetMillis = null, isCancelled = { true }).end).isEqualTo(CollectEnd.CANCELLED)
            assertThat(started.get()).isFalse()
        }

    @Test
    fun `an upstream failure propagates and an outer timeout still cancels the collection`() {
        assertThatThrownBy { runBlocking { collectWithinBudget(flow<Int> { throw IllegalStateException("boom") }, budgetMillis = 10_000) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("boom")

        val torn = CountDownLatch(1)
        val hanging =
            flow<Int> {
                try {
                    awaitCancellation()
                } finally {
                    torn.countDown()
                }
            }
        assertThatThrownBy { runBlocking { withTimeout(300) { collectWithinBudget(hanging, budgetMillis = null) } } }
            .isInstanceOf(TimeoutCancellationException::class.java)
        assertThat(torn.await(10, TimeUnit.SECONDS)).describedAs("the outer cancellation reached the upstream").isTrue()
    }

    // ---------------------------------------------------------------------------------------------
    // The real thing: cancelling the collection stops the NATIVE CP-SAT solve behind optimize()
    // ---------------------------------------------------------------------------------------------

    /** Threads currently inside an OR-Tools native call — i.e. a CP-SAT solve is running on them. */
    private fun nativeSolveThreads(): Int =
        Thread
            .getAllStackTraces()
            .values
            .count { frames -> frames.any { it.isNativeMethod && it.className.startsWith("com.google.ortools") } }

    /** The embedded level-110 EPIC pool: the free max-damage solve on it needs far longer than these tests to prove OPTIMAL. */
    private fun level110Pool(): Map<ItemType, List<Equipment>> =
        WakfuBestBuildFinderAlgorithm.equipments
            .filter { it.rarity <= Rarity.EPIC }
            .filter { it.level in 0..110 || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
            .groupBy { it.itemType }

    private val longSolve = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, interleaveSearch = true, maxDeterministicTime = 600.0)

    @Test
    fun `cancelling the collection stops the native CP-SAT solve behind optimize`(): Unit =
        runBlocking {
            val pool = level110Pool()
            val baseline = nativeSolveThreads()
            // CP-SAT logs the moment THIS solve starts: raise the cancel flag then, so the test exercises stopSearch on a
            // solve that is really running (not the not-yet-started shortcut), whatever other tests leave behind.
            val solving = AtomicBoolean(false)
            val tuning = longSolve.copy(searchLogSink = { solving.set(true) })
            val run = collectWithinBudget(WakfuBuildSolver.optimize(fireParams(110), pool, tuning), budgetMillis = 300_000, isCancelled = { solving.get() })
            assertThat(run.end).describedAs("the flag was raised while the solve ran").isEqualTo(CollectEnd.CANCELLED)

            // The solve winds down by itself after stopSearch(): its thread leaves the native call.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (nativeSolveThreads() > baseline && System.nanoTime() < deadline) Thread.sleep(100)
            assertThat(nativeSolveThreads()).describedAs("no CP-SAT solve left running after the cancel").isLessThanOrEqualTo(baseline)
        }

    /** True while some thread is inside the (blocking, uncancellable) CP-SAT model build of [WakfuBuildSolver]. */
    private fun inModelBuild(): Boolean =
        Thread
            .getAllStackTraces()
            .values
            .any { frames -> frames.any { it.className.endsWith("WakfuBuildSolver") && it.methodName == "buildModel" } }

    @Test
    fun `a flow torn down while its model is still being built never starts the native solve`(): Unit =
        runBlocking {
            val pool = level110Pool()
            val solveStarted = AtomicBoolean(false)
            // CP-SAT logs the moment a solve starts — a deterministic "a solve began" signal, unlike sampling native frames.
            val tuning = longSolve.copy(searchLogSink = { solveStarted.set(true) })
            val cancelled = AtomicBoolean(false)
            val stopWatching = AtomicBoolean(false)
            // Raise the cancel flag the moment the model build is observed running: `awaitClose` then finds no solver to stop.
            // Without the `isActive` guard in optimize() the orphaned build went on to START the solve anyway — and, when no
            // solution exists to trip the solution callback's own cancel check (the E8 fallback's infeasible floor), ran out
            // its whole budget with nobody listening.
            val watcher =
                thread(isDaemon = true) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                    while (!stopWatching.get() && System.nanoTime() < deadline) {
                        if (inModelBuild()) {
                            cancelled.set(true)
                            return@thread
                        }
                        Thread.sleep(1)
                    }
                }
            val run =
                try {
                    collectWithinBudget(WakfuBuildSolver.optimize(fireParams(110), pool, tuning), budgetMillis = 120_000, isCancelled = { cancelled.get() }, pollMillis = 5)
                } finally {
                    stopWatching.set(true)
                    watcher.join()
                }
            assertThat(run.end).describedAs("the flag was raised while the model was being built").isEqualTo(CollectEnd.CANCELLED)
            // The orphaned build cannot be interrupted: let it finish (a fraction of a second), then give a solve every
            // chance to start behind it.
            Thread.sleep(5_000)
            assertThat(solveStarted.get()).describedAs("a torn-down flow must not start its native solve").isFalse()
        }

    // ---------------------------------------------------------------------------------------------
    // Manual probe (production path): the GUI's default maximized row on the 4-core free incumbents
    // ---------------------------------------------------------------------------------------------

    /**
     * Probe P4 (2026-10 perf pass) re-run on the PRODUCTION path: the real GUI-default max-damage
     * request — runes + sublimations on, the maximized "distance mastery 1" row — at levels 110 / 245 with the E0
     * 4-core free incumbents (the search ended at "proven within 1.1 % / 1.6 %"): cached certificate ledger, then
     * [WakfuBuildSolver.dpConstructProvenOptimum] exactly as `constructMaxDamageProvenOptimum` calls it. Prints the
     * ledger / construct wall times and the outcome (a failing rescue is the wall-capped one). `WAKFU_E8_GATE_PROBE=1`;
     * run with `WAKFU_TEST_JVM_ARGS=-XX:ActiveProcessorCount=4 WAKFU_TEST_MAX_HEAP=3g` for the 4-core profile.
     */
    @Test
    @Tag("manual")
    fun `manual E8 construct with the GUI maximized row on the E0 free incumbents`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_E8_GATE_PROBE") == "1")
            val subs = WakfuBestBuildFinderAlgorithm.sublimations
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val levels = System.getenv("WAKFU_E8_GATE_LEVELS")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(245, 110)
            val incumbents = mapOf(110 to 1_595_415L, 245 to 20_475_270L)
            for (lvl in levels) {
                val incumbent = incumbents.getValue(lvl)
                val params =
                    fireParams(lvl, rows = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
                        .copy(useRunes = true, useSublimations = true, damageScenario = DamageScenario()) // the default scenario the E0 incumbents were searched under
                val pool =
                    WakfuBestBuildFinderAlgorithm.equipments
                        .filter { it.rarity <= Rarity.EPIC }
                        .filter { it.level in 0..lvl || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                        .groupBy { it.itemType }
                MaxDamageCertificateCache.clear()
                // 1) the ledger — in production the E10 warm-up / the post-search proof pays this (and caches it).
                val t0 = System.nanoTime()
                val ledger =
                    MaxDamageCertificateCache.certificate(
                        params,
                        pool,
                        runes,
                        subs,
                        applyDomination = true,
                        incumbentObjective = incumbent,
                        threads = WakfuBuildSolver.certifierDefaultThreads(),
                        cascadeTier15 = true
                    )
                val ledgerMs = (System.nanoTime() - t0) / 1_000_000
                // 2) the construct — what the post-search rescue pays (ledger = cache hit).
                val t1 = System.nanoTime()
                val constructed = WakfuBuildSolver.dpConstructProvenOptimum(params, pool, runes, subs, incumbentObjective = incumbent)
                val constructMs = (System.nanoTime() - t1) / 1_000_000
                println(
                    "E8GATE level=$lvl incumbent=$incumbent ledgerMs=$ledgerMs maxCell=${ledger?.maxCellObjective} " +
                        "constructMs=$constructMs success=${constructed != null} isOptimal=${constructed?.isOptimal} " +
                        "constructedProxy=${constructed?.maxDamageRawProxy} fallbackCapS=${WakfuBuildSolver.E8_FALLBACK_WALL_CAP_SECONDS}"
                )
            }
        }
}
