package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.scaledWeight
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationRarity
import java.math.BigInteger
import kotlin.math.ceil

/**
 * PRODUCTION certificate (plan §9, promoted from the S4 prototype — history under
 * `MaxDamageSoftCertificateTest`, campaign log §9.10-§9.19): a target-aware sound upper bound
 * on the MAX-DAMAGE SOFT folded objective (the targets-unreachable fallback leg, which CP-SAT
 * alone never proves — the conditional-sublimation reification wall):
 *
 *   `damage(build) × power6(bucket(totalActual))`
 *
 * with `damage = ⌊throughput[AP] · ⌊D·Graw / PERHIT_DOWNSCALE⌋ · resFactor / FINAL_DOWNSCALE⌋`,
 * `D = 100 + clamp(DI)`, `Graw = 400·M + crit·(M + 5·K)` — the exact chain of
 * [perTurnDamageScore] / [perHitDamageScore], wrapped by the exact [applyConstraintPenalty]
 * power-6 fold (max-damage has NO overshoot tie-break, so no ×SCALE term).
 *
 * ARCHITECTURE: a clone of [MostMasteriesCertificate]'s stage DP (same packed key, same dims,
 * same target fold, same world split) with the CORE swapped. The value tracked per state is the
 * single scalar
 *
 *   `W = (400 + critCap)·M' + 5·critCap·K`      (M' = ΣMastery, the +100 base rides the seed)
 *
 * which upper-bounds Graw for EVERY build: `crit ≤ critCap` ⟹
 * `Graw = 400·M + crit·(M + 5K) ≤ (400+critCap)·M + 5·critCap·K = W`. On the S4 shape the CC
 * target equals the crit cap (100), so the binding (target-saturated) states sit AT the cap and
 * the fold is near-exact there.
 *
 * S4-2 (transposed from the MM certificate's increments 4/6/8 + review fixes A#1/A#2):
 *  - AT_MOST cap subs (AP_AT_MOST/AP_EXACT/CRIT_AT_MOST — all EPIC) → WORLD SPLIT: a main world
 *    without them + one world per cap sub assumed carried (LOW dim semantics on its capped stat,
 *    credits folded at collapse as constants).
 *  - Objective-capping subs (SECONDARY_MASTERIES_AT_MOST, CRITICAL_MASTERY_AT_MOST) → WORLD B per
 *    state: the carrier's W is clamped at a sound cap — wM·(100 + elemental reach + the EXACT
 *    secondary budget knapsack) + wK·critM-reach for Neutralité-family, wM·(mastery reach) + wK·t
 *    for Secret Critique — and its own credits (DI/CC/…) ride the state's fold.
 *  - BLOCK_AT_LEAST subs (Mesure) gate on an over-counted block dim (full tier only).
 *  - the MP→DI ramp (Poids Plume) defers to collapse at the state's own MP (ramp bit); the
 *    MAX_MP−1 rider (Armure lourde) pays the mpMinus bit.
 *  - chunked parallel stage apply (bit-identical max-merge, ported from the MM certificate).
 *
 * Remaining OVER-counts (sound):
 *  - Elemental Concentration's +DI credited penalty-free; conversions credited additively at
 *    percent·reachableMax(from) without debiting the source.
 *  - assume-CC worlds keep the critCap weights (a real carrier's crit ≤ threshold would allow
 *    tighter weights — banked as a follow-up seam).
 *
 * Bails (null): multi-element/boss scenarios, survivability floor, AP-pinned probes, forced
 * items/runes/subs/passives, a required target outside {AP, MP, CC, HP}.
 */
internal object MaxDamageSoftCertificate {
    private val SUPPORTED_TARGETS =
        setOf(
            Characteristic.ACTION_POINT,
            Characteristic.MOVEMENT_POINT,
            Characteristic.CRITICAL_HIT,
            Characteristic.HP
        )

    private val logger = KotlinLogging.logger {}

    // Same grid as the MM certificate's default (coarse) profile. MUTABLE (harness only): the
    // provenance pass retains every stage map, so it needs a coarser grid to fit the test heap.
    @Volatile
    var ccStep = 10

    @Volatile
    var hpStep = 500

    @Volatile
    var diStep = 1

    private const val BLOCK_STEP = 5

    class WorldRead(
        val assume: Sublimation?,
        val arm: String,
        val foldedBound: Long,
        val coreBound: Long,
        val states: Int,
        val wallMs: Long,
    )

    class Result(
        val foldedBound: Long,
        val coreBound: Long,
        val states: Int,
        val wallMs: Long,
        val bindingState: String = "",
        // Instrument only ([bound] provenance=true): the reconstructed binding PATH.
        val bindingPath: List<String> = emptyList(),
        // Outer-orchestrator reads used by the adaptive promotion harness. Empty on a single
        // recursive world call.
        val worldReads: List<WorldRead> = emptyList(),
    )

    /** One stage option: weighted-Graw value + per-axis deltas (positive parts only). */
    private data class Opt(
        val w: Long,
        val d: Int,
        val ap: Int = 0,
        val mp: Int = 0,
        val cc: Int = 0,
        val hp: Int = 0,
        val hpPct: Int = 0,
        val epic: Boolean = false,
        val relic: Boolean = false,
        val requiresEpicItem: Boolean = false,
        val requiresRelicItem: Boolean = false,
        val mpCapMinus: Int = 0,
        val block: Int = 0,
        val requiresBlockAtLeast: Int = 0,
        // ASSUME-world LOW semantics (signed, floor/raw) — see the MM certificate's A#1 fix.
        val apLow: Int = 0,
        val ccLowRaw: Int = 0,
        val ramp: Boolean = false,
        // Value-side partition marker: at least one selected sublimation bears a condition.
        // It is dormant through the equipment prefix and starts splitting only at sub stages.
        val conditional: Boolean = false,
        // Provenance identity (instrument only — "" in normal runs, so distinct()/dominance
        // semantics are untouched there).
        val src: String = "",
    ) {
        // src deliberately ignored: provenance never changes what an option contributes.
        fun dominates(o: Opt): Boolean =
            w >= o.w &&
                d >= o.d &&
                ap >= o.ap &&
                mp >= o.mp &&
                cc >= o.cc &&
                hp >= o.hp &&
                hpPct >= o.hpPct &&
                epic == o.epic &&
                relic == o.relic &&
                requiresEpicItem == o.requiresEpicItem &&
                requiresRelicItem == o.requiresRelicItem &&
                mpCapMinus == o.mpCapMinus &&
                ramp == o.ramp &&
                conditional == o.conditional &&
                block >= o.block &&
                requiresBlockAtLeast == o.requiresBlockAtLeast &&
                apLow <= o.apLow &&
                ccLowRaw <= o.ccLowRaw
    }

    private fun prune(options: List<Opt>): List<Opt> {
        val distinct = options.distinct()
        return distinct.filter { o -> distinct.none { other -> other !== o && other.dominates(o) && !o.dominates(other) } }
    }

    private class Geometry(
        val apCap: Int,
        val mpCap: Int,
        val ccBucketCap: Int,
        val hpBucketCap: Int,
        val diBucketCap: Int,
        val blockBucketCap: Int = 0,
        val assumeApThreshold: Int = -1,
        val assumeCcThresholdRaw: Int = -1,
    ) {
        /**
         * False when a bucket cap overflows its packed-key field (an extreme request/grid combo,
         * e.g. a huge HP target on a fine step). Callers must BAIL (return null) — withholding the
         * badge is always sound; throwing would crash a production proof.
         */
        val fitsPackedKey: Boolean =
            hpBucketCap <= 0x1FF && ccBucketCap <= 0x7F && diBucketCap <= 0x1FFF && blockBucketCap <= 0xF

        // Packed key: conditional(1b @49) block(4b @45) ramp(1b @44) mpMinus(1b @43) d(13b @28)
        //             ap(5b @23) mp(5b @18) cc(7b @11) hp(9b @2) e(1b @1) r(1b @0)
        fun key(
            d: Int,
            ap: Int,
            mp: Int,
            cc: Int,
            hp: Int,
            e: Int,
            r: Int,
            mpMinus: Int = 0,
            ramp: Int = 0,
            block: Int = 0,
            conditional: Int = 0,
        ): Long =
            (conditional.toLong() shl 49) or (block.toLong() shl 45) or (ramp.toLong() shl 44) or (mpMinus.toLong() shl 43) or
                (d.toLong() shl 28) or (ap.toLong() shl 23) or (mp.toLong() shl 18) or
                (cc.toLong() shl 11) or (hp.toLong() shl 2) or (e.toLong() shl 1) or r.toLong()

        fun d(k: Long): Int = ((k shr 28) and 0x1FFF).toInt()

        fun ap(k: Long): Int = ((k shr 23) and 0x1F).toInt()

        fun mp(k: Long): Int = ((k shr 18) and 0x1F).toInt()

        fun cc(k: Long): Int = ((k shr 11) and 0x7F).toInt()

        fun hp(k: Long): Int = ((k shr 2) and 0x1FF).toInt()

        fun e(k: Long): Int = ((k shr 1) and 1L).toInt()

        fun r(k: Long): Int = (k and 1L).toInt()

        fun mpMinus(k: Long): Int = ((k shr 43) and 1L).toInt()

        fun ramp(k: Long): Int = ((k shr 44) and 1L).toInt()

        fun block(k: Long): Int = ((k shr 45) and 0xF).toInt()

        fun conditional(k: Long): Int = ((k shr 49) and 1L).toInt()

        fun mpCapOf(mpMinus: Int): Int = (mpCap - mpMinus).coerceAtLeast(0)

        fun withMp(
            k: Long,
            newMp: Int,
        ): Long = key(d(k), ap(k), newMp, cc(k), hp(k), e(k), r(k), mpMinus(k), ramp(k), block(k), conditional(k))
    }

    private fun ceilDiv(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b

    private fun Geometry.applyOne(
        k: Long,
        o: Opt,
    ): Long? {
        val e = e(k)
        val r = r(k)
        if (o.epic && e == 1) return null
        if (o.relic && r == 1) return null
        if (o.requiresEpicItem && e == 0) return null
        if (o.requiresRelicItem && r == 0) return null
        val newMpMinus = (mpMinus(k) + o.mpCapMinus).coerceAtMost(1)
        val newAp =
            if (assumeApThreshold >= 0) {
                (ap(k) + o.apLow).coerceIn(0, (assumeApThreshold + 1).coerceAtMost(apCap))
            } else {
                (ap(k) + o.ap).coerceIn(0, apCap)
            }
        val newCcBuckets =
            if (assumeCcThresholdRaw >= 0) {
                (cc(k) + o.ccLowRaw).coerceIn(0, (assumeCcThresholdRaw + 1).coerceAtMost(ccBucketCap))
            } else {
                (cc(k) + ceilDiv(o.cc, ccStep)).coerceAtMost(ccBucketCap)
            }
        if (o.requiresBlockAtLeast > 0 && block(k) * BLOCK_STEP < o.requiresBlockAtLeast) return null
        val mpEff = mpCapOf(newMpMinus)
        val flatHpBuckets = hp(k) + ceilDiv(o.hp, hpStep)
        val hpBuckets = if (o.hpPct > 0) ceilDiv(flatHpBuckets * (100 + o.hpPct), 100) else flatHpBuckets
        return key(
            (d(k) + ceilDiv(o.d, diStep)).coerceAtMost(diBucketCap),
            newAp,
            (mp(k) + o.mp).coerceIn(0, mpEff),
            newCcBuckets,
            hpBuckets.coerceAtMost(hpBucketCap),
            if (o.epic) 1 else e,
            if (o.relic) 1 else r,
            newMpMinus,
            if (o.ramp) 1 else ramp(k),
            (block(k) + ceilDiv(o.block, BLOCK_STEP)).coerceAtMost(blockBucketCap),
            if (o.conditional) 1 else conditional(k)
        )
    }

    /** Transitions below this stay single-threaded (ported from the MM certificate). */
    private const val PARALLEL_APPLY_MIN_TRANSITIONS = 4_000_000L

    private fun Geometry.applySequential(
        entries: List<Map.Entry<Long, Long>>,
        options: List<Opt>,
        expectedSize: Int,
        ccSupportLambda: Long,
    ): HashMap<Long, Long> {
        val next = HashMap<Long, Long>(expectedSize)
        for ((k, wv) in entries) {
            for (o in options) {
                val nk = applyOne(k, o) ?: continue
                val nw = wv + o.w + ccSupportLambda * o.cc.coerceAtLeast(0)
                val cur = next[nk]
                if (cur == null || nw > cur) next[nk] = nw
            }
        }
        return next
    }

    /** Chunked parallel stage advance — bit-identical max-merge (MM certificate pattern). */
    private fun Geometry.apply(
        states: HashMap<Long, Long>,
        options: List<Opt>,
        ccSupportLambda: Long,
    ): HashMap<Long, Long> {
        val transitions = states.size.toLong() * options.size
        val workers = Runtime.getRuntime().availableProcessors() - 1
        if (transitions < PARALLEL_APPLY_MIN_TRANSITIONS || workers < 2) {
            return applySequential(states.entries.toList(), options, states.size * 2, ccSupportLambda)
        }
        val entries = states.entries.toList()
        val chunkCount = minOf(workers, 8)
        val chunkSize = (entries.size + chunkCount - 1) / chunkCount
        val locals =
            (0 until chunkCount)
                .toList()
                .parallelStream()
                .map { c ->
                    val from = c * chunkSize
                    val to = minOf(entries.size, from + chunkSize)
                    if (from >= to) {
                        HashMap()
                    } else {
                        applySequential(entries.subList(from, to), options, (to - from) * 2, ccSupportLambda)
                    }
                }.collect(
                    java.util.stream.Collectors
                        .toList()
                )
        val merged = locals.maxByOrNull { it.size } ?: HashMap()
        for (local in locals) {
            if (local === merged) continue
            for ((k, wv) in local) {
                val cur = merged[k]
                if (cur == null || wv > cur) merged[k] = wv
            }
        }
        return merged
    }

    fun bound(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        debug: Boolean = false,
        // Attribution seams (UNSOUND as a bound — pricing only): "noCondSubs", "noSubs",
        // "noSkills", "noRunes".
        diag: Set<String> = emptySet(),
        // Two-tier: `false` skips the block dim — the QUICK tier. Both tiers independently sound.
        blockGate: Boolean = true,
        // Instrument (attribution): retain per-stage states + options and reconstruct the binding
        // PATH backward. Costs memory (all stage maps retained) — run on a coarse grid.
        provenance: Boolean = false,
        shouldContinue: () -> Boolean = { true },
        // Test-side support-function seam. For λ>0 the DP value is max(W + λ·positiveCC)
        // per abstract key; collapse partitions the possible actual-CC range into bands and
        // combines the support-derived W ceiling at each band low with the target fold at its high.
        // Every rectangle is an over-count, so every λ>=0 independently yields a sound bound.
        ccSupportLambda: Long = 0L,
        ccSupportBand: Int = 5,
        // Test-side secZero tightening: price each item's negative-secondary magnitude on that
        // same option (`W0 + wMastery*Nitem`) instead of adding the best legal N layout as an
        // independent fold constant. Non-item negative sources stay independently over-credited.
        coupleSecondaryItemNegative: Boolean = false,
        // Stronger form: `Pscenario + Pother - N <= t` gives
        // `Pscenario <= t + N - Pother`; debit each item's positive non-scenario secondary lines.
        netSecondaryItemBudget: Boolean = false,
        // Keep DI signed and CC exact inside the shared 10-normal-sub knapsack, then round each
        // axis only once when the packed option enters the main DP. This pays useful-stat riders
        // such as Vélocité II's -10 DI and avoids inflating every +3 CC copy to a full bucket.
        exactNormalSubPacking: Boolean = false,
        // Fold equipment MAX_ACTION_POINT debits onto AP in non-AP-assume worlds, matching
        // Equipment.valueFor. Every debit-capable single slot is staged first, so the AP upper
        // clamp cannot discard positive headroom before a later -1. AP-assume worlds keep the old
        // optimistic read until their threshold-sentinel state gains equivalent headroom.
        foldNegativeItemAp: Boolean = false,
        // Fold MAX_MOVEMENT_POINT debits as signed MP in the existing axis. The axis gains raw
        // headroom before its target fold, so later debits cannot lose earlier positive overflow.
        foldNegativeMaxMp: Boolean = false,
        // Split the NO_OFFHAND_OR_TWO_HANDED condition into two value-side worlds rather than a
        // key bit: no carrier sub, or a weapon pool eligible for the carrier.
        splitLightWeaponCondition: Boolean = false,
        // Certificate partition seam: retain only builds selecting at least one condition-bearing
        // sublimation. The marker is added at sub stages, so the expensive equipment prefix is not
        // duplicated. Combined with an independent exact no-condition optimum, this covers the
        // full feasible set as a two-way union.
        requireConditionalSub: Boolean = false,
        // Diagnostic-only screen: execute only the base world on the plain arm. This is NOT a
        // certificate because it omits every assumed-cap world and objective-capping arm; it is
        // solely a fast ranking oracle for ideas whose current binding path is base/plain.
        diagnosticBasePlain: Boolean = false,
        // INTERNAL world-split recursion — never set by callers (MM certificate A#1 pattern).
        worldAssume: Sublimation? = null,
        worldDropCaps: Boolean = false,
        // INTERNAL structural-condition recursion.
        lightWeaponArm: String? = null,
        // WEIGHT ARM (§9.6, replaces the never-binding worldB fold): null = orchestrate. Every
        // real build is covered by ≥1 (world × arm):
        //  - "plain": objective-capping subs excluded (builds carrying none);
        //  - "secZero": secondary weights zeroed + wM·secondaryBudgetCap(t) fold constant;
        //    ALL SECONDARY_MASTERIES_AT_MOST subs staged (they coexist — NORMAL rarity) and the
        //    CRITM cappers staged CAP-IGNORED (a SC+Neutralité build lands here: its real K ≤ the
        //    arm's unconstrained K — over-count, sound);
        //  - "critZero": wK zeroed + wK·(t + own grants) constant; CRITM cappers staged,
        //    sec-cappers excluded (their builds live in secZero).
        worldArm: String? = null,
    ): Result? {
        val t0 = System.nanoTime()
        val wantSrc = provenance
        require(ccSupportLambda >= 0L)
        require(ccSupportBand > 0)
        require(!netSecondaryItemBudget || coupleSecondaryItemNegative)
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return null
        val scenario = params.damageScenario
        if (scenario.survivabilityFloor) return null
        if (params.maxDamageApTarget != null) return null
        if (params.maxDamageMpPin != null) return null
        val candidates = scenario.candidateElements()
        if (candidates.size != 1) return null
        if (params.forcedItems.isNotEmpty() ||
            params.forcedRunes.isNotEmpty() ||
            params.forcedRunesByItem.isNotEmpty() ||
            params.forcedSublimations.isNotEmpty() ||
            params.forcedPassives.isNotEmpty()
        ) {
            return null
        }
        val targets = params.targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        if (targets.any { it.characteristic !in SUPPORTED_TARGETS }) return null
        val targetByChar = targets.associateBy { it.characteristic }
        val level = params.character.level
        val diCap = DAMAGE_DI_MAX.toInt()
        val critCap = scenario.critCapPercent.toLong().coerceIn(0L, 100L)
        val wMastery = 400L + critCap
        val wCritMastery = 5L * critCap

        // The scenario's throughput table — the exact constant the solver looks up by AP.
        val (element, resistance) = candidates.single()
        val spells =
            SpellCatalog.damageSpells(params.character.clazz).filter {
                it.element ==
                    me.chosante.common.SpellElement
                        .valueOf(element.name)
            }
        val table = SpellRotationOptimizer.baseThroughputTable(spells, MAX_ROTATION_AP.toInt(), level)
        if (table.all { it == 0L }) {
            // No playable spell in the scenario element: the solver's objective is the constant 0.
            return Result(0L, 0L, 0, (System.nanoTime() - t0) / 1_000_000)
        }
        val clampedTable = LongArray(table.size) { table[it].coerceAtMost(PER_TURN_THROUGHPUT_MAX) }
        val resFactor = (100L - resistance).coerceIn(RES_FACTOR_MIN, RES_FACTOR_MAX)

        // The masteries feeding M — mirror scenarioMasteryStats + the random-element full-value
        // fold of computeDamagePreMasteryTerms.
        val masteryStats = scenarioMasteryStats(scenario).distinct().toSet()
        val randomStats = WakfuBuildSolver.MASTERY_RANDOM_BY_COUNT.map { it.first }.toSet()

        fun cap(char: Characteristic): Int = targetByChar[char]?.target ?: 0

        // AT_MOST cap subs → world split (MM certificate A#1).
        fun capStatOf(sub: Sublimation): Characteristic? =
            when (sub.condition?.type) {
                SublimationConditionType.AP_AT_MOST, SublimationConditionType.AP_EXACT -> Characteristic.ACTION_POINT
                SublimationConditionType.CRIT_AT_MOST -> Characteristic.CRITICAL_HIT
                else -> null
            }

        val capSubs =
            if (params.useSublimations && "noSubs" !in diag && "noCondSubs" !in diag) {
                sublimations.filter { it.solverChoosable && capStatOf(it) != null }
            } else {
                emptyList()
            }
        if (capSubs.size > 6) return null

        fun capsObjectiveType(sub: Sublimation): SublimationConditionType? =
            sub.condition?.type?.takeIf {
                it == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST || it == SublimationConditionType.CRITICAL_MASTERY_AT_MOST
            }

        val objCapSubs =
            if (params.useSublimations && "noSubs" !in diag && "noCondSubs" !in diag) {
                sublimations.filter { it.solverChoosable && capsObjectiveType(it) != null }
            } else {
                emptyList()
            }
        if (foldNegativeMaxMp &&
            sublimations.any { sub ->
                sub.solverChoosable &&
                    sub.rarity != SublimationRarity.NORMAL &&
                    sub.effects
                        .filterIsInstance<SublimationEffect.StatEffect>()
                        .any {
                            it.characteristic == Characteristic.MAX_MOVEMENT_POINT &&
                                it.magnitudeAtLevel(level) < 0
                        }
            }
        ) {
            return null
        }
        val futureNormalMpDebit =
            if (foldNegativeMaxMp && exactNormalSubPacking) {
                sublimations
                    .filter { it.solverChoosable && it.rarity == SublimationRarity.NORMAL }
                    .sumOf { sub ->
                        sub.effects
                            .filterIsInstance<SublimationEffect.StatEffect>()
                            .filter { it.characteristic == Characteristic.MAX_MOVEMENT_POINT }
                            .sumOf { (-minOf(it.magnitudeAtLevel(level), 0)).coerceAtLeast(0) } * sub.maxCopies.coerceAtLeast(1)
                    }
            } else {
                0
            }
        val futureItemMpDebit =
            if (foldNegativeMaxMp) {
                fun maxDebit(type: ItemType): Int =
                    pool[type].orEmpty().maxOfOrNull {
                        (-minOf(it.characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0, 0)).coerceAtLeast(0)
                    } ?: 0

                val ringDebits =
                    pool[ItemType.RING]
                        .orEmpty()
                        .map { (-minOf(it.characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0, 0)).coerceAtLeast(0) }
                        .sortedDescending()
                val weaponDebit =
                    maxOf(
                        maxDebit(ItemType.TWO_HANDED_WEAPONS),
                        maxDebit(ItemType.ONE_HANDED_WEAPONS) + maxDebit(ItemType.OFF_HAND_WEAPONS)
                    )
                pool.keys
                    .filter {
                        it !in
                            setOf(
                                ItemType.RING,
                                ItemType.ONE_HANDED_WEAPONS,
                                ItemType.TWO_HANDED_WEAPONS,
                                ItemType.OFF_HAND_WEAPONS
                            )
                    }.sumOf(::maxDebit) +
                    (ringDebits.getOrNull(0) ?: 0) +
                    (ringDebits.getOrNull(1) ?: 0) +
                    weaponDebit
            } else {
                0
            }
        val hasSecCappers = objCapSubs.any { capsObjectiveType(it) == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST }
        val hasCritMCappers = objCapSubs.any { capsObjectiveType(it) == SublimationConditionType.CRITICAL_MASTERY_AT_MOST }
        if (worldArm == null) {
            data class WorldSpec(
                val assume: Sublimation?,
                val arm: String,
            )

            val arms =
                buildList {
                    add("plain")
                    if (hasSecCappers) add("secZero")
                    if (hasCritMCappers) add("critZero")
                }
            val specs =
                if (diagnosticBasePlain) {
                    listOf(WorldSpec(null, "plain"))
                } else {
                    (listOf<Sublimation?>(null) + capSubs).flatMap { assume ->
                        arms.map { arm -> WorldSpec(assume, arm) }
                    }
                }
            // Provenance is diagnostic only. Retaining every stage map in every world/arm made a
            // coarse path run exceed ten minutes. First price all worlds normally, then replay only
            // the winning world/arm with retention; the returned bound is still the max of the same
            // first-pass partition and the replay cannot influence which world wins.
            //
            // Worlds are priced SEQUENTIALLY on purpose. A 4-thread world pool was measured
            // (2026-07-16, 10-core M-series, 8 GiB heap): every world slowed 4-6× (Mesure III
            // plain 20 s → 125 s wall) — the DP is memory-bandwidth/GC-bound, matching the MM
            // certificate's "world-level parallel is SLOWER" verdict. Do not retry world threads;
            // the only concurrency that pays here is the CP-SAT oracle solving alongside this
            // single-threaded sweep (harness-side).
            //
            // Assume-worlds (one cap sub forced under its threshold) price on a 2× COARSER grid:
            // coarser buckets only merge states under a max, so each bound stays a sound upper —
            // and every measured assume-world lands ~45% under the main worlds, so the extra
            // looseness cannot promote one into the refinement set.
            val savedDi = diStep
            val savedHp = hpStep
            val savedCc = ccStep
            val worlds: List<Pair<WorldSpec, Result?>> =
                try {
                    specs.map { spec ->
                        if (spec.assume == null) {
                            diStep = savedDi
                            hpStep = savedHp
                            ccStep = savedCc
                        } else {
                            diStep = savedDi * 2
                            hpStep = savedHp * 2
                            ccStep = savedCc * 2
                        }
                        spec to
                            bound(
                                params,
                                pool,
                                runes,
                                sublimations,
                                debug,
                                diag,
                                blockGate = if (spec.assume == null) blockGate else false,
                                provenance = false,
                                shouldContinue = shouldContinue,
                                ccSupportLambda = ccSupportLambda,
                                ccSupportBand = ccSupportBand,
                                coupleSecondaryItemNegative = coupleSecondaryItemNegative,
                                netSecondaryItemBudget = netSecondaryItemBudget,
                                exactNormalSubPacking = exactNormalSubPacking,
                                foldNegativeItemAp = foldNegativeItemAp,
                                foldNegativeMaxMp = foldNegativeMaxMp,
                                splitLightWeaponCondition = splitLightWeaponCondition,
                                requireConditionalSub = requireConditionalSub,
                                diagnosticBasePlain = diagnosticBasePlain,
                                worldAssume = spec.assume,
                                worldDropCaps = spec.assume == null,
                                worldArm = spec.arm
                            )
                    }
                } finally {
                    diStep = savedDi
                    hpStep = savedHp
                    ccStep = savedCc
                }
            if (debug) {
                worlds.forEach { (spec, result) ->
                    println(
                        "S4_PROTO_WORLD assume=${spec.assume?.name?.fr ?: "-"} arm=${spec.arm} " +
                            "bound=${result?.foldedBound ?: "bail"} states=${result?.states ?: 0} wallMs=${result?.wallMs ?: 0}"
                    )
                }
            }
            if (worlds.any { it.second == null }) return null
            val (bestSpec, best) = worlds.maxByOrNull { it.second?.foldedBound ?: Long.MIN_VALUE } ?: return null
            best ?: return null
            val explained =
                if (provenance) {
                    bound(
                        params,
                        pool,
                        runes,
                        sublimations,
                        debug,
                        diag,
                        blockGate = if (bestSpec.assume == null) blockGate else false,
                        provenance = true,
                        shouldContinue = shouldContinue,
                        ccSupportLambda = ccSupportLambda,
                        ccSupportBand = ccSupportBand,
                        coupleSecondaryItemNegative = coupleSecondaryItemNegative,
                        netSecondaryItemBudget = netSecondaryItemBudget,
                        exactNormalSubPacking = exactNormalSubPacking,
                        foldNegativeItemAp = foldNegativeItemAp,
                        foldNegativeMaxMp = foldNegativeMaxMp,
                        splitLightWeaponCondition = splitLightWeaponCondition,
                        requireConditionalSub = requireConditionalSub,
                        diagnosticBasePlain = diagnosticBasePlain,
                        worldAssume = bestSpec.assume,
                        worldDropCaps = bestSpec.assume == null,
                        worldArm = bestSpec.arm
                    ) ?: return null
                } else {
                    best
                }
            val priced = worlds.map { requireNotNull(it.second) }
            val worldReads =
                worlds.map { (spec, result) ->
                    val read = requireNotNull(result)
                    WorldRead(spec.assume, spec.arm, read.foldedBound, read.coreBound, read.states, read.wallMs)
                }
            return Result(
                best.foldedBound,
                priced.maxOf { it.coreBound },
                priced.sumOf { it.states },
                (System.nanoTime() - t0) / 1_000_000,
                explained.bindingState,
                explained.bindingPath,
                worldReads
            )
        }
        if (splitLightWeaponCondition && lightWeaponArm == null) {
            fun price(
                arm: String,
                keepPath: Boolean,
            ): Result? =
                bound(
                    params,
                    pool,
                    runes,
                    sublimations,
                    debug,
                    diag,
                    blockGate,
                    keepPath,
                    shouldContinue,
                    ccSupportLambda,
                    ccSupportBand,
                    coupleSecondaryItemNegative,
                    netSecondaryItemBudget,
                    exactNormalSubPacking,
                    foldNegativeItemAp,
                    foldNegativeMaxMp,
                    splitLightWeaponCondition,
                    requireConditionalSub,
                    diagnosticBasePlain,
                    worldAssume,
                    worldDropCaps,
                    arm,
                    worldArm
                )

            val priced = listOf("noExpert", "expertEligible").map { arm -> arm to price(arm, false) }
            if (priced.any { it.second == null }) return null
            val (bestArm, bestRead) = priced.maxBy { requireNotNull(it.second).foldedBound }
            val best = requireNotNull(bestRead)
            val explained = if (provenance) price(bestArm, true) ?: return null else best
            return Result(
                best.foldedBound,
                priced.maxOf { requireNotNull(it.second).coreBound },
                priced.sumOf { requireNotNull(it.second).states },
                (System.nanoTime() - t0) / 1_000_000,
                explained.bindingState + " lightArm=$bestArm",
                explained.bindingPath
            )
        }
        val assumeStat = worldAssume?.let { capStatOf(it) }
        val assumeThreshold = worldAssume?.condition?.value ?: -1
        val armZeroSecondary = worldArm == "secZero"
        val armZeroCritM = worldArm == "critZero"

        val blockAtLeastMax =
            if (blockGate && params.useSublimations && "noSubs" !in diag && "noCondSubs" !in diag) {
                sublimations
                    .filter { it.solverChoosable && it.condition?.type == SublimationConditionType.BLOCK_AT_LEAST }
                    .maxOfOrNull { it.condition?.value ?: 0 } ?: 0
            } else {
                0
            }

        // WALL seam (§9.6): the MP→DI ramp (Poids Plume) is priced at reachableMax like every
        // other ramp instead of a deferred state BIT — the S4 binding state saturates its MP
        // target anyway (contribution(mpCap) = the same 24), and the bit doubled the state space.
        // The dormant ramp machinery (key bit, collapse read) stays for a future re-fine.
        val mpDiRamp: Pair<Sublimation, SublimationEffect.PerStatStep>? = null

        val geo =
            Geometry(
                apCap =
                    (
                        if (assumeStat == Characteristic.ACTION_POINT) {
                            assumeThreshold + 1
                        } else {
                            // throughput[AP] grows with AP: track the full out-of-combat reach.
                            MAX_OUT_OF_COMBAT_AP.toInt()
                        }
                    ).coerceAtMost(MAX_OUT_OF_COMBAT_AP.toInt()),
                mpCap =
                    if (foldNegativeMaxMp && cap(Characteristic.MOVEMENT_POINT) > 0) {
                        // Raw signed reach; collapse still credits at most the requested MP.
                        // 5-bit headroom avoids upper saturation before later -1 riders.
                        0x1F
                    } else {
                        maxOf(
                            cap(Characteristic.MOVEMENT_POINT),
                            if (mpDiRamp != null) MAX_OUT_OF_COMBAT_MP.toInt() else 0
                        ).coerceAtMost(MAX_OUT_OF_COMBAT_MP.toInt())
                    },
                ccBucketCap =
                    if (assumeStat == Characteristic.CRITICAL_HIT) {
                        (assumeThreshold + 1).coerceAtMost(0x7F)
                    } else {
                        // The CC dim feeds the target fold AND (headroom for) the crit factor.
                        ceilDiv(maxOf(cap(Characteristic.CRITICAL_HIT), critCap.toInt()), ccStep)
                    },
                hpBucketCap = ceilDiv(cap(Characteristic.HP), hpStep),
                diBucketCap = ceilDiv(diCap, diStep),
                blockBucketCap = ceilDiv(blockAtLeastMax, BLOCK_STEP),
                assumeApThreshold = if (assumeStat == Characteristic.ACTION_POINT) assumeThreshold else -1,
                assumeCcThresholdRaw = if (assumeStat == Characteristic.CRITICAL_HIT) assumeThreshold else -1
            )
        if (!geo.fitsPackedKey) return null

        fun statOf(
            e: Equipment,
            c: Characteristic,
        ): Int = maxOf(e.characteristics[c] ?: 0, 0)

        fun itemAp(e: Equipment): Int =
            statOf(e, Characteristic.ACTION_POINT) +
                if (foldNegativeItemAp && geo.assumeApThreshold < 0) {
                    minOf(e.characteristics[Characteristic.MAX_ACTION_POINT] ?: 0, 0)
                } else {
                    0
                }

        /**
         * Weighted-Graw value of one positive stat line. In a zeroed weight ARM the capped
         * component contributes nothing — its condition-derived cap rides the fold as a constant.
         */
        fun wOf(
            c: Characteristic,
            v: Int,
        ): Long =
            when {
                v <= 0 -> 0L
                c in masteryStats || c in randomStats ->
                    if (armZeroSecondary && c in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS) 0L else wMastery * v
                c == Characteristic.MASTERY_CRITICAL -> if (armZeroCritM) 0L else wCritMastery * v
                else -> 0L
            }

        // Rune axes: [weighted mastery, AP, MP, CC, HP]. Runes have no per-element shard, but the
        // generic elemental-mastery rune and the crit-mastery rune both feed W — the axis takes the
        // best WEIGHTED value among them.
        val runeAxesW: (ItemType, Int) -> Long = { type, lvl ->
            if (params.useRunes && "noRunes" !in diag) {
                runes.maxOfOrNull { wOf(it.characteristic, it.valueOn(type, lvl)) } ?: 0L
            } else {
                0L
            }
        }
        val runeAxesTarget: List<(ItemType, Int) -> Int> =
            listOf(
                Characteristic.ACTION_POINT,
                Characteristic.MOVEMENT_POINT,
                Characteristic.CRITICAL_HIT,
                Characteristic.HP
            ).map { axisChar ->
                val matching = runes.filter { it.characteristic == axisChar && axisChar in targetByChar }
                (
                    { type: ItemType, lvl: Int ->
                        if (params.useRunes && "noRunes" !in diag) matching.maxOfOrNull { it.valueOn(type, lvl) } ?: 0 else 0
                    }
                )
            }

        fun itemNegativeSecondary(e: Equipment): Long =
            e.characteristics.entries
                .filter { it.key in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS }
                .sumOf { (_, v) -> (-minOf(v, 0)).toLong() }

        fun itemOtherNegativeSecondary(e: Equipment): Long =
            e.characteristics.entries
                .filter {
                    it.key in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS &&
                        it.key !in masteryStats
                }.sumOf { (_, v) -> (-minOf(v, 0)).toLong() }

        fun itemOtherPositiveSecondary(e: Equipment): Long =
            e.characteristics.entries
                .filter {
                    it.key in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS &&
                        it.key !in masteryStats
                }.sumOf { (_, v) -> maxOf(v, 0).toLong() }

        fun itemOpts(e: Equipment): List<Opt> {
            val negativeSecondary = itemNegativeSecondary(e)
            val otherNegativeSecondary = itemOtherNegativeSecondary(e)
            val otherPositiveSecondary = itemOtherPositiveSecondary(e)
            val base =
                Opt(
                    w =
                        e.characteristics.entries.sumOf { (c, v) -> wOf(c, v) } +
                            if (armZeroSecondary && coupleSecondaryItemNegative) {
                                wMastery *
                                    if (netSecondaryItemBudget) {
                                        otherNegativeSecondary - otherPositiveSecondary
                                    } else {
                                        negativeSecondary
                                    }
                            } else {
                                0L
                            },
                    d = statOf(e, Characteristic.DAMAGE_INFLICTED),
                    ap = itemAp(e),
                    mp =
                        statOf(e, Characteristic.MOVEMENT_POINT) +
                            if (foldNegativeMaxMp) minOf(e.characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0, 0) else 0,
                    cc = statOf(e, Characteristic.CRITICAL_HIT),
                    hp = statOf(e, Characteristic.HP),
                    epic = e.rarity == me.chosante.common.Rarity.EPIC,
                    relic = e.rarity == me.chosante.common.Rarity.RELIC,
                    block = if (blockAtLeastMax > 0) statOf(e, Characteristic.BLOCK_PERCENTAGE) else 0,
                    apLow = if (geo.assumeApThreshold >= 0) itemAp(e) else 0,
                    ccLowRaw = if (geo.assumeCcThresholdRaw >= 0) (e.characteristics[Characteristic.CRITICAL_HIT] ?: 0) else 0,
                    src =
                        if (wantSrc) {
                            "${e.name.fr}[id=${e.equipmentId} lvl=${e.level} rarity=${e.rarity} " +
                                "secN=$negativeSecondary secNOther=$otherNegativeSecondary secOther=$otherPositiveSecondary]"
                        } else {
                            ""
                        }
                )
            val slots = if (params.useRunes) e.maxShardSlots else 0
            if (slots == 0) return listOf(base)
            val wPer = runeAxesW(e.itemType, e.level)
            val perAxis = runeAxesTarget.map { it(e.itemType, e.level) }
            val out = mutableListOf<Opt>()
            for (a0 in 0..slots) {
                for (a1 in 0..(slots - a0)) {
                    for (a2 in 0..(slots - a0 - a1)) {
                        for (a3 in 0..(slots - a0 - a1 - a2)) {
                            val a4 = slots - a0 - a1 - a2 - a3
                            out +=
                                base.copy(
                                    w = base.w + a0 * wPer,
                                    ap = base.ap + a1 * perAxis[0],
                                    mp = base.mp + a2 * perAxis[1],
                                    cc = base.cc + a3 * perAxis[2],
                                    hp = base.hp + a4 * perAxis[3],
                                    apLow = base.apLow + (if (geo.assumeApThreshold >= 0) a1 * perAxis[0] else 0),
                                    ccLowRaw = base.ccLowRaw + (if (geo.assumeCcThresholdRaw >= 0) a3 * perAxis[2] else 0),
                                    src =
                                        if (wantSrc) {
                                            base.src + "{runes=W:$a0 AP:$a1 MP:$a2 CC:$a3 HP:$a4}"
                                        } else {
                                            ""
                                        }
                                )
                        }
                    }
                }
            }
            return out.distinct()
        }

        val baseValues = params.character.baseCharacteristicValues
        // Seed: the +100 base of M rides here, plus the base sheet's own positive lines.
        val seedW =
            wMastery * 100L +
                baseValues.entries.sumOf { (c, v) -> wOf(c, v) }
        val seedSupport = seedW + ccSupportLambda * (baseValues[Characteristic.CRITICAL_HIT] ?: 0).coerceAtLeast(0)
        var states = HashMap<Long, Long>()
        val seedKey =
            geo.key(
                0,
                (baseValues[Characteristic.ACTION_POINT] ?: 0).coerceIn(0, geo.apCap),
                (baseValues[Characteristic.MOVEMENT_POINT] ?: 0).coerceIn(0, geo.mpCap),
                (
                    if (geo.assumeCcThresholdRaw >= 0) {
                        (baseValues[Characteristic.CRITICAL_HIT] ?: 0)
                    } else {
                        ceilDiv((baseValues[Characteristic.CRITICAL_HIT] ?: 0).coerceAtLeast(0), ccStep)
                    }
                ).coerceIn(0, geo.ccBucketCap),
                ceilDiv((baseValues[Characteristic.HP] ?: 0).coerceAtLeast(0), hpStep).coerceAtMost(geo.hpBucketCap),
                0,
                0,
                block = ceilDiv((baseValues[Characteristic.BLOCK_PERCENTAGE] ?: 0).coerceAtLeast(0), BLOCK_STEP).coerceAtMost(geo.blockBucketCap)
            )
        states[seedKey] = seedSupport

        var cancelled = false
        val stageLog = if (provenance) mutableListOf<Triple<String, HashMap<Long, Long>, List<Opt>>>() else null

        fun step(
            label: String,
            options: List<Opt>,
        ) {
            if (cancelled || !shouldContinue()) {
                cancelled = true
                states = HashMap()
                return
            }
            stageLog?.add(Triple(label, HashMap(states), options))
            val stageT0 = System.nanoTime()
            states = geo.apply(states, options, ccSupportLambda)
            if (debug) {
                println(
                    "S4_PROTO_STAGE $label states=${states.size} options=${options.size} " +
                        "wallMs=${(System.nanoTime() - stageT0) / 1_000_000}"
                )
            }
        }

        fun collapseMpHeadroom(
            label: String,
            retainedMp: Int = cap(Characteristic.MOVEMENT_POINT),
        ) {
            if (!foldNegativeMaxMp || provenance || cap(Characteristic.MOVEMENT_POINT) <= 0) return
            val collapsed = HashMap<Long, Long>(states.size)
            for ((k, wv) in states) {
                val nk = geo.withMp(k, minOf(geo.mp(k), retainedMp))
                val current = collapsed[nk]
                if (current == null || wv > current) collapsed[nk] = wv
            }
            states = collapsed
            if (debug) println("S4_PROTO_STAGE $label states=${states.size} options=0 wallMs=0")
        }

        fun combineOpts(
            a: Opt,
            b: Opt,
        ): Opt =
            Opt(
                a.w + b.w,
                a.d + b.d,
                a.ap + b.ap,
                a.mp + b.mp,
                a.cc + b.cc,
                a.hp + b.hp,
                a.hpPct + b.hpPct,
                epic = a.epic || b.epic,
                relic = a.relic || b.relic,
                block = a.block + b.block,
                requiresBlockAtLeast = maxOf(a.requiresBlockAtLeast, b.requiresBlockAtLeast),
                apLow = a.apLow + b.apLow,
                ccLowRaw = a.ccLowRaw + b.ccLowRaw,
                conditional = a.conditional || b.conditional,
                src =
                    if (a.src.isEmpty()) {
                        b.src
                    } else if (b.src.isEmpty()) {
                        a.src
                    } else {
                        a.src + "+" + b.src
                    }
            )

        // Rings: exact distinct-name pairs.
        run {
            val perRing = pool[ItemType.RING].orEmpty().map { it to prune(itemOpts(it)) }
            val options = mutableListOf(Opt(0L, 0))
            perRing.forEach { (_, opts) -> options += opts }
            for (i in perRing.indices) {
                for (j in i + 1 until perRing.size) {
                    val (ri, oi) = perRing[i]
                    val (rj, oj) = perRing[j]
                    if (ri.name.fr == rj.name.fr) continue
                    val merged = mutableListOf<Opt>()
                    for (a in oi) {
                        for (b in oj) {
                            if (a.epic && b.epic) continue
                            if (a.relic && b.relic) continue
                            merged += combineOpts(a, b)
                        }
                    }
                    options += prune(merged)
                }
            }
            step("rings", options)
        }
        // Weapons: 2H | 1H (+ optional off-hand) | off-hand | nothing.
        run {
            val options = mutableListOf(Opt(0L, 0))
            if (lightWeaponArm != "expertEligible") {
                options += pool[ItemType.TWO_HANDED_WEAPONS].orEmpty().flatMap { prune(itemOpts(it)) }
            }
            val oneH = pool[ItemType.ONE_HANDED_WEAPONS].orEmpty().map { prune(itemOpts(it)) }
            val off =
                if (lightWeaponArm == "expertEligible") {
                    emptyList()
                } else {
                    pool[ItemType.OFF_HAND_WEAPONS].orEmpty().map { prune(itemOpts(it)) }
                }
            oneH.forEach { options += it }
            off.forEach { options += it }
            for (oi in oneH) {
                for (oj in off) {
                    val merged = mutableListOf<Opt>()
                    for (a in oi) {
                        for (b in oj) {
                            if (a.epic && b.epic) continue
                            if (a.relic && b.relic) continue
                            merged += combineOpts(a, b)
                        }
                    }
                    options += prune(merged)
                }
            }
            if (foldNegativeItemAp &&
                pool
                    .filterKeys {
                        it !in
                            setOf(
                                ItemType.RING,
                                ItemType.ONE_HANDED_WEAPONS,
                                ItemType.TWO_HANDED_WEAPONS,
                                ItemType.OFF_HAND_WEAPONS
                            )
                    }.values
                    .flatten()
                    .any { itemAp(it) < 0 }
            ) {
                val prefixApUpper = states.keys.maxOf { geo.ap(it) } + options.maxOf { maxOf(it.ap, 0) }
                if (prefixApUpper > geo.apCap) {
                    // The signed AP fold needs weapon-before-debit headroom in the 5-bit AP dim.
                    // On an exotic shape without it, BAIL (sound: no badge) instead of throwing.
                    return null
                }
            }
            step("weapons", options)
        }
        val singleSlots =
            (
                pool.keys -
                    setOf(ItemType.RING, ItemType.ONE_HANDED_WEAPONS, ItemType.TWO_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS)
            ).sortedBy { slot ->
                if (pool[slot].orEmpty().any {
                        (foldNegativeItemAp && itemAp(it) < 0) ||
                            (foldNegativeMaxMp && (it.characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0) < 0)
                    }
                ) {
                    0
                } else {
                    1
                }
            }
        for (slot in singleSlots) {
            step(slot.name, listOf(Opt(0L, 0)) + pool[slot].orEmpty().flatMap { prune(itemOpts(it)) })
        }
        collapseMpHeadroom(
            "mp-fold-after-items",
            cap(Characteristic.MOVEMENT_POINT) + futureNormalMpDebit
        )

        // Sound layer-independent reachable max of a stat (percent skills, ramps, conversions,
        // world-B caps). Rings count top-2; runes included (MM review fix A#6).
        fun reachableMax(stat: Characteristic): Int {
            var v = baseValues[stat] ?: 0
            for ((slot, items) in pool) {
                val vals = items.map { maxOf(it.characteristics[stat] ?: 0, 0) }.sortedDescending()
                v += (vals.getOrNull(0) ?: 0) + (if (slot == ItemType.RING) (vals.getOrNull(1) ?: 0) else 0)
            }
            if (params.useRunes && "noRunes" !in diag) {
                val matching = runes.filter { it.characteristic == stat }
                if (matching.isNotEmpty()) {
                    for ((slot, items) in pool) {
                        val sockets = items.maxOfOrNull { it.maxShardSlots } ?: 0
                        val lvl = items.maxOfOrNull { it.level } ?: 0
                        val per = matching.maxOf { it.valueOn(slot, lvl) }
                        v += sockets * per * (if (slot == ItemType.RING) 2 else 1)
                    }
                }
            }
            val skills = params.character.characterSkills
            for (branch in listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major)) {
                v += branch
                    .getCharacteristics()
                    .flatMap { sk -> if (sk is me.chosante.common.skills.SkillCharacteristic.PairedCharacteristic) listOf(sk.first, sk.second) else listOf(sk) }
                    .filter { it.characteristic == stat && it.unitType == me.chosante.common.skills.UnitType.FIXED }
                    .maxOfOrNull { it.unitValue * minOf(branch.maxPointsToAssign, it.maxPointsAssignable) } ?: 0
            }
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                v += sub.effects
                    .filterIsInstance<SublimationEffect.StatEffect>()
                    .filter { it.characteristic == stat && WakfuBuildSolver.scenarioGateMatches(it.scenarioGate, params) }
                    .sumOf { maxOf(it.magnitudeAtLevel(level), 0) } * sub.maxCopies.coerceAtLeast(1)
            }
            return v
        }
        // Saturation is sound as long as the raw coordinate retains enough room for every
        // negative rider that can still follow it. Positive MP beyond that line is equivalent for
        // the target fold, so the much looser all-sources reachableMax is neither needed nor useful
        // here (it made tiny seeded pools bail merely because every catalogue sub was summed).
        if (foldNegativeMaxMp &&
            cap(Characteristic.MOVEMENT_POINT) > 0 &&
            cap(Characteristic.MOVEMENT_POINT) + futureItemMpDebit + futureNormalMpDebit > geo.mpCap
        ) {
            return null
        }

        // Sound SECONDARY-mastery cap for a Neutralité-family carrier (MM review fix A#2,
        // transposed): the EXACT budget knapsack — maximize Σ positive SCENARIO-relevant secondary
        // lines subject to the signed all-secondaries sum ≤ t, over every mastery source.
        fun secondaryBudgetCap(t: Long): Long {
            fun bucket(x: Long): Int = x.toInt()

            val secondaryObjective = masteryStats.filter { it in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS }.toSet()
            val pickLists = mutableListOf<List<Pair<Int, Long>>>()
            for ((slot, items) in pool) {
                val options =
                    items
                        .map { e ->
                            var p = 0L
                            var qn = 0L
                            for ((c, x) in e.characteristics) {
                                if (c !in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS) continue
                                if (c in secondaryObjective && x > 0) p += x
                                if (x < 0) qn += -x.toLong()
                            }
                            bucket(p - qn) to p
                        }.plus(0 to 0L)
                        .distinct()
                pickLists += options
                if (slot == ItemType.RING) pickLists += options
            }
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                var p = 0L
                var qn = 0L
                for (eff in sub.effects.filterIsInstance<SublimationEffect.StatEffect>()) {
                    if (eff.characteristic !in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS) continue
                    if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                    val x = eff.magnitudeAtLevel(level)
                    if (eff.characteristic in secondaryObjective && x > 0) p += x
                    if (x < 0) qn += -x.toLong()
                }
                if (p == 0L && qn == 0L) continue
                repeat(sub.maxCopies.coerceAtLeast(1)) { pickLists += listOf(0 to 0L, bucket(p - qn) to p) }
            }
            val skills = params.character.characterSkills
            for (branch in listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major)) {
                val v =
                    branch
                        .getCharacteristics()
                        .flatMap { sk -> if (sk is me.chosante.common.skills.SkillCharacteristic.PairedCharacteristic) listOf(sk.first, sk.second) else listOf(sk) }
                        .filter { it.characteristic in secondaryObjective && it.unitType == me.chosante.common.skills.UnitType.FIXED }
                        .maxOfOrNull { (it.unitValue * minOf(branch.maxPointsToAssign, it.maxPointsAssignable)).toLong() } ?: 0L
                if (v > 0) pickLists += listOf(0 to 0L, bucket(v) to v)
            }
            if (params.useRunes && "noRunes" !in diag) {
                val matching = runes.filter { it.characteristic in secondaryObjective }
                if (matching.isNotEmpty()) {
                    for ((slot, items) in pool) {
                        val sockets = items.maxOfOrNull { it.maxShardSlots } ?: 0
                        val lvl = items.maxOfOrNull { it.level } ?: 0
                        val per = matching.maxOf { it.valueOn(slot, lvl) }.toLong()
                        if (per <= 0) continue
                        repeat(sockets * (if (slot == ItemType.RING) 2 else 1)) {
                            pickLists += listOf(0 to 0L, bucket(per) to per)
                        }
                    }
                }
            }
            var dp = HashMap<Int, Long>().apply { put(0, 0L) }
            for (options in pickLists) {
                val next = HashMap<Int, Long>(dp.size * 2)
                for ((b, p) in dp) {
                    for ((db, dpVal) in options) {
                        val nb = b + db
                        val np = p + dpVal
                        val cur = next[nb]
                        if (cur == null || np > cur) next[nb] = np
                    }
                }
                dp = next
            }
            return dp.entries.filter { it.key <= bucket(t) }.maxOfOrNull { it.value } ?: t.coerceAtLeast(0L)
        }

        /**
         * Independent upper bound for a `sum(all secondary masteries) <= t` carrier. Write the
         * signed sum as `P_all - N <= t`, where N is the magnitude of all negative secondary
         * lines. Then the positive scenario-relevant part obeys `P_objective <= P_all <= t + N`.
         *
         * The item part maximizes N over the real slot/weapon layout and the one-EPIC/one-RELIC
         * budgets. Every non-item negative source is deliberately all-credited independently
         * (ignoring skill/sub/rune budgets), so this remains an upper bound. Taking the minimum
         * with [secondaryBudgetCap] is therefore sound and removes its impossible 2H+1H+off-hand
         * and multi-relic negative-budget stack.
         */
        fun negativeSecondaryLines(
            values: Map<Characteristic, Int>,
            onlyOutsideScenario: Boolean = false,
        ): Long =
            values.entries
                .filter {
                    it.key in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS &&
                        (!onlyOutsideScenario || it.key !in masteryStats)
                }.sumOf { (_, v) -> (-minOf(v, 0)).toLong() }

        fun secondaryExternalNegativeBudget(onlyOutsideScenario: Boolean = false): Long {
            var external = negativeSecondaryLines(baseValues, onlyOutsideScenario)
            if (params.useRunes && "noRunes" !in diag) {
                for ((slot, items) in pool) {
                    val sockets = items.maxOfOrNull { it.maxShardSlots } ?: 0
                    val per =
                        items.maxOfOrNull { item ->
                            runes.maxOfOrNull { rune ->
                                if (rune.characteristic in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS &&
                                    (!onlyOutsideScenario || rune.characteristic !in masteryStats)
                                ) {
                                    -minOf(rune.valueOn(slot, item.level), 0)
                                } else {
                                    0
                                }
                            } ?: 0
                        } ?: 0
                    external += sockets.toLong() * per * (if (slot == ItemType.RING) 2 else 1)
                }
            }
            val skills = params.character.characterSkills
            for (branch in listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major)) {
                for (skill in branch.getCharacteristics()) {
                    val components =
                        if (skill is me.chosante.common.skills.SkillCharacteristic.PairedCharacteristic) {
                            listOf(skill.first, skill.second)
                        } else {
                            listOf(skill)
                        }
                    for (component in components) {
                        if (component.characteristic !in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS ||
                            (onlyOutsideScenario && component.characteristic in masteryStats) ||
                            component.unitValue >= 0
                        ) {
                            continue
                        }
                        external +=
                            if (component.unitType == me.chosante.common.skills.UnitType.FIXED) {
                                (-component.unitValue).toLong() * minOf(branch.maxPointsToAssign, component.maxPointsAssignable)
                            } else {
                                // No negative-percent secondary skill exists in the catalog. If one is
                                // added, keep this diagnostic bound sound (and intentionally loose).
                                STAT_ABS_MAX
                            }
                    }
                }
            }
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                val perCopy =
                    sub.effects
                        .filterIsInstance<SublimationEffect.StatEffect>()
                        .filter {
                            it.characteristic in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS &&
                                (!onlyOutsideScenario || it.characteristic !in masteryStats)
                        }.sumOf { effect -> -minOf(effect.magnitudeAtLevel(level), 0).toLong() }
                external += perCopy * sub.maxCopies.coerceAtLeast(1)
            }
            return external
        }

        fun secondaryNegativeBudgetCap(t: Long): Long {
            data class NegOpt(
                val amount: Long,
                val epic: Boolean = false,
                val relic: Boolean = false,
            )

            fun itemOpt(e: Equipment) =
                NegOpt(
                    negativeSecondaryLines(e.characteristics),
                    epic = e.rarity == me.chosante.common.Rarity.EPIC,
                    relic = e.rarity == me.chosante.common.Rarity.RELIC
                )

            fun combine(
                a: NegOpt,
                b: NegOpt,
            ): NegOpt? {
                if (a.epic && b.epic || a.relic && b.relic) return null
                return NegOpt(a.amount + b.amount, a.epic || b.epic, a.relic || b.relic)
            }

            fun prune(options: Iterable<NegOpt>): List<NegOpt> =
                options
                    .groupBy { it.epic to it.relic }
                    .values
                    .map { group -> group.maxBy { it.amount } }

            var layouts = listOf(NegOpt(0L))

            fun step(options: List<NegOpt>) {
                layouts = prune(layouts.flatMap { a -> options.mapNotNull { b -> combine(a, b) } })
            }

            val ringItems = pool[ItemType.RING].orEmpty()
            val ringOptions = mutableListOf(NegOpt(0L))
            ringOptions += ringItems.map(::itemOpt)
            for (i in ringItems.indices) {
                for (j in i + 1 until ringItems.size) {
                    if (ringItems[i].name.fr.lowercase() == ringItems[j].name.fr.lowercase()) continue
                    combine(itemOpt(ringItems[i]), itemOpt(ringItems[j]))?.let(ringOptions::add)
                }
            }
            step(prune(ringOptions))

            val twoHanded = pool[ItemType.TWO_HANDED_WEAPONS].orEmpty().map(::itemOpt)
            val oneHanded = pool[ItemType.ONE_HANDED_WEAPONS].orEmpty().map(::itemOpt)
            val offHand = pool[ItemType.OFF_HAND_WEAPONS].orEmpty().map(::itemOpt)
            val weaponOptions = mutableListOf(NegOpt(0L))
            weaponOptions += twoHanded
            weaponOptions += oneHanded
            weaponOptions += offHand
            for (one in oneHanded) {
                for (off in offHand) combine(one, off)?.let(weaponOptions::add)
            }
            step(prune(weaponOptions))

            val handSlots = setOf(ItemType.RING, ItemType.ONE_HANDED_WEAPONS, ItemType.TWO_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS)
            for (slot in pool.keys - handSlots) {
                step(prune(listOf(NegOpt(0L)) + pool[slot].orEmpty().map(::itemOpt)))
            }

            return (t + layouts.maxOf { it.amount } + secondaryExternalNegativeBudget()).coerceAtLeast(0L)
        }

        var epicRelicStages: (() -> Unit)? = null
        var assumedOpt = Opt(0L, 0)

        // Weight-arm caps (RAW units): what the zeroed component can reach on a build COVERED by
        // this arm — condition threshold + the cappers' own grants (a sub never feeds its own
        // condition, so they ride above t). Feeds the fold constant AND the conversion moved cap.
        fun ownPositiveOf(
            sub: Sublimation,
            pred: (Characteristic) -> Boolean,
        ): Long =
            sub.effects
                .filterIsInstance<SublimationEffect.StatEffect>()
                .filter { pred(it.characteristic) && WakfuBuildSolver.scenarioGateMatches(it.scenarioGate, params) }
                .sumOf { maxOf(it.magnitudeAtLevel(level), 0).toLong() }

        val secTMax =
            if (armZeroSecondary) {
                objCapSubs
                    .filter { capsObjectiveType(it) == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST }
                    .maxOf { (it.condition?.value ?: 0).toLong() }
            } else {
                0L
            }
        val secOwnMax =
            if (armZeroSecondary) {
                objCapSubs
                    .filter { capsObjectiveType(it) == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST }
                    .maxOf { ownPositiveOf(it) { c -> c in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS && c in masteryStats } }
            } else {
                0L
            }
        val armSecIndependentCapRaw =
            if (armZeroSecondary) minOf(secondaryBudgetCap(secTMax), secondaryNegativeBudgetCap(secTMax)) + secOwnMax else 0L
        val armSecCapRaw =
            if (armZeroSecondary && coupleSecondaryItemNegative) {
                secTMax + secondaryExternalNegativeBudget(onlyOutsideScenario = netSecondaryItemBudget) + secOwnMax
            } else {
                armSecIndependentCapRaw
            }
        // Conversions into DI/CC still need a scalar moved ceiling. The item-coupled cap is represented
        // inside W rather than as a raw stat, so retain the older independent (looser, sound) ceiling there.
        val armSecConversionCapRaw =
            if (armZeroSecondary && coupleSecondaryItemNegative) armSecIndependentCapRaw else armSecCapRaw
        val armCritMCapRaw: Long =
            if (armZeroCritM) {
                objCapSubs
                    .filter { capsObjectiveType(it) == SublimationConditionType.CRITICAL_MASTERY_AT_MOST }
                    .maxOf { (it.condition?.value ?: 0).toLong() + ownPositiveOf(it) { c -> c == Characteristic.MASTERY_CRITICAL } }
            } else {
                0L
            }
        val armConstantW = wMastery * armSecCapRaw + wCritMastery * armCritMCapRaw
        if (params.useSublimations && "noSubs" !in diag) {
            data class SubOpt(
                val opt: Opt,
                val rarity: SublimationRarity,
            )
            val subOpts = mutableListOf<SubOpt>()
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                if (lightWeaponArm == "noExpert" &&
                    sub.condition?.type == SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED
                ) {
                    continue
                }
                if ("noCondSubs" in diag && sub.condition != null) continue
                val cond = sub.condition
                val capsObjective =
                    cond != null &&
                        (
                            cond.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ||
                                cond.type == SublimationConditionType.CRITICAL_MASTERY_AT_MOST
                        )
                // AT_MOST cap subs never enter the stages — the world split handles them.
                if (capStatOf(sub) != null && sub !== worldAssume) continue
                // Objective-capping subs per ARM: plain excludes them; secZero stages sec-cappers
                // AND critM cappers (cap-ignored — sound); critZero stages critM cappers only.
                if (capsObjective) {
                    val secCapper = cond?.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST
                    val staged =
                        (armZeroSecondary) || (armZeroCritM && !secCapper)
                    if (!staged) continue
                }
                val blockRequirement =
                    if (blockAtLeastMax > 0 && cond?.type == SublimationConditionType.BLOCK_AT_LEAST) (cond.value ?: 0) else 0
                var opt =
                    Opt(
                        0L,
                        0,
                        requiresBlockAtLeast = blockRequirement,
                        conditional = requireConditionalSub && cond != null,
                        src = if (wantSrc) sub.name.fr else ""
                    )
                for (eff in sub.effects) {
                    when (eff) {
                        is SublimationEffect.StatEffect -> {
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            val value = eff.magnitudeAtLevel(level)
                            // The quick tier may ignore MAX_MP debits. With the exact NORMAL pack,
                            // keep them signed so positive MP can legally compensate a -1 rider.
                            if (eff.characteristic == Characteristic.MAX_MOVEMENT_POINT) {
                                if (foldNegativeMaxMp && exactNormalSubPacking && value < 0) {
                                    opt = opt.copy(mp = opt.mp + value)
                                }
                                continue
                            }
                            if (value <= 0) {
                                // NET DI per sub: a build takes the sub as a whole, so its DI
                                // lines SUM exactly (Anatomie's +40 back / −20 flat is +20 real —
                                // the positive-only read credited +40). Clamped ≥ 0 at the end.
                                if (value < 0 && eff.characteristic == Characteristic.DAMAGE_INFLICTED) {
                                    opt = opt.copy(d = opt.d + value)
                                }
                                if (value < 0 && geo.assumeApThreshold >= 0 && eff.characteristic == Characteristic.ACTION_POINT) {
                                    opt = opt.copy(apLow = opt.apLow + value)
                                }
                                if (value < 0 && geo.assumeCcThresholdRaw >= 0 && eff.characteristic == Characteristic.CRITICAL_HIT) {
                                    opt = opt.copy(ccLowRaw = opt.ccLowRaw + value)
                                }
                                continue
                            }
                            opt =
                                when (eff.characteristic) {
                                    Characteristic.DAMAGE_INFLICTED -> opt.copy(d = opt.d + value)
                                    Characteristic.ACTION_POINT ->
                                        opt.copy(ap = opt.ap + value, apLow = opt.apLow + (if (geo.assumeApThreshold >= 0) value else 0))
                                    Characteristic.MOVEMENT_POINT -> opt.copy(mp = opt.mp + value)
                                    Characteristic.CRITICAL_HIT ->
                                        opt.copy(cc = opt.cc + value, ccLowRaw = opt.ccLowRaw + (if (geo.assumeCcThresholdRaw >= 0) value else 0))
                                    Characteristic.HP -> opt.copy(hp = opt.hp + value)
                                    Characteristic.BLOCK_PERCENTAGE ->
                                        if (blockAtLeastMax > 0) opt.copy(block = opt.block + value) else opt.copy(w = opt.w + wOf(eff.characteristic, value))
                                    else -> opt.copy(w = opt.w + wOf(eff.characteristic, value))
                                }
                        }
                        is SublimationEffect.PerStatStep -> {
                            if ("noRamps" in diag) continue
                            if (mpDiRamp != null && eff === mpDiRamp.second) {
                                opt = opt.copy(ramp = true)
                                continue
                            }
                            val credit = eff.contribution(reachableMax(eff.source))
                            if (credit <= 0) continue
                            opt =
                                when (eff.target) {
                                    Characteristic.DAMAGE_INFLICTED -> opt.copy(d = opt.d + credit)
                                    Characteristic.ACTION_POINT -> opt.copy(ap = opt.ap + credit)
                                    Characteristic.MOVEMENT_POINT -> opt.copy(mp = opt.mp + credit)
                                    Characteristic.CRITICAL_HIT -> opt.copy(cc = opt.cc + credit)
                                    Characteristic.HP -> opt.copy(hp = opt.hp + credit)
                                    else -> opt.copy(w = opt.w + wOf(eff.target, credit))
                                }
                        }
                        // Elemental Concentration (single-element max-damage: solver-choosable):
                        // credit the +DI, ignore the weakest-elements penalty — over-count, sound.
                        is SublimationEffect.BestElementConcentration -> {
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            opt = opt.copy(d = opt.d + maxOf(eff.damageInflictedBonus, 0))
                        }
                        // Conversion: the real effect MOVES the stat, so in W terms a carrier nets
                        // `moved × (w_to − w_from)`. Credit `max(0, Δweight) × reachableMax(from)`
                        // (dropping a negative net = over-count, sound; the additive no-debit
                        // shortcut credited Dénouement's critM→elemental at +45% of W — the S4-2
                        // binding-path culprit, W-NEUTRAL in reality at critCap=100 where both
                        // weights are 500). A conversion INTO the DI/CC axes cannot debit W —
                        // keep those additive (over-count, sound).
                        is SublimationEffect.Conversion -> {
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            // Arm-covered builds hold the zeroed component ≤ the arm cap — a
                            // conversion FROM it cannot move more than that (the reachableMax
                            // base re-inflated Dénouement to +45% of W inside critZero).
                            val movedBase =
                                when {
                                    armZeroCritM && eff.from == Characteristic.MASTERY_CRITICAL -> armCritMCapRaw
                                    armZeroSecondary && eff.from in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS ->
                                        armSecConversionCapRaw
                                    else -> reachableMax(eff.from).coerceAtLeast(0).toLong()
                                }
                            val moved = (movedBase * eff.percent / 100L).toInt()
                            if (moved <= 0) continue
                            opt =
                                when (eff.to) {
                                    Characteristic.DAMAGE_INFLICTED -> opt.copy(d = opt.d + moved)
                                    Characteristic.CRITICAL_HIT -> opt.copy(cc = opt.cc + moved)
                                    else -> {
                                        // In the coupled secZero arm, `wMastery*N` already reserves one
                                        // scenario-secondary unit per moved source unit. Replacing it by the
                                        // destination therefore costs only (w_to - wMastery), not full w_to.
                                        val sourceWeight =
                                            if (armZeroSecondary &&
                                                coupleSecondaryItemNegative &&
                                                eff.from in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
                                            ) {
                                                wMastery
                                            } else {
                                                wOf(eff.from, 1)
                                            }
                                        val netPerUnit = (wOf(eff.to, 1) - sourceWeight).coerceAtLeast(0L)
                                        opt.copy(w = opt.w + netPerUnit * moved)
                                    }
                                }
                        }
                        else -> {}
                    }
                }
                if (sub === worldAssume) {
                    assumedOpt = opt
                    continue
                }
                val entersNormalPacking =
                    sub.rarity == SublimationRarity.NORMAL &&
                        opt.mpCapMinus == 0 &&
                        !opt.ramp &&
                        opt.requiresBlockAtLeast == 0
                if (opt.d < 0 && !(exactNormalSubPacking && entersNormalPacking)) opt = opt.copy(d = 0)
                if (opt.w == 0L && opt.d == 0 && opt.ap == 0 && opt.mp == 0 && opt.cc == 0 && opt.hp == 0 && !opt.ramp && !opt.conditional) {
                    continue
                }
                repeat(sub.maxCopies.coerceAtLeast(1)) { subOpts += SubOpt(opt, sub.rarity) }
            }

            fun bucketOptions(
                rarity: SublimationRarity,
                capCount: Int,
            ): List<Opt> {
                // Flag-carrying subs never enter the packing — they get their own stages below.
                val opts =
                    subOpts
                        .filter {
                            it.rarity == rarity &&
                                it.opt.mpCapMinus == 0 &&
                                !it.opt.ramp &&
                                it.opt.requiresBlockAtLeast == 0
                        }.map { it.opt }
                if (opts.isEmpty()) return listOf(Opt(0L, 0))
                // LOW dims: the packing cannot track per-subset signed sums — every aggregate
                // carries the pool-wide NEGATIVE parts (a valid lower bound of any subset).
                val knapApNeg = opts.sumOf { minOf(it.apLow, 0) }
                val knapCcNeg = opts.sumOf { minOf(it.ccLowRaw, 0) }
                if (exactNormalSubPacking && rarity == SublimationRarity.NORMAL) {
                    data class ExactSubKey(
                        val count: Int,
                        val d: Int,
                        val ap: Int,
                        val mp: Int,
                        val cc: Int,
                        val hpBucket: Int,
                        val blockBucket: Int,
                        val conditional: Boolean,
                    )

                    data class PackedKey(
                        val d: Int,
                        val ap: Int,
                        val mp: Int,
                        val cc: Int,
                        val hp: Int,
                        val block: Int,
                        val conditional: Boolean,
                    )

                    val zero = ExactSubKey(0, 0, 0, 0, 0, 0, 0, false)
                    val ccRawCap = geo.ccBucketCap * ccStep
                    var exact = HashMap<ExactSubKey, Long>().apply { put(zero, 0L) }
                    var exactSrc = if (wantSrc) HashMap<ExactSubKey, String>().apply { put(zero, "") } else null
                    for (o in opts) {
                        val next = HashMap(exact)
                        val nextSrc = exactSrc?.let(::HashMap)
                        for ((k, wv) in exact) {
                            if (k.count >= capCount) continue
                            val nk =
                                ExactSubKey(
                                    count = k.count + 1,
                                    // Saturating before a later negative DI rider could under-count.
                                    d = k.d + o.d,
                                    ap = (k.ap + o.ap).coerceAtMost(geo.apCap),
                                    mp = (k.mp + o.mp).coerceAtMost(geo.mpCap),
                                    cc = (k.cc + o.cc).coerceAtMost(ccRawCap),
                                    hpBucket = (k.hpBucket + ceilDiv(o.hp, hpStep)).coerceAtMost(geo.hpBucketCap),
                                    blockBucket =
                                        (k.blockBucket + ceilDiv(o.block, BLOCK_STEP)).coerceAtMost(geo.blockBucketCap),
                                    conditional = k.conditional || o.conditional
                                )
                            val nw = wv + o.w
                            val current = next[nk]
                            if (current == null || nw > current) {
                                next[nk] = nw
                                nextSrc?.set(
                                    nk,
                                    listOfNotNull(
                                        exactSrc?.get(k)?.takeIf(String::isNotEmpty),
                                        o.src.takeIf(String::isNotEmpty)
                                    ).joinToString("+")
                                )
                            }
                        }
                        exact = next
                        exactSrc = nextSrc
                    }

                    val packed = HashMap<PackedKey, Opt>()
                    for ((k, wv) in exact) {
                        val pk =
                            PackedKey(
                                d = k.d.coerceAtLeast(0).coerceAtMost(geo.diBucketCap * diStep),
                                ap = k.ap,
                                mp = k.mp,
                                cc = k.cc,
                                hp = k.hpBucket * hpStep,
                                block = k.blockBucket * BLOCK_STEP,
                                conditional = k.conditional
                            )
                        val candidate =
                            Opt(
                                w = wv,
                                d = pk.d,
                                ap = pk.ap,
                                mp = pk.mp,
                                cc = pk.cc,
                                hp = pk.hp,
                                block = pk.block,
                                conditional = pk.conditional,
                                apLow = knapApNeg,
                                ccLowRaw = knapCcNeg,
                                src = if (wantSrc) exactSrc?.get(k).orEmpty().ifEmpty { "$rarity x${k.count}" } else ""
                            )
                        val current = packed[pk]
                        if (current == null || candidate.w > current.w) packed[pk] = candidate
                    }
                    return packed.values.toList()
                }
                val statMask = (1L shl 50) - 1
                var sub = HashMap<Long, Long>()
                sub[0L] = 0L
                var subSrc = if (wantSrc) HashMap<Long, String>().apply { put(0L, "") } else null
                for (o in opts) {
                    val next = HashMap(sub)
                    val nextSrc = subSrc?.let(::HashMap)
                    for ((k, wv) in sub) {
                        val cnt = (k shr 50).toInt()
                        if (cnt >= capCount) continue
                        val stat = k and statMask
                        val nStat =
                            geo.key(
                                (geo.d(stat) + ceilDiv(o.d, diStep)).coerceAtMost(geo.diBucketCap),
                                (geo.ap(stat) + o.ap).coerceAtMost(geo.apCap),
                                (geo.mp(stat) + o.mp).coerceAtMost(geo.mpCap),
                                (geo.cc(stat) + ceilDiv(o.cc, ccStep)).coerceAtMost(geo.ccBucketCap),
                                (geo.hp(stat) + ceilDiv(o.hp, hpStep)).coerceAtMost(geo.hpBucketCap),
                                0,
                                0,
                                block = (geo.block(stat) + ceilDiv(o.block, BLOCK_STEP)).coerceAtMost(geo.blockBucketCap),
                                conditional = if (geo.conditional(stat) == 1 || o.conditional) 1 else 0
                            )
                        val nk = ((cnt + 1).toLong() shl 50) or nStat
                        val nw = wv + o.w
                        val cur = next[nk]
                        if (cur == null || nw > cur) {
                            next[nk] = nw
                            nextSrc?.set(
                                nk,
                                listOfNotNull(subSrc[k]?.takeIf(String::isNotEmpty), o.src.takeIf(String::isNotEmpty)).joinToString("+")
                            )
                        }
                    }
                    sub = next
                    subSrc = nextSrc
                }
                return sub.map { (k, wv) ->
                    val cnt = (k shr 50).toInt()
                    val stat = k and statMask
                    Opt(
                        w = wv,
                        d = geo.d(stat) * diStep,
                        ap = geo.ap(stat),
                        mp = geo.mp(stat),
                        cc = geo.cc(stat) * ccStep,
                        hp = geo.hp(stat) * hpStep,
                        block = geo.block(stat) * BLOCK_STEP,
                        conditional = geo.conditional(stat) == 1,
                        requiresEpicItem = rarity == SublimationRarity.EPIC && cnt > 0,
                        requiresRelicItem = rarity == SublimationRarity.RELIC && cnt > 0,
                        apLow = knapApNeg,
                        ccLowRaw = knapCcNeg,
                        src = if (wantSrc) subSrc?.get(k).orEmpty().ifEmpty { "$rarity x$cnt" } else ""
                    )
                }
            }
            step("subs-normal", bucketOptions(SublimationRarity.NORMAL, 10))
            // Flag-carrying NORMAL subs: one stage each on top of the 10-cap knapsack (over-counts
            // the shared slot budget by ≤ the handful of such subs — sound).
            for (flagged in subOpts.filter {
                it.rarity == SublimationRarity.NORMAL &&
                    (it.opt.mpCapMinus != 0 || it.opt.ramp || it.opt.requiresBlockAtLeast != 0)
            }) {
                step("sub-flagged", listOf(Opt(0L, 0), flagged.opt))
            }

            fun singleSlotOptions(rarity: SublimationRarity): List<Opt> =
                listOf(Opt(0L, 0)) +
                    prune(
                        subOpts.filter { it.rarity == rarity }.map {
                            it.opt.copy(
                                requiresEpicItem = rarity == SublimationRarity.EPIC,
                                requiresRelicItem = rarity == SublimationRarity.RELIC
                            )
                        }
                    )
            // EPIC/RELIC stages land AFTER skills so AT_LEAST conditions gate on the full sheet.
            epicRelicStages = {
                // Assume worlds: the assumed cap sub (EPIC) occupies the single epic-sub slot.
                step("subs-epic", if (worldAssume != null) listOf(Opt(0L, 0)) else singleSlotOptions(SublimationRarity.EPIC))
                step("subs-relic", singleSlotOptions(SublimationRarity.RELIC))
            }
        }
        // Every solver-choosable negative MAX_MP source is NORMAL on the supported catalog and is
        // now inside the signed pack. From here on MP is monotone, so target-capping is exact.
        collapseMpHeadroom("mp-fold-after-subs")
        // Skills — after subs (the %HP skill multiplies the whole HP dim). Paired majors are
        // expanded into their two component credits: unlike MM, the elemental half feeds THIS core.
        if ("noSkills" !in diag) {
            val skills = params.character.characterSkills
            for (branch in listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major)) {
                fun componentsOf(sk: me.chosante.common.skills.SkillCharacteristic): List<me.chosante.common.skills.SkillCharacteristic> =
                    if (sk is me.chosante.common.skills.SkillCharacteristic.PairedCharacteristic) listOf(sk.first, sk.second) else listOf(sk)

                fun relevantChar(c: Characteristic?): Boolean =
                    c != null &&
                        (
                            c in masteryStats ||
                                c in randomStats ||
                                c == Characteristic.MASTERY_CRITICAL ||
                                c == Characteristic.DAMAGE_INFLICTED ||
                                c in targetByChar ||
                                (blockAtLeastMax > 0 && c == Characteristic.BLOCK_PERCENTAGE)
                        )
                val relevant = branch.getCharacteristics().filter { sk -> componentsOf(sk).any { relevantChar(it.characteristic) } }
                if (relevant.isEmpty()) continue
                val budget = branch.maxPointsToAssign

                fun creditOf(
                    sk: me.chosante.common.skills.SkillCharacteristic,
                    pts: Int,
                ): Opt {
                    var acc = Opt(0L, 0, src = if (wantSrc && pts > 0) "${sk.name}:$pts" else "")
                    for (component in componentsOf(sk)) {
                        val skChar = component.characteristic ?: continue
                        if (component.unitType == me.chosante.common.skills.UnitType.PERCENT && skChar == Characteristic.HP) {
                            acc = acc.copy(hpPct = acc.hpPct + pts * component.unitValue)
                            continue
                        }
                        val value =
                            if (component.unitType == me.chosante.common.skills.UnitType.PERCENT) {
                                ceil(reachableMax(skChar).coerceAtLeast(0) * pts.toLong() * component.unitValue / 100.0).toLong()
                            } else {
                                pts.toLong() * component.unitValue
                            }
                        val v = value.coerceAtLeast(0L).toInt()
                        acc =
                            when (skChar) {
                                Characteristic.DAMAGE_INFLICTED -> acc.copy(d = acc.d + v)
                                Characteristic.ACTION_POINT ->
                                    acc.copy(ap = acc.ap + v, apLow = acc.apLow + (if (geo.assumeApThreshold >= 0) v else 0))
                                Characteristic.MOVEMENT_POINT -> acc.copy(mp = acc.mp + v)
                                Characteristic.CRITICAL_HIT ->
                                    acc.copy(cc = acc.cc + v, ccLowRaw = acc.ccLowRaw + (if (geo.assumeCcThresholdRaw >= 0) v else 0))
                                Characteristic.HP -> acc.copy(hp = acc.hp + v)
                                Characteristic.BLOCK_PERCENTAGE ->
                                    if (blockAtLeastMax > 0) acc.copy(block = acc.block + v) else acc.copy(w = acc.w + wOf(skChar, v))
                                else -> acc.copy(w = acc.w + wOf(skChar, v))
                            }
                    }
                    return acc
                }

                var options = listOf(Opt(0L, 0) to budget)
                for (sk in relevant) {
                    val next = mutableListOf<Pair<Opt, Int>>()
                    for ((acc, remaining) in options) {
                        var pts = 0
                        while (true) {
                            val spend = pts.coerceAtMost(minOf(remaining, sk.maxPointsAssignable))
                            next += combineOpts(acc, creditOf(sk, spend)) to (remaining - spend)
                            if (spend >= minOf(remaining, sk.maxPointsAssignable)) break
                            pts += 1
                        }
                    }
                    options = next
                }
                step("skills-${branch.javaClass.simpleName}", options.map { it.first }.distinct())
                collapseMpHeadroom("mp-fold-${branch.javaClass.simpleName}")
            }
        }

        epicRelicStages?.invoke()
        collapseMpHeadroom("mp-fold-final")
        if (cancelled) return null

        // Collapse: the exact damage chain (throughput lookup + downscales) × the exact
        // applyConstraintPenalty fold — NO overshoot scale in max-damage.
        val totalExpected =
            targets
                .sumOf { it.target.toLong() * params.targetStats.scaledWeight(it) }
                .coerceAtLeast(1L)
        val bucketSize =
            if (totalExpected <= MAX_POWER_TABLE_INDEX) 1L else ceil(totalExpected.toDouble() / MAX_POWER_TABLE_INDEX).toLong()
        val maxIndex = if (totalExpected <= MAX_POWER_TABLE_INDEX) totalExpected.toInt() else ((totalExpected + bucketSize - 1) / bucketSize).toInt()
        val maxPow = BigInteger.valueOf(maxIndex.toLong()).pow(6)
        val powScale =
            if (maxPow > BigInteger.valueOf(MAX_PENALTY_MULTIPLIER)) maxPow.divide(BigInteger.valueOf(MAX_PENALTY_MULTIPLIER)) else BigInteger.ONE
        val powTable =
            LongArray(maxIndex + 1) { i ->
                BigInteger
                    .valueOf(i.toLong())
                    .pow(6)
                    .divide(powScale)
                    .toLong()
            }

        fun weight(char: Characteristic): Long = targetByChar[char]?.let { params.targetStats.scaledWeight(it) } ?: 0L

        fun targetOf(char: Characteristic): Long = targetByChar[char]?.target?.toLong() ?: 0L

        var bestCore = 0L
        var bestFolded = 0L
        var bindingState = ""
        var bindingKey = 0L
        var bindingW = 0L

        data class ScoredBand(
            val core: Long,
            val folded: Long,
            val wUpper: Long,
            val ccLow: Long,
            val ccHigh: Long,
        )

        data class FoldResult(
            val maxCore: Long,
            val winner: ScoredBand,
        )

        for ((k, wv) in states) {
            val armForcesConditional = armZeroSecondary || armZeroCritM
            if (requireConditionalSub && geo.conditional(k) == 0 && !assumedOpt.conditional && !armForcesConditional) continue
            // ASSUME-world filters: the assumed cap sub is EPIC (needs an epic item) and the
            // condition must hold on the LOW-read dim.
            if (worldAssume != null) {
                if (geo.e(k) == 0) continue
                if (geo.assumeApThreshold >= 0 && geo.ap(k) > geo.assumeApThreshold) continue
                if (geo.assumeCcThresholdRaw >= 0 && geo.cc(k) > geo.assumeCcThresholdRaw) continue
            }
            val rampDi =
                if (geo.ramp(k) == 1 && mpDiRamp != null) {
                    maxOf(mpDiRamp.second.contribution((geo.mp(k) + assumedOpt.mp).coerceAtMost(geo.mpCap)), 0)
                } else {
                    0
                }

            // Per-state fold parameterized by an extra credits layer (the assumed cap sub and/or a
            // world-B sub) and an optional W-cap (world-B condition).
            fun foldWith(
                extra: Opt,
                wCap: Long?,
            ): FoldResult {
                val di = (geo.d(k).toLong() * diStep + assumedOpt.d + extra.d + rampDi).coerceAtMost(diCap.toLong())
                val apRead =
                    if (geo.assumeApThreshold >= 0) {
                        ((geo.assumeApThreshold + maxOf(assumedOpt.ap, 0)).toLong() + extra.ap)
                    } else {
                        geo.ap(k).toLong() + assumedOpt.ap + extra.ap
                    }
                val ccRead =
                    if (geo.assumeCcThresholdRaw >= 0) {
                        (assumeThreshold + maxOf(assumedOpt.cc, 0)).toLong() + extra.cc
                    } else {
                        geo.cc(k).toLong() * ccStep + assumedOpt.cc + extra.cc
                    }
                val support =
                    wv + assumedOpt.w + armConstantW + extra.w +
                        ccSupportLambda * (assumedOpt.cc.coerceAtLeast(0) + extra.cc.coerceAtLeast(0))

                fun scoreBand(
                    supportDerivedW: Long,
                    ccForPenalty: Long,
                    ccLow: Long,
                    ccHigh: Long,
                ): ScoredBand {
                    val wUpper = supportDerivedW.let { if (wCap != null) minOf(it, wCap) else it }
                    val grawUb = wUpper.coerceIn(0L, DAMAGE_GRAW_MAX)
                    val perHit = ((100L + di) * grawUb).coerceAtMost(DAMAGE_SCORE_ABS_MAX)
                    val perHitScaled = (perHit / PERHIT_DOWNSCALE).coerceAtMost(PERHIT_SCALED_MAX)
                    val throughput = clampedTable[apRead.coerceIn(0L, clampedTable.lastIndex.toLong()).toInt()]
                    val raw = (throughput * perHitScaled).coerceAtMost(ROTATION_RAW_MAX)
                    val core = (raw * resFactor / FINAL_DOWNSCALE).coerceAtMost(DAMAGE_PERTURN_ABS_MAX)
                    if (targets.isEmpty()) return ScoredBand(core, core, wUpper, ccLow, ccHigh)
                    val totalActual =
                        weight(Characteristic.ACTION_POINT) * minOf(apRead, targetOf(Characteristic.ACTION_POINT)) +
                            weight(Characteristic.MOVEMENT_POINT) *
                            minOf(geo.mp(k).toLong() + assumedOpt.mp + extra.mp, targetOf(Characteristic.MOVEMENT_POINT)) +
                            weight(Characteristic.CRITICAL_HIT) * minOf(ccForPenalty, targetOf(Characteristic.CRITICAL_HIT)) +
                            weight(Characteristic.HP) *
                            minOf(geo.hp(k).toLong() * hpStep + assumedOpt.hp + extra.hp, targetOf(Characteristic.HP))
                    val bucket = (totalActual.coerceIn(1L, totalExpected) / bucketSize).toInt().coerceAtMost(maxIndex)
                    return ScoredBand(core, core * powTable[bucket], wUpper, ccLow, ccHigh)
                }

                if (ccSupportLambda == 0L || weight(Characteristic.CRITICAL_HIT) == 0L) {
                    val scored = scoreBand(support, ccRead, 0L, ccRead)
                    return FoldResult(scored.core, scored)
                }

                // For a build whose signed CC lies in [lo, hi], positiveCC >= signedCC gives
                // W <= H_lambda - lambda*lo, while pricing its target fold at hi only over-counts.
                // Negative signed CC is covered by the lo=0 rectangle (both W and target credit
                // are then relaxed upward). Values above the target are covered by its last band.
                val ccUpper = minOf(ccRead.coerceAtLeast(0L), targetOf(Characteristic.CRITICAL_HIT))
                var lo = 0L
                var maxCoreForFold = 0L
                var winner: ScoredBand? = null
                while (lo <= ccUpper) {
                    val hi = minOf(ccUpper, lo + ccSupportBand - 1L)
                    val scored = scoreBand(support - ccSupportLambda * lo, hi, lo, hi)
                    maxCoreForFold = maxOf(maxCoreForFold, scored.core)
                    if (winner == null || scored.folded > winner.folded) winner = scored
                    lo = hi + 1L
                }
                return FoldResult(maxCoreForFold, requireNotNull(winner))
            }

            fun consider(
                extra: Opt,
                wCap: Long?,
                tag: String,
            ) {
                val folded = foldWith(extra, wCap)
                if (folded.maxCore > bestCore) bestCore = folded.maxCore
                if (folded.winner.folded > bestFolded) {
                    bestFolded = folded.winner.folded
                    bindingKey = k
                    bindingW = wv
                    bindingState =
                        "supportLambda=$ccSupportLambda support=$wv ccBand=${folded.winner.ccLow}..${folded.winner.ccHigh} " +
                        "Wupper=${folded.winner.wUpper} armConstW=$armConstantW armSecCap=$armSecCapRaw " +
                        "d=${geo.d(k) * diStep}+ramp$rampDi ap=${geo.ap(k)} mp=${geo.mp(k)} " +
                        "cc=${geo.cc(k) * ccStep} hp=${geo.hp(k) * hpStep} e=${geo.e(k)} r=${geo.r(k)} " +
                        "conditional=${if (geo.conditional(k) == 1 || assumedOpt.conditional || armForcesConditional) 1 else 0} " +
                        "assume=${worldAssume?.name?.fr ?: "-"}$tag core=${folded.winner.core}"
                }
            }

            consider(EMPTY_OPT, null, if (worldArm != "plain") " arm($worldArm)" else "")
        }
        // Instrument: reconstruct the binding path backward — for each stage (last → first), find
        // a predecessor state + option that lands exactly on the current (key, w). INVERTED: the
        // naive `preMap × options` scan is O(billions) on the big stages (rings had 7.6M options)
        // and hangs for hours × every world; instead index the stage's predecessor states by their
        // w-value (buckets are small — w is finely spread) so only the `pw == curW − o.w` bucket is
        // scanned per option. A hard cap bails the reconstruction rather than risk a runaway.
        val bindingPath = mutableListOf<String>()
        if (provenance && stageLog != null && bindingState.isNotEmpty()) {
            var curK = bindingKey
            var curW = bindingW
            var work = 0L
            val workCap = 500_000_000L
            reconstruct@ for ((label, preMap, options) in stageLog.reversed()) {
                val byW = HashMap<Long, MutableList<Long>>(preMap.size)
                for ((pk, pw) in preMap) byW.getOrPut(pw) { mutableListOf() }.add(pk)
                var found = false
                outer@ for (o in options) {
                    val supportArc = o.w + ccSupportLambda * o.cc.coerceAtLeast(0)
                    val bucket = byW[curW - supportArc] ?: continue
                    for (pk in bucket) {
                        work++
                        if (geo.applyOne(pk, o) == curK) {
                            if (o.src.isNotEmpty() || o.w != 0L || o.d != 0 || o.ap != 0 || o.mp != 0 || o.cc != 0 || o.hp != 0) {
                                bindingPath +=
                                    "$label: ${o.src.ifEmpty { "opt" }} " +
                                    "(w=${o.w} d=${o.d} ap=${o.ap} mp=${o.mp} cc=${o.cc} hp=${o.hp} hpPct=${o.hpPct})"
                            }
                            curK = pk
                            curW -= supportArc
                            found = true
                            break@outer
                        }
                    }
                    if (work > workCap) {
                        bindingPath += "$label: <reconstruction bailed — work cap reached>"
                        break@reconstruct
                    }
                }
                if (!found) {
                    bindingPath += "$label: <no predecessor found — reconstruction broke here>"
                    break
                }
            }
            bindingPath.reverse()
        }
        return Result(bestFolded, bestCore, states.size, (System.nanoTime() - t0) / 1_000_000, bindingState, bindingPath)
    }

    private val EMPTY_OPT = Opt(0L, 0)

    // ---------------------------------------------------------------------------------------------
    // PRODUCTION soft-leg proof orchestration (plan §9.20) — the hybrid partition union.
    // ---------------------------------------------------------------------------------------------

    /** The winning support-function knee/band from the §9.13 screen — fixed in production. */
    private const val PROD_CC_SUPPORT_LAMBDA = 6000L
    private const val PROD_CC_SUPPORT_BAND = 5

    /** The §9.19 adaptive grid: coarse sweep steps and the fine defaults refinements restore. */
    private const val COARSE_DI_STEP = 10
    private const val COARSE_HP_STEP = 2000
    private const val COARSE_CC_STEP = 20
    private const val REFINE_HP_STEP = 1000

    /** Budget for the §9.22 relaxed (conditions-stripped) probe: 7.4 s at cra-140 even on a hot
     *  machine (no reifications = structurally easy); large pools burn the budget and fall back. */
    private const val RELAXED_PROBE_SECONDS = 45.0

    /** Budget for the §9.21 full-model CP-SAT probe — paid only on shapes where the DP is loose.
     *  cra-140 proves OPTIMAL in ~104 s cold but needed >300 s on a thermally saturated machine
     *  (§9.22 late-night screens) — 420 s buys the exact badge back on warm hardware; a timeout
     *  still degrades gracefully to the dual bound. */
    private const val CONDITIONAL_PROBE_SECONDS = 420.0

    /** Probe gate: below a 10% DP-vs-oracle gap the DP regime is already tight and the probe's
     *  dual essentially never beats it (iop-200: +2.9% DP, probe bought nothing for 180 s). */
    private const val CONDITIONAL_PROBE_MIN_GAP = 0.10
    private const val DEFAULT_DI_STEP = 1
    private const val DEFAULT_HP_STEP = 500
    private const val DEFAULT_CC_STEP = 10

    /** A sound upper bound on the PENALIZED soft objective over EVERY build (union of both partitions). */
    internal class SoftUnionUpper(
        val upper: Long,
        val noConditionUpper: Long,
        /** True when the no-condition side is a full CP-SAT `OPTIMAL` proof (else its dual bound — looser but sound). */
        val noConditionProven: Boolean,
        val wallMs: Long,
    )

    private val unionMemo = java.util.concurrent.ConcurrentHashMap<String, SoftUnionUpper>()

    /** Mirrors [bound]'s cheap up-front shape gates so the orchestrator can refuse a shape BEFORE
     *  spending an oracle solve on it. Deep bails (geometry overflow, >6 cap subs, AP-headroom)
     *  still surface as a null [bound] — the orchestrator then returns null too. */
    private fun supportsShape(params: WakfuBestBuildParams): Boolean {
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return false
        if (params.damageScenario.survivabilityFloor) return false
        if (params.maxDamageApTarget != null) return false
        if (params.maxDamageMpPin != null) return false
        if (params.damageScenario.candidateElements().size != 1) return false
        if (params.forcedItems.isNotEmpty() ||
            params.forcedRunes.isNotEmpty() ||
            params.forcedRunesByItem.isNotEmpty() ||
            params.forcedSublimations.isNotEmpty() ||
            params.forcedPassives.isNotEmpty()
        ) {
            return false
        }
        val targets = params.targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        return targets.none { it.characteristic !in SUPPORTED_TARGETS }
    }

    /**
     * PRODUCTION entry for the max-damage SOFT-leg proof (the targets-unreachable fallback — the
     * workload CP-SAT alone never proves, §9.10-9.12). Returns a sound upper bound on the
     * PENALIZED objective over ALL builds as the union of an exhaustive partition:
     *
     *  - **no conditional sublimation** — the no-condition CP-SAT model, solved CONCURRENTLY with
     *    the DP below on the production wall-clock portfolio. `OPTIMAL` ⇒ its objective is the
     *    partition's exact optimum; on a timeout its DUAL bound stands in (sound, just looser).
     *  - **≥1 conditional sublimation** — this file's DP with the `requireConditionalSub` marker:
     *    a coarse all-world sweep, then each contender still above the union floor refines through
     *    the §9.19 DI10→4→1 cascade (the cheapest sufficient grid carries the world).
     *
     * Every ingredient is a sound upper on its partition, so the max is a sound upper on every
     * build. Returns null on a bailed shape or cancellation — the caller withholds the badge.
     * Memoized per data/certifier version + request + pool + catalog (a GUI re-proof of the same
     * finished search must not re-pay the oracle).
     *
     * `@Synchronized`: the grid steps are shared mutable state on this object.
     */
    @Synchronized
    fun hybridUnionUpper(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        oracleWorkers: Int,
        oracleSeconds: Double,
        shouldContinue: () -> Boolean = { true },
        // §9.22 fast path: when the caller knows the incumbent's PENALIZED objective, the relaxed
        // probe below can close the proof outright (7.4 s at cra-140). MIN_VALUE = no early exit.
        incumbentObjective: Long = Long.MIN_VALUE,
    ): SoftUnionUpper? {
        if (!supportsShape(params)) return null
        val memoKey =
            listOf(
                me.chosante.common.WakfuData.VERSION,
                WakfuBuildSolver.CERTIFIER_VERSION,
                params.hashCode(),
                pool.values
                    .flatten()
                    .map { it.equipmentId }
                    .sorted()
                    .hashCode(),
                sublimations.map { it.name.fr }.sorted().hashCode(),
                runes.size
            ).joinToString("|")
        unionMemo[memoKey]?.let { return it }
        val t0 = System.nanoTime()

        // §9.22 STEP 0 — the RELAXED probe: strip the conditions (subs kept, slots and credits
        // intact, ZERO reifications). Sound upper on EVERY build: a real build whose conditional
        // subs are inert is covered by its variant without them (same value, feasible here). At
        // cra-140 it proves the EXACT optimum in 7.4 s — the conditional credits do not improve
        // the optimum even for free — closing the badge instantly; on large pools (S4-245) its
        // dual is useless (29.4T after 300 s) and the union below takes over. Forced subs never
        // reach this path ([supportsShape] bails), so the stripped credits are always optional.
        // Deliberately UNGATED: whether the probe closes depends on the REQUEST (do the
        // conditional credits improve ITS optimum, even free?), which no a-priori proxy (pool
        // size, level) can decide — shapes where it is useless only pay this bounded overhead
        // before the union takes over.
        val relaxedUpper =
            run {
                try {
                    val relaxed =
                        WakfuBuildSolver.timedMaxDamageProfileForTest(
                            params = params,
                            equipmentsByItemType = pool,
                            runes = runes,
                            sublimations =
                                sublimations.map {
                                    if (it.condition != null && it.solverChoosable) it.copy(condition = null) else it
                                },
                            workers = oracleWorkers,
                            seconds = RELAXED_PROBE_SECONDS,
                            applyDomination = true
                        )
                    if (relaxed.status == "OPTIMAL") relaxed.objective else relaxed.bestBound
                } catch (e: Exception) {
                    logger.warn(e) { "soft-leg proof: the relaxed probe failed — continuing with the union" }
                    Long.MAX_VALUE
                }
            }
        if (relaxedUpper <= incumbentObjective) {
            return SoftUnionUpper(relaxedUpper, relaxedUpper, true, (System.nanoTime() - t0) / 1_000_000)
                .also { unionMemo[memoKey] = it }
        }
        if (!shouldContinue()) return null

        val noConditionSubs = sublimations.filter { it.condition == null }
        val oracleFuture =
            java.util.concurrent.CompletableFuture.supplyAsync {
                WakfuBuildSolver.warmUp()
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params = params,
                    equipmentsByItemType = pool,
                    runes = runes,
                    sublimations = noConditionSubs,
                    workers = oracleWorkers,
                    seconds = oracleSeconds,
                    applyDomination = true
                )
            }

        fun joinOracle(): Pair<Long, Boolean>? =
            try {
                val profile = oracleFuture.join()
                if (profile.status == "OPTIMAL") {
                    profile.objective to true
                } else {
                    // The dual bound of the no-condition model is a sound upper for its partition
                    // even on a timeout (domination is exactness-preserving, so the reduced-model
                    // dual still covers the full pool).
                    profile.bestBound to false
                }
            } catch (e: Exception) {
                logger.warn(e) { "soft-leg proof: the no-condition oracle solve failed — badge withheld" }
                null
            }

        try {
            val hasConditional =
                params.useSublimations && sublimations.any { it.solverChoosable && it.condition != null }
            if (!hasConditional) {
                // The active catalog carries no conditional sublimation: the no-condition model IS
                // the full model and its bound alone covers every build.
                val (upper, proven) = joinOracle() ?: return null
                return SoftUnionUpper(upper, upper, proven, (System.nanoTime() - t0) / 1_000_000)
                    .also { if (proven) unionMemo[memoKey] = it }
            }

            diStep = COARSE_DI_STEP
            hpStep = COARSE_HP_STEP
            ccStep = COARSE_CC_STEP
            val coarse =
                bound(
                    params,
                    pool,
                    runes,
                    sublimations,
                    blockGate = false,
                    shouldContinue = shouldContinue,
                    ccSupportLambda = PROD_CC_SUPPORT_LAMBDA,
                    ccSupportBand = PROD_CC_SUPPORT_BAND,
                    coupleSecondaryItemNegative = true,
                    netSecondaryItemBudget = true,
                    exactNormalSubPacking = true,
                    foldNegativeItemAp = true,
                    // The coarse pass keeps the cheaper optimistic MP read and unsplit light
                    // weapon (both sound uppers); only contenders pay the fine seams below.
                    foldNegativeMaxMp = false,
                    splitLightWeaponCondition = false,
                    requireConditionalSub = false
                ) ?: return null
            val pending = coarse.worldReads.sortedByDescending { it.foldedBound }
            if (pending.isEmpty()) return null

            val (noConditionUpper, noConditionProven) = joinOracle() ?: return null

            hpStep = REFINE_HP_STEP

            // One refinement sweep at a given support-lambda. The union floor starts AT the
            // no-condition upper: a world at or below it can never move the final bound, so it
            // skips refinement outright (§9.19). Returns the DP side alone (every world carries
            // its refined-or-coarse sound upper) or null on bail/cancellation.
            fun refinementPass(lambda: Long): Long? {
                var passUpper = 0L
                var refinedBest = noConditionUpper
                var refinedCount = 0
                while (refinedCount < pending.size && refinedBest < pending[refinedCount].foldedBound) {
                    if (!shouldContinue()) return null
                    val world = pending[refinedCount]
                    var refined: Result? = null
                    for (di in intArrayOf(10, 4, 1)) {
                        diStep = di
                        refined =
                            bound(
                                params,
                                pool,
                                runes,
                                sublimations,
                                blockGate = false,
                                shouldContinue = shouldContinue,
                                ccSupportLambda = lambda,
                                ccSupportBand = PROD_CC_SUPPORT_BAND,
                                coupleSecondaryItemNegative = true,
                                netSecondaryItemBudget = true,
                                exactNormalSubPacking = true,
                                foldNegativeItemAp = true,
                                foldNegativeMaxMp = true,
                                splitLightWeaponCondition = true,
                                requireConditionalSub = true,
                                worldAssume = world.assume,
                                worldDropCaps = world.assume == null,
                                worldArm = world.arm
                            ) ?: return null
                        if (refined.foldedBound <= refinedBest) break
                    }
                    val refinedBound = requireNotNull(refined).foldedBound
                    passUpper = maxOf(passUpper, refinedBound)
                    refinedBest = maxOf(refinedBest, refinedBound)
                    refinedCount += 1
                }
                val remainingUpper = pending.drop(refinedCount).maxOfOrNull { it.foldedBound } ?: 0L
                return maxOf(passUpper, remainingUpper)
            }

            var dpConditionalUpper = refinementPass(PROD_CC_SUPPORT_LAMBDA) ?: return null
            // The relaxed upper bounds conditional builds too: min it in BEFORE the lambda-0 and
            // CP-probe gates, so a tight relaxed read short-circuits both heavy fallbacks.
            dpConditionalUpper = minOf(dpConditionalUpper, relaxedUpper)
            // §9.22 — lambda is per-shape tuning, not semantics (§6.A4): every lambda >= 0 is an
            // independently sound bound, and lambda=6000 (calibrated on the 245 frontier) measured
            // ACTIVELY loose off it (cra-140: +46.5% at 6000 vs +25.9% at 0). When the first pass
            // did not close onto the oracle, re-sweep at lambda=0 and take the min — same gate as
            // the CP probe, so tight shapes (the S4 frontier) never pay the second pass.
            val lambdaGap = noConditionUpper + (noConditionUpper.toDouble() * CONDITIONAL_PROBE_MIN_GAP).toLong()
            if (dpConditionalUpper > lambdaGap) {
                refinementPass(0L)?.let { dpConditionalUpper = minOf(dpConditionalUpper, it) }
            }

            // §9.21 — the DP and CP-SAT are tight in OPPOSITE regimes: the DP holds on huge pools
            // where CP-SAT's dual stalls (S4-245: DP exact, CP dual 2x), while on small low-level
            // pools the DP's relative looseness explodes (+46% at cra-140) yet a PLAIN full-model
            // CP-SAT solve proves OPTIMAL outright (104 s at cra-140, 2.6k branches — the
            // reification wall is a large-pool phenomenon). So when the DP failed to close onto
            // the oracle, re-solve the FULL model plainly and take the min: OPTIMAL closes the
            // union exactly; a timeout's dual bound is still a sound upper on EVERY build. Do NOT
            // model this probe as {cutoff at oracle+1 + require-a-conditional-sub}: that variant
            // is a strictly HARDER problem (proving near-optimal infeasibility) — measured UNKNOWN
            // after 300 s (+11%) on the very shape the plain solve proves in 104 s. The 10% gate
            // keeps tight shapes (the S4 frontier, iop-200) from ever paying the probe.
            var conditionalUpper = dpConditionalUpper
            val probeGap = noConditionUpper + (noConditionUpper.toDouble() * CONDITIONAL_PROBE_MIN_GAP).toLong()
            if (conditionalUpper > probeGap && shouldContinue()) {
                val probeUpper =
                    try {
                        val probe =
                            WakfuBuildSolver.timedMaxDamageProfileForTest(
                                params = params,
                                equipmentsByItemType = pool,
                                runes = runes,
                                sublimations = sublimations,
                                workers = oracleWorkers,
                                seconds = CONDITIONAL_PROBE_SECONDS,
                                applyDomination = true
                            )
                        // OPTIMAL: objective == bestBound, the union collapses onto the true
                        // optimum. Otherwise the dual still upper-bounds every build.
                        probe.bestBound
                    } catch (e: Exception) {
                        logger.warn(e) { "soft-leg proof: the full-model CP-SAT probe failed — keeping the DP bound" }
                        conditionalUpper
                    }
                conditionalUpper = minOf(conditionalUpper, probeUpper)
            }

            val upper = minOf(maxOf(noConditionUpper, conditionalUpper), relaxedUpper)
            // Memoize only PROVEN-oracle unions: a timeout dual is sound but transiently loose
            // (CPU load), and pinning it would deny a later, better re-proof of the same request.
            return SoftUnionUpper(upper, noConditionUpper, noConditionProven, (System.nanoTime() - t0) / 1_000_000)
                .also { if (noConditionProven) unionMemo[memoKey] = it }
        } finally {
            diStep = DEFAULT_DI_STEP
            hpStep = DEFAULT_HP_STEP
            ccStep = DEFAULT_CC_STEP
        }
    }
}
