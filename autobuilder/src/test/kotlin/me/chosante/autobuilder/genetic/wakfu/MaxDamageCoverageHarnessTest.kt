package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * MANUAL measurement harness for the max-damage AP-cell certificate's coverage of the GUI-default target rows
 * (CERTIFIER_VERSION 44, `docs/CERTIFICATE_PROD_PLAN.md` P5.4). Env-gated, prints `MDCOV_*` lines into the JUnit XML.
 *
 * ```shell
 * # certificate-only diagnosis (fast-only ledger; AUX = the aux worlds' share; SPLIT = the same ledger without the two
 * # dropped sub families, i.e. the normal worlds alone), per level / target-row variant:
 * WAKFU_MDCOV_DIAG=1 WAKFU_MDCOV_AUX=1 WAKFU_MDCOV_SPLIT=1 WAKFU_MDCOV_LEVELS=110,245 WAKFU_MDCOV_VARIANTS=all,free \
 *   WAKFU_TEST_MAX_HEAP=6g ./gradlew --no-daemon :autobuilder:test --tests '*MaxDamageCoverageHarnessTest*' --rerun
 * # production path (search, then the post-search proof + E8 construct exactly as the GUI chains them) on the
 * # GUI-default request, on the 2026-10 E0 baseline's 4-core laptop shape (3 CP-SAT workers, 3 GB heap):
 * WAKFU_MDCOV_PROD=1 WAKFU_MDCOV_LEVELS=110,245 WAKFU_MDCOV_SECONDS=120 WAKFU_TEST_MAX_HEAP=3g \
 *   WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4" \
 *   ./gradlew --no-daemon :autobuilder:test --tests '*MaxDamageCoverageHarnessTest*' --rerun
 * ```
 */
class MaxDamageCoverageHarnessTest {
    private val guiDefaultRows =
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

    private fun params(
        level: Int,
        targets: List<TargetStat>,
        seconds: Long = 60L,
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

    private fun levels(): List<Int> = (System.getenv("WAKFU_MDCOV_LEVELS") ?: "110").split(',').map { it.trim().toInt() }

    @Test
    fun `manual default-row certificate diagnosis`() {
        assumeTrue(System.getenv("WAKFU_MDCOV_DIAG") == "1")
        val variantFilter =
            System
                .getenv("WAKFU_MDCOV_VARIANTS")
                ?.split(',')
                ?.map { it.trim() }
                ?.toSet()
        val threads = System.getenv("WAKFU_MDCOV_THREADS")?.toIntOrNull() ?: 4
        val variants =
            listOf(
                "all" to guiDefaultRows,
                "free" to listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)),
                "hpOnly" to listOf(TargetStat(Characteristic.HP, 2000)),
                "resOnly" to listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WIND, 0)),
                "dodgeOnly" to listOf(TargetStat(Characteristic.DODGE, 0))
            ).filter { variantFilter == null || it.first in variantFilter }
        val subs = WakfuBestBuildFinderAlgorithm.sublimations
        val runes = WakfuBestBuildFinderAlgorithm.runes
        for (lvl in levels()) {
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..lvl || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            for ((label, targets) in variants) {
                val p = params(lvl, targets)
                // A huge incumbent eliminates every cell ⇒ the pure fast ledger (what the badge starts from).
                val t0 = System.nanoTime()
                val ledger = WakfuBuildSolver.maxDamageCertificate(p, pool, runes, subs, applyDomination = true, incumbentObjective = Long.MAX_VALUE / 4, threads = threads)
                val ledgerMs = (System.nanoTime() - t0) / 1_000_000
                val certified = ledger?.cellObjectives?.count { it.value > 0 }
                println(
                    "MDCOV_DIAG level=$lvl variant=$label ledgerMs=$ledgerMs ledgerNull=${ledger == null} " +
                        "bailed=${ledger?.bailedCells} maxCell=${ledger?.maxCellObjective} certified=$certified/${ledger?.cellObjectives?.size}"
                )
                if (System.getenv("WAKFU_MDCOV_SPLIT") == "1") {
                    // The same ledger without the two sub families the normal worlds drop ⇒ no aux world: the normal
                    // worlds' own cost and values (the pre-v44 ledger on every shape that did not bail).
                    val normalOnlySubs = subs.filterNot { isSecondaryCapDroppedSub(it, p.damageScenario) || isBlockWorldSub(it) }
                    val t2 = System.nanoTime()
                    val normal =
                        WakfuBuildSolver.maxDamageCertificate(
                            p,
                            pool,
                            runes,
                            normalOnlySubs,
                            applyDomination = true,
                            incumbentObjective = Long.MAX_VALUE / 4,
                            threads = threads
                        )
                    println(
                        "MDCOV_SPLIT level=$lvl variant=$label normalOnlyLedgerMs=${(System.nanoTime() - t2) / 1_000_000} droppedSubs=${subs.size - normalOnlySubs.size} " +
                            "bailed=${normal?.bailedCells} maxCell=${normal?.maxCellObjective} sameCells=${normal?.cellObjectives == ledger?.cellObjectives}"
                    )
                }
                if (System.getenv("WAKFU_MDCOV_AUX") == "1") {
                    val t1 = System.nanoTime()
                    val (fast, aux) = WakfuBuildSolver.certifierFastAndAuxCellObjectivesForTest(p, pool, runes, subs, applyDomination = true, threads = threads)
                    val binding = aux.filter { (a, v) -> v > 0 && v >= (fast[a] ?: -1L) }.keys
                    println(
                        "MDCOV_AUX level=$lvl variant=$label ms=${(System.nanoTime() - t1) / 1_000_000} fastMax=${fast.values.maxOrNull()} " +
                            "auxMax=${aux.values.maxOrNull()} auxBindingCells=$binding"
                    )
                    println("MDCOV_AUX_CELLS level=$lvl variant=$label fast=${fast.toSortedMap()} aux=${aux.toSortedMap()}")
                }
            }
        }
    }

    @Test
    fun `manual production default-row badge`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MDCOV_PROD") == "1")
            val seconds = System.getenv("WAKFU_MDCOV_SECONDS")?.toLongOrNull() ?: 90L
            WakfuBuildSolver.warmUp()
            for (lvl in levels()) {
                val p = params(lvl, guiDefaultRows, seconds)
                val t0 = System.nanoTime()
                var last: SolverResult<BuildCombination>? = null
                var lastImproveMs = 0L
                var lastScore: java.math.BigDecimal? = null
                WakfuBestBuildFinderAlgorithm.run(p).collect { r ->
                    val prev = lastScore
                    if (prev == null || r.matchPercentage > prev) {
                        lastScore = r.matchPercentage
                        lastImproveMs = (System.nanoTime() - t0) / 1_000_000
                    }
                    last = r
                }
                val final = checkNotNull(last)
                println(
                    "MDCOV_PROD level=$lvl searchMs=${(System.nanoTime() - t0) / 1_000_000} lastImproveMs=$lastImproveMs optimal=${final.isOptimal} " +
                        "proxy=${final.maxDamageRawProxy} hardMet=${final.maxDamageHardConstraintsMet} " +
                        "subs=${final.individual.sublimations.values.flatten().map { it.name.en }} " +
                        "runes=${final.individual.runes.values.flatten().groupingBy { it.characteristic }.eachCount()}"
                )
                val p0 = System.nanoTime()
                val proof = WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(p, final)
                val verdict =
                    when (proof) {
                        MaxDamageSearch.MaxDamageProof.ProvenOptimal -> "ProvenOptimal"
                        is MaxDamageSearch.MaxDamageProof.ProvenWithin -> "ProvenWithin(${"%.4f".format(Locale.ROOT, proof.fraction * 100)}%)"
                        MaxDamageSearch.MaxDamageProof.Unavailable -> "Unavailable"
                    }
                println("MDCOV_PROD level=$lvl proof=$verdict proofMs=${(System.nanoTime() - p0) / 1_000_000} sinceStartMs=${(System.nanoTime() - t0) / 1_000_000}")
                if (proof is MaxDamageSearch.MaxDamageProof.ProvenWithin) {
                    // The GUI's next step on a ProvenWithin badge: the E8 construct (an upgrade ⇒ ProvenOptimal).
                    val c0 = System.nanoTime()
                    val constructed = WakfuBestBuildFinderAlgorithm.constructMaxDamageProvenOptimum(p, final)
                    println("MDCOV_PROD level=$lvl construct=${constructed != null} constructMs=${(System.nanoTime() - c0) / 1_000_000} proxy=${constructed?.maxDamageRawProxy}")
                }
            }
        }
}
