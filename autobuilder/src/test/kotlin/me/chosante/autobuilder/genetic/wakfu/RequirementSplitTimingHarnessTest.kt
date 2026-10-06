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
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * MANUAL measurement harness — what the max-damage certificate costs on the production level-200 shape since the item-REQUIRES
 * world split (CERTIFIER_VERSION 57: every certifier world split into its no-nation-sword half and its sword + ring bundle half;
 * review of #246, finding 3). Env-gated, prints `REQSPLIT_*` lines (add `WAKFU_MAX_DAMAGE_CERT_TIMING=1` for the per-tier
 * `CERT_TIMING` split).
 *
 * ```shell
 * # the pure fast tier (a huge incumbent eliminates every cell; WAKFU_REQSPLIT_INCUMBENT sets a real one), production pool:
 * WAKFU_REQSPLIT_FAST=1 WAKFU_REQSPLIT_LEVEL=200 WAKFU_REQSPLIT_THREADS=4 WAKFU_TEST_MAX_HEAP=8g \
 *   ./gradlew :autobuilder:test --tests '*RequirementSplitTimingHarnessTest*' --rerun -i
 * # a free max-damage search (distance row only, runes + subs) of WAKFU_REQSPLIT_SECONDS, then its post-search badge:
 * WAKFU_REQSPLIT_SEARCH=1 WAKFU_REQSPLIT_LEVEL=200 WAKFU_REQSPLIT_SECONDS=60 WAKFU_TEST_MAX_HEAP=8g \
 *   ./gradlew :autobuilder:test --tests '*RequirementSplitTimingHarnessTest*' --rerun -i
 * ```
 *
 * What it measured (4-core container, CERTIFIER_VERSION 58), for the record of the LAZY split the review proposed and that was
 * NOT shipped. Running the fast tier on the UNSPLIT worlds (the sword fused with its ring — a sound relaxation of both halves)
 * cut the pure fast tier by ~17 %, but on this shape the top surviving cell's unsplit argmax IS the over-counted fused-sword
 * composition (sword + its ring + two other rings), so the split is needed exactly where the proof refines: every lazy variant
 * (parent rows for tier-1.5, unsplit tier-1.5 / exact with an argmax test, an unskipped split tier-1.5, a lazy aux floor) paid
 * the split there WITHOUT the per-half fast rows that make the v57 tier-1.5 skip and exact c-loop prune cheap — the ledger at
 * the 60 s search's incumbent took 73–101 s against 61–63 s always-split, the badge after a 60 s search 65–67 s against 33–37 s.
 */
@Tag("manual")
class RequirementSplitTimingHarnessTest {
    private fun freeParams(
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
        useSublimations = true,
        damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
    )

    private fun ms(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

    private fun describe(proof: MaxDamageSearch.MaxDamageProof): String =
        when (proof) {
            MaxDamageSearch.MaxDamageProof.ProvenOptimal -> "ProvenOptimal"
            is MaxDamageSearch.MaxDamageProof.ProvenWithin -> "ProvenWithin(${"%.4f".format(Locale.ROOT, proof.fraction * 100)}%)"
            MaxDamageSearch.MaxDamageProof.Unavailable -> "Unavailable"
        }

    @Test
    fun `manual pure fast tier on the production pool`() {
        assumeTrue(System.getenv("WAKFU_REQSPLIT_FAST") == "1")
        val level = System.getenv("WAKFU_REQSPLIT_LEVEL")?.toIntOrNull() ?: 200
        val threads = System.getenv("WAKFU_REQSPLIT_THREADS")?.toIntOrNull() ?: 4
        val rounds = System.getenv("WAKFU_REQSPLIT_ROUNDS")?.toIntOrNull() ?: 2
        val incumbent = System.getenv("WAKFU_REQSPLIT_INCUMBENT")?.toLongOrNull() ?: (Long.MAX_VALUE / 2)
        val params = freeParams(level, 60)
        val pool = WakfuBestBuildFinderAlgorithm.poolFor(params)
        WakfuBuildSolver.warmUp()
        println("REQSPLIT_ENV cores=${Runtime.getRuntime().availableProcessors()} certifierVersion=${WakfuBuildSolver.CERTIFIER_VERSION}")
        repeat(rounds) { round ->
            val t0 = System.nanoTime()
            val ledger =
                WakfuBuildSolver.certifyLedgerForTest(
                    params,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.activeSublimations(params),
                    applyDomination = true,
                    incumbentObjective = incumbent,
                    threads = threads
                )
            println(
                "REQSPLIT_FAST level=$level round=$round threads=$threads incumbent=$incumbent ms=${ms(t0)} " +
                    "max=${ledger.maxCellObjective} tier2=${ledger.tier2Cells.toSortedSet()} bailed=${ledger.bailedCells.toSortedSet()} " +
                    "cells=${ledger.cellObjectives.toSortedMap()}"
            )
        }
    }

    @Test
    fun `manual search then badge`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_REQSPLIT_SEARCH") == "1")
            val level = System.getenv("WAKFU_REQSPLIT_LEVEL")?.toIntOrNull() ?: 200
            val seconds = System.getenv("WAKFU_REQSPLIT_SECONDS")?.toLongOrNull() ?: 60L
            val reps = System.getenv("WAKFU_REQSPLIT_REPS")?.toIntOrNull() ?: 1
            WakfuBuildSolver.warmUp()
            println("REQSPLIT_ENV cores=${Runtime.getRuntime().availableProcessors()} certifierVersion=${WakfuBuildSolver.CERTIFIER_VERSION}")
            repeat(reps) { rep ->
                MaxDamageCertificateCache.clear()
                val p = freeParams(level, seconds)
                val t0 = System.nanoTime()
                var last: SolverResult<BuildCombination>? = null
                WakfuBestBuildFinderAlgorithm.run(p).collect { last = it }
                val searchMs = ms(t0)
                val final = checkNotNull(last)
                val worn =
                    final.individual.equipments
                        .sortedBy { it.itemType }
                        .joinToString { "${it.equipmentId}:${it.name.fr}(${it.rarity})" }
                println("REQSPLIT_SEARCH level=$level rep=$rep searchMs=$searchMs optimal=${final.isOptimal} proxy=${final.maxDamageRawProxy} items=[$worn]")
                val p0 = System.nanoTime()
                val proof = WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(p, final)
                println("REQSPLIT_BADGE level=$level rep=$rep proof=${describe(proof)} afterSearchEndMs=${ms(p0)} sinceStartMs=${ms(t0)}")
            }
        }
}
