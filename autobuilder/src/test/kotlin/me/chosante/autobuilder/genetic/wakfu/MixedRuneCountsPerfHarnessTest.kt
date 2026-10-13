package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/**
 * The mixed-rune count-model perf harness (manual: `WAKFU_MIXED_PERF=shape|run`, docs/MIXED_RUNE_SECONDARY_BUDGET.md "Cost").
 * Deterministic protocol (1 worker, interleave, a fixed seed, a det budget; production pool with domination on, presolve 3 as in
 * production, the greedy warm start hinted as in production), so a job's numbers do not depend on the machine's load: jobs run
 * in parallel (`WAKFU_MIXED_PAR`, default 4), one CP-SAT worker each.
 *
 * Requests (`WAKFU_MIXED_FIXTURES`, default all): the GUI default rows (AP 11, MP 4, range 4, crit 25, distance 1, HP 2000, wind
 * resistance 0, dodge 0 — [PerfBaselineE0Test]) at levels 110 / 200 / 245, on the hard leg as production runs it first, and the
 * free level-245 request. Arms (`WAKFU_MIXED_ARMS`, default `fold,counts`; also `classes`, `classesL1`, `hybrid` (the hybrid,
 * its count stages on classes), `hybridPerCarrier`): see [arms]. `WAKFU_MIXED_LEGS=pair` runs production's leg PAIR
 * ([MaxDamageSearch.optimizeHardThenSoft]: the hard leg, then the soft one if it found nothing) instead of the single leg; the
 * hybrid's `first` is its fold stage's, so measure `first` on the `fold` arm. Budgets (`WAKFU_MIXED_BUDGETS`, default
 * `first,60,120`): `first` stops at CP-SAT's first solution (its det time is the number), a number is a det budget (the objective
 * at its end), `w<n>` a WALL budget of n seconds. Seeds: `WAKFU_MIXED_SEEDS` (default `1`). `WAKFU_MIXED_LOGDIR` writes each
 * job's CP-SAT search log there. One `MIXED_PERF` line per job.
 *
 * Production's portfolio is NOT the interleaved one-worker search: `WAKFU_MIXED_WORKERS=3 WAKFU_MIXED_INTERLEAVE=0` with a `w60`
 * budget, `WAKFU_MIXED_PAR=1` and `WAKFU_TEST_JVM_ARGS=-XX:ActiveProcessorCount=4` runs the GUI's 4-core search (wall clock, not
 * deterministic: repeat it over several seeds). The two protocols ranked the arms differently
 * (docs/MIXED_RUNE_SECONDARY_BUDGET.md "Count classes").
 */
@Tag("manual")
class MixedRuneCountsPerfHarnessTest {
    private fun params(
        level: Int,
        targets: List<TargetStat>,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 120.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        useRunes = true,
        useSublimations = true
    )

    private val guiTargets =
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

    private val fixtures: Map<String, Pair<WakfuBestBuildParams, Boolean>> =
        linkedMapOf(
            "MD110" to (params(110, guiTargets) to true),
            "MD200" to (params(200, guiTargets) to true),
            "MD245" to (params(245, guiTargets) to true),
            "MD245F" to (params(245, listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))) to false),
            "MD110F" to (params(110, listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))) to false)
        )

    /** A named fixture, or `MD<level>` (the GUI-default rows, hard leg) / `MD<level>F` (free) at any other level. */
    private fun fixture(name: String): Pair<WakfuBestBuildParams, Boolean> =
        fixtures[name] ?: Regex("""MD(\d+)(F?)""").matchEntire(name)!!.let { m ->
            val level = m.groupValues[1].toInt()
            if (m.groupValues[2] == "F") {
                params(level, listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))) to false
            } else {
                params(level, guiTargets) to true
            }
        }

    /** Arm name → the tuning it solves with (on top of the deterministic protocol). */
    private val arms: Map<String, (WakfuBuildSolver.SolverTuning) -> WakfuBuildSolver.SolverTuning> =
        linkedMapOf(
            "fold" to { t -> t.copy(runeMixedCarriers = false, runeCountClasses = false) },
            "counts" to { t -> t.copy(runeCountClasses = false) },
            "classes" to { t -> t.copy(runeCountClasses = true) },
            "classesL1" to { t -> t.copy(runeCountClasses = true, linearizationLevelOverride = 1) },
            // The mixed-rune hybrid (fold search, count polish / verify): its count stages on the classes, or per-carrier.
            "hybrid" to { t -> t.copy(runeCountClasses = true, mixedRuneHybrid = true) },
            "hybridPerCarrier" to { t -> t.copy(runeCountClasses = false, mixedRuneHybrid = true) },
            // The fold / the hybrid with the fold's pick carriers aggregated into PICK classes (production's default).
            "foldPC" to { t -> t.copy(runeMixedCarriers = false, runeCountClasses = false, runePickClasses = true) },
            "hybridPC" to { t -> t.copy(runeCountClasses = true, mixedRuneHybrid = true, runePickClasses = true) },
            // Production's rule: pick classes only on a fold of PICK_CLASS_MIN_PICK_VARS pick vars or more.
            "hybridAuto" to { t ->
                t.copy(runeCountClasses = true, mixedRuneHybrid = true, runePickClasses = true, runePickClassMinPickVars = WakfuBuildSolver.PICK_CLASS_MIN_PICK_VARS)
            }
        )

    private fun env(
        name: String,
        default: String,
    ): List<String> = (System.getenv(name) ?: default).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun `mixed rune count model perf`(): Unit =
        runBlocking {
            val mode = System.getenv("WAKFU_MIXED_PERF")
            assumeTrue(mode == "shape" || mode == "pickshape" || mode == "run")
            val fixtureNames = env("WAKFU_MIXED_FIXTURES", "MD110,MD200,MD245,MD245F")
            val armNames = env("WAKFU_MIXED_ARMS", "fold,counts")
            val runes = WakfuBestBuildFinderAlgorithm.runes
            val subs = WakfuBestBuildFinderAlgorithm.sublimations
            if (mode == "pickshape") {
                for (f in fixtureNames) {
                    val (p, hard) = fixture(f)
                    val shape = WakfuBuildSolver.foldPickClassShapeForTest(p, WakfuBestBuildFinderAlgorithm.poolFor(p), runes, subs, hard)
                    println("PICK_SHAPE $f carriers=${shape[0]} pickVars=${shape[1]} classes=${shape[2]} classVars=${shape[3]}")
                }
                return@runBlocking
            }
            if (mode == "shape") {
                for (f in fixtureNames) {
                    val (p, hard) = fixture(f)
                    val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
                    for ((mixed, classes) in listOf(false to false, true to false, true to true)) {
                        val shape = WakfuBuildSolver.runeShapeForTest(p, pool, runes, subs, hard, runeMixedCarriers = mixed, runeCountClasses = classes)
                        println("MIXED_SHAPE $f mixed=$mixed classes=$classes $shape")
                    }
                }
                return@runBlocking
            }
            val budgets = env("WAKFU_MIXED_BUDGETS", "first,60,120")
            val seeds = env("WAKFU_MIXED_SEEDS", "1").map { it.toInt() }
            val logDir = System.getenv("WAKFU_MIXED_LOGDIR")?.let { File(it).apply { mkdirs() } }
            val par = System.getenv("WAKFU_MIXED_PAR")?.toInt() ?: 4
            // Workers per solve (default 1) and the interleaved deterministic protocol (default on).
            val workers = System.getenv("WAKFU_MIXED_WORKERS")?.toInt() ?: 1
            val interleave = System.getenv("WAKFU_MIXED_INTERLEAVE") != "0"
            val legPair = System.getenv("WAKFU_MIXED_LEGS") == "pair"
            // The greedy warm start (and its CP-SAT hint), on by default as in production.
            val greedy = System.getenv("WAKFU_MIXED_GREEDY") != "0"
            val pools = fixtureNames.associateWith { WakfuBestBuildFinderAlgorithm.poolFor(fixture(it).first) }
            val jobs =
                buildList {
                    for (seed in seeds) for (b in budgets) for (f in fixtureNames) for (a in armNames) add(listOf(f, a, b, seed.toString()))
                }
            val executor = Executors.newFixedThreadPool(par)
            try {
                val futures =
                    jobs.map { (f, a, b, seed) ->
                        executor.submit(
                            Callable {
                                val (p, hard) = fixture(f)
                                val first = b == "first"
                                // "w60": a 60 s WALL budget (production-like, non-deterministic).
                                val wall = b.startsWith("w")
                                val log = logDir?.let { File(it, "$f-$a-$b-s$seed.log").printWriter() }
                                val base =
                                    WakfuBuildSolver.SolverTuning(
                                        numSearchWorkers = workers,
                                        randomSeed = seed.toInt(),
                                        interleaveSearch = interleave,
                                        maxDeterministicTime =
                                            if (wall) {
                                                1e6
                                            } else if (first) {
                                                300.0
                                            } else {
                                                b.toDouble()
                                            },
                                        maxWallSecondsForTest = if (wall) b.drop(1).toDouble() else null,
                                        applyDominationOverride = true,
                                        maxDamageExperiment = MaxDamageExperimentConfig.DEFAULT,
                                        maxPresolveIterationsOverride = 3,
                                        greedyWarmStart = greedy,
                                        stopAtFirstSolution = first,
                                        searchLogSink = log?.let { w -> { line: String -> synchronized(w) { w.println(line) } } }
                                    )
                                val tuning = arms.getValue(a)(base)
                                var outcome: WakfuBuildSolver.SolveOutcome? = null
                                val start = System.nanoTime()
                                val results =
                                    if (legPair) {
                                        runBlocking { MaxDamageSearch.optimizeHardThenSoft(p, pools.getValue(f), runes, subs, tuning, greedyWarmStart = greedy).toList() }
                                    } else {
                                        runBlocking {
                                            WakfuBuildSolver
                                                .optimize(
                                                    p,
                                                    pools.getValue(f),
                                                    runes,
                                                    subs,
                                                    tuning,
                                                    hardConstraints = hard,
                                                    maxDamageGreedyWarmStart = greedy,
                                                    onTermination = { outcome = it }
                                                ).toList()
                                        }
                                    }
                                log?.close()
                                val wallMs = (System.nanoTime() - start) / 1_000_000
                                val last = results.lastOrNull { !it.greedyWarmStartEmission }
                                val line =
                                    "MIXED_PERF fixture=$f arm=$a budget=$b seed=$seed legs=${if (legPair) "pair" else "single"} status=${outcome?.status} " +
                                        "objective=${outcome?.objectiveValue} raw=${last?.maxDamageRawProxy} bound=${outcome?.bestObjectiveBound} " +
                                        "det=${outcome?.deterministicTime} branches=${outcome?.branches} conflicts=${outcome?.conflicts} " +
                                        "last=${last?.maxDamageObjective}/hardLeg=${last?.maxDamageHardConstraintsMet}/optimal=${last?.isOptimal} wallMs=$wallMs"
                                println(line)
                                System.out.flush()
                                line
                            }
                        )
                    }
                val lines = futures.map { it.get() }
                System.getenv("WAKFU_MIXED_OUT")?.let { File(it).appendText(lines.joinToString("\n", postfix = "\n")) }
            } finally {
                executor.shutdown()
            }
        }
}
