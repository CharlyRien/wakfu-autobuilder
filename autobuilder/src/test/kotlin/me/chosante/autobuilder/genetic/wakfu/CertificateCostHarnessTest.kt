package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * MANUAL measurement harness — the post-search max-damage certificate in isolation (no search): the production call
 * ([WakfuBuildSolver.maxDamageCertificate] with the proof's default thread counts and the cascade tier-1.5) on the E0
 * fixtures `MD245F` (free distance row) / `MD245` (the GUI's default rows: the target-aware hard-leg ledger), at a fixed
 * incumbent (a 60 s search's typical one). Arms alternate inside ONE JVM so a noisy machine hits both alike.
 *
 * ```shell
 * WAKFU_CERTCOST=1 WAKFU_CERTCOST_FX=MD245F WAKFU_CERTCOST_INCUMBENT=19470250 WAKFU_CERTCOST_ARMS=on,off,on,off \
 *   WAKFU_TEST_MAX_HEAP=3g WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4" WAKFU_MAX_DAMAGE_CERT_TIMING=1 \
 *   ./gradlew :autobuilder:test --tests '*CertificateCostHarnessTest*' --rerun
 * ```
 * Arms: `on` (every lever), `noprobe` (no [CertifierTuning.exactFirstProbeEnabled]), `off` (no
 * [CertifierTuning.crossWorldPruneEnabled] either). Prints `CERTCOST` lines (ms + the full ledger, so
 * the arms' bounds can be diffed).
 */
@Tag("manual")
class CertificateCostHarnessTest {
    private fun params(
        level: Int,
        targets: List<TargetStat>,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        useRunes = true,
        useSublimations = true
    )

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

    @Test
    fun `manual post-search certificate cost`() {
        assumeTrue(System.getenv("WAKFU_CERTCOST") == "1")
        val fx = System.getenv("WAKFU_CERTCOST_FX") ?: "MD245F"
        val (p, targetAware) =
            when (fx) {
                "MD245F" -> params(245, listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))) to false
                "MD245" -> params(245, guiTargets()) to true
                "MD110" -> params(110, guiTargets()) to true
                else -> error("unknown fixture $fx")
            }
        val incumbent = System.getenv("WAKFU_CERTCOST_INCUMBENT")?.toLongOrNull() ?: error("WAKFU_CERTCOST_INCUMBENT")
        val arms = (System.getenv("WAKFU_CERTCOST_ARMS") ?: "on,off").split(',').map { it.trim() }
        val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
        val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
        WakfuBuildSolver.warmUp()
        println(
            "CERTCOST_ENV fx=$fx cores=${Runtime.getRuntime().availableProcessors()} heapMb=${Runtime.getRuntime().maxMemory() / 1_048_576} " +
                "certifierVersion=${WakfuBuildSolver.CERTIFIER_VERSION} exactThreads=${WakfuBuildSolver.certifierDefaultThreads(p.computeBudget)} " +
                "tier15Threads=${WakfuBuildSolver.certifierTier15Threads(p.computeBudget)} fastThreads=${WakfuBuildSolver.certifierFastWorldThreads(p.computeBudget)}"
        )
        try {
            for ((i, arm) in arms.withIndex()) {
                // "off": neither lever; "noprobe": the cross-world prune only; anything else: both.
                CertifierTuning.crossWorldPruneEnabled = arm != "off"
                CertifierTuning.exactFirstProbeEnabled = arm != "off" && arm != "noprobe"
                MaxDamageCertificateCache.clear()
                val t0 = System.nanoTime()
                val ledger =
                    WakfuBuildSolver.maxDamageCertificate(
                        p,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        subs,
                        applyDomination = true,
                        incumbentObjective = incumbent,
                        cascadeTier15 = true,
                        targetAware = targetAware
                    )
                val ms = (System.nanoTime() - t0) / 1_000_000
                println(
                    "CERTCOST fx=$fx run=$i arm=$arm ms=$ms max=${ledger?.maxCellObjective} tier2=${ledger?.tier2Cells?.toSortedSet()} " +
                        "prov=${ledger?.cellProvenance?.toSortedMap()?.mapValues { "${it.value.worldIndex}/${it.value.c}" }} " +
                        "cells=${ledger?.cellObjectives?.toSortedMap()}"
                )
            }
        } finally {
            CertifierTuning.crossWorldPruneEnabled = true
            CertifierTuning.exactFirstProbeEnabled = true
        }
    }
}
