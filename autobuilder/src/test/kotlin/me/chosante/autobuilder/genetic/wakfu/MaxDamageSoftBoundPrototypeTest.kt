package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.runBlocking
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
 *   ./gradlew :autobuilder:test --tests '*MaxDamageSoftBoundPrototypeTest*' --rerun-tasks
 * ```
 */
class MaxDamageSoftBoundPrototypeTest {
    private fun mdParams(
        level: Int,
        targets: List<TargetStat>,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
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

    @Test
    fun `manual S4 prototype soundness lock on seeded pools`(): Unit =
        runBlocking {
            assumeTrue(System.getenv("WAKFU_S4_PROTO") == "1")

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

            // Unreachable-on-a-small-pool targets: the solve lands on the SOFT leg's penalized
            // objective, the exact value the prototype bounds.
            val p = mdParams(200, listOf(TargetStat(Characteristic.ACTION_POINT, 12), TargetStat(Characteristic.HP, 8000)))
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    randomSeed = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = 60.0
                )

            WakfuBuildSolver.warmUp()
            for ((label, pool) in fixtures) {
                var last: me.chosante.autobuilder.genetic.SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
                WakfuBuildSolver
                    .optimize(p, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations, tuning, hardConstraints = false)
                    .collect { last = it }
                val final = requireNotNull(last) { "$label: the soft solve emitted nothing" }
                val incumbent = requireNotNull(final.maxDamageObjective) { "$label: the soft leg must stamp maxDamageObjective" }
                val bound =
                    requireNotNull(
                        MaxDamageSoftBoundPrototype.bound(p, pool, WakfuBestBuildFinderAlgorithm.runes, WakfuBestBuildFinderAlgorithm.sublimations)
                    ) { "$label: the prototype bailed on a supported shape" }
                println(
                    "S4_PROTO_LOCK $label incumbent=$incumbent bound=${bound.foldedBound} optimal=${final.isOptimal} " +
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
        val level = 245
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val p = mdParams(level, frontierTargets())
        val bound =
            requireNotNull(
                MaxDamageSoftBoundPrototype.bound(
                    p,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.sublimations,
                    debug = true,
                    // QUICK tier: the block dim multiplies the main world ~9× (OOMs 8g); MM
                    // measured its refinement worth ~1.3pt — not needed for the tightness verdict.
                    blockGate = false
                )
            ) { "the prototype bailed on the canonical S4 shape" }
        val oracle = System.getenv("WAKFU_S4_ORACLE")?.toLongOrNull()
        println(
            "S4_PROTO_TIGHTNESS bound=${bound.foldedBound} core=${bound.coreBound} states=${bound.states} " +
                "wallMs=${bound.wallMs} binding=[${bound.bindingState}]" +
                (oracle?.let { " oracle=$it ratio=${"%.4f".format(bound.foldedBound.toDouble() / it)}" } ?: " oracle=UNSET")
        )
        if (oracle != null) {
            assertThat(bound.foldedBound)
                .describedAs("SOUNDNESS canary — the bound must cover the banked S4 incumbent")
                .isGreaterThanOrEqualTo(oracle)
        }
        // Attribution: price the big relaxations (UNSOUND arms — deltas only).
        for (arm in listOf("noCondSubs", "noSubs", "noSkills", "noRunes")) {
            val armBound =
                MaxDamageSoftBoundPrototype.bound(
                    p,
                    pool,
                    WakfuBestBuildFinderAlgorithm.runes,
                    WakfuBestBuildFinderAlgorithm.sublimations,
                    diag = setOf(arm),
                    blockGate = false
                )
            println("S4_PROTO_ATTRIB arm=$arm bound=${armBound?.foldedBound ?: "bail"}")
        }
    }
}
