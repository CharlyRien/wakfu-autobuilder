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
                        diagnosticBasePlain = diagnostic
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
                // Coarse HP=2000: the §9.2 grid screen measured HP bucketing bound-inert on this
                // shape (the binding state saturates HP through other relaxed paths) — halving the
                // HP buckets halves the coarse world sweep. Refinements below restore HP=1000.
                MaxDamageSoftCertificate.diStep = 10
                MaxDamageSoftCertificate.hpStep = 2000
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
                            requireConditionalSub = false
                        )
                    ) { "adaptive coarse pass bailed" }
                coarseStates = coarse.states
                val pending = coarse.worldReads.sortedByDescending { it.foldedBound }
                require(pending.isNotEmpty()) { "adaptive coarse pass did not expose world reads" }
                if (foldNegativeMaxMp()) println("S4_PROTO_ADAPTIVE coarseMpDebit=RELAXED refineMpDebit=SIGNED")

                // Refinements restore HP=1000 (the coarse pass ran HP=2000 for speed).
                MaxDamageSoftCertificate.hpStep = 1000

                fun refineAt(
                    world: MaxDamageSoftCertificate.WorldRead,
                    di: Int,
                ): MaxDamageSoftCertificate.Result {
                    MaxDamageSoftCertificate.diStep = di
                    return requireNotNull(
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
                            worldAssume = world.assume,
                            worldDropCaps = world.assume == null,
                            worldArm = world.arm
                        )
                    ) { "adaptive DI=$di refinement bailed for assume=${world.assume?.name?.fr ?: "-"} arm=${world.arm}" }
                }

                fun unionOf(bound: Long): Long = if (requireConditionalSub()) maxOf(requireNotNull(oracle), bound) else bound

                fun refineLog(
                    world: MaxDamageSoftCertificate.WorldRead,
                    refined: MaxDamageSoftCertificate.Result,
                    tierLabel: String,
                ) = println(
                    "S4_PROTO_ADAPTIVE_REFINE assume=${world.assume?.name?.fr ?: "-"} arm=${world.arm} " +
                        "coarse=${world.foldedBound} refined=${refined.foldedBound} union=${unionOf(refined.foldedBound)} " +
                        "tier=$tierLabel conditionalOnly=${requireConditionalSub()} states=${refined.states} wallMs=${refined.wallMs}"
                )

                // Grid CASCADE — every tier is independently sound, so the cheapest sufficient one
                // carries the world; escalation only affects tightness/wall, never soundness.
                // Refinements are SEQUENTIAL: a parallel DI10 wave over the contenders was measured
                // (2026-07-16) at 87-94 s per world vs 29 s solo — the DP is memory-bandwidth/
                // GC-bound, so world-level threads lose here exactly as in the coarse sweep.
                //
                // Conditional-union mode: the final bound is max(oracle, worlds), so a world only
                // needs the cheapest grid landing AT OR BELOW maxOf(oracle, refinedBest) — every
                // world cascades DI=10 → 4 → 1 and stops at the first sufficient tier. §9.18
                // measured the DI=1 top-world pass 3.8% UNDER the oracle: fine grids are almost
                // always waste in union mode. Non-conditional: the top world decides the final
                // bound and goes straight to DI=1; later worlds try DI=4 and escalate only while
                // above the running best.
                // In union mode the floor starts AT the oracle: a coarse world already at or below
                // it can never move the final bound (max(oracle, worlds)), so it skips refinement.
                var refinedBest = if (requireConditionalSub()) requireNotNull(oracle) else 0L
                while (refinedCount < pending.size && refinedBest < pending[refinedCount].foldedBound) {
                    val world = pending[refinedCount]
                    val tiers =
                        when {
                            requireConditionalSub() -> listOf(10, 4, 1)
                            refinedCount == 0 -> listOf(1)
                            else -> listOf(4, 1)
                        }
                    val floor = if (requireConditionalSub()) maxOf(refinedBest, requireNotNull(oracle)) else refinedBest
                    var tier = tiers.first()
                    var refined = refineAt(world, tier)
                    refinedStates += refined.states
                    for (next in tiers.drop(1)) {
                        if (refined.foldedBound <= floor) break
                        refineLog(world, refined, "DI$tier-escalate")
                        tier = next
                        refined = refineAt(world, next)
                        refinedStates += refined.states
                    }
                    refinedBest = maxOf(refinedBest, unionOf(refined.foldedBound))
                    refinedCount += 1
                    refineLog(world, refined, "DI$tier")
                }
                val remainingUpper = pending.drop(refinedCount).maxOfOrNull { it.foldedBound } ?: 0L
                finalBound = maxOf(refinedBest, remainingUpper)
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
                            diagnosticBasePlain = diagnostic
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
                val path =
                    MaxDamageSoftCertificate.bound(
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
        // WAKFU_S4_CP_FULLCOND=1 = the PARTITION-EXACTNESS probe: the FULL catalog (every
        // condition modeled) + `requireAnyConditionalSublimation` + the cutoff. INFEASIBLE (or a
        // proven bound below the cutoff) proves NO conditional build beats the no-condition
        // optimum ⇒ the full-model optimum equals it EXACTLY (badge +0%).
        val fullConditional = System.getenv("WAKFU_S4_CP_FULLCOND") == "1"
        val bindingArmSubs =
            when {
                fullConditional -> WakfuBestBuildFinderAlgorithm.sublimations
                System.getenv("WAKFU_S4_CP_NOCOND") == "1" ->
                    WakfuBestBuildFinderAlgorithm.sublimations.filter { it.condition == null }
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
                requireAnyConditionalSublimation = fullConditional,
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

    /**
     * §9.20 E2E gate for the PRODUCTION soft-leg proof: an incumbent at the (typed, re-proven)
     * no-condition optimum — which §9.18 proved IS the full-model optimum on this shape — must
     * come back **ProvenOptimal** through the real production entry
     * (`proveMaxDamageOptimality` → target-missing soft branch → `hybridUnionUpper`).
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
        val level = 245
        val pool =
            WakfuBestBuildFinderAlgorithm.equipments
                .filter { it.rarity <= Rarity.EPIC }
                .filter { it.level in 0..level || it.itemType == ItemType.PETS || it.itemType == ItemType.MOUNTS }
                .groupBy { it.itemType }
        val p = mdParams(level, frontierTargets())
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
        val proof = WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(p, result)
        println("S4_PROD_PROOF incumbent=$incumbent verdict=$proof")
        assertThat(proof)
            .describedAs("the production soft-leg proof must close S4 exactly (ProvenOptimal)")
            .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)
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
