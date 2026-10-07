package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * E10-for-MM (docs/MOST_MASTERIES_PERF_PLAN.md §8.19): the most-masteries quality bound is memoized single-flight,
 * computed beside the search (in its tail — the tests start it at once) on one stage worker, joined by the post-search
 * proof, and cancelled whenever no proof will ask for it. Deterministic: a gated fake certificate
 * ([MostMasteriesBoundCache.certificateForTest]) drives timing and cancellation — no DP, no CP-SAT — except the
 * end-to-end lock, which runs both on a one-item pool.
 */
class MostMasteriesBoundCacheTest {
    @AfterEach
    fun reset() {
        MostMasteriesBoundCache.certificateForTest = null
        MostMasteriesBoundCache.clearForTest()
    }

    /** A distinct request per test ([level]) so no memo entry or flight leaks between tests. */
    private fun params(
        level: Int,
        targets: List<TargetStat> = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.MOVEMENT_POINT, 4)),
        forcedItems: List<String> = emptyList(),
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 5.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = forcedItems,
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes = false,
        useSublimations = false
    )

    private val helmet =
        Equipment(
            equipmentId = 1,
            guiId = 1,
            level = 200,
            name = I18nText("helmet", "helmet", "", ""),
            rarity = Rarity.LEGENDARY,
            itemType = ItemType.HELMET,
            characteristics = mapOf(Characteristic.MASTERY_DISTANCE to 300, Characteristic.MOVEMENT_POINT to 1),
            maxShardSlots = 0
        )
    private val pool: Map<ItemType, List<Equipment>> = listOf(helmet).groupBy { it.itemType }

    private fun fakeBound(folded: Long) = MostMasteriesCertificate.Result(foldedBound = folded, coreBound = folded / 1_000, states = 1, wallMs = 0)

    private fun result(
        objective: Long?,
        optimal: Boolean = false,
    ) = SolverResult(
        individual = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(200)),
        matchPercentage = BigDecimal.ONE,
        progressPercentage = 100,
        isOptimal = optimal,
        mostMasteriesObjective = objective
    )

    /**
     * A fake certificate that blocks until [release] opens or its compute is cancelled, recording the stage-worker
     * count it reads on every poll (the throttle) and whether it saw the cancellation.
     */
    private class Gate(
        private val bound: MostMasteriesCertificate.Result?,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workersSeen = CopyOnWriteArrayList<Int>()
        val sawCancel = AtomicBoolean(false)

        fun certificate(): (WakfuBestBuildParams, () -> Boolean, () -> Int) -> MostMasteriesCertificate.Result? =
            { _, live, workers ->
                entered.countDown()
                var out = bound
                while (true) {
                    workersSeen += workers()
                    if (!live()) {
                        sawCancel.set(true)
                        out = null
                        break
                    }
                    if (release.await(5, TimeUnit.MILLISECONDS)) break
                }
                out
            }
    }

    private suspend fun awaitUntil(predicate: () -> Boolean) =
        withTimeout(20.seconds) {
            while (!predicate()) delay(5)
        }

    private fun computes() = MostMasteriesBoundCache.computeCountForTest.get()

    /**
     * The production wrapper with the warm-up starting at once by default — production schedules it in the search's
     * tail ([MostMasteriesBoundCache.warmupStartDelay]), tests drive the timing.
     */
    private fun warm(
        p: WakfuBestBuildParams,
        basePool: Map<ItemType, List<Equipment>>,
        sublimations: List<me.chosante.common.Sublimation>,
        search: Flow<SolverResult<BuildCombination>>,
        startDelay: Duration? = Duration.ZERO,
    ) = MostMasteriesBoundCache.withSearchTimeWarmup(p, basePool, sublimations, search, startDelay)

    /** A search that streams [first], then holds until [finish] completes and ends with [last]. */
    private fun heldSearch(
        first: SolverResult<BuildCombination>,
        finish: CompletableDeferred<Unit>,
        last: SolverResult<BuildCombination>,
    ): Flow<SolverResult<BuildCombination>> =
        flow {
            emit(first)
            finish.await()
            emit(last)
        }

    @Test
    fun `concurrent callers share one memoized compute and a re-run with another budget hits the memo`(): Unit =
        runBlocking {
            val p = params(201)
            val gate = Gate(fakeBound(2_000))
            MostMasteriesBoundCache.certificateForTest = gate.certificate()
            val before = computes()
            val first = async(Dispatchers.Default) { MostMasteriesBoundCache.bound(p, pool) }
            gate.entered.await()
            val second = async(Dispatchers.Default) { MostMasteriesBoundCache.bound(p, pool) }
            delay(50)
            gate.release.countDown()
            val bound = requireNotNull(first.await())
            assertThat(second.await()).isSameAs(bound)
            assertThat(computes() - before).describedAs("single-flight: one compute for both callers").isEqualTo(1)
            // The key ignores the budget (and the other search-only fields): a re-run reads the memo instantly.
            assertThat(MostMasteriesBoundCache.bound(p.copy(searchDuration = 99.seconds, solverWorkers = 2))).isSameAs(bound)
            assertThat(computes() - before).isEqualTo(1)
        }

    @Test
    fun `a caller that gives up cancels the compute it started and nothing is cached`(): Unit =
        runBlocking {
            val p = params(202)
            val gate = Gate(fakeBound(2_000))
            MostMasteriesBoundCache.certificateForTest = gate.certificate()
            val stop = AtomicBoolean(false)
            val waiting = async(Dispatchers.Default) { MostMasteriesBoundCache.bound(p, pool) { !stop.get() } }
            gate.entered.await()
            stop.set(true)
            assertThat(waiting.await()).isNull()
            awaitUntil { gate.sawCancel.get() && !MostMasteriesBoundCache.inFlightForTest(p) }
            assertThat(MostMasteriesBoundCache.isCachedForTest(p)).describedAs("a cancelled compute is unknown, never a bail").isFalse()
        }

    @Test
    fun `a cancelled compute still winding down is replaced, not joined`(): Unit =
        runBlocking {
            val p = params(219)
            val windDown = CountDownLatch(1)
            val firstEntered = CountDownLatch(1)
            val calls =
                java.util.concurrent.atomic
                    .AtomicInteger()
            // The first compute is stuck in a long stage (it cannot see its cancellation until the stage ends); the
            // second answers at once.
            MostMasteriesBoundCache.certificateForTest = { _, _, _ ->
                if (calls.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    windDown.await(20, TimeUnit.SECONDS)
                }
                fakeBound(2_000)
            }
            val before = computes()
            val stop = AtomicBoolean(false)
            val gaveUp = async(Dispatchers.Default) { MostMasteriesBoundCache.bound(p, pool) { !stop.get() } }
            firstEntered.await()
            stop.set(true)
            assertThat(gaveUp.await()).isNull()
            // A new caller must not wait for the dying compute: it starts a fresh one and gets its bound.
            assertThat(withTimeout(10.seconds) { async(Dispatchers.Default) { MostMasteriesBoundCache.bound(p, pool) }.await() }?.foldedBound)
                .isEqualTo(2_000L)
            assertThat(computes() - before).isEqualTo(2)
            windDown.countDown()
            awaitUntil { !MostMasteriesBoundCache.inFlightForTest(p) }
        }

    @Test
    fun `a bail is memoized like a bound`(): Unit =
        runBlocking {
            val p = params(203)
            MostMasteriesBoundCache.certificateForTest = { _, _, _ -> null }
            val before = computes()
            assertThat(MostMasteriesBoundCache.bound(p, pool)).isNull()
            assertThat(MostMasteriesBoundCache.bound(p, pool)).isNull()
            assertThat(computes() - before).isEqualTo(1)
        }

    @Test
    fun `the warm-up runs beside the search on one stage worker and the post-search proof joins it`(): Unit =
        runBlocking {
            val p = params(204)
            val gate = Gate(fakeBound(2_000))
            MostMasteriesBoundCache.certificateForTest = gate.certificate()
            val before = computes()
            val finish = CompletableDeferred<Unit>()
            val search =
                async(Dispatchers.Default) {
                    warm(p, pool, emptyList(), heldSearch(result(1_000), finish, result(1_500)))
                        .toList()
                }
            gate.entered.await()
            awaitUntil { gate.workersSeen.size >= 3 }
            assertThat(gate.workersSeen).describedAs("one stage worker while CP-SAT owns the cores").containsOnly(1)
            finish.complete(Unit)
            val final = search.await().last()
            // Un-proven, comparable result: the compute keeps running, now on the full chunk-worker count.
            val seenBeforeEnd = gate.workersSeen.size
            awaitUntil { gate.workersSeen.size > seenBeforeEnd + 2 }
            assertThat(gate.workersSeen.last()).isEqualTo(LongLongMaxMap.defaultWorkers())
            assertThat(MostMasteriesBoundCache.inFlightForTest(p)).isTrue()
            val proof = async(Dispatchers.Default) { WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(p, final) }
            delay(50)
            gate.release.countDown()
            assertThat(proof.await()).isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenWithin(2_000.0 / 1_500 - 1))
            assertThat(computes() - before).describedAs("the proof joined the warm-up's compute").isEqualTo(1)
        }

    @Test
    fun `the warm-up runs in the search's tail, never in its first 30 s`() {
        val startDelay = MostMasteriesBoundCache::warmupStartDelay
        // No room after the early phase: the proof computes the bound post-search.
        assertThat(startDelay(10.seconds)).isNull()
        assertThat(startDelay(30.seconds)).isNull()
        // Short budgets start at the early-phase floor, long ones 30 s before the budget runs out.
        assertThat(startDelay(45.seconds)).isEqualTo(30.seconds)
        assertThat(startDelay(60.seconds)).isEqualTo(30.seconds)
        assertThat(startDelay(120.seconds)).isEqualTo(90.seconds)
        assertThat(startDelay(600.seconds)).isEqualTo(570.seconds)
    }

    @Test
    fun `a delayed warm-up starts only if its search is still running`(): Unit =
        runBlocking {
            val gate = Gate(fakeBound(2_000))
            MostMasteriesBoundCache.certificateForTest = gate.certificate()
            val before = computes()
            // Over before the start: nothing ever computes (an OPTIMAL proof, or a budget the proof covers on demand).
            val early = params(217)
            warm(early, pool, emptyList(), flowOf(result(1_500, optimal = true)), startDelay = 300.milliseconds).toList()
            warm(early, pool, emptyList(), flowOf(result(1_500)), startDelay = 300.milliseconds).toList()
            delay(600)
            assertThat(computes() - before).isEqualTo(0)
            assertThat(MostMasteriesBoundCache.inFlightForTest(early)).isFalse()
            // Still running at the start: the bound starts, on one stage worker.
            val late = params(218)
            val finish = CompletableDeferred<Unit>()
            val search = async(Dispatchers.Default) { warm(late, pool, emptyList(), heldSearch(result(1_000), finish, result(1_500)), startDelay = 300.milliseconds).toList() }
            delay(100)
            assertThat(computes() - before).describedAs("not before its start").isEqualTo(0)
            gate.entered.await()
            awaitUntil { gate.workersSeen.size >= 3 }
            assertThat(gate.workersSeen).containsOnly(1)
            finish.complete(Unit)
            search.await()
            gate.release.countDown()
            awaitUntil { MostMasteriesBoundCache.isCachedForTest(late) }
            assertThat(computes() - before).isEqualTo(1)
        }

    @Test
    fun `a search that ends proven optimal cancels its warm-up`(): Unit =
        runBlocking {
            val p = params(205)
            val gate = Gate(fakeBound(2_000))
            MostMasteriesBoundCache.certificateForTest = gate.certificate()
            warm(p, pool, emptyList(), flowOf(result(1_000), result(1_500, optimal = true)))
                .toList()
            awaitUntil { gate.sawCancel.get() && !MostMasteriesBoundCache.inFlightForTest(p) }
            assertThat(MostMasteriesBoundCache.isCachedForTest(p)).isFalse()
        }

    @Test
    fun `a search without a comparable objective cancels its warm-up`(): Unit =
        runBlocking {
            val p = params(206)
            val gate = Gate(fakeBound(2_000))
            MostMasteriesBoundCache.certificateForTest = gate.certificate()
            warm(p, pool, emptyList(), flowOf(result(objective = null))).toList()
            awaitUntil { gate.sawCancel.get() && !MostMasteriesBoundCache.inFlightForTest(p) }
            assertThat(MostMasteriesBoundCache.isCachedForTest(p)).isFalse()
        }

    @Test
    fun `a search cancelled mid-flight cancels its warm-up`(): Unit =
        runBlocking {
            val p = params(207)
            val gate = Gate(fakeBound(2_000))
            MostMasteriesBoundCache.certificateForTest = gate.certificate()
            val job =
                launch(Dispatchers.Default) {
                    warm(
                        p,
                        pool,
                        emptyList(),
                        flow {
                            emit(result(1_000))
                            awaitCancellation()
                        }
                    ).collect { }
                }
            gate.entered.await()
            job.cancelAndJoin()
            awaitUntil { gate.sawCancel.get() && !MostMasteriesBoundCache.inFlightForTest(p) }
            assertThat(MostMasteriesBoundCache.isCachedForTest(p)).isFalse()
        }

    @Test
    fun `a same-request re-search adopts the in-flight bound and a new request supersedes it`(): Unit =
        runBlocking {
            val a = params(208)
            val b = params(209)
            val gateA = Gate(fakeBound(2_000))
            val gateB = Gate(fakeBound(3_000))
            MostMasteriesBoundCache.certificateForTest = { p, live, workers ->
                (if (p.character.level == 208) gateA else gateB).certificate()(p, live, workers)
            }
            val before = computes()
            // Search A ends un-proven: its warm-up keeps computing for the proof.
            warm(a, pool, emptyList(), flowOf(result(1_500))).toList()
            gateA.entered.await()
            // The SAME request again (another budget): adopted, not recomputed — and throttled again while it runs.
            val finish = CompletableDeferred<Unit>()
            val rerun =
                async(Dispatchers.Default) {
                    warm(a.copy(searchDuration = 30.seconds), pool, emptyList(), heldSearch(result(1_000), finish, result(1_600)))
                        .toList()
                }
            val seen = gateA.workersSeen.size
            awaitUntil { gateA.workersSeen.size > seen + 2 }
            assertThat(gateA.workersSeen.last()).isEqualTo(1)
            finish.complete(Unit)
            rerun.await()
            assertThat(computes() - before).describedAs("the re-search adopted A's compute").isEqualTo(1)
            assertThat(MostMasteriesBoundCache.inFlightForTest(a)).isTrue()
            // A different request supersedes A's compute the moment its search starts.
            val searchB = async(Dispatchers.Default) { warm(b, pool, emptyList(), flowOf(result(2_000))).toList() }
            awaitUntil { gateA.sawCancel.get() && !MostMasteriesBoundCache.inFlightForTest(a) }
            assertThat(MostMasteriesBoundCache.isCachedForTest(a)).isFalse()
            searchB.await()
            gateB.release.countDown()
            awaitUntil { MostMasteriesBoundCache.isCachedForTest(b) }
            // A non-most-masteries search supersedes every in-flight bound too.
            val c = params(210)
            val gateC = Gate(fakeBound(4_000))
            MostMasteriesBoundCache.certificateForTest = gateC.certificate()
            warm(c, pool, emptyList(), flowOf(result(2_500))).toList()
            gateC.entered.await()
            MostMasteriesBoundCache.supersedeAll()
            awaitUntil { gateC.sawCancel.get() && !MostMasteriesBoundCache.inFlightForTest(c) }
        }

    @Test
    fun `cancelBackgroundProofs stops a bound the finished search left computing and caches nothing`(): Unit =
        runBlocking {
            val p = params(290)
            val gate = Gate(fakeBound(2_000))
            MostMasteriesBoundCache.certificateForTest = gate.certificate()
            // The search ends un-proven with a comparable objective: its warm-up keeps computing for the post-search proof.
            warm(p, pool, emptyList(), flowOf(result(1_500))).toList()
            gate.entered.await()
            assertThat(MostMasteriesBoundCache.inFlightForTest(p)).isTrue()
            // A front-end that will not ask for that proof (the user stopped or declined it) stops it.
            WakfuBestBuildFinderAlgorithm.cancelBackgroundProofs()
            awaitUntil { gate.sawCancel.get() && !MostMasteriesBoundCache.inFlightForTest(p) }
            assertThat(MostMasteriesBoundCache.isCachedForTest(p)).describedAs("a cancelled compute caches nothing").isFalse()
            // Idempotent, and a no-op when nothing runs.
            WakfuBestBuildFinderAlgorithm.cancelBackgroundProofs()
            WakfuBestBuildFinderAlgorithm.cancelBackgroundProofs()
        }

    @Test
    fun `a proof that may not continue is a peek - it answers from the memo and never computes or joins`(): Unit =
        runBlocking {
            val p = params(291)
            val gate = Gate(fakeBound(2_000))
            MostMasteriesBoundCache.certificateForTest = gate.certificate()
            val before = computes()
            val peek = { r: SolverResult<BuildCombination> -> WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(p, r) { false } }

            // Nothing memoized: no verdict, and no compute is started.
            assertThat(peek(result(1_000))).isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable)
            assertThat(computes() - before).describedAs("a peek never starts a compute").isEqualTo(0)
            assertThat(MostMasteriesBoundCache.inFlightForTest(p)).isFalse()

            // A compute in flight (the search's tail warm-up): a peek does not wait for it either.
            val finish = CompletableDeferred<Unit>()
            val search = async(Dispatchers.Default) { warm(p, pool, emptyList(), heldSearch(result(1_000), finish, result(1_500))).toList() }
            gate.entered.await()
            val started = computes()
            val whileComputing = async(Dispatchers.Default) { peek(result(1_000)) }
            assertThat(withTimeout(5.seconds) { whileComputing.await() }).isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable)
            assertThat(computes()).describedAs("no second compute beside the in-flight one").isEqualTo(started)
            assertThat(MostMasteriesBoundCache.inFlightForTest(p)).describedAs("the peek left the in-flight compute alone").isTrue()
            finish.complete(Unit)
            search.await()

            // Once the bound is memoized, the peek returns the same verdict a normal proof would, with no new compute.
            gate.release.countDown()
            awaitUntil { MostMasteriesBoundCache.isCachedForTest(p) }
            val settled = computes()
            assertThat(peek(result(1_000))).isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenWithin(1.0))
            assertThat(peek(result(2_000))).isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal)
            assertThat(computes()).isEqualTo(settled)
        }

    @Test
    fun `requests the certificate cannot bound start no warm-up`(): Unit =
        runBlocking {
            MostMasteriesBoundCache.certificateForTest = { _, _, _ -> error("an ineligible request must never compute") }
            val before = computes()
            val ineligible =
                listOf(
                    params(211, forcedItems = listOf("helmet")),
                    params(212, targets = listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 9999))),
                    params(213, targets = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.DODGE, 100))),
                    params(214, targets = listOf(TargetStat(Characteristic.MOVEMENT_POINT, 4)))
                )
            for (p in ineligible) {
                warm(p, pool, emptyList(), flowOf(result(1_500))).toList()
                assertThat(MostMasteriesBoundCache.inFlightForTest(p)).isFalse()
            }
            assertThat(computes() - before).isEqualTo(0)
        }

    @Test
    fun `an all-resistances-0 request is a floor, not a multi-element one - it warms and computes its quality bound`(): Unit =
        runBlocking {
            val p =
                params(
                    1,
                    targets = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 0))
                )
            // "All resistances 0" only holds each element at 0 or more (TargetStats.resistanceFloorElements): it wants no element,
            // so the search runs on the full pool (no heuristic prefilter) and the bound — a relaxation of the floors — applies.
            assertThat(MostMasteriesCertificate.supportsRequest(p, emptyList())).isTrue()
            assertThat(WakfuBuildSolver.needsItemPrefilter(p.targetStats)).isFalse()
            MostMasteriesBoundCache.certificateForTest = { _, _, _ -> fakeBound(2_000) }
            val before = computes()
            warm(p, pool, emptyList(), flowOf(result(1_000))).toList()
            assertThat(MostMasteriesBoundCache.bound(p, pool)).isNotNull
            assertThat(computes() - before).describedAs("one warm-up, reused by the proof").isEqualTo(1)
        }

    @Test
    fun `prefiltered requests compute no quality bound, even when asked directly`(): Unit =
        runBlocking {
            // Two wanted mastery elements: the search uses the heuristic prefilter, so no bound may ever be computed for it.
            val p =
                params(
                    2,
                    targets =
                        listOf(
                            TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1),
                            TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 1),
                            TargetStat(Characteristic.MOVEMENT_POINT, 4)
                        )
                )
            assertThat(WakfuBuildSolver.needsItemPrefilter(p.targetStats)).isTrue()
            MostMasteriesBoundCache.certificateForTest = { _, _, _ -> fakeBound(2_000) }
            val before = computes()
            val search =
                flow {
                    // With zero start delay a warm-up attaches synchronously before collection:
                    // a wrongly admitted compute is either in flight or already cached, regardless of scheduling.
                    assertThat(MostMasteriesBoundCache.inFlightForTest(p)).isFalse()
                    assertThat(MostMasteriesBoundCache.isCachedForTest(p)).isFalse()
                    emit(result(1_000))
                }
            warm(p, pool, emptyList(), search).toList()
            assertThat(computes() - before).describedAs("no prefiltered-request warm-up").isZero()
            assertThat(MostMasteriesBoundCache.bound(p, pool)).isNull()
            assertThat(computes() - before).describedAs("no post-search bound either").isZero()
        }

    /**
     * Parity of the request support gate with the certificate: every request [MostMasteriesCertificate.supportsRequest]
     * rejects makes [MostMasteriesCertificate.bound] bail, and the supported ones bound (one-item pool).
     */
    @Test
    fun `supportsRequest mirrors the certificate's request-level bails`() {
        val subs = WakfuBestBuildFinderAlgorithm.sublimations
        val shapes =
            listOf(
                params(220),
                params(221, targets = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999))),
                params(222).copy(useSublimations = true, useRunes = true),
                params(223, forcedItems = listOf("helmet")),
                params(224).copy(forcedRunes = listOf("rune")),
                params(225).copy(forcedSublimations = listOf("sub")),
                params(226).copy(forcedRunesByItem = mapOf("helmet" to listOf(1))),
                params(227, targets = listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 9999))),
                params(228, targets = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.RANGE, 4))),
                params(229, targets = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 100))),
                params(230, targets = listOf(TargetStat(Characteristic.MOVEMENT_POINT, 4))),
                // The GUI's default rows (most-masteries): RANGE + the 0-valued wind-resistance / dodge rows.
                params(
                    231,
                    targets =
                        listOf(
                            TargetStat(Characteristic.ACTION_POINT, 11),
                            TargetStat(Characteristic.MOVEMENT_POINT, 4),
                            TargetStat(Characteristic.RANGE, 4),
                            TargetStat(Characteristic.CRITICAL_HIT, 25),
                            TargetStat(Characteristic.MASTERY_DISTANCE, 1),
                            TargetStat(Characteristic.HP, 2000),
                            TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WIND, 0),
                            TargetStat(Characteristic.DODGE, 0)
                        )
                ).copy(useSublimations = true, useRunes = true),
                params(232, targets = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.DODGE, 100)))
            )
        for (p in shapes) {
            val supported = MostMasteriesCertificate.supportsRequest(p, subs)
            val bound = MostMasteriesCertificate.bound(p, pool, WakfuBestBuildFinderAlgorithm.runes, subs)
            assertThat(bound != null).describedAs("level ${p.character.level}: supportsRequest=$supported").isEqualTo(supported)
        }
    }

    private fun synthSub(
        id: Int,
        rarity: SublimationRarity = SublimationRarity.NORMAL,
        condition: SublimationCondition? = null,
        effects: List<SublimationEffect>,
    ) = Sublimation(
        stateId = id,
        name = I18nText("syn$id", "syn$id", "", ""),
        rarity = rarity,
        maxStackLevel = 1,
        kind = if (condition == null) SublimationKind.FLAT else SublimationKind.STATIC_CONDITIONAL,
        solverChoosable = true,
        condition = condition,
        effects = effects
    )

    private fun capped(
        type: SublimationConditionType,
        value: Int,
    ) = SublimationCondition(type, value = value)

    /**
     * Every REQUEST-level bail of the certificate, triggered on SYNTHETIC sublimation catalogs, through both entries:
     * [MostMasteriesCertificate.supportsRequest] (the warm-up gate) and [MostMasteriesCertificate.bound] (one-item pool)
     * must agree with each other AND with the expected verdict — each bail is proven to fire, and its control (the
     * same shape one step inside the limit) to stay supported.
     */
    @Test
    fun `supportsRequest and bound share every request-level bail on synthetic catalogs`() {
        val epic = SublimationRarity.EPIC
        val dist = TargetStat(Characteristic.MASTERY_DISTANCE, 9999)

        fun subsParams(
            level: Int,
            targets: List<TargetStat> = listOf(dist, TargetStat(Characteristic.MOVEMENT_POINT, 4)),
        ) = params(level, targets).copy(useSublimations = true)

        fun conversion(to: Characteristic) = SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, to, 100)

        fun flat(
            c: Characteristic,
            v: Int,
        ) = SublimationEffect.Flat(c, v)

        val mpDiRamp = SublimationEffect.PerStatStep(Characteristic.MOVEMENT_POINT, 4, 6, 24, Characteristic.DAMAGE_INFLICTED)
        val cases: List<Triple<String, Pair<WakfuBestBuildParams, List<Sublimation>>, Boolean>> =
            listOf(
                Triple("no sub", subsParams(250) to emptyList(), true),
                // A conversion into a stat the DP reads (its moved value rides no option), or into an AP / MP the
                // final-stat upper cannot bound.
                Triple("conversion into AP", subsParams(251) to listOf(synthSub(9301, effects = listOf(conversion(Characteristic.ACTION_POINT)))), false),
                Triple("conversion into MP", subsParams(252) to listOf(synthSub(9302, effects = listOf(conversion(Characteristic.MOVEMENT_POINT)))), false),
                Triple("conversion into the requested mastery", subsParams(253) to listOf(synthSub(9303, effects = listOf(conversion(Characteristic.MASTERY_DISTANCE)))), false),
                Triple("conversion into DI", subsParams(254) to listOf(synthSub(9304, effects = listOf(conversion(Characteristic.DAMAGE_INFLICTED)))), false),
                Triple(
                    "conversion into a tracked CC",
                    subsParams(255, listOf(dist, TargetStat(Characteristic.CRITICAL_HIT, 30))) to listOf(synthSub(9305, effects = listOf(conversion(Characteristic.CRITICAL_HIT)))),
                    false
                ),
                Triple("conversion into an untracked CC", subsParams(256) to listOf(synthSub(9306, effects = listOf(conversion(Characteristic.CRITICAL_HIT)))), true),
                // The 5-bit AP / MP fields: the dims saturate at min(target, final-stat upper).
                Triple(
                    "AP field overflow",
                    subsParams(257, listOf(dist, TargetStat(Characteristic.ACTION_POINT, 35))) to listOf(synthSub(9307, effects = listOf(flat(Characteristic.ACTION_POINT, 20)))),
                    false
                ),
                Triple("AP 35 capped by its upper", subsParams(258, listOf(dist, TargetStat(Characteristic.ACTION_POINT, 35))) to emptyList(), true),
                Triple(
                    "MP field overflow",
                    subsParams(259, listOf(dist, TargetStat(Characteristic.MOVEMENT_POINT, 32))) to
                        listOf(synthSub(9308, effects = listOf(flat(Characteristic.MOVEMENT_POINT, 30)))),
                    false
                ),
                // The world split: at most 6 cap subs, all EPIC.
                Triple(
                    "7 cap subs",
                    subsParams(260) to
                        (1..7).map { synthSub(9310 + it, epic, capped(SublimationConditionType.AP_AT_MOST, 9 + it), listOf(flat(Characteristic.DAMAGE_INFLICTED, 10))) },
                    false
                ),
                Triple(
                    "6 cap subs",
                    subsParams(261) to
                        (1..6).map { synthSub(9320 + it, epic, capped(SublimationConditionType.AP_AT_MOST, 9 + it), listOf(flat(Characteristic.DAMAGE_INFLICTED, 10))) },
                    true
                ),
                Triple(
                    "a non-EPIC cap sub",
                    subsParams(262) to
                        listOf(synthSub(9330, condition = capped(SublimationConditionType.CRIT_AT_MOST, 10), effects = listOf(flat(Characteristic.DAMAGE_INFLICTED, 10)))),
                    false
                ),
                // One tracked MP→DI ramp (a state bit), never two.
                Triple("two MP→DI ramps", subsParams(263) to listOf(synthSub(9331, effects = listOf(mpDiRamp)), synthSub(9332, effects = listOf(mpDiRamp))), false),
                Triple("one MP→DI ramp", subsParams(264) to listOf(synthSub(9333, effects = listOf(mpDiRamp))), true),
                // World B folds every subset of the objective-capping subs: at most 6.
                Triple(
                    "7 world-B subs",
                    subsParams(265) to
                        (1..7).map {
                            synthSub(
                                9340 + it,
                                condition = capped(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, it),
                                effects = listOf(flat(Characteristic.CRITICAL_HIT, 5))
                            )
                        },
                    false
                ),
                Triple(
                    "6 world-B subs",
                    subsParams(266) to
                        (1..6).map {
                            synthSub(
                                9350 + it,
                                condition = capped(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, it),
                                effects = listOf(flat(Characteristic.CRITICAL_HIT, 5))
                            )
                        },
                    true
                ),
                // The block field (4 bits of 5-block buckets).
                Triple(
                    "block field overflow",
                    subsParams(267) to listOf(synthSub(9360, epic, capped(SublimationConditionType.BLOCK_AT_LEAST, 80), listOf(flat(Characteristic.DAMAGE_INFLICTED, 10)))),
                    false
                ),
                Triple(
                    "block 75",
                    subsParams(268) to listOf(synthSub(9361, epic, capped(SublimationConditionType.BLOCK_AT_LEAST, 75), listOf(flat(Characteristic.DAMAGE_INFLICTED, 10)))),
                    true
                ),
                // Request-only shapes: one stat required twice, CC / HP targets past their fields.
                Triple(
                    "AP required twice",
                    subsParams(269, listOf(dist, TargetStat(Characteristic.ACTION_POINT, 8), TargetStat(Characteristic.ACTION_POINT, 10))) to emptyList(),
                    false
                ),
                Triple("CC field overflow", subsParams(270, listOf(dist, TargetStat(Characteristic.CRITICAL_HIT, 1_300))) to emptyList(), false),
                Triple("HP field overflow", subsParams(271, listOf(dist, TargetStat(Characteristic.HP, 300_000))) to emptyList(), false),
                // Plan §8.20: RANGE is tracked (5 bits), and a 0-valued row of any stat is an exact skip.
                Triple("RANGE target", subsParams(272, listOf(dist, TargetStat(Characteristic.RANGE, 4))) to emptyList(), true),
                Triple("RANGE field overflow", subsParams(273, listOf(dist, TargetStat(Characteristic.RANGE, 40))) to emptyList(), false),
                Triple(
                    "conversion into a tracked RANGE",
                    subsParams(274, listOf(dist, TargetStat(Characteristic.RANGE, 4))) to listOf(synthSub(9370, effects = listOf(conversion(Characteristic.RANGE)))),
                    false
                ),
                Triple("conversion into an untracked RANGE", subsParams(275) to listOf(synthSub(9371, effects = listOf(conversion(Characteristic.RANGE)))), true),
                Triple(
                    "0-valued rows of untracked stats",
                    subsParams(
                        276,
                        listOf(dist, TargetStat(Characteristic.MOVEMENT_POINT, 4), TargetStat(Characteristic.DODGE, 0), TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WIND, 0))
                    ) to
                        emptyList(),
                    true
                ),
                Triple("only 0-valued required rows", subsParams(277, listOf(dist, TargetStat(Characteristic.DODGE, 0))) to emptyList(), true),
                Triple("a non-zero untracked row", subsParams(278, listOf(dist, TargetStat(Characteristic.DODGE, 50))) to emptyList(), false),
                // CERTIFIER_VERSION 51: a negative capped-stat line on a sub no assume world stages into its LOW dim — the
                // assumed cap sub itself or a world-B sub — would reject a real carrier (A1's over-rejection).
                Triple(
                    "a cap sub's own negative crit line",
                    subsParams(279) to
                        listOf(
                            synthSub(
                                9380,
                                epic,
                                capped(SublimationConditionType.CRIT_AT_MOST, 10),
                                listOf(flat(Characteristic.DAMAGE_INFLICTED, 20), flat(Characteristic.CRITICAL_HIT, -5))
                            )
                        ),
                    false
                ),
                Triple(
                    "a world-B sub's negative crit line beside a crit cap sub",
                    subsParams(280) to
                        listOf(
                            synthSub(9381, epic, capped(SublimationConditionType.CRIT_AT_MOST, 10), listOf(flat(Characteristic.DAMAGE_INFLICTED, 20))),
                            synthSub(9382, condition = capped(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, 0), effects = listOf(flat(Characteristic.CRITICAL_HIT, -5)))
                        ),
                    false
                ),
                Triple(
                    "a world-B sub's negative MAX_AP line beside an AP cap sub",
                    subsParams(281) to
                        listOf(
                            synthSub(9383, epic, capped(SublimationConditionType.AP_AT_MOST, 10), listOf(flat(Characteristic.DAMAGE_INFLICTED, 15))),
                            synthSub(
                                9384,
                                condition = capped(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, 0),
                                effects = listOf(flat(Characteristic.DAMAGE_INFLICTED, 10), flat(Characteristic.MAX_ACTION_POINT, -1))
                            )
                        ),
                    false
                ),
                Triple(
                    "a world-B sub's negative crit line without a cap sub",
                    subsParams(282) to
                        listOf(
                            synthSub(9385, condition = capped(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, 0), effects = listOf(flat(Characteristic.CRITICAL_HIT, -5)))
                        ),
                    true
                ),
                Triple(
                    "a world-B sub's negative AP line beside a crit cap sub (no AP LOW dim)",
                    subsParams(283) to
                        listOf(
                            synthSub(9386, epic, capped(SublimationConditionType.CRIT_AT_MOST, 10), listOf(flat(Characteristic.DAMAGE_INFLICTED, 20))),
                            synthSub(
                                9387,
                                condition = capped(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, 0),
                                effects = listOf(flat(Characteristic.DAMAGE_INFLICTED, 10), flat(Characteristic.ACTION_POINT, -1))
                            )
                        ),
                    true
                ),
                // CERTIFIER_VERSION 51: a POSITIVE MAX_* AP / MP line is folded by the solver, by no AP / MP read here.
                Triple("a positive MAX_AP sub line", subsParams(284) to listOf(synthSub(9388, effects = listOf(flat(Characteristic.MAX_ACTION_POINT, 1)))), false),
                Triple("a positive MAX_MP sub line", subsParams(285) to listOf(synthSub(9389, effects = listOf(flat(Characteristic.MAX_MOVEMENT_POINT, 1)))), false),
                Triple(
                    "a negative MAX_MP sub line (Armure lourde's shape)",
                    subsParams(286) to
                        listOf(synthSub(9390, effects = listOf(flat(Characteristic.DAMAGE_INFLICTED, 10), flat(Characteristic.MAX_MOVEMENT_POINT, -1)))),
                    true
                )
            )
        val mismatches = mutableListOf<String>()
        for ((label, shape, expected) in cases) {
            val (p, subs) = shape
            val supported = MostMasteriesCertificate.supportsRequest(p, subs)
            val bound = MostMasteriesCertificate.bound(p, pool, WakfuBestBuildFinderAlgorithm.runes, subs)
            if (supported != expected || (bound != null) != expected) mismatches += "$label: expected $expected, supportsRequest=$supported bound=${bound != null}"
        }
        assertThat(mismatches).describedAs("every request-level bail must fire through BOTH entries").isEmpty()
    }

    @Test
    fun `compare reads the folded bound with required targets, the core without, and self-checks`() {
        val withTargets = params(240)
        val coreOnly = params(241, targets = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)))
        val bound = MostMasteriesCertificate.Result(foldedBound = 2_000, coreBound = 1_100, states = 1, wallMs = 0)
        val compare = WakfuBestBuildFinderAlgorithm::compareMostMasteriesQuality
        assertThat(compare(withTargets, bound, result(1_000))).isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenWithin(1.0))
        assertThat(compare(withTargets, bound, result(2_000))).isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal)
        assertThat(compare(withTargets, bound, result(2_001)))
            .describedAs("an incumbent above the bound = an under-count: no badge")
            .isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable)
        assertThat(compare(coreOnly, bound, result(1_000))).isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenWithin(1_100.0 / 1_000 - 1))
        assertThat(compare(withTargets, bound, result(5_000, optimal = true))).isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal)
        assertThat(compare(withTargets, bound, result(null))).isEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable)
    }

    /**
     * End to end on a one-item pool, real certificate + real hard-leg solve: the bound the warm-up memoizes is exactly
     * the bound a direct computation gives, and the one-pass proof compares against it. (The proof's on-demand compute
     * rebuilds the PRODUCTION pool from the request, so it is covered by the [MostMasteriesBoundCache.bound] tests
     * above instead.)
     */
    @Test
    fun `the warm-up bound equals the direct certificate and the proof reads it`(): Unit =
        runBlocking {
            val p = params(242)
            val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
            val before = computes()
            val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 30.0)
            // This one-item hard leg proves at once: the result is downgraded to "un-proven" so the proof path runs.
            val final =
                warm(
                    p,
                    pool,
                    subs,
                    flow {
                        WakfuBuildSolver
                            .optimize(p, pool, WakfuBestBuildFinderAlgorithm.runes, subs, tuning, hardConstraints = true)
                            .collect { emit(it.copy(isOptimal = false)) }
                    }
                ).toList()
                    .last()
            val proof = WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(p, final)
            assertThat(computes() - before).describedAs("one compute, started by the warm-up").isEqualTo(1)
            val direct =
                requireNotNull(
                    MostMasteriesCertificate.bound(
                        p,
                        WakfuBuildSolver.filterDominatedPoolMemoizedForTest(pool, requireNotNull(dominationShape(p, subs))),
                        WakfuBestBuildFinderAlgorithm.runes,
                        subs
                    )
                )
            val memoized = requireNotNull(MostMasteriesBoundCache.bound(p))
            assertThat(memoized.foldedBound).isEqualTo(direct.foldedBound)
            assertThat(memoized.coreBound).isEqualTo(direct.coreBound)
            assertThat(proof).isEqualTo(WakfuBestBuildFinderAlgorithm.compareMostMasteriesQuality(p, direct, final))
            assertThat(proof).isNotEqualTo(WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable)
        }
}
