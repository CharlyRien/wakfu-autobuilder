package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
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
        // Null is the legacy unsplit read; false excludes the MP→DI ramp carrier, true requires
        // it. Production's outer partition emits false for every ordinary world and one true
        // cap-relaxed cover world.
        val rampRequired: Boolean?,
        val foldedBound: Long,
        val coreBound: Long,
        val states: Int,
        val wallMs: Long,
        // Research instrument: already-computed per-CC-band maxima for this world. This adds no
        // DP state and lets the harness identify which world owns an envelope band.
        val ccBandBounds: Map<Long, Long> = emptyMap(),
        val targetCellBounds: Map<TargetCell, Long> = emptyMap(),
        val targetCellCoreBounds: Map<TargetCell, Long> = emptyMap(),
        val belowTargetBounds: Map<Characteristic, Long> = emptyMap(),
        val penaltyProfile: PenaltyProfile? = null,
        val lightArmBounds: Map<String, Long> = emptyMap(),
    )

    data class TargetCell(
        val ap: Long,
        val mp: Long,
        val cc: Long,
        val hp: Long,
    )

    data class PenaltyProfile(
        val totalExpected: Long,
        val bucketSize: Long,
        val maxIndex: Int,
        val powScale: BigInteger,
        val weights: Map<Characteristic, Long>,
        val targets: Map<Characteristic, Long>,
    ) {
        fun totalCredit(cell: TargetCell): Long =
            weights.getOrDefault(Characteristic.ACTION_POINT, 0L) * cell.ap +
                weights.getOrDefault(Characteristic.MOVEMENT_POINT, 0L) * cell.mp +
                weights.getOrDefault(Characteristic.CRITICAL_HIT, 0L) * cell.cc +
                weights.getOrDefault(Characteristic.HP, 0L) * cell.hp

        fun multiplier(totalCredit: Long): Long {
            val bucket = (totalCredit.coerceIn(1L, totalExpected) / bucketSize).toInt().coerceAtMost(maxIndex)
            return BigInteger
                .valueOf(bucket.toLong())
                .pow(6)
                .divide(powScale)
                .toLong()
        }

        fun folded(
            core: Long,
            totalCredit: Long,
        ): Long =
            (BigInteger.valueOf(core.coerceAtLeast(0L)) * BigInteger.valueOf(multiplier(totalCredit)))
                .min(BigInteger.valueOf(Long.MAX_VALUE))
                .toLong()
    }

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
        // Research instrument: sound folded upper per CC-band low endpoint. World/arm unions
        // combine with max; a multi-anchor envelope can then combine independent supports with
        // min per band before taking the global max.
        val ccBandBounds: Map<Long, Long> = emptyMap(),
        // Research instrument: the same final folds grouped by their target-credit rectangle.
        // This is deliberately not used as a certificate partition yet: rounded DP dimensions
        // can make rectangles overlap real builds, so replacing one requires a separate coverage
        // proof. It does identify every rectangle that can still own the global percentage.
        val targetCellBounds: Map<TargetCell, Long> = emptyMap(),
        val targetCellCoreBounds: Map<TargetCell, Long> = emptyMap(),
        // Sound complement reads: for each requested sheet stat, maximum folded objective under
        // the relaxation `actual credit <= target - 1`. These reads let an exact oracle own the
        // all-targets-satisfied region while DP safely owns every strict-shortfall arm.
        val belowTargetBounds: Map<Characteristic, Long> = emptyMap(),
        val penaltyProfile: PenaltyProfile? = null,
        val lightArmBounds: Map<String, Long> = emptyMap(),
    )

    private fun unionBandBounds(results: List<Result>): Map<Long, Long> =
        results
            .flatMap { it.ccBandBounds.entries }
            .groupingBy { it.key }
            .fold(Long.MIN_VALUE) { best, entry -> maxOf(best, entry.value) }

    private fun unionTargetCellBounds(results: List<Result>): Map<TargetCell, Long> =
        results
            .flatMap { it.targetCellBounds.entries }
            .groupingBy { it.key }
            .fold(Long.MIN_VALUE) { best, entry -> maxOf(best, entry.value) }

    private fun unionTargetCellCoreBounds(results: List<Result>): Map<TargetCell, Long> =
        results
            .flatMap { it.targetCellCoreBounds.entries }
            .groupingBy { it.key }
            .fold(Long.MIN_VALUE) { best, entry -> maxOf(best, entry.value) }

    private fun unionBelowTargetBounds(results: List<Result>): Map<Characteristic, Long> =
        results
            .flatMap { it.belowTargetBounds.entries }
            .groupingBy { it.key }
            .fold(Long.MIN_VALUE) { best, entry -> maxOf(best, entry.value) }

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
        // POSITIVE scenario-secondary mastery of the option (items/subs). Only read when the
        // secZero credit-cap dimension is active (P3 spec v2): the state stores an UPPER bound
        // of the build's item/sub-side S⁺, which soundly caps the μ budget credit at collapse.
        val secPos: Int = 0,
        // NEGATIVE-secondary budget basis of the option (items only; net-other clamped >= 0,
        // same composition as itemSecBudgetMax). Only read when the 2-dim credit budget is
        // active (P3 route a): the state stores an UPPER bound of the build's item-side
        // negative-secondary budget, replacing the global itemSecBudgetMax at collapse.
        val secNeg: Int = 0,
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
                ccLowRaw <= o.ccLowRaw &&
                // Credit-dimension monotonicity: a larger secPos/secNeg yields a >= collapse
                // credit (looser, sound side), so dominance must not drop the larger one.
                secPos >= o.secPos &&
                secNeg <= o.secNeg
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
        // P3 credit-cap dimension (spec v2): 0 = off. When active (secZero reads only), the key
        // carries an UP-rounded S⁺ bucket that soundly caps the μ budget credit at collapse.
        val secBucketCap: Int = 0,
        // P3 route (a) second dimension: UP-rounded item-side negative-secondary budget. 0 = off.
        val negBucketCap: Int = 0,
    ) {
        /**
         * False when a bucket cap overflows its packed-key field (an extreme request/grid combo,
         * e.g. a huge HP target on a fine step). Callers must BAIL (return null) — withholding the
         * badge is always sound; throwing would crash a production proof.
         */
        val fitsPackedKey: Boolean =
            hpBucketCap <= 0x1FF &&
                ccBucketCap <= 0x7F &&
                diBucketCap <= 0x1FFF &&
                blockBucketCap <= 0xF &&
                secBucketCap <= 0x7 &&
                negBucketCap <= 0x7

        // Packed key: negB(3b @53) sec(3b @50) conditional(1b @49) block(4b @45) ramp(1b @44) mpMinus(1b @43)
        //             d(13b @28) ap(5b @23) mp(5b @18) cc(7b @11) hp(9b @2) e(1b @1) r(1b @0)
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
            sec: Int = 0,
            negB: Int = 0,
        ): Long =
            (negB.toLong() shl 53) or (sec.toLong() shl 50) or
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

        fun sec(k: Long): Int = ((k shr 50) and 0x7).toInt()

        fun negB(k: Long): Int = ((k shr 53) and 0x7).toInt()

        fun mpCapOf(mpMinus: Int): Int = (mpCap - mpMinus).coerceAtLeast(0)

        fun withMp(
            k: Long,
            newMp: Int,
        ): Long = key(d(k), ap(k), newMp, cc(k), hp(k), e(k), r(k), mpMinus(k), ramp(k), block(k), conditional(k), sec(k), negB(k))
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
            if (o.conditional) 1 else conditional(k),
            // UP-rounded accumulation (ceil + saturating min at the cap): stored ≥ true S⁺,
            // the sound direction for CAPPING a credit (spec v2).
            if (secBucketCap > 0) (sec(k) + ceilDiv(o.secPos, SEC_DIM_STEP)).coerceAtMost(secBucketCap) else 0,
            // DOWN-rounded accumulation for the budget dimension (correction mode): stored ≤
            // true item negB, so the subtracted over-credit is a lower bound (sound); saturation
            // only weakens the correction toward 0.
            if (negBucketCap > 0) (negB(k) + o.secNeg / SEC_DIM_STEP).coerceAtMost(negBucketCap) else 0
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
        // INTERNAL MP-ramp partition. Null = orchestrate/legacy, false = carrier excluded,
        // true = carrier required in the single cap-relaxed ramp cover world.
        rampWorldRequired: Boolean? = null,
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
        // Research seam: W is priced at critCap during the DP. At collapse, rescale that single
        // non-negative weighted sum by the worst coefficient ratio reachable at the state's
        // actual crit band. This is sound without adding an (M,K) dimension; default OFF until
        // the seeded locks + real-shape campaign pass.
        critAwareCollapse: Boolean = false,
        // Research-only scalar support anchor. Instead of carrying a dense (M,K) dimension,
        // price W at one crit anchor A: (400+A)M + 5AK. Collapse transports that support to the
        // actual crit band with the worst coefficient ratio. Every A>0 is independently sound;
        // taking min across a few anchors approximates the Pareto envelope with unchanged keys.
        critWeightAnchorPercent: Int? = null,
        // Research-only provenance selector. The returned bound remains the global maximum, but
        // bindingState/bindingPath explain this CC-band low endpoint instead of the global winner.
        diagnosticBindingCcBandLow: Long? = null,
        // Research seam: keep the unique MP→DI ramp (Poids Plume in the current catalogue) in
        // the normal-sub knapsack and evaluate it from the path's own signed MP at collapse.
        // If future data introduces several such ramps we bail instead of silently under-counting.
        stateDependentMpRamp: Boolean = false,
        // P3 spec v2 credit-cap dimension (secZero arm only): replace the unconditional per-item
        // μ·negSec budget credit by μ·min(state S⁺, global budget cap) at collapse — the exact
        // identity is value = (wM−μ)·S + μ·min(S, t+negB). A state whose S⁺ bucket SATURATED
        // gets the full budget cap (the up-bound is lost there), i.e. today's behavior.
        secondaryNetDimension: Boolean = false,
        // P3 route (a) — the 2-dim CORRECTION form (safe sentinels both sides). The baseline W
        // keeps its exact per-item μ·negSec credits (no regression possible); the two tracked
        // dimensions only fund a SUBTRACTIVE collapse correction
        //   −μ·max(0, negB_down·STEP + armSecCapRaw − S⁺_up)
        // i.e. reclaim the provable over-credit on paths whose positive scenario-secondary S⁺
        // cannot fund the credited budget (the IOP-200 phantom has S⁺ = 0). negB is DOWN-rounded
        // (≤ true ⇒ the subtracted quantity is a lower bound of the true over-credit — sound),
        // S⁺ is UP-rounded; EITHER bucket saturating degrades the correction toward 0 (baseline).
        // Research seam (WAKFU_S4_SEC_DIM2).
        secondaryNegBudgetDimension: Boolean = false,
        // Research seam: ASSUME/secZero/critZero worlds already imply that a conditional carrier
        // is selected by their exhaustive partition. Do not retain a redundant marker bit there.
        // The base/plain world still tracks the bit normally.
        elideImpliedConditionalMarker: Boolean = false,
        // Research support for SECONDARY_MASTERIES_AT_MOST. If μ is in [0,wMastery], price
        // scenario-secondary supply at (wMastery-μ) and the signed other-secondary budget at μ.
        // This is the Lagrangian upper `wM*S - μ(S+O)`; μ=wM is the historical secZero arm.
        secondarySupportPrice: Long? = null,
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
        val critWeightAnchor = (critWeightAnchorPercent?.toLong() ?: critCap).coerceIn(1L, 100L)
        val wMastery = 400L + critWeightAnchor
        val wCritMastery = 5L * critWeightAnchor

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

        val mpDiRampCandidates =
            if (stateDependentMpRamp && params.useSublimations && "noSubs" !in diag) {
                sublimations.mapNotNull { sub ->
                    if (!sub.solverChoosable) return@mapNotNull null
                    val ramp = sub.perStatStep ?: return@mapNotNull null
                    if (ramp.source == Characteristic.MOVEMENT_POINT &&
                        ramp.target == Characteristic.DAMAGE_INFLICTED &&
                        WakfuBuildSolver.scenarioGateMatches(ramp.scenarioGate, params)
                    ) {
                        sub to ramp
                    } else {
                        null
                    }
                }
            } else {
                emptyList()
            }
        if (mpDiRampCandidates.size > 1) return null
        val mpDiRamp = mpDiRampCandidates.singleOrNull()
        if (rampWorldRequired == true && mpDiRamp == null) return null
        if (foldNegativeMaxMp &&
            sublimations.any { sub ->
                sub.solverChoosable &&
                    sub.rarity != SublimationRarity.NORMAL &&
                    sub.effects
                        .filterIsInstance<SublimationEffect.StatEffect>()
                        .any {
                            (
                                it.characteristic == Characteristic.MAX_MOVEMENT_POINT ||
                                    (rampWorldRequired == true && it.characteristic == Characteristic.MOVEMENT_POINT)
                            ) &&
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
                            .filter {
                                it.characteristic == Characteristic.MAX_MOVEMENT_POINT ||
                                    (rampWorldRequired == true && it.characteristic == Characteristic.MOVEMENT_POINT)
                            }.sumOf { (-minOf(it.magnitudeAtLevel(level), 0)).coerceAtLeast(0) } * sub.maxCopies.coerceAtLeast(1)
                    }
            } else {
                0
            }
        val futureItemMpDebit =
            if (foldNegativeMaxMp) {
                fun maxDebit(type: ItemType): Int =
                    pool[type].orEmpty().maxOfOrNull {
                        val capDebit = -minOf(it.characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0, 0)
                        val flatDebit =
                            if (rampWorldRequired == true) {
                                -minOf(it.characteristics[Characteristic.MOVEMENT_POINT] ?: 0, 0)
                            } else {
                                0
                            }
                        (capDebit + flatDebit).coerceAtLeast(0)
                    } ?: 0

                val ringDebits =
                    pool[ItemType.RING]
                        .orEmpty()
                        .map {
                            val capDebit = -minOf(it.characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0, 0)
                            val flatDebit =
                                if (rampWorldRequired == true) {
                                    -minOf(it.characteristics[Characteristic.MOVEMENT_POINT] ?: 0, 0)
                                } else {
                                    0
                                }
                            (capDebit + flatDebit).coerceAtLeast(0)
                        }.sortedDescending()
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
                val rampRequired: Boolean?,
            )

            val arms =
                buildList {
                    add("plain")
                    if (hasSecCappers) add("secZero")
                    if (hasCritMCappers) add("critZero")
                }
            val specs =
                if (diagnosticBasePlain) {
                    listOf(WorldSpec(null, "plain", null))
                } else {
                    // Base worlds keep the arm partition; each assume-world is priced ONCE with
                    // the "capFree" cover arm (every objective capper staged, no cap, no forcing)
                    // — a sound superset of its three arm reads at a third of the sweep cost
                    // (S4-245: the nine per-arm assume reads were 43 s of the 69 s coarse).
                    val ordinaryRampMode = if (mpDiRamp != null) false else null
                    arms.map { arm -> WorldSpec(null, arm, ordinaryRampMode) } +
                        capSubs.map { assume -> WorldSpec(assume, "capFree", ordinaryRampMode) } +
                        if (mpDiRamp != null) {
                            // Exhaustive world split: all ordinary worlds exclude Poids Plume;
                            // this ONE world requires it and relaxes every other cap condition.
                            listOf(WorldSpec(null, "rampCover", true))
                        } else {
                            emptyList()
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
            // Assume-worlds (one cap sub forced under its threshold) price on a 4× COARSER grid:
            // coarser buckets only merge states under a max, so each bound stays a sound upper —
            // and every measured assume-world lands ~45% under the main worlds (S4-245: 9.5-10.4T
            // vs the 17.702T authority even at 2×), so the extra looseness cannot promote one into
            // the refinement set; if it ever did, the best-first queue re-prices it at DI10.
            // Measured 2026-07-18: at 2× the nine S4 assume-worlds still cost 55 s of the 69 s
            // coarse sweep — the 4× grid is the wall fix.
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
                            diStep = savedDi * 4
                            // Absolute floor: assume-worlds always land far under the main worlds,
                            // so they never need the caller's (possibly refine-grade) HP step.
                            hpStep = maxOf(savedHp * 4, 16_000)
                            ccStep = savedCc * 4
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
                                exactNormalSubPacking = exactNormalSubPacking || spec.rampRequired == true,
                                foldNegativeItemAp = foldNegativeItemAp,
                                foldNegativeMaxMp = foldNegativeMaxMp || spec.rampRequired == true,
                                splitLightWeaponCondition = splitLightWeaponCondition,
                                requireConditionalSub = requireConditionalSub,
                                diagnosticBasePlain = diagnosticBasePlain,
                                worldAssume = spec.assume,
                                worldDropCaps = spec.assume == null,
                                rampWorldRequired = spec.rampRequired,
                                worldArm = spec.arm,
                                critAwareCollapse = critAwareCollapse,
                                critWeightAnchorPercent = critWeightAnchorPercent,
                                diagnosticBindingCcBandLow = diagnosticBindingCcBandLow,
                                stateDependentMpRamp = stateDependentMpRamp,
                                secondaryNetDimension = secondaryNetDimension,
                                secondaryNegBudgetDimension = secondaryNegBudgetDimension,
                                elideImpliedConditionalMarker = elideImpliedConditionalMarker,
                                secondarySupportPrice = secondarySupportPrice
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
                        exactNormalSubPacking = exactNormalSubPacking || bestSpec.rampRequired == true,
                        foldNegativeItemAp = foldNegativeItemAp,
                        foldNegativeMaxMp = foldNegativeMaxMp || bestSpec.rampRequired == true,
                        splitLightWeaponCondition = splitLightWeaponCondition,
                        requireConditionalSub = requireConditionalSub,
                        diagnosticBasePlain = diagnosticBasePlain,
                        worldAssume = bestSpec.assume,
                        worldDropCaps = bestSpec.assume == null,
                        rampWorldRequired = bestSpec.rampRequired,
                        worldArm = bestSpec.arm,
                        critAwareCollapse = critAwareCollapse,
                        critWeightAnchorPercent = critWeightAnchorPercent,
                        diagnosticBindingCcBandLow = diagnosticBindingCcBandLow,
                        stateDependentMpRamp = stateDependentMpRamp,
                        secondaryNetDimension = secondaryNetDimension,
                        secondaryNegBudgetDimension = secondaryNegBudgetDimension,
                        elideImpliedConditionalMarker = elideImpliedConditionalMarker,
                        secondarySupportPrice = secondarySupportPrice
                    ) ?: return null
                } else {
                    best
                }
            val priced = worlds.map { requireNotNull(it.second) }
            val worldReads =
                worlds.map { (spec, result) ->
                    val read = requireNotNull(result)
                    WorldRead(
                        spec.assume,
                        spec.arm,
                        spec.rampRequired,
                        read.foldedBound,
                        read.coreBound,
                        read.states,
                        read.wallMs,
                        read.ccBandBounds,
                        read.targetCellBounds,
                        read.targetCellCoreBounds,
                        read.belowTargetBounds,
                        read.penaltyProfile,
                        read.lightArmBounds
                    )
                }
            return Result(
                best.foldedBound,
                priced.maxOf { it.coreBound },
                priced.sumOf { it.states },
                (System.nanoTime() - t0) / 1_000_000,
                explained.bindingState,
                explained.bindingPath,
                worldReads,
                unionBandBounds(priced),
                unionTargetCellBounds(priced),
                unionTargetCellCoreBounds(priced),
                unionBelowTargetBounds(priced),
                priced.firstNotNullOfOrNull { it.penaltyProfile }
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
                    rampWorldRequired,
                    arm,
                    worldArm,
                    critAwareCollapse,
                    critWeightAnchorPercent,
                    diagnosticBindingCcBandLow,
                    stateDependentMpRamp,
                    secondaryNetDimension,
                    secondaryNegBudgetDimension,
                    elideImpliedConditionalMarker,
                    secondarySupportPrice
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
                explained.bindingPath,
                ccBandBounds = unionBandBounds(priced.map { requireNotNull(it.second) }),
                targetCellBounds = unionTargetCellBounds(priced.map { requireNotNull(it.second) }),
                targetCellCoreBounds = unionTargetCellCoreBounds(priced.map { requireNotNull(it.second) }),
                belowTargetBounds = unionBelowTargetBounds(priced.map { requireNotNull(it.second) }),
                penaltyProfile = priced.firstNotNullOfOrNull { it.second?.penaltyProfile },
                lightArmBounds = priced.associate { (arm, result) -> arm to requireNotNull(result).foldedBound }
            )
        }
        val assumeStat = worldAssume?.let { capStatOf(it) }
        val assumeThreshold = worldAssume?.condition?.value ?: -1
        val armZeroSecondary = worldArm == "secZero"
        val armZeroCritM = worldArm == "critZero"
        // "capFree": stage every objective-capper sub with NO cap and NO forcing — a single sound
        // cover of all three arms of an assume-world (cap-ignored staging is sound, and with both
        // armZero* flags false every mastery line and conversion prices at its full sound
        // ceiling). Used to price assume-worlds once instead of three times.
        val armCapFree = worldArm == "capFree" || worldArm == "rampCover"
        val secDimActive = secondaryNetDimension && armZeroSecondary
        // Correction mode requires the coupled per-item credits to exist (it reclaims from them).
        val secCorrActive = secondaryNegBudgetDimension && armZeroSecondary && coupleSecondaryItemNegative && !secondaryNetDimension
        val secTrackActive = secDimActive || secCorrActive
        val armForcesConditional = armZeroSecondary || armZeroCritM || rampWorldRequired == true
        val secSupportPrice = (secondarySupportPrice ?: wMastery).coerceIn(0L, wMastery)
        val needsConditionalMarker =
            requireConditionalSub &&
                !(elideImpliedConditionalMarker && (worldAssume != null || armForcesConditional))

        val blockAtLeastMax =
            if (blockGate && params.useSublimations && "noSubs" !in diag && "noCondSubs" !in diag) {
                sublimations
                    .filter { it.solverChoosable && it.condition?.type == SublimationConditionType.BLOCK_AT_LEAST }
                    .maxOfOrNull { it.condition?.value ?: 0 } ?: 0
            } else {
                0
            }

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
                assumeCcThresholdRaw = if (assumeStat == Characteristic.CRITICAL_HIT) assumeThreshold else -1,
                secBucketCap = if (secTrackActive) 5 else 0,
                negBucketCap = if (secCorrActive) 5 else 0
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
                    if (armZeroSecondary && c in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS) {
                        (wMastery - secSupportPrice) * v
                    } else {
                        wMastery * v
                    }
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
                // Review fix (2026-07-20): like the skill stage, the CC axis must stay modeled
                // whenever the collapse is crit-aware — the transport's ccHigh must upper-bound
                // the crit REACHABLE via rune shards even without a crit target.
                val axisRelevant = axisChar in targetByChar || (critAwareCollapse && axisChar == Characteristic.CRITICAL_HIT)
                val matching = runes.filter { it.characteristic == axisChar && axisRelevant }
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

        // A real build can socket a scenario-secondary shard whose FULL value exceeds the W the
        // axis credited (the axis prices secondary at wM−μ): its μ complement must ride the S⁺
        // dimension. With uniform shard values the best non-secondary shard dominates and this
        // is zero per shard.
        val runeSecOver: (ItemType, Int) -> Int = { type, lvl ->
            if (params.useRunes && "noRunes" !in diag) {
                val vSecMax =
                    runes
                        .filter {
                            it.characteristic in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS &&
                                (it.characteristic in masteryStats || it.characteristic in randomStats)
                        }.maxOfOrNull { it.valueOn(type, lvl) } ?: 0
                if (vSecMax > 0 && wMastery * vSecMax > runeAxesW(type, lvl)) vSecMax else 0
            } else {
                0
            }
        }

        fun itemPositiveScenarioSecondary(e: Equipment): Int =
            e.characteristics.entries
                .filter {
                    it.key in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS &&
                        (it.key in masteryStats || it.key in randomStats)
                }.sumOf { maxOf(it.value, 0) }

        fun itemOpts(e: Equipment): List<Opt> {
            val negativeSecondary = itemNegativeSecondary(e)
            val otherNegativeSecondary = itemOtherNegativeSecondary(e)
            val otherPositiveSecondary = itemOtherPositiveSecondary(e)
            val base =
                Opt(
                    w =
                        e.characteristics.entries.sumOf { (c, v) -> wOf(c, v) } +
                            if (armZeroSecondary && coupleSecondaryItemNegative && !secDimActive) {
                                secSupportPrice *
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
                        if (rampWorldRequired == true && foldNegativeMaxMp) {
                            e.characteristics[Characteristic.MOVEMENT_POINT] ?: 0
                        } else {
                            statOf(e, Characteristic.MOVEMENT_POINT)
                        } +
                            if (foldNegativeMaxMp) minOf(e.characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0, 0) else 0,
                    cc = statOf(e, Characteristic.CRITICAL_HIT),
                    hp = statOf(e, Characteristic.HP),
                    epic = e.rarity == me.chosante.common.Rarity.EPIC,
                    relic = e.rarity == me.chosante.common.Rarity.RELIC,
                    block = if (blockAtLeastMax > 0) statOf(e, Characteristic.BLOCK_PERCENTAGE) else 0,
                    apLow = if (geo.assumeApThreshold >= 0) itemAp(e) else 0,
                    ccLowRaw = if (geo.assumeCcThresholdRaw >= 0) (e.characteristics[Characteristic.CRITICAL_HIT] ?: 0) else 0,
                    // Populated ONLY when the credit-cap dimension is active: a populated secPos
                    // splits stat-identical ring pairs / weapon combos at distinct(), which
                    // measured as a 2.5-7x refine-read slowdown with the dimension OFF.
                    secPos = if (secTrackActive) itemPositiveScenarioSecondary(e) else 0,
                    // Same composition as the itemSecBudgetMax basis (net-other clamped >= 0):
                    // the state's negB replaces exactly that global sum.
                    secNeg =
                        if (secCorrActive) {
                            (
                                if (netSecondaryItemBudget) {
                                    otherNegativeSecondary - otherPositiveSecondary
                                } else {
                                    negativeSecondary
                                }
                            ).coerceAtLeast(0L).toInt()
                        } else {
                            0
                        },
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
                                    secPos = if (secTrackActive) base.secPos + a0 * runeSecOver(e.itemType, e.level) else 0,
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
            // Review fix (2026-07-20): with a state-dependent MP ramp the collapse read at
            // fold time evaluates the ramp DI on the STORED MP — merging high-MP states down
            // to the TARGET under-credits every real build above it (executed counterexample:
            // −17.9% vs a pinned CP-SAT optimum). Above the ramp's SATURATION point
            // contribution() is constant, so merging there is bit-equivalent: retain up to
            // max(target, saturation) — a full skip doubled the matrix walls for nothing.
            val rampSaturationMp = mpDiRamp?.second?.let { it.threshold + ceilDiv(it.cap, it.perStep) } ?: 0
            val keepMp = maxOf(retainedMp, rampSaturationMp)
            val collapsed = HashMap<Long, Long>(states.size)
            for ((k, wv) in states) {
                val nk = geo.withMp(k, minOf(geo.mp(k), keepMp))
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
                secPos = a.secPos + b.secPos,
                secNeg = a.secNeg + b.secNeg,
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
                            (
                                foldNegativeMaxMp &&
                                    (
                                        (it.characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0) < 0 ||
                                            (
                                                rampWorldRequired == true &&
                                                    (it.characteristics[Characteristic.MOVEMENT_POINT] ?: 0) < 0
                                            )
                                    )
                            )
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
        // Global μ budget cap for the credit-cap dimension: the old total credit ceiling
        // (arm constant + one per-item basis per slot; rings top-2, weapon combo like the MP
        // debit fold). Sound because S ≤ t + negB_true ≤ this cap for every covered build.
        val itemSecBudgetMax: Long =
            if (secDimActive && coupleSecondaryItemNegative) {
                fun basis(e: Equipment): Long =
                    (
                        if (netSecondaryItemBudget) {
                            itemOtherNegativeSecondary(e) - itemOtherPositiveSecondary(e)
                        } else {
                            itemNegativeSecondary(e)
                        }
                    ).coerceAtLeast(0L)

                fun maxBasis(type: ItemType): Long = pool[type].orEmpty().maxOfOrNull(::basis) ?: 0L
                val ringBases =
                    pool[ItemType.RING]
                        .orEmpty()
                        .map(::basis)
                        .sortedDescending()
                val weaponBasis =
                    maxOf(
                        maxBasis(ItemType.TWO_HANDED_WEAPONS),
                        maxBasis(ItemType.ONE_HANDED_WEAPONS) + maxBasis(ItemType.OFF_HAND_WEAPONS)
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
                    }.sumOf(::maxBasis) +
                    (ringBases.getOrNull(0) ?: 0L) +
                    (ringBases.getOrNull(1) ?: 0L) +
                    weaponBasis
            } else {
                0L
            }
        val secDimBudgetCap = armSecCapRaw + itemSecBudgetMax
        val armConstantW =
            (if (secDimActive) 0L else secSupportPrice * armSecCapRaw) + wCritMastery * armCritMCapRaw
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
                val isMpRampCarrier = sub === mpDiRamp?.first
                if (rampWorldRequired == false && isMpRampCarrier) continue
                if ("noCondSubs" in diag && sub.condition != null) continue
                val cond = sub.condition
                val capsObjective =
                    cond != null &&
                        (
                            cond.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ||
                                cond.type == SublimationConditionType.CRITICAL_MASTERY_AT_MOST
                        )
                // AT_MOST cap subs never enter the stages — the world split handles them.
                if (capStatOf(sub) != null && sub !== worldAssume && rampWorldRequired != true) continue
                // Objective-capping subs per ARM: plain excludes them; secZero stages sec-cappers
                // AND critM cappers (cap-ignored — sound); critZero stages critM cappers only.
                if (capsObjective) {
                    val secCapper = cond?.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST
                    val staged =
                        armCapFree || armZeroSecondary || (armZeroCritM && !secCapper)
                    if (!staged) continue
                }
                val blockRequirement =
                    if (blockAtLeastMax > 0 && cond?.type == SublimationConditionType.BLOCK_AT_LEAST) (cond.value ?: 0) else 0
                var opt =
                    Opt(
                        0L,
                        0,
                        requiresBlockAtLeast = blockRequirement,
                        conditional = needsConditionalMarker && cond != null,
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
                                // Only the dedicated ramp world needs the REAL signed MP used by
                                // contribution(). Armure lourde II is a plain -MP line rather than
                                // MAX_MP; dropping it would recreate an unfunded Poids Plume ramp.
                                if (value < 0 &&
                                    rampWorldRequired == true &&
                                    foldNegativeMaxMp &&
                                    exactNormalSubPacking &&
                                    eff.characteristic == Characteristic.MOVEMENT_POINT
                                ) {
                                    opt = opt.copy(mp = opt.mp + value)
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
                                    else ->
                                        opt.copy(
                                            w = opt.w + wOf(eff.characteristic, value),
                                            secPos =
                                                opt.secPos +
                                                    if (secTrackActive &&
                                                        eff.characteristic in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS &&
                                                        (eff.characteristic in masteryStats || eff.characteristic in randomStats)
                                                    ) {
                                                        maxOf(value, 0)
                                                    } else {
                                                        0
                                                    }
                                        )
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
                        (!opt.ramp || (exactNormalSubPacking && stateDependentMpRamp)) &&
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
                                (!it.opt.ramp || (exactNormalSubPacking && stateDependentMpRamp)) &&
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
                        val ramp: Boolean,
                    )

                    data class PackedKey(
                        val d: Int,
                        val ap: Int,
                        val mp: Int,
                        val cc: Int,
                        val hp: Int,
                        val block: Int,
                        val conditional: Boolean,
                        val ramp: Boolean,
                    )

                    val zero = ExactSubKey(0, 0, 0, 0, 0, 0, 0, false, false)
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
                                    conditional = k.conditional || o.conditional,
                                    ramp = k.ramp || o.ramp
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
                                conditional = k.conditional,
                                ramp = k.ramp
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
                                ramp = pk.ramp,
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
                    (
                        it.opt.mpCapMinus != 0 ||
                            (it.opt.ramp && !(exactNormalSubPacking && stateDependentMpRamp)) ||
                            it.opt.requiresBlockAtLeast != 0
                    )
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
                                // Review fix (2026-07-20): the crit-aware transport scales W by the
                                // band's ccHigh, which must UPPER-bound the reachable crit — even a
                                // no-crit-target build can allocate crit SKILLS for the (400+c)
                                // leverage, so crit stays a modeled skill dimension whenever the
                                // collapse is crit-aware (the hardened ramp lock caught a 2.9%
                                // under-count from the missing skill crit reach).
                                (critAwareCollapse && c == Characteristic.CRITICAL_HIT) ||
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
                                else ->
                                    acc.copy(
                                        w = acc.w + wOf(skChar, v),
                                        secPos =
                                            acc.secPos +
                                                if (secTrackActive &&
                                                    skChar in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS &&
                                                    (skChar in masteryStats || skChar in randomStats)
                                                ) {
                                                    maxOf(v, 0)
                                                } else {
                                                    0
                                                }
                                    )
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
        var bestBindingFolded = Long.MIN_VALUE
        val ccBandBounds = linkedMapOf<Long, Long>()
        val targetCellBounds = linkedMapOf<TargetCell, Long>()
        val targetCellCoreBounds = linkedMapOf<TargetCell, Long>()
        val belowTargetBounds = linkedMapOf<Characteristic, Long>()

        data class ScoredBand(
            val core: Long,
            val folded: Long,
            val wUpper: Long,
            val ccLow: Long,
            val ccHigh: Long,
            val targetCell: TargetCell,
            val belowTargetFolded: Map<Characteristic, Long>,
        )

        data class FoldResult(
            val maxCore: Long,
            val winner: ScoredBand,
            val bands: List<ScoredBand>,
        )

        for ((k, wv) in states) {
            if (needsConditionalMarker &&
                geo.conditional(k) == 0 &&
                !assumedOpt.conditional &&
                rampWorldRequired != true
            ) {
                continue
            }
            if (rampWorldRequired == true && geo.ramp(k) == 0) continue
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
                // Credit-cap dimension (spec v2): μ·min(S⁺ upper, budget cap). A saturated bucket
                // lost its upper bound — fall back to the full budget cap (today's behavior).
                val secDimCredit =
                    if (secDimActive) {
                        val sUp =
                            if (geo.sec(k) >= geo.secBucketCap) {
                                secDimBudgetCap
                            } else {
                                minOf(
                                    geo.sec(k).toLong() * SEC_DIM_STEP + assumedOpt.secPos + extra.secPos,
                                    secDimBudgetCap
                                )
                            }
                        secSupportPrice * sUp
                    } else if (secCorrActive && geo.sec(k) < geo.secBucketCap) {
                        // Correction mode: the baseline W already credited μ·(negB_exact +
                        // armSecCapRaw); the true needed credit is μ·S ≤ μ·S⁺_up. Reclaim the
                        // provable over-credit −μ·max(0, negB_down + armSecCapRaw − S⁺_up).
                        // A saturated S⁺ bucket (else-branch) loses its upper — correction 0,
                        // i.e. exactly the baseline bound.
                        val sUp = geo.sec(k).toLong() * SEC_DIM_STEP + assumedOpt.secPos + extra.secPos
                        -secSupportPrice *
                            maxOf(0L, geo.negB(k).toLong() * SEC_DIM_STEP + armSecCapRaw - sUp)
                    } else {
                        0L
                    }
                val support =
                    wv + assumedOpt.w + armConstantW + extra.w + secDimCredit +
                        ccSupportLambda * (assumedOpt.cc.coerceAtLeast(0) + extra.cc.coerceAtLeast(0))

                fun scoreBand(
                    supportDerivedW: Long,
                    ccForPenalty: Long,
                    ccLow: Long,
                    ccHigh: Long,
                ): ScoredBand {
                    val cappedW = supportDerivedW.let { if (wCap != null) minOf(it, wCap) else it }.coerceAtLeast(0L)
                    val wUpper =
                        if (!critAwareCollapse || critCap <= 0L) {
                            cappedW
                        } else {
                            // WA = (400+A)M + 5AK at A=critWeightAnchor. For any M,K>=0:
                            // W(c) <= max((400+c)/(400+A), c/A) * WA. The ratio may exceed one
                            // when c>A; it remains a sound transport between scalar supports.
                            val c = ccHigh.coerceIn(0L, critCap)
                            val masteryNumerator = 400L + c
                            val masteryDenominator = 400L + critWeightAnchor
                            val critMasteryNumerator = c
                            val critMasteryDenominator = critWeightAnchor
                            val (numerator, denominator) =
                                if (masteryNumerator * critMasteryDenominator >=
                                    critMasteryNumerator * masteryDenominator
                                ) {
                                    masteryNumerator to masteryDenominator
                                } else {
                                    critMasteryNumerator to critMasteryDenominator
                                }
                            // Review fix (2026-07-20): the DOWN-scaling branch (c < anchor ⇒ ratio < 1)
                            // is only sound if EVERY W term is anchor-conforming ((400+A)·M or 5A·K) —
                            // empirically refuted by the hardened ramp lock (a crit-3 build's real
                            // score exceeded the down-scaled W by 1.4%: W carries terms that do not
                            // shrink with c). Never scale W below its anchor pricing; production
                            // shapes run at c = anchor (ratio 1) and are unaffected.
                            if (numerator >= denominator) {
                                (cappedW * numerator + denominator - 1L) / denominator
                            } else {
                                cappedW
                            }
                        }
                    val grawUb = wUpper.coerceIn(0L, DAMAGE_GRAW_MAX)
                    val perHit = ((100L + di) * grawUb).coerceAtMost(DAMAGE_SCORE_ABS_MAX)
                    val perHitScaled = (perHit / PERHIT_DOWNSCALE).coerceAtMost(PERHIT_SCALED_MAX)
                    val throughput = clampedTable[apRead.coerceIn(0L, clampedTable.lastIndex.toLong()).toInt()]
                    val raw = (throughput * perHitScaled).coerceAtMost(ROTATION_RAW_MAX)
                    val core = (raw * resFactor / FINAL_DOWNSCALE).coerceAtMost(DAMAGE_PERTURN_ABS_MAX)
                    if (targets.isEmpty()) {
                        return ScoredBand(core, core, wUpper, ccLow, ccHigh, TargetCell(0L, 0L, 0L, 0L), emptyMap())
                    }
                    val apCredit = minOf(apRead, targetOf(Characteristic.ACTION_POINT))
                    val mpCredit =
                        minOf(
                            geo.mp(k).toLong() + assumedOpt.mp + extra.mp,
                            targetOf(Characteristic.MOVEMENT_POINT)
                        )
                    val ccCredit = minOf(ccForPenalty, targetOf(Characteristic.CRITICAL_HIT))
                    val hpCredit =
                        minOf(
                            geo.hp(k).toLong() * hpStep + assumedOpt.hp + extra.hp,
                            targetOf(Characteristic.HP)
                        )
                    val totalActual =
                        weight(Characteristic.ACTION_POINT) * apCredit +
                            weight(Characteristic.MOVEMENT_POINT) * mpCredit +
                            weight(Characteristic.CRITICAL_HIT) * ccCredit +
                            weight(Characteristic.HP) * hpCredit

                    fun foldedAt(totalCredit: Long): Long {
                        val bucket = (totalCredit.coerceIn(1L, totalExpected) / bucketSize).toInt().coerceAtMost(maxIndex)
                        return core * powTable[bucket]
                    }
                    val credits =
                        mapOf(
                            Characteristic.ACTION_POINT to apCredit,
                            Characteristic.MOVEMENT_POINT to mpCredit,
                            Characteristic.CRITICAL_HIT to ccCredit,
                            Characteristic.HP to hpCredit
                        )
                    val below =
                        credits
                            .mapNotNull { (stat, credit) ->
                                val target = targetOf(stat)
                                if (target <= 0L || (stat == Characteristic.CRITICAL_HIT && ccLow >= target)) {
                                    null
                                } else {
                                    val cappedCredit = minOf(credit, target - 1L)
                                    stat to foldedAt(totalActual - weight(stat) * (credit - cappedCredit))
                                }
                            }.toMap()
                    return ScoredBand(
                        core,
                        foldedAt(totalActual),
                        wUpper,
                        ccLow,
                        ccHigh,
                        TargetCell(apCredit, mpCredit, ccCredit, hpCredit),
                        below
                    )
                }

                if (ccSupportLambda == 0L || (!critAwareCollapse && weight(Characteristic.CRITICAL_HIT) == 0L)) {
                    val scored = scoreBand(support, ccRead, 0L, ccRead)
                    return FoldResult(scored.core, scored, listOf(scored))
                }

                // For a build whose signed CC lies in [lo, hi], positiveCC >= signedCC gives
                // W <= H_lambda - lambda*lo, while pricing its target fold at hi only over-counts.
                // Negative signed CC is covered by the lo=0 rectangle (both W and target credit
                // are then relaxed upward). Values above the target are covered by its last band.
                val ccFoldCap =
                    if (weight(Characteristic.CRITICAL_HIT) == 0L) {
                        critCap
                    } else {
                        targetOf(Characteristic.CRITICAL_HIT)
                    }
                val ccUpper = minOf(ccRead.coerceAtLeast(0L), ccFoldCap)
                var lo = 0L
                var maxCoreForFold = 0L
                var winner: ScoredBand? = null
                val bands = arrayListOf<ScoredBand>()
                while (lo <= ccUpper) {
                    val hi = minOf(ccUpper, lo + ccSupportBand - 1L)
                    val scored = scoreBand(support - ccSupportLambda * lo, hi, lo, hi)
                    bands += scored
                    maxCoreForFold = maxOf(maxCoreForFold, scored.core)
                    if (winner == null || scored.folded > winner.folded) winner = scored
                    lo = hi + 1L
                }
                return FoldResult(maxCoreForFold, requireNotNull(winner), bands)
            }

            fun consider(
                extra: Opt,
                wCap: Long?,
                tag: String,
            ) {
                val folded = foldWith(extra, wCap)
                folded.bands.forEach { band ->
                    ccBandBounds[band.ccLow] = maxOf(ccBandBounds[band.ccLow] ?: Long.MIN_VALUE, band.folded)
                    targetCellBounds[band.targetCell] =
                        maxOf(targetCellBounds[band.targetCell] ?: Long.MIN_VALUE, band.folded)
                    targetCellCoreBounds[band.targetCell] =
                        maxOf(targetCellCoreBounds[band.targetCell] ?: Long.MIN_VALUE, band.core)
                    band.belowTargetFolded.forEach { (stat, bound) ->
                        belowTargetBounds[stat] = maxOf(belowTargetBounds[stat] ?: Long.MIN_VALUE, bound)
                    }
                }
                if (folded.maxCore > bestCore) bestCore = folded.maxCore
                bestFolded = maxOf(bestFolded, folded.winner.folded)
                val bindingWinner =
                    diagnosticBindingCcBandLow?.let { requested -> folded.bands.firstOrNull { it.ccLow == requested } }
                        ?: if (diagnosticBindingCcBandLow == null) folded.winner else return
                if (bindingWinner.folded > bestBindingFolded) {
                    bestBindingFolded = bindingWinner.folded
                    bindingKey = k
                    bindingW = wv
                    bindingState =
                        "supportLambda=$ccSupportLambda support=$wv ccBand=${bindingWinner.ccLow}..${bindingWinner.ccHigh} " +
                        "Wupper=${bindingWinner.wUpper} armConstW=$armConstantW armSecCap=$armSecCapRaw secPrice=$secSupportPrice " +
                        "d=${geo.d(k) * diStep}+ramp$rampDi ap=${geo.ap(k)} mp=${geo.mp(k)} " +
                        "cc=${geo.cc(k) * ccStep} hp=${geo.hp(k) * hpStep} e=${geo.e(k)} r=${geo.r(k)} " +
                        "conditional=${if (geo.conditional(k) == 1 || assumedOpt.conditional || armForcesConditional) 1 else 0} " +
                        "assume=${worldAssume?.name?.fr ?: "-"}$tag core=${bindingWinner.core} folded=${bindingWinner.folded}"
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
        return Result(
            bestFolded,
            bestCore,
            states.size,
            (System.nanoTime() - t0) / 1_000_000,
            bindingState,
            bindingPath,
            ccBandBounds = ccBandBounds,
            targetCellBounds = targetCellBounds,
            targetCellCoreBounds = targetCellCoreBounds,
            belowTargetBounds = belowTargetBounds,
            penaltyProfile =
                if (targets.isEmpty()) {
                    null
                } else {
                    PenaltyProfile(
                        totalExpected,
                        bucketSize,
                        maxIndex,
                        powScale,
                        SUPPORTED_TARGETS.associateWith(::weight),
                        SUPPORTED_TARGETS.associateWith(::targetOf)
                    )
                }
        )
    }

    private val EMPTY_OPT = Opt(0L, 0)

    // ---------------------------------------------------------------------------------------------
    // PRODUCTION soft-leg proof orchestration (plan §9.20) — the hybrid partition union.
    // ---------------------------------------------------------------------------------------------

    /** Sound support-function knees selected by the measured stat scale/arm (§9.22septdecies). */
    private const val PROD_CC_SUPPORT_LAMBDA = 6000L
    private const val PROD_CC_SUPPORT_LAMBDA_LOW = 1500L
    private const val PROD_SECZERO_MID_SUPPORT_LAMBDA = 4000L
    private const val PROD_SECZERO_SECONDARY_SUPPORT_PRICE = 250L

    // Refinement acceptance band vs the search incumbent: below this gap the ProvenWithin badge
    // is already sub-2% and further DI1 passes buy hundredths of a percent for tens of seconds.
    private const val PROD_SOFT_BADGE_ACCEPT_FRACTION = 0.015

    // P3 credit-cap dimension (spec v2): bucket step for the UP-rounded S⁺ state field.
    private const val SEC_DIM_STEP = 250
    private const val PROD_CC_SUPPORT_BAND = 5
    private const val PROD_CRIT_WEIGHT_ANCHOR = 100
    private const val PROD_HIGH_SCALE_LEVEL = 225
    private const val PROD_SECZERO_HIGH_SCALE_LEVEL = 175

    /** The §9.19 adaptive grid: coarse sweep steps and the fine defaults refinements restore. */
    private const val COARSE_DI_STEP = 10
    private const val COARSE_HP_STEP = 4000
    private const val COARSE_CC_STEP = 20
    private const val REFINE_HP_STEP = 2000

    /** Budget for the §9.22 relaxed (conditions-stripped) probe: the corrected v17 relaxation
     *  proves its upper in 2.9 s at cra-140; large pools burn the budget and fall back. */
    private const val RELAXED_PROBE_SECONDS = 45.0

    /** First exact-structure attempt after the free-condition relaxation. CRA-80 and CRA-110
     *  full-catalog proofs closed in 87-125 s with 7-9 nodes, versus monolithic CP-SAT still open
     *  after 120-300 s. The global budget is strict; in this measured low-level regime an unfinished
     *  tree returns its sound frontier dual directly instead of stacking the legacy budgets. */
    private const val CONDITIONAL_WORLD_BB_SECONDS = 180.0

    // Coarse SAFETY NET only — the deterministic budget below is the real (load-invariant)
    // per-node limit and typically binds first (det 60 ≈ 8-25 s wall depending on load).
    private const val CONDITIONAL_WORLD_BB_NODE_SECONDS = 45.0
    private const val CONDITIONAL_WORLD_BB_MAX_NODES = 64

    // Load-invariant per-node deterministic budget (roots measured closing at det 50-70, inner
    // nodes at 20-48; the root gets ×2 inside the driver).
    private const val CONDITIONAL_WORLD_BB_NODE_DET = 60.0

    // Root prognosis: a root dual beyond this band over the incumbent means the reification wall
    // makes the tree unclosable in any bounded budget (iop110-full +142% never closes; cra80-ap10
    // +14% closes in 9 nodes) — bail after one node and use the DP union instead.
    private const val CONDITIONAL_WORLD_BB_ROOT_BAIL = 0.25

    // Extended 110 → 140 (2026-07-18): with k-ary SOS branching + parent-assignment hints the
    // tree closes cra-140 in 6 nodes / 65 s (it did not close at all under the binary chain).
    // 200+ stays out: mixed-node duals are +218% there (the reification wall).
    private const val CONDITIONAL_WORLD_BB_MAX_LEVEL = 140

    /** Budget for the §9.21 full-model CP-SAT probe — paid only on shapes where the DP is loose.
     *  cra-140 proves OPTIMAL in ~104 s cold but needed >300 s on a thermally saturated machine
     *  (§9.22 late-night screens) — 420 s buys the exact badge back on warm hardware; a timeout
     *  still degrades gracefully to the dual bound. */
    private const val CONDITIONAL_PROBE_SECONDS = 420.0

    // Product deadline for the whole soft-proof phase: the open-ended CP legs (the ≤110
    // conditional-world tree and the full-model plain probe) are clipped to what remains of this
    // budget, so every request gets a sound verdict in about two minutes. The DP legs are never
    // clipped — S4-245/IOP-200 close well inside the deadline and never reach the CP legs.
    private const val PROOF_PHASE_DEADLINE_SECONDS = 110.0

    // A clipped CP leg still needs enough time to produce a useful dual.
    private const val MIN_CLIPPED_CP_SECONDS = 20.0

    /** Probe gate: below a 10% DP-vs-oracle gap the DP regime is already tight and the probe's
     *  dual essentially never beats it (iop-200: +2.9% DP, probe bought nothing for 180 s). */
    private const val CONDITIONAL_PROBE_MIN_GAP = 0.10
    private const val FRONTIER_REGION_TOTAL_SECONDS = 120.0
    private const val DEFAULT_DI_STEP = 1
    private const val DEFAULT_HP_STEP = 500
    private const val DEFAULT_CC_STEP = 10

    internal data class FrontierRegionCellRead(
        val regionUpper: Long,
        val hpComplementUpper: Long,
        val cell: TargetCell,
        val hpFloor: Long,
        val oracleStatus: String,
        val oracleRawUpper: Long,
        val oracleWallSeconds: Double,
    )

    internal data class FrontierRegionRead(
        val upper: Long,
        val complementUpper: Long,
        val cells: List<FrontierRegionCellRead>,
    )

    /**
     * Research primitive for the last loose DP rectangle. It is intentionally conservative:
     * activate only when at most four target-credit rectangles are above [incumbent]. Every real
     * build then belongs to one of three exhaustive arms:
     *
     *  1. another AP/MP/CC/HP rectangle (its existing DP fold is retained),
     *  2. the dominant rectangle but HP below [hpFloor] (same core upper, penalty credit capped),
     *  3. the exact AP/MP/CC region with HP >= [hpFloor] (plain/raw CP-SAT upper, multiplied by
     *     the rectangle's worst penalty multiplier).
     *
     * The method returns null rather than guessing when the ledger has multiple offenders.
     */
    internal fun frontierRegionUpper(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        dp: Result,
        incumbent: Long,
        workers: Int,
        seconds: Double,
    ): FrontierRegionRead? {
        if (incumbent <= 0L) return null
        val offenders =
            dp.targetCellBounds
                .filterValues { it > incumbent }
                .entries
                .sortedByDescending { it.value }
        if (offenders.isEmpty() || offenders.size > 4) return null
        val secondsPerCell = seconds / offenders.size
        val profile = dp.penaltyProfile ?: return null
        val hpWeight = profile.weights.getOrDefault(Characteristic.HP, 0L)
        if (offenders.any { it.key.hp < profile.targets.getOrDefault(Characteristic.HP, 0L) }) return null
        val otherCellsUpper =
            dp.targetCellBounds.entries
                .filter { entry -> offenders.none { it.key == entry.key } }
                .maxOfOrNull { it.value } ?: Long.MIN_VALUE

        fun requested(stat: Characteristic): Long = profile.targets.getOrDefault(stat, 0L)

        // Review fix (2026-07-20): the DP's cell coordinates are CREDITED reads — negative item
        // stat lines are clamped at 0 (statOf) and negative sub AP/MP/CC/HP lines are dropped
        // outside assume worlds — so a real build can sit BELOW its covering cell's coordinates
        // and would escape both arm 1 (its state maps to the offender cell) and REAL-stat lower
        // pins (the coverage hole the targetCellBounds doc warns about). The lower pins are
        // therefore posted as CREDITED bounds (`creditedStatLowerBounds`: the CP oracle adds the
        // elided per-item/per-sub debits back onto the sheet stat — the exact per-build crediting
        // identity), keeping them as TIGHT as the cell coordinates while covering every build
        // whose state maps into the cell. HP debits are over-scaled by the maximum reachable
        // skill hp% multiplier (the DP applies hp% to CREDITED flats).
        val skills = params.character.characterSkills
        val maxSkillHpPct =
            listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major).sumOf { branch ->
                val hpPctUnit =
                    branch
                        .getCharacteristics()
                        .flatMap { sk ->
                            if (sk is me.chosante.common.skills.SkillCharacteristic.PairedCharacteristic) listOf(sk.first, sk.second) else listOf(sk)
                        }.filter { it.unitType == me.chosante.common.skills.UnitType.PERCENT && it.characteristic == Characteristic.HP }
                        .maxOfOrNull { it.unitValue } ?: 0
                branch.maxPointsToAssign * hpPctUnit
            }
        // The oracle's `hardConstraints` ALSO posts `actual ≥ target` on the REAL sheet stats from
        // its targetStats (a second door for the same hole), and those targets shape the model
        // (rune axes, domination). Keep them — but at the δ-RELAXED values, where δ is the maximum
        // total debit the credits can elide for one build: the region becomes
        // {real ≥ pin − δ} ∩ {credited ≥ pin}, which still contains every covered build while the
        // credited pins carry the tightness.
        val level = params.character.level

        fun maxElidedDebit(stat: Characteristic): Long {
            fun itemDebit(e: Equipment): Long = (-minOf(e.characteristics[stat] ?: 0, 0)).toLong()

            fun slotMax(type: ItemType): Long = pool[type].orEmpty().maxOfOrNull(::itemDebit) ?: 0L
            val ringDebits = pool[ItemType.RING].orEmpty().map(::itemDebit).sortedDescending()
            val weaponDebit =
                maxOf(
                    slotMax(ItemType.TWO_HANDED_WEAPONS),
                    slotMax(ItemType.ONE_HANDED_WEAPONS) + slotMax(ItemType.OFF_HAND_WEAPONS)
                )
            val itemDebits =
                pool.keys
                    .filter {
                        it !in
                            setOf(
                                ItemType.RING,
                                ItemType.ONE_HANDED_WEAPONS,
                                ItemType.TWO_HANDED_WEAPONS,
                                ItemType.OFF_HAND_WEAPONS
                            )
                    }.sumOf(::slotMax) +
                    (ringDebits.getOrNull(0) ?: 0L) +
                    (ringDebits.getOrNull(1) ?: 0L) +
                    weaponDebit
            val subDebits =
                sublimations
                    .filter { it.solverChoosable }
                    .sumOf { sub ->
                        sub.effects
                            .filterIsInstance<SublimationEffect.StatEffect>()
                            .filter { it.characteristic == stat }
                            .sumOf { (-minOf(it.magnitudeAtLevel(level), 0)).toLong() } * sub.maxCopies.coerceAtLeast(1)
                    }
            val pctFactor = if (stat == Characteristic.HP) 100 + maxSkillHpPct else 100
            return ((itemDebits + subDebits) * pctFactor + 99) / 100
        }
        val elidedByStat =
            mapOf(
                Characteristic.ACTION_POINT to maxElidedDebit(Characteristic.ACTION_POINT),
                Characteristic.MOVEMENT_POINT to maxElidedDebit(Characteristic.MOVEMENT_POINT),
                Characteristic.CRITICAL_HIT to maxElidedDebit(Characteristic.CRITICAL_HIT),
                Characteristic.HP to maxElidedDebit(Characteristic.HP)
            )

        val cellReads =
            offenders.map { offender ->
                val cell = offender.key
                val core = dp.targetCellCoreBounds[cell] ?: return null
                val baseCredit = profile.totalCredit(cell)
                var hpComplementCap = cell.hp
                if (hpWeight > 0L) {
                    while (hpComplementCap >= 0L) {
                        val credit = baseCredit - hpWeight * (cell.hp - hpComplementCap)
                        if (profile.folded(core, credit) <= incumbent) break
                        hpComplementCap--
                    }
                } else {
                    hpComplementCap = -1L
                }
                val hpFloor = (hpComplementCap + 1L).coerceAtLeast(0L)
                val hpComplementUpper =
                    if (hpComplementCap >= 0L) {
                        profile.folded(core, baseCredit - hpWeight * (cell.hp - hpComplementCap))
                    } else {
                        Long.MIN_VALUE
                    }
                val ccLow =
                    if (cell.cc >= requested(Characteristic.CRITICAL_HIT)) {
                        requested(Characteristic.CRITICAL_HIT)
                    } else {
                        (cell.cc / PROD_CC_SUPPORT_BAND) * PROD_CC_SUPPORT_BAND
                    }
                val lower =
                    linkedMapOf(
                        Characteristic.ACTION_POINT to cell.ap,
                        Characteristic.MOVEMENT_POINT to cell.mp,
                        Characteristic.CRITICAL_HIT to ccLow,
                        Characteristic.HP to hpFloor
                    ).filter { (stat, _) -> requested(stat) > 0L }
                val upper =
                    buildMap {
                        if (cell.ap < requested(Characteristic.ACTION_POINT)) put(Characteristic.ACTION_POINT, cell.ap)
                        if (cell.mp < requested(Characteristic.MOVEMENT_POINT)) put(Characteristic.MOVEMENT_POINT, cell.mp)
                        if (cell.cc < requested(Characteristic.CRITICAL_HIT)) put(Characteristic.CRITICAL_HIT, cell.cc)
                    }
                // δ-relaxed REAL targets (coverage through the hardConstraints door, model shape
                // preserved) + TIGHT credited pins (the actual region boundary).
                val relaxedLower = lower.mapValues { (stat, value) -> (value - elidedByStat.getOrDefault(stat, 0L)).coerceAtLeast(0L) }
                val regionTargets =
                    relaxedLower.mapNotNull { (stat, value) -> value.takeIf { it > 0L }?.let { TargetStat(stat, it.toInt()) } }
                val oracleParams = params.copy(targetStats = TargetStats(regionTargets))
                val oracle =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        params = oracleParams,
                        equipmentsByItemType = pool,
                        runes = runes,
                        sublimations = sublimations,
                        workers = workers,
                        seconds = secondsPerCell,
                        applyDomination = relaxedLower.values.all { it > 0L },
                        hardConstraints = true,
                        statLowerBounds = relaxedLower,
                        statUpperBounds = upper,
                        creditedStatLowerBounds = lower,
                        creditedHpPctFactor = 100 + maxSkillHpPct
                    )
                // UNKNOWN normally still carries a finite dual. Treat a missing/negative native
                // sentinel as infinity: folding it to zero would make a timeout unsound.
                val rawUpper =
                    (if (oracle.status == "OPTIMAL") oracle.objective else oracle.bestBound)
                        .takeIf { it >= 0L } ?: Long.MAX_VALUE
                FrontierRegionCellRead(
                    profile.folded(rawUpper, baseCredit),
                    hpComplementUpper,
                    cell,
                    hpFloor,
                    oracle.status,
                    rawUpper,
                    oracle.wallTimeSec
                )
            }
        val complementUpper = maxOf(otherCellsUpper, cellReads.maxOf { it.hpComplementUpper })
        return FrontierRegionRead(
            maxOf(complementUpper, cellReads.maxOf { it.regionUpper }),
            complementUpper,
            cellReads
        )
    }

    /** A sound upper bound on the PENALIZED soft objective over EVERY build (union of both partitions). */
    internal class SoftUnionUpper(
        val upper: Long,
        val noConditionUpper: Long,
        /** True when the no-condition side is a full CP-SAT `OPTIMAL` proof (else its dual bound — looser but sound). */
        val noConditionProven: Boolean,
        val wallMs: Long,
    )

    private val unionMemo = java.util.concurrent.ConcurrentHashMap<List<Any>, SoftUnionUpper>()

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
        // probe below can close outright when its upper meets the incumbent. MIN_VALUE = no early exit.
        incumbentObjective: Long = Long.MIN_VALUE,
        // User-facing progress: invoked with the stage key each time a proof stage completes, so
        // the GUI can narrate the multi-minute soft proof (user request 2026-07-18).
        onPhase: (String) -> Unit = {},
    ): SoftUnionUpper? {
        if (!supportsShape(params)) return null
        // Review fix (2026-07-20): the memo decides the badge, so the key must be EQUALS-checked
        // (the sibling MaxDamageCertificateCache's collision-freedom standard) — a list of the
        // actual values, never joined hashCodes: a 32-bit collision between two different
        // requests would serve one request the other's upper and could mint a wrong badge.
        val memoKey: List<Any> =
            listOf(
                me.chosante.common.WakfuData.VERSION,
                WakfuBuildSolver.CERTIFIER_VERSION,
                params,
                pool.values
                    .flatten()
                    .map { it.equipmentId }
                    .sorted(),
                sublimations.map { it.name.fr }.sorted(),
                runes.size
            )
        unionMemo[memoKey]?.let { return it }
        val t0 = System.nanoTime()

        // Stage stamps: the wall of a 1-3 minute proof must stay attributable without a re-run.
        fun stamp(label: String) {
            logger.info { "soft-leg proof stage=$label elapsedMs=${(System.nanoTime() - t0) / 1_000_000}" }
            onPhase(label)
        }

        fun deadlineSecondsRemaining(): Double =
            (PROOF_PHASE_DEADLINE_SECONDS - (System.nanoTime() - t0) / 1_000_000_000.0)
                .coerceAtLeast(MIN_CLIPPED_CP_SECONDS)

        // §9.22 STEP 0 — the RELAXED probe: strip the conditions (subs kept, slots and credits
        // intact, ZERO reifications). Sound upper on EVERY build: a real build whose conditional
        // subs are inert is covered by its variant without them (same value, feasible here). The
        // v17-corrected relaxation proves a +13.49% upper in 2.9 s at cra-140 (so the union below
        // takes over there); on large pools (S4-245) its dual is also loose. Forced subs never
        // reach this path ([supportsShape] bails), so the stripped credits are always optional.
        // At level >=175 the v22 DP closes S4-245 in 103 s and gives IOP-200 +3.01% in 74 s;
        // the relaxed probe was loose on both and its serial 45 s alone broke the two-minute
        // product target. Keep it on the smaller regimes where it proves in seconds / feeds the
        // low-level world tree, and let the high-level oracle+DP start immediately.
        // LAZY: on the ≤110 route the conditional-world tree usually closes exactly, in which
        // case the 15-45 s relaxed read is never paid at all.
        val relaxedUpper: Long by lazy {
            (
                if (params.character.level >= PROD_SECZERO_HIGH_SCALE_LEVEL) {
                    Long.MAX_VALUE
                } else {
                    try {
                        val relaxed =
                            WakfuBuildSolver.timedMaxDamageProfileForTest(
                                params = params,
                                equipmentsByItemType = pool,
                                runes = runes,
                                sublimations = sublimations.map { it.withRelaxedBuildStaticCondition() },
                                workers = oracleWorkers,
                                // Deadline-clipped like every other CP leg (feca65 measured the
                                // LAZY relaxed read burning its fixed 45 s late in a long proof).
                                seconds = minOf(RELAXED_PROBE_SECONDS, deadlineSecondsRemaining()),
                                applyDomination = true
                            )
                        if (relaxed.status == "OPTIMAL") relaxed.objective else relaxed.bestBound
                    } catch (e: Exception) {
                        logger.warn(e) { "soft-leg proof: the relaxed probe failed — continuing with the union" }
                        Long.MAX_VALUE
                    }
                }
            ).also { stamp("relaxedProbe") }
        }

        val hasConditional =
            params.useSublimations && sublimations.any { it.solverChoosable && it.condition?.type in SUPPORTED_SUB_CONDITIONS }
        val worldTreeEligible =
            hasConditional && incumbentObjective != Long.MIN_VALUE && params.character.level <= CONDITIONAL_WORLD_BB_MAX_LEVEL
        if (!worldTreeEligible) {
            if (relaxedUpper <= incumbentObjective) {
                return SoftUnionUpper(relaxedUpper, relaxedUpper, true, (System.nanoTime() - t0) / 1_000_000)
                    .also { unionMemo[memoKey] = it }
            }
            if (!shouldContinue()) return null
        }

        val noConditionSubs = sublimations.filter { it.condition == null }

        var conditionalWorldUpper = Long.MAX_VALUE
        if (worldTreeEligible) {
            onPhase("worldTree")
            val worldT0 = System.nanoTime()
            val worldProof =
                try {
                    WakfuBuildSolver.conditionalWorldBranchAndBound(
                        params = params,
                        equipmentsByItemType = pool,
                        runes = runes,
                        sublimations = sublimations,
                        incumbentObjective = incumbentObjective,
                        workers = oracleWorkers,
                        // With the root prognosis below, a hopeless tree costs ONE node before
                        // falling through to the DP union — so the closable trees (root dual
                        // within the bail band) may keep their full closure budget.
                        totalSeconds = minOf(CONDITIONAL_WORLD_BB_SECONDS, oracleSeconds),
                        maxSecondsPerNode = CONDITIONAL_WORLD_BB_NODE_SECONDS,
                        // Deterministic node budget (load-INVARIANT): under 15 h of bench load
                        // the 30 s wall root stopped closing and cra80 went erratic (4.45%
                        // instead of ProvenOptimal). Measured dets: roots close at 50-70, inner
                        // nodes at 20-48; 60 (root ×2 inside) covers both with margin while the
                        // wall stays a coarse safety net.
                        deterministicLimitPerNode = CONDITIONAL_WORLD_BB_NODE_DET,
                        maxNodes = CONDITIONAL_WORLD_BB_MAX_NODES,
                        applyDomination = true,
                        rootBailFraction = CONDITIONAL_WORLD_BB_ROOT_BAIL,
                        shouldContinue = shouldContinue
                    )
                } catch (e: Exception) {
                    logger.warn(e) { "soft-leg proof: conditional-world B&B failed — continuing with the DP union" }
                    null
                }
            logger.info {
                "soft-leg proof: conditional-world result=${worldProof?.javaClass?.simpleName ?: "failed"} " +
                    "nodes=${worldProof?.reads?.size ?: 0} wallMs=${(System.nanoTime() - worldT0) / 1_000_000}"
            }
            when (worldProof) {
                is WakfuBuildSolver.ConditionalWorldProof.Proven -> {
                    return SoftUnionUpper(
                        worldProof.upper,
                        worldProof.upper,
                        true,
                        (System.nanoTime() - t0) / 1_000_000
                    ).also { unionMemo[memoKey] = it }
                }
                is WakfuBuildSolver.ConditionalWorldProof.Inconclusive -> {
                    // Generality-matrix fix (iop110-full: Unavailable in 225 s): an inconclusive
                    // tree no longer ends the proof — its frontier joins the union by min and the
                    // pipeline FALLS THROUGH to the DP authorities, which handle exactly the
                    // shapes whose mixed-node duals are reification-walled. The old budget-
                    // stacking concern is contained by the root prognosis (a hopeless tree costs
                    // one node) and the proof-phase deadline on the CP legs.
                    conditionalWorldUpper = worldProof.upper
                }
                is WakfuBuildSolver.ConditionalWorldProof.Counterexample -> {
                    logger.info {
                        "soft-leg proof: conditional-world B&B found exact objective " +
                            "${worldProof.objective} above incumbent=$incumbentObjective"
                    }
                    return SoftUnionUpper(relaxedUpper, relaxedUpper, false, (System.nanoTime() - t0) / 1_000_000)
                }
                null -> Unit
            }
        }
        if (!shouldContinue()) return null

        // Above 175, relaunching CP-SAT beside the parallel DP is the wrong fallback: 8 CP workers
        // stretch S4 to 178 s, while 2/4 workers fail to prove in 240 s. A no-condition DP on the
        // filtered catalogue is itself a sound partition upper and takes ~12 s at S4 (HP=4000).
        // This follows the product order: main CP-SAT first; if it stalls, certificate only.
        val highLevelNoCondition =
            if (params.character.level >= PROD_SECZERO_HIGH_SCALE_LEVEL) {
                diStep = 1
                hpStep = 4000
                ccStep = COARSE_CC_STEP
                bound(
                    params,
                    pool,
                    runes,
                    noConditionSubs,
                    blockGate = false,
                    shouldContinue = shouldContinue,
                    ccSupportLambda =
                        if (params.character.level >= PROD_HIGH_SCALE_LEVEL) {
                            PROD_CC_SUPPORT_LAMBDA
                        } else {
                            PROD_CC_SUPPORT_LAMBDA_LOW
                        },
                    ccSupportBand = PROD_CC_SUPPORT_BAND,
                    coupleSecondaryItemNegative = true,
                    netSecondaryItemBudget = true,
                    exactNormalSubPacking = true,
                    foldNegativeItemAp = true,
                    foldNegativeMaxMp = true,
                    splitLightWeaponCondition = false,
                    requireConditionalSub = false,
                    critAwareCollapse = true,
                    critWeightAnchorPercent = PROD_CRIT_WEIGHT_ANCHOR,
                    stateDependentMpRamp = true
                ) ?: return null
            } else {
                null
            }
        val highLevelNoConditionUpper =
            highLevelNoCondition?.let { dp ->
                val regional =
                    if (incumbentObjective > 0L && dp.foldedBound > incumbentObjective) {
                        try {
                            frontierRegionUpper(
                                params,
                                pool,
                                runes,
                                noConditionSubs,
                                dp,
                                incumbentObjective,
                                oracleWorkers,
                                minOf(FRONTIER_REGION_TOTAL_SECONDS, oracleSeconds)
                            )
                        } catch (e: Exception) {
                            logger.warn(e) {
                                "soft-leg proof: no-condition frontier refinement failed — keeping the DP upper"
                            }
                            null
                        }
                    } else {
                        null
                    }
                minOf(dp.foldedBound, regional?.upper ?: Long.MAX_VALUE)
            }
        if (highLevelNoCondition != null) stamp("noConditionDp+region")
        // Started AFTER the B&B (starting it before stole cores from a PROVING tree: cra80-ap10
        // regressed 41 s → 176 s at even half workers). The LAZY join below still overlaps this
        // solve with the whole DP phase, so the old post-oracle serial hole stays gone (feca65:
        // DP 60-90 s > the ~39 s oracle solve — the final join is instant).
        val oracleFuture =
            if (highLevelNoCondition == null) {
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
            } else {
                null
            }

        fun joinOracle(): Pair<Long, Boolean>? {
            highLevelNoConditionUpper?.let { upper ->
                // `true` here means this partition authority alone is at/below the known feasible
                // full-model incumbent; the final max still decides global optimality.
                return upper to (upper <= incumbentObjective)
            }
            return try {
                val profile = requireNotNull(oracleFuture).join()
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
        }

        try {
            if (!hasConditional) {
                // The active catalog carries no conditional sublimation: the no-condition model IS
                // the full model and its bound alone covers every build.
                val (upper, proven) = joinOracle() ?: return null
                return SoftUnionUpper(upper, upper, proven, (System.nanoTime() - t0) / 1_000_000)
                    .also { if (proven) unionMemo[memoKey] = it }
            }

            val highScale = params.character.level >= PROD_HIGH_SCALE_LEVEL
            val coarseLambda = if (highScale) PROD_CC_SUPPORT_LAMBDA else PROD_CC_SUPPORT_LAMBDA_LOW
            diStep = COARSE_DI_STEP
            // NOTE (measured 2026-07-18): a refine-grade hp step here left every S4/IOP coarse
            // bound BIT-IDENTICAL for +14% states — the binding coarse path overshoots the HP
            // target, so the bucketed HP penalty never engages. The coarse looseness lives in the
            // seams (light-arm split / MP fold), not the HP grid.
            hpStep = if (params.character.level >= PROD_SECZERO_HIGH_SCALE_LEVEL) COARSE_HP_STEP else 2000
            ccStep = COARSE_CC_STEP
            val coarse =
                bound(
                    params,
                    pool,
                    runes,
                    sublimations,
                    blockGate = false,
                    shouldContinue = shouldContinue,
                    ccSupportLambda = coarseLambda,
                    ccSupportBand = PROD_CC_SUPPORT_BAND,
                    coupleSecondaryItemNegative = true,
                    netSecondaryItemBudget = true,
                    exactNormalSubPacking = true,
                    foldNegativeItemAp = true,
                    // The coarse pass keeps the cheaper optimistic MP read and unsplit light
                    // weapon (both sound uppers); only contenders pay the fine seams below.
                    foldNegativeMaxMp = false,
                    splitLightWeaponCondition = false,
                    requireConditionalSub = false,
                    // Split away every no-ramp path cheaply; the one required-ramp cover world
                    // overrides its own signed-MP/exact-normal settings inside the orchestrator.
                    stateDependentMpRamp = true
                ) ?: return null
            val pending = coarse.worldReads.sortedByDescending { it.foldedBound }
            if (pending.isEmpty()) return null
            stamp("coarse")
            logger.info {
                "soft-leg proof coarse worlds=${pending.size} states=${coarse.states} " +
                    pending.joinToString(" ") {
                        "${it.arm}/${it.assume?.name?.fr ?: "base"}=${it.foldedBound}@${it.wallMs}ms"
                    }
            }

            // LAZY oracle join (feca65's ~35-45 s serial hole): the DP queue only needs a STOP
            // threshold, and the search incumbent is a sound one — a world at or under the
            // incumbent cannot own the badge (the final union max is at least the no-condition
            // authority, itself >= the incumbent). So while an incumbent exists, let the oracle
            // keep solving in the background through the WHOLE DP phase and join it only for the
            // final assembly below. Without an incumbent (test paths), join now.
            // At >=175 the authority is the synchronous DP+region read — no hole to hide, join
            // now (it can also be TIGHTER than the incumbent-based stop, so the queue stops
            // earlier there). The lazy path only serves the <175 oracle-CP future.
            val earlyJoined =
                if (incumbentObjective > 0L && highLevelNoCondition == null) {
                    null
                } else {
                    joinOracle() ?: return null
                }
            val noConditionUpper = earlyJoined?.first ?: incumbentObjective

            hpStep = if (params.character.level >= PROD_SECZERO_HIGH_SCALE_LEVEL) REFINE_HP_STEP else 1000

            fun supportLambda(world: WorldRead): Long =
                when {
                    world.arm == "secZero" && params.character.level >= PROD_HIGH_SCALE_LEVEL ->
                        PROD_CC_SUPPORT_LAMBDA
                    world.arm == "secZero" && params.character.level >= PROD_SECZERO_HIGH_SCALE_LEVEL ->
                        PROD_SECZERO_MID_SUPPORT_LAMBDA
                    highScale -> PROD_CC_SUPPORT_LAMBDA
                    else -> PROD_CC_SUPPORT_LAMBDA_LOW
                }

            // Stop refining once every world is within this fraction of the search incumbent:
            // the badge then reads e.g. "proven within 1.1%" instead of "0.7%", for tens of
            // seconds less wall. Only meaningful when a real incumbent exists.
            val refinementFloor =
                if (incumbentObjective > 0L) {
                    maxOf(
                        noConditionUpper,
                        incumbentObjective + (incumbentObjective.toDouble() * PROD_SOFT_BADGE_ACCEPT_FRACTION).toLong()
                    )
                } else {
                    noConditionUpper
                }

            // Best-first grid queue (§9.22septdecies). Candidate state survives support-envelope
            // refinements: after μ tightens secZero, resume the SAME queue so a newly exposed
            // plain/critZero contender pays only its missing DI tiers.
            data class Candidate(
                val world: WorldRead,
                var upper: Long,
                var tier: Int = -1,
                // One-shot alternate-λ read at DI10 before any deeper descent (LOW-λ worlds only):
                // iop215 measured plain paying DI1@λ1500 43.7 s, staying above the floor, then the
                // post-μ calibration re-paying DI1@λ4000 — while the alternate at DI10 closes it
                // for ~13 s.
                var altTried: Boolean = false,
                val lightArmUppers: MutableMap<String, Long> = world.lightArmBounds.toMutableMap(),
            )

            val tiers = intArrayOf(10, 4, 1)

            // Identical (world, tier, λ, μ, arm) reads are deterministic — the queue-resume after
            // the capFree re-split re-pays the λ-calibration read otherwise (measured 2.7 s dupe
            // on enutrof: critZero/base DI10 λ4000 priced twice).
            val refineMemo = HashMap<List<Any?>, Result>()

            fun refineWorld(
                world: WorldRead,
                di: Int,
                lambdaOverride: Long? = null,
                secondaryPrice: Long? = null,
                lightArm: String? = null,
                armOverride: String? = null,
            ): Result? {
                val memoKey = listOf(world, di, lambdaOverride ?: supportLambda(world), secondaryPrice, lightArm, armOverride)
                refineMemo[memoKey]?.let { return it }
                diStep = di
                val rt0 = System.nanoTime()

                fun logRefine(r: Result?) =
                    logger.info {
                        "soft-leg proof refine world=${armOverride ?: world.arm}/${world.assume?.name?.fr ?: "base"} " +
                            "ramp=${world.rampRequired ?: "legacy"} di=$di " +
                            "lambda=${lambdaOverride ?: supportLambda(world)} mu=${secondaryPrice ?: "-"} " +
                            "lightArm=${lightArm ?: "both"} bound=${r?.foldedBound} " +
                            "wallMs=${(System.nanoTime() - rt0) / 1_000_000}"
                    }
                return bound(
                    params,
                    pool,
                    runes,
                    sublimations,
                    blockGate = false,
                    shouldContinue = shouldContinue,
                    ccSupportLambda = lambdaOverride ?: supportLambda(world),
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
                    rampWorldRequired = world.rampRequired,
                    lightWeaponArm = lightArm,
                    worldArm = armOverride ?: world.arm,
                    critAwareCollapse = true,
                    critWeightAnchorPercent = PROD_CRIT_WEIGHT_ANCHOR,
                    stateDependentMpRamp = true,
                    elideImpliedConditionalMarker = true,
                    secondarySupportPrice = secondaryPrice
                    // NOTE (measured 2026-07-18): secondaryNetDimension is a NO-GO here — the
                    // 1-dim credit-cap with a GLOBAL budget makes positive scenario-secondary
                    // effectively full-price and a new S⁺-rich binding path emerges ABOVE the
                    // coupled per-item pricing (IOP secZero DI1: 9.933T vs 8.9718T). The exact
                    // form needs BOTH S⁺ and negB per state (~25x states). Kept as a research
                    // seam; production stays on the coupled per-item credits.
                ).also {
                    logRefine(it)
                    if (it != null) refineMemo[memoKey] = it
                }
            }

            fun intersect(
                candidate: Candidate,
                refined: Result,
            ) {
                candidate.upper = minOf(candidate.upper, refined.foldedBound)
                refined.lightArmBounds.forEach { (arm, upper) ->
                    candidate.lightArmUppers.merge(arm, upper, ::minOf)
                }
                if (candidate.lightArmUppers.isNotEmpty()) {
                    candidate.upper = minOf(candidate.upper, candidate.lightArmUppers.values.max())
                }
            }

            // [lambdaOverride] retains the independently-sound λ=0 fallback for loose shapes.
            fun refinementPass(
                candidates: List<Candidate>,
                lambdaOverride: Long? = null,
            ): Long? {
                while (true) {
                    if (!shouldContinue()) return null
                    val top = candidates.maxBy { it.upper }
                    if (top.upper <= noConditionUpper) return noConditionUpper
                    // Product acceptance floor: the DP can never fall below a conditional-carrying
                    // optimum, so a sub-percent ProvenWithin badge is this route's terminal state.
                    // Once every world is within the acceptance band of the search incumbent, stop
                    // paying DI1 passes for hundredths of a percent — reporting the current max is
                    // sound (it is a valid upper; stopping early only loosens the displayed %).
                    // Guarded to worlds already past one fine read (tier >= 0): a COARSE bound
                    // landing inside the band must still pay its DI10 pass, else a shape whose
                    // fine reads close onto an exact authority (S4-245) would trade its
                    // ProvenOptimal badge for a ProvenWithin stop.
                    if (top.tier >= 0 && top.upper <= refinementFloor) return maxOf(noConditionUpper, top.upper)
                    // Before descending a LOW-λ world past DI10, try the alternate λ at the SAME
                    // grid once — each λ is independently sound and the cheap read often closes
                    // the world outright (per-request λ auto-calibration, wall side).
                    if (lambdaOverride == null &&
                        top.tier == 0 &&
                        !top.altTried &&
                        supportLambda(top.world) == PROD_CC_SUPPORT_LAMBDA_LOW
                    ) {
                        top.altTried = true
                        val alt = refineWorld(top.world, tiers[0], lambdaOverride = PROD_SECZERO_MID_SUPPORT_LAMBDA) ?: return null
                        intersect(top, alt)
                        continue
                    }
                    if (top.tier == tiers.lastIndex) return maxOf(noConditionUpper, top.upper)
                    top.tier =
                        if (top.tier >= 0 && tiers[top.tier] == 10) {
                            // Every world jumps DI10→DI1: the DI4 tier measured as pure wall at
                            // BOTH scales (IOP-200 plain: DI4 9.060T vs its own DI10 9.005T;
                            // enutrof125 plain: DI4 2460T vs DI10 2440T, critZero 2388T vs 2370T).
                            tiers.lastIndex
                        } else {
                            top.tier + 1
                        }
                    // NOTE (measured 2026-07-18): an UNSPLIT-first DI10 scout is a NO-GO — the
                    // light-arm split IS the binding tightener (S4 plain: 18.13T unsplit vs
                    // 17.24T split), so the scout rarely clears the floor and its cost stacks on
                    // top (S4 +7.6 s, IOP +13 s).
                    // NOTE (measured 2026-07-18): refining a capFree world through its exact
                    // three-arm partition instead of the merged cover read is a NO-GO in the
                    // QUEUE — at DI10 the arms cost MORE than the cover (enutrof Mesure III:
                    // 28.8+12.0+14.5 s vs 43.6 s merged). The re-split stays where it is
                    // (post-queue, DI1, only for worlds still above the floor).
                    val refined = refineWorld(top.world, tiers[top.tier], lambdaOverride) ?: return null
                    // The coarse/current and refined reads are independent sound uppers on the
                    // same world; their min is sound and guards against non-monotone grid rounding.
                    intersect(top, refined)
                }
            }

            val primaryCandidates = pending.map { Candidate(it, it.foldedBound) }
            var dpConditionalUpper = refinementPass(primaryCandidates) ?: return null
            stamp("primaryRefinement")

            // Per-request λ auto-calibration (user request 2026-07-18: "find the proving means
            // per request automatically"). The crit-support λ is per-shape tuning and the fixed
            // low-band constant CAN be miscalibrated (IOP-200: plain 9.005T@λ1500 vs 8.751T@λ4000
            // at DI10 — the world drops BELOW the exact authority at the alternate λ). Any λ ≥ 0
            // is independently sound, so re-read at the alternate λ and min-intersect — but ONLY
            // the world currently OWNING the union max (the badge owner), iteratively: alternate
            // reads on non-owners cannot change the verdict and only pay wall. Runs after the μ
            // support pass below so the owner is judged on FINAL world bounds.
            fun lambdaAutoCalibration(): Long? {
                val altTried = HashSet<WorldRead>()
                while (true) {
                    val owner = primaryCandidates.maxBy { it.upper }
                    if (owner.upper <= noConditionUpper) break
                    if (owner.tier < 0 ||
                        supportLambda(owner.world) != PROD_CC_SUPPORT_LAMBDA_LOW ||
                        !altTried.add(owner.world)
                    ) {
                        break
                    }
                    if (!shouldContinue()) return null
                    refineWorld(owner.world, tiers[owner.tier.coerceAtLeast(0)], lambdaOverride = PROD_SECZERO_MID_SUPPORT_LAMBDA)
                        ?.let { intersect(owner, it) } ?: return null
                }
                return maxOf(noConditionUpper, primaryCandidates.maxOf { it.upper })
            }
            // A second Lagrangian projection of SECONDARY_AT_MOST. μ=500 is the historical
            // budget-only secZero arm; μ=250 also prices actual scenario-secondary supply and
            // closes IOP-200's invented mastery. Refine only secZero worlds that can still beat
            // the no-condition authority, intersect per world, then resume the existing queue.
            // Level gate removed (was >=175): the per-world filter below is the real gate, and
            // the xelor155 probe measured mu=250 tightening secZero DI1 by 1.1% at level 155 too.
            if (dpConditionalUpper > refinementFloor) {
                primaryCandidates
                    .filter { it.world.arm == "secZero" && it.upper > refinementFloor }
                    .sortedByDescending { it.upper }
                    .forEach { candidate ->
                        if (!shouldContinue()) return null
                        if (candidate.lightArmUppers.isEmpty()) {
                            val supported =
                                refineWorld(
                                    candidate.world,
                                    tiers.last(),
                                    secondaryPrice = PROD_SECZERO_SECONDARY_SUPPORT_PRICE
                                ) ?: return null
                            intersect(candidate, supported)
                        } else {
                            candidate.lightArmUppers
                                .filterValues { it > refinementFloor }
                                .keys
                                .toList()
                                .forEach { lightArm ->
                                    val supported =
                                        refineWorld(
                                            candidate.world,
                                            tiers.last(),
                                            secondaryPrice = PROD_SECZERO_SECONDARY_SUPPORT_PRICE,
                                            lightArm = lightArm
                                        ) ?: return null
                                    candidate.lightArmUppers.merge(lightArm, supported.foldedBound, ::minOf)
                                }
                            candidate.upper =
                                minOf(candidate.upper, candidate.lightArmUppers.values.max())
                        }
                        candidate.tier = tiers.lastIndex
                    }
                // NB: do NOT min with `relaxedUpper` here — that forces the LAZY CP probe
                // (20 s+) regardless of gap; the gated consultation after the capFree
                // re-split is the only place allowed to pay it.
                dpConditionalUpper =
                    minOf(
                        dpConditionalUpper,
                        refinementPass(primaryCandidates) ?: return null
                    )
                stamp("secondarySupportPass")
            }
            dpConditionalUpper = minOf(dpConditionalUpper, lambdaAutoCalibration() ?: return null)
            stamp("lambdaAutoCalibration")
            // capFree re-split (matrix sweep-3 fix): the merged assume cover is priced with NO
            // caps, which is fine while it stays under the authority (S4/IOP: ~45% under, zero
            // cost) but at small/mid levels it can OWN the badge (xelor155: capFree/Mesure III
            // +12.87%). Its exact three-arm partition is always available — re-price any capFree
            // world still above the floor per arm and take the max (a sound upper of the same
            // world; min with the cover read).
            run {
                val loose =
                    primaryCandidates
                        .filter { it.world.arm == "capFree" && it.upper > refinementFloor }
                        .sortedByDescending { it.upper }
                for (candidate in loose) {
                    if (!shouldContinue()) return null
                    var resplit = Long.MIN_VALUE
                    for (arm in listOf("plain", "secZero", "critZero")) {
                        val read =
                            refineWorld(candidate.world, tiers.last(), armOverride = arm)
                                ?: return null
                        resplit = maxOf(resplit, read.foldedBound)
                    }
                    if (resplit > Long.MIN_VALUE) {
                        candidate.upper = minOf(candidate.upper, resplit)
                        candidate.tier = tiers.lastIndex
                    }
                }
                // RESUME the queue: the re-split may hand the union max to a world still at a
                // coarse tier (feca65: secZero/base owned the badge at DI10 only — its DI1 read
                // costs ~2 s at that level and was never paid).
                dpConditionalUpper =
                    minOf(
                        dpConditionalUpper,
                        refinementPass(primaryCandidates) ?: return null,
                        maxOf(noConditionUpper, primaryCandidates.maxOf { it.upper })
                    )
            }
            stamp("capFreeResplit")
            // The DP phase is over — join the REAL oracle authority for the final assembly (the
            // queue above only needed the provisional incumbent stop threshold).
            val (oracleUpper, noConditionProven) = earlyJoined ?: (joinOracle() ?: return null)
            stamp("oracleJoin")
            // The relaxed upper bounds conditional builds too — but touching the LAZY probe pays
            // its CP budget, so consult it only AFTER every cheap tightening pass (feca65: gated
            // before the capFree re-split the union was still +19.8% and the probe burned 31 s;
            // after it the union sits at +9.3% and the probe is never paid).
            if (dpConditionalUpper > oracleUpper + (oracleUpper.toDouble() * CONDITIONAL_PROBE_MIN_GAP).toLong()) {
                dpConditionalUpper = minOf(dpConditionalUpper, relaxedUpper)
            }
            // §9.22 — lambda is per-shape tuning, not semantics (§6.A4): every lambda >= 0 is an
            // independently sound bound, and lambda=6000 (calibrated on the 245 frontier) measured
            // ACTIVELY loose off it (cra-140: +46.5% at 6000 vs +25.9% at 0). When the first pass
            // did not close onto the oracle, re-sweep at lambda=0 and take the min — same gate as
            // the CP probe, so tight shapes (the S4 frontier) never pay the second pass.
            val lambdaGap = oracleUpper + (oracleUpper.toDouble() * CONDITIONAL_PROBE_MIN_GAP).toLong()
            if (dpConditionalUpper > lambdaGap) {
                val lambdaZeroCandidates = pending.map { Candidate(it, it.foldedBound) }
                refinementPass(lambdaZeroCandidates, lambdaOverride = 0L)
                    ?.let { dpConditionalUpper = minOf(dpConditionalUpper, it) }
                stamp("lambdaZeroPass")
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
            val probeGap = oracleUpper + (oracleUpper.toDouble() * CONDITIONAL_PROBE_MIN_GAP).toLong()
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
                                seconds = minOf(CONDITIONAL_PROBE_SECONDS, deadlineSecondsRemaining()),
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
                stamp("plainProbe")
            }

            // Same lazy-probe gate as above: only consult the relaxed read when the union is
            // still >10% loose (at >=175 the lazy body is a free MAX_VALUE either way).
            var upper = minOf(maxOf(oracleUpper, conditionalUpper), conditionalWorldUpper)
            if (upper > oracleUpper + (oracleUpper.toDouble() * CONDITIONAL_PROBE_MIN_GAP).toLong()) {
                upper = minOf(upper, relaxedUpper)
            }
            // Memoize only PROVEN-oracle unions: a timeout dual is sound but transiently loose
            // (CPU load), and pinning it would deny a later, better re-proof of the same request.
            return SoftUnionUpper(upper, oracleUpper, noConditionProven, (System.nanoTime() - t0) / 1_000_000)
                .also { if (noConditionProven) unionMemo[memoKey] = it }
        } finally {
            diStep = DEFAULT_DI_STEP
            hpStep = DEFAULT_HP_STEP
            ccStep = DEFAULT_CC_STEP
        }
    }
}
