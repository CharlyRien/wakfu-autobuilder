package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.WakfuData
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * MANUAL measurement harnesses of the TARGET-AWARE max-damage certificate (CERTIFIER_VERSION 52,
 * `docs/CERTIFICATE_PROD_PLAN.md` §P5.6) — default OFF, env-gated, `@Tag("manual")`. Every line is prefixed `TA …` and
 * lands in the JUnit XML (and in `WAKFU_TA_LOG` when set). The soundness locks live in `MaxDamageTargetAwareCertificateTest`
 * (CI); its seeded fuzz replays any seeds with `WAKFU_TA_FUZZ_SEEDS=a,b,…`.
 *
 * 1. Where the GUI-default request's badge comes from (a long production search, the build's row stats, the free request):
 * ```shell
 * WAKFU_TA_DECOMP=1 WAKFU_TA_LEVELS=110,200,245 WAKFU_TA_SECONDS=420 WAKFU_TA_FREE_SECONDS=90 WAKFU_TA_LOG=/path \
 *   WAKFU_TEST_MAX_HEAP=6g ./gradlew --no-daemon :autobuilder:test --tests '*MaxDamageTargetAwareHarnessTest*decomposition*' --rerun
 * ```
 * 2. The production-shaped proof of that request, target-blind (`off`), rows without the range dim (`filters`) and the
 *    shipped arm (`range`), against a real targets-met incumbent; certificate wall time and sampled peak heap per arm:
 * ```shell
 * WAKFU_TA_BOUND=1 WAKFU_TA_LEVELS=110,200,245 WAKFU_TA_INCUMBENTS=110=1495770,200=10507040,245=19242720 WAKFU_TA_ARMS=off,filters,range \
 *   [WAKFU_TA_RATCHET=1] [WAKFU_TA_FAST_ONLY=1] [WAKFU_TA_AUX=1] WAKFU_TA_LOG=/path WAKFU_TEST_MAX_HEAP=3g \
 *   WAKFU_TEST_JVM_ARGS=-XX:ActiveProcessorCount=4 ./gradlew --no-daemon :autobuilder:test --tests '*MaxDamageTargetAwareHarnessTest*bound*' --rerun
 * ```
 *    Threads follow the production formula (`certifierDefaultThreads` + the per-tier providers) unless `WAKFU_TA_THREADS`
 *    pins them. `WAKFU_TA_RATCHET=1` re-runs the proof with a fake incumbent one below the argmax of the tightest bound seen
 *    so far until that argmax is exact-confirmed — the IDEAL maximum (every survivor refined). `WAKFU_TA_AUX=1` prints the
 *    aux worlds' share of the fast ledger next to the normal worlds' (does the aux floor bind once the rows tighten?). The
 *    incumbents are the 420 s, 10-core targets-met results of harness 1 (110 / 245: the research memo's; 200: 2026-10-03).
 */
class MaxDamageTargetAwareHarnessTest {
    private val logFile: File? = System.getenv("WAKFU_TA_LOG")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun log(line: String) {
        synchronized(this) {
            println(line)
            logFile?.appendText(line + "\n")
        }
    }

    private fun guiRows(): List<TargetStat> =
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

    private fun freeRows(): List<TargetStat> = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))

    private fun params(
        level: Int,
        targets: List<TargetStat>,
        seconds: Long,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = seconds.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        useRunes = true,
        useSublimations = true
    )

    private fun envList(name: String): List<Int>? =
        System
            .getenv(name)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() }

    /** The build's actual stats on the rows the GUI-default request constrains (the scorer's arithmetic). */
    private fun rowStats(
        p: WakfuBestBuildParams,
        build: BuildCombination,
    ): String {
        val stats =
            computeCharacteristicsValues(
                buildCombination = build,
                characterBaseCharacteristics = p.character.baseCharacteristicValues,
                masteryElementsWanted = mapOf(p.damageScenario.element.masteryCharacteristic to 1),
                resistanceElementsWanted = p.targetStats.resistanceElementsWanted,
                scoreComputationMode = p.scoreComputationMode,
                damageScenario = p.damageScenario
            )
        return listOf(
            Characteristic.ACTION_POINT,
            Characteristic.MOVEMENT_POINT,
            Characteristic.RANGE,
            Characteristic.CRITICAL_HIT,
            Characteristic.HP,
            Characteristic.DODGE,
            Characteristic.RESISTANCE_ELEMENTARY_WIND
        ).joinToString(" ") { "${it.name}=${stats[it]}" }
    }

    private fun describe(build: BuildCombination): String =
        "items=${build.equipments.map { "${it.itemType}:${it.name.fr}" }} subs=${build.sublimations.values.flatten().map { it.name.en }} " +
            "runes=${build.runes.values.flatten().groupingBy { it.characteristic }.eachCount()} " +
            "skills=${build.characterSkills.allCharacteristic.filter { it.pointsAssigned > 0 }.map { "${it.name}:${it.pointsAssigned}" }}"

    private fun verdict(proof: MaxDamageSearch.MaxDamageProof): String =
        when (proof) {
            MaxDamageSearch.MaxDamageProof.ProvenOptimal -> "ProvenOptimal"
            is MaxDamageSearch.MaxDamageProof.ProvenWithin -> "ProvenWithin(${"%.4f".format(Locale.ROOT, proof.fraction * 100)}%)"
            MaxDamageSearch.MaxDamageProof.Unavailable -> "Unavailable"
        }

    private fun clearCaches() {
        MostMasteriesBoundCache.clearForTest()
        MaxDamageCertificateCache.clear()
        MaxDamageSearch.warmupJobForTest.set(null)
    }

    /** Production search; logs every objective improvement; returns the final emission. */
    private fun search(
        tag: String,
        p: WakfuBestBuildParams,
    ): SolverResult<BuildCombination> {
        clearCaches()
        val t0 = System.nanoTime()
        var last: SolverResult<BuildCombination>? = null
        var lastProxy: Long? = null
        runBlocking {
            WakfuBestBuildFinderAlgorithm.run(p).collect { r ->
                val proxy = r.maxDamageRawProxy
                if (proxy != null && (lastProxy == null || proxy > lastProxy!!)) {
                    lastProxy = proxy
                    log("TA EMIT $tag tMs=${(System.nanoTime() - t0) / 1_000_000} proxy=$proxy hardMet=${r.maxDamageHardConstraintsMet} optimal=${r.isOptimal}")
                }
                last = r
            }
        }
        val final = checkNotNull(last) { "$tag: no emission" }
        log(
            "TA SEARCH $tag endMs=${(System.nanoTime() - t0) / 1_000_000} optimal=${final.isOptimal} proxy=${final.maxDamageRawProxy} " +
                "hardMet=${final.maxDamageHardConstraintsMet} ${rowStats(p, final.individual)}"
        )
        log("TA BUILD $tag ${describe(final.individual)}")
        return final
    }

    @Test
    @Tag("manual")
    fun `manual target cost decomposition`() {
        assumeTrue(System.getenv("WAKFU_TA_DECOMP") == "1")
        val levels = envList("WAKFU_TA_LEVELS") ?: listOf(110, 245)
        val seconds = System.getenv("WAKFU_TA_SECONDS")?.toLongOrNull() ?: 420L
        val freeSeconds = System.getenv("WAKFU_TA_FREE_SECONDS")?.toLongOrNull() ?: 90L
        val rt = Runtime.getRuntime()
        log(
            "TA ENV cores=${rt.availableProcessors()} maxHeapMb=${rt.maxMemory() / 1_048_576} seconds=$seconds freeSeconds=$freeSeconds " +
                "certifierVersion=${WakfuBuildSolver.CERTIFIER_VERSION} dataVersion=${WakfuData.VERSION}"
        )
        WakfuBuildSolver.warmUp()
        for (lvl in levels) {
            // 1. The GUI-default request: the strongest targets-met incumbent a long multi-worker search gives.
            val p = params(lvl, guiRows(), seconds)
            val final = search("gui$lvl", p)
            val p0 = System.nanoTime()
            val proof = WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(p, final)
            log("TA PROOF gui$lvl verdict=${verdict(proof)} proofMs=${(System.nanoTime() - p0) / 1_000_000}")
            // 2. The FREE request (max-damage ignores the maximized mastery row): where does its optimum sit on each row?
            if (freeSeconds > 0) {
                val pf = params(lvl, freeRows(), freeSeconds)
                val freeFinal = search("free$lvl", pf)
                val f0 = System.nanoTime()
                val freeProof = WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(pf, freeFinal)
                log("TA PROOF free$lvl verdict=${verdict(freeProof)} proofMs=${(System.nanoTime() - f0) / 1_000_000}")
                if (freeProof is MaxDamageSearch.MaxDamageProof.ProvenWithin) {
                    val c0 = System.nanoTime()
                    val constructed = WakfuBestBuildFinderAlgorithm.constructMaxDamageProvenOptimum(pf, freeFinal)
                    log("TA E8 free$lvl constructed=${constructed != null} ms=${(System.nanoTime() - c0) / 1_000_000} proxy=${constructed?.maxDamageRawProxy}")
                    if (constructed != null) {
                        log("TA SEARCH freeOpt$lvl proxy=${constructed.maxDamageRawProxy} ${rowStats(pf, constructed.individual)}")
                        log("TA BUILD freeOpt$lvl ${describe(constructed.individual)}")
                    }
                }
            }
        }
    }

    /** Runs [block] while a daemon thread samples the used heap every 25 ms; returns (result, peak used MB). */
    private fun <T> withPeakHeap(block: () -> T): Pair<T, Long> {
        val rt = Runtime.getRuntime()
        System.gc()
        val peak = AtomicLong(rt.totalMemory() - rt.freeMemory())
        val done = AtomicBoolean(false)
        val sampler =
            Thread {
                while (!done.get()) {
                    peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), ::maxOf)
                    Thread.sleep(25)
                }
            }.apply {
                isDaemon = true
                start()
            }
        val result =
            try {
                block()
            } finally {
                done.set(true)
                sampler.join()
            }
        return result to peak.get() / 1_048_576
    }

    /** Runs [block] with the RANGE sub-seam set as given, restoring it afterwards. */
    private fun <T> withRangeDim(
        on: Boolean,
        block: () -> T,
    ): T {
        val prev = CertifierTuning.targetAwareRangeEnabled
        CertifierTuning.targetAwareRangeEnabled = on
        try {
            return block()
        } finally {
            CertifierTuning.targetAwareRangeEnabled = prev
        }
    }

    @Test
    @Tag("manual")
    fun `manual target-aware bound on the GUI-default request`() {
        assumeTrue(System.getenv("WAKFU_TA_BOUND") == "1")
        val levels = envList("WAKFU_TA_LEVELS") ?: listOf(110, 245)
        val incumbents =
            (System.getenv("WAKFU_TA_INCUMBENTS") ?: "110=1495770,200=10507040,245=19242720")
                .split(',')
                .associate { kv -> kv.substringBefore('=').trim().toInt() to kv.substringAfter('=').trim().toLong() }
        val arms = (System.getenv("WAKFU_TA_ARMS") ?: "off,filters,range").split(',').map { it.trim() }
        val threads = System.getenv("WAKFU_TA_THREADS")?.toIntOrNull() ?: WakfuBuildSolver.certifierDefaultThreads()
        val pinnedThreads = System.getenv("WAKFU_TA_THREADS") != null
        val fastOnly = System.getenv("WAKFU_TA_FAST_ONLY") == "1"
        val rt = Runtime.getRuntime()
        log(
            "TA BOUND_ENV cores=${rt.availableProcessors()} maxHeapMb=${rt.maxMemory() / 1_048_576} threads=$threads pinned=$pinnedThreads " +
                "tier15=${WakfuBuildSolver.certifierTier15Threads()} fastWorlds=${WakfuBuildSolver.certifierFastWorldThreads()} arms=$arms " +
                "certifierVersion=${WakfuBuildSolver.CERTIFIER_VERSION}"
        )
        WakfuBuildSolver.warmUp()
        val provider: ((CertTier) -> Int)? = if (pinnedThreads) ({ threads }) else null
        for (lvl in levels) {
            val p = params(lvl, guiRows(), 60)
            val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
            val incumbent = incumbents[lvl]
            for (arm in arms) {
                val targetAware = arm != "off"
                CertifierTuning.targetAwareRangeFreeForTest.set(-1)
                CertifierTuning.targetAwareRangeNeedForTest.set(-1)
                withRangeDim(arm != "filters") {
                    // (a) The pure fast ledger (a huge incumbent eliminates every cell).
                    val f0 = System.nanoTime()
                    val fast =
                        WakfuBuildSolver.maxDamageCertificate(
                            p,
                            pool,
                            runes,
                            subs,
                            incumbentObjective = Long.MAX_VALUE / 4,
                            threads = threads,
                            threadsProvider = provider,
                            targetAware = targetAware
                        )
                    log(
                        "TA FAST level=$lvl arm=$arm ms=${(System.nanoTime() - f0) / 1_000_000} maxCell=${fast?.maxCellObjective} bailed=${fast?.bailedCells} " +
                            "cells=${fast?.cellObjectives?.toSortedMap()}"
                    )
                    if (System.getenv("WAKFU_TA_AUX") == "1") {
                        val (normal, aux) =
                            WakfuBuildSolver.certifierFastAndAuxCellObjectivesForTest(
                                p,
                                pool,
                                runes,
                                subs,
                                applyDomination = true,
                                threads = threads,
                                targetAware = targetAware
                            )
                        log("TA AUX level=$lvl arm=$arm normalMax=${normal.values.maxOrNull()} auxMax=${aux.values.maxOrNull()} aux=${aux.toSortedMap()}")
                    }
                    if (fastOnly || incumbent == null) return@withRangeDim
                    // (b) The production-shaped proof: elimination against the real incumbent, tier-1.5 cascade, exact refinement.
                    val pruned0 = CertifierTuning.targetAwareRangePrunedForTest.get()
                    val l0 = System.nanoTime()
                    val (ledger, peakHeapMb) =
                        withPeakHeap {
                            WakfuBuildSolver.maxDamageCertificate(
                                p,
                                pool,
                                runes,
                                subs,
                                incumbentObjective = incumbent,
                                threads = threads,
                                threadsProvider = provider,
                                cascadeTier15 = true,
                                targetAware = targetAware
                            )
                        }
                    val ms = (System.nanoTime() - l0) / 1_000_000
                    val max = ledger?.maxCellObjective
                    val badge = max?.let { "%.4f%%".format(Locale.ROOT, (it.toDouble() / incumbent - 1.0) * 100) }
                    val pruned = CertifierTuning.targetAwareRangePrunedForTest.get() - pruned0
                    log(
                        "TA PROOF level=$lvl arm=$arm ms=$ms peakHeapMb=$peakHeapMb incumbent=$incumbent maxCell=$max badge=$badge " +
                            "tier2Cells=${ledger?.tier2Cells} exactBailed=${ledger?.exactBailedCells} rangePruned=$pruned " +
                            "rangeRowFree=${CertifierTuning.targetAwareRangeFreeForTest.get()} rangeNeed=${CertifierTuning.targetAwareRangeNeedForTest.get()} " +
                            "cells=${ledger?.cellObjectives?.toSortedMap()}"
                    )
                    // (c) The IDEAL maximum: the flood control stops at the first exact value above the incumbent and leaves the
                    // other survivors at their fast values — what would refining every survivor give?
                    if (System.getenv("WAKFU_TA_RATCHET") == "1" && ledger != null && max != null) {
                        // Per cell, the tightest bound any round computed (each is a sound upper bound, so their min is too);
                        // a round re-runs the proof with a fake incumbent one below the current argmax, which refines that
                        // cell (tier-1.5, then exact if tier-1.5 cannot clear it) — until the argmax is exact-confirmed.
                        val tightest = ledger.cellObjectives.toMutableMap()
                        val exactCells = (ledger.tier2Cells + ledger.exactBailedCells).toMutableSet()
                        var rounds = 0
                        val r0 = System.nanoTime()
                        while (rounds < 12) {
                            val (argCell, argValue) = tightest.entries.maxBy { it.value }
                            if (argCell in exactCells) break
                            rounds++
                            val cur =
                                WakfuBuildSolver.maxDamageCertificate(
                                    p,
                                    pool,
                                    runes,
                                    subs,
                                    incumbentObjective = argValue - 1,
                                    threads = threads,
                                    threadsProvider = provider,
                                    cascadeTier15 = true,
                                    targetAware = targetAware
                                ) ?: break
                            for ((c, v) in cur.cellObjectives) tightest[c] = minOf(tightest.getValue(c), v)
                            exactCells += cur.tier2Cells + cur.exactBailedCells
                            log(
                                "TA RATCHET level=$lvl arm=$arm round=$rounds argmax=$argCell value=$argValue tier2Cells=${cur.tier2Cells} tier15=${cur.tier15Objectives.toSortedMap()}"
                            )
                        }
                        val idealMax = tightest.values.maxOrNull()
                        log(
                            "TA IDEAL level=$lvl arm=$arm ratchetRounds=$rounds ms=${(System.nanoTime() - r0) / 1_000_000} incumbent=$incumbent maxCell=$idealMax " +
                                "badge=${idealMax?.let { "%.4f%%".format(Locale.ROOT, (it.toDouble() / incumbent - 1.0) * 100) }} exactCells=$exactCells " +
                                "cells=${tightest.toSortedMap()}"
                        )
                    }
                }
            }
        }
    }
}
