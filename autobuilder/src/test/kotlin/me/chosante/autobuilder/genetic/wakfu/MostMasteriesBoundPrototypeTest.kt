package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * M3 gate of docs/MOST_MASTERIES_PERF_PLAN.md P3: how tight is the one-cell (mastery, DI) bound
 * prototype vs the pinned CP-SAT optimum? Small pools run on every invocation (they are seconds);
 * the full-pool F5@245 leg is env-gated (`WAKFU_MM_M3=1`) because the reference optimum is banked
 * from the P0/P0.5 runs (10705) instead of re-solved (~150 s).
 *
 * The soundness canary is `bound ≥ optimum` on every case — a violation means the prototype
 * UNDER-counts and the measurement is void.
 */
class MostMasteriesBoundPrototypeTest {
    private fun item(
        id: Int,
        type: ItemType,
        level: Int = 200,
        rarity: Rarity = Rarity.LEGENDARY,
        slots: Int = 4,
        stats: Map<Characteristic, Int>,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = level,
        name = I18nText("item$id", "item$id", "", ""),
        rarity = rarity,
        itemType = type,
        characteristics = stats,
        maxShardSlots = slots
    )

    private fun params(
        level: Int,
        useRunes: Boolean,
        useSublimations: Boolean,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats =
            TargetStats(
                listOf(
                    TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                    TargetStat(Characteristic.ACTION_POINT, 6)
                )
            ),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes = useRunes,
        useSublimations = useSublimations
    )

    private fun smallPool(): Map<ItemType, List<Equipment>> =
        listOf(
            item(1, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 120)),
            item(2, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 80, Characteristic.HP to 200)),
            item(3, ItemType.CAPE, stats = mapOf(Characteristic.MASTERY_DISTANCE to 90)),
            item(4, ItemType.CAPE, rarity = Rarity.EPIC, stats = mapOf(Characteristic.MASTERY_DISTANCE to 150)),
            item(5, ItemType.BELT, rarity = Rarity.EPIC, stats = mapOf(Characteristic.MASTERY_DISTANCE to 140)),
            item(6, ItemType.BELT, stats = mapOf(Characteristic.MASTERY_DISTANCE to 70)),
            item(7, ItemType.RING, stats = mapOf(Characteristic.MASTERY_DISTANCE to 60)),
            item(8, ItemType.RING, stats = mapOf(Characteristic.MASTERY_DISTANCE to 55)),
            item(9, ItemType.ONE_HANDED_WEAPONS, stats = mapOf(Characteristic.MASTERY_DISTANCE to 110)),
            item(10, ItemType.OFF_HAND_WEAPONS, stats = mapOf(Characteristic.MASTERY_DISTANCE to 50)),
            item(11, ItemType.TWO_HANDED_WEAPONS, stats = mapOf(Characteristic.MASTERY_DISTANCE to 175)),
            // Spare 4-socket carriers (the certifier carrier-blindness gotcha): amulet/boots with no
            // objective stats, so sub copies have hosts without displacing mastery items.
            item(12, ItemType.AMULET, stats = mapOf(Characteristic.HP to 100)),
            item(13, ItemType.BOOTS, stats = mapOf(Characteristic.HP to 100))
        ).groupBy { it.itemType }

    private fun pinnedOptimum(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        det: Double,
    ): Pair<Long, Boolean> =
        runBlocking {
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = det
                )
            val results =
                WakfuBuildSolver
                    .optimize(p, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations, tuning)
                    .toList()
            val last = results.last()
            last.matchPercentage.toLong() to last.isOptimal
        }

    private fun measure(
        label: String,
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        det: Double = 60.0,
    ) {
        val t0 = System.nanoTime()
        val bound =
            MostMasteriesBoundPrototype.bound(
                p,
                pool,
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations
            )!!
        val boundMs = (System.nanoTime() - t0) / 1_000_000
        val (optimum, optimal) = pinnedOptimum(p, pool, det)
        val gapPct = if (optimum > 0) 100.0 * (bound - optimum) / optimum else Double.NaN
        println("MM_M3 $label bound=$bound optimum=$optimum optimal=$optimal gapPct=${"%.2f".format(gapPct)} boundMs=$boundMs")
        assertThat(optimal).describedAs("$label: reference must be a proven optimum").isTrue()
        assertThat(bound).describedAs("$label: SOUNDNESS — the bound must never under-count").isGreaterThanOrEqualTo(optimum)
    }

    @Test
    fun `M3 small pools - equipment only`() = measure("equipmentOnly", params(200, useRunes = false, useSublimations = false), smallPool())

    @Test
    fun `M3 small pools - with runes`() = measure("withRunes", params(200, useRunes = true, useSublimations = false), smallPool())

    @Test
    fun `M3 small pools - runes and sublimations`() = measure("runesAndSubs", params(200, useRunes = true, useSublimations = true), smallPool(), det = 120.0)

    /**
     * P2a correctness lock: on a pool where the targets are reachable, the HARD-constraints leg
     * (targets as `actual ≥ target`, plain unpenalized objective) must reach exactly the soft
     * (penalized) optimum — a targets-met soft optimum is hard-feasible, and among targets-met builds
     * the two objectives coincide (penalty == 1).
     */
    @Test
    fun `P2a hard leg equals the soft optimum on a reachable-targets pool`(): Unit =
        runBlocking {
            val p = params(200, useRunes = true, useSublimations = true)
            val pool = smallPool()
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = 120.0
                )

            suspend fun solve(hard: Boolean): Pair<Long, Boolean> {
                val results =
                    WakfuBuildSolver
                        .optimize(
                            p,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations,
                            tuning,
                            hardConstraints = hard
                        ).toList()
                return results.last().matchPercentage.toLong() to results.last().isOptimal
            }
            val (softScore, softOptimal) = solve(hard = false)
            val (hardScore, hardOptimal) = solve(hard = true)
            assertThat(softOptimal).isTrue()
            assertThat(hardOptimal).isTrue()
            assertThat(hardScore).isEqualTo(softScore)
        }

    /**
     * P2b correctness lock: the two-stage lexicographic split (stage 1 = plain primary, stage 2 =
     * overshoot with the primary pinned) must reproduce the folded single-stage optimum EXACTLY —
     * same primary score AND the same overshoot behavior (the final build still dumps spare skill
     * points, visible as strictly more HP than the primary alone forces).
     */
    @Test
    fun `P2b two-stage split reproduces the folded lexicographic optimum`(): Unit =
        runBlocking {
            val p = params(200, useRunes = true, useSublimations = true)
            val pool = smallPool()
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = 120.0
                )

            suspend fun solve(twoStage: Boolean): SolverResult<me.chosante.autobuilder.domain.BuildCombination> =
                WakfuBuildSolver
                    .optimize(
                        p,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        tuning,
                        hardConstraints = true,
                        mmTwoStageOvershoot = twoStage
                    ).toList()
                    .last()

            fun achievedHp(r: SolverResult<me.chosante.autobuilder.domain.BuildCombination>): Int =
                computeCharacteristicsValues(
                    buildCombination = r.individual,
                    characterBaseCharacteristics = p.character.baseCharacteristicValues,
                    masteryElementsWanted = p.targetStats.masteryElementsWanted,
                    resistanceElementsWanted = p.targetStats.resistanceElementsWanted
                )[Characteristic.HP] ?: 0

            val folded = solve(twoStage = false)
            val split = solve(twoStage = true)
            assertThat(folded.isOptimal).isTrue()
            assertThat(split.isOptimal).isTrue()
            assertThat(split.matchPercentage).describedAs("same primary optimum").isEqualByComparingTo(folded.matchPercentage)
            assertThat(achievedHp(split)).describedAs("stage 2 must still dump spare points (overshoot behavior)").isEqualTo(achievedHp(folded))
        }

    /**
     * P2b E2 timing A/B: two-stage vs folded hard leg on the production F5@245 shape (baseline E1:
     * folded hard leg 78.4 s). `WAKFU_MM_P2B=1`; det via `WAKFU_MM_P2B_DET` (600).
     */
    @Test
    fun `manual P2b two-stage timing at 245`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_P2B") == "1")
            val det = System.getenv("WAKFU_MM_P2B_DET")?.toDoubleOrNull() ?: 600.0
            val level = 245
            val p =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                                TargetStat(Characteristic.ACTION_POINT, 12),
                                TargetStat(Characteristic.MOVEMENT_POINT, 6),
                                TargetStat(Characteristic.HP, 2000),
                                TargetStat(Characteristic.CRITICAL_HIT, 30)
                            )
                        ),
                    searchDuration = 600.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = true,
                    useSublimations = true
                )
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = det,
                    applyDominationOverride = true,
                    maxPresolveIterationsOverride = 1,
                    linearizationLevelOverride = 1
                )
            for (twoStage in listOf(false, true)) {
                val t0 = System.nanoTime()
                var last: SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
                WakfuBuildSolver
                    .optimize(
                        p,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        tuning,
                        hardConstraints = true,
                        mmTwoStageOvershoot = twoStage
                    ).collect { r ->
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        println("MM_P2B twoStage=$twoStage emit t=${ms}ms objective=${r.matchPercentage} optimal=${r.isOptimal}")
                        last = r
                    }
                val wallMs = (System.nanoTime() - t0) / 1_000_000
                println("MM_P2B lvl$level det=$det twoStage=$twoStage SUMMARY wallMs=$wallMs optimal=${last?.isOptimal} objective=${last?.matchPercentage}")
            }
        }

    /**
     * P2a E3: the REAL cost of the hard→soft fallback on deliberately unreachable requests, on the
     * PRODUCTION path (wall-clock, cores−1 — what a user experiences), GUI-default 120 s budget.
     * Three profiles at 245:
     *  - `static`: a target above its individual reachable ceiling (AP 99) — statically detected,
     *    the hard leg must skip instantly and the soft model gets the whole budget;
     *  - `joint`: targets individually reachable but (probably) jointly not — the hard leg must
     *    PROVE infeasibility or burn budget on UNKNOWN; measures how much the soft leg has left;
     *  - `reachable` control: the F5 shape — no fallback, baseline total.
     * `WAKFU_MM_E3=1`; budget via `WAKFU_MM_E3_SECONDS` (120).
     */
    @Test
    fun `manual P2a E3 unreachable-fallback cost at 245`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_E3") == "1")
            val budget = (System.getenv("WAKFU_MM_E3_SECONDS")?.toIntOrNull() ?: 120).seconds
            val level = 245

            fun p(targets: List<TargetStat>) =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                    targetStats = TargetStats(targets),
                    searchDuration = budget,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = true,
                    useSublimations = true
                )
            val distance = TargetStat(Characteristic.MASTERY_DISTANCE, 9999)
            val shapes =
                mapOf(
                    "reachable" to
                        p(
                            listOf(
                                distance,
                                TargetStat(Characteristic.ACTION_POINT, 12),
                                TargetStat(Characteristic.MOVEMENT_POINT, 6),
                                TargetStat(Characteristic.HP, 2000),
                                TargetStat(Characteristic.CRITICAL_HIT, 30)
                            )
                        ),
                    "static" to p(listOf(distance, TargetStat(Characteristic.ACTION_POINT, 99))),
                    // AP+MP near their joint frontier plus a steep crit+HP ask: each is individually
                    // reachable at 245, the conjunction (hopefully) is not — the summary prints the
                    // hard leg's real termination so a mis-crafted shape is visible, not misleading.
                    "joint" to
                        p(
                            listOf(
                                distance,
                                TargetStat(Characteristic.ACTION_POINT, 16),
                                TargetStat(Characteristic.MOVEMENT_POINT, 8),
                                TargetStat(Characteristic.CRITICAL_HIT, 100),
                                TargetStat(Characteristic.HP, 12000)
                            )
                        )
                )
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            for ((label, params) in shapes) {
                val t0 = System.nanoTime()
                var last: SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
                var finalSends = 0
                WakfuBestBuildFinderAlgorithm
                    .mostMasteriesHardThenSoft(params, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations)
                    .collect { r ->
                        if (r.progressPercentage == 100) {
                            finalSends++
                            println("MM_E3 $label finalSend#$finalSends t=${(System.nanoTime() - t0) / 1_000_000}ms objective=${r.matchPercentage} optimal=${r.isOptimal}")
                        }
                        last = r
                    }
                println(
                    "MM_E3 $label SUMMARY wallMs=${(System.nanoTime() - t0) / 1_000_000} budgetMs=${budget.inWholeMilliseconds} " +
                        "finalObjective=${last?.matchPercentage} optimal=${last?.isOptimal} finalSends=$finalSends"
                )
            }
        }

    /**
     * MAX-DAMAGE hard-leg fallback lock (1.9.0 regression guard): with the greedy warm start ON and
     * an unreachable required target, the greedy's instant pre-solve emission must NOT count as
     * hard-leg feasibility (the soft fallback must still run and deliver a final `progress == 100`
     * build) and must NOT carry the solver-verified [SolverResult.maxDamageHardConstraintsMet]
     * provenance the certificate badge trusts.
     */
    @Test
    fun `max-damage greedy emission neither fakes hard feasibility nor hard-met provenance`(): Unit =
        runBlocking {
            val level = 200
            val p =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 99))),
                    searchDuration = 15.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    useRunes = false,
                    useSublimations = false,
                    damageScenario =
                        me.chosante.autobuilder.domain.DamageScenario(
                            element = me.chosante.autobuilder.domain.SpellElement.FIRE,
                            rangeBand = me.chosante.autobuilder.domain.RangeBand.DISTANCE,
                            orientation = me.chosante.autobuilder.domain.Orientation.FACE
                        )
                )
            val results =
                MaxDamageSearch
                    .optimizeHardThenSoft(p, smallPool(), emptyList(), emptyList(), tuning = null, greedyWarmStart = true)
                    .toList()
            val greedy = results.filter { it.greedyWarmStartEmission }
            assertThat(greedy).describedAs("the greedy warm start must stream its instant build").isNotEmpty()
            assertThat(greedy.none { it.maxDamageHardConstraintsMet })
                .describedAs("a pre-solve greedy build must never carry solver-verified hard-met provenance")
                .isTrue()
            val last = results.last()
            assertThat(last.progressPercentage)
                .describedAs("the soft fallback must still run and deliver its guaranteed final send")
                .isEqualTo(100)
        }

    /**
     * P2a fallback lock: with an unreachable required target (AP 99), the hard leg is INFEASIBLE and
     * the production orchestration must fall back to the soft (penalized) model and still deliver a
     * final build — never an empty flow. Runs the REAL production path (wall-clock), so the pool is
     * tiny and the duration short.
     */
    @Test
    fun `P2a falls back to the soft model when targets are unreachable`(): Unit =
        runBlocking {
            val p =
                params(200, useRunes = false, useSublimations = false).copy(
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                                TargetStat(Characteristic.ACTION_POINT, 99)
                            )
                        ),
                    searchDuration = 10.seconds
                )
            val results =
                WakfuBestBuildFinderAlgorithm
                    .mostMasteriesHardThenSoft(p, smallPool(), WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations)
                    .toList()
            val last = results.last()
            assertThat(last.progressPercentage).describedAs("the soft fallback must deliver its guaranteed final send").isEqualTo(100)
            assertThat(last.individual.equipments).isNotEmpty()
        }

    /**
     * P2a E1 timing: the hard leg's time-to-OPTIMAL on the production F5@245 shape — the 2-5×
     * hypothesis test vs the ~147 s soft baseline (docs/MOST_MASTERIES_PERF_PLAN.md P2a). The
     * hard-leg optimum score must equal the banked soft optimum (10705: the soft optimum meets its
     * targets, so the two coincide). `WAKFU_MM_P2A=1`; det via `WAKFU_MM_P2A_DET` (600).
     */
    @Test
    fun `manual P2a hard leg timing at 245`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_P2A") == "1")
            val det = System.getenv("WAKFU_MM_P2A_DET")?.toDoubleOrNull() ?: 600.0
            val level = 245
            val p =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                                TargetStat(Characteristic.ACTION_POINT, 12),
                                TargetStat(Characteristic.MOVEMENT_POINT, 6),
                                TargetStat(Characteristic.HP, 2000),
                                TargetStat(Characteristic.CRITICAL_HIT, 30)
                            )
                        ),
                    searchDuration = 600.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = true,
                    useSublimations = true
                )
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = det,
                    applyDominationOverride = true,
                    maxPresolveIterationsOverride = 1,
                    linearizationLevelOverride = 1
                )
            val t0 = System.nanoTime()
            var last: me.chosante.autobuilder.genetic.SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
            WakfuBuildSolver
                .optimize(p, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations, tuning, hardConstraints = true)
                .collect { r ->
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    println("MM_P2A emit t=${ms}ms objective=${r.matchPercentage} optimal=${r.isOptimal}")
                    last = r
                }
            val wallMs = (System.nanoTime() - t0) / 1_000_000
            println("MM_P2A lvl$level det=$det SUMMARY wallMs=$wallMs optimal=${last?.isOptimal} objective=${last?.matchPercentage} (soft baseline ~147000ms / 10705)")
        }

    /**
     * The DECISIVE M3 sub-measurement: the max UNPENALIZED objective at 245 (distance mastery as the
     * only target — no required stats, so penalty == 1 for every build and CP-SAT's optimum IS the
     * quantity the bound must dominate). Distinguishes "the bound is loose" (optimum ≪ bound) from
     * "the algebra is the problem" (optimum ≈ bound ≫ the with-targets 10705 — a crit/AP-dumped build
     * really reaches that unpenalized score, and an unpenalized U can never certify the targeted one).
     * `WAKFU_MM_M3=1`.
     */
    @Test
    fun `manual M3 unpenalized optimum at 245`() {
        assumeTrue(System.getenv("WAKFU_MM_M3") == "1")
        val level = 245
        val p =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999))),
                searchDuration = 600.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = true,
                useSublimations = true
            )
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val bound =
            MostMasteriesBoundPrototype.bound(p, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations)!!
        val (optimum, optimal) = pinnedOptimum(p, pool, det = 600.0)
        val gapPct = 100.0 * (bound - optimum) / optimum
        println("MM_M3 unpenalized245 bound=$bound optimum=$optimum optimal=$optimal gapPct=${"%.2f".format(gapPct)}")
        assertThat(bound).isGreaterThanOrEqualTo(optimum)
    }

    /**
     * Full-pool F5@245 tightness vs the BANKED proven optimum from the P0/P0.5 runs (10705, scorer
     * units, proven OPTIMAL four times deterministically). `WAKFU_MM_M3=1` to run; prints M2 (bound
     * cost) and M3 (gap %) — the campaign's GO/NO-GO numbers.
     */
    @Test
    fun `manual M3 full pool at 245`() {
        assumeTrue(System.getenv("WAKFU_MM_M3") == "1")
        val level = 245
        val p =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats =
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                            TargetStat(Characteristic.ACTION_POINT, 12),
                            TargetStat(Characteristic.MOVEMENT_POINT, 6),
                            TargetStat(Characteristic.HP, 2000),
                            TargetStat(Characteristic.CRITICAL_HIT, 30)
                        )
                    ),
                searchDuration = 600.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = true,
                useSublimations = true
            )
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val bankedOptimum = 10705L
        val t0 = System.nanoTime()
        val bound =
            MostMasteriesBoundPrototype.bound(
                p,
                pool,
                WakfuBestBuildFinderAlgorithm.runes,
                WakfuBestBuildFinderAlgorithm.sublimations
            )!!
        val boundMs = (System.nanoTime() - t0) / 1_000_000
        val gapPct = 100.0 * (bound - bankedOptimum) / bankedOptimum
        println("MM_M3 f5at245 bound=$bound bankedOptimum=$bankedOptimum gapPct=${"%.2f".format(gapPct)} boundMs=$boundMs")
        assertThat(bound).isGreaterThanOrEqualTo(bankedOptimum)
    }
}
