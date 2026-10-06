package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.WakfuData
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * docs/perf-next-steps-2026-10.md — E0 "lean baseline": the USER-VISIBLE milestones of the
 * PRODUCTION path (`tuning == null`, wall-clock budget) on a handful of representative requests.
 * Mimic a 4-core laptop by restricting the JVM: `WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4"`
 * (solver workers = cores − 1 = 3, certifier threads, DP chunk workers all follow), and the shipped
 * GUI heap `WAKFU_TEST_MAX_HEAP=3g`.
 *
 * ```shell
 * WAKFU_E0=1 WAKFU_E0_FIXTURES=MM110,MM245,MD110,MD245,MD110F,MD245F,S2,S4 WAKFU_E0_SECONDS=120 \
 *   WAKFU_TEST_MAX_HEAP=3g WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4" \
 *   ./gradlew --no-daemon :autobuilder:test --tests '*PerfBaselineE0Test*' --rerun
 * ```
 *
 * Knobs (all env): `WAKFU_E0_FIXTURES` (default = the eight v38 fixtures; `SAC230` = the production request of the
 * known minutes-long refinement shape, and `SAC230R` = that shape routed into the soft-leg branch the way
 * `MaxDamageSoftCertificateTest` does — no search, see [runSoftRouted] — the free face requests `MD80MF` /
 * `MD200MF` / `MD230DF` (level, melee / distance) and `MM110L` / `MM110LB` (`MM110` plus "lock 0": a floor the relaxed optimum
 * breaks, then one that binds) only run when named; `WAKFU_E0_ORACLE_WORKERS`
 * sets the oracle's CP-SAT workers, 8), `WAKFU_E0_SECONDS` (search budget, 120; a comma list
 * runs every fixture once per budget, ids get an `@<n>s` suffix — a short budget exercises the post-search badge
 * path of searches CP-SAT leaves un-proven), `WAKFU_E0_REPS` (repeat everything n times in the same JVM, `#k`
 * suffix), `WAKFU_E0_RUNES_SUBS=0` (runes + sublimations OFF), `WAKFU_E0_EXCLUDE_SUBS` (French names of sublimations
 * every fixture excludes), `WAKFU_E0_REFINE=1` (run the silent refinement after
 * a failed E8 construct) with `WAKFU_E0_REFINE_CAP_S` (1500 = 25 min), `WAKFU_E0_PROVE_CAP_S` (600) and
 * `WAKFU_E0_CONSTRUCT_CAP_S` (300), `WAKFU_E0_LOG=/path` (every `E0 …` line is also appended + flushed there, so a
 * run can be followed live instead of reading the JUnit XML at the end).
 *
 * Milestones per fixture (ms from the search start): first emission, last improvement, flow end (isOptimal), then
 * the post-search chain exactly as `BuildSearchModel` (gui-compose) runs it after a search:
 *  - **max-damage**: `proveMaxDamageOptimality` → on ProvenWithin the badge is published AT ONCE and
 *    `constructMaxDamageProvenOptimum` (E8: gated by `isFreeMaxDamageShape`, wall-capped at 60 s) runs behind it →
 *    failing that, the silent `refineMaxDamageOptimality`;
 *  - **most-masteries**: the quality bound is computed in the search's TAIL (`MostMasteriesBoundCache`, plan §8.19:
 *    one full-tier pass started [MostMasteriesBoundCache.warmupStartDelay] after the search starts), so the harness
 *    polls the cache to timestamp when that warm-up starts / lands, then asks `proveMostMasteriesQuality` for the
 *    verdict and times the call (the badge delay after the search end). Only when CP-SAT ended non-OPTIMAL, like
 *    the GUI.
 * Every fixture starts with cold certificate caches (a session's first request of that shape). Output: `E0 …`
 * lines in the JUnit XML (and in `WAKFU_E0_LOG`).
 */
class PerfBaselineE0Test {
    private data class Fixture(
        val id: String,
        val params: WakfuBestBuildParams,
        /** True ⇒ [runSoftRouted] (no search): the soft-leg proof chain is reached by stamping the oracle optimum on an empty build. */
        val softRouted: Boolean = false,
    )

    private class Config(
        val seconds: Long,
        val runesAndSubs: Boolean,
        val refine: Boolean,
        val refineCapMs: Long,
        val proveCapMs: Long,
        val constructCapMs: Long,
    )

    private val logFile: File? = System.getenv("WAKFU_E0_LOG")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun log(line: String) {
        synchronized(this) {
            println(line)
            logFile?.appendText(line + "\n")
        }
    }

    private fun guiTargets(): List<TargetStat> =
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

    private fun frontierTargets(): List<TargetStat> =
        listOf(
            TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
            TargetStat(Characteristic.ACTION_POINT, 16),
            TargetStat(Characteristic.MOVEMENT_POINT, 8),
            TargetStat(Characteristic.CRITICAL_HIT, 100),
            TargetStat(Characteristic.HP, 12000)
        )

    private fun params(
        level: Int,
        mode: ScoreComputationMode,
        targets: List<TargetStat>,
        cfg: Config,
        clazz: CharacterClass = CharacterClass.CRA,
    ) = WakfuBestBuildParams(
        character = Character(clazz, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = cfg.seconds.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = mode,
        useRunes = cfg.runesAndSubs,
        useSublimations = cfg.runesAndSubs
    )

    private fun fixtures(cfg: Config): List<Fixture> {
        val mm = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
        val md = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
        return listOf(
            // The GUI's default request (CRA 110, most-masteries, default target rows).
            Fixture("MM110", params(110, mm, guiTargets(), cfg)),
            Fixture("MM245", params(245, mm, guiTargets(), cfg)),
            // The same rows in max-damage mode (AP/MP/range/crit/HP become hard targets).
            Fixture("MD110", params(110, md, guiTargets(), cfg)),
            Fixture("MD245", params(245, md, guiTargets(), cfg)),
            // The free max-damage flagship (certificate proof authority, no required targets).
            Fixture("MD110F", params(110, md, listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)), cfg)),
            Fixture("MD245F", params(245, md, listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)), cfg)),
            // Unreachable targets ⇒ the most-masteries SOFT fallback + the quality certificate (S2).
            Fixture("S2", params(245, mm, frontierTargets(), cfg)),
            // The same in max-damage ⇒ the soft-leg certificate (S4).
            Fixture("S4", params(245, md, frontierTargets().drop(1), cfg))
        )
    }

    /** Fixtures outside the v38 table — they only run when named in `WAKFU_E0_FIXTURES`. */
    private fun extraFixtures(cfg: Config): List<Fixture> {
        val mm = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
        val md = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
        val sacrieur230 =
            params(
                230,
                md,
                listOf(TargetStat(Characteristic.ACTION_POINT, 16), TargetStat(Characteristic.MOVEMENT_POINT, 8)),
                cfg,
                CharacterClass.SACRIEUR
            )

        // Free FACE requests (CRA fire, the row is the band's maximized mastery) — the rune-collapse review's fixtures
        // (docs/RUNE_CHOICE_COLLAPSE_FIX.md): their optima carry secondary runes beside the Neutralité family.
        fun freeFace(
            level: Int,
            band: RangeBand,
        ) = params(level, md, listOf(TargetStat(band.masteryCharacteristic, 1)), cfg)
            .copy(damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = band, orientation = Orientation.FACE))
        return listOf(
            // The GUI's default request plus "lock 0" (WakfuBuildSolver.relaxThenCheck): the best build without the floors has lock
            // −140 (Visibilité II), yet another build reaches the same optimum with every floor kept — the floor does not bind.
            Fixture("MM110L", params(110, mm, guiTargets() + TargetStat(Characteristic.LOCK, 0), cfg)),
            // ...and with the sublimations that lift dodge or lock excluded, it BINDS: the floored optimum is 1782 against the
            // relaxed 1787 — relax-then-check's slow path, its floored stage searching past the relaxed optimum.
            Fixture(
                "MM110LB",
                params(110, mm, guiTargets() + TargetStat(Characteristic.LOCK, 0), cfg).copy(
                    excludedSublimations =
                        listOf(
                            "Evasion III",
                            "Interception III",
                            "Combat rapproché II",
                            "Force Herculéenne",
                            "Furie",
                            "Esquive Berserk III",
                            "Tacle Berserk III"
                        )
                )
            ),
            // The production request of MaxDamageSoftCertificateTest `sacrieur230-apmp`: on data 1.93 its hard leg
            // meets the targets, so the post-search chain is the hard-leg ledger and the refinement never applies.
            Fixture("SAC230", sacrieur230),
            // The same shape routed into the soft-leg branch (the known minutes-long silent refinement:
            // ProvenOptimal after ~19.6 min on 10 cores in v36) — see [runSoftRouted].
            Fixture("SAC230R", sacrieur230, softRouted = true),
            Fixture("MD80MF", freeFace(80, RangeBand.MELEE)),
            Fixture("MD200MF", freeFace(200, RangeBand.MELEE)),
            Fixture("MD230DF", freeFace(230, RangeBand.DISTANCE))
        )
    }

    @Test
    @Tag("manual")
    fun `manual E0 production baseline`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_E0") == "1")
            // One budget (the GUI default, 120 s) or a comma list: each budget re-runs every selected fixture, whose id
            // then carries an `@<n>s` suffix. Shorter budgets exercise the post-search badge path of searches CP-SAT
            // leaves un-proven (a 120 s search of a reachable request often ends proven on 4 cores).
            val budgets =
                System
                    .getenv("WAKFU_E0_SECONDS")
                    ?.split(',')
                    ?.mapNotNull { it.trim().toLongOrNull() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf(120L)
            val baseCfg =
                Config(
                    seconds = budgets.first(),
                    runesAndSubs = System.getenv("WAKFU_E0_RUNES_SUBS") != "0",
                    refine = System.getenv("WAKFU_E0_REFINE") == "1",
                    refineCapMs = (System.getenv("WAKFU_E0_REFINE_CAP_S")?.toLongOrNull() ?: 1500L) * 1000,
                    proveCapMs = (System.getenv("WAKFU_E0_PROVE_CAP_S")?.toLongOrNull() ?: 600L) * 1000,
                    constructCapMs = (System.getenv("WAKFU_E0_CONSTRUCT_CAP_S")?.toLongOrNull() ?: 300L) * 1000
                )
            val selected =
                System
                    .getenv("WAKFU_E0_FIXTURES")
                    ?.split(',')
                    ?.map { it.trim().uppercase() }
                    ?.toSet()
            val rt = Runtime.getRuntime()
            log(
                "E0 ENV cores=${rt.availableProcessors()} maxHeapMb=${rt.maxMemory() / 1_048_576} budgetsS=$budgets " +
                    "runesAndSubs=${baseCfg.runesAndSubs} refine=${baseCfg.refine} refineCapS=${baseCfg.refineCapMs / 1000} " +
                    "certifierVersion=${WakfuBuildSolver.CERTIFIER_VERSION} dataVersion=${WakfuData.VERSION}"
            )
            // First engine touch of the JVM, exactly the GUI's loading-screen call (natives already cached on disk).
            val w0 = System.nanoTime()
            WakfuBuildSolver.warmUp()
            log("E0 WARMUP ms=${ms(w0)}")
            // WAKFU_E0_REPS=n repeats every (budget, fixture) n times in the same JVM (ids get a `#k` suffix): CP-SAT's
            // multi-worker proof time is heavy-tailed, one run says little about it.
            val reps = System.getenv("WAKFU_E0_REPS")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            for (rep in 1..reps) {
                for (seconds in budgets) {
                    val cfg = Config(seconds, baseCfg.runesAndSubs, baseCfg.refine, baseCfg.refineCapMs, baseCfg.proveCapMs, baseCfg.constructCapMs)
                    log("E0 ENV2 rep=$rep budgetS=$seconds mmBoundWarmupStartMs=${MostMasteriesBoundCache.warmupStartDelay(seconds.seconds)?.inWholeMilliseconds}")
                    // WAKFU_E0_EXCLUDE_SUBS=<French names, comma-separated>: the request's excluded sublimations (a world
                    // control, e.g. the Neutralité family out of the catalog).
                    val excludedSubs =
                        System
                            .getenv("WAKFU_E0_EXCLUDE_SUBS")
                            ?.split(',')
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            .orEmpty()
                    val chosen =
                        if (selected == null) {
                            fixtures(cfg)
                        } else {
                            (fixtures(cfg) + extraFixtures(cfg)).filter { it.id in selected }
                        }.map { it.copy(params = it.params.copy(excludedSublimations = excludedSubs)) }
                    for (fx in chosen) {
                        val suffix = (if (budgets.size > 1) "@${seconds}s" else "") + (if (reps > 1) "#$rep" else "")
                        runFixture(fx.copy(id = fx.id + suffix), cfg)
                    }
                }
            }
        }

    private fun runFixture(
        fx: Fixture,
        cfg: Config,
    ) {
        if (fx.softRouted) return runSoftRouted(fx, cfg)
        // A session's FIRST request of this shape: no memoized bound, no cached ledger from an earlier fixture.
        MostMasteriesBoundCache.clearForTest()
        MaxDamageCertificateCache.clear()
        MaxDamageSearch.warmupJobForTest.set(null)
        val mode = fx.params.scoreComputationMode
        val isMm = mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
        val isMd = mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE

        val t0 = System.nanoTime()
        // The search-tail / E10 warm-up timeline (ms from the search start): MM = the quality bound's compute
        // (in flight → memoized); MD = the certificate warm-up job (started on the first incumbent → completed).
        val warmStartMs = AtomicLong(-1)
        val warmDoneMs = AtomicLong(-1)
        val stopMonitor = AtomicBoolean(false)
        val monitor =
            thread(name = "e0-monitor", isDaemon = true) {
                while (!stopMonitor.get()) {
                    val now = ms(t0)
                    if (isMm) {
                        if (warmStartMs.get() < 0 && MostMasteriesBoundCache.inFlightForTest(fx.params)) warmStartMs.set(now)
                        if (warmDoneMs.get() < 0 && MostMasteriesBoundCache.isCachedForTest(fx.params)) warmDoneMs.set(now)
                    } else if (isMd) {
                        val job = MaxDamageSearch.warmupJobForTest.get()
                        if (job != null) {
                            if (warmStartMs.get() < 0) warmStartMs.set(now)
                            if (warmDoneMs.get() < 0 && job.isCompleted) warmDoneMs.set(now)
                        }
                    }
                    Thread.sleep(25)
                }
            }

        var firstMs: Long? = null
        var lastImproveMs = 0L
        var lastObjImproveMs = 0L
        var lastObjective: Long? = null
        var emissions = 0
        var last: SolverResult<BuildCombination>? = null
        var lastScore: BigDecimal? = null
        runBlocking {
            WakfuBestBuildFinderAlgorithm.run(fx.params).collect { r ->
                val t = ms(t0)
                emissions++
                if (firstMs == null) firstMs = t
                if (lastScore == null || r.matchPercentage > lastScore) {
                    lastImproveMs = t
                    lastScore = r.matchPercentage
                }
                val objective = r.mostMasteriesObjective ?: r.maxDamageRawProxy ?: r.maxDamageObjective
                if (objective != null && (lastObjective == null || objective > lastObjective!!)) {
                    lastObjImproveMs = t
                    lastObjective = objective
                }
                last = r
                log(
                    "E0 EMIT fx=${fx.id} tMs=$t score=${r.matchPercentage} optimal=${r.isOptimal} " +
                        "mdProxy=${r.maxDamageRawProxy} mmObj=${r.mostMasteriesObjective} greedy=${r.greedyWarmStartEmission}"
                )
            }
        }
        val endNanos = System.nanoTime()
        val endMs = (endNanos - t0) / 1_000_000
        val final = checkNotNull(last) { "${fx.id}: no emission" }
        val runeTypes =
            final.individual.runes.values
                .flatten()
                .groupingBy { it.characteristic }
                .eachCount()
        log(
            "E0 SEARCH fx=${fx.id} firstMs=$firstMs lastImproveMs=$lastImproveMs lastObjImproveMs=$lastObjImproveMs endMs=$endMs " +
                "optimal=${final.isOptimal} score=${final.matchPercentage} emissions=$emissions mdProxy=${final.maxDamageRawProxy} " +
                "mmObj=${final.mostMasteriesObjective} hardMet=${final.maxDamageHardConstraintsMet} mmHardMet=${final.mostMasteriesHardConstraintsMet} " +
                "subs=${final.individual.sublimations.values.flatten().map { it.name.fr }} runes=$runeTypes"
        )
        // Milestones for the one-line summary (all relative to the search END, null = not reached).
        var badge = "none"
        var badgeAfterEndMs: Long? = null
        var provenOptimalAfterEndMs: Long? = if (final.isOptimal) 0L else null
        var e8 = "n/a"
        var refinement = "n/a"
        when {
            isMd -> {
                val chain = maxDamageChain(fx, cfg, final, endNanos)
                badge = chain.badge
                badgeAfterEndMs = chain.badgeAfterEndMs
                e8 = chain.e8
                refinement = chain.refinement
                provenOptimalAfterEndMs = chain.provenOptimalAfterEndMs
            }
            isMm -> {
                if (!final.isOptimal && final.mostMasteriesObjective != null) {
                    val q0 = System.nanoTime()
                    val verdict = WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality(fx.params, final)
                    val badgeMs = ms(q0)
                    if (warmDoneMs.get() < 0 && MostMasteriesBoundCache.isCachedForTest(fx.params)) warmDoneMs.set(ms(t0))
                    badge = describeMm(verdict)
                    badgeAfterEndMs = badgeMs
                    if (verdict == WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal) provenOptimalAfterEndMs = badgeMs
                    log(
                        "E0 PROOF fx=${fx.id} kind=mmBadge verdict=$badge afterEndMs=$badgeMs searchEndMs=$endMs " +
                            "warmStartMs=${warmStartMs.get()} warmLandedMs=${warmDoneMs.get()} " +
                            "boundReadyRelEndMs=${if (warmDoneMs.get() < 0) "n/a" else (warmDoneMs.get() - endMs).toString()}"
                    )
                } else {
                    log("E0 PROOF fx=${fx.id} kind=none reason=${if (final.isOptimal) "cpsatOptimal" else "notComparable"}")
                }
            }
        }
        stopMonitor.set(true)
        monitor.join()
        log(
            "E0 SUMMARY fx=${fx.id} firstMs=$firstMs lastImproveMs=$lastImproveMs endMs=$endMs cpsatProven=${final.isOptimal} " +
                "badge=$badge badgeAfterEndMs=${badgeAfterEndMs ?: "n/a"} e8=$e8 refine=$refinement " +
                "provenOptimalAfterEndMs=${provenOptimalAfterEndMs ?: "not reached"} warmStartMs=${warmStartMs.get()} warmDoneMs=${warmDoneMs.get()}"
        )
    }

    /** Outcome of the post-search max-damage chain; every time is ms after the search end (null = not reached). */
    private class MdChain(
        val badge: String,
        val badgeAfterEndMs: Long?,
        val e8: String,
        val refinement: String,
        val provenOptimalAfterEndMs: Long?,
    )

    /**
     * What `BuildSearchModel.launchOptimalityProof` runs once a max-damage search is over (the search end is
     * [endNanos]): the certificate verdict, then — behind a ProvenWithin badge — the E8 construct, then the silent
     * refinement of a target-missing (soft-leg) result.
     */
    private fun maxDamageChain(
        fx: Fixture,
        cfg: Config,
        final: SolverResult<BuildCombination>,
        endNanos: Long,
    ): MdChain {
        fun sinceEnd() = ms(endNanos)

        var provenOptimalAfterEndMs: Long? = if (final.isOptimal) 0L else null
        var e8 = "n/a"
        var refinement = "n/a"
        val p0 = System.nanoTime()
        val proofDeadline = p0 + cfg.proveCapMs * 1_000_000
        val proof =
            WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(
                fx.params,
                final,
                isCancelled = { System.nanoTime() >= proofDeadline },
                onPhase = { key -> log("E0 PHASE fx=${fx.id} key=$key sinceEndMs=${sinceEnd()}") }
            )
        val proveMs = ms(p0)
        log("E0 PROOF fx=${fx.id} kind=prove verdict=${describe(proof)} ms=$proveMs sinceEndMs=${sinceEnd()} capHit=${System.nanoTime() >= proofDeadline}")
        if (proof == MaxDamageSearch.MaxDamageProof.ProvenOptimal && provenOptimalAfterEndMs == null) provenOptimalAfterEndMs = sinceEnd()
        if (proof is MaxDamageSearch.MaxDamageProof.ProvenWithin) {
            // GUI order: the "within X %" badge is published first; the E8 construct runs behind it.
            val c0 = System.nanoTime()
            val constructDeadline = c0 + cfg.constructCapMs * 1_000_000
            val constructed =
                WakfuBestBuildFinderAlgorithm.constructMaxDamageProvenOptimum(
                    fx.params,
                    final,
                    isCancelled = { System.nanoTime() >= constructDeadline }
                )
            val constructMs = ms(c0)
            e8 = if (constructed != null) "constructed(${constructMs}ms)" else "none(${constructMs}ms)"
            log(
                "E0 PROOF fx=${fx.id} kind=construct success=${constructed != null} ms=$constructMs sinceEndMs=${sinceEnd()} " +
                    "constructedProxy=${constructed?.maxDamageRawProxy} gate=${isFreeMaxDamageShape(fx.params.targetStats)}"
            )
            if (constructed != null) {
                provenOptimalAfterEndMs = sinceEnd()
            } else if (cfg.refine) {
                val r0 = System.nanoTime()
                val refineDeadline = r0 + cfg.refineCapMs * 1_000_000
                val refined =
                    WakfuBestBuildFinderAlgorithm.refineMaxDamageOptimality(
                        fx.params,
                        final,
                        isCancelled = { System.nanoTime() >= refineDeadline },
                        onPhase = { key -> log("E0 PHASE fx=${fx.id} key=$key sinceEndMs=${sinceEnd()}") }
                    )
                val refineMs = ms(r0)
                val capHit = System.nanoTime() >= refineDeadline
                refinement =
                    if (refined != null) {
                        "${describe(refined)} (${refineMs}ms)"
                    } else if (capHit) {
                        "not reached by the ${cfg.refineCapMs / 1000}s cap"
                    } else {
                        "n/a or no improvement (${refineMs}ms)"
                    }
                if (refined == MaxDamageSearch.MaxDamageProof.ProvenOptimal) provenOptimalAfterEndMs = sinceEnd()
                log("E0 PROOF fx=${fx.id} kind=refine verdict=${refined?.let { describe(it) }} ms=$refineMs sinceEndMs=${sinceEnd()} capHit=$capHit")
            }
        }
        return MdChain(describe(proof), proveMs, e8, refinement, provenOptimalAfterEndMs)
    }

    /**
     * The routing of `MaxDamageSoftCertificateTest` `manual S4 production soft proof end-to-end`. With data 1.93 the
     * sacrieur-230 AP 16 / MP 8 request is REACHABLE — the production hard leg meets it (fixture `SAC230`: hardMet), so
     * the soft-leg proof and its minutes-long silent refinement never run for it. They are reproduced the way that
     * test reaches them: the no-condition CP-SAT oracle's proven optimum stands for "the search found the optimum",
     * stamped on an EMPTY build (which misses every target) so `proveMaxDamageOptimality` takes the soft-leg branch.
     * The oracle solve stands for the search and is NOT part of the timed chain (clock zero = the search end).
     */
    private fun runSoftRouted(
        fx: Fixture,
        cfg: Config,
    ) {
        MaxDamageCertificateCache.clear()
        val level = fx.params.character.level
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val o0 = System.nanoTime()
        val oracle =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params = fx.params,
                equipmentsByItemType = pool,
                runes = WakfuBestBuildFinderAlgorithm.runes,
                sublimations = WakfuBestBuildFinderAlgorithm.sublimations.filter { it.condition == null },
                workers = System.getenv("WAKFU_E0_ORACLE_WORKERS")?.toIntOrNull() ?: 8,
                seconds = 600.0,
                applyDomination = true
            )
        log("E0 ORACLE fx=${fx.id} status=${oracle.status} objective=${oracle.objective} wallMs=${ms(o0)} (stands for the search; not timed)")
        check(oracle.status == "OPTIMAL") { "${fx.id}: the no-condition oracle must prove its optimum (got ${oracle.status})" }
        val routed =
            SolverResult(
                individual = BuildCombination(emptyList(), CharacterSkills(level)),
                matchPercentage = BigDecimal.ZERO,
                progressPercentage = 100,
                isOptimal = false,
                maxDamageObjective = oracle.objective,
                maxDamageHardConstraintsMet = false
            )
        val endNanos = System.nanoTime()
        val chain = maxDamageChain(fx, cfg, routed, endNanos)
        log(
            "E0 SUMMARY fx=${fx.id} firstMs=n/a lastImproveMs=n/a endMs=0 cpsatProven=false badge=${chain.badge} " +
                "badgeAfterEndMs=${chain.badgeAfterEndMs ?: "n/a"} e8=${chain.e8} refine=${chain.refinement} " +
                "provenOptimalAfterEndMs=${chain.provenOptimalAfterEndMs ?: "not reached"} warmStartMs=-1 warmDoneMs=-1"
        )
    }

    private fun describe(p: MaxDamageSearch.MaxDamageProof): String =
        when (p) {
            MaxDamageSearch.MaxDamageProof.ProvenOptimal -> "ProvenOptimal"
            is MaxDamageSearch.MaxDamageProof.ProvenWithin -> "ProvenWithin(${"%.4f".format(Locale.ROOT, p.fraction * 100)}%)"
            MaxDamageSearch.MaxDamageProof.Unavailable -> "Unavailable"
        }

    private fun describeMm(p: WakfuBestBuildFinderAlgorithm.MostMasteriesProof): String =
        when (p) {
            WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenOptimal -> "ProvenOptimal"
            is WakfuBestBuildFinderAlgorithm.MostMasteriesProof.ProvenWithin -> "ProvenWithin(${"%.4f".format(Locale.ROOT, p.percent * 100)}%)"
            WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable -> "Unavailable"
        }

    private fun ms(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000
}
