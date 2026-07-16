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
 *   ./gradlew :autobuilder:test --tests '*MaxDamageSoftBoundPrototypeTest*' --rerun-tasks
 * ```
 */
class MaxDamageSoftBoundPrototypeTest {
    private fun ccSupportLambda(): Long = System.getenv("WAKFU_S4_CC_LAMBDA")?.toLongOrNull() ?: 0L

    private fun ccSupportBand(): Int = System.getenv("WAKFU_S4_CC_BAND")?.toIntOrNull() ?: 5

    private fun coupleSecondaryItemNegative(): Boolean = System.getenv("WAKFU_S4_COUPLE_NEG_ITEMS") == "1"

    private fun netSecondaryItemBudget(): Boolean = System.getenv("WAKFU_S4_NET_NEG_ITEMS") == "1"

    private fun exactNormalSubPacking(): Boolean = System.getenv("WAKFU_S4_EXACT_NORMAL_SUBS") == "1"

    private fun foldNegativeItemAp(): Boolean = System.getenv("WAKFU_S4_FOLD_ITEM_MAX_AP") == "1"

    private fun foldNegativeMaxMp(): Boolean = System.getenv("WAKFU_S4_FOLD_MAX_MP") == "1"

    private fun splitLightWeaponCondition(): Boolean = System.getenv("WAKFU_S4_SPLIT_LIGHT_WEAPON") == "1"

    private fun requireConditionalSub(): Boolean = System.getenv("WAKFU_S4_REQUIRE_CONDITIONAL") == "1"

    private fun diagnosticBasePlain(): Boolean = System.getenv("WAKFU_S4_DIAG_BASE_PLAIN") == "1"

    private fun timings(): Boolean = System.getenv("WAKFU_S4_TIMINGS") == "1"

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
                (1L..(System.getenv("WAKFU_S4_LOCK_SEEDS")?.toLongOrNull() ?: 3L)).map { seed ->
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
                        MaxDamageSoftBoundPrototype.bound(
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
                            requireConditionalSub = requireConditionalSub()
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
        val level = 245
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val p = mdParams(level, frontierTargets())
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
                    MaxDamageSoftBoundPrototype.bound(
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
                        diagnosticBasePlain = diagnostic
                    )
                ) { "the prototype bailed on the canonical S4 shape" }
            }
        val oracle = System.getenv("WAKFU_S4_ORACLE")?.toLongOrNull()
        if (bound != null) {
            println(
                "S4_PROTO_TIGHTNESS bound=${bound.foldedBound} core=${bound.coreBound} states=${bound.states} " +
                    "wallMs=${bound.wallMs} binding=[${bound.bindingState}]" +
                    (if (diagnostic) " diagnostic=BASE_PLAIN_UNSOUND" else "") +
                    (oracle?.let { " oracle=$it ratio=${"%.4f".format(bound.foldedBound.toDouble() / it)}" } ?: " oracle=UNSET")
            )
            if (oracle != null && !diagnostic) {
                assertThat(bound.foldedBound)
                    .describedAs("SOUNDNESS canary — the bound must cover the banked S4 incumbent")
                    .isGreaterThanOrEqualTo(oracle)
            }
        }
        if (gridProfile == "adaptive") {
            require(!diagnostic) { "adaptive is already a sound all-world promotion; do not combine it with plain diagnostic mode" }
            if (requireConditionalSub()) {
                require(oracle != null) {
                    "the conditional adaptive union needs WAKFU_S4_ORACLE: it caps the independently proven no-condition partition"
                }
            }
            val adaptiveT0 = System.nanoTime()
            var coarseStates = 0
            var refinedStates = 0
            var refinedCount = 0
            var finalBound = 0L
            try {
                MaxDamageSoftBoundPrototype.diStep = 10
                MaxDamageSoftBoundPrototype.hpStep = 1000
                MaxDamageSoftBoundPrototype.ccStep = 20
                val coarse =
                    requireNotNull(
                        MaxDamageSoftBoundPrototype.bound(
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
                            // Keep the cheap full-space upper here. Only worlds still above the
                            // no-condition oracle pay for the conditional-use partition below.
                            requireConditionalSub = false
                        )
                    ) { "adaptive coarse pass bailed" }
                coarseStates = coarse.states
                val pending = coarse.worldReads.sortedByDescending { it.foldedBound }
                require(pending.isNotEmpty()) { "adaptive coarse pass did not expose world reads" }
                if (foldNegativeMaxMp()) println("S4_PROTO_ADAPTIVE coarseMpDebit=RELAXED refineMpDebit=SIGNED")

                MaxDamageSoftBoundPrototype.diStep = 1
                var refinedBest = 0L
                while (refinedCount < pending.size && refinedBest < pending[refinedCount].foldedBound) {
                    val world = pending[refinedCount]
                    val refined =
                        requireNotNull(
                            MaxDamageSoftBoundPrototype.bound(
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
                                worldAssume = world.assume,
                                worldDropCaps = world.assume == null,
                                worldArm = world.arm
                            )
                        ) { "adaptive DI refinement bailed for assume=${world.assume?.name?.fr ?: "-"} arm=${world.arm}" }
                    val unionRefined =
                        if (requireConditionalSub()) {
                            maxOf(requireNotNull(oracle), refined.foldedBound)
                        } else {
                            refined.foldedBound
                        }
                    refinedBest = maxOf(refinedBest, unionRefined)
                    refinedStates += refined.states
                    refinedCount += 1
                    println(
                        "S4_PROTO_ADAPTIVE_REFINE assume=${world.assume?.name?.fr ?: "-"} arm=${world.arm} " +
                            "coarse=${world.foldedBound} refined=${refined.foldedBound} union=$unionRefined " +
                            "conditionalOnly=${requireConditionalSub()} states=${refined.states} wallMs=${refined.wallMs}"
                    )
                }
                val remainingUpper = pending.drop(refinedCount).maxOfOrNull { it.foldedBound } ?: 0L
                finalBound = maxOf(refinedBest, remainingUpper)
                val wallMs = (System.nanoTime() - adaptiveT0) / 1_000_000
                println(
                    "S4_PROTO_ADAPTIVE bound=$finalBound refinedWorlds=$refinedCount " +
                        "remainingCoarseUpper=$remainingUpper states=${coarseStates + refinedStates} wallMs=$wallMs" +
                        (oracle?.let { " oracle=$it ratio=${"%.4f".format(finalBound.toDouble() / it)}" } ?: " oracle=UNSET")
                )
                if (oracle != null) {
                    assertThat(finalBound)
                        .describedAs("adaptive mixed-grid certificate must cover the banked incumbent")
                        .isGreaterThanOrEqualTo(oracle)
                }
            } finally {
                MaxDamageSoftBoundPrototype.diStep = 1
                MaxDamageSoftBoundPrototype.hpStep = 500
                MaxDamageSoftBoundPrototype.ccStep = 10
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
                MaxDamageSoftBoundPrototype.diStep = di
                MaxDamageSoftBoundPrototype.hpStep = hp
                MaxDamageSoftBoundPrototype.ccStep = cc
                try {
                    val g =
                        MaxDamageSoftBoundPrototype.bound(
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
                            diagnosticBasePlain = diagnostic
                        )
                    println(
                        "S4_PROTO_GRID profile=${gridProfile ?: "legacy"} di=$di hp=$hp cc=$cc " +
                            "bound=${g?.foldedBound} states=${g?.states} wallMs=${g?.wallMs}" +
                            " binding=[${g?.bindingState}]" +
                            (if (diagnostic) " diagnostic=BASE_PLAIN_UNSOUND" else "") +
                            (oracle?.let { " ratio=${"%.4f".format((g?.foldedBound ?: 0).toDouble() / it)}" } ?: "")
                    )
                    if (oracle != null && g != null && !diagnostic) {
                        assertThat(g.foldedBound)
                            .describedAs("grid d$di/hp$hp/cc$cc must stay sound vs the banked incumbent")
                            .isGreaterThanOrEqualTo(oracle)
                    }
                } finally {
                    MaxDamageSoftBoundPrototype.diStep = 1
                    MaxDamageSoftBoundPrototype.hpStep = 500
                    MaxDamageSoftBoundPrototype.ccStep = 10
                }
            }
        }
        // Binding-path provenance (WAKFU_S4_PATH=1): coarse grid so the retained stage maps fit
        // the heap; the path names the option every stage contributed to the argmax state.
        if (System.getenv("WAKFU_S4_PATH") == "1") {
            val pathArm = System.getenv("WAKFU_S4_PATH_ARM")?.takeIf(String::isNotBlank)
            val pathLightArm = System.getenv("WAKFU_S4_PATH_LIGHT_ARM")?.takeIf(String::isNotBlank)
            MaxDamageSoftBoundPrototype.diStep = if (System.getenv("WAKFU_S4_PATH_DI") == "1") 1 else 10
            MaxDamageSoftBoundPrototype.ccStep = System.getenv("WAKFU_S4_PATH_CC")?.toIntOrNull() ?: 20
            MaxDamageSoftBoundPrototype.hpStep = System.getenv("WAKFU_S4_PATH_HP")?.toIntOrNull() ?: 1000
            try {
                val path =
                    MaxDamageSoftBoundPrototype.bound(
                        p,
                        pool,
                        WakfuBestBuildFinderAlgorithm.runes,
                        WakfuBestBuildFinderAlgorithm.sublimations,
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
                        lightWeaponArm = pathLightArm,
                        worldArm = pathArm
                    )
                println("S4_PROTO_PATH bound=${path?.foldedBound} binding=[${path?.bindingState}]")
                path?.bindingPath?.forEach { println("S4_PROTO_PATH_STEP $it") }
            } finally {
                MaxDamageSoftBoundPrototype.diStep = 1
                MaxDamageSoftBoundPrototype.ccStep = 10
                MaxDamageSoftBoundPrototype.hpStep = 500
            }
        }
        // Attribution: price the big relaxations (UNSOUND arms — deltas only). Opt-in: each arm
        // is a full DP (~12 min) — only worth re-running after a structural change.
        if (System.getenv("WAKFU_S4_ATTRIB") != "1") return
        for (arm in listOf("noCondSubs", "noSubs", "noSkills", "noRunes")) {
            val armBound =
                MaxDamageSoftBoundPrototype.bound(
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
                    diagnosticBasePlain = diagnostic
                )
            println("S4_PROTO_ATTRIB arm=$arm bound=${armBound?.foldedBound ?: "bail"}")
        }
    }

    @Test
    fun `manual S4 binding-arm CP cutoff`() {
        val cellMode = System.getenv("WAKFU_S4_CP_CELL") == "1"
        assumeTrue(System.getenv("WAKFU_S4_CP_CUTOFF") != null || cellMode)
        val level = 245
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
        val bindingArmSubs =
            if (System.getenv("WAKFU_S4_CP_NOCOND") == "1") {
                WakfuBestBuildFinderAlgorithm.sublimations.filter { it.condition == null }
            } else {
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
                mdParams(
                    level,
                    listOf(
                        TargetStat(Characteristic.MOVEMENT_POINT, 8),
                        TargetStat(Characteristic.CRITICAL_HIT, 100),
                        TargetStat(Characteristic.HP, 12000)
                    )
                ).copy(maxDamageApTarget = 15)
            } else {
                mdParams(level, frontierTargets())
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
                hardConstraints = cellMode,
                interleave = System.getenv("WAKFU_S4_CP_INTERLEAVE") == "1",
                logSearch = System.getenv("WAKFU_S4_CP_LOG") == "1"
            )
        println(
            "S4_BINDING_CP cell=$cellMode cutoff=${cutoff ?: "-"} keptSubs=${bindingArmSubs.size} " +
                "status=${profile.status} objective=${profile.objective} bound=${profile.bestBound} " +
                "wall=${profile.wallTimeSec} det=${profile.deterministicTime} branches=${profile.branches}"
        )
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
}
