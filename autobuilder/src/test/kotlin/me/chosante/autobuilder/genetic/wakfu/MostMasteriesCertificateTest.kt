package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpSolverStatus
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * M3-v2 tightness harness (plan §8.9 amendment). Computes the target-aware folded bound on the
 * campaign fixtures and compares against the banked optima (re-banked 2026-10-02 on data 1.93.1.62,
 * both proven OPTIMAL by the production portfolio; the 1.92.1.58 values of §8.1.1 were
 * 67 295 807 882 856 / 10 985):
 *  - S2 frontier soft: folded optimum 67 728 953 322 880;
 *  - S3 DI isolate: core optimum 10 993.
 *
 * The bound must be ≥ the optimum (soundness canary — an under-count here is a bug, not a win);
 * the measured question is the OVERSHOOT ratio and the DP wall time. Measured at CERTIFIER_VERSION
 * 39 on 1.93: S2 +29.56%, S3 +8.11% (v37 on 1.92: +9.87% / +6.77% — the v38 soundness fixes
 * loosened it, see docs/MOST_MASTERIES_PERF_PLAN.md §8.16).
 *
 * ```shell
 * WAKFU_MM_M3V2=1 [WAKFU_MM_M3V2_DEBUG=1] \
 *   ./gradlew :autobuilder:test --tests '*MostMasteriesCertificateTest*'
 * ```
 */
class MostMasteriesCertificateTest {
    // Re-banked 2026-10-02 on data 1.93.1.62 (production portfolio, both OPTIMAL — S2 in 106 s, S3 in
    // 16 s); the 1.92.1.58 optima were 67_295_807_882_856 / 10_985.
    private val s2Optimum = 67_728_953_322_880L
    private val s3Optimum = 10_993L

    /**
     * CI SOUNDNESS LOCK for the 2026-07-14 review findings A#1/A#2 — the two shapes where the
     * certificate UNDER-counted (a false badge):
     *  - A#1: the AT_MOST condition rejection accumulated per-option CEILs, denying a real build
     *    at crit 3+5=8 ≤ 10 its Constance-class sub (fixed by the LOW under-approximating dims);
     *  - A#2: world B capped the WHOLE objective at the CRITICAL_MASTERY_AT_MOST threshold while
     *    M sums every requested mastery, and priced the sub's +CC side credit nowhere.
     * Each fixture pins CP-SAT (1-worker, seeded) and asserts bound ≥ its optimum.
     */
    @Test
    fun `certificate bound covers conditional-sub optima (A1 ceil rejection, A2 world B)`(): Unit =
        kotlinx.coroutines.runBlocking {
            fun item(
                id: Int,
                type: ItemType,
                rarity: Rarity = Rarity.LEGENDARY,
                stats: Map<Characteristic, Int>,
            ) = me.chosante.common.Equipment(
                equipmentId = id,
                guiId = id,
                level = 200,
                name = me.chosante.common.I18nText("item$id", "item$id", "", ""),
                rarity = rarity,
                itemType = type,
                characteristics = stats,
                maxShardSlots = 3
            )

            fun epicConditionalSub(
                stateId: Int,
                condType: me.chosante.common.SublimationConditionType,
                threshold: Int,
                effects: List<me.chosante.common.SublimationEffect>,
            ) = me.chosante.common.Sublimation(
                stateId = stateId,
                name = me.chosante.common.I18nText("sub$stateId", "sub$stateId", "", ""),
                rarity = me.chosante.common.SublimationRarity.EPIC,
                maxStackLevel = 1,
                kind = me.chosante.common.SublimationKind.STATIC_CONDITIONAL,
                solverChoosable = true,
                condition = me.chosante.common.SublimationCondition(condType, value = threshold),
                effects = effects
            )

            data class Fixture(
                val label: String,
                val pool: Map<ItemType, List<me.chosante.common.Equipment>>,
                val targets: List<TargetStat>,
                val subs: List<me.chosante.common.Sublimation>,
            )

            val fixtures =
                listOf(
                    // A#1: base crit 3 + cape's +5 = 8 ≤ 10 — the optimum carries the CRIT_AT_MOST-10
                    // sub; the old ceil-accumulated rejection (1+1 buckets > 1) denied it.
                    Fixture(
                        "A1-ceil-rejection",
                        listOf(
                            item(11, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 300)),
                            item(12, ItemType.CAPE, Rarity.EPIC, mapOf(Characteristic.MASTERY_DISTANCE to 250, Characteristic.CRITICAL_HIT to 5))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)),
                        listOf(
                            epicConditionalSub(
                                9001,
                                me.chosante.common.SublimationConditionType.CRIT_AT_MOST,
                                10,
                                listOf(
                                    me.chosante.common.SublimationEffect
                                        .Flat(Characteristic.DAMAGE_INFLICTED, 20)
                                )
                            )
                        )
                    ),
                    // A#2: two requested masteries + a CC target; the optimum carries the
                    // CRITICAL_MASTERY_AT_MOST-0 sub for its +30 CC while stacking melee mastery —
                    // the old world B capped the whole M at 0 and world A never credited the +30 CC.
                    Fixture(
                        "A2-worldB",
                        listOf(
                            item(21, ItemType.HELMET, Rarity.EPIC, mapOf(Characteristic.MASTERY_MELEE to 400)),
                            item(22, ItemType.CAPE, stats = mapOf(Characteristic.MASTERY_MELEE to 200, Characteristic.HP to 100))
                        ).groupBy { it.itemType },
                        listOf(
                            TargetStat(Characteristic.MASTERY_MELEE, 9999),
                            TargetStat(Characteristic.MASTERY_CRITICAL, 9999),
                            TargetStat(Characteristic.CRITICAL_HIT, 30)
                        ),
                        listOf(
                            epicConditionalSub(
                                9002,
                                me.chosante.common.SublimationConditionType.CRITICAL_MASTERY_AT_MOST,
                                0,
                                listOf(
                                    me.chosante.common.SublimationEffect
                                        .Flat(Characteristic.CRITICAL_HIT, 30)
                                )
                            )
                        )
                    )
                )
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    randomSeed = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = 60.0
                )
            for (f in fixtures) {
                val p =
                    WakfuBestBuildParams(
                        character = Character(CharacterClass.CRA, 200, 0, CharacterSkills(200)),
                        targetStats = TargetStats(f.targets),
                        searchDuration = 60.seconds,
                        stopWhenBuildMatch = false,
                        maxRarity = Rarity.EPIC,
                        forcedItems = emptyList(),
                        excludedItems = emptyList(),
                        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                        useRunes = false,
                        useSublimations = true
                    )
                var last: me.chosante.autobuilder.genetic.SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
                WakfuBuildSolver
                    .optimize(p, f.pool, emptyList(), f.subs, tuning, hardConstraints = false)
                    .collect { last = it }
                val final = requireNotNull(last) { "${f.label}: the soft solve emitted nothing" }
                val incumbent = requireNotNull(final.mostMasteriesObjective) { "${f.label}: no comparable objective stamped" }
                val bound =
                    requireNotNull(
                        MostMasteriesCertificate.bound(p, f.pool, emptyList(), f.subs)
                    ) { "${f.label}: the certificate bailed on a supported shape" }
                println("MM_CERT_COND_LOCK ${f.label} incumbent=$incumbent bound=${bound.foldedBound} optimal=${final.isOptimal}")
                assertThat(bound.foldedBound)
                    .describedAs("${f.label}: SOUNDNESS — the certificate must never under-count the CP-SAT soft objective")
                    .isGreaterThanOrEqualTo(incumbent)
            }
        }

    /**
     * CI SOUNDNESS LOCK for the 2026-10-01 pre-release review — each fixture is a tiny pool where the
     * certificate used to UNDER-count the proven CP-SAT soft optimum (a false badge):
     *  - paired-MP: the Major "Movement Point and damage" point (+1 MP) was never credited;
     *  - armure: Armure lourde's MAX_MP −1 lowered the MP CAP instead of debiting 1 MP;
     *  - passive: a selected passive's flat +1 MP (Zobal "Regard masqué") was ignored;
     *  - max-ap: the assume-AP low read ignored an item's MAX_ACTION_POINT −1 (real pre-combat AP = 6);
     *  - soc-crit: the assume-CC fold stopped at `threshold + own`, missing a start-of-combat +12 crit
     *    (and that +12 even fed the condition's low read, rejecting the carrier);
     *  - soc-critm: world B's CRITICAL_MASTERY_AT_MOST cap ignored start-of-combat crit mastery;
     *  - block-only: a block-only sub was dropped, so the BLOCK_AT_LEAST gate denied its carrier;
     *  - hp-order: the Intelligence %HP was applied before the Strength HP points, leaving them
     *    unscaled (CP-SAT reaches 9 030 HP with %HP 50 + HP 48 at level 200).
     */
    @Test
    fun `certificate bound covers the pre-release review under-counts`(): Unit =
        kotlinx.coroutines.runBlocking {
            fun item(
                id: Int,
                type: ItemType,
                rarity: Rarity = Rarity.LEGENDARY,
                stats: Map<Characteristic, Int>,
            ) = me.chosante.common.Equipment(
                equipmentId = id,
                guiId = id,
                level = 200,
                name = me.chosante.common.I18nText("item$id", "item$id", "", ""),
                rarity = rarity,
                itemType = type,
                characteristics = stats,
                maxShardSlots = 3
            )

            fun sub(
                stateId: Int,
                rarity: me.chosante.common.SublimationRarity,
                condition: me.chosante.common.SublimationCondition?,
                vararg effects: me.chosante.common.SublimationEffect,
            ) = me.chosante.common.Sublimation(
                stateId = stateId,
                name = me.chosante.common.I18nText("sub$stateId", "sub$stateId", "", ""),
                rarity = rarity,
                maxStackLevel = 1,
                kind =
                    if (condition == null) {
                        me.chosante.common.SublimationKind.FLAT
                    } else {
                        me.chosante.common.SublimationKind.STATIC_CONDITIONAL
                    },
                solverChoosable = true,
                condition = condition,
                effects = effects.toList()
            )

            fun flat(
                c: Characteristic,
                v: Int,
                beforeCombat: Boolean,
            ) = me.chosante.common.SublimationEffect
                .Flat(c, v, appliesBeforeCombat = beforeCombat)

            fun cond(
                type: me.chosante.common.SublimationConditionType,
                value: Int,
            ) = me.chosante.common.SublimationCondition(type, value = value)

            data class Fixture(
                val label: String,
                val pool: Map<ItemType, List<me.chosante.common.Equipment>>,
                val targets: List<TargetStat>,
                val subs: List<me.chosante.common.Sublimation>,
                val clazz: CharacterClass = CharacterClass.CRA,
                val passives: List<String> = emptyList(),
            )
            val epic = me.chosante.common.SublimationRarity.EPIC
            val normal = me.chosante.common.SublimationRarity.NORMAL
            val fixtures =
                listOf(
                    Fixture(
                        "paired-MP",
                        listOf(
                            item(31, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 300)),
                            item(32, ItemType.CAPE, stats = mapOf(Characteristic.MASTERY_DISTANCE to 200))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.MOVEMENT_POINT, 4)),
                        emptyList()
                    ),
                    Fixture(
                        "armure",
                        listOf(
                            item(41, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 300)),
                            item(42, ItemType.BOOTS, stats = mapOf(Characteristic.MASTERY_DISTANCE to 100, Characteristic.MOVEMENT_POINT to 2)),
                            item(43, ItemType.CAPE, stats = mapOf(Characteristic.MASTERY_DISTANCE to 200))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.MOVEMENT_POINT, 5)),
                        listOf(
                            sub(
                                9101,
                                normal,
                                null,
                                flat(Characteristic.MAX_MOVEMENT_POINT, -1, true),
                                flat(Characteristic.DAMAGE_INFLICTED, 10, true)
                            )
                        )
                    ),
                    Fixture(
                        "passive",
                        listOf(
                            item(51, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 300)),
                            item(52, ItemType.CAPE, stats = mapOf(Characteristic.MASTERY_DISTANCE to 200))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.MOVEMENT_POINT, 5)),
                        emptyList(),
                        clazz = CharacterClass.ZOBAL,
                        passives = listOf("Regard masqué")
                    ),
                    Fixture(
                        "max-ap",
                        listOf(
                            item(61, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 300)),
                            item(
                                62,
                                ItemType.BOOTS,
                                Rarity.EPIC,
                                mapOf(Characteristic.MASTERY_DISTANCE to 500, Characteristic.ACTION_POINT to 1, Characteristic.MAX_ACTION_POINT to -1)
                            ),
                            item(63, ItemType.BOOTS, stats = mapOf(Characteristic.MASTERY_DISTANCE to 400, Characteristic.ACTION_POINT to 1))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)),
                        listOf(
                            sub(
                                9102,
                                epic,
                                cond(me.chosante.common.SublimationConditionType.AP_AT_MOST, 6),
                                flat(Characteristic.DAMAGE_INFLICTED, 15, false)
                            )
                        )
                    ),
                    Fixture(
                        "soc-crit",
                        listOf(
                            item(71, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 300, Characteristic.CRITICAL_HIT to 5)),
                            item(72, ItemType.CAPE, Rarity.EPIC, mapOf(Characteristic.MASTERY_DISTANCE to 200))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.CRITICAL_HIT, 20)),
                        listOf(
                            sub(
                                9103,
                                epic,
                                cond(me.chosante.common.SublimationConditionType.CRIT_AT_MOST, 10),
                                flat(Characteristic.DAMAGE_INFLICTED, 20, false)
                            ),
                            sub(9104, normal, null, flat(Characteristic.CRITICAL_HIT, 12, false))
                        )
                    ),
                    Fixture(
                        "soc-critm",
                        listOf(
                            item(81, ItemType.HELMET, stats = mapOf(Characteristic.HP to 100)),
                            item(82, ItemType.CAPE, Rarity.EPIC, mapOf(Characteristic.HP to 50))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.MASTERY_CRITICAL, 9999), TargetStat(Characteristic.CRITICAL_HIT, 50)),
                        listOf(
                            sub(
                                9105,
                                epic,
                                cond(me.chosante.common.SublimationConditionType.CRITICAL_MASTERY_AT_MOST, 0),
                                flat(Characteristic.CRITICAL_HIT, 30, false)
                            ),
                            sub(9106, normal, null, flat(Characteristic.MASTERY_CRITICAL, 36, false))
                        )
                    ),
                    Fixture(
                        "block-only",
                        listOf(
                            item(91, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 300, Characteristic.BLOCK_PERCENTAGE to 12)),
                            item(92, ItemType.CAPE, Rarity.EPIC, mapOf(Characteristic.MASTERY_DISTANCE to 200))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)),
                        listOf(
                            sub(
                                9107,
                                epic,
                                // 40 = item 12 + the Luck "% Block" skill's 20 + the block-only sub's 9:
                                // without the sub the gate cannot open (20 opened it through skills alone).
                                cond(me.chosante.common.SublimationConditionType.BLOCK_AT_LEAST, 40),
                                flat(Characteristic.DAMAGE_INFLICTED, 10, false)
                            ),
                            sub(9108, normal, null, flat(Characteristic.BLOCK_PERCENTAGE, 9, true))
                        )
                    ),
                    Fixture(
                        "hp-order",
                        listOf(
                            item(93, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 300)),
                            item(94, ItemType.CAPE, stats = mapOf(Characteristic.MASTERY_DISTANCE to 200))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.HP, 9000)),
                        emptyList()
                    )
                )
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    randomSeed = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = 60.0
                )
            val underCounts = mutableListOf<String>()
            for (f in fixtures) {
                val p =
                    WakfuBestBuildParams(
                        character = Character(f.clazz, 200, 0, CharacterSkills(200)),
                        targetStats = TargetStats(f.targets),
                        searchDuration = 60.seconds,
                        stopWhenBuildMatch = false,
                        maxRarity = Rarity.EPIC,
                        forcedItems = emptyList(),
                        excludedItems = emptyList(),
                        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                        useRunes = false,
                        useSublimations = true,
                        forcedPassives = f.passives
                    )
                var last: me.chosante.autobuilder.genetic.SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
                WakfuBuildSolver
                    .optimize(p, f.pool, emptyList(), f.subs, tuning, hardConstraints = false)
                    .collect { last = it }
                val final = requireNotNull(last) { "${f.label}: the soft solve emitted nothing" }
                val incumbent = requireNotNull(final.mostMasteriesObjective) { "${f.label}: no comparable objective stamped" }
                val bound =
                    requireNotNull(
                        MostMasteriesCertificate.bound(p, f.pool, emptyList(), f.subs)
                    ) { "${f.label}: the certificate bailed on a supported shape" }
                println("MM_CERT_REVIEW_LOCK ${f.label} incumbent=$incumbent bound=${bound.foldedBound} optimal=${final.isOptimal}")
                assertThat(final.isOptimal).describedAs("${f.label}: the tiny pool must be PROVEN, so the lock compares to the optimum").isTrue()
                // Collected, not thrown: one run names EVERY under-counting shape.
                if (bound.foldedBound < incumbent) underCounts += "${f.label}: bound ${bound.foldedBound} < optimum $incumbent"
            }
            assertThat(underCounts)
                .describedAs("SOUNDNESS — the certificate must never under-count the CP-SAT soft optimum")
                .isEmpty()
        }

    /**
     * CI EXACTNESS LOCK for the stage-option Pareto pruning (perf next-steps P2): the pruned DP must return
     * the SAME folded and core bounds as the unpruned one — pruning a dominated option is exact only if every
     * transition and the collapse fold stay monotone in [MostMasteriesCertificate]'s dominance order, which a
     * new option field or a non-monotone fold would silently break. Random seeded pools (1-2 items per slot,
     * epic/relic items, signed AP and −MAX_AP lines, block, 0-2 sockets) with the real sub catalog and runes,
     * × four target shapes × both tiers. No CP-SAT; the UNPRUNED reference DP is the cost (~1 s per case), so CI
     * runs 4 seeds — `WAKFU_MM_PRUNE_LOCK_SEEDS=8` reproduces the 64-case screen the pruning shipped with.
     */
    @Test
    fun `stage-option pruning is bit-identical on seeded random pools`() {
        val slotTypes =
            listOf(
                ItemType.HELMET,
                ItemType.CAPE,
                ItemType.BELT,
                ItemType.BOOTS,
                ItemType.AMULET,
                ItemType.RING,
                ItemType.CHEST_PLATE,
                ItemType.SHOULDER_PADS,
                ItemType.ONE_HANDED_WEAPONS,
                ItemType.OFF_HAND_WEAPONS,
                ItemType.TWO_HANDED_WEAPONS,
                ItemType.EMBLEM
            )
        val palette =
            listOf(
                Characteristic.MASTERY_DISTANCE,
                Characteristic.ACTION_POINT,
                Characteristic.MOVEMENT_POINT,
                Characteristic.CRITICAL_HIT,
                Characteristic.HP,
                Characteristic.DAMAGE_INFLICTED,
                Characteristic.BLOCK_PERCENTAGE,
                Characteristic.MASTERY_BERSERK,
                Characteristic.MAX_ACTION_POINT
            )
        val shapes =
            listOf(
                emptyList(),
                listOf(TargetStat(Characteristic.ACTION_POINT, 9), TargetStat(Characteristic.HP, 3500)),
                listOf(TargetStat(Characteristic.MOVEMENT_POINT, 5), TargetStat(Characteristic.CRITICAL_HIT, 40)),
                listOf(
                    TargetStat(Characteristic.ACTION_POINT, 10),
                    TargetStat(Characteristic.MOVEMENT_POINT, 5),
                    TargetStat(Characteristic.CRITICAL_HIT, 30),
                    TargetStat(Characteristic.HP, 4000)
                )
            )
        val mismatches = mutableListOf<String>()
        var compared = 0
        val seeds = System.getenv("WAKFU_MM_PRUNE_LOCK_SEEDS")?.toLongOrNull() ?: 4L
        for (seed in 1L..seeds) {
            val rng = java.util.Random(seed)
            var id = 0
            val pool =
                slotTypes
                    .flatMap { type ->
                        (0 until 1 + rng.nextInt(2)).map {
                            id++
                            val stats =
                                (0 until 2 + rng.nextInt(4)).associate {
                                    val stat = palette[rng.nextInt(palette.size)]
                                    val magnitude =
                                        when (stat) {
                                            Characteristic.ACTION_POINT, Characteristic.MOVEMENT_POINT ->
                                                1 + rng.nextInt(2) * (if (rng.nextInt(6) == 0) -2 else 1)
                                            Characteristic.MAX_ACTION_POINT -> -1
                                            Characteristic.CRITICAL_HIT -> 1 + rng.nextInt(12)
                                            Characteristic.HP -> 50 + rng.nextInt(500)
                                            Characteristic.DAMAGE_INFLICTED -> 1 + rng.nextInt(12)
                                            Characteristic.BLOCK_PERCENTAGE -> 1 + rng.nextInt(15)
                                            else -> 20 + rng.nextInt(200) * (if (rng.nextInt(5) == 0) -1 else 1)
                                        }
                                    stat to magnitude
                                }
                            val rarity =
                                when (rng.nextInt(8)) {
                                    0 -> Rarity.EPIC
                                    1 -> Rarity.RELIC
                                    else -> Rarity.LEGENDARY
                                }
                            me.chosante.common.Equipment(
                                equipmentId = seed.toInt() * 10_000 + id,
                                guiId = id,
                                level = 200,
                                name = me.chosante.common.I18nText("p2i$seed-$id", "p2i$seed-$id", "", ""),
                                rarity = rarity,
                                itemType = type,
                                characteristics = stats,
                                maxShardSlots = rng.nextInt(3)
                            )
                        }
                    }.groupBy { it.itemType }
            for (targets in shapes) {
                val p =
                    WakfuBestBuildParams(
                        character = Character(CharacterClass.CRA, 200, 0, CharacterSkills(200)),
                        targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)) + targets),
                        searchDuration = 60.seconds,
                        stopWhenBuildMatch = false,
                        maxRarity = Rarity.EPIC,
                        forcedItems = emptyList(),
                        excludedItems = emptyList(),
                        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                        useRunes = true,
                        useSublimations = true
                    )
                for (blockGate in listOf(false, true)) {
                    fun boundOf(prune: Boolean) =
                        MostMasteriesCertificate.bound(
                            p,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations,
                            blockGate = blockGate,
                            pruneDominatedOptions = prune
                        )
                    val reference = boundOf(prune = false)
                    val pruned = boundOf(prune = true)
                    val label = "seed $seed targets=${targets.size} blockGate=$blockGate"
                    if (pruned?.foldedBound != reference?.foldedBound || pruned?.coreBound != reference?.coreBound) {
                        mismatches +=
                            "$label: pruned ${pruned?.foldedBound}/${pruned?.coreBound} vs unpruned " +
                            "${reference?.foldedBound}/${reference?.coreBound}"
                    }
                    compared++
                }
            }
        }
        assertThat(compared).isEqualTo(seeds.toInt() * shapes.size * 2)
        assertThat(mismatches).describedAs("EXACTNESS — pruning dominated stage options must never move a bound").isEmpty()
    }

    /**
     * The most-masteries HARD leg (targets enforced) emits `core × SCALE + bonus`; its stamp must be
     * CONVERTED into the certificate's soft units (× the full-targets penalty multiplier, ≈1e6). The raw
     * stamp produced "proven within ~1 074 318 %" badges (pre-release review 2026-10-01).
     */
    @Test
    fun `hard-leg results are stamped in the certificate's soft units`(): Unit =
        kotlinx.coroutines.runBlocking {
            val pool =
                listOf(
                    me.chosante.common.Equipment(
                        equipmentId = 1,
                        guiId = 1,
                        level = 200,
                        name = me.chosante.common.I18nText("helmet", "helmet", "", ""),
                        rarity = Rarity.LEGENDARY,
                        itemType = ItemType.HELMET,
                        characteristics = mapOf(Characteristic.MASTERY_DISTANCE to 300, Characteristic.MOVEMENT_POINT to 1),
                        maxShardSlots = 0
                    )
                ).groupBy { it.itemType }
            val p =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, 200, 0, CharacterSkills(200)),
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.MOVEMENT_POINT, 4))),
                    searchDuration = 60.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = false,
                    useSublimations = false
                )
            val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 60.0)
            var last: me.chosante.autobuilder.genetic.SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
            WakfuBuildSolver
                .optimize(p, pool, emptyList(), emptyList(), tuning, hardConstraints = true)
                .collect { last = it }
            val final = requireNotNull(last) { "the hard leg emitted nothing" }
            val incumbent = requireNotNull(final.mostMasteriesObjective) { "the hard leg must stamp a (converted) objective" }
            val bound = requireNotNull(MostMasteriesCertificate.bound(p, pool, emptyList(), emptyList()))
            println("MM_CERT_HARD_LEG incumbent=$incumbent bound=${bound.foldedBound}")
            assertThat(bound.foldedBound).describedAs("soundness on the converted stamp").isGreaterThanOrEqualTo(incumbent)
            assertThat(bound.foldedBound.toDouble() / incumbent)
                .describedAs("same units: a one-item pool keeps the certificate near-tight (a raw stamp reads ~1e6×)")
                .isLessThan(2.0)
        }

    /**
     * Design gate for the exact negative-mastery penalty (the measured 91% of the S3 residual):
     * the distribution of NEGATIVE penalized-mastery lines across the lvl-245 domination pool.
     * If one char dominates, a single signed state dim captures most of the penalty; a flat
     * multi-char spread would need the full per-char treatment.
     *
     * ```shell
     * WAKFU_MM_M3V2_NEGSTATS=1 ./gradlew :autobuilder:test --tests '*MostMasteriesCertificateTest*'
     * ```
     */
    @Test
    fun `manual negative-mastery pool statistics at 245`() {
        assumeTrue(System.getenv("WAKFU_MM_M3V2_NEGSTATS") == "1")
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
        val basePool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= p.maxRarity && it.rarity !in p.excludedRarities }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val shape = requireNotNull(dominationShape(p, WakfuBestBuildFinderAlgorithm.sublimations))
        val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool, shape).values.flatten()
        val requested = setOf(Characteristic.MASTERY_DISTANCE)
        val penalized = me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS - requested

        for (char in penalized) {
            val negatives = pool.mapNotNull { e -> e.characteristics[char]?.takeIf { it < 0 } }
            val positives = pool.mapNotNull { e -> e.characteristics[char]?.takeIf { it > 0 } }
            println(
                "MM_NEGSTATS $char negItems=${negatives.size} negSum=${negatives.sum()} negMax=${negatives.minOrNull() ?: 0} " +
                    "posItems=${positives.size} posSum=${positives.sum()} posMax=${positives.maxOrNull() ?: 0}"
            )
        }
        val itemsWithAnyNeg = pool.count { e -> penalized.any { (e.characteristics[it] ?: 0) < 0 } }
        println("MM_NEGSTATS pool=${pool.size} itemsWithAnyNegative=$itemsWithAnyNeg")
    }

    /**
     * Binding-path provenance on S2: reconstructs the argmax state's full path (one option per
     * stage) — the input to the cross-slot coupling work: WHERE does the claimed combination
     * diverge from any real build?
     *
     * ```shell
     * WAKFU_MM_M3V2_PATH=1 ./gradlew :autobuilder:test --tests '*MostMasteriesCertificateTest*'
     * ```
     */
    @Test
    fun `manual S2 binding-path provenance`() {
        assumeTrue(System.getenv("WAKFU_MM_M3V2_PATH") == "1")
        val level = 245
        val p =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats =
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                            TargetStat(Characteristic.ACTION_POINT, 16),
                            TargetStat(Characteristic.MOVEMENT_POINT, 8),
                            TargetStat(Characteristic.CRITICAL_HIT, 100),
                            TargetStat(Characteristic.HP, 12000)
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
        val basePool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= p.maxRarity && it.rarity !in p.excludedRarities }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val shape = requireNotNull(dominationShape(p, WakfuBestBuildFinderAlgorithm.sublimations))
        val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool, shape)
        val r =
            requireNotNull(
                MostMasteriesCertificate.bound(
                    p,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.sublimations,
                    provenance = true
                )
            )
        println("MM_PATH bound=${r.foldedBound} binding=${r.bindingState} wallMs=${r.wallMs}")
        r.bindingPath.forEach { println("MM_PATH $it") }
    }

    /**
     * CI SOUNDNESS LOCK: on small deterministic pools (hand-built + seeded random), the certificate
     * bound must upper-bound the pinned CP-SAT SOFT optimum — an under-count here would let
     * [WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality] award a WRONG "proven within X%"
     * badge. Also locks the [SolverResult.mostMasteriesObjective] stamping end-to-end (the exact
     * value the production proof entry compares).
     */
    @Test
    fun `certificate bound upper-bounds the pinned CP-SAT soft optimum`(): Unit =
        kotlinx.coroutines.runBlocking {
            fun item(
                id: Int,
                type: ItemType,
                rarity: Rarity = Rarity.LEGENDARY,
                stats: Map<Characteristic, Int>,
            ) = me.chosante.common.Equipment(
                equipmentId = id,
                guiId = id,
                level = 200,
                name = me.chosante.common.I18nText("item$id", "item$id", "", ""),
                rarity = rarity,
                itemType = type,
                characteristics = stats,
                maxShardSlots = 3
            )

            val slotTypes =
                listOf(
                    ItemType.HELMET,
                    ItemType.CAPE,
                    ItemType.BELT,
                    ItemType.BOOTS,
                    ItemType.AMULET,
                    ItemType.RING,
                    ItemType.RING,
                    ItemType.CHEST_PLATE,
                    ItemType.TWO_HANDED_WEAPONS
                )
            val statPalette =
                listOf(
                    Characteristic.MASTERY_DISTANCE,
                    Characteristic.ACTION_POINT,
                    Characteristic.MOVEMENT_POINT,
                    Characteristic.CRITICAL_HIT,
                    Characteristic.HP,
                    Characteristic.DAMAGE_INFLICTED,
                    Characteristic.MASTERY_BERSERK
                )
            val fixtures =
                (1L..3L).map { seed ->
                    val rng = java.util.Random(seed)
                    "seed$seed" to
                        slotTypes
                            .mapIndexed { i, type ->
                                val stats =
                                    (0 until 2 + rng.nextInt(3)).associate {
                                        val stat = statPalette[rng.nextInt(statPalette.size)]
                                        val magnitude =
                                            when (stat) {
                                                Characteristic.ACTION_POINT, Characteristic.MOVEMENT_POINT -> 1
                                                Characteristic.CRITICAL_HIT -> 2 + rng.nextInt(8)
                                                Characteristic.HP -> 50 + rng.nextInt(300)
                                                Characteristic.DAMAGE_INFLICTED -> 1 + rng.nextInt(10)
                                                else -> 20 + rng.nextInt(120) * (if (rng.nextInt(5) == 0) -1 else 1)
                                            }
                                        stat to magnitude
                                    }
                                item(seed.toInt() * 100 + i, type, if (i == 3) Rarity.EPIC else Rarity.LEGENDARY, stats)
                            }.groupBy { it.itemType }
                }

            val p =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, 200, 0, CharacterSkills(200)),
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                                TargetStat(Characteristic.ACTION_POINT, 8),
                                TargetStat(Characteristic.HP, 3000)
                            )
                        ),
                    searchDuration = 60.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = true,
                    useSublimations = true
                )
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    randomSeed = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = 60.0
                )

            for ((label, pool) in fixtures) {
                var last: me.chosante.autobuilder.genetic.SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
                WakfuBuildSolver
                    .optimize(p, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations, tuning, hardConstraints = false)
                    .collect { last = it }
                val final = requireNotNull(last) { "$label: the soft solve emitted nothing" }
                val incumbent = requireNotNull(final.mostMasteriesObjective) { "$label: the soft leg must stamp the comparable objective" }
                val bound =
                    requireNotNull(
                        MostMasteriesCertificate.bound(p, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations)
                    ) { "$label: the certificate bailed on a supported shape" }
                println("MM_CERT_LOCK $label incumbent=$incumbent bound=${bound.foldedBound} optimal=${final.isOptimal}")
                assertThat(bound.foldedBound)
                    .describedAs("$label: SOUNDNESS — the certificate must never under-count the CP-SAT soft objective")
                    .isGreaterThanOrEqualTo(incumbent)
            }
        }

    /**
     * Inventory of the solver-choosable sublimations' CONDITIONS + per-axis credits at 245 — sizes
     * the M3-v2 exact-condition world split (AT_MOST/EXACT conditions need worlds; AT_LEAST gates
     * soundly on the over-counted dims).
     *
     * ```shell
     * WAKFU_MM_M3V2_INVENTORY=1 ./gradlew :autobuilder:test --tests '*MostMasteriesCertificateTest*'
     * ```
     */
    @Test
    fun `manual choosable sub condition inventory`() {
        assumeTrue(System.getenv("WAKFU_MM_M3V2_INVENTORY") == "1")
        val level = 245
        for (sub in WakfuBestBuildFinderAlgorithm.sublimations) {
            if (!sub.solverChoosable) continue
            val effects =
                sub.effects.joinToString(" | ") { eff ->
                    when (eff) {
                        is me.chosante.common.SublimationEffect.StatEffect ->
                            "${eff.characteristic}=${eff.magnitudeAtLevel(level)}${if (eff.scenarioGate != null) " [gated]" else ""}"
                        is me.chosante.common.SublimationEffect.PerStatStep -> "ramp ${eff.source}->${eff.target} cap=${eff.cap}"
                        is me.chosante.common.SublimationEffect.Conversion -> "conv ${eff.from}->${eff.to}"
                        is me.chosante.common.SublimationEffect.BestElementConcentration -> "bestElemDI=${eff.damageInflictedBonus}"
                    }
                }
            println(
                "MM_M3V2_INV ${sub.name.fr} rarity=${sub.rarity} copies=${sub.maxCopies} " +
                    "cond=${sub.condition?.type ?: "-"} v=${sub.condition?.value ?: "-"} :: $effects"
            )
        }
    }

    /**
     * Attribution of the S3 core-floor overshoot (+8.41%): re-computes the bound with each credit
     * layer DIAGNOSTICALLY removed (unsound — never a bound; only the DELTA vs the full bound is
     * read). The biggest delta names the next relaxation worth modeling exactly.
     *
     * ```shell
     * WAKFU_MM_M3V2_ATTRIB=1 ./gradlew :autobuilder:test --tests '*MostMasteriesCertificateTest*'
     * ```
     */
    @Test
    fun `manual M3-v2 core-floor attribution on S3`() {
        assumeTrue(System.getenv("WAKFU_MM_M3V2_ATTRIB") == "1")
        val level = 245

        fun params(required: List<TargetStat>) =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)) + required),
                searchDuration = 600.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = true,
                useSublimations = true
            )

        val frontier =
            listOf(
                TargetStat(Characteristic.ACTION_POINT, 16),
                TargetStat(Characteristic.MOVEMENT_POINT, 8),
                TargetStat(Characteristic.CRITICAL_HIT, 100),
                TargetStat(Characteristic.HP, 12000)
            )
        val flags =
            listOf(
                "noCondSubs",
                "noSubs",
                "noSkills",
                "noRunes",
                "noEpicSubs",
                "noNormalSubs",
                "noRelicSubs",
                "noRamps",
                "noSecretCritique",
                "netNegatives"
            )

        // S2 at the COARSE grid (15 s/run — the grid screen proved it bound-identical to fine).
        MostMasteriesCertificate.ccStep = 10
        MostMasteriesCertificate.hpStep = 500
        try {
            for ((label, required, optimum) in listOf(Triple("S3", emptyList<TargetStat>(), s3Optimum), Triple("S2", frontier, s2Optimum))) {
                val p = params(required)
                val basePool =
                    WakfuBestBuildFinderAlgorithm.equipments
                        .filter { it.rarity <= p.maxRarity && it.rarity !in p.excludedRarities }
                        .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                        .groupBy { it.itemType }
                val shape = requireNotNull(dominationShape(p, WakfuBestBuildFinderAlgorithm.sublimations))
                val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool, shape)

                fun run(diag: Set<String>): MostMasteriesCertificate.Result =
                    requireNotNull(
                        MostMasteriesCertificate.bound(
                            p,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations,
                            diag = diag
                        )
                    )

                val full = run(emptySet())
                val value = { r: MostMasteriesCertificate.Result -> if (required.isEmpty()) r.coreBound else r.foldedBound }
                println("MM_M3V2_ATTRIB shape=$label full=${value(full)} optimum=$optimum overshoot=${value(full) - optimum}")
                println("MM_M3V2_ATTRIB shape=$label binding: ${full.bindingState}")
                for (flag in flags) {
                    val without = run(setOf(flag))
                    println("MM_M3V2_ATTRIB shape=$label $flag bound=${value(without)} delta=${value(full) - value(without)}")
                }
            }
        } finally {
            MostMasteriesCertificate.ccStep = 10
            MostMasteriesCertificate.hpStep = 500
        }
    }

    /**
     * Grid → tightness/states/heap profile on S2 (maintainer scenario 2026-07-13: the certificate
     * as a BACKUP on low-core machines, where the 1-worker proof takes 15-20 min — but those
     * machines are also low-RAM, so the fine grid's ~6 GB heap is the blocker). Screens coarser
     * grids (always sound — rounding is UP) for the ≤1-2 GB point and its tightness price.
     *
     * ```shell
     * WAKFU_MM_M3V2_GRIDS=1 ./gradlew :autobuilder:test --tests '*MostMasteriesCertificateTest*'
     * ```
     */
    @Test
    fun `manual M3-v2 grid profile on S2`() {
        assumeTrue(System.getenv("WAKFU_MM_M3V2_GRIDS") == "1")
        val level = 245
        val p =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats =
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                            TargetStat(Characteristic.ACTION_POINT, 16),
                            TargetStat(Characteristic.MOVEMENT_POINT, 8),
                            TargetStat(Characteristic.CRITICAL_HIT, 100),
                            TargetStat(Characteristic.HP, 12000)
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
        val basePool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= p.maxRarity && it.rarity !in p.excludedRarities }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val shape = requireNotNull(dominationShape(p, WakfuBestBuildFinderAlgorithm.sublimations))
        val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool, shape)

        // (di, cc, hp) steps, coarse → fine so the cheap points land first. One grid per JVM via
        // WAKFU_MM_M3V2_GRIDS_ONLY="di,cc,hp" (a big-heap grid dying must not eat the others).
        val grids =
            System.getenv("WAKFU_MM_M3V2_GRIDS_ONLY")?.split(',')?.map { it.trim().toInt() }?.let {
                listOf(Triple(it[0], it[1], it[2]))
            } ?: listOf(Triple(2, 10, 500), Triple(1, 10, 500), Triple(1, 5, 250), Triple(1, 2, 100))
        try {
            for ((di, cc, hp) in grids) {
                MostMasteriesCertificate.diStep = di
                MostMasteriesCertificate.ccStep = cc
                MostMasteriesCertificate.hpStep = hp
                val r =
                    requireNotNull(
                        MostMasteriesCertificate.bound(
                            p,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations
                        )
                    )
                val ratio = r.foldedBound.toDouble() / s2Optimum
                println(
                    "MM_M3V2_GRID di=$di cc=$cc hp=$hp bound=${r.foldedBound} " +
                        "overshoot=+${"%.2f".format(Locale.ROOT, (ratio - 1) * 100)}% states=${r.states} " +
                        "estHeapMb=${r.states * 60L / 1_048_576} wallMs=${r.wallMs}"
                )
            }
        } finally {
            MostMasteriesCertificate.diStep = 1
            MostMasteriesCertificate.ccStep = 10
            MostMasteriesCertificate.hpStep = 500
        }
    }

    /**
     * The "ultra-precise certifier" RACE (maintainer ask 2026-07-13): on the S2 fallback shape —
     * where CP-SAT never proves within a user budget — measure, wall-to-wall on the same machine:
     *  1. the PRODUCTION solver leg (multi-worker, domination, 600 s budget): first emission,
     *     time-to-best-incumbent, final status;
     *  2. the M3-v2 DP bound: wall + the "proven within X%" it would award against the final
     *     incumbent.
     * The decision readout: a badge "proven within X%" becomes available at
     * `max(DP wall, time-to-incumbent)` vs the solver's own never-arriving OPTIMAL — i.e. how many
     * minutes the certificate saves and at what X. Runs SEQUENTIALLY (each leg gets the whole
     * machine); production would pay a small concurrency tax instead.
     *
     * ```shell
     * WAKFU_MM_M3V2_RACE=1 [WAKFU_MM_M3V2_RACE_SECONDS=600] \
     *   ./gradlew :autobuilder:test --tests '*MostMasteriesCertificateTest*'
     * ```
     */
    @Test
    fun `manual M3-v2 certificate race on S2`(): Unit =
        kotlinx.coroutines.runBlocking {
            assumeTrue(System.getenv("WAKFU_MM_M3V2_RACE") == "1")
            val seconds = System.getenv("WAKFU_MM_M3V2_RACE_SECONDS")?.toLongOrNull() ?: 600L
            val level = 245
            val p =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                                TargetStat(Characteristic.ACTION_POINT, 16),
                                TargetStat(Characteristic.MOVEMENT_POINT, 8),
                                TargetStat(Characteristic.CRITICAL_HIT, 100),
                                TargetStat(Characteristic.HP, 12000)
                            )
                        ),
                    searchDuration = seconds.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = true,
                    useSublimations = true
                )
            val basePool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= p.maxRarity && it.rarity !in p.excludedRarities }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            WakfuBuildSolver.warmUp()

            // Leg 1 — the PRODUCTION soft solve (tuning = null ⇒ wall-clock budget, domination,
            // default workers). Emission trajectory in scored units + final raw folded incumbent.
            val termination =
                java.util.concurrent.atomic
                    .AtomicReference<WakfuBuildSolver.SolveOutcome?>(null)
            val t0 = System.nanoTime()
            var firstEmissionMs = -1L
            var bestScore = java.math.BigDecimal.ZERO
            var bestScoreAtMs = -1L
            WakfuBuildSolver
                .optimize(
                    p,
                    basePool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.sublimations,
                    tuning = null,
                    hardConstraints = false,
                    onTermination = { termination.set(it) }
                ).collect { result ->
                    val tMs = (System.nanoTime() - t0) / 1_000_000
                    if (firstEmissionMs < 0) firstEmissionMs = tMs
                    if (result.matchPercentage > bestScore) {
                        bestScore = result.matchPercentage
                        bestScoreAtMs = tMs
                    }
                    println("MM_M3V2_RACE EMIT tMs=$tMs score=${result.matchPercentage} optimal=${result.isOptimal}")
                }
            val solverWallMs = (System.nanoTime() - t0) / 1_000_000
            val outcome = termination.get()
            val incumbent = outcome?.objectiveValue
            println(
                "MM_M3V2_RACE SOLVER wallMs=$solverWallMs status=${outcome?.status ?: "NA"} incumbentRaw=${incumbent ?: "NA"} " +
                    "firstEmitMs=$firstEmissionMs bestScore=$bestScore bestScoreAtMs=$bestScoreAtMs detUsed=${outcome?.deterministicTime ?: "NA"}"
            )

            // Leg 2 — the DP bound, alone on the machine.
            val shape = requireNotNull(dominationShape(p, WakfuBestBuildFinderAlgorithm.sublimations))
            val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool, shape)
            val bound =
                requireNotNull(
                    MostMasteriesCertificate.bound(
                        p,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations
                    )
                )
            val withinPct =
                if (incumbent != null && incumbent > 0) {
                    "%.2f".format(java.util.Locale.ROOT, (bound.foldedBound.toDouble() / incumbent - 1) * 100)
                } else {
                    "NA"
                }
            println(
                "MM_M3V2_RACE VERDICT dpWallMs=${bound.wallMs} bound=${bound.foldedBound} incumbentRaw=${incumbent ?: "NA"} " +
                    "provenWithinPct=$withinPct badgeAvailableAtMs=${maxOf(bound.wallMs, bestScoreAtMs)} " +
                    "solverProvedOptimal=${outcome?.status == CpSolverStatus.OPTIMAL} solverWallMs=$solverWallMs"
            )
        }

    /**
     * §8.15 P&B-2 PROBE — fix/exclude DD-B&B branching on the S2 binding RINGS choice (quick
     * tier, oracle incumbent = the banked optimum). The decisive question: does EXCLUDING the
     * binding choice drop the bound, or does a phantom substitute keep it flat (flat ⇒ fanout
     * explosion ⇒ the §8.15 NO-GO exit)? Chain: exclude the current binding choice, re-bound,
     * repeat; node 0 also measures the FIX child (only that pair allowed).
     *
     * ```shell
     * WAKFU_MM_PNB2=1 ./gradlew :autobuilder:test --tests '*MostMasteriesCertificateTest*'
     * ```
     */
    @Test
    fun `manual P&B-2 fix-exclude probe on S2`() {
        assumeTrue(System.getenv("WAKFU_MM_PNB2") == "1")
        val level = 245
        val p =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats =
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                            TargetStat(Characteristic.ACTION_POINT, 16),
                            TargetStat(Characteristic.MOVEMENT_POINT, 8),
                            TargetStat(Characteristic.CRITICAL_HIT, 100),
                            TargetStat(Characteristic.HP, 12000)
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
        val basePool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= p.maxRarity && it.rarity !in p.excludedRarities }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val shape = requireNotNull(dominationShape(p, WakfuBestBuildFinderAlgorithm.sublimations))
        val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool, shape)

        fun quickBound(
            provenance: Boolean,
            veto: ((String, String) -> Boolean)?,
        ): MostMasteriesCertificate.Result =
            requireNotNull(
                MostMasteriesCertificate.bound(
                    p,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.sublimations,
                    provenance = provenance,
                    blockGate = false,
                    optionVeto = veto
                )
            )

        fun ringsChoiceOf(r: MostMasteriesCertificate.Result): String? =
            r.bindingPath
                .firstOrNull { it.startsWith("rings: ") }
                ?.removePrefix("rings: ")
                ?.substringBefore(" (m=")

        val excluded = mutableSetOf<String>()
        var node = 0
        while (node < 7) {
            val r = quickBound(provenance = true) { stage, src -> stage == "rings" && src in excluded }
            val over = 100.0 * r.foldedBound / s2Optimum - 100.0
            val choice = ringsChoiceOf(r)
            println(
                "MM_PNB2 node=$node excluded=${excluded.size} bound=${r.foldedBound} " +
                    "overshoot=+${"%.2f".format(Locale.ROOT, over)}% wallMs=${r.wallMs} bindingRings=$choice"
            )
            if (r.foldedBound <= s2Optimum) {
                println("MM_PNB2 EXCLUDE-CHAIN CLOSED at node=$node (bound <= incumbent)")
                break
            }
            if (choice == null) {
                println("MM_PNB2 ABORT: no rings choice on the binding path")
                break
            }
            if (node == 0) {
                val fix = quickBound(provenance = false) { stage, src -> stage == "rings" && src != choice }
                val fixOver = 100.0 * fix.foldedBound / s2Optimum - 100.0
                println(
                    "MM_PNB2 FIX-CHILD choice=$choice bound=${fix.foldedBound} " +
                        "overshoot=+${"%.2f".format(Locale.ROOT, fixOver)}% wallMs=${fix.wallMs}"
                )
            }
            excluded += choice
            node++
        }
    }

    @Test
    fun `manual M3-v2 tightness on S2 and S3`() {
        assumeTrue(System.getenv("WAKFU_MM_M3V2") == "1")
        val debug = System.getenv("WAKFU_MM_M3V2_DEBUG") == "1"
        val level = 245

        fun params(requiredTargets: List<TargetStat>) =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)) + requiredTargets),
                searchDuration = 600.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                useRunes = true,
                useSublimations = true
            )

        val frontier =
            listOf(
                TargetStat(Characteristic.ACTION_POINT, 16),
                TargetStat(Characteristic.MOVEMENT_POINT, 8),
                TargetStat(Characteristic.CRITICAL_HIT, 100),
                TargetStat(Characteristic.HP, 12000)
            )

        for (
        (label, required, optimum) in
        listOf(
            Triple("S2", frontier, s2Optimum),
            Triple("S3", emptyList(), s3Optimum)
        )
        ) {
            val p = params(required)
            val basePool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= p.maxRarity && it.rarity !in p.excludedRarities }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val shape = requireNotNull(dominationShape(p, WakfuBestBuildFinderAlgorithm.sublimations))
            val pool = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(basePool, shape)

            val result =
                MostMasteriesCertificate.bound(
                    p,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.sublimations,
                    debug = debug
                )
            checkNotNull(result) { "$label: the prototype bailed on a supported shape" }

            val value = if (required.isEmpty()) result.coreBound else result.foldedBound
            assertThat(value)
                .describedAs("$label: SOUNDNESS — the bound must never under-count the banked optimum")
                .isGreaterThanOrEqualTo(optimum)
            val ratio = value.toDouble() / optimum
            println(
                "MM_M3V2 RESULT shape=$label bound=$value optimum=$optimum " +
                    "overshoot=${"%.4f".format(Locale.ROOT, ratio)} (+${"%.2f".format(Locale.ROOT, (ratio - 1) * 100)}%) " +
                    "coreBound=${result.coreBound} states=${result.states} wallMs=${result.wallMs}"
            )
        }
    }
}
