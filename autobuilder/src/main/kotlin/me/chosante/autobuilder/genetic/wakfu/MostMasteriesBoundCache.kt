package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Sublimation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * E10-for-MM (docs/MOST_MASTERIES_PERF_PLAN.md §8.19): the most-masteries quality bound
 * ([MostMasteriesCertificate.bound], full tier) is INCUMBENT-FREE — a pure function of the request and its dominated
 * pool — so it is computed in the TAIL of the search instead of after it, and the post-search badge
 * ([WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality]) only compares the result against it.
 *
 * - **Schedule** ([warmupStartDelay]): 30 s before the search's budget runs out, never in its first 30 s — the bound
 *   beside the steep early phase measurably slowed the search; in the tail it costs little and is ready at search
 *   end. A search that proves OPTIMAL before the start never runs it.
 * - **Memo**: one finished bound per request ([keyFor]: the params minus the fields the bound never reads, plus the
 *   data + certifier versions — a [WakfuBuildSolver.CERTIFIER_VERSION] bump invalidates every entry); a re-run of the
 *   same request reads its badge instantly. A bail (null bound) is cached too: it is a pure function of the key.
 * - **Single-flight**: at most one compute per key ([Flight]); the post-search proof WAITS for the warm-up's
 *   in-flight compute (cancellation-responsive) instead of duplicating it, and starts one on demand when none ran (a
 *   short budget, an ineligible shape, a cancelled warm-up, a direct caller).
 * - **Threads**: every compute runs off its caller's thread, and its DP stage advance uses ONE thread while any
 *   most-masteries search is collecting (CP-SAT owns cores − 1 workers), the full chunk-worker count once none is —
 *   so a bound still computing at search end speeds up for the waiting proof. A pure work knob: the bound value is
 *   identical at any worker count.
 * - **Cancellation**: a new search of ANY request supersedes every other request's in-flight compute; the warm-up of
 *   a search that ends PROVEN OPTIMAL or without a comparable objective (no proof will ask), or is cancelled mid-flight
 *   (new search, user cancel, window close), is cancelled with it. A NORMALLY completed un-proven search leaves its
 *   warm-up running — the proof joins it (E10's rule). A cancelled compute caches nothing.
 */
internal object MostMasteriesBoundCache {
    private val logger = KotlinLogging.logger {}

    /**
     * The warm-up never starts in its search's first [WARMUP_EARLIEST]. Measured on the 4-core laptop profile (§8.19):
     * the bound beside the steep early phase measurably slows it — CP-SAT loses ~15% of its throughput while the
     * one-thread bound runs (race-free 1-worker protocol), and on S2 the incumbent reached ~60T after 32-40 s instead of
     * 17-20 s when the bound ran from the start.
     */
    private val WARMUP_EARLIEST: Duration = 30.seconds

    /** Otherwise the warm-up starts this long before its search's budget runs out (S2 bound beside the search: ~20-22 s). */
    private val WARMUP_LEAD: Duration = 30.seconds

    /**
     * When the warm-up of a search with this [budget] starts, measured from the search's start: [WARMUP_LEAD] before
     * the budget runs out, but never in the first [WARMUP_EARLIEST] — so the overlap lands in the search's low-yield
     * tail, a search that proves OPTIMAL before then never runs it, and the bound is ready when the search ends. Null =
     * no room after the early phase: the post-search proof computes the bound on demand, at full speed.
     */
    internal fun warmupStartDelay(budget: Duration): Duration? = maxOf(WARMUP_EARLIEST, budget - WARMUP_LEAD).takeIf { it < budget }

    /** How often a waiting caller re-polls its own cancellation while a compute runs. */
    private const val WAIT_POLL_MS = 100L

    /** A session runs a handful of distinct requests; each entry is a few numbers. */
    private const val MAX_ENTRIES = 16

    /** The request minus the fields the bound never reads, plus the versions it was computed under. */
    private data class Key(
        val dataVersion: String,
        val certifierVersion: Int,
        val params: WakfuBestBuildParams,
    )

    /** A finished compute — [bound] null = the certificate bailed on this request. */
    private class Entry(
        val bound: MostMasteriesCertificate.Result?,
    )

    /** One in-flight compute: [cancelled] stops its DP within a stage; waiters block on [done]. */
    private class Flight(
        val key: Key,
    ) {
        val cancelled = AtomicBoolean(false)
        val done = CountDownLatch(1)
    }

    private val entries =
        object : LinkedHashMap<Key, Entry>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean = size > MAX_ENTRIES
        }
    private val inFlight = ConcurrentHashMap<Key, Flight>()

    /** Most-masteries searches currently collecting — the stage-advance throttle reads it. */
    private val runningSearches = AtomicInteger()
    private val computeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Test seam: how many bound COMPUTES started (memo hits and single-flight waits don't count). */
    internal val computeCountForTest = AtomicLong()

    /**
     * Test seam: replaces the certificate call — receives the request, the compute's continue predicate and its
     * stage-worker provider — so tests drive timing, cancellation and the throttle without a real DP. Null = the real
     * [MostMasteriesCertificate.bound].
     */
    @Volatile
    internal var certificateForTest: ((WakfuBestBuildParams, () -> Boolean, () -> Int) -> MostMasteriesCertificate.Result?)? = null

    /** Test seam: empties the memo (in-flight computes keep running). */
    internal fun clearForTest() = synchronized(entries) { entries.clear() }

    /** Test seam: true while a compute for [params] is in flight. */
    internal fun inFlightForTest(params: WakfuBestBuildParams): Boolean = inFlight.containsKey(keyFor(params))

    /** Test seam: true when the memo holds [params]' finished compute (a bound or a bail). */
    internal fun isCachedForTest(params: WakfuBestBuildParams): Boolean = cached(keyFor(params)) != null

    private fun keyFor(params: WakfuBestBuildParams): Key =
        Key(
            dataVersion = me.chosante.common.WakfuData.VERSION,
            certifierVersion = WakfuBuildSolver.CERTIFIER_VERSION,
            // Normalize what the bound never reads, so a re-run with another budget / worker count still hits.
            params =
                params.copy(
                    searchDuration = Duration.ZERO,
                    stopWhenBuildMatch = false,
                    maxDamageApTarget = null,
                    maxDamageMpPin = null,
                    solverWorkers = null
                )
        )

    private fun cached(key: Key): Entry? = synchronized(entries) { entries[key] }

    private fun stageWorkers(): Int = if (runningSearches.get() > 0) 1 else LongLongMaxMap.defaultWorkers()

    /**
     * The memoized single-flight bound for [params] (null = the certificate bails on this request, or [shouldContinue]
     * turned false first). A memoized bound returns at once; an in-flight compute (typically the search-time warm-up)
     * is waited for; otherwise this call starts the compute — over [basePool] (the search's own filtered pool, so its
     * domination memo is shared) or the request's pool rebuilt from [params] — and cancels it again when it stops
     * waiting for a compute it started itself.
     */
    fun bound(
        params: WakfuBestBuildParams,
        basePool: Map<ItemType, List<Equipment>>? = null,
        shouldContinue: () -> Boolean = { true },
    ): MostMasteriesCertificate.Result? {
        val key = keyFor(params)
        while (true) {
            cached(key)?.let { return it.bound }
            if (!shouldContinue()) return null
            val (flight, started) = ensureFlight(key, params, basePool) ?: continue
            while (!flight.done.await(WAIT_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (!shouldContinue()) {
                    if (started) flight.cancelled.set(true)
                    return null
                }
            }
            // Done: a finished compute left its entry (read on the next turn); a cancelled one left none, and this
            // caller starts its own.
        }
    }

    /**
     * The live in-flight compute for [key], started here when none runs (`second` = started by this call); null when
     * the memo already holds the key (the caller re-reads it). A compute that was cancelled but is still winding down
     * is replaced, not joined — it would finish without a bound.
     */
    private fun ensureFlight(
        key: Key,
        params: WakfuBestBuildParams,
        basePool: Map<ItemType, List<Equipment>>?,
    ): Pair<Flight, Boolean>? {
        while (true) {
            val existing = inFlight[key]
            if (existing != null && !existing.cancelled.get()) return existing to false
            val fresh = Flight(key)
            val claimed = if (existing == null) inFlight.putIfAbsent(key, fresh) == null else inFlight.replace(key, existing, fresh)
            if (!claimed) continue // another caller claimed the slot first: re-read it
            // A compute may have finished between the caller's memo read and this claim.
            if (cached(key) != null) {
                inFlight.remove(key, fresh)
                fresh.done.countDown()
                return null
            }
            computeScope.launch { compute(fresh, params, basePool) }
            return fresh to true
        }
    }

    private fun compute(
        flight: Flight,
        params: WakfuBestBuildParams,
        basePool: Map<ItemType, List<Equipment>>?,
    ) {
        computeCountForTest.incrementAndGet()
        try {
            val live = { !flight.cancelled.get() }
            val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(params)
            val fake = certificateForTest
            // The same filtered + dominated pool the production solve searched: the bound then upper-bounds the
            // exact optimum OF THAT SEARCH (domination is optimum-preserving). No domination shape ⇒ a bail.
            val bound =
                if (fake != null) {
                    fake(params, live, ::stageWorkers)
                } else {
                    dominationShape(params, subs)?.let { shape ->
                        val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool ?: WakfuBestBuildFinderAlgorithm.poolFor(params), shape)
                        MostMasteriesCertificate.bound(
                            params,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            subs,
                            shouldContinue = live,
                            parallelism = ::stageWorkers
                        )
                    }
                }
            // bound() returns null on cancellation, never a truncated bound: a non-null result is always complete,
            // and a null only counts as a (cacheable) bail when the compute was never cancelled.
            if (bound != null || live()) synchronized(entries) { entries[flight.key] = Entry(bound) }
        } catch (throwable: Throwable) {
            // Cache the failure as "no bound": a retry would fail the same way, and every waiter must be released.
            logger.error(throwable) { "Most-masteries quality bound failed (badge withheld; the search is unaffected)." }
            synchronized(entries) { entries[flight.key] = Entry(null) }
        } finally {
            inFlight.remove(flight.key, flight)
            flight.done.countDown()
        }
    }

    /** Only requests the certificate can bound get a warm-up: a guaranteed bail would burn a core for nothing. */
    private fun boundable(
        params: WakfuBestBuildParams,
        sublimations: List<Sublimation>,
    ): Boolean =
        params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
            MostMasteriesCertificate.supportsRequest(params, sublimations) &&
            dominationShape(params, sublimations) != null

    /** Cancels every in-flight compute but [keep]'s: a new search makes other requests' bounds moot. */
    private fun supersede(keep: Key?) {
        for ((key, flight) in inFlight) if (key != keep) flight.cancelled.set(true)
    }

    /** A production search of another mode is starting: every in-flight bound is moot ([supersede]). */
    fun supersedeAll() = supersede(keep = null)

    /**
     * Wraps the production most-masteries [search] flow of [params]: on collection it supersedes other requests'
     * bounds and schedules this request's bound beside the search ([startDelay] after it starts, see
     * [warmupStartDelay]; null = not during the search) when the certificate can bound the shape; on completion it
     * keeps that compute only when the post-search proof will ask for it (a normally completed, un-proven result with
     * a comparable objective). [basePool] is the search's own filtered pool, [sublimations] its active catalog.
     */
    fun withSearchTimeWarmup(
        params: WakfuBestBuildParams,
        basePool: Map<ItemType, List<Equipment>>,
        sublimations: List<Sublimation>,
        search: Flow<SolverResult<BuildCombination>>,
        startDelay: Duration? = warmupStartDelay(params.searchDuration),
    ): Flow<SolverResult<BuildCombination>> =
        flow {
            val key = keyFor(params)
            supersede(keep = key)
            runningSearches.incrementAndGet()
            val warmup = if (startDelay != null && boundable(params, sublimations)) Warmup(key, params, basePool, startDelay) else null
            var last: SolverResult<BuildCombination>? = null
            var completed = false
            try {
                search.collect {
                    last = it
                    emit(it)
                }
                completed = true
            } finally {
                runningSearches.decrementAndGet()
                val final = last
                warmup?.searchEnded(needed = completed && final != null && !final.isOptimal && final.mostMasteriesObjective != null)
            }
        }

    /** One search's warm-up: starts (or adopts) the request's compute, and decides its fate when the search ends. */
    private class Warmup(
        key: Key,
        params: WakfuBestBuildParams,
        basePool: Map<ItemType, List<Equipment>>,
        startDelay: Duration,
    ) {
        private val lock = Any()
        private var flight: Flight? = null
        private var ended = false
        private val pending: Job?

        init {
            pending =
                if (startDelay <= Duration.ZERO) {
                    attach(key, params, basePool)
                    null
                } else {
                    computeScope.launch {
                        delay(startDelay)
                        attach(key, params, basePool)
                    }
                }
        }

        private fun attach(
            key: Key,
            params: WakfuBestBuildParams,
            basePool: Map<ItemType, List<Equipment>>,
        ) = synchronized(lock) {
            // Never START after the search ended: the proof then computes on demand at full speed.
            if (!ended) flight = ensureFlight(key, params, basePool)?.first
        }

        fun searchEnded(needed: Boolean) =
            synchronized(lock) {
                ended = true
                pending?.cancel()
                // Not needed ⇒ stop the compute (an adopted one too: its own search is over, nobody else asks).
                if (!needed) flight?.cancelled?.set(true)
            }
    }
}
