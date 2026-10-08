package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.SublimationConditionType
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * S4-1 harness (plan §9): the max-damage SOFT-leg bound prototype.
 *
 * 1. Soundness lock on seeded small pools: `bound ≥ maxDamageObjective` for the pinned 1-worker
 *    CP-SAT soft solve — the same shape as MM_CERT_LOCK, transposed to the damage core.
 * 2. Tightness read on the canonical S4 fixture (frontier targets, lvl 245, full pool): bound vs
 *    the banked oracle incumbent (S4-0). Manual — the full-pool DP takes tens of seconds.
 *
 * ```shell
 * ./gradlew --stop   # the daemon freezes its env snapshot
 * WAKFU_S4_PROTO=1 [WAKFU_S4_ORACLE=<incumbent>] \
 *   ./gradlew :autobuilder:test --tests '*MaxDamageSoftCertificateTest*' --rerun-tasks
 * ```
 */
class MaxDamageSoftCertificateTest {
    // PRODUCTION PARITY BY DEFAULT (P3 param-diff, 2026-07-19): every research-harness read used
    // to default each modeling flag OFF (opt-in `== "1"`), so a probe that forgot one env flag
    // silently priced a DIFFERENT model than the production μ-pass read on the same world (~2%
    // apart on IOP-200 secZero). Defaults now mirror `refineWorld` (opt-OUT via `=0` / explicit
    // value); set the env only to deviate from production.
    private fun ccSupportLambda(): Long = System.getenv("WAKFU_S4_CC_LAMBDA")?.toLongOrNull() ?: 1500L

    private fun perWorldSupportLambda(
        level: Int,
        world: MaxDamageSoftCertificate.WorldRead,
    ): Long =
        if (System.getenv("WAKFU_S4_PER_WORLD_LAMBDA") == "1" && level >= 175 && world.arm == "secZero") {
            6000L
        } else {
            ccSupportLambda()
        }

    private fun ccSupportBand(): Int = System.getenv("WAKFU_S4_CC_BAND")?.toIntOrNull() ?: 5

    private fun secondarySupportPrice(): Long? = System.getenv("WAKFU_S4_SECONDARY_PRICE")?.toLongOrNull()

    private fun critAwareCollapse(): Boolean = System.getenv("WAKFU_S4_CRIT_AWARE_COLLAPSE") != "0"

    private fun critWeightAnchor(): Int? = System.getenv("WAKFU_S4_CRIT_WEIGHT_ANCHOR")?.toIntOrNull() ?: 100

    private fun anchorConstTransport(): Boolean = System.getenv("WAKFU_S4_ANCHOR_CONST") == "1"

    private fun coupleSecondaryItemNegative(): Boolean = System.getenv("WAKFU_S4_COUPLE_NEG_ITEMS") != "0"

    private fun netSecondaryItemBudget(): Boolean = System.getenv("WAKFU_S4_NET_NEG_ITEMS") != "0"

    private fun exactNormalSubPacking(): Boolean = System.getenv("WAKFU_S4_EXACT_NORMAL_SUBS") != "0"

    private fun foldNegativeItemAp(): Boolean = System.getenv("WAKFU_S4_FOLD_ITEM_MAX_AP") != "0"

    private fun foldNegativeMaxMp(): Boolean = System.getenv("WAKFU_S4_FOLD_MAX_MP") != "0"

    private fun stateDependentMpRamp(): Boolean = System.getenv("WAKFU_S4_STATE_MP_RAMP") != "0"

    private fun rampWorldRequired(): Boolean? =
        when (System.getenv("WAKFU_S4_RAMP_WORLD")?.lowercase()) {
            null, "", "legacy" -> null
            "required", "1", "true" -> true
            "excluded", "0", "false" -> false
            else -> error("WAKFU_S4_RAMP_WORLD must be required|excluded|legacy")
        }

    private fun elideImpliedConditionalMarker(): Boolean = System.getenv("WAKFU_S4_ELIDE_IMPLIED_COND") != "0"

    private fun skipMidTierForHighSecZero(): Boolean = System.getenv("WAKFU_S4_SKIP_MID_SECZERO") == "1"

    private fun splitLightWeaponCondition(): Boolean = System.getenv("WAKFU_S4_SPLIT_LIGHT_WEAPON") != "0"

    private fun requireConditionalSub(): Boolean = System.getenv("WAKFU_S4_REQUIRE_CONDITIONAL") != "0"

    private fun diagnosticBasePlain(): Boolean = System.getenv("WAKFU_S4_DIAG_BASE_PLAIN") == "1"

    private fun timings(): Boolean = System.getenv("WAKFU_S4_TIMINGS") == "1"

    private fun mdParams(
        level: Int,
        targets: List<TargetStat>,
        clazz: CharacterClass = CharacterClass.CRA,
    ) = WakfuBestBuildParams(
        character = Character(clazz, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 600.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        useRunes = true,
        useSublimations = true
    )

    private fun frontierTargets() =
        listOf(
            TargetStat(Characteristic.ACTION_POINT, 16),
            TargetStat(Characteristic.MOVEMENT_POINT, 8),
            TargetStat(Characteristic.CRITICAL_HIT, 100),
            TargetStat(Characteristic.HP, 12000)
        )

    /**
     * Off-frontier diagnosis (§9.21): `WAKFU_S4_SHAPE` retargets the manual screens at the
     * generality shapes where the union measured loose. Default = the canonical S4 fixture.
     */
    private fun shapePreset(): Triple<CharacterClass, Int, List<TargetStat>> =
        when (val shape = System.getenv("WAKFU_S4_SHAPE")) {
            null, "", "s4" -> Triple(CharacterClass.CRA, 245, frontierTargets())
            "cra50-apmp" ->
                Triple(
                    CharacterClass.CRA,
                    50,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 10),
                        TargetStat(Characteristic.MOVEMENT_POINT, 6)
                    )
                )
            "cra140-apmp" ->
                Triple(
                    CharacterClass.CRA,
                    140,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 14),
                        TargetStat(Characteristic.MOVEMENT_POINT, 7)
                    )
                )
            "cra110-free" -> Triple(CharacterClass.CRA, 110, emptyList())
            "cra110-crit40" ->
                Triple(
                    CharacterClass.CRA,
                    110,
                    listOf(TargetStat(Characteristic.CRITICAL_HIT, 40))
                )
            "cra110-ap12" ->
                Triple(
                    CharacterClass.CRA,
                    110,
                    listOf(TargetStat(Characteristic.ACTION_POINT, 12))
                )
            "cra80-free" -> Triple(CharacterClass.CRA, 80, emptyList())
            "cra80-crit40" ->
                Triple(
                    CharacterClass.CRA,
                    80,
                    listOf(TargetStat(Characteristic.CRITICAL_HIT, 40))
                )
            "cra80-ap10" ->
                Triple(
                    CharacterClass.CRA,
                    80,
                    listOf(TargetStat(Characteristic.ACTION_POINT, 10))
                )
            "iop200-frontier" ->
                Triple(
                    CharacterClass.IOP,
                    200,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 15),
                        TargetStat(Characteristic.MOVEMENT_POINT, 8),
                        TargetStat(Characteristic.CRITICAL_HIT, 100),
                        TargetStat(Characteristic.HP, 10000)
                    )
                )
            // Sweep-3 wall hot spot (low-level full-target fallback stack, 193-209 s).
            "feca65-full" ->
                Triple(
                    CharacterClass.FECA,
                    65,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 11),
                        TargetStat(Characteristic.MOVEMENT_POINT, 6),
                        TargetStat(Characteristic.CRITICAL_HIT, 40),
                        TargetStat(Characteristic.HP, 2500)
                    )
                )
            // Sweep-3 borderline wall (135 s pre-lazy-join fixes).
            "enutrof125-cchp" ->
                Triple(
                    CharacterClass.ENUTROF,
                    125,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 13),
                        TargetStat(Characteristic.MOVEMENT_POINT, 6),
                        TargetStat(Characteristic.CRITICAL_HIT, 70),
                        TargetStat(Characteristic.HP, 5000)
                    )
                )
            // Sweep-3 loose-badge class (AP/MP-only) — second representative.
            "panda170-apmp" ->
                Triple(
                    CharacterClass.PANDAWA,
                    170,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 14),
                        TargetStat(Characteristic.MOVEMENT_POINT, 7)
                    )
                )
            // Sweep-3 loose-badge class representative (AP/MP-only, 12.87%).
            "xelor155-apmp" ->
                Triple(
                    CharacterClass.XELOR,
                    155,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 14),
                        TargetStat(Characteristic.MOVEMENT_POINT, 7)
                    )
                )
            "sacrieur230-apmp" ->
                Triple(
                    CharacterClass.SACRIEUR,
                    230,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 16),
                        TargetStat(Characteristic.MOVEMENT_POINT, 8)
                    )
                )
            // The generality matrix's first hot spot (Unavailable in 225 s, B&B inconclusive).
            "iop110-full" ->
                Triple(
                    CharacterClass.IOP,
                    110,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 12),
                        TargetStat(Characteristic.MOVEMENT_POINT, 6),
                        TargetStat(Characteristic.CRITICAL_HIT, 60),
                        TargetStat(Characteristic.HP, 4000)
                    )
                )
            // The remaining generality-matrix shapes, mirrored from the matrix runner's
            // frontier() rows so every matrix shape can also run as a single cold probe.
            "cra185-full" ->
                Triple(
                    CharacterClass.CRA,
                    185,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 14),
                        TargetStat(Characteristic.MOVEMENT_POINT, 7),
                        TargetStat(Characteristic.CRITICAL_HIT, 90),
                        TargetStat(Characteristic.HP, 8000)
                    )
                )
            "iop215-full" ->
                Triple(
                    CharacterClass.IOP,
                    215,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 15),
                        TargetStat(Characteristic.MOVEMENT_POINT, 8),
                        TargetStat(Characteristic.CRITICAL_HIT, 100),
                        TargetStat(Characteristic.HP, 11000)
                    )
                )
            "eca195-full" ->
                Triple(
                    CharacterClass.ECAFLIP,
                    195,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 15),
                        TargetStat(Characteristic.MOVEMENT_POINT, 7),
                        TargetStat(Characteristic.CRITICAL_HIT, 95),
                        TargetStat(Characteristic.HP, 9000)
                    )
                )
            "osa225-full" ->
                Triple(
                    CharacterClass.OSAMODAS,
                    225,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 16),
                        TargetStat(Characteristic.MOVEMENT_POINT, 8),
                        TargetStat(Characteristic.CRITICAL_HIT, 100),
                        TargetStat(Characteristic.HP, 11500)
                    )
                )
            "steamer240-apmp" ->
                Triple(
                    CharacterClass.STEAMER,
                    240,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 16),
                        TargetStat(Characteristic.MOVEMENT_POINT, 8)
                    )
                )
            else -> error("unknown WAKFU_S4_SHAPE=$shape")
        }

    /**
     * The shared seeded-fixture builder (the second agent's fast-iteration protocol): tiny
     * synthetic pools whose pinned 1-worker CP-SAT solve proves OPTIMAL in seconds — every
     * orchestration change gets its soundness read HERE first, before any full-pool run.
     * The rng draw order is part of the lock's identity — do not reorder.
     */
    private fun seededPools(): List<Pair<String, Map<ItemType, List<me.chosante.common.Equipment>>>> {
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
        // The damage core's own axes: elemental + secondary masteries, crit mastery, DI, crit,
        // plus the target stats — every factor of D·Graw and the fold exercised.
        val statPalette =
            listOf(
                Characteristic.MASTERY_ELEMENTARY,
                Characteristic.MASTERY_ELEMENTARY_FIRE,
                Characteristic.MASTERY_DISTANCE,
                Characteristic.MASTERY_CRITICAL,
                Characteristic.ACTION_POINT,
                Characteristic.MOVEMENT_POINT,
                Characteristic.CRITICAL_HIT,
                Characteristic.HP,
                Characteristic.DAMAGE_INFLICTED
            )
        return (1L..(System.getenv("WAKFU_S4_LOCK_SEEDS")?.toLongOrNull() ?: 3L)).map { seed ->
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
    }

    @Test
    fun `state dependent MP ramp is sound on an incompatible item choice`(): Unit =
        runBlocking {
            fun helmet(
                id: Int,
                stats: Map<Characteristic, Int>,
            ) = me.chosante.common.Equipment(
                equipmentId = id,
                guiId = id,
                level = 80,
                name = me.chosante.common.I18nText("ramp$id", "ramp$id", "", ""),
                rarity = Rarity.LEGENDARY,
                itemType = ItemType.HELMET,
                characteristics = stats,
                maxShardSlots = 3
            )

            // The relaxed reachableMax ramp can combine the MP helmet with the mastery helmet.
            // The state-dependent variant must choose one path while still covering CP-SAT.
            val pool =
                mapOf(
                    ItemType.HELMET to
                        listOf(
                            helmet(990_001, mapOf(Characteristic.MOVEMENT_POINT to 5)),
                            helmet(990_002, mapOf(Characteristic.MASTERY_ELEMENTARY to 200))
                        )
                )
            val feather =
                WakfuBestBuildFinderAlgorithm.sublimations.single {
                    it.name.fr == "Poids Plume III"
                }
            val params = mdParams(80, emptyList())
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    randomSeed = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = 20.0
                )
            var last: me.chosante.autobuilder.genetic.SolverResult<BuildCombination>? = null
            WakfuBuildSolver
                .optimize(params, pool, emptyList(), listOf(feather), tuning, hardConstraints = false)
                .collect { last = it }
            val exact = requireNotNull(last)
            assertThat(exact.isOptimal).isTrue()
            val incumbent = requireNotNull(exact.maxDamageObjective)
            val legacy =
                requireNotNull(
                    MaxDamageSoftCertificate.bound(
                        params,
                        pool,
                        emptyList(),
                        listOf(feather),
                        blockGate = false,
                        exactNormalSubPacking = true,
                        foldNegativeMaxMp = true,
                        // Same W pricing as the coupled read below — the legacy-vs-coupled
                        // comparison is only meaningful at identical crit pricing.
                        critAwareCollapse = true,
                        critWeightAnchorPercent = 100,
                        anchorConstTransport = anchorConstTransport()
                    )
                )
            val coupled =
                requireNotNull(
                    MaxDamageSoftCertificate.bound(
                        params,
                        pool,
                        emptyList(),
                        listOf(feather),
                        blockGate = false,
                        exactNormalSubPacking = true,
                        foldNegativeMaxMp = true,
                        stateDependentMpRamp = true,
                        // Production parity (review fix 2026-07-20): without the crit-aware collapse
                        // the anchor's ~24% slack MASKED a real ramp under-count — the lock must pin
                        // the same W pricing production runs.
                        critAwareCollapse = true,
                        critWeightAnchorPercent = 100,
                        anchorConstTransport = anchorConstTransport()
                    )
                )
            if (anchorConstTransport()) {
                println("S4_ANCHOR_LOCK incumbent=$incumbent legacy=${legacy.foldedBound} coupled=${coupled.foldedBound} binding=${coupled.bindingState}")
            }
            assertThat(coupled.foldedBound)
                .describedAs("the MP-coupled certificate must cover the exact CP-SAT optimum")
                .isGreaterThanOrEqualTo(incumbent)
            assertThat(coupled.foldedBound)
                .describedAs("the coupled ramp must remove the cross-item reachableMax combination")
                .isLessThan(legacy.foldedBound)
        }

    /**
     * CI SOUNDNESS LOCK for the 2026-10-01 pre-release review of the soft DP — tiny pools where the
     * fold used to UNDER-count the proven CP-SAT optimum (a too-tight "proven within X%" badge):
     *  - ap-skill: the Major "Action Point" point was dropped when AP was not a target, although AP
     *    always sets the throughput (guarded here; on this pool the old fold's slack masked the drop —
     *    the three fixtures below each reproduce the old under-count);
     *  - max-ap: the assume-AP low read ignored an item's −1 MAX_ACTION_POINT (real pre-combat AP 6);
     *  - soc-crit: the assume-CC fold stopped at `threshold + own`, missing a start-of-combat +12
     *    crit (which even fed the low read and rejected the carrier);
     *  - soc-critm: the critZero arm's constant stopped at `t + own`, missing start-of-combat crit
     *    mastery;
     *  - crit-above-target (production crit-aware bands): the last band transported builds whose crit
     *    exceeds the target at the TARGET's ratio, under-stating their crit leverage.
     * Every fixture is checked under the default DP and under the production crit-aware band setup.
     */
    @Test
    fun `soft certificate covers the pre-release review under-counts`(): Unit =
        runBlocking {
            fun item(
                id: Int,
                type: ItemType,
                rarity: Rarity = Rarity.LEGENDARY,
                stats: Map<Characteristic, Int>,
            ) = me.chosante.common.Equipment(
                equipmentId = id,
                guiId = id,
                level = 200,
                name = me.chosante.common.I18nText("rev$id", "rev$id", "", ""),
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
                name = me.chosante.common.I18nText("revsub$stateId", "revsub$stateId", "", ""),
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

            data class Fixture(
                val label: String,
                val pool: Map<ItemType, List<me.chosante.common.Equipment>>,
                val targets: List<TargetStat>,
                val subs: List<me.chosante.common.Sublimation>,
                val level: Int = 200,
            )
            val epic = me.chosante.common.SublimationRarity.EPIC
            val normal = me.chosante.common.SublimationRarity.NORMAL
            val fixtures =
                listOf(
                    Fixture(
                        "ap-skill",
                        listOf(item(991_101, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_ELEMENTARY to 400))).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.MOVEMENT_POINT, 12), TargetStat(Characteristic.HP, 9000)),
                        emptyList()
                    ),
                    Fixture(
                        "max-ap",
                        listOf(
                            item(991_201, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_ELEMENTARY to 300)),
                            item(
                                991_202,
                                ItemType.BOOTS,
                                Rarity.EPIC,
                                mapOf(Characteristic.MASTERY_ELEMENTARY to 500, Characteristic.ACTION_POINT to 1, Characteristic.MAX_ACTION_POINT to -1)
                            ),
                            item(991_203, ItemType.BOOTS, stats = mapOf(Characteristic.MASTERY_ELEMENTARY to 100, Characteristic.ACTION_POINT to 1))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.HP, 9000)),
                        listOf(
                            sub(
                                991_204,
                                epic,
                                me.chosante.common.SublimationCondition(SublimationConditionType.AP_AT_MOST, value = 6),
                                flat(Characteristic.DAMAGE_INFLICTED, 15, false)
                            )
                        )
                    ),
                    Fixture(
                        "soc-crit",
                        listOf(
                            item(991_301, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_ELEMENTARY to 300, Characteristic.CRITICAL_HIT to 5)),
                            item(991_302, ItemType.CAPE, Rarity.EPIC, mapOf(Characteristic.MASTERY_ELEMENTARY to 200))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.CRITICAL_HIT, 20)),
                        listOf(
                            sub(
                                991_303,
                                epic,
                                me.chosante.common.SublimationCondition(SublimationConditionType.CRIT_AT_MOST, value = 10),
                                flat(Characteristic.DAMAGE_INFLICTED, 20, false)
                            ),
                            sub(991_304, normal, null, flat(Characteristic.CRITICAL_HIT, 12, false))
                        )
                    ),
                    Fixture(
                        "soc-critm",
                        listOf(
                            item(991_401, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_ELEMENTARY to 300)),
                            item(991_402, ItemType.CAPE, Rarity.EPIC, mapOf(Characteristic.MASTERY_ELEMENTARY to 200))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.CRITICAL_HIT, 50)),
                        listOf(
                            sub(
                                991_403,
                                epic,
                                me.chosante.common.SublimationCondition(SublimationConditionType.CRITICAL_MASTERY_AT_MOST, value = 0),
                                flat(Characteristic.CRITICAL_HIT, 30, false)
                            ),
                            sub(991_404, normal, null, flat(Characteristic.MASTERY_CRITICAL, 300, false))
                        )
                    ),
                    Fixture(
                        "crit-above-target",
                        listOf(
                            item(991_501, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_ELEMENTARY to 3000, Characteristic.CRITICAL_HIT to 60)),
                            item(991_502, ItemType.CAPE, stats = mapOf(Characteristic.MASTERY_CRITICAL to 800))
                        ).groupBy { it.itemType },
                        listOf(TargetStat(Characteristic.CRITICAL_HIT, 20)),
                        emptyList(),
                        level = 160
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
                val params = mdParams(f.level, f.targets).copy(useRunes = false)
                var last: me.chosante.autobuilder.genetic.SolverResult<BuildCombination>? = null
                WakfuBuildSolver
                    .optimize(params, f.pool, emptyList(), f.subs, tuning, hardConstraints = false)
                    .collect { last = it }
                val exact = requireNotNull(last) { "${f.label}: the solve emitted nothing" }
                assertThat(exact.isOptimal).describedAs("${f.label}: the tiny pool must be PROVEN").isTrue()
                val optimum = requireNotNull(exact.maxDamageObjective)
                val bound =
                    requireNotNull(MaxDamageSoftCertificate.bound(params, f.pool, emptyList(), f.subs, blockGate = false)) {
                        "${f.label}: the soft DP bailed on a supported shape"
                    }
                // The production no-condition pass's crit-aware band setup (λ per level band, 5-crit
                // bands, anchor 100, anchor-constant transport below a 100-crit target).
                val banded =
                    requireNotNull(
                        MaxDamageSoftCertificate.bound(
                            params,
                            f.pool,
                            emptyList(),
                            f.subs,
                            blockGate = false,
                            ccSupportLambda = 1500L,
                            ccSupportBand = 5,
                            coupleSecondaryItemNegative = true,
                            netSecondaryItemBudget = true,
                            exactNormalSubPacking = true,
                            foldNegativeItemAp = true,
                            foldNegativeMaxMp = true,
                            critAwareCollapse = true,
                            critWeightAnchorPercent = 100,
                            anchorConstTransport = true,
                            stateDependentMpRamp = true
                        )
                    ) { "${f.label}: the banded soft DP bailed on a supported shape" }
                println("S4_REVIEW_LOCK ${f.label} optimum=$optimum bound=${bound.foldedBound} banded=${banded.foldedBound}")
                if (bound.foldedBound < optimum) underCounts += "${f.label}: bound ${bound.foldedBound} < optimum $optimum"
                if (banded.foldedBound < optimum) underCounts += "${f.label} (banded): bound ${banded.foldedBound} < optimum $optimum"
            }
            assertThat(underCounts)
                .describedAs("SOUNDNESS — the soft DP must never under-count the CP-SAT optimum")
                .isEmpty()
        }

    @Test
    fun `state dependent MP ramp is sound on seeded item paths`(): Unit =
        runBlocking {
            fun item(
                id: Int,
                type: ItemType,
                stats: Map<Characteristic, Int>,
            ) = me.chosante.common.Equipment(
                equipmentId = id,
                guiId = id,
                level = 100,
                name = me.chosante.common.I18nText("ramp$id", "ramp$id", "", ""),
                rarity = Rarity.LEGENDARY,
                itemType = type,
                characteristics = stats,
                maxShardSlots = 0
            )

            val feather =
                WakfuBestBuildFinderAlgorithm.sublimations.single {
                    it.name.fr == "Poids Plume III"
                }
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    randomSeed = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = 30.0
                )
            WakfuBuildSolver.warmUp()
            var strictlyTighter = 0
            for (seed in 1L..8L) {
                val rng = java.util.Random(seed)
                val pool =
                    listOf(ItemType.HELMET, ItemType.CAPE, ItemType.BOOTS)
                        .flatMapIndexed { slot, type ->
                            (0..2).map { choice ->
                                item(
                                    id = 991_000 + seed.toInt() * 100 + slot * 10 + choice,
                                    type = type,
                                    stats =
                                        mapOf(
                                            Characteristic.MOVEMENT_POINT to (rng.nextInt(8) - 2),
                                            Characteristic.MASTERY_ELEMENTARY to (40 + rng.nextInt(241)),
                                            Characteristic.MASTERY_DISTANCE to rng.nextInt(161),
                                            Characteristic.MASTERY_CRITICAL to rng.nextInt(121),
                                            Characteristic.DAMAGE_INFLICTED to rng.nextInt(11),
                                            Characteristic.CRITICAL_HIT to rng.nextInt(21)
                                        )
                                )
                            }
                        }.groupBy { it.itemType }
                val targets =
                    when (seed % 3L) {
                        0L -> emptyList()
                        1L -> listOf(TargetStat(Characteristic.MOVEMENT_POINT, 7))
                        else -> listOf(TargetStat(Characteristic.CRITICAL_HIT, 35))
                    }
                val params = mdParams(100, targets)
                var last: me.chosante.autobuilder.genetic.SolverResult<BuildCombination>? = null
                WakfuBuildSolver
                    .optimize(params, pool, emptyList(), listOf(feather), tuning, hardConstraints = false)
                    .collect { last = it }
                val exact = requireNotNull(last)
                assertThat(exact.isOptimal).describedAs("seed $seed CP-SAT oracle").isTrue()
                val incumbent = requireNotNull(exact.maxDamageObjective)
                val legacy =
                    requireNotNull(
                        MaxDamageSoftCertificate.bound(
                            params,
                            pool,
                            emptyList(),
                            listOf(feather),
                            blockGate = false,
                            exactNormalSubPacking = true,
                            foldNegativeMaxMp = true
                        )
                    )
                val coupled =
                    requireNotNull(
                        MaxDamageSoftCertificate.bound(
                            params,
                            pool,
                            emptyList(),
                            listOf(feather),
                            blockGate = false,
                            exactNormalSubPacking = true,
                            foldNegativeMaxMp = true,
                            stateDependentMpRamp = true,
                            // Production parity (see the sibling lock): pin the crit-aware W pricing.
                            critAwareCollapse = true,
                            critWeightAnchorPercent = 100,
                            anchorConstTransport = anchorConstTransport()
                        )
                    )
                assertThat(coupled.foldedBound)
                    .describedAs("seed $seed MP-coupled bound must cover CP-SAT")
                    .isGreaterThanOrEqualTo(incumbent)
                assertThat(coupled.foldedBound)
                    .describedAs("seed $seed state coupling cannot weaken the legacy upper")
                    .isLessThanOrEqualTo(legacy.foldedBound)
                if (coupled.foldedBound < legacy.foldedBound) strictlyTighter++
            }
            assertThat(strictlyTighter)
                .describedAs("the seeded campaign must exercise a real cross-path ramp relaxation")
                .isGreaterThan(0)
        }

    @Test
    fun `every WakfuBestBuildParams field is classified for the soft certificate`() {
        // Tripwire (mirrors the MaxDamageCertificateCache fingerprint test): supportsShape is a
        // hand-enumerated deny-list, so a NEW objective-affecting param would silently slip past
        // it and the DP would price a model missing that dimension — an under-count risk the
        // production self-check cannot catch. A new field must be classified here on purpose:
        // gate it in supportsShape, model it in bound(), or record it as objective-neutral.
        val gatedBySupportsShape =
            setOf(
                "scoreComputationMode",
                "damageScenario",
                "maxDamageApTarget",
                "maxDamageMpPin",
                "forcedItems",
                "forcedRunes",
                "forcedRunesByItem",
                "forcedSublimations",
                "forcedSublimationLevels",
                "forcedPassives",
                "targetStats"
            )
        val reflectedInCertificateInputs =
            setOf(
                // These reach the certificate through its pool/catalog/params inputs (the caller
                // pre-filters the pool; bound() reads character/useRunes/useSublimations itself).
                "character",
                "maxRarity",
                "excludedRarities",
                "excludedItems",
                "useRunes",
                "useSublimations",
                "maxSublimationTier",
                "excludedSublimations"
            )
        val objectiveNeutral = setOf("searchDuration", "stopWhenBuildMatch", "solverWorkers", "computeBudget")
        val classified = gatedBySupportsShape + reflectedInCertificateInputs + objectiveNeutral
        val actual =
            WakfuBestBuildParams::class.java.declaredFields
                .filter {
                    !it.isSynthetic &&
                        !java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                }.map { it.name }
                .toSet()
        assertThat(actual)
            .describedAs(
                "WakfuBestBuildParams changed shape — classify the new/renamed field for the soft " +
                    "certificate (supportsShape gate, bound() modeling, or objective-neutral) before " +
                    "updating this pinned set"
            ).isEqualTo(classified)
    }

    @Test
    fun `implied conditional worlds can discard their redundant marker`() {
        val params = mdParams(100, emptyList())
        val pool = seededPools().first().second
        for (arm in listOf("secZero", "critZero")) {
            fun read(elide: Boolean) =
                requireNotNull(
                    MaxDamageSoftCertificate.bound(
                        params,
                        pool,
                        emptyList(),
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        blockGate = false,
                        exactNormalSubPacking = true,
                        requireConditionalSub = true,
                        worldDropCaps = true,
                        worldArm = arm,
                        elideImpliedConditionalMarker = elide
                    )
                )

            val marked = read(false)
            val elided = read(true)
            assertThat(elided.foldedBound).describedAs("$arm folded bound").isEqualTo(marked.foldedBound)
            assertThat(elided.coreBound).describedAs("$arm core bound").isEqualTo(marked.coreBound)
            assertThat(elided.states).describedAs("$arm state count").isLessThanOrEqualTo(marked.states)
        }
    }

    /**
     * FAST soundness lock for the PRODUCTION union orchestrator (seconds per seed): on each
     * seeded pool, [MaxDamageSoftCertificate.hybridUnionUpper] must cover the pinned full-model
     * CP-SAT soft optimum. Exercises the oracle path, the conditional DP cascade, the §9.21
     * conditional CP-SAT probe and the min() composition — the pre-flight for ANY orchestrator
     * change, before paying a full-pool run.
     *
     * ```shell
     * ./gradlew --stop
     * WAKFU_S4_UNION_LOCK=1 ./gradlew :autobuilder:cleanTest :autobuilder:test \
     *   --tests '*MaxDamageSoftCertificateTest*union soundness*' --no-daemon
     * ```
     */
    @Test
    fun `manual S4 union soundness lock on seeded pools`() {
        assumeTrue(System.getenv("WAKFU_S4_UNION_LOCK") == "1")
        val p =
            mdParams(
                200,
                listOf(
                    TargetStat(Characteristic.ACTION_POINT, 12),
                    TargetStat(Characteristic.CRITICAL_HIT, 100),
                    TargetStat(Characteristic.HP, 8000)
                )
            )
        WakfuBuildSolver.warmUp()
        for ((label, pool) in seededPools()) {
            val full =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    p,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.sublimations,
                    workers = 1,
                    seconds = 600.0,
                    deterministicLimit = 60.0,
                    interleave = true,
                    applyDomination = false
                )
            require(full.hasSolution && full.status == "OPTIMAL") {
                "$label: the pinned full-model oracle must prove OPTIMAL (got ${full.status})"
            }
            val union =
                requireNotNull(
                    MaxDamageSoftCertificate.hybridUnionUpper(
                        p,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        oracleWorkers = 2,
                        oracleSeconds = 60.0,
                        incumbentObjective = full.objective
                    )
                ) { "$label: the union orchestrator bailed on a supported shape" }
            println(
                "S4_UNION_LOCK $label optimum=${full.objective} union=${union.upper} " +
                    "noCond=${union.noConditionUpper} noCondProven=${union.noConditionProven} " +
                    "ratio=${"%.4f".format(union.upper.toDouble() / full.objective.coerceAtLeast(1))}"
            )
            assertThat(union.upper)
                .describedAs("$label: SOUNDNESS — the union must never under-count the full-model soft optimum")
                .isGreaterThanOrEqualTo(full.objective)
        }
    }

    @Test
    fun `manual frontier region partition covers seeded no-condition optima`() {
        assumeTrue(System.getenv("WAKFU_S4_FRONTIER_LOCK") == "1")
        val params =
            mdParams(
                200,
                listOf(
                    TargetStat(Characteristic.ACTION_POINT, 12),
                    TargetStat(Characteristic.MOVEMENT_POINT, 7),
                    TargetStat(Characteristic.CRITICAL_HIT, 100),
                    TargetStat(Characteristic.HP, 8000)
                )
            )
        val noConditionSubs = WakfuBestBuildFinderAlgorithm.sublimations.filter { it.condition == null }
        for ((label, pool) in seededPools()) {
            val exact =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    noConditionSubs,
                    workers = 1,
                    seconds = 120.0,
                    deterministicLimit = 60.0,
                    interleave = true,
                    applyDomination = false
                )
            require(exact.status == "OPTIMAL") { "$label no-condition oracle=${exact.status}" }
            MaxDamageSoftCertificate.diStep = 1
            MaxDamageSoftCertificate.hpStep = 4000
            MaxDamageSoftCertificate.ccStep = 20
            try {
                val dp =
                    requireNotNull(
                        MaxDamageSoftCertificate.bound(
                            params,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            noConditionSubs,
                            blockGate = false,
                            ccSupportLambda = 1500,
                            ccSupportBand = 5,
                            coupleSecondaryItemNegative = true,
                            netSecondaryItemBudget = true,
                            exactNormalSubPacking = true,
                            foldNegativeItemAp = true,
                            foldNegativeMaxMp = true,
                            critAwareCollapse = true,
                            critWeightAnchorPercent = 100,
                            anchorConstTransport = anchorConstTransport(),
                            stateDependentMpRamp = true
                        )
                    )
                val distinct =
                    dp.targetCellBounds.values
                        .distinct()
                        .sortedDescending()
                require(distinct.size >= 2) { "$label needs at least two cell levels" }
                val syntheticIncumbent = distinct[1]
                val refined =
                    requireNotNull(
                        MaxDamageSoftCertificate.frontierRegionUpper(
                            params,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            noConditionSubs,
                            dp,
                            syntheticIncumbent,
                            workers = 2,
                            seconds = 30.0
                        )
                    ) { "$label frontier partition did not activate" }
                println(
                    "S4_FRONTIER_LOCK $label exact=${exact.objective} dp=${dp.foldedBound} " +
                        "refined=${refined.upper} cells=${refined.cells.size}"
                )
                assertThat(refined.upper)
                    .describedAs("$label regional union must cover the exact no-condition optimum")
                    .isGreaterThanOrEqualTo(exact.objective)
            } finally {
                MaxDamageSoftCertificate.diStep = 1
                MaxDamageSoftCertificate.hpStep = 500
                MaxDamageSoftCertificate.ccStep = 10
            }
        }
    }

    /**
     * Generality MATRIX (user request 2026-07-18: verify "any request proves in under two
     * minutes" across the request space, not on 4 samples). Runs the FULL production proof
     * per shape and prints verdict + wall. `WAKFU_S4_PROOF_MATRIX=1`; ~30-60 min. Shapes are
     * soft-leg (unreachable-target) requests across classes, levels and target styles.
     */
    @Test
    fun `manual soft proof generality matrix`() {
        assumeTrue(System.getenv("WAKFU_S4_PROOF_MATRIX") == "1")

        data class Shape(
            val label: String,
            val clazz: CharacterClass,
            val level: Int,
            val targets: List<TargetStat>,
        )

        fun frontier(
            ap: Int,
            mp: Int,
            cc: Int? = null,
            hp: Int? = null,
        ) = buildList {
            add(TargetStat(Characteristic.ACTION_POINT, ap))
            add(TargetStat(Characteristic.MOVEMENT_POINT, mp))
            cc?.let { add(TargetStat(Characteristic.CRITICAL_HIT, it)) }
            hp?.let { add(TargetStat(Characteristic.HP, it)) }
        }
        val shapes =
            listOf(
                Shape("cra50-apmp", CharacterClass.CRA, 50, frontier(10, 6)),
                Shape("iop110-full", CharacterClass.IOP, 110, frontier(12, 6, 60, 4000)),
                Shape("xelor155-apmp", CharacterClass.XELOR, 155, frontier(14, 7)),
                Shape("cra185-full", CharacterClass.CRA, 185, frontier(14, 7, 90, 8000)),
                Shape("iop215-full", CharacterClass.IOP, 215, frontier(15, 8, 100, 11000)),
                Shape("sacrieur230-apmp", CharacterClass.SACRIEUR, 230, frontier(16, 8)),
                // Coverage extension (sweep 3): more classes, mixed target styles, low levels.
                Shape("feca65-full", CharacterClass.FECA, 65, frontier(11, 6, 40, 2500)),
                Shape("enutrof125-cchp", CharacterClass.ENUTROF, 125, frontier(13, 6, 70, 5000)),
                Shape("panda170-apmp", CharacterClass.PANDAWA, 170, frontier(14, 7)),
                Shape("eca195-full", CharacterClass.ECAFLIP, 195, frontier(15, 7, 95, 9000)),
                Shape("osa225-full", CharacterClass.OSAMODAS, 225, frontier(16, 8, 100, 11500)),
                Shape("steamer240-apmp", CharacterClass.STEAMER, 240, frontier(16, 8))
            )
        val rows = mutableListOf<String>()
        for (shape in shapes) {
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..shape.level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val p = mdParams(shape.level, shape.targets, shape.clazz)
            val incumbent = solvedNoConditionOracle(p, pool).objective
            val result =
                me.chosante.autobuilder.genetic.SolverResult(
                    individual = BuildCombination(emptyList(), CharacterSkills(shape.level)),
                    matchPercentage = java.math.BigDecimal.ZERO,
                    progressPercentage = 100,
                    isOptimal = false,
                    maxDamageObjective = incumbent,
                    maxDamageHardConstraintsMet = false
                )
            val t0 = System.nanoTime()
            val proof = WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(p, result)
            val wallMs = (System.nanoTime() - t0) / 1_000_000
            val row = "S4_MATRIX shape=${shape.label} verdict=$proof wallMs=$wallMs"
            println(row)
            rows += row
        }
        rows.forEach(::println)
    }

    /**
     * P3 measurement: per-capper region CP on the REAL pool — the carrier forced, its condition
     * kept (the only reification), every OTHER conditional condition-stripped (sound relaxation).
     * If every capper's bound lands at or under the incumbent, the secZero/critZero worlds close
     * by CP. `WAKFU_S4_CAPPER_CP=1`, shape via WAKFU_S4_SHAPE, incumbent via WAKFU_S4_INCUMBENT.
     */
    @Test
    fun `manual capper region CP on the real pool`() {
        assumeTrue(System.getenv("WAKFU_S4_CAPPER_CP") == "1")
        val (clazz, level, targets) = shapePreset()
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val p = mdParams(level, targets, clazz)
        val incumbent = System.getenv("WAKFU_S4_INCUMBENT")?.toLongOrNull() ?: Long.MIN_VALUE
        val seconds = System.getenv("WAKFU_S4_CP_SECONDS")?.toDoubleOrNull() ?: 120.0
        // WAKFU_S4_CAPPER_ALL=1 sweeps EVERY solver-choosable conditional carrier (any supported
        // condition type) — the full per-carrier closure evidence: exact-build space = no-condition
        // builds (oracle authority) ∪ carrier worlds, so all carriers closing ≤ incumbent composes
        // into an exact certificate.
        val cappers =
            if (System.getenv("WAKFU_S4_CAPPER_ALL") == "1") {
                WakfuBestBuildFinderAlgorithm.sublimations.filter {
                    it.solverChoosable && it.condition != null && it.condition?.type in SUPPORTED_SUB_CONDITIONS
                }
            } else {
                WakfuBestBuildFinderAlgorithm.sublimations.filter {
                    it.solverChoosable &&
                        (
                            it.condition?.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ||
                                it.condition?.type == SublimationConditionType.CRITICAL_MASTERY_AT_MOST
                        )
                }
            }
        assertThat(cappers).isNotEmpty
        // WAKFU_S4_CAPPER_ONLY=<fr-name substring> restricts the sweep to one carrier;
        // WAKFU_S4_CAPPER_STRICT=1 EXCLUDES the other conditionals instead of relaxing them —
        // a sound RESTRICTION, so any solution it finds is an exactly-feasible build (witness
        // validation for a carrier optimum found under the relaxed model).
        val only = System.getenv("WAKFU_S4_CAPPER_ONLY")
        val strict = System.getenv("WAKFU_S4_CAPPER_STRICT") == "1"
        // Pairwise world splitting: WAKFU_S4_CAPPER_DROP=<fr substring> EXCLUDES that sub from the
        // catalog (the "carrier without it" half-world); WAKFU_S4_CAPPER_KEEP=<fr substring> keeps
        // its condition EXACT instead of relaxing it (the "both conditions exact" half-world).
        // max(the two bounds) is a sound upper of the whole carrier world.
        val drop = System.getenv("WAKFU_S4_CAPPER_DROP")
        val keep = System.getenv("WAKFU_S4_CAPPER_KEEP")
        for (capper in cappers.filter { only == null || it.name.fr.contains(only) }) {
            val subs =
                if (strict) {
                    // STRICT + KEEP: the carrier plus the KEEP-matched conditionals (exact),
                    // everything else conditional excluded — the pairwise world {carrier ∧ kept}.
                    WakfuBestBuildFinderAlgorithm.sublimations.filter {
                        it.stateId == capper.stateId ||
                            it.condition == null ||
                            (keep != null && it.name.fr.contains(keep))
                    }
                } else {
                    WakfuBestBuildFinderAlgorithm.sublimations
                        .filter { drop == null || !it.name.fr.contains(drop) || it.stateId == capper.stateId }
                        .map {
                            when {
                                it.stateId == capper.stateId -> it
                                keep != null && it.name.fr.contains(keep) -> it
                                else -> it.withRelaxedBuildStaticCondition()
                            }
                        }
                }
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    p,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    subs,
                    workers = 8,
                    seconds = seconds,
                    applyDomination = true,
                    requiredSublimationStateId = capper.stateId,
                    // WAKFU_S4_CAPPER_CUTOFF=1 turns each carrier run into the DECISION problem
                    // "does a build of this world EXCEED the incumbent?" — INFEASIBLE everywhere
                    // proves the true optimum IS the incumbent; a FEASIBLE yields an exact witness.
                    penalizedObjectiveCutoff =
                        if (System.getenv("WAKFU_S4_CAPPER_CUTOFF") == "1" && incumbent != Long.MIN_VALUE) incumbent + 1 else null
                )
            println(
                "S4_CAPPER_CP capper=${capper.name.fr}(${capper.condition?.type}) strict=$strict status=${profile.status} " +
                    "objective=${profile.objective} bound=${profile.bestBound} " +
                    "closes=${incumbent != Long.MIN_VALUE && profile.bestBound <= incumbent} " +
                    "selectedSubIds=${profile.selectedSublimationStateIds.sorted()}"
            )
        }
    }

    /** Targeted soundness lock for the secZero Lagrangian support, without pricing unrelated worlds. */
    @Test
    fun `manual secondary support covers exact capper worlds on seeded pools`() {
        assumeTrue(System.getenv("WAKFU_S4_SECONDARY_LOCK") == "1")
        val params =
            mdParams(
                200,
                listOf(
                    TargetStat(Characteristic.ACTION_POINT, 12),
                    TargetStat(Characteristic.MOVEMENT_POINT, 7),
                    TargetStat(Characteristic.CRITICAL_HIT, 100),
                    TargetStat(Characteristic.HP, 8000)
                )
            )
        val cappers =
            WakfuBestBuildFinderAlgorithm.sublimations.filter {
                it.solverChoosable && it.condition?.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST
            }
        assertThat(cappers).isNotEmpty
        for ((label, pool) in seededPools()) {
            val exactUpper =
                cappers
                    .mapNotNull { capper ->
                        val exact =
                            WakfuBuildSolver.timedMaxDamageProfileForTest(
                                params,
                                pool,
                                WakfuBestBuildFinderAlgorithm.runes,
                                WakfuBestBuildFinderAlgorithm.sublimations,
                                workers = 8,
                                seconds = 60.0,
                                applyDomination = false,
                                requiredSublimationStateId = capper.stateId
                            )
                        when (exact.status) {
                            "OPTIMAL" -> exact.objective
                            "INFEASIBLE" -> null
                            else -> error("$label/${capper.name.fr}: exact=${exact.status}")
                        }
                    }.maxOrNull() ?: Long.MIN_VALUE
            MaxDamageSoftCertificate.diStep = 1
            MaxDamageSoftCertificate.hpStep = 1000
            MaxDamageSoftCertificate.ccStep = 10
            try {
                for (mu in listOf(0L, 250L, 500L)) {
                    val bound =
                        requireNotNull(
                            MaxDamageSoftCertificate.bound(
                                params,
                                pool,
                                WakfuBestBuildFinderAlgorithm.runes,
                                WakfuBestBuildFinderAlgorithm.sublimations,
                                blockGate = false,
                                ccSupportLambda = 4000,
                                ccSupportBand = 1,
                                coupleSecondaryItemNegative = true,
                                netSecondaryItemBudget = true,
                                exactNormalSubPacking = true,
                                foldNegativeItemAp = true,
                                foldNegativeMaxMp = true,
                                splitLightWeaponCondition = true,
                                requireConditionalSub = true,
                                worldDropCaps = true,
                                worldArm = "secZero",
                                critAwareCollapse = true,
                                critWeightAnchorPercent = 100,
                                anchorConstTransport = anchorConstTransport(),
                                stateDependentMpRamp = true,
                                elideImpliedConditionalMarker = true,
                                secondarySupportPrice = mu,
                                secondaryNetDimension = System.getenv("WAKFU_S4_SEC_DIM") == "1",
                                secondaryNegBudgetDimension = System.getenv("WAKFU_S4_SEC_DIM2") == "1"
                            )
                        )
                    println("S4_SECONDARY_LOCK $label mu=$mu exact=$exactUpper bound=${bound.foldedBound}")
                    assertThat(bound.foldedBound)
                        .describedAs("$label: μ=$mu secZero must cover every exact secondary-capper build")
                        .isGreaterThanOrEqualTo(exactUpper)
                }
            } finally {
                MaxDamageSoftCertificate.diStep = 1
                MaxDamageSoftCertificate.hpStep = 500
                MaxDamageSoftCertificate.ccStep = 10
            }
        }
    }

    /** The hybrid union's lower side: a PROVEN CP-SAT optimum, never a trusted constant. */
    private data class NoConditionOracle(
        val objective: Long,
        val status: String,
        val wallSec: Double,
        val dataVersion: String,
    )

    /**
     * Solves (once per request/pool/data-version, memoized) the NO-CONDITION S4 model with the
     * production real-parallel portfolio and requires a full `OPTIMAL` proof. This is the typed
     * replacement for the hand-banked `WAKFU_S4_ORACLE` constant: the hybrid conditional union is
     * only sound when its no-condition side is PROVEN for the exact same request and pool.
     */
    private fun solvedNoConditionOracle(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<me.chosante.common.Equipment>>,
    ): NoConditionOracle =
        noConditionOracleMemo.getOrPut(
            "${WakfuBestBuildFinderAlgorithm.dataVersion}|${p.hashCode()}|${pool.values.sumOf { it.size }}"
        ) {
            WakfuBuildSolver.warmUp()
            val profile =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params = p,
                    equipmentsByItemType = pool,
                    runes = WakfuBestBuildFinderAlgorithm.runes,
                    sublimations = WakfuBestBuildFinderAlgorithm.sublimations.filter { it.condition == null },
                    // 8 workers coexist fine with the SINGLE-threaded DP sweep (oracle 37 s while
                    // coarse runs); adding a 4-thread DP world pool degraded it to ~150 s — the
                    // world pool was reverted, not the workers.
                    workers = System.getenv("WAKFU_S4_ORACLE_WORKERS")?.toIntOrNull() ?: 8,
                    seconds = 600.0,
                    applyDomination = true
                )
            require(profile.status == "OPTIMAL") {
                "the no-condition oracle must PROVE its optimum (got ${profile.status}, " +
                    "objective=${profile.objective}, bound=${profile.bestBound}) — without the proof the " +
                    "hybrid union has no sound lower side"
            }
            println(
                "S4_ORACLE status=${profile.status} objective=${profile.objective} " +
                    "wallSec=${profile.wallTimeSec} dataVersion=${WakfuBestBuildFinderAlgorithm.dataVersion}"
            )
            NoConditionOracle(profile.objective, profile.status, profile.wallTimeSec, WakfuBestBuildFinderAlgorithm.dataVersion)
        }

    private companion object {
        val noConditionOracleMemo = java.util.concurrent.ConcurrentHashMap<String, NoConditionOracle>()
    }

    /**
     * A cancelled proof must RELEASE its CPU promptly ("une recherche ne doit jamais en bloquer une
     * autre", 2026-07-21): the stop-watcher thread polls `shouldContinue` every 500 ms and calls
     * `CpSolver.stopSearch()`, so a multi-minute solve ends seconds after cancellation instead of
     * running to its full budget. Full sacrieur-sized pool + 60 s budget, cancelled at 2 s — the
     * whole call must return well under the budget (generous 30 s ceiling for model build + stop
     * latency on a loaded machine).
     */
    @Test
    fun `a cancelled proof solve stops within seconds not its full budget`() {
        val level = 230
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val p =
            mdParams(
                level,
                listOf(
                    TargetStat(Characteristic.ACTION_POINT, 16),
                    TargetStat(Characteristic.MOVEMENT_POINT, 8)
                ),
                CharacterClass.SACRIEUR
            )
        WakfuBuildSolver.warmUp()
        val t0 = System.nanoTime()
        WakfuBuildSolver.timedMaxDamageProfileForTest(
            params = p,
            equipmentsByItemType = pool,
            runes = WakfuBestBuildFinderAlgorithm.runes,
            sublimations = WakfuBestBuildFinderAlgorithm.sublimations,
            workers = 2,
            seconds = 60.0,
            applyDomination = true,
            shouldContinue = { (System.nanoTime() - t0) / 1_000_000 < 2_000L }
        )
        val wallMs = (System.nanoTime() - t0) / 1_000_000
        assertThat(wallMs)
            .describedAs("a solve cancelled at 2 s must stop promptly, not run its 60 s budget")
            .isLessThan(30_000L)
    }

    @Test
    fun `manual S4 prototype soundness lock on seeded pools`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_S4_PROTO") == "1")
            val fixtures = seededPools()

            // Unreachable-on-a-small-pool targets: the solve lands on the SOFT leg's penalized
            // objective, the exact value the prototype bounds.
            val p =
                mdParams(
                    200,
                    listOf(
                        TargetStat(Characteristic.ACTION_POINT, 12),
                        TargetStat(Characteristic.CRITICAL_HIT, 100),
                        TargetStat(Characteristic.HP, 8000)
                    )
                )
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    randomSeed = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = 60.0
                )

            WakfuBuildSolver.warmUp()
            for ((label, pool) in fixtures) {
                val partitioned = requireConditionalSub()
                val (incumbent, optimal) =
                    if (partitioned) {
                        val profile =
                            WakfuBuildSolver.timedMaxDamageProfileForTest(
                                p,
                                pool,
                                WakfuBestBuildFinderAlgorithm.runes,
                                WakfuBestBuildFinderAlgorithm.sublimations,
                                workers = 1,
                                seconds = 600.0,
                                deterministicLimit = 60.0,
                                interleave = true,
                                applyDomination = false,
                                requireAnyConditionalSublimation = true
                            )
                        require(profile.hasSolution) { "$label: the conditional soft solve emitted nothing (${profile.status})" }
                        profile.objective to (profile.status == "OPTIMAL")
                    } else {
                        var last: me.chosante.autobuilder.genetic.SolverResult<BuildCombination>? = null
                        WakfuBuildSolver
                            .optimize(
                                p,
                                pool,
                                WakfuBestBuildFinderAlgorithm.runes,
                                WakfuBestBuildFinderAlgorithm.sublimations,
                                tuning,
                                hardConstraints = false
                            ).collect { last = it }
                        val final = requireNotNull(last) { "$label: the soft solve emitted nothing" }
                        requireNotNull(final.maxDamageObjective) { "$label: the soft leg must stamp maxDamageObjective" } to final.isOptimal
                    }
                require(optimal) { "$label: the conditional-partition soundness oracle must prove OPTIMAL" }
                val bound =
                    requireNotNull(
                        MaxDamageSoftCertificate.bound(
                            p,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations,
                            ccSupportLambda = ccSupportLambda(),
                            ccSupportBand = ccSupportBand(),
                            coupleSecondaryItemNegative = coupleSecondaryItemNegative(),
                            netSecondaryItemBudget = netSecondaryItemBudget(),
                            exactNormalSubPacking = exactNormalSubPacking(),
                            foldNegativeItemAp = foldNegativeItemAp(),
                            foldNegativeMaxMp = foldNegativeMaxMp(),
                            splitLightWeaponCondition = splitLightWeaponCondition(),
                            requireConditionalSub = requireConditionalSub(),
                            critAwareCollapse = critAwareCollapse(),
                            critWeightAnchorPercent = critWeightAnchor(),
                            anchorConstTransport = anchorConstTransport(),
                            stateDependentMpRamp = stateDependentMpRamp(),
                            elideImpliedConditionalMarker = elideImpliedConditionalMarker(),
                            secondarySupportPrice = secondarySupportPrice()
                        )
                    ) { "$label: the prototype bailed on a supported shape" }
                println(
                    "S4_PROTO_LOCK $label incumbent=$incumbent bound=${bound.foldedBound} optimal=$optimal conditionalOnly=$partitioned " +
                        "ratio=${"%.4f".format(bound.foldedBound.toDouble() / incumbent.coerceAtLeast(1))}"
                )
                assertThat(bound.foldedBound)
                    .describedAs("$label: SOUNDNESS — the prototype must never under-count the CP-SAT soft objective")
                    .isGreaterThanOrEqualTo(incumbent)
            }
        }

    @Test
    fun `manual S4 prototype tightness on the frontier fixture`() {
        assumeTrue(System.getenv("WAKFU_S4_PROTO") == "1")
        val (clazz, level, targets) = shapePreset()
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val p = mdParams(level, targets, clazz)
        // Provenance and targeted grid screens skip the ~47-minute canonical fine read. Manual
        // harness only; none of these switches changes the default certificate semantics.
        val pathOnly = System.getenv("WAKFU_S4_PATH_ONLY") == "1"
        val gridProfile = System.getenv("WAKFU_S4_GRID_PROFILE")?.lowercase()
        val skipDefault = pathOnly || gridProfile != null || System.getenv("WAKFU_S4_SKIP_DEFAULT") == "1"
        val diagnostic = diagnosticBasePlain()
        val bound =
            if (skipDefault) {
                null
            } else {
                requireNotNull(
                    MaxDamageSoftCertificate.bound(
                        p,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        debug = timings(),
                        // QUICK tier: the block dim multiplies the main world ~9× (OOMs 8g); MM
                        // measured its refinement worth ~1.3pt — not needed for the tightness verdict.
                        blockGate = false,
                        ccSupportLambda = ccSupportLambda(),
                        ccSupportBand = ccSupportBand(),
                        coupleSecondaryItemNegative = coupleSecondaryItemNegative(),
                        netSecondaryItemBudget = netSecondaryItemBudget(),
                        exactNormalSubPacking = exactNormalSubPacking(),
                        foldNegativeItemAp = foldNegativeItemAp(),
                        foldNegativeMaxMp = foldNegativeMaxMp(),
                        splitLightWeaponCondition = splitLightWeaponCondition(),
                        requireConditionalSub = requireConditionalSub(),
                        diagnosticBasePlain = diagnostic,
                        critAwareCollapse = critAwareCollapse(),
                        critWeightAnchorPercent = critWeightAnchor(),
                        anchorConstTransport = anchorConstTransport(),
                        stateDependentMpRamp = stateDependentMpRamp(),
                        elideImpliedConditionalMarker = elideImpliedConditionalMarker()
                    )
                ) { "the prototype bailed on the canonical S4 shape" }
            }
        // TYPED oracle: the interval's lower side must be the PROVEN no-condition optimum for
        // THIS request/pool — solved here (real-parallel, ~1 min) unless the env override is set
        // (the override remains for controlled A/Bs; it is trusted, not re-proven).
        // In the adaptive profile the solve starts CONCURRENTLY with the coarse DP pass (they are
        // independent; the DP first touches the oracle after coarse, so the ~42 s CP-SAT hides
        // entirely under the ~2 min coarse sweep). Targeted grid screens keep the sequential
        // solve: their wall readings are A/B material and must not share the CPU with CP-SAT.
        val oracleEnv = System.getenv("WAKFU_S4_ORACLE")?.toLongOrNull()
        val oracleFuture =
            if (oracleEnv == null && gridProfile == "adaptive") {
                java.util.concurrent.CompletableFuture
                    .supplyAsync { solvedNoConditionOracle(p, pool).objective }
            } else {
                null
            }
        val oracle: Long? by lazy { oracleEnv ?: oracleFuture?.join() ?: solvedNoConditionOracle(p, pool).objective }
        if (bound != null) {
            println(
                "S4_PROTO_TIGHTNESS bound=${bound.foldedBound} core=${bound.coreBound} states=${bound.states} " +
                    "wallMs=${bound.wallMs} binding=[${bound.bindingState}]" +
                    (if (diagnostic) " diagnostic=BASE_PLAIN_UNSOUND" else "") +
                    (oracle?.let { " oracle=$it ratio=${"%.4f".format(bound.foldedBound.toDouble() / it)}" } ?: " oracle=UNSET")
            )
            if (!diagnostic) {
                oracle?.let { o ->
                    assertThat(bound.foldedBound)
                        .describedAs("SOUNDNESS canary — the bound must cover the banked S4 incumbent")
                        .isGreaterThanOrEqualTo(o)
                }
            }
        }
        if (gridProfile == "adaptive") {
            require(!diagnostic) { "adaptive is already a sound all-world promotion; do not combine it with plain diagnostic mode" }
            // No oracle guard here: the typed oracle always resolves (env override or an inline
            // PROVEN solve), and touching it before the coarse pass would serialize the concurrent
            // CP-SAT solve back behind the DP.
            val adaptiveT0 = System.nanoTime()
            var coarseStates = 0
            var refinedStates = 0
            var refinedCount = 0
            var finalBound = 0L
            try {
                // Coarse HP=2000 by default: the §9.2 grid screen measured this bucketing
                // bound-inert on the canonical shape. Research A/Bs may make it coarser through
                // WAKFU_S4_COARSE_HP_STEP; refinements always restore HP=1000 below.
                MaxDamageSoftCertificate.diStep = 10
                MaxDamageSoftCertificate.hpStep =
                    System.getenv("WAKFU_S4_COARSE_HP_STEP")?.toIntOrNull()?.coerceAtLeast(1) ?: 2000
                MaxDamageSoftCertificate.ccStep = 20
                val coarse =
                    requireNotNull(
                        MaxDamageSoftCertificate.bound(
                            p,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations,
                            debug = timings(),
                            blockGate = false,
                            ccSupportLambda = ccSupportLambda(),
                            ccSupportBand = ccSupportBand(),
                            coupleSecondaryItemNegative = coupleSecondaryItemNegative(),
                            netSecondaryItemBudget = netSecondaryItemBudget(),
                            exactNormalSubPacking = exactNormalSubPacking(),
                            foldNegativeItemAp = foldNegativeItemAp(),
                            // The coarse pass may keep the older optimistic MP read. It remains a
                            // sound upper bound; only contenders pay the signed-MP refinement.
                            foldNegativeMaxMp = false,
                            splitLightWeaponCondition = false,
                            // Keep the cheap full-space upper here. Pricing the coarse pass
                            // conditional-only was MEASURED BOUND-INERT (2026-07-16): at this
                            // grid the binding path already carries a conditional sub, so the
                            // marker bit doubled the states (63k→126k per main world) without
                            // moving a single bound — the 19.4T→17.6T tightening seen in
                            // refinements comes from their finer seams (HP=1000, signed MP,
                            // light-weapon split), not from the conditional marker. Do not retry.
                            requireConditionalSub = false,
                            stateDependentMpRamp = stateDependentMpRamp()
                        )
                    ) { "adaptive coarse pass bailed" }
                coarseStates = coarse.states
                val pending = coarse.worldReads.sortedByDescending { it.foldedBound }
                require(pending.isNotEmpty()) { "adaptive coarse pass did not expose world reads" }
                if (foldNegativeMaxMp()) println("S4_PROTO_ADAPTIVE coarseMpDebit=RELAXED refineMpDebit=SIGNED")

                // Refinements restore HP=1000 by default. A/Bs may screen a coarser independently
                // sound grid; it is promotable only when real-shape bounds stay unchanged.
                MaxDamageSoftCertificate.hpStep =
                    System.getenv("WAKFU_S4_REFINE_HP_STEP")?.toIntOrNull()?.coerceAtLeast(1) ?: 1000

                fun refineAt(
                    world: MaxDamageSoftCertificate.WorldRead,
                    di: Int,
                ): MaxDamageSoftCertificate.Result {
                    MaxDamageSoftCertificate.diStep = di
                    val supportLambda = perWorldSupportLambda(level, world)
                    return requireNotNull(
                        MaxDamageSoftCertificate.bound(
                            p,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations,
                            debug = timings(),
                            blockGate = false,
                            ccSupportLambda = supportLambda,
                            ccSupportBand = ccSupportBand(),
                            coupleSecondaryItemNegative = coupleSecondaryItemNegative(),
                            netSecondaryItemBudget = netSecondaryItemBudget(),
                            exactNormalSubPacking = exactNormalSubPacking(),
                            foldNegativeItemAp = foldNegativeItemAp(),
                            foldNegativeMaxMp = foldNegativeMaxMp(),
                            splitLightWeaponCondition = splitLightWeaponCondition(),
                            requireConditionalSub = requireConditionalSub(),
                            worldAssume = world.assume,
                            worldDropCaps = world.assume == null,
                            rampWorldRequired = world.rampRequired,
                            worldArm = world.arm,
                            critAwareCollapse = critAwareCollapse(),
                            critWeightAnchorPercent = critWeightAnchor(),
                            anchorConstTransport = anchorConstTransport(),
                            stateDependentMpRamp = stateDependentMpRamp(),
                            elideImpliedConditionalMarker = elideImpliedConditionalMarker()
                        )
                    ) {
                        "adaptive DI=$di refinement bailed for assume=${world.assume?.name?.fr ?: "-"} " +
                            "arm=${world.arm} lambda=$supportLambda"
                    }
                }

                fun unionOf(bound: Long): Long = if (requireConditionalSub()) maxOf(requireNotNull(oracle), bound) else bound

                fun refineLog(
                    world: MaxDamageSoftCertificate.WorldRead,
                    refined: MaxDamageSoftCertificate.Result,
                    tierLabel: String,
                ) = println(
                    "S4_PROTO_ADAPTIVE_REFINE assume=${world.assume?.name?.fr ?: "-"} arm=${world.arm} " +
                        "coarse=${world.foldedBound} refined=${refined.foldedBound} union=${unionOf(refined.foldedBound)} " +
                        "tier=$tierLabel lambda=${perWorldSupportLambda(level, world)} conditionalOnly=${requireConditionalSub()} " +
                        "states=${refined.states} wallMs=${refined.wallMs}"
                )

                // Best-first grid cascade — every tier is independently sound. Refine only the
                // world with the largest CURRENT upper, one tier at a time; once that winner is at
                // DI=1, every other world's current (possibly coarse) upper is already below it.
                // This avoids fully refining the coarse winner before a cheap DI=10 scout reveals
                // that another arm owns the fine-grid maximum (IOP-200: plain → secZero).
                // Refinements remain SEQUENTIAL: a parallel DI10 wave over the contenders measured
                // (2026-07-16) at 87-94 s per world vs 29 s solo — the DP is memory-bandwidth/
                // GC-bound, so world-level threads lose here exactly as in the coarse sweep.
                data class AdaptiveCandidate(
                    val world: MaxDamageSoftCertificate.WorldRead,
                    var upper: Long,
                    var tierIndex: Int = -1,
                )

                val tiers = intArrayOf(10, 4, 1)
                val candidates = pending.map { AdaptiveCandidate(it, it.foldedBound) }
                val refinedWorlds = mutableSetOf<MaxDamageSoftCertificate.WorldRead>()
                val floor = if (requireConditionalSub()) requireNotNull(oracle) else 0L
                while (true) {
                    val top = candidates.maxBy { it.upper }
                    if (top.upper <= floor || top.tierIndex == tiers.lastIndex) {
                        finalBound = maxOf(floor, top.upper)
                        break
                    }
                    top.tierIndex =
                        if (skipMidTierForHighSecZero() &&
                            level >= 175 &&
                            top.world.arm == "secZero" &&
                            top.tierIndex >= 0 &&
                            tiers[top.tierIndex] == 10
                        ) {
                            tiers.lastIndex
                        } else {
                            top.tierIndex + 1
                        }
                    val tier = tiers[top.tierIndex]
                    val refined = refineAt(top.world, tier)
                    refinedStates += refined.states
                    refinedWorlds += top.world
                    // Both the previous grid and the new one are sound on this exact world. Their
                    // min is sound too and prevents a coarser-axis anomaly from raising the queue.
                    top.upper = minOf(top.upper, refined.foldedBound)
                    refineLog(top.world, refined, "DI$tier-best-first")
                }
                refinedCount = refinedWorlds.size
                val remainingUpper = candidates.filter { it.tierIndex < 0 }.maxOfOrNull { it.upper } ?: 0L
                val wallMs = (System.nanoTime() - adaptiveT0) / 1_000_000
                println(
                    "S4_PROTO_ADAPTIVE bound=$finalBound refinedWorlds=$refinedCount " +
                        "remainingCoarseUpper=$remainingUpper states=${coarseStates + refinedStates} wallMs=$wallMs" +
                        (oracle?.let { " oracle=$it ratio=${"%.4f".format(finalBound.toDouble() / it)}" } ?: " oracle=UNSET")
                )
                oracle?.let { o ->
                    assertThat(finalBound)
                        .describedAs("adaptive mixed-grid certificate must cover the banked incumbent")
                        .isGreaterThanOrEqualTo(o)
                }
            } finally {
                MaxDamageSoftCertificate.diStep = 1
                MaxDamageSoftCertificate.hpStep = 500
                MaxDamageSoftCertificate.ccStep = 10
            }
        }
        // Targeted screens refine only the axis touched by an idea. `all` retains the historical
        // grid sweep; `fine` is the 47-minute promotion gate and should not be an iteration loop.
        val grids =
            when (gridProfile) {
                "coarse" -> listOf(Triple(10, 1000, 20))
                "di" -> listOf(Triple(1, 1000, 20))
                "cc" -> listOf(Triple(10, 1000, 10))
                "hp" -> listOf(Triple(10, 500, 20))
                "fine" -> listOf(Triple(1, 500, 10))
                "adaptive" -> emptyList()
                "all" -> listOf(Triple(2, 500, 10), Triple(4, 1000, 10), Triple(5, 1500, 20), Triple(10, 2000, 20))
                null ->
                    if (System.getenv("WAKFU_S4_GRID") == "1") {
                        listOf(Triple(2, 500, 10), Triple(4, 1000, 10), Triple(5, 1500, 20), Triple(10, 2000, 20))
                    } else {
                        emptyList()
                    }
                else -> error("unknown WAKFU_S4_GRID_PROFILE='$gridProfile'; expected coarse|di|cc|hp|adaptive|fine|all")
            }
        if (grids.isNotEmpty()) {
            for ((di, hp, cc) in grids) {
                MaxDamageSoftCertificate.diStep = di
                MaxDamageSoftCertificate.hpStep = hp
                MaxDamageSoftCertificate.ccStep = cc
                try {
                    val g =
                        MaxDamageSoftCertificate.bound(
                            p,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            WakfuBestBuildFinderAlgorithm.sublimations,
                            debug = timings(),
                            blockGate = false,
                            ccSupportLambda = ccSupportLambda(),
                            ccSupportBand = ccSupportBand(),
                            coupleSecondaryItemNegative = coupleSecondaryItemNegative(),
                            netSecondaryItemBudget = netSecondaryItemBudget(),
                            exactNormalSubPacking = exactNormalSubPacking(),
                            foldNegativeItemAp = foldNegativeItemAp(),
                            foldNegativeMaxMp = foldNegativeMaxMp(),
                            splitLightWeaponCondition = splitLightWeaponCondition(),
                            requireConditionalSub = requireConditionalSub(),
                            diagnosticBasePlain = diagnostic,
                            critAwareCollapse = critAwareCollapse(),
                            critWeightAnchorPercent = critWeightAnchor(),
                            anchorConstTransport = anchorConstTransport(),
                            stateDependentMpRamp = stateDependentMpRamp()
                        )
                    println(
                        "S4_PROTO_GRID profile=${gridProfile ?: "legacy"} di=$di hp=$hp cc=$cc " +
                            "bound=${g?.foldedBound} states=${g?.states} wallMs=${g?.wallMs}" +
                            " binding=[${g?.bindingState}]" +
                            (if (diagnostic) " diagnostic=BASE_PLAIN_UNSOUND" else "") +
                            (oracle?.let { " ratio=${"%.4f".format((g?.foldedBound ?: 0).toDouble() / it)}" } ?: "")
                    )
                    if (g != null && !diagnostic) {
                        oracle?.let { o ->
                            assertThat(g.foldedBound)
                                .describedAs("grid d$di/hp$hp/cc$cc must stay sound vs the banked incumbent")
                                .isGreaterThanOrEqualTo(o)
                        }
                    }
                } finally {
                    MaxDamageSoftCertificate.diStep = 1
                    MaxDamageSoftCertificate.hpStep = 500
                    MaxDamageSoftCertificate.ccStep = 10
                }
            }
        }
        // Binding-path provenance (WAKFU_S4_PATH=1): coarse grid so the retained stage maps fit
        // the heap; the path names the option every stage contributed to the argmax state.
        if (System.getenv("WAKFU_S4_PATH") == "1") {
            val pathArm = System.getenv("WAKFU_S4_PATH_ARM")?.takeIf(String::isNotBlank)
            val pathLightArm = System.getenv("WAKFU_S4_PATH_LIGHT_ARM")?.takeIf(String::isNotBlank)
            MaxDamageSoftCertificate.diStep = if (System.getenv("WAKFU_S4_PATH_DI") == "1") 1 else 10
            MaxDamageSoftCertificate.ccStep = System.getenv("WAKFU_S4_PATH_CC")?.toIntOrNull() ?: 20
            MaxDamageSoftCertificate.hpStep = System.getenv("WAKFU_S4_PATH_HP")?.toIntOrNull() ?: 1000
            try {
                val pathSublimations =
                    if (System.getenv("WAKFU_S4_PATH_NO_COND_CATALOG") == "1") {
                        WakfuBestBuildFinderAlgorithm.sublimations.filter { it.condition == null }
                    } else {
                        WakfuBestBuildFinderAlgorithm.sublimations
                    }
                val path =
                    MaxDamageSoftCertificate.bound(
                        p,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        pathSublimations,
                        diag = if (System.getenv("WAKFU_S4_PATH_NOCOND") == "1") setOf("noCondSubs") else emptySet(),
                        debug = timings(),
                        blockGate = false,
                        provenance = System.getenv("WAKFU_S4_PATH_FAST") != "1",
                        ccSupportLambda = ccSupportLambda(),
                        ccSupportBand = ccSupportBand(),
                        coupleSecondaryItemNegative = coupleSecondaryItemNegative(),
                        netSecondaryItemBudget = netSecondaryItemBudget(),
                        exactNormalSubPacking = exactNormalSubPacking(),
                        foldNegativeItemAp = foldNegativeItemAp(),
                        foldNegativeMaxMp = foldNegativeMaxMp(),
                        splitLightWeaponCondition = splitLightWeaponCondition(),
                        requireConditionalSub = requireConditionalSub(),
                        diagnosticBasePlain = diagnostic,
                        worldDropCaps = pathArm != null,
                        rampWorldRequired = rampWorldRequired(),
                        lightWeaponArm = pathLightArm,
                        worldArm = pathArm,
                        critAwareCollapse = critAwareCollapse(),
                        critWeightAnchorPercent = critWeightAnchor(),
                        anchorConstTransport = anchorConstTransport(),
                        diagnosticBindingCcBandLow = System.getenv("WAKFU_S4_PATH_CC_BAND")?.toLongOrNull(),
                        stateDependentMpRamp = stateDependentMpRamp(),
                        elideImpliedConditionalMarker = elideImpliedConditionalMarker(),
                        secondarySupportPrice = secondarySupportPrice(),
                        secondaryNetDimension = System.getenv("WAKFU_S4_SEC_DIM") == "1",
                        secondaryNegBudgetDimension = System.getenv("WAKFU_S4_SEC_DIM2") == "1"
                    )
                println("S4_PROTO_PATH bound=${path?.foldedBound} binding=[${path?.bindingState}]")
                path
                    ?.targetCellBounds
                    ?.entries
                    ?.sortedByDescending { it.value }
                    ?.take(20)
                    ?.forEach { (cell, bound) -> println("S4_PROTO_CELL cell=$cell bound=$bound") }
                println("S4_PROTO_BELOW_TARGET ${path?.belowTargetBounds}")
                if (path != null && System.getenv("WAKFU_S4_FRONTIER_REGION") == "1") {
                    val frontier =
                        MaxDamageSoftCertificate.frontierRegionUpper(
                            p,
                            pool,
                            WakfuBestBuildFinderAlgorithm.runes,
                            pathSublimations,
                            path,
                            requireNotNull(System.getenv("WAKFU_S4_FRONTIER_INCUMBENT")?.toLongOrNull()),
                            System.getenv("WAKFU_S4_FRONTIER_WORKERS")?.toIntOrNull() ?: 8,
                            System.getenv("WAKFU_S4_FRONTIER_SECONDS")?.toDoubleOrNull() ?: 120.0
                        )
                    println("S4_PROTO_FRONTIER_REGION $frontier")
                }
                path?.bindingPath?.forEach { println("S4_PROTO_PATH_STEP $it") }
            } finally {
                MaxDamageSoftCertificate.diStep = 1
                MaxDamageSoftCertificate.ccStep = 10
                MaxDamageSoftCertificate.hpStep = 500
            }
        }
        // Attribution: price the big relaxations (UNSOUND arms — deltas only). Opt-in: each arm
        // is a full DP (~12 min) — only worth re-running after a structural change.
        if (System.getenv("WAKFU_S4_ATTRIB") != "1") return
        for (arm in listOf("noCondSubs", "noSubs", "noSkills", "noRunes")) {
            val armBound =
                MaxDamageSoftCertificate.bound(
                    p,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.sublimations,
                    diag = setOf(arm),
                    debug = timings(),
                    blockGate = false,
                    ccSupportLambda = ccSupportLambda(),
                    ccSupportBand = ccSupportBand(),
                    coupleSecondaryItemNegative = coupleSecondaryItemNegative(),
                    netSecondaryItemBudget = netSecondaryItemBudget(),
                    exactNormalSubPacking = exactNormalSubPacking(),
                    foldNegativeItemAp = foldNegativeItemAp(),
                    foldNegativeMaxMp = foldNegativeMaxMp(),
                    splitLightWeaponCondition = splitLightWeaponCondition(),
                    requireConditionalSub = requireConditionalSub(),
                    diagnosticBasePlain = diagnostic,
                    critAwareCollapse = critAwareCollapse(),
                    critWeightAnchorPercent = critWeightAnchor(),
                    anchorConstTransport = anchorConstTransport()
                )
            println("S4_PROTO_ATTRIB arm=$arm bound=${armBound?.foldedBound ?: "bail"}")
        }
    }

    /** Multi-slope scalar envelope: min across independently sound anchors per CC band. */
    @Test
    fun `manual S4 crit anchor envelope`() {
        val anchors =
            System
                .getenv("WAKFU_S4_CRIT_ANCHORS")
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                .orEmpty()
        assumeTrue(anchors.isNotEmpty())
        val (clazz, level, targets) = shapePreset()
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val p = mdParams(level, targets, clazz)
        val reads =
            anchors.associateWith { anchor ->
                requireNotNull(
                    MaxDamageSoftCertificate.bound(
                        p,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
                        blockGate = false,
                        ccSupportLambda = ccSupportLambda(),
                        ccSupportBand = ccSupportBand(),
                        coupleSecondaryItemNegative = coupleSecondaryItemNegative(),
                        netSecondaryItemBudget = netSecondaryItemBudget(),
                        exactNormalSubPacking = exactNormalSubPacking(),
                        foldNegativeItemAp = foldNegativeItemAp(),
                        foldNegativeMaxMp = foldNegativeMaxMp(),
                        splitLightWeaponCondition = splitLightWeaponCondition(),
                        requireConditionalSub = requireConditionalSub(),
                        critAwareCollapse = true,
                        critWeightAnchorPercent = anchor,
                        stateDependentMpRamp = stateDependentMpRamp(),
                        elideImpliedConditionalMarker = elideImpliedConditionalMarker()
                    )
                )
            }
        val bands = reads.values.flatMapTo(sortedSetOf()) { it.ccBandBounds.keys }
        val envelopeByBand =
            bands.associateWith { band ->
                reads.values.minOf { read -> read.ccBandBounds[band] ?: Long.MAX_VALUE }
            }
        val (worstBand, envelope) = envelopeByBand.maxBy { it.value }

        data class BandOwner(
            val anchor: Int,
            val assume: String,
            val arm: String,
            val bound: Long,
        )

        val owners =
            reads.flatMap { (anchor, read) ->
                read.worldReads.mapNotNull { world ->
                    world.ccBandBounds[worstBand]?.let { bandBound ->
                        BandOwner(anchor, world.assume?.name?.fr ?: "-", world.arm, bandBound)
                    }
                }
            }
        // The envelope first takes max(world) for each anchor, then min(anchor). Reconstruct that
        // order so the diagnostic names the world that truly owns the winning band.
        val anchorOwners = owners.groupBy { it.anchor }.mapValues { (_, candidates) -> candidates.maxBy { it.bound } }
        val envelopeOwner = anchorOwners.minBy { it.value.bound }.value
        val oracle = requireNotNull(System.getenv("WAKFU_S4_ORACLE")?.toLongOrNull())
        println(
            "S4_CRIT_ANCHOR_ENVELOPE anchors=$anchors globals=${reads.mapValues { it.value.foldedBound }} " +
                "bands=${bands.size} envelope=$envelope oracle=$oracle " +
                "ratio=${"%.4f".format(envelope.toDouble() / oracle)} worstBand=$worstBand " +
                "anchorBounds=${reads.mapValues { it.value.ccBandBounds[worstBand] }} " +
                "owner=anchor:${envelopeOwner.anchor}/assume:${envelopeOwner.assume}/arm:${envelopeOwner.arm} " +
                "ownerBound=${envelopeOwner.bound} " +
                "anchorOwners=${anchorOwners.mapValues { (_, owner) -> "${owner.assume}/${owner.arm}:${owner.bound}" }}"
        )
        assertThat(envelope).describedAs("multi-anchor per-band envelope must remain sound").isGreaterThanOrEqualTo(oracle)
    }

    /** §9.22 controlled solver-parameter pairs under equal deterministic work. */
    @Test
    fun `manual S4 solver parameter controlled pairs`() {
        val variant = System.getenv("WAKFU_S4_CP_PAIR")
        assumeTrue(variant != null)
        val (clazz, level, targets) = shapePreset()
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val params = mdParams(level, targets, clazz)
        val seedFrom = System.getenv("WAKFU_S4_CP_SEED_FROM")?.toIntOrNull() ?: 1
        val seedCount = System.getenv("WAKFU_S4_CP_SEEDS")?.toIntOrNull() ?: 3
        val deterministicLimit = System.getenv("WAKFU_S4_CP_DET")?.toDoubleOrNull() ?: 120.0
        val seconds = System.getenv("WAKFU_S4_CP_SECONDS")?.toDoubleOrNull() ?: 1200.0

        data class Arm(
            val name: String,
            val linearizationLevel: Int = 2,
            val extraSubsolvers: List<String> = emptyList(),
            val symmetryLevel: Int? = null,
            val maxPresolveIterations: Int = 3,
            val detectLinearizedProduct: Boolean = false,
        )

        val baseline = Arm("baseline")
        val candidate =
            when (variant) {
                "lin1" -> Arm("lin1", linearizationLevel = 1)
                "fixed" -> Arm("fixed", extraSubsolvers = listOf("fixed"))
                "sym3" -> Arm("sym3", symmetryLevel = 3)
                "sym4" -> Arm("sym4", symmetryLevel = 4)
                "presolve8" -> Arm("presolve8", maxPresolveIterations = 8)
                "detect-product" -> Arm("detect-product", detectLinearizedProduct = true)
                else -> error("unknown WAKFU_S4_CP_PAIR=$variant")
            }
        for (seed in seedFrom until seedFrom + seedCount) {
            val ordered = if (seed % 2 == 1) listOf(baseline, candidate) else listOf(candidate, baseline)
            val reads = linkedMapOf<String, WakfuBuildSolver.MaxDamageTimedProfile>()
            for (arm in ordered) {
                val profile =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        params = params,
                        equipmentsByItemType = pool,
                        runes = WakfuBestBuildFinderAlgorithm.runes,
                        sublimations = WakfuBestBuildFinderAlgorithm.sublimations,
                        workers = 1,
                        seconds = seconds,
                        applyDomination = true,
                        randomSeed = seed,
                        experiment = MaxDamageExperimentConfig.DEFAULT,
                        deterministicLimit = deterministicLimit,
                        interleave = true,
                        linearizationLevel = arm.linearizationLevel,
                        extraSubsolvers = arm.extraSubsolvers,
                        symmetryLevel = arm.symmetryLevel,
                        maxPresolveIterations = arm.maxPresolveIterations,
                        detectLinearizedProduct = arm.detectLinearizedProduct
                    )
                reads[arm.name] = profile
                println(
                    "S4_PARAM_PAIR variant=$variant shape=${System.getenv("WAKFU_S4_SHAPE") ?: "s4-245"} seed=$seed " +
                        "order=${ordered.joinToString(",") { it.name }} arm=${arm.name} " +
                        "status=${profile.status} objective=${profile.objective} bound=${profile.bestBound} " +
                        "wall=${profile.wallTimeSec} det=${profile.deterministicTime} branches=${profile.branches} " +
                        "conflicts=${profile.conflicts} lp=${profile.lpIterations}"
                )
            }
            val baseRead = requireNotNull(reads[baseline.name])
            val candidateRead = requireNotNull(reads[candidate.name])
            println(
                "S4_PARAM_PAIR_SUMMARY variant=$variant shape=${System.getenv("WAKFU_S4_SHAPE") ?: "s4-245"} seed=$seed " +
                    "dualRatio=${candidateRead.bestBound.toDouble() / baseRead.bestBound.coerceAtLeast(1L)} " +
                    "branchRatio=${candidateRead.branches.toDouble() / baseRead.branches.coerceAtLeast(1L)} " +
                    "objectiveDelta=${if (candidateRead.hasSolution && baseRead.hasSolution) candidateRead.objective - baseRead.objective else "-"}"
            )
        }
    }

    @Test
    fun `manual S4 binding-arm CP cutoff`() {
        val cellMode = System.getenv("WAKFU_S4_CP_CELL") == "1"
        // WAKFU_S4_CP_PLAIN=1: the UNRESTRICTED full-model solve (full catalog, no cutoff, no
        // conditional constraint) — "does plain CP-SAT prove this shape's soft leg at all?".
        val plainFull = System.getenv("WAKFU_S4_CP_PLAIN") == "1"
        assumeTrue(System.getenv("WAKFU_S4_CP_CUTOFF") != null || cellMode || plainFull)
        val (clazz, level, shapeTargets) = shapePreset()
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val excludedConditionTypes =
            setOf(
                SublimationConditionType.AP_AT_MOST,
                SublimationConditionType.AP_EXACT,
                SublimationConditionType.CRIT_AT_MOST,
                SublimationConditionType.SECONDARY_MASTERIES_AT_MOST,
                SublimationConditionType.CRITICAL_MASTERY_AT_MOST,
                SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED
            )
        // WAKFU_S4_CP_FULLCOND=1 = the PARTITION-EXACTNESS probe: the FULL catalog (every
        // condition modeled) + `requireAnyConditionalSublimation` + the cutoff. INFEASIBLE (or a
        // proven bound below the cutoff) proves NO conditional build beats the no-condition
        // optimum ⇒ the full-model optimum equals it EXACTLY (badge +0%).
        val fullConditional = System.getenv("WAKFU_S4_CP_FULLCOND") == "1"
        val bindingArmSubs =
            when {
                System.getenv("WAKFU_S4_CP_NOSUBS") == "1" -> emptyList()
                System.getenv("WAKFU_S4_CP_ONLYCOND_RELAX") == "1" ->
                    WakfuBestBuildFinderAlgorithm.sublimations
                        .filter { it.condition != null && it.solverChoosable }
                        .map { it.withRelaxedBuildStaticCondition() }
                System.getenv("WAKFU_S4_CP_ONLYCOND") == "1" ->
                    WakfuBestBuildFinderAlgorithm.sublimations.filter { it.condition != null && it.solverChoosable }
                // RELAXCOND: KEEP every sub but strip the conditions (always-on credits) — a sound
                // upper model (only relaxes) with ZERO reifications. §9.22 last family (option D).
                System.getenv("WAKFU_S4_CP_RELAXCOND") == "1" ->
                    WakfuBestBuildFinderAlgorithm.sublimations.map { it.withRelaxedBuildStaticCondition() }
                System.getenv("WAKFU_S4_CP_NOCOND") == "1" ->
                    WakfuBestBuildFinderAlgorithm.sublimations.filter { it.condition == null }
                fullConditional || plainFull -> WakfuBestBuildFinderAlgorithm.sublimations
                else ->
                    WakfuBestBuildFinderAlgorithm.sublimations.filter { sub ->
                        sub.condition?.type !in excludedConditionTypes
                    }
            }
        val cutoff = System.getenv("WAKFU_S4_CP_CUTOFF")?.toLongOrNull()
        val seconds = System.getenv("WAKFU_S4_CP_SECONDS")?.toDoubleOrNull() ?: 600.0
        val workers = System.getenv("WAKFU_S4_CP_WORKERS")?.toIntOrNull() ?: 8
        val deterministicLimit = System.getenv("WAKFU_S4_CP_DET")?.toDoubleOrNull()
        val runParams =
            if (cellMode) {
                // Shape-aware AP cell (WAKFU_S4_CP_APCELL, default 15). CELL_SOFT keeps the FULL
                // target list (incl. AP — its shortfall penalty must price the pinned actual) and
                // the soft penalized objective: max over all achievable AP cells then equals the
                // full soft optimum EXACTLY (the AP pin is a hard equality, so cells partition).
                val apCell = System.getenv("WAKFU_S4_CP_APCELL")?.toIntOrNull() ?: 15
                val cellTargets =
                    if (System.getenv("WAKFU_S4_CP_CELL_SOFT") == "1") {
                        shapeTargets
                    } else {
                        val hpFloor = System.getenv("WAKFU_S4_CP_HP_MIN")?.toIntOrNull()
                        shapeTargets
                            .filter { it.characteristic != Characteristic.ACTION_POINT }
                            .map { target ->
                                if (target.characteristic == Characteristic.HP && hpFloor != null) {
                                    TargetStat(Characteristic.HP, hpFloor, target.userDefinedWeight)
                                } else {
                                    target
                                }
                            }
                    }
                mdParams(level, cellTargets, clazz).copy(
                    maxDamageApTarget = apCell,
                    maxDamageMpPin = System.getenv("WAKFU_S4_CP_MPCELL")?.toIntOrNull()
                )
            } else {
                mdParams(level, shapeTargets, clazz)
            }
        val profile =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params = runParams,
                equipmentsByItemType = pool,
                runes = WakfuBestBuildFinderAlgorithm.runes,
                sublimations = bindingArmSubs,
                workers = workers,
                seconds = seconds,
                applyDomination = true,
                deterministicLimit = deterministicLimit,
                penalizedObjectiveCutoff = cutoff,
                requireAnyConditionalSublimation = fullConditional && !plainFull,
                hardConstraints = cellMode && System.getenv("WAKFU_S4_CP_CELL_SOFT") != "1",
                statLowerBounds =
                    buildMap {
                        System.getenv("WAKFU_S4_CP_MP_MIN")?.toLongOrNull()?.let {
                            put(Characteristic.MOVEMENT_POINT, it)
                        }
                        System.getenv("WAKFU_S4_CP_CC_MIN")?.toLongOrNull()?.let {
                            put(Characteristic.CRITICAL_HIT, it)
                        }
                        System.getenv("WAKFU_S4_CP_HP_MIN")?.toLongOrNull()?.let { put(Characteristic.HP, it) }
                    },
                interleave = System.getenv("WAKFU_S4_CP_INTERLEAVE") == "1",
                logSearch = System.getenv("WAKFU_S4_CP_LOG") == "1",
                linearizationLevel = System.getenv("WAKFU_S4_CP_LIN")?.toIntOrNull() ?: 2,
                maxPresolveIterations = System.getenv("WAKFU_S4_CP_PRESOLVE")?.toIntOrNull() ?: 3,
                detectLinearizedProduct = System.getenv("WAKFU_S4_CP_DETECT_PROD") == "1",
                symmetryLevel = System.getenv("WAKFU_S4_CP_SYM")?.toIntOrNull(),
                extraSubsolvers = System.getenv("WAKFU_S4_CP_EXTRA")?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            )
        println(
            "S4_BINDING_CP cell=$cellMode cutoff=${cutoff ?: "-"} keptSubs=${bindingArmSubs.size} " +
                "status=${profile.status} objective=${profile.objective} bound=${profile.bestBound} " +
                "wall=${profile.wallTimeSec} det=${profile.deterministicTime} branches=${profile.branches} " +
                "selectedSubIds=${profile.selectedSublimationStateIds.sorted()}"
        )
    }

    /**
     * Structural prototype: solve the condition-stripped upper model, validate its complete
     * assignment in the exact model, and restore only the selected conditions that made that
     * assignment infeasible. The small conditional-only CRA-80 fixture closes exactly in seconds
     * and tells us whether this refinement has a realistic iteration count before touching the
     * production path.
     *
     * ```shell
     * WAKFU_S4_COND_REFINE=1 WAKFU_S4_SHAPE=cra80-free \
     *   ./gradlew :autobuilder:test --tests '*MaxDamageSoftCertificateTest*refinement*' --rerun-tasks
     * ```
     */
    @Test
    fun `manual S4 conditional refinement prototype`() {
        assumeTrue(System.getenv("WAKFU_S4_COND_REFINE") == "1")
        val (clazz, level, targets) = shapePreset()
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val conditionalSubs =
            WakfuBestBuildFinderAlgorithm.sublimations
                .filter { it.condition != null && it.solverChoosable }
        val (proven, iterations) =
            WakfuBuildSolver.conditionalRefinementProfileForTest(
                params = mdParams(level, targets, clazz),
                equipmentsByItemType = pool,
                runes = WakfuBestBuildFinderAlgorithm.runes,
                sublimations = conditionalSubs,
                workers = System.getenv("WAKFU_S4_CP_WORKERS")?.toIntOrNull() ?: 8,
                secondsPerIteration = System.getenv("WAKFU_S4_CP_SECONDS")?.toDoubleOrNull() ?: 60.0,
                maxIterations = System.getenv("WAKFU_S4_REFINE_ITERS")?.toIntOrNull() ?: 20,
                applyDomination = true
            )
        for (read in iterations) {
            println(
                "S4_COND_REFINE iteration=${read.iteration} status=${read.status} objective=${read.objective} " +
                    "bound=${read.bestBound} " +
                    "relaxedCount=${read.relaxedConditionIds.size} selectedSubIds=${read.selectedSublimationStateIds.sorted()} " +
                    "enforce=${read.newlyEnforcedConditionIds.sorted()} upperWall=${read.relaxedWallTimeSec} " +
                    "validation=${read.exactValidationStatus} validationWall=${read.exactValidationWallTimeSec}"
            )
        }
        println(
            "S4_COND_REFINE_SUMMARY proven=$proven iterations=${iterations.size} " +
                "objective=${iterations.lastOrNull()?.objective ?: Long.MIN_VALUE}"
        )
        assertThat(iterations).isNotEmpty()
    }

    /** Sound union of a no-condition world and one single-reification upper world per conditional carrier. */
    @Test
    fun `manual S4 per-carrier conditional worlds`() {
        assumeTrue(System.getenv("WAKFU_S4_CARRIER_WORLDS") == "1")
        val (clazz, level, targets) = shapePreset()
        val params = mdParams(level, targets, clazz)
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val modeledConditional =
            WakfuBestBuildFinderAlgorithm.sublimations.filter {
                it.solverChoosable && it.condition?.type in SUPPORTED_SUB_CONDITIONS
            }
        val seconds = System.getenv("WAKFU_S4_CP_SECONDS")?.toDoubleOrNull() ?: 30.0
        val workers = System.getenv("WAKFU_S4_CP_WORKERS")?.toIntOrNull() ?: 8
        val noCondition =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params,
                pool,
                WakfuBestBuildFinderAlgorithm.runes,
                emptyList(),
                workers,
                seconds,
                applyDomination = true
            )
        val reads = arrayListOf(noCondition)
        println(
            "S4_CARRIER_WORLD stateId=none status=${noCondition.status} objective=${noCondition.objective} " +
                "bound=${noCondition.bestBound} wall=${noCondition.wallTimeSec}"
        )
        for (carrier in modeledConditional) {
            val worldSubs =
                modeledConditional.map { sub ->
                    if (sub.stateId == carrier.stateId) sub else sub.withRelaxedBuildStaticCondition()
                }
            val read =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    worldSubs,
                    workers,
                    seconds,
                    applyDomination = true,
                    requiredSublimationStateId = carrier.stateId
                )
            reads += read
            println(
                "S4_CARRIER_WORLD stateId=${carrier.stateId} status=${read.status} objective=${read.objective} " +
                    "bound=${read.bestBound} wall=${read.wallTimeSec} selected=${read.selectedSublimationStateIds.sorted()}"
            )
        }
        val unionUpper = reads.maxOf { if (it.status == "OPTIMAL") it.objective else it.bestBound }
        println(
            "S4_CARRIER_WORLD_SUMMARY upper=$unionUpper allOptimal=${reads.all { it.status == "OPTIMAL" }} " +
                "wall=${reads.sumOf { it.wallTimeSec }}"
        )
        assertThat(unionUpper).isGreaterThanOrEqualTo(0L)
    }

    /** External B&B: branch an invalid relaxed sub into excluded vs selected-with-exact-condition. */
    @Test
    fun `manual S4 conditional world branch and bound`() {
        assumeTrue(System.getenv("WAKFU_S4_WORLD_BB") == "1")
        val (clazz, level, targets) = shapePreset()
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val worldSubs =
            if (System.getenv("WAKFU_S4_WORLD_FULLCAT") == "1") {
                WakfuBestBuildFinderAlgorithm.sublimations
            } else {
                WakfuBestBuildFinderAlgorithm.sublimations.filter {
                    it.condition != null && it.solverChoosable
                }
            }
        val incumbent = System.getenv("WAKFU_S4_INCUMBENT")?.toLongOrNull() ?: 522720L
        val workers = System.getenv("WAKFU_S4_CP_WORKERS")?.toIntOrNull() ?: 8
        val secondsPerNode = System.getenv("WAKFU_S4_CP_SECONDS")?.toDoubleOrNull() ?: 15.0
        val maxNodes = System.getenv("WAKFU_S4_WORLD_NODES")?.toIntOrNull() ?: 50
        val totalSeconds = System.getenv("WAKFU_S4_WORLD_TOTAL")?.toDoubleOrNull()
        val proof =
            if (totalSeconds != null) {
                WakfuBuildSolver.conditionalWorldBranchAndBound(
                    params = mdParams(level, targets, clazz),
                    equipmentsByItemType = pool,
                    runes = WakfuBestBuildFinderAlgorithm.runes,
                    sublimations = worldSubs,
                    incumbentObjective = incumbent,
                    workers = workers,
                    totalSeconds = totalSeconds,
                    maxSecondsPerNode = secondsPerNode,
                    deterministicLimitPerNode = System.getenv("WAKFU_S4_CP_DET")?.toDoubleOrNull(),
                    interleave = System.getenv("WAKFU_S4_CP_INTERLEAVE") == "1",
                    maxNodes = maxNodes,
                    applyDomination = true,
                    requiredFirst = System.getenv("WAKFU_S4_WORLD_REQUIRED_FIRST") == "1"
                )
            } else {
                val (proven, reads) =
                    WakfuBuildSolver.conditionalWorldBranchAndBoundForTest(
                        params = mdParams(level, targets, clazz),
                        equipmentsByItemType = pool,
                        runes = WakfuBestBuildFinderAlgorithm.runes,
                        sublimations = worldSubs,
                        incumbentObjective = incumbent,
                        workers = workers,
                        secondsPerNode = secondsPerNode,
                        deterministicLimitPerNode = System.getenv("WAKFU_S4_CP_DET")?.toDoubleOrNull(),
                        interleave = System.getenv("WAKFU_S4_CP_INTERLEAVE") == "1",
                        maxNodes = maxNodes,
                        applyDomination = true
                    )
                if (proven) {
                    WakfuBuildSolver.ConditionalWorldProof.Proven(incumbent, reads)
                } else {
                    WakfuBuildSolver.ConditionalWorldProof.Inconclusive(Long.MAX_VALUE, reads)
                }
            }
        val proven = proof is WakfuBuildSolver.ConditionalWorldProof.Proven
        val reads = proof.reads
        reads.forEach { read ->
            println(
                "S4_WORLD_BB node=${read.node} required=${read.requiredConditionIds.sorted()} " +
                    "excluded=${read.excludedConditionIds.sorted()} status=${read.status} objective=${read.objective} " +
                    "bound=${read.bestBound} branch=${read.branchedOnStateId ?: "-"} " +
                    "selected=${read.selectedSublimationStateIds.sorted()} disposition=${read.disposition} " +
                    "wall=${read.wallTimeSec} det=${read.deterministicTime}"
            )
        }
        println(
            "S4_WORLD_BB_SUMMARY proven=$proven nodes=${reads.size} wall=${reads.sumOf { it.wallTimeSec }} " +
                "det=${reads.sumOf { it.deterministicTime }} " +
                "incumbent=$incumbent result=$proof"
        )
        assertThat(reads).isNotEmpty()
    }

    /** Exact production orchestration timing for a known full-model incumbent. */
    @Test
    fun `manual S4 conditional world production path`() {
        assumeTrue(System.getenv("WAKFU_S4_WORLD_PROD") == "1")
        val incumbent = requireNotNull(System.getenv("WAKFU_S4_INCUMBENT")?.toLongOrNull())
        val (clazz, level, targets) = shapePreset()
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val t0 = System.nanoTime()
        val union =
            requireNotNull(
                MaxDamageSoftCertificate.hybridUnionUpper(
                    params = mdParams(level, targets, clazz),
                    pool = pool,
                    runes = WakfuBestBuildFinderAlgorithm.runes,
                    sublimations = WakfuBestBuildFinderAlgorithm.sublimations,
                    oracleWorkers = System.getenv("WAKFU_S4_CP_WORKERS")?.toIntOrNull() ?: 8,
                    oracleSeconds = System.getenv("WAKFU_S4_CP_SECONDS")?.toDoubleOrNull() ?: 240.0,
                    incumbentObjective = incumbent
                )
            )
        val wallMs = (System.nanoTime() - t0) / 1_000_000
        println(
            "S4_WORLD_PROD shape=${System.getenv("WAKFU_S4_SHAPE") ?: "s4"} incumbent=$incumbent " +
                "upper=${union.upper} noCond=${union.noConditionUpper} noCondProven=${union.noConditionProven} " +
                "reportedWallMs=${union.wallMs} measuredWallMs=$wallMs"
        )
        assertThat(union.upper).describedAs("production union must never under-count incumbent").isGreaterThanOrEqualTo(incumbent)
    }

    /**
     * Hard-leg AP-cell ledger probe for NO-target shapes (`WAKFU_S4_HARD_LEDGER=1`, shape via
     * `WAKFU_S4_SHAPE`, e.g. cra80-free). Positive soundness closure of the 2026-07-18 false
     * alarm: the ledger's global ceiling must cover the PROVEN no-condition oracle optimum
     * (the oracle solves the same pool/catalog restricted to condition-free subs — a subset of
     * the ledger's coverage, so `maxCellObjective >= oracle` is a hard invariant).
     */
    @Test
    fun `manual hard ledger covers the no-target oracle`() {
        assumeTrue(System.getenv("WAKFU_S4_HARD_LEDGER") == "1")
        val (clazz, level, targets) = shapePreset()
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val p = mdParams(level, targets, clazz)
        val oracle = solvedNoConditionOracle(p, pool)
        val ledger =
            WakfuBuildSolver.maxDamageCertificate(
                params = p,
                equipmentsByItemType = pool,
                runes = WakfuBestBuildFinderAlgorithm.runes,
                sublimations = WakfuBestBuildFinderAlgorithm.sublimations,
                applyDomination = true,
                incumbentObjective = oracle.objective
            )
        requireNotNull(ledger) { "the ledger bailed on a shape it is expected to certify" }
        val cells =
            ledger.cellObjectives.entries
                .sortedBy { it.key }
                .joinToString(" ") { "${it.key}=${it.value}" }
        println("S4_HARD_LEDGER oracle=${oracle.objective} maxCell=${ledger.maxCellObjective} cells=[$cells]")
        val maxCell = requireNotNull(ledger.maxCellObjective) { "a cell bailed — no sound global ceiling" }
        assertThat(maxCell)
            .describedAs("the AP-cell ledger's global ceiling must cover the proven no-condition optimum")
            .isGreaterThanOrEqualTo(oracle.objective)
    }

    /**
     * §9.20 E2E gate for the PRODUCTION soft-leg proof: an incumbent at the known no-condition
     * optimum must come back with a useful ≤1% certificate through the real production entry
     * (`proveMaxDamageOptimality` → target-missing soft branch → `hybridUnionUpper`).
     * v22 deliberately replaces the second CP-SAT oracle with a bounded DP after the main solve
     * stalls; S4 currently returns ProvenWithin(0.343%), and may return ProvenOptimal again when
     * that last no-condition residual closes.
     *
     * ```shell
     * ./gradlew --stop
     * WAKFU_S4_PROD_PROOF=1 WAKFU_TEST_MAX_HEAP=8g ./gradlew :autobuilder:cleanTest \
     *   :autobuilder:test --tests '*MaxDamageSoftCertificateTest*production*' --no-daemon
     * ```
     */
    @Test
    fun `manual S4 production soft proof end-to-end`() {
        assumeTrue(System.getenv("WAKFU_S4_PROD_PROOF") == "1")
        val (clazz, level, targets) = shapePreset()
        // The empty-build routing trick below requires MISSING targets. On a NO-target shape
        // (cra80-free) the empty build satisfies the (vacuous) targets, so the proof routes to
        // the HARD AP-cell ledger with an INCONSISTENT incumbent pair — the oracle's proxy
        // belongs to a ~12-AP build while the empty individual reads base 6 AP — and the ledger
        // self-check rightly refuses (the 2026-07-18 "cell 6 bound=418880 < proxy=820040" false
        // alarm). No-target shapes are covered by the hard-ledger probe test instead.
        assumeTrue(targets.isNotEmpty())
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val p = mdParams(level, targets, clazz)
        val incumbent = solvedNoConditionOracle(p, pool).objective
        val result =
            me.chosante.autobuilder.genetic.SolverResult(
                // An empty build: `fullyMeetsRequiredTargets` is false for it, which is exactly
                // what routes the proof into the soft-leg branch under test.
                individual = BuildCombination(emptyList(), CharacterSkills(level)),
                matchPercentage = java.math.BigDecimal.ZERO,
                progressPercentage = 100,
                isOptimal = false,
                maxDamageObjective = incumbent,
                maxDamageHardConstraintsMet = false
            )
        val proofT0 = System.nanoTime()
        val proof = WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(p, result)
        val proofWallMs = (System.nanoTime() - proofT0) / 1_000_000
        println("S4_PROD_PROOF incumbent=$incumbent verdict=$proof proofWallMs=$proofWallMs")
        val certifiedGap =
            when (proof) {
                MaxDamageSearch.MaxDamageProof.ProvenOptimal -> 0.0
                is MaxDamageSearch.MaxDamageProof.ProvenWithin -> proof.fraction
                MaxDamageSearch.MaxDamageProof.Unavailable -> Double.POSITIVE_INFINITY
            }
        // Per-shape contract. The S4-245 frontier must close EXACTLY (its conditional union
        // falls under the exact no-condition authority) and IOP-200 must stay within the
        // production acceptance floor. Shapes whose authority is a deadline-clipped CP leg
        // (cra80/cra140-class) only promise a DISPLAYABLE badge inside the proof deadline —
        // their exact closure is the open structural work.
        // Honest post-soundness-wave contracts (2026-07-20): the pre-wave tighter values
        // (s4 = 0.0, iop200 = 0.02) were partly the FRUIT of the MP-ramp under-count and the
        // unproven crit-transport down-scaling — both fixed; these are the sound residuals.
        val maxGap =
            when (System.getenv("WAKFU_S4_SHAPE") ?: "s4") {
                "s4" -> 0.005
                "iop200-frontier" -> 0.045
                else -> 0.25
            }
        assertThat(certifiedGap)
            .describedAs("the production soft-leg proof must certify within the shape's contract")
            .isLessThanOrEqualTo(maxGap)
        assertThat(proofWallMs)
            .describedAs("the proof phase must conclude within the product deadline (plus harness slack)")
            .isLessThanOrEqualTo(150_000L)
        // WAKFU_S4_REFINE=1: exercise the SILENT-REFINEMENT pass (per-carrier closure, journal
        // 2026-07-21) after the fast badge. Un-timed (minutes by design). Sacrieur230 is the
        // measured composed-closure shape — its refined verdict must be EXACTLY ProvenOptimal.
        if (System.getenv("WAKFU_S4_REFINE") == "1" && proof is MaxDamageSearch.MaxDamageProof.ProvenWithin) {
            val refineT0 = System.nanoTime()
            val refined = WakfuBestBuildFinderAlgorithm.refineMaxDamageOptimality(p, result)
            val refineWallMs = (System.nanoTime() - refineT0) / 1_000_000
            println("S4_PROD_REFINE verdict=$refined refineWallMs=$refineWallMs")
            val refinedGap =
                when (refined) {
                    MaxDamageSearch.MaxDamageProof.ProvenOptimal -> 0.0
                    is MaxDamageSearch.MaxDamageProof.ProvenWithin -> refined.fraction
                    else -> certifiedGap // null/Unavailable = keep the first badge (still sound)
                }
            assertThat(refinedGap)
                .describedAs("the refinement must never LOOSEN the badge")
                .isLessThanOrEqualTo(certifiedGap)
            if ((System.getenv("WAKFU_S4_SHAPE") ?: "s4") == "sacrieur230-apmp") {
                assertThat(refined)
                    .describedAs("sacrieur230's composed per-carrier closure is measured EXACT — the refined badge must be ProvenOptimal")
                    .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)
            }
        }
    }

    /**
     * §9.20 generality screen: shapes beyond the CRA-245 fixture through the production union.
     * Contract under test: NEVER an exception — a sound union (with a per-shape soundness canary:
     * any full-model primal found in a short solve must sit at or below the union upper) or a
     * clean bail. `WAKFU_S4_PROD_SCREEN=1`; ~10-15 min (two full unions + short primal solves).
     */
    @Test
    fun `manual S4 production soft proof generality screen`() {
        assumeTrue(System.getenv("WAKFU_S4_PROD_SCREEN") == "1")
        val shapes =
            listOf(
                // A different class + level, same unreachable-frontier flavour.
                "iop-200-frontier" to
                    mdParams(
                        200,
                        listOf(
                            TargetStat(Characteristic.ACTION_POINT, 15),
                            TargetStat(Characteristic.MOVEMENT_POINT, 8),
                            TargetStat(Characteristic.CRITICAL_HIT, 100),
                            TargetStat(Characteristic.HP, 10000)
                        ),
                        CharacterClass.IOP
                    ),
                // Mid-level, AP/MP only.
                "cra-140-apmp" to
                    mdParams(
                        140,
                        listOf(
                            TargetStat(Characteristic.ACTION_POINT, 14),
                            TargetStat(Characteristic.MOVEMENT_POINT, 7)
                        )
                    ),
                // Unsupported required target (RANGE): supportsShape must refuse it instantly.
                "xelor-245-range-bail" to
                    mdParams(
                        245,
                        listOf(
                            TargetStat(Characteristic.ACTION_POINT, 16),
                            TargetStat(Characteristic.RANGE, 6)
                        ),
                        CharacterClass.XELOR
                    )
            )
        for ((label, p) in shapes) {
            val level = p.character.level
            val pool =
                WakfuBestBuildFinderAlgorithm.equipments
                    .filter { it.rarity <= Rarity.EPIC }
                    .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                    .groupBy { it.itemType }
            val union =
                MaxDamageSoftCertificate.hybridUnionUpper(
                    p,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.sublimations,
                    oracleWorkers = 8,
                    oracleSeconds = 180.0
                )
            println(
                "S4_PROD_SCREEN shape=$label union=${union?.upper ?: "bail"} " +
                    "noCond=${union?.noConditionUpper} noCondProven=${union?.noConditionProven} wallMs=${union?.wallMs}"
            )
            if (label.endsWith("-bail")) {
                assertThat(union).describedAs("$label must bail (unsupported target)").isNull()
                continue
            }
            if (union == null) continue // a clean bail is acceptable; the screen only forbids exceptions/unsoundness
            // Soundness canary: any primal of the FULL model must sit at or below the union upper.
            val primal =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params = p,
                    equipmentsByItemType = pool,
                    runes = WakfuBestBuildFinderAlgorithm.runes,
                    sublimations = WakfuBestBuildFinderAlgorithm.sublimations,
                    workers = 8,
                    seconds = 60.0,
                    applyDomination = true
                )
            println("S4_PROD_SCREEN shape=$label primalStatus=${primal.status} primal=${primal.objective}")
            if (primal.hasSolution) {
                assertThat(union.upper)
                    .describedAs("$label: the union upper must cover every full-model primal")
                    .isGreaterThanOrEqualTo(primal.objective)
            }
        }
    }

    @Test
    fun `manual S4 certificate witness exact rescore`() {
        assumeTrue(System.getenv("WAKFU_S4_WITNESS") == "1")
        val level = 245
        val p = mdParams(level, frontierTargets())
        val equipmentIds =
            setOf(
                31908,
                32541,
                26497,
                32499,
                31904,
                31976,
                32213,
                29284,
                32097,
                29352,
                32026,
                27205,
                14422,
                29441
            )
        val equipments =
            equipmentIds.map { id ->
                WakfuBestBuildFinderAlgorithm.equipments.single { it.equipmentId == id }
            }
        val relevantRuneStats =
            setOf(
                Characteristic.MASTERY_ELEMENTARY,
                Characteristic.MASTERY_DISTANCE,
                Characteristic.MASTERY_BACK,
                Characteristic.MASTERY_CRITICAL
            )
        val runes =
            equipments
                .filter { it.maxShardSlots > 0 }
                .associateWith { item ->
                    val best =
                        WakfuBestBuildFinderAlgorithm.runes
                            .filter { it.characteristic in relevantRuneStats }
                            .maxBy { it.valueOn(item.itemType, item.level) }
                    List(item.maxShardSlots) { best }
                }

        val skills = CharacterSkills(level)
        skills.intelligence.hpPercentage.setPointAssigned(11)
        skills.strength.masteryElementary.setPointAssigned(21)
        skills.strength.masteryDistance.setPointAssigned(40)
        skills.luck.criticalHit.setPointAssigned(20)
        skills.luck.masteryBack.setPointAssigned(41)
        skills.major.actionPoint.setPointAssigned(1)
        skills.major.movementPointAndMasteryElementary.setPointAssigned(1)
        skills.major.controlAndMasteryElementary.setPointAssigned(1)
        skills.major.damageInflicted.setPointAssigned(1)

        fun sub(name: String) = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == name }

        val normalSubs =
            listOf(
                sub("Ravage III"),
                sub("Carnage III"),
                sub("Carnage III"),
                sub("Vivacité II"),
                sub("Destruction III"),
                sub("Destruction III"),
                sub("Poids Plume III"),
                sub("Influence vitale III"),
                sub("Influence vitale III"),
                sub("Brûlure III")
            )
        val normalCarriers = equipments.filter { it.maxShardSlots >= 3 }.take(normalSubs.size)
        val sublimations =
            normalCarriers
                .zip(normalSubs)
                .associate { (carrier, chosen) -> carrier to mutableListOf(chosen) }
                .toMutableMap()
                .apply {
                    val epicCarrier = equipments.single { it.equipmentId == 32097 }
                    getOrPut(epicCarrier) { mutableListOf() }.add(sub("Anatomie"))
                }.mapValues { it.value.toList() }
        val build = BuildCombination(equipments, skills, runes, sublimations)
        val stats =
            computeCharacteristicsValues(
                build,
                p.character.baseCharacteristicValues,
                masteryElementsWanted = mapOf(p.damageScenario.element.masteryCharacteristic to 1),
                resistanceElementsWanted = p.targetStats.resistanceElementsWanted,
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                damageScenario = p.damageScenario
            )
        val grawUnits =
            100L +
                (stats[p.damageScenario.element.masteryCharacteristic] ?: 0) +
                (stats[Characteristic.MASTERY_DISTANCE] ?: 0) +
                (stats[Characteristic.MASTERY_BACK] ?: 0) +
                (stats[Characteristic.MASTERY_CRITICAL] ?: 0)
        val score = FindMaxDamageScoring.computeScore(p.targetStats, build, p.character.baseCharacteristicValues, p.damageScenario)
        println(
            "S4_WITNESS valid=${build.isValid()} score=$score grawUnits=$grawUnits " +
                "AP=${stats[Characteristic.ACTION_POINT]} MP=${stats[Characteristic.MOVEMENT_POINT]} " +
                "CC=${stats[Characteristic.CRITICAL_HIT]} HP=${stats[Characteristic.HP]} " +
                "DI=${stats[Characteristic.DAMAGE_INFLICTED]} " +
                "elem=${stats[p.damageScenario.element.masteryCharacteristic]} " +
                "distance=${stats[Characteristic.MASTERY_DISTANCE]} back=${stats[Characteristic.MASTERY_BACK]} " +
                "critM=${stats[Characteristic.MASTERY_CRITICAL]}"
        )
        runes.forEach { (item, chosen) ->
            println("S4_WITNESS_RUNE ${item.name.fr}=${chosen.first().name.fr}:${chosen.first().valueOn(item.itemType, item.level)}x${chosen.size}")
        }
    }

    /** Pin the IOP-200 secZero DP provenance into the exact CP model. */
    @Test
    fun `manual IOP DP witness exact validation`() {
        assumeTrue(System.getenv("WAKFU_IOP_DP_WITNESS") == "1")
        val (_, level, targets) = shapePreset()
        require(level == 200) { "run with WAKFU_S4_SHAPE=iop200-frontier" }
        val params = mdParams(level, targets, CharacterClass.IOP)
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val equipmentIds =
            setOf(
                26897,
                27416,
                26497,
                26287,
                26996,
                25078,
                27557,
                26952,
                26323,
                21760,
                21859,
                14527,
                14422,
                27923
            )
        val subCopies =
            mapOf(
                6005 to 1, // Vélocité II
                6008 to 1, // Vivacité II
                6825 to 2, // Destruction III
                6931 to 1, // Neutralité III
                7088 to 1, // Poids Plume III
                7115 to 1, // Ambition III
                7862 to 2, // Influence vitale III
                8518 to 1, // Brûlure III
                5445 to 1 // Anatomie
            )
        val read =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                params = params,
                equipmentsByItemType = pool,
                runes = WakfuBestBuildFinderAlgorithm.runes,
                sublimations = WakfuBestBuildFinderAlgorithm.sublimations,
                workers = 8,
                seconds = 120.0,
                applyDomination = false,
                pinnedEquipmentIds = equipmentIds,
                pinnedSublimationCopies = subCopies
            )
        println(
            "IOP_DP_WITNESS status=${read.status} objective=${read.objective} bound=${read.bestBound} " +
                "raw=${read.rawObjective} stats=${read.actualStats} wall=${read.wallTimeSec} " +
                "items=${read.selectedEquipmentIds.sorted()} subs=${read.selectedSublimationCopies.toSortedMap()}"
        )
        assertThat(read.status).isIn("OPTIMAL", "INFEASIBLE")
    }
}
