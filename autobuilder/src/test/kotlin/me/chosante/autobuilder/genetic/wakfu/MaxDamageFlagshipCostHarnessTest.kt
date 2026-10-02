package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * MANUAL measurement harness — what the max-damage certificate costs the FREE flagship request it runs beside
 * (CRA, runes + subs, max rarity EPIC, only the distance-mastery row: E0's MD110F / MD245F). Env-gated, prints `FLAG_*`
 * lines into the JUnit XML. Uses only APIs that predate CERTIFIER_VERSION 44, so the same file runs against older
 * certifier sources for a before/after comparison.
 *
 * ```shell
 * # production timeline: search (E10 warm-up beside it), when the warm-up ledger lands, the post-search badge
 * # (4-core laptop profile; drop WAKFU_TEST_JVM_ARGS for the full machine's certificate early stop):
 * WAKFU_FLAG_PROD=1 WAKFU_FLAG_LEVELS=245,110 WAKFU_FLAG_SECONDS=120 WAKFU_TEST_MAX_HEAP=3g \
 *   WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4" \
 *   ./gradlew --no-daemon :autobuilder:test --tests '*MaxDamageFlagshipCostHarnessTest*' --rerun
 * # race-free CP-SAT throughput: a 1-worker deterministic solve alone vs beside the serial warm-up ledger of each
 * # variant (normalOnly = the pre-v44 work, v44 = the always-split aux schedule, full = the shipped one):
 * WAKFU_FLAG_CONTENTION=1 WAKFU_FLAG_CONT_LEVEL=245 WAKFU_FLAG_DET=160 WAKFU_FLAG_CONT_VARIANTS=normalOnly,v44,full WAKFU_TEST_MAX_HEAP=3g \
 *   WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4" \
 *   ./gradlew --no-daemon :autobuilder:test --tests '*MaxDamageFlagshipCostHarnessTest*' --rerun
 * ```
 */
class MaxDamageFlagshipCostHarnessTest {
    private fun params(
        level: Int,
        seconds: Long,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))),
        searchDuration = seconds.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        useRunes = true,
        useSublimations = true
    )

    private fun ms(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

    private fun describe(proof: MaxDamageSearch.MaxDamageProof): String =
        when (proof) {
            MaxDamageSearch.MaxDamageProof.ProvenOptimal -> "ProvenOptimal"
            is MaxDamageSearch.MaxDamageProof.ProvenWithin -> "ProvenWithin(${"%.4f".format(Locale.ROOT, proof.fraction * 100)}%)"
            MaxDamageSearch.MaxDamageProof.Unavailable -> "Unavailable"
        }

    private fun env() =
        "cores=${Runtime.getRuntime().availableProcessors()} maxHeapMb=${Runtime.getRuntime().maxMemory() / 1_048_576} " +
            "certifierVersion=${WakfuBuildSolver.CERTIFIER_VERSION} fastWorldThreads=${WakfuBuildSolver.certifierFastWorldThreads()} " +
            "tier15Threads=${WakfuBuildSolver.certifierTier15Threads()} defaultThreads=${WakfuBuildSolver.certifierDefaultThreads()}"

    @Test
    fun `manual flagship production timeline`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_FLAG_PROD") == "1")
            val seconds = System.getenv("WAKFU_FLAG_SECONDS")?.toLongOrNull() ?: 120L
            val reps = System.getenv("WAKFU_FLAG_REPS")?.toIntOrNull() ?: 1
            val levels = (System.getenv("WAKFU_FLAG_LEVELS") ?: "245,110").split(',').map { it.trim().toInt() }
            WakfuBuildSolver.warmUp()
            println("FLAG_ENV ${env()}")
            for (lvl in levels) {
                repeat(reps) { rep ->
                    MaxDamageCertificateCache.clear()
                    val p = params(lvl, seconds)
                    val previousJob = MaxDamageSearch.warmupJobForTest.get()
                    val t0 = System.nanoTime()
                    // The E10 warm-up job is published on the first streamed incumbent; its completion = the ledger landed
                    // (plus the in-search E8 construct attempt, which only runs while the search is live).
                    val warmupDoneMs = AtomicLong(-1)
                    val watcher =
                        launch(Dispatchers.Default) {
                            var job: Job? = null
                            while (job == null) {
                                val j = MaxDamageSearch.warmupJobForTest.get()
                                if (j != null && j !== previousJob) job = j else delay(20)
                            }
                            job.join()
                            warmupDoneMs.set(ms(t0))
                        }
                    var firstMs = -1L
                    var lastImproveMs = 0L
                    var lastScore: java.math.BigDecimal? = null
                    var last: SolverResult<BuildCombination>? = null
                    WakfuBestBuildFinderAlgorithm.run(p).collect { r ->
                        if (firstMs < 0) firstMs = ms(t0)
                        val prev = lastScore
                        if (prev == null || r.matchPercentage > prev) {
                            lastScore = r.matchPercentage
                            lastImproveMs = ms(t0)
                        }
                        last = r
                    }
                    val searchMs = ms(t0)
                    val final = checkNotNull(last)
                    println(
                        "FLAG_SEARCH level=$lvl rep=$rep firstMs=$firstMs lastImproveMs=$lastImproveMs searchMs=$searchMs " +
                            "optimal=${final.isOptimal} proxy=${final.maxDamageRawProxy} warmupDoneMsAtSearchEnd=${warmupDoneMs.get()}"
                    )
                    val p0 = System.nanoTime()
                    val proof = WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(p, final)
                    println("FLAG_PROOF level=$lvl rep=$rep proof=${describe(proof)} afterSearchEndMs=${ms(p0)} sinceStartMs=${ms(t0)}")
                    if (proof is MaxDamageSearch.MaxDamageProof.ProvenWithin) {
                        val c0 = System.nanoTime()
                        val constructed = WakfuBestBuildFinderAlgorithm.constructMaxDamageProvenOptimum(p, final)
                        println("FLAG_CONSTRUCT level=$lvl rep=$rep success=${constructed != null} ms=${ms(c0)} proxy=${constructed?.maxDamageRawProxy}")
                    }
                    withTimeoutOrNull(10.minutes) { watcher.join() } ?: watcher.cancel()
                    val done = warmupDoneMs.get()
                    println("FLAG_WARMUP level=$lvl rep=$rep warmupDoneMs=$done relativeToSearchEndMs=${if (done < 0) "n/a" else (done - searchMs).toString()}")
                }
            }
        }

    @Test
    fun `manual flagship ledger contention`() {
        assumeTrue(System.getenv("WAKFU_FLAG_CONTENTION") == "1")
        val lvl = System.getenv("WAKFU_FLAG_CONT_LEVEL")?.toIntOrNull() ?: 245
        val det = System.getenv("WAKFU_FLAG_DET")?.toDoubleOrNull() ?: 30.0
        // E0's 4-core MD245F final incumbent: the warm-up eliminates against the search's latest proxy.
        val incumbent = System.getenv("WAKFU_FLAG_INCUMBENT")?.toLongOrNull() ?: 20_475_270L
        val p = params(lvl, 600)
        // The production pool for this request (groupAndFilterEquipments: EPIC cap, level ≤ lvl, level-free companions).
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { ((it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS) && !it.levelRestricted) || it.level <= lvl }
                .groupBy { it.itemType }
        val runes = WakfuBestBuildFinderAlgorithm.runes
        val subs = WakfuBestBuildFinderAlgorithm.sublimations
        WakfuBuildSolver.warmUp()
        println("FLAG_ENV ${env()}")

        // The search-time warm-up ledger: 1 thread throughout (the provider E10 passes while the search runs),
        // cascade tier-1.5, eliminating against the incumbent.
        // Ledger variants, compared inside ONE JVM (same thermal state): "full" = the shipped schedule; "v44" = the same
        // catalog on the v44 always-split aux schedule (8 eager aux worlds); "normalOnly" = the catalog minus the two sub
        // families the normal certifier worlds drop (the Neutralité family and the EPIC/RELIC block subs) — no aux world
        // runs, so it is exactly the pre-v44 ledger's work on this shape.
        val variants = (System.getenv("WAKFU_FLAG_CONT_VARIANTS") ?: "full").split(',').map { it.trim() }

        fun subsFor(variant: String) =
            if (variant == "normalOnly") {
                subs.filterNot { s ->
                    val cond = s.condition
                    (cond?.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST && (cond.value ?: 0) <= 0) ||
                        (cond?.type == SublimationConditionType.BLOCK_AT_LEAST && s.rarity != SublimationRarity.NORMAL)
                }
            } else {
                subs
            }

        fun ledger(
            variant: String,
            cancel: AtomicBoolean,
        ): Pair<CertLedger?, Long> {
            val t0 = System.nanoTime()
            val wasRelaxed = CertifierTuning.auxRelaxedCappedEnabled
            CertifierTuning.auxRelaxedCappedEnabled = variant != "v44"
            try {
                val l =
                    WakfuBuildSolver.maxDamageCertificate(
                        p,
                        pool,
                        runes,
                        subsFor(variant),
                        applyDomination = true,
                        incumbentObjective = incumbent,
                        threads = 1,
                        threadsProvider = { 1 },
                        cascadeTier15 = true,
                        isCancelled = { cancel.get() }
                    )
                return l to ms(t0)
            } finally {
                CertifierTuning.auxRelaxedCappedEnabled = wasRelaxed
            }
        }

        fun solve(label: String): Long {
            val t0 = System.nanoTime()
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    p,
                    pool,
                    runes,
                    subs,
                    workers = 1,
                    seconds = 3600.0,
                    applyDomination = true,
                    deterministicLimit = det
                )
            val wall = ms(t0)
            println("FLAG_SOLVE level=$lvl det=$det run=$label wallMs=$wall status=${profile.status} objective=${profile.objective}")
            return wall
        }

        for (variant in variants) {
            val (alone, ledgerAloneMs) = ledger(variant, AtomicBoolean(false))
            println("FLAG_LEDGER_ALONE level=$lvl variant=$variant threads=1 ms=$ledgerAloneMs maxCell=${alone?.maxCellObjective} bailed=${alone?.bailedCells}")
        }
        val alone1 = solve("alone1")
        val beside = LinkedHashMap<String, Triple<Long, Long, Boolean>>()
        for (variant in variants) {
            val cancel = AtomicBoolean(false)
            val concurrentMs = AtomicLong(-1)
            val ledgerThread = thread(name = "flag-ledger-$variant") { concurrentMs.set(ledger(variant, cancel).second) }
            val wall = solve("beside-$variant")
            val outlived = ledgerThread.isAlive
            cancel.set(true) // only the overlap with the solve matters
            ledgerThread.join()
            beside[variant] = Triple(wall, concurrentMs.get(), outlived)
        }
        val alone2 = solve("alone2")
        val reference = (alone1 + alone2) / 2.0
        for ((variant, r) in beside) {
            println(
                "FLAG_CONTENTION level=$lvl det=$det variant=$variant alone1Ms=$alone1 alone2Ms=$alone2 besideLedgerMs=${r.first} " +
                    "slowdownPct=${"%.1f".format(Locale.ROOT, (r.first / reference - 1) * 100)} ledgerRanMs=${r.second} ledgerOutlivedSolve=${r.third}"
            )
        }
    }
}
