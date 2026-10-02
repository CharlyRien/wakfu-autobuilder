package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.scaledWeight
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.SkillCharacteristic
import java.math.BigInteger
import kotlin.math.ceil

/**
 * M3-v2 PROTOTYPE (plan §8.9 amendment, maintainer GO 2026-07-12) — a TARGET-AWARE sound upper
 * bound on the most-masteries SOFT folded objective:
 *
 *   `⌊max(M,0) × (100 + clamp(D)) / 100⌋ × power6(bucket(totalActual)) × SCALE + (SCALE − 1)`
 *
 * (`power6` is the solver's own [penaltyMultiplier], floor at 1 included.)
 *
 * [MostMasteriesBoundPrototype] (v1) bounded the unpenalized core only, so any targets-met
 * incumbent sat below it by the crit/AP-dump gap (+2.6% on F5). v2 tracks the required-target
 * achievements INSIDE the DP state, so mastery bought by dumping targets pays its penalty bucket:
 * state = (DI, AP, MP, CC, HP, RANGE, epicItem, relicItem) → best M, with the achievement dims SATURATED
 * at their target (per-stat clamp in `totalActualScore`) and BUCKETED UP (an over-count of the
 * achievement can only raise the multiplier — sound). A 0-valued required row of ANY stat (the GUI's
 * default "wind resistance 0" / "dodge 0") gets no dim: the model weighs it 0 in the penalty's actual
 * AND expected sums and the hard leg skips it, so skipping it is exact — it only keeps the objective
 * folded, like the model's fold predicate.
 *
 * Sound-by-construction relaxations (each only ever RAISES the bound):
 *  - negative stat lines dropped everywhere — except a sublimation's DI, which is NET per sub (a build
 *    takes the sub whole) and kept signed inside the exact normal-sub packing, and the assume worlds'
 *    LOW reads, which take negative lines on purpose;
 *  - per-item rune compositions are EXACT over the socket count and the available rune axes
 *    (mixing mastery + target runes on one item is a real build shape, so omitting mixes would
 *    UNDER-count — they are enumerated);
 *  - skills: per-branch enumeration over the objective-relevant skills, points credited in
 *    ceil-to-step granularity (step 1 when the branch is small); PERCENT skills credited at the
 *    layer-independent reachable max of their stat;
 *  - sublimations: v1's caps-only knapsack (10/1/1, epic/relic carrier binding), extended with the
 *    target axes; conditional subs credited as if their condition held; objective-capping subs go
 *    to world B (M ≤ threshold at the max multiplier). The 10-slot NORMAL knapsack is EXACT per subset
 *    (plan §8.18, T5): every stat summed raw, DI signed (a carried sub's negative DI rider pays), each
 *    axis rounded once when the packed option enters the DP. In an assume-CC world the start-of-combat
 *    crit the path's subs carry rides its own saturating dim (T1) — the fold's crit read is
 *    `threshold + carried start-of-combat crit + passives/ramps`, not every sub's crit at max copies;
 *  - ramps (perStatStep) priced at the sound reachable max of their source.
 *
 * Bails (null) instead of guessing — every one request-level, shared with [supportsRequest]
 * ([requestShape]): elemental-mastery requests (min-over-elements out of scope), forced
 * items/runes/subs, a NON-ZERO required target outside {AP, MP, CC, HP, RANGE}, a choosable sub
 * converting into a stat the DP reads, a sub family the worlds cannot cover, a packed-field overflow.
 *
 * PRODUCTION (backup certificate, plan §8.9bis): read by [WakfuBestBuildFinderAlgorithm.
 * proveMostMasteriesQuality] after a most-masteries search whose CP-SAT leg ended non-OPTIMAL —
 * on low-core machines the 1-worker proof takes 15-20 min while this DP delivers a "proven within
 * X%" statement in seconds. The bound is incumbent-free, so it is computed in the search's tail (one
 * full-tier pass, memoized single-flight by [MostMasteriesBoundCache], §8.19) and the badge is
 * usually ready when the search ends. Default grid = COARSE (DI 1 / CC 10 / HP 500):
 * measured bound-identical to the fine grid on S2 (the binding state saturates its targets) at
 * 580k states / ~33 MB / ~15 s. The steps stay mutable for the measurement harnesses
 * (MostMasteriesCertificateTest: tightness, attribution, grid profile, race).
 */
internal object MostMasteriesCertificate {
    private val SUPPORTED_TARGETS =
        setOf(
            Characteristic.ACTION_POINT,
            Characteristic.MOVEMENT_POINT,
            Characteristic.CRITICAL_HIT,
            Characteristic.HP,
            Characteristic.RANGE
        )

    private val REQUESTABLE_MASTERIES =
        setOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_HEALING,
            Characteristic.MASTERY_MELEE
        )

    /**
     * Bucket steps for the wide dims — achievement rounds UP (sound), DI rounds UP too. Every
     * per-option ceil ACCUMULATES across the ~20 stages, so coarse steps inflate the bound
     * multiplicatively (increment 2 measured DI step 10 ≈ +4 points on S3 alone) — DI defaults
     * exact. MUTABLE (grid-profile harness only): coarser grids trade tightness for the state
     * count / heap a low-RAM machine can afford; any setting stays SOUND (rounding is always UP).
     */
    @Volatile
    var ccStep = 10

    @Volatile
    var hpStep = 500

    @Volatile
    var diStep = 1

    /**
     * Increment 8: block-dim bucket step. 5 is the measured sweet spot: the gate's tightness lives
     * in the per-OPTION ceil (small +3/+9 block lines must not round up to a threshold's worth —
     * step 10 measured the whole −1.33pt gain away), while the state cost stays ~83 s / ~250 MB on
     * S2 — 12× under the 1-worker proof this certificate replaces.
     */
    private const val BLOCK_STEP = 5

    /**
     * The bound of one DP pass, read twice at the collapse (plan §8.18, T3):
     *  - [foldedBound] / [coreBound] — the SOFT read: every build, its shortfall priced by the penalty bucket;
     *  - [hardFoldedBound] / [hardCoreBound] — the TARGETS-MET read: only the states whose (over-counted) reads
     *    meet every required target > 0, at the full-targets multiplier — a bound on the most-masteries HARD leg's
     *    feasible set, the set a hard-leg result is optimal over. 0 when no state can meet the targets. Same units
     *    as the hard leg's converted stamp ([fullTargetsMultiplier]); never above the soft read.
     */
    class Result(
        val foldedBound: Long,
        val coreBound: Long,
        val states: Int,
        val wallMs: Long,
        // Provenance of the FOLDED argmax state: what the binding state claims, for attribution.
        val bindingState: String = "",
        // Instrument only ([bound] provenance=true): the reconstructed binding PATH — one line per
        // stage naming the chosen option and its claimed deltas.
        val bindingPath: List<String> = emptyList(),
        val hardFoldedBound: Long = 0L,
        val hardCoreBound: Long = 0L,
        // Provenance of the targets-met argmax state (instrument / harness readouts).
        val hardBindingState: String = "",
    ) {
        /**
         * The read a most-masteries result's stamped objective is compared with: the bare core without a required
         * target (both legs coincide there), the TARGETS-MET read for a HARD-leg result (it is optimal over exactly
         * that set — [me.chosante.autobuilder.genetic.SolverResult.mostMasteriesHardConstraintsMet]), else the soft
         * read.
         */
        fun comparableUpper(
            hardLeg: Boolean,
            hasRequiredTargets: Boolean,
        ): Long =
            when {
                !hasRequiredTargets -> coreBound
                hardLeg -> hardFoldedBound
                else -> foldedBound
            }
    }

    /**
     * One stage option: deltas per axis (positive parts only) + rarity budget/binding flags.
     * [hpPct] applies MULTIPLICATIVELY to the HP dim (skills-stage only): a %HP skill's real
     * contribution is a percentage of the build's own HP, so scaling the dim (a sound upper bound
     * of that HP) is sound and far tighter than a flat credit at the layer-independent max.
     */
    private data class Opt(
        val m: Long,
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
        // §8.9bis increment 4 — EXACT AT_MOST-condition modeling. A sub whose condition caps a
        // TRACKED stat (AP_AT_MOST / CRIT_AT_MOST) sets [capKind] (index into the run's cap table):
        // taking it TRUNCATES the dim to the threshold and pins its saturation there — the state
        // then pays the real target shortfall instead of enjoying the credit for free. Sound both
        // ways: a real build carrying the sub has its FINAL stat ≤ threshold (so its partial is
        // too), and states not taking the sub are unchanged. [mpCapMinus] (a lowered MP ceiling) is
        // no longer set: Armure lourde's MAX_MP −1 is a flat debit in the solver, and modeling it as a
        // ceiling under-counted (review fix 2026-10-01) — the debit is now simply ignored (sound).
        val capKind: Int = 0,
        val mpCapMinus: Int = 0,
        // Increment 8 — BLOCK tracking for AT_LEAST sub conditions (Mesure: +10 DI/+10 CC iff
        // block ≥ 40): every block source feeds [block]; a conditioned sub carries
        // [requiresBlockAtLeast] and is applicable only when the state's (over-counted) block
        // reaches the threshold — an over-counted dim can never wrongly DENY a real build (sound).
        val block: Int = 0,
        val requiresBlockAtLeast: Int = 0,
        // Review fix 2026-07-14 (A#1) — SIGNED contributions for the ASSUME worlds. In a world that
        // assumes an AT_MOST cap sub is carried, the capped stat's own dim switches to LOW
        // (under-approximating) semantics — signed values, FLOOR bucketing — because the condition
        // check must never over-reject a real build (the old ceil-accumulated rejection denied a
        // real crit 3+5=8 ≤ 10 build its sub: an UNDER-count of the bound). Aggregate sources that
        // cannot track their signed sum (the sub knapsack) contribute only their negative parts (a
        // valid lower bound of any subset). Unused (0) outside assume worlds.
        val apLow: Int = 0,
        val ccLowRaw: Int = 0,
        // Provenance identity (debug/instrument only — "" in production, so distinct()/dominance
        // semantics are untouched there): the human-readable source of this option.
        val src: String = "",
        // §8.9bis increment 6 — STATE-DEPENDENT ramp (Poids Plume): instead of pricing the
        // MP→DI ramp at the layer-independent MP max (attribution: the largest single over-credit,
        // 1 743 core units), taking the sub sets this bit and the ramp's DI lands at COLLAPSE time
        // as `contribution(mp dim)` — the path's own MP, exactly like the real build.
        val ramp: Boolean = false,
        // T1 (plan §8.18) — assume-CC worlds only: the option's POSITIVE START-OF-COMBAT crit (outside the
        // condition's pre-combat read). Tracked in its own saturating dim so the fold credits the crit the path
        // actually carries instead of every choosable sub's at max copies, budget-free. Over-counted (≥).
        val ccSoc: Int = 0,
        // RANGE (plan §8.20): the option's POSITIVE range, fed to the saturating range dim (0 = untracked — set only
        // when a non-zero RANGE target exists). Over-counted (≥), like AP / MP.
        val range: Int = 0,
    ) {
        /** Component-wise dominance (same flags): a ≤-everywhere option can never beat this one. */
        fun dominates(o: Opt): Boolean =
            m >= o.m &&
                ccSoc >= o.ccSoc &&
                range >= o.range &&
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
                capKind == o.capKind &&
                mpCapMinus == o.mpCapMinus &&
                ramp == o.ramp &&
                block >= o.block &&
                requiresBlockAtLeast == o.requiresBlockAtLeast &&
                // LOW dims only ever REJECT, so a SMALLER low contribution is the permissive side.
                apLow <= o.apLow &&
                ccLowRaw <= o.ccLowRaw
        // src deliberately ignored: provenance never changes what an option contributes.
    }

    /**
     * Drops options STRICTLY dominated by another with identical flags (exact pruning). Strictness
     * matters: [Opt.dominates] ignores [Opt.src], so under wantSrc two stat-identical options with
     * different src each dominate the other — a non-strict test annihilated BOTH (an under-count in
     * provenance/veto mode). Mutual domination ⇔ stat-equality, so requiring `!o.dominates(other)`
     * keeps every stat point's survivors in harness mode while production ("" src) still dedups
     * through distinct().
     */
    private fun prune(options: List<Opt>): List<Opt> {
        val distinct = options.distinct()
        return distinct.filter { o -> distinct.none { other -> other !== o && other.dominates(o) && !o.dominates(other) } }
    }

    /** Stage lists above this stay unpruned: the ring-pair list runs to millions and is pruned per pair already. */
    private const val PARETO_PRUNE_MAX_OPTIONS = 200_000

    /**
     * [prune]'s exact filter (same strictness rule) applied to a whole STAGE — across the slot's items and the
     * branch's skill allocations — as a skyline sweep, O(n · front) instead of O(n²), so it scales to the ~3-4k
     * options of an item slot (perf next-steps P2: S2 4.6M → 45k states, full tier 78 s → 8 s with primitive maps,
     * bit-identical bound).
     *
     * Why it is exact: a dominating option has the same flags (identical applicability in every state) and yields
     * a successor state ≥ on every over-counted dim, ≤ on the assume-world LOW dims and ≥ on M. Every later
     * transition (the BLOCK_AT_LEAST gate only gets easier with more block) and the collapse fold (core, penalty
     * bucket, world-B caps, assume-world filters) are monotone in that order, so a dominated option's descendants
     * can never beat the dominating one's — the max, and the core max, are unchanged.
     */
    private fun paretoPrune(options: List<Opt>): List<Opt> {
        val front = ArrayList<Opt>()
        for (o in options.distinct().sortedByDescending { it.m }) {
            if (front.any { it.dominates(o) && !o.dominates(it) }) continue
            front.removeAll { o.dominates(it) && !it.dominates(o) }
            front += o
        }
        return front
    }

    /** One AT_MOST cap a conditional sub can pin: the tracked dim + its threshold (raw units). */
    class CapSpec(
        val stat: Characteristic,
        val threshold: Int,
    )

    private class Geometry(
        val apCap: Int,
        val mpCap: Int,
        val ccBucketCap: Int,
        val hpBucketCap: Int,
        val diBucketCap: Int,
        // Increment 8: block dim cap in BUCKETS (0 = untracked — no AT_LEAST block condition in play).
        val blockBucketCap: Int = 0,
        // Review fix (A#1) — the ASSUME-world switches. When ≥ 0, this world assumes an AT_MOST cap
        // sub on that stat is CARRIED: the stat's own dim runs LOW (signed, floor) semantics
        // saturated one past the threshold (AP raw / CC buckets), the collapse filters states whose
        // dim exceeds it, and the fold credits the stat as a CONSTANT min(threshold, target).
        val assumeApThreshold: Int = -1,
        val assumeCcThresholdRaw: Int = -1,
        // T1 (plan §8.18) — assume-CC worlds with a CC target: the start-of-combat crit dim's cap in [socStep]
        // buckets (0 = untracked). It saturates at `target − threshold`, past which the fold's crit read meets
        // the target whatever else lands (exact for the fold); [socStep] is 1 (raw) unless that range overflows
        // the 7-bit field, where UP-rounding keeps it an over-count.
        val socCap: Int = 0,
        val socStep: Int = 1,
        // RANGE (plan §8.20): the range dim's saturation, RAW (0 = untracked — no non-zero RANGE target). Its target;
        // the model puts no out-of-combat cap on range, so no reachable value lies between the two.
        val rangeCap: Int = 0,
    ) {
        init {
            // The packed-key field widths are FIXED; the grid steps are mutable. A too-fine step
            // overflows its field into the neighbour (state merging corrupts ⇒ the bound can
            // UNDER-count, not just loosen) — fail loudly instead of honoring the KDoc's
            // "any setting stays sound" with a corrupted key. ([requestShape] bails on every
            // target-driven overflow first; these are the backstop.)
            require(apCap <= 0x1F) { "apCap $apCap overflows the 5-bit ap field" }
            require(mpCap <= 0x1F) { "mpCap $mpCap overflows the 5-bit mp field" }
            require(hpBucketCap <= 0x1FF) { "hpBucketCap $hpBucketCap overflows the 9-bit hp field (hpStep too fine)" }
            require(ccBucketCap <= 0x7F) { "ccBucketCap $ccBucketCap overflows the 7-bit cc field (ccStep too fine)" }
            require(diBucketCap <= 0x1FFF) { "diBucketCap $diBucketCap overflows the 13-bit d field (diStep too fine)" }
            require(blockBucketCap <= 0xF) { "blockBucketCap $blockBucketCap overflows the 4-bit block field" }
            require(socCap <= 0x7F) { "socCap $socCap overflows the 7-bit soc field" }
            require(rangeCap <= 0x1F) { "rangeCap $rangeCap overflows the 5-bit range field" }
        }

        // Packed key: range(5b @56) soc(7b @49) block(4b @45) ramp(1b @44) mpMinus(1b @43) d(13b @28)
        //             ap(5b @23) mp(5b @18) cc(7b @11) hp(9b @2) e(1b @1) r(1b @0)
        // (bit 63 stays clear: LongLongMaxMap reserves Long.MIN_VALUE as its empty slot.)
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
            soc: Int = 0,
            range: Int = 0,
        ): Long =
            (range.toLong() shl 56) or
                (soc.toLong() shl 49) or
                (block.toLong() shl 45) or (ramp.toLong() shl 44) or (mpMinus.toLong() shl 43) or
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

        fun soc(k: Long): Int = ((k shr 49) and 0x7F).toInt()

        fun range(k: Long): Int = ((k shr 56) and 0x1F).toInt()

        fun mpCapOf(mpMinus: Int): Int = (mpCap - mpMinus).coerceAtLeast(0)
    }

    private fun ceilDiv(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b

    /**
     * The DI dim after an option adding [delta] raw DI to a state at [dk] buckets. Only the exact normal-sub
     * packing (T5) emits a NEGATIVE delta (a carried sub's DI rider, e.g. Vélocité II's −10), and the dim must stay
     * an over-count of `min(real DI, cap)` through it: a negative delta rounds UP (`ceil` of a negative quotient),
     * the floor at 0 only raises the dim, and a SATURATED dim stays saturated — its real DI may sit anywhere above
     * the cap, so subtracting from the cap could under-count. Monotone in both arguments, which the stage-option
     * Pareto pruning needs ([paretoPrune]).
     */
    private fun Geometry.nextDi(
        dk: Int,
        delta: Int,
    ): Int =
        when {
            delta >= 0 -> (dk + ceilDiv(delta, diStep)).coerceAtMost(diBucketCap)
            dk >= diBucketCap -> diBucketCap
            else -> (dk - (-delta) / diStep).coerceAtLeast(0)
        }

    /** The solver's soft-penalty geometry (applyConstraintPenalty / bucketedIndex / buildPowerTable), mirrored. */
    private class PenaltyGeometry(
        val totalExpected: Long,
        val bucketSize: Long,
        val maxIndex: Int,
        val powScale: BigInteger,
    ) {
        // The solver's own multiplier (floored at 1), shared — never re-derived here.
        fun power6(index: Int): Long = penaltyMultiplier(index.toLong(), powScale)

        /** The bucket a build meeting EVERY required target lands in (its per-stat clamps sum to totalExpected). */
        val fullBucket: Int
            get() = (totalExpected / bucketSize).toInt().coerceAtMost(maxIndex)
    }

    private fun penaltyGeometry(
        params: WakfuBestBuildParams,
        targets: List<me.chosante.autobuilder.domain.TargetStat>,
    ): PenaltyGeometry {
        val totalExpected =
            targets
                .sumOf { it.target.toLong() * params.targetStats.scaledWeight(it) }
                .coerceAtLeast(1L)
        val bucketSize =
            if (totalExpected <= MAX_POWER_TABLE_INDEX) 1L else ceil(totalExpected.toDouble() / MAX_POWER_TABLE_INDEX).toLong()
        val maxIndex = if (totalExpected <= MAX_POWER_TABLE_INDEX) totalExpected.toInt() else ((totalExpected + bucketSize - 1) / bucketSize).toInt()
        return PenaltyGeometry(totalExpected, bucketSize, maxIndex, penaltyPowScale(maxIndex.toLong()))
    }

    /**
     * The soft objective's penalty multiplier for a build MEETING every required target — the factor a
     * most-masteries HARD-leg emission (`core × SCALE + bonus`, targets enforced) needs to land in this
     * certificate's soft units (`core × power6(bucket) × SCALE + bonus`). The multiplier at full targets
     * is ≈ MAX_PENALTY_MULTIPLIER, not 1: comparing the raw hard objective awarded "proven within
     * ~1 000 000 %" badges (pre-release review 2026-10-01). Null without required targets — the two
     * objectives coincide there.
     */
    fun fullTargetsMultiplier(params: WakfuBestBuildParams): Long? {
        val targets = params.targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        if (targets.isEmpty()) return null
        val geometry = penaltyGeometry(params, targets)
        return geometry.power6(geometry.fullBucket)
    }

    /** Sentinel of [applyOneRaw] for an inapplicable/rejected option (no packed key can equal it). */
    private const val REJECT = Long.MIN_VALUE

    /** One state transition, or null when the option is inapplicable/rejected in [k]. */
    private fun Geometry.applyOne(
        k: Long,
        o: Opt,
    ): Long? = applyOneRaw(k, o).takeIf { it != REJECT }

    /** [applyOne] without the boxed `Long?` — [REJECT] when the option is inapplicable in [k] (the hot path). */
    private fun Geometry.applyOneRaw(
        k: Long,
        o: Opt,
    ): Long {
        val e = e(k)
        val r = r(k)
        if (o.epic && e == 1) return REJECT
        if (o.relic && r == 1) return REJECT
        if (o.requiresEpicItem && e == 0) return REJECT
        if (o.requiresRelicItem && r == 0) return REJECT
        val newMpMinus = (mpMinus(k) + o.mpCapMinus).coerceAtMost(1)
        // Review fix (A#1) — ASSUME-world semantics: the capped stat's dim is a LOW
        // under-approximation (SIGNED deltas, FLOOR bucketing), saturated one past the threshold
        // (all higher values fail the collapse filter identically). real ≥ dim on every
        // represented build, which is exactly what the sound condition check needs; the fold
        // credits the capped stat as a constant instead of this dim.
        val newAp =
            if (assumeApThreshold >= 0) {
                (ap(k) + o.apLow).coerceIn(0, (assumeApThreshold + 1).coerceAtMost(apCap))
            } else {
                (ap(k) + o.ap).coerceAtMost(apCap)
            }
        val newCcBuckets =
            if (assumeCcThresholdRaw >= 0) {
                // RAW signed accumulation (no bucketing): flooring lost small +cc lines and let
                // phantom carriers slip the condition (+21% measured on S2) — raw is exact, and
                // the dim saturates at threshold+1 anyway (every higher value fails the filter).
                (cc(k) + o.ccLowRaw).coerceIn(0, (assumeCcThresholdRaw + 1).coerceAtMost(ccBucketCap))
            } else {
                (cc(k) + ceilDiv(o.cc, ccStep)).coerceAtMost(ccBucketCap)
            }
        // Increment 8: an AT_LEAST block condition gates on the state's OVER-counted block —
        // real final block ≥ t implies the dim reads ≥ t, so a real build is never wrongly denied.
        if (o.requiresBlockAtLeast > 0 && block(k) * BLOCK_STEP < o.requiresBlockAtLeast) return REJECT
        // mpCapMinus stays SATURATING: a lowered MAX MP wastes excess MP, it never forbids it.
        val mpEff = mpCapOf(newMpMinus)
        val flatHpBuckets = hp(k) + ceilDiv(o.hp, hpStep)
        // %HP scales the dim's value (a sound upper of the build's own HP): value ≤ buckets×STEP,
        // so ceil(buckets × (100+pct) / 100) buckets still over-count the scaled value.
        val hpBuckets =
            if (o.hpPct > 0) ceilDiv(flatHpBuckets * (100 + o.hpPct), 100) else flatHpBuckets
        return key(
            nextDi(d(k), o.d),
            newAp,
            (mp(k) + o.mp).coerceAtMost(mpEff),
            newCcBuckets,
            hpBuckets.coerceAtMost(hpBucketCap),
            if (o.epic) 1 else e,
            if (o.relic) 1 else r,
            newMpMinus,
            if (o.ramp) 1 else ramp(k),
            (block(k) + ceilDiv(o.block, BLOCK_STEP)).coerceAtMost(blockBucketCap),
            // T1: the carried start-of-combat crit, UP-rounded and saturating (an over-count — sound).
            if (socCap > 0) (soc(k) + ceilDiv(o.ccSoc, socStep)).coerceAtMost(socCap) else 0,
            // RANGE: raw and saturating at the target — an over-count of `min(real range, target)`.
            (range(k) + o.range).coerceAtMost(rangeCap)
        )
    }

    /** Transitions below this stay single-threaded (thread + merge overhead beats the gain). */
    private const val PARALLEL_APPLY_MIN_TRANSITIONS = 4_000_000L

    /**
     * One stage advance on primitive arrays ([LongLongMaxMap], perf next-steps P1): every transition is a
     * max-merge, so the result is identical to the boxed `HashMap` sweep it replaced — only allocation, hashing
     * and cache behaviour change. Big stages run chunked across CPU cores inside [LongLongMaxMap.advance]; the
     * states stay a `HashMap` between stages for the collapse and the provenance replay.
     */
    private fun Geometry.apply(
        states: HashMap<Long, Long>,
        options: List<Opt>,
        workers: Int,
    ): HashMap<Long, Long> {
        val n = states.size
        val ks = LongArray(n)
        val vs = LongArray(n)
        var i = 0
        for ((k, v) in states) {
            ks[i] = k
            vs[i] = v
            i++
        }
        val opts = options.toTypedArray()
        return LongLongMaxMap
            .advance(ks, vs, n, opts.size, PARALLEL_APPLY_MIN_TRANSITIONS, workers) { from, to, into ->
                for (idx in from until to) {
                    val k = ks[idx]
                    val m = vs[idx]
                    for (o in opts) {
                        val nk = applyOneRaw(k, o)
                        if (nk != REJECT) into.putMax(nk, m + o.m)
                    }
                }
            }.toHashMap()
    }

    /**
     * `false` ⇔ [bound] returns null on this request whatever the pool — answered by the SAME code [bound] runs first
     * ([requestShape], production semantics: no diagnostic flag, full tier), so the two cannot drift. The search-time
     * warm-up ([MostMasteriesBoundCache]) skips such a request instead of paying the pool + option build to watch the
     * bail. Only ever gates that warm-up — the proof itself always runs [bound] — and the parity lock in
     * MostMasteriesBoundCacheTest triggers every bail through both entries.
     */
    fun supportsRequest(
        params: WakfuBestBuildParams,
        sublimations: List<Sublimation>,
    ): Boolean = requestShape(params, sublimations, diag = emptySet(), blockGate = true) != null

    /** The AT_MOST cap a sub's condition puts on a TRACKED stat (the world split's cap subs), or null. */
    private fun capStatOf(sub: Sublimation): Characteristic? =
        when (sub.condition?.type) {
            SublimationConditionType.AP_AT_MOST, SublimationConditionType.AP_EXACT -> Characteristic.ACTION_POINT
            SublimationConditionType.CRIT_AT_MOST -> Characteristic.CRITICAL_HIT
            else -> null
        }

    // A PAIRED skill (Major "Movement Point and damage": +1 MP AND +20 elemental mastery for ONE point) carries a
    // null characteristic of its own — its halves are what the solver credits (buildSkillTerms adds both).
    // Expanding it is the review fix for the never-credited major MP.
    private fun skillComponents(sk: SkillCharacteristic): List<SkillCharacteristic> =
        if (sk is SkillCharacteristic.PairedCharacteristic) listOf(sk.first, sk.second) else listOf(sk)

    /**
     * Whether [bound] STAGES [sub] (as a DP option, a world-B credit or an assumed cap sub): every solver-choosable
     * sub except best-element concentration (forced-input only in most-masteries) and the diagnostic removals.
     */
    private fun stagesSub(
        sub: Sublimation,
        diag: Set<String>,
    ): Boolean =
        sub.solverChoosable &&
            sub.bestElementConcentration == null &&
            !("noCondSubs" in diag && sub.condition != null) &&
            !("noEpicSubs" in diag && sub.rarity == SublimationRarity.EPIC) &&
            !("noNormalSubs" in diag && sub.rarity == SublimationRarity.NORMAL) &&
            !("noRelicSubs" in diag && sub.rarity == SublimationRarity.RELIC) &&
            !("noSecretCritique" in diag && sub.condition?.type == SublimationConditionType.CRITICAL_MASTERY_AT_MOST)

    /**
     * Whether [sub]'s condition caps the OBJECTIVE itself — world B: [bound] folds its credits per state under a sound
     * M-cap instead of staging it.
     */
    private fun isObjectiveCapping(
        sub: Sublimation,
        requested: Set<Characteristic>,
    ): Boolean {
        val cond = sub.condition ?: return false
        return cond.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ||
            (cond.type == SublimationConditionType.CRITICAL_MASTERY_AT_MOST && Characteristic.MASTERY_CRITICAL in requested)
    }

    /** [requestShape]'s output: the request-level facts every world of [bound] reads. */
    private class RequestShape(
        val requested: Set<Characteristic>,
        // Every required row: the model's fold predicate ("any required-target stat requested ⇒ the objective folds")
        // and its penalty geometry read them all.
        val targets: List<me.chosante.autobuilder.domain.TargetStat>,
        // The rows the DP tracks — the NON-ZERO required rows, all in [SUPPORTED_TARGETS], one per stat.
        val targetByChar: Map<Characteristic, me.chosante.autobuilder.domain.TargetStat>,
        // The selected passives' flat stats, folded to the usable stat.
        val passiveFlat: Map<Characteristic, Int>,
        // The AT_MOST cap subs of the world split (all EPIC).
        val capSubs: List<Sublimation>,
        // Increment 8: the largest BLOCK_AT_LEAST threshold among the staged subs (0 = no block dim).
        val blockAtLeastMax: Int,
        // Increment 6: the single tracked MP→DI ramp (Poids Plume), or null.
        val mpDiRamp: Pair<Sublimation, SublimationEffect.PerStatStep>?,
        // Sound upper bounds of a build's FINAL AP / MP (see [requestShape]).
        val apUpper: Long,
        val mpUpper: Long,
    ) {
        // A dim's saturation point: its target, never below 0 (a negative target would corrupt the packed key).
        fun cap(char: Characteristic): Int = (targetByChar[char]?.target ?: 0).coerceAtLeast(0)

        fun passivePos(char: Characteristic): Int = maxOf(passiveFlat[char] ?: 0, 0)

        // The tracked ramp reads the MP dim up to where its contribution saturates.
        val rampSaturationMp: Int = mpDiRamp?.second?.let { it.threshold + ceilDiv(it.cap, it.perStep) } ?: 0

        // The MP dim's saturation (world-independent) and the main world's AP one: min(target, upper) keeps
        // `dim ≥ min(real, target)` — at the target that is the fold's own clamp; at the upper the dim never saturates
        // below a real build. (An assume-AP world saturates its LOW AP read one past the threshold instead.)
        val mpCap: Int = minOf(maxOf(cap(Characteristic.MOVEMENT_POINT), rampSaturationMp).toLong(), mpUpper).toInt()
        val mainApCap: Int = minOf(cap(Characteristic.ACTION_POINT).toLong(), apUpper).toInt()
    }

    /**
     * [bound]'s REQUEST-level facts and bails — the ONE source of truth [bound] (every world) and [supportsRequest]
     * read, computed before any pool or DP work. Null ⇔ [bound] returns null on this request whatever the pool:
     *  - an elemental-mastery request (min-over-elements out of scope), forced items / runes / sublimations (they can
     *    ADD capability the bound does not price), no requestable mastery;
     *  - a NON-ZERO required target outside [SUPPORTED_TARGETS], or one stat required twice (the fold reads one row
     *    per stat) — a 0-valued row of any stat is skipped exactly (see `tracked` below);
     *  - a cap-sub family the world split cannot cover (more than 6, or a non-EPIC one), a second tracked MP→DI ramp,
     *    more than 6 objective-capping (world-B) subs;
     *  - a choosable sub CONVERTING into a stat the DP reads — a requested mastery, DI, AP, MP, or a tracked CC / HP /
     *    RANGE / block — whose moved value no option prices (an under-count);
     *  - a target or catalog that overflows its packed-key field (AP / MP / RANGE 5 bits, CC 7, HP 9, block 4).
     * [diag] and [blockGate] are [bound]'s own arguments ([supportsRequest]: none / the full tier).
     */
    private fun requestShape(
        params: WakfuBestBuildParams,
        sublimations: List<Sublimation>,
        diag: Set<String>,
        blockGate: Boolean,
    ): RequestShape? {
        if (params.targetStats.masteryElementsToMinimize.isNotEmpty()) return null
        // Forced RUNES (both forms) and forced SUBS can ADD modelable capability the bound does not
        // price — under-count risk ⇒ bail. (Forced ITEMS only restrict, but stay bailed for parity
        // with v1's conservative gate.)
        if (params.forcedItems.isNotEmpty() ||
            params.forcedRunes.isNotEmpty() ||
            params.forcedRunesByItem.isNotEmpty() ||
            params.forcedSublimations.isNotEmpty()
        ) {
            return null
        }
        val requested =
            params.targetStats
                .map { it.characteristic }
                .filter { it in REQUESTABLE_MASTERIES }
                .toSet()
        if (requested.isEmpty()) return null
        // EXACTLY the model's fold predicate (buildMostMasteriesObjective / applyConstraintPenalty):
        // the objective folds when ANY required-target stat is requested, 0-valued included — the
        // certificate's units must never diverge from the model's on any request shape.
        val targets = params.targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        // The rows the DP TRACKS: a 0-valued row of any stat is an EXACT skip — TargetStats weighs it 0, so its
        // penalty term (weight × clamp(actual, ±0)) and its share of the expected total are both 0, its overshoot
        // term is 0, and the hard leg only constrains `target > 0` (addRequiredTargetHardConstraints). It keeps the
        // fold (above) and nothing else.
        val tracked = targets.filter { it.target != 0 }
        if (tracked.any { it.characteristic !in SUPPORTED_TARGETS }) return null
        // The model sums one penalty term PER ROW; the fold reads one per stat — two rows of one stat would be
        // under-counted.
        if (tracked.groupBy { it.characteristic }.any { it.value.size > 1 }) return null
        val targetByChar = tracked.associateBy { it.characteristic }
        val level = params.character.level

        // Pre-release review fix (2026-10-01): the selected passives' flat stats fold into the
        // solver's FINAL stats (StatBuilder.prePercentTermsFor) but into NO condition read (pre-combat
        // / first-turn reads stop at baseTermsFor) — ignoring them under-counted every passive-
        // carrying request (Zobal "Regard masqué", Ouginak "Pistage": +1 MP). They are credited as a
        // mandatory stage (dims), as constants in the assume-world folds, and as free value in the
        // world-B M-caps. Flat passive stats are positive by extraction contract.
        val passiveFlat: Map<Characteristic, Int> =
            WakfuBuildSolver
                .resolvedPassives(params)
                .flatMap { it.flatStats.entries }
                .groupBy({ it.key.foldedToUsableStat() }, { it.value })
                .mapValues { (_, values) -> values.sum() }

        fun passivePos(char: Characteristic): Int = maxOf(passiveFlat[char] ?: 0, 0)

        val staging = params.useSublimations && "noSubs" !in diag
        // Increment 4/5 pre-scan, redesigned by the 2026-07-14 review (A#1): the AT_MOST cap subs
        // (AP_AT_MOST/AP_EXACT/CRIT_AT_MOST — all EPIC, so a real build carries at most ONE) — see the world split in
        // [bound]. The split relies on every cap sub being EPIC (one epic slot ⇒ a build carries at most one, so one
        // assume-world per cap sub covers it): bail on a NORMAL/RELIC cap sub (a future game-data refresh) rather than
        // under-count.
        val capSubs =
            if (staging && "noCondSubs" !in diag) {
                sublimations.filter { it.solverChoosable && capStatOf(it) != null }
            } else {
                emptyList()
            }
        if (capSubs.size > 6) return null
        if (capSubs.any { it.rarity != SublimationRarity.EPIC }) return null

        // Increment 8 pre-scan: BLOCK_AT_LEAST conditions among the choosable subs — when present,
        // the block dim is tracked up to the LARGEST threshold (values above it are equivalent).
        val blockAtLeastMax =
            if (blockGate && staging && "noCondSubs" !in diag) {
                sublimations
                    .filter { it.solverChoosable && it.condition?.type == SublimationConditionType.BLOCK_AT_LEAST }
                    .maxOfOrNull { it.condition?.value ?: 0 } ?: 0
            } else {
                0
            }

        // Increment 6 pre-scan: the single tracked MP→DI ramp (Poids Plume). Its DI lands at
        // collapse as contribution(mp dim) — so MP is tracked even without an MP target. A second
        // tracked-source→DI ramp would need another state bit: bail rather than under-model.
        val mpDiRamps =
            if (staging && "noRamps" !in diag) {
                sublimations
                    .filter { it.solverChoosable && ("noCondSubs" !in diag || it.condition == null) }
                    .flatMap { sub -> sub.effects.filterIsInstance<SublimationEffect.PerStatStep>().map { sub to it } }
                    .filter { (_, eff) -> eff.source == Characteristic.MOVEMENT_POINT && eff.target == Characteristic.DAMAGE_INFLICTED }
            } else {
                emptyList()
            }
        if (mpDiRamps.size > 1) return null

        // Sound upper bound of a build's FINAL AP / MP (review fix, plan §8.18): the solver caps only the
        // PRE-sublimation sheet at 16 AP / 8 MP (applyOutOfCombatCaps), so sublimations (Vivacité II's +1 AP,
        // Vélocité II's +1 MP), ramps and passives land above it. The AP/MP dims used to saturate at the bare
        // out-of-combat cap, one short of a reachable target above it — an UNDER-count of that target's partial
        // (−46% on the `neg-di-rider-mp-overflow` lock). Null (bail) on a conversion into the stat.
        fun finalStatUpper(
            stat: Characteristic,
            outOfCombatCap: Long,
        ): Long? {
            var v = outOfCombatCap + passivePos(stat)
            if (!params.useSublimations) return v
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                val copies = sub.maxCopies.coerceAtLeast(1).toLong()
                for (eff in sub.effects) {
                    when (eff) {
                        is SublimationEffect.StatEffect ->
                            if (eff.characteristic.foldedToUsableStat() == stat && WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) {
                                v += maxOf(eff.magnitudeAtLevel(level), 0).toLong() * copies
                            }
                        // A ramp never contributes more than its cap.
                        is SublimationEffect.PerStatStep -> if (eff.target.foldedToUsableStat() == stat) v += maxOf(eff.cap, 0).toLong() * copies
                        is SublimationEffect.Conversion -> if (eff.to.foldedToUsableStat() == stat) return null
                        else -> {}
                    }
                }
            }
            return v
        }
        val apUpper = finalStatUpper(Characteristic.ACTION_POINT, MAX_OUT_OF_COMBAT_AP) ?: return null
        val mpUpper = finalStatUpper(Characteristic.MOVEMENT_POINT, MAX_OUT_OF_COMBAT_MP) ?: return null

        if (staging) {
            // Every stat the DP reads, a choosable sub must not convert into: the moved value rides no option. (AP / MP
            // conversions already bailed above, whether staged or not.)
            val read =
                requested +
                    Characteristic.DAMAGE_INFLICTED +
                    targetByChar.keys +
                    (if (blockAtLeastMax > 0) setOf(Characteristic.BLOCK_PERCENTAGE) else emptySet())
            if (sublimations.any { sub ->
                    stagesSub(sub, diag) && sub.effects.any { it is SublimationEffect.Conversion && it.to.foldedToUsableStat() in read }
                }
            ) {
                return null
            }
            // World B folds every SUBSET of the objective-capping subs at each collapse state (2^n − 1).
            if (sublimations.count { stagesSub(it, diag) && capStatOf(it) == null && isObjectiveCapping(it, requested) } > 6) return null
        }

        val shape = RequestShape(requested, targets, targetByChar, passiveFlat, capSubs, blockAtLeastMax, mpDiRamps.firstOrNull(), apUpper, mpUpper)
        // The packed-key fields are FIXED (Geometry): a target or threshold past them would corrupt the key — bail
        // instead. (Assume worlds saturate their capped dim at threshold + 1, inside these fields.)
        if (shape.mainApCap > 0x1F || shape.mpCap > 0x1F) return null
        if (ceilDiv(shape.cap(Characteristic.CRITICAL_HIT), ccStep) > 0x7F) return null
        if (ceilDiv(shape.cap(Characteristic.HP), hpStep) > 0x1FF) return null
        if (ceilDiv(blockAtLeastMax, BLOCK_STEP) > 0xF) return null
        if (shape.cap(Characteristic.RANGE) > 0x1F) return null
        return shape
    }

    fun bound(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        debug: Boolean = false,
        // ⚠️ DIAGNOSTIC ONLY — each flag REMOVES a credit layer, making the result UNSOUND as a
        // bound. Used exclusively by the attribution harness to price each relaxation's share of
        // the overshoot: "noCondSubs", "noSubs", "noSkills", "noRunes".
        diag: Set<String> = emptySet(),
        // Instrument (attribution): retain per-stage states + options and reconstruct the binding
        // PATH backward — names the option chosen at every stage of the argmax state. Costs memory
        // (all stage maps retained) and a backward sweep; never used in production.
        provenance: Boolean = false,
        // `false` skips the block dim (increment 8) — the former QUICK tier (bound ~1.3pt looser),
        // kept for the harnesses and the assume worlds below. Production runs the full tier only:
        // since P1+P2 it costs what the quick tier did, so the badge is ONE pass (§8.19). Both
        // settings are independently sound.
        blockGate: Boolean = true,
        // §8.15 P&B-2 seam (harness only): veto options of a stage by provenance src — the DD-B&B
        // branching primitive. A veto only ever RESTRICTS the relaxation, so the result stays a
        // sound upper bound OF THE VETOED SUBSPACE (fix a choice = veto its alternatives; exclude
        // it = veto it). Non-null veto forces src population (slightly larger option sets, same
        // soundness).
        optionVeto: ((stage: String, src: String) -> Boolean)? = null,
        // Cooperative cancellation (review finding): the DP runs 15-80 s and used to be
        // uninterruptible — a superseded GUI proof kept a core pinned until completion. Checked
        // once per stage; `false` aborts with null (callers already treat null as "no badge").
        shouldContinue: () -> Boolean = { true },
        // Exact stage-option Pareto pruning ([paretoPrune]) — production ON; `false` only for the
        // bit-identity lock that compares both runs.
        pruneDominatedOptions: Boolean = true,
        // Stage-advance chunk workers ([LongLongMaxMap.advance]), read once per stage. A pure work
        // knob — the max-merge is order-independent, so every value yields the identical bound. The
        // search-time warm-up ([MostMasteriesBoundCache]) holds it at 1 while CP-SAT owns the cores.
        parallelism: () -> Int = LongLongMaxMap::defaultWorkers,
        // INTERNAL world-split recursion (review fix A#1) — never set by callers. worldDropCaps:
        // run the DP with every AT_MOST cap sub excluded; worldAssume: run it with THAT cap sub
        // assumed carried (LOW semantics on its capped stat, credits added at collapse).
        worldAssume: Sublimation? = null,
        worldDropCaps: Boolean = false,
    ): Result? {
        val t0 = System.nanoTime()
        // Cancelled before this world even builds its options (the world split runs them in turn).
        if (!shouldContinue()) return null
        val wantSrc = provenance || optionVeto != null
        // Every request-level fact and bail — the code [supportsRequest] runs too.
        val shape = requestShape(params, sublimations, diag, blockGate) ?: return null
        val requested = shape.requested
        val targets = shape.targets
        val targetByChar = shape.targetByChar
        val level = params.character.level
        val diCap = DAMAGE_DI_MAX.toInt()

        fun cap(char: Characteristic): Int = shape.cap(char)

        fun passivePos(char: Characteristic): Int = shape.passivePos(char)

        // The AT_MOST cap subs (AP_AT_MOST/AP_EXACT/CRIT_AT_MOST — all EPIC, so a real build carries at most ONE),
        // redesigned by the 2026-07-14 review (A#1): the old in-state rejection accumulated per-option CEILs and could
        // deny a real satisfying build (an under-count); the sound form is a WORLD SPLIT: one world WITHOUT any cap sub
        // (bit-identical to the pre-increment-4 DP) plus one world per cap sub where it is ASSUMED carried — the capped
        // stat's dim switches to LOW semantics for the condition and the fold credits it as a constant. The bound is
        // the max over worlds (every real build lives in exactly one).
        val capSubs = shape.capSubs
        if (capSubs.isNotEmpty() && worldAssume == null && !worldDropCaps) {
            // Orchestrate the worlds SEQUENTIALLY: the peak memory stays that of a single DP (the
            // low-core/low-RAM machines this backup exists for cannot afford 4 concurrent state
            // maps), and the assume worlds are far smaller than the main one (their capped dim
            // saturates at the threshold).
            val worlds: List<Result?> =
                (listOf<Sublimation?>(null) + capSubs).map { assume ->
                    bound(
                        params,
                        pool,
                        runes,
                        sublimations,
                        debug,
                        diag,
                        provenance,
                        // Assume worlds always run the QUICK grid (no block dim): their raw capped
                        // dim already multiplies the state space (full-tier assume worlds measured
                        // 15.5 min wall for no bound change), and the block refinement's ~1.3pt
                        // lives in the MAIN world. Mixed tiers are fine — each world is
                        // independently sound.
                        blockGate = if (assume == null) blockGate else false,
                        optionVeto = optionVeto,
                        shouldContinue = shouldContinue,
                        pruneDominatedOptions = pruneDominatedOptions,
                        parallelism = parallelism,
                        worldAssume = assume,
                        worldDropCaps = assume == null
                    )
                }
            if (worlds.any { it == null }) return null
            val best = worlds.filterNotNull().maxByOrNull { it.foldedBound } ?: return null
            val bestHard = worlds.filterNotNull().maxByOrNull { it.hardFoldedBound } ?: return null
            return Result(
                best.foldedBound,
                worlds.filterNotNull().maxOf { it.coreBound },
                worlds.filterNotNull().sumOf { it.states },
                (System.nanoTime() - t0) / 1_000_000,
                best.bindingState,
                best.bindingPath,
                hardFoldedBound = bestHard.hardFoldedBound,
                hardCoreBound = worlds.filterNotNull().maxOf { it.hardCoreBound },
                hardBindingState = bestHard.hardBindingState
            )
        }
        val assumeStat = worldAssume?.let { capStatOf(it) }
        val assumeThreshold = worldAssume?.condition?.value ?: -1
        val passiveFlat = shape.passiveFlat
        val blockAtLeastMax = shape.blockAtLeastMax
        val mpDiRamp = shape.mpDiRamp
        val apCap =
            if (assumeStat == Characteristic.ACTION_POINT) {
                // Assume-world: the AP dim is the condition's LOW read, saturated one past the threshold (its
                // fold contribution is a constant instead).
                (assumeThreshold + 1).coerceAtMost(MAX_OUT_OF_COMBAT_AP.toInt())
            } else {
                shape.mainApCap
            }
        val mpCap = shape.mpCap
        // T1: in an assume-CC world the crit read is `threshold + (crit landing outside the pre-combat read)`; the
        // part carried by staged subs rides the soc dim, useful only up to the target (0 = no CC target to reach).
        val socRange = if (assumeStat == Characteristic.CRITICAL_HIT) (cap(Characteristic.CRITICAL_HIT) - assumeThreshold).coerceAtLeast(0) else 0
        val socStep = ceilDiv(socRange, 0x7F).coerceAtLeast(1)

        val geo =
            Geometry(
                apCap = apCap,
                mpCap = mpCap,
                ccBucketCap =
                    if (assumeStat == Characteristic.CRITICAL_HIT) {
                        // RAW units in assume-CC worlds (see applyOne) — saturation at threshold+1.
                        (assumeThreshold + 1).coerceAtMost(0x7F)
                    } else {
                        ceilDiv(cap(Characteristic.CRITICAL_HIT), ccStep)
                    },
                hpBucketCap = ceilDiv(cap(Characteristic.HP), hpStep),
                diBucketCap = ceilDiv(diCap, diStep),
                blockBucketCap = ceilDiv(blockAtLeastMax, BLOCK_STEP),
                assumeApThreshold = if (assumeStat == Characteristic.ACTION_POINT) assumeThreshold else -1,
                assumeCcThresholdRaw = if (assumeStat == Characteristic.CRITICAL_HIT) assumeThreshold else -1,
                socCap = ceilDiv(socRange, socStep),
                socStep = socStep,
                // RANGE (plan §8.20): saturates at the target. The model puts no out-of-combat cap on range (only on
                // AP / MP / WP / crit, applyOutOfCombatCaps), so — unlike the AP / MP dims — no final-stat upper can
                // sit below a reachable target. 0 = no non-zero RANGE target (the dim stays 0, keys unchanged).
                rangeCap = cap(Characteristic.RANGE)
            )

        // The %HP skill multiplies the build's WHOLE flat HP (StatBuilder.actualStat: the pre-percent sum, subs and
        // passives included, × (100 + %HP) / 100), but the skills stage scales only what was staged before it. Three HP
        // sources land after it — the EPIC / RELIC sub stages (after the skills since increment 8) and the collapse-time
        // credits of the world-B subs and the assumed cap sub — so their flat HP is scaled here by the LARGEST reachable
        // %HP: an over-count of its real share (a build's own %HP ≤ that max) — sound (plan §8.20). 0 when HP is
        // untracked or the skills are switched off (diag).
        val hpPctMax =
            if (geo.hpBucketCap == 0 || "noSkills" in diag) {
                0
            } else {
                val skills = params.character.characterSkills
                listOf(skills.strength, skills.agility, skills.luck, skills.major, skills.intelligence).sumOf { branch ->
                    branch
                        .getCharacteristics()
                        .flatMap(::skillComponents)
                        .filter { it.characteristic == Characteristic.HP && it.unitType == me.chosante.common.skills.UnitType.PERCENT }
                        .sumOf { it.unitValue * minOf(branch.maxPointsToAssign, it.maxPointsAssignable) }
                }
            }

        fun hpScaled(hp: Int): Int = if (hpPctMax > 0 && hp > 0) ceilDiv(hp * (100 + hpPctMax), 100) else hp

        // Positive parts of an equipment's objective/target lines.
        fun statOf(
            e: Equipment,
            c: Characteristic,
        ): Int = maxOf(e.characteristics[c] ?: 0, 0)

        // The rune axes that actually exist: for each supported axis, the best rune value per
        // (slot type, level). Axis order: [mastery, AP, MP, CC, HP, RANGE] — absent axes get 0 everywhere.
        val runeAxes: List<(ItemType, Int) -> Int> =
            listOf<(RuneType) -> Boolean>(
                { it.characteristic in requested },
                { it.characteristic == Characteristic.ACTION_POINT && Characteristic.ACTION_POINT in targetByChar },
                { it.characteristic == Characteristic.MOVEMENT_POINT && Characteristic.MOVEMENT_POINT in targetByChar },
                { it.characteristic == Characteristic.CRITICAL_HIT && Characteristic.CRITICAL_HIT in targetByChar },
                { it.characteristic == Characteristic.HP && Characteristic.HP in targetByChar },
                { it.characteristic == Characteristic.RANGE && Characteristic.RANGE in targetByChar }
            ).map { predicate ->
                val matching = runes.filter(predicate)
                (
                    { type: ItemType, lvl: Int ->
                        if (params.useRunes && "noRunes" !in diag) matching.maxOfOrNull { it.valueOn(type, lvl) } ?: 0 else 0
                    }
                )
            }

        /**
         * All rune compositions of an item's sockets over the existing axes — EXACT socket
         * accounting (a real build can mix rune kinds on one item, so pure-axis options alone
         * would under-count mixed shapes).
         */
        fun itemOpts(e: Equipment): List<Opt> {
            // ⚠️ diag "netNegatives" (UNSOUND, sizing only): subtract each item's negative
            // penalized-mastery lines from its m — a LOWER bound of the real per-build penalty
            // treatment, so the result under-counts; only the delta vs the full bound is read.
            val negPenalty =
                if ("netNegatives" in diag) {
                    e.characteristics.entries.sumOf { (c, v) ->
                        if (c in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS && c !in requested && v < 0) v.toLong() else 0L
                    }
                } else {
                    0L
                }
            val base =
                Opt(
                    m = e.characteristics.entries.sumOf { (c, v) -> if (c in requested && v > 0) v.toLong() else 0L } + negPenalty,
                    d = statOf(e, Characteristic.DAMAGE_INFLICTED),
                    ap = statOf(e, Characteristic.ACTION_POINT),
                    mp = statOf(e, Characteristic.MOVEMENT_POINT),
                    cc = statOf(e, Characteristic.CRITICAL_HIT),
                    hp = statOf(e, Characteristic.HP),
                    epic = e.rarity == me.chosante.common.Rarity.EPIC,
                    relic = e.rarity == me.chosante.common.Rarity.RELIC,
                    block = if (blockAtLeastMax > 0) statOf(e, Characteristic.BLOCK_PERCENTAGE) else 0,
                    // LOW dims: per-item options are exact, so the SIGNED value (negative lines
                    // included) is the tightest valid under-approximation. The AP read is the
                    // solver's pre-combat `valueFor(AP)` = AP + MAX_ACTION_POINT (review fix: raw AP
                    // ignored the −1 MAX_AP of Les Affamées & co and over-rejected real carriers).
                    apLow =
                        if (geo.assumeApThreshold >= 0) {
                            (e.characteristics[Characteristic.ACTION_POINT] ?: 0) + (e.characteristics[Characteristic.MAX_ACTION_POINT] ?: 0)
                        } else {
                            0
                        },
                    ccLowRaw = if (geo.assumeCcThresholdRaw >= 0) (e.characteristics[Characteristic.CRITICAL_HIT] ?: 0) else 0,
                    src = if (wantSrc) e.name.fr else "",
                    // Gated on the dim: an untracked range must not split otherwise-equal options (more work, same bound).
                    range = if (geo.rangeCap > 0) statOf(e, Characteristic.RANGE) else 0
                )
            val slots = if (params.useRunes) e.maxShardSlots else 0
            if (slots == 0) return listOf(base)
            val perAxis = runeAxes.map { it(e.itemType, e.level) }
            val out = mutableListOf<Opt>()
            // Every composition of `slots` sockets over the axes. An axis valued 0 adds nothing, so all of them
            // collapse into ONE blank remainder (a socket left to an untracked rune): this emits exactly the
            // distinct options of the full composition set — every socket filled when no axis is blank.
            val live = perAxis.indices.filter { perAxis[it] != 0 }
            val mustFill = live.size == perAxis.size
            val counts = IntArray(perAxis.size)

            fun compose(
                next: Int,
                left: Int,
            ) {
                if (next == live.size) {
                    if (mustFill && left > 0) return
                    out +=
                        base.copy(
                            m = base.m + counts[0].toLong() * perAxis[0],
                            ap = base.ap + counts[1] * perAxis[1],
                            mp = base.mp + counts[2] * perAxis[2],
                            cc = base.cc + counts[3] * perAxis[3],
                            hp = base.hp + counts[4] * perAxis[4],
                            range = base.range + counts[5] * perAxis[5],
                            // Runes are positive and exact — they feed the LOW dims too.
                            apLow = base.apLow + (if (geo.assumeApThreshold >= 0) counts[1] * perAxis[1] else 0),
                            ccLowRaw = base.ccLowRaw + (if (geo.assumeCcThresholdRaw >= 0) counts[3] * perAxis[3] else 0)
                        )
                    return
                }
                val axis = live[next]
                for (c in 0..left) {
                    counts[axis] = c
                    compose(next + 1, left - c)
                }
                counts[axis] = 0
            }
            compose(0, slots)
            return out.distinct()
        }

        val baseValues = params.character.baseCharacteristicValues
        var states = HashMap<Long, Long>()
        states[
            geo.key(
                0,
                // Assume-AP worlds seed the AP dim with the SIGNED base (LOW semantics); otherwise
                // the usual saturating positive read. Same split for CC (floor vs ceil bucketing).
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
                block = ceilDiv((baseValues[Characteristic.BLOCK_PERCENTAGE] ?: 0).coerceAtLeast(0), BLOCK_STEP).coerceAtMost(geo.blockBucketCap),
                range = (baseValues[Characteristic.RANGE] ?: 0).coerceIn(0, geo.rangeCap)
            )
        ] = 0L

        // Provenance retention + the single stage-advance helper.
        val stageLog = if (provenance) mutableListOf<Triple<String, HashMap<Long, Long>, List<Opt>>>() else null

        var cancelled = false

        fun step(
            label: String,
            options: List<Opt>,
        ) {
            if (cancelled || !shouldContinue()) {
                // Cancelled: drop the state map so the remaining stages are no-ops; the caller gets
                // null (checked before the collapse), never a truncated "bound".
                cancelled = true
                states = HashMap()
                return
            }
            val allowed = if (optionVeto == null) options else options.filter { !optionVeto(label, it.src) }
            // Exact: a strictly dominated option's descendants are dominated too (see [paretoPrune]). The
            // ring-pair list is skipped — it is millions long and already pruned per pair.
            val effective =
                if (pruneDominatedOptions && allowed.size in 2..PARETO_PRUNE_MAX_OPTIONS) paretoPrune(allowed) else allowed
            stageLog?.add(Triple(label, HashMap(states), effective))
            states = geo.apply(states, effective, parallelism())
        }

        // Pair-wise exact merge of two options (both non-null axes add; budgets/flags OR).
        fun combineOpts(
            a: Opt,
            b: Opt,
        ): Opt =
            Opt(
                a.m + b.m,
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
                ccSoc = a.ccSoc + b.ccSoc,
                range = a.range + b.range,
                src =
                    if (a.src.isEmpty()) {
                        b.src
                    } else if (b.src.isEmpty()) {
                        a.src
                    } else {
                        a.src + "+" + b.src
                    }
            )

        // Stage ORDER matters for cost (|states| × |options|): the huge exact-pair stages (rings,
        // weapons) run FIRST while the state space is still tiny; the per-slot single stages follow.
        // Rings: exact distinct-name pairs over per-item dominance-pruned option sets (v1's rule —
        // the two-stage relaxation measured ~+7% looseness on S3 together with the weapon double-dip).
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
            if (debug) println("MM_M3V2_STAGE rings states=${states.size} options=${options.size}")
        }
        // Weapons: 2H alone | 1H (+ optional off-hand) | off-hand alone | nothing — exact pairs.
        run {
            val options = mutableListOf(Opt(0L, 0))
            options += pool[ItemType.TWO_HANDED_WEAPONS].orEmpty().flatMap { prune(itemOpts(it)) }
            val oneH = pool[ItemType.ONE_HANDED_WEAPONS].orEmpty().map { prune(itemOpts(it)) }
            val off = pool[ItemType.OFF_HAND_WEAPONS].orEmpty().map { prune(itemOpts(it)) }
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
            step("weapons", options)
            if (debug) println("MM_M3V2_STAGE weapons states=${states.size} options=${options.size}")
        }
        val singleSlots =
            pool.keys - setOf(ItemType.RING, ItemType.ONE_HANDED_WEAPONS, ItemType.TWO_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS)
        for (slot in singleSlots) {
            step(slot.name, listOf(Opt(0L, 0)) + pool[slot].orEmpty().flatMap { prune(itemOpts(it)) })
            if (debug) println("MM_M3V2_STAGE $slot states=${states.size}")
        }

        // Sound layer-independent reachable max of a stat (for percent skills, ramps and the
        // world-B caps). Review fix (A#6): a build equips TWO rings, so the RING slot counts its
        // top-2 items; rune contributions are included too (both omissions UNDER-stated the max —
        // a latent under-count for every consumer that needs an upper bound).
        fun reachableMax(
            stat: Characteristic,
            depth: Int = 0,
        ): Int {
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
                    .flatMap(::skillComponents)
                    .filter { it.characteristic == stat && it.unitType == me.chosante.common.skills.UnitType.FIXED }
                    .maxOfOrNull { it.unitValue * minOf(branch.maxPointsToAssign, it.maxPointsAssignable) } ?: 0
            }
            v += passivePos(stat)
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                v += sub.effects
                    .filterIsInstance<SublimationEffect.StatEffect>()
                    .filter { it.characteristic == stat && WakfuBuildSolver.scenarioGateMatches(it.scenarioGate, params) }
                    .sumOf { maxOf(it.magnitudeAtLevel(level), 0) } * sub.maxCopies.coerceAtLeast(1)
                // Ramps INTO this stat land in the final sheet too (priced at their source's own
                // reachable max; the depth guard only stops a pathological ramp cycle).
                if (depth < 2) {
                    v += sub.effects
                        .filterIsInstance<SublimationEffect.PerStatStep>()
                        .filter { it.target == stat && it.source != stat }
                        .sumOf { maxOf(it.contribution(reachableMax(it.source, depth + 1)), 0) } * sub.maxCopies.coerceAtLeast(1)
                }
            }
            return v
        }

        // Upper bound of what [stat] gains OUTSIDE the pre-combat condition read: START-OF-COMBAT
        // sub effects (Influence vitale III's +12 crit ×2, Ravage III's crit mastery…), ramps (final
        // sheet only) and passives. An assume world credits its capped stat as a constant — the read is
        // ≤ threshold, but the FINAL stat the target reads adds these on top (review fix: the constant
        // used to stop at `threshold + own`, an under-count). [excludeEpics]: in an assume world the
        // assumed cap sub holds the single epic slot, so no other epic sub can contribute. [pricedElsewhere]
        // (T1): subs whose start-of-combat lines the fold already prices — their stat lines are skipped (their
        // ramps stay here, budget-free).
        fun outsideReadMax(
            stat: Characteristic,
            exclude: Sublimation?,
            excludeEpics: Boolean,
            pricedElsewhere: Set<Sublimation> = emptySet(),
        ): Long {
            var v = passivePos(stat).toLong()
            for (sub in sublimations) {
                if (!sub.solverChoosable || sub === exclude) continue
                if (excludeEpics && sub.rarity == SublimationRarity.EPIC) continue
                val copies = sub.maxCopies.coerceAtLeast(1).toLong()
                for (eff in sub.effects) {
                    when (eff) {
                        is SublimationEffect.StatEffect ->
                            if (sub !in pricedElsewhere &&
                                eff.characteristic == stat &&
                                !eff.appliesBeforeCombat &&
                                WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)
                            ) {
                                v += maxOf(eff.magnitudeAtLevel(level), 0).toLong() * copies
                            }
                        is SublimationEffect.PerStatStep ->
                            if (eff.target == stat && eff.source != stat) {
                                v += maxOf(eff.contribution(reachableMax(eff.source)), 0).toLong() * copies
                            }
                        else -> {}
                    }
                }
            }
            return v
        }

        // Sound M-cap for builds carrying a SECONDARY_MASTERIES_AT_MOST-t sub (review fix A#2): a
        // standalone budget knapsack over EVERY mastery source — maximize Σp (positive requested
        // lines, what M counts) s.t. Σ(p − q − n) ≤ t, where p − q − n per pick is a LOWER bound
        // of its contribution to the signed all-secondaries sum the condition reads (q = |negative
        // requested|, n = |negative unrequested|). Items are per-slot picks (rings twice); subs,
        // skills and mastery runes are optional pseudo-slots — their mastery PAYS the same budget
        // (an additive shortcut measured +102% on S2; the naive Σ-of-best-negatives offset, which
        // let budget items ride free of their mastery cost, measured +21%). Budget bucketed with
        // FLOOR (admits more ⇒ sound).
        fun secondaryBudgetCap(t: Long): Long {
            // EXACT budgets: a floor-20 bucketing across ~90 picks leaked ~1000 free budget
            // (measured +47% vs +21% on S2); the raw-unit DP stays tiny (≤ tens of thousands of
            // distinct budget keys).
            fun bucket(x: Long): Int = x.toInt()
            val pickLists = mutableListOf<List<Pair<Int, Long>>>()
            for ((slot, items) in pool) {
                val options =
                    items
                        .map { e ->
                            var p = 0L
                            var qn = 0L
                            for ((c, x) in e.characteristics) {
                                if (c !in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS) continue
                                if (c in requested && x > 0) p += x
                                if (x < 0) qn += -x.toLong()
                            }
                            bucket(p - qn) to p
                        }.plus(0 to 0L) // empty slot
                        .distinct()
                pickLists += options
                if (slot == ItemType.RING) pickLists += options
            }
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                // The condition reads the FIRST-TURN sheet: pre-combat + the start-of-combat lines of
                // UNCONDITIONAL FLAT subs (StatBuilder.firstTurnStat). Any other positive line (a
                // conditional sub's start-of-combat mastery, a ramp) lands outside the read: it adds
                // to M WITHOUT paying the budget (review fix — making it pay tightened the cap
                // below reachable M). Negatives always relax the budget (permissive, sound).
                val unconditionalFlat = sub.condition == null && sub.kind == SublimationKind.FLAT
                var p = 0L
                var pFree = 0L
                var qn = 0L
                for (eff in sub.effects) {
                    when (eff) {
                        is SublimationEffect.StatEffect -> {
                            if (eff.characteristic !in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS) continue
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            val x = eff.magnitudeAtLevel(level)
                            val inRead = eff.appliesBeforeCombat || unconditionalFlat
                            if (eff.characteristic in requested && x > 0) {
                                if (inRead) p += x else pFree += x
                            }
                            if (x < 0) qn += -x.toLong()
                        }
                        is SublimationEffect.PerStatStep ->
                            if (eff.target in requested && eff.source != eff.target) {
                                pFree += maxOf(eff.contribution(reachableMax(eff.source)), 0).toLong()
                            }
                        else -> {}
                    }
                }
                if (p == 0L && pFree == 0L && qn == 0L) continue
                repeat(sub.maxCopies.coerceAtLeast(1)) { pickLists += listOf(0 to 0L, bucket(p - qn) to (p + pFree)) }
            }
            // Passives sit outside every condition read: their requested mastery is always-on, free.
            val passiveMastery = requested.sumOf { passivePos(it).toLong() }
            if (passiveMastery > 0) pickLists += listOf(0 to passiveMastery)
            val skills = params.character.characterSkills
            for (branch in listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major)) {
                val v =
                    branch
                        .getCharacteristics()
                        .flatMap(::skillComponents)
                        .filter { it.characteristic in requested && it.unitType == me.chosante.common.skills.UnitType.FIXED }
                        .maxOfOrNull { (it.unitValue * minOf(branch.maxPointsToAssign, it.maxPointsAssignable)).toLong() } ?: 0L
                if (v > 0) pickLists += listOf(0 to 0L, bucket(v) to v)
            }
            if (params.useRunes && "noRunes" !in diag) {
                val matching = runes.filter { it.characteristic in requested }
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

        // Max total the requested masteries OTHER than crit mastery can reach: the
        // CRITICAL_MASTERY_AT_MOST condition caps only the crit component of M.
        fun otherRequestedMasteriesMax(): Long = requested.filter { it != Characteristic.MASTERY_CRITICAL }.sumOf { reachableMax(it).toLong() }

        var epicRelicStages: (() -> Unit)? = null
        // The assumed cap sub's credits (assume worlds only) — applied at collapse.
        var assumedOpt = Opt(0L, 0)
        // Sublimations: caps-only knapsack per rarity (10/1/1) with epic/relic carrier binding —
        // v1's machinery extended with the target axes. Objective-capping subs go to world B.
        // Review fix (A#2): world B's M-cap is NOT the raw condition threshold — see the per-type
        // corrections below (the old capT under-stated M on multi-requested / negative-offset
        // shapes, an under-count of the bound).
        // World-B subs (objective-capping conditions): per-sub (M-cap, credits) pairs, folded
        // PER STATE at collapse — the old analytic fold at power6(maxIndex) assumed all targets
        // fully met, which a knapsack-limited carrier cannot do (measured +24.6% vs +21% naive).
        val worldBSubs = mutableListOf<Triple<Long, Opt, Boolean>>()
        // T1: the subs whose start-of-combat crit the fold already prices — staged subs (soc dim) and world-B subs
        // (their credits ride the per-state `extra`) — so the assume-CC constant [outsideReadMax] skips them.
        val socPricedSubs = HashSet<Sublimation>()
        if (params.useSublimations && "noSubs" !in diag) {
            data class SubOpt(
                val opt: Opt,
                val rarity: SublimationRarity,
            )
            val subOpts = mutableListOf<SubOpt>()
            for (sub in sublimations) {
                if (!stagesSub(sub, diag)) continue
                val cond = sub.condition
                val capsObjective = isObjectiveCapping(sub, requested)
                // Review fix (A#1): AT_MOST cap subs never enter the stages — the world split
                // handles them (excluded in every world; the assumed one is credited at collapse).
                if (capStatOf(sub) != null && sub !== worldAssume) continue
                val blockRequirement =
                    if (blockAtLeastMax > 0 && cond?.type == SublimationConditionType.BLOCK_AT_LEAST) (cond.value ?: 0) else 0
                var opt = Opt(0L, 0, requiresBlockAtLeast = blockRequirement, src = if (wantSrc) sub.name.fr else "")
                // A Conversion credits nothing here: [requestShape] bailed on every staged sub converting INTO a
                // stat the DP reads, so the moved value only ever lands on an untracked stat (its debit on the
                // `from` stat is dropped — an over-count).
                for (eff in sub.effects) {
                    when (eff) {
                        is SublimationEffect.StatEffect -> {
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            val value = eff.magnitudeAtLevel(level)
                            // A negative MAX_MOVEMENT_POINT rider (Armure lourde II) is a flat MP debit
                            // in the solver (foldedToUsableStat). It used to LOWER the MP cap instead,
                            // which under-credited carriers whose pre-sub MP overshoots the target by ≥ 1
                            // (review fix 2026-10-01): ignoring the debit over-counts MP — sound.
                            if (eff.characteristic == Characteristic.MAX_MOVEMENT_POINT && value < 0) continue
                            if (value <= 0) {
                                // T5: NET DI per sub — a build takes the sub whole, so its DI lines sum
                                // exactly (Vélocité II's −10 rider is real whenever its +1 MP is). Kept signed
                                // only inside the exact normal packing; clamped at 0 below for every other sub.
                                if (value < 0 && eff.characteristic == Characteristic.DAMAGE_INFLICTED) {
                                    opt = opt.copy(d = opt.d + value)
                                }
                                // LOW dims (A#1): a NEGATIVE AP/CC line still lowers the real stat a
                                // condition reads — feed it to the under-approximating dims (permissive
                                // even when the line sits outside the read). MAX_ACTION_POINT folds into
                                // the pre-combat AP read like on items (Carapace II's −1).
                                if (value < 0 &&
                                    geo.assumeApThreshold >= 0 &&
                                    (eff.characteristic == Characteristic.ACTION_POINT || eff.characteristic == Characteristic.MAX_ACTION_POINT)
                                ) {
                                    opt = opt.copy(apLow = opt.apLow + value)
                                }
                                if (value < 0 && geo.assumeCcThresholdRaw >= 0 && eff.characteristic == Characteristic.CRITICAL_HIT) {
                                    opt = opt.copy(ccLowRaw = opt.ccLowRaw + value)
                                }
                                continue
                            }
                            // A POSITIVE line raises a condition's pre-combat read only when it is
                            // permanent (appliesBeforeCombat); start-of-combat lines land after the
                            // read, so feeding them to a LOW dim over-rejected real carriers (review fix).
                            val inPreCombatRead = eff.appliesBeforeCombat
                            opt =
                                when {
                                    eff.characteristic in requested -> opt.copy(m = opt.m + value)
                                    eff.characteristic == Characteristic.DAMAGE_INFLICTED -> opt.copy(d = opt.d + value)
                                    eff.characteristic == Characteristic.ACTION_POINT ->
                                        opt.copy(
                                            ap = opt.ap + value,
                                            apLow = opt.apLow + (if (geo.assumeApThreshold >= 0 && inPreCombatRead) value else 0)
                                        )
                                    eff.characteristic == Characteristic.MOVEMENT_POINT -> opt.copy(mp = opt.mp + value)
                                    eff.characteristic == Characteristic.CRITICAL_HIT ->
                                        opt.copy(
                                            cc = opt.cc + value,
                                            ccLowRaw = opt.ccLowRaw + (if (geo.assumeCcThresholdRaw >= 0 && inPreCombatRead) value else 0),
                                            // T1: a start-of-combat line lands OUTSIDE the read — priced on the soc dim.
                                            ccSoc = opt.ccSoc + (if (geo.socCap > 0 && !inPreCombatRead) value else 0)
                                        )
                                    eff.characteristic == Characteristic.HP -> opt.copy(hp = opt.hp + value)
                                    eff.characteristic == Characteristic.BLOCK_PERCENTAGE && blockAtLeastMax > 0 ->
                                        opt.copy(block = opt.block + value)
                                    // RANGE (Visibilité II's +1, Furie II's credited-as-held +1): positive lines only,
                                    // a −1 rider (Combat rapproché II) is dropped — an over-count of the dim.
                                    eff.characteristic == Characteristic.RANGE && geo.rangeCap > 0 -> opt.copy(range = opt.range + value)
                                    else -> opt
                                }
                        }

                        is SublimationEffect.PerStatStep -> {
                            if ("noRamps" in diag) continue
                            // Increment 6: the tracked MP→DI ramp defers to collapse (state MP).
                            if (mpDiRamp != null && eff === mpDiRamp.second) {
                                opt = opt.copy(ramp = true)
                                continue
                            }
                            val credit = eff.contribution(reachableMax(eff.source))
                            if (credit <= 0) continue
                            opt =
                                when {
                                    eff.target in requested -> opt.copy(m = opt.m + credit)
                                    eff.target == Characteristic.DAMAGE_INFLICTED -> opt.copy(d = opt.d + credit)
                                    eff.target == Characteristic.ACTION_POINT -> opt.copy(ap = opt.ap + credit)
                                    eff.target == Characteristic.MOVEMENT_POINT -> opt.copy(mp = opt.mp + credit)
                                    eff.target == Characteristic.CRITICAL_HIT -> opt.copy(cc = opt.cc + credit)
                                    eff.target == Characteristic.HP -> opt.copy(hp = opt.hp + credit)
                                    eff.target == Characteristic.RANGE && geo.rangeCap > 0 -> opt.copy(range = opt.range + credit)
                                    else -> opt
                                }
                        }

                        else -> {}
                    }
                }
                // T5: only the exact normal packing tracks a signed DI (its per-subset sum is exact, and the DP's
                // DI dim handles the negative delta — [nextDi]); every other sub keeps a NET DI clamped at 0 (an
                // over-count of its real net, so sound, and never a negative fold-time extra).
                val entersNormalPacking =
                    sub.rarity == SublimationRarity.NORMAL &&
                        !capsObjective &&
                        sub !== worldAssume &&
                        opt.capKind == 0 &&
                        opt.mpCapMinus == 0 &&
                        !opt.ramp &&
                        opt.requiresBlockAtLeast == 0
                if (opt.d < 0 && !entersNormalPacking) opt = opt.copy(d = 0)
                if (capsObjective) {
                    // The sound M-cap for a build CARRYING this sub:
                    //  - SECONDARY_MASTERIES_AT_MOST t: the budget knapsack over every mastery
                    //    source (maximize Σ positive requested lines s.t. the signed
                    //    all-secondaries sum stays ≤ t);
                    //  - CRITICAL_MASTERY_AT_MOST t: caps only the crit component, so M ≤ t + the
                    //    other requested masteries' reachable max.
                    // Its credits (DI/CC/…) ride along and are folded per state at collapse.
                    val t = (cond?.value ?: 0).toLong()
                    val mCap =
                        if (cond?.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST) {
                            secondaryBudgetCap(t)
                        } else {
                            // The condition reads PRE-COMBAT crit mastery; M counts the final one,
                            // which adds start-of-combat crit mastery (Ravage III), ramps and passives
                            // on top of the read (review fix: `t + others` alone under-counted).
                            t +
                                outsideReadMax(Characteristic.MASTERY_CRITICAL, exclude = sub, excludeEpics = sub.rarity == SublimationRarity.EPIC) +
                                otherRequestedMasteriesMax()
                        }
                    worldBSubs += Triple(mCap, opt, sub.rarity == SublimationRarity.EPIC)
                    socPricedSubs += sub
                    continue
                }
                if (sub === worldAssume) {
                    // The assumed cap sub: credited at collapse, never staged (its own capped-stat
                    // contribution thereby never feeds its own condition — the game's rule).
                    assumedOpt = opt
                    continue
                }
                // Block-only subs (Dérobade continue III) stay: their block feeds the AT_LEAST gate. So do
                // LOW-read-only subs (Carapace II's −1 MAX_AP): in an assume world they open an AT_MOST read
                // (review fix, plan §8.18 — dropping them denied a real Inflexibilité carrier its +DI, −21% on
                // the `low-read-only` lock). A sub whose only effect is a NEGATIVE DI is dominated by not
                // carrying it, so it is dropped.
                if (opt.m == 0L &&
                    opt.d <= 0 &&
                    opt.ap == 0 &&
                    opt.mp == 0 &&
                    opt.cc == 0 &&
                    opt.hp == 0 &&
                    opt.block == 0 &&
                    opt.range == 0 &&
                    opt.apLow == 0 &&
                    opt.ccLowRaw == 0 &&
                    !opt.ramp
                ) {
                    continue
                }
                repeat(sub.maxCopies.coerceAtLeast(1)) { subOpts += SubOpt(opt, sub.rarity) }
                socPricedSubs += sub
            }

            /**
             * T5 (plan §8.18) — the 10-slot NORMAL knapsack, EXACT per subset: every axis summed RAW (three +3 CC
             * copies used to be credited a full 10-CC bucket each) and DI SIGNED (a carried sub's negative rider —
             * Vélocité II's −10 — pays against the subset's other DI); each axis is rounded once, when the packed
             * option enters the DP ([applyOneRaw], [nextDi]). Sound: a subset's sums are exact up to saturation at
             * the dims' own caps (the over-counted axes only ever grow, and DI is never saturated before its sum is
             * complete). The port of MaxDamageSoftCertificate's `exactNormalSubPacking`.
             */
            fun exactNormalPacking(capCount: Int): List<Opt> {
                // Flag-carrying subs never enter the packing (it would erase their flags) — they are
                // applied as their own stages below.
                val opts =
                    subOpts
                        .filter {
                            it.rarity == SublimationRarity.NORMAL &&
                                it.opt.capKind == 0 &&
                                it.opt.mpCapMinus == 0 &&
                                !it.opt.ramp &&
                                it.opt.requiresBlockAtLeast == 0
                        }.map { it.opt }
                if (opts.isEmpty()) return listOf(Opt(0L, 0))
                // LOW dims (A#1): the packing does not track per-subset signed sums, so every emitted
                // aggregate carries the pool-wide NEGATIVE parts — a valid lower bound of ANY
                // subset's contribution (positives deliberately uncounted: lenient, still sound).
                val knapApNeg = opts.sumOf { minOf(it.apLow, 0) }
                val knapCcNeg = opts.sumOf { minOf(it.ccLowRaw, 0) }

                data class PackKey(
                    val count: Int,
                    val d: Int,
                    val ap: Int,
                    val mp: Int,
                    val cc: Int,
                    val hp: Int,
                    val block: Int,
                    val soc: Int,
                    val range: Int,
                )
                val ccRawCap = geo.ccBucketCap * ccStep
                val hpRawCap = geo.hpBucketCap * hpStep
                val blockRawCap = geo.blockBucketCap * BLOCK_STEP
                val socRawCap = geo.socCap * geo.socStep
                val zero = PackKey(0, 0, 0, 0, 0, 0, 0, 0, 0)
                var frontier = HashMap<PackKey, Long>().apply { put(zero, 0L) }
                var names = if (wantSrc) HashMap<PackKey, String>().apply { put(zero, "") } else null
                for (o in opts) {
                    val next = HashMap(frontier)
                    val nextNames = names?.let { HashMap(it) }
                    for ((k, mv) in frontier) {
                        if (k.count >= capCount) continue
                        val nk =
                            PackKey(
                                count = k.count + 1,
                                // Exact and unsaturated: saturating before a later negative rider could under-count.
                                d = k.d + o.d,
                                ap = (k.ap + o.ap).coerceAtMost(geo.apCap),
                                mp = (k.mp + o.mp).coerceAtMost(geo.mpCap),
                                cc = (k.cc + o.cc).coerceAtMost(ccRawCap),
                                hp = (k.hp + o.hp).coerceAtMost(hpRawCap),
                                block = (k.block + o.block).coerceAtMost(blockRawCap),
                                soc = (k.soc + o.ccSoc).coerceAtMost(socRawCap),
                                range = (k.range + o.range).coerceAtMost(geo.rangeCap)
                            )
                        val nm = mv + o.m
                        val cur = next[nk]
                        if (cur == null || nm > cur) {
                            next[nk] = nm
                            nextNames?.set(nk, listOfNotNull(names?.get(k)?.ifEmpty { null }, o.src.ifEmpty { null }).joinToString("+"))
                        }
                    }
                    frontier = next
                    names = nextNames
                }
                return frontier.map { (k, mv) ->
                    Opt(
                        m = mv,
                        d = k.d,
                        ap = k.ap,
                        mp = k.mp,
                        cc = k.cc,
                        hp = k.hp,
                        block = k.block,
                        apLow = knapApNeg,
                        ccLowRaw = knapCcNeg,
                        ccSoc = k.soc,
                        range = k.range,
                        src = if (wantSrc) names?.get(k).orEmpty().ifEmpty { "NORMAL x${k.count}" } else ""
                    )
                }
            }
            step("subs-normal", exactNormalPacking(10))
            // Flag-carrying NORMAL subs (Armure lourde's MAX_MP−1 rider, the Poids Plume ramp): one
            // stage each, on top of the 10-cap knapsack — over-counts the shared slot budget by ≤
            // the handful of such subs (slots are not scarce for the axes we track; sound).
            for (flagged in subOpts.filter {
                it.rarity == SublimationRarity.NORMAL &&
                    (it.opt.capKind != 0 || it.opt.mpCapMinus != 0 || it.opt.ramp || it.opt.requiresBlockAtLeast != 0)
            }) {
                step("sub-flagged:${flagged.opt.src.ifEmpty { "?" }}", listOf(Opt(0L, 0), flagged.opt))
            }

            // EPIC / RELIC: cap 1 ⇒ a plain option list (no packing), which also PRESERVES the cap
            // flags of the increment-4 conditional epics (Inflexibilité, Constance, Mesure III). Staged after the
            // skills, so their flat HP takes the %HP scaling here ([hpScaled]).
            fun singleSlotOptions(rarity: SublimationRarity): List<Opt> =
                listOf(Opt(0L, 0)) +
                    prune(
                        subOpts.filter { it.rarity == rarity }.map {
                            it.opt.copy(
                                hp = hpScaled(it.opt.hp),
                                requiresEpicItem = rarity == SublimationRarity.EPIC,
                                requiresRelicItem = rarity == SublimationRarity.RELIC
                            )
                        }
                    )
            // Increment 8: the EPIC/RELIC stages move AFTER the skills stage (see below) so an
            // AT_LEAST condition gates on the FULL over-counted final sheet — gating any earlier
            // could wrongly deny a real build whose later layers supply the missing block.
            epicRelicStages = {
                // Assume worlds: the assumed cap sub (always EPIC) occupies the single epic-sub
                // slot — offering another epic sub would let a state carry two (illegal).
                step("subs-epic", if (worldAssume != null) listOf(Opt(0L, 0)) else singleSlotOptions(SublimationRarity.EPIC))
                step("subs-relic", singleSlotOptions(SublimationRarity.RELIC))
                if (debug) println("MM_M3V2_STAGE subs states=${states.size}")
            }
        }

        // Selected passives (review fix 2026-10-01): an always-on stage BEFORE the skills — the
        // solver's %HP skill scales the final HP, passives included. Outside every condition read, so
        // they never feed the LOW dims (the assume-world folds add them as constants instead).
        if (passiveFlat.isNotEmpty()) {
            step(
                "passives",
                listOf(
                    Opt(
                        m = requested.sumOf { passivePos(it).toLong() },
                        d = passivePos(Characteristic.DAMAGE_INFLICTED),
                        ap = passivePos(Characteristic.ACTION_POINT),
                        mp = passivePos(Characteristic.MOVEMENT_POINT),
                        cc = passivePos(Characteristic.CRITICAL_HIT),
                        hp = passivePos(Characteristic.HP),
                        block = if (blockAtLeastMax > 0) passivePos(Characteristic.BLOCK_PERCENTAGE) else 0,
                        range = if (geo.rangeCap > 0) passivePos(Characteristic.RANGE) else 0,
                        src = if (wantSrc) "passives" else ""
                    )
                )
            )
        }

        // Skills — AFTER the subs stage, because the %HP skill multiplies the build's WHOLE HP
        // (base + items + subs); applying it earlier would under-count the subs' flat HP share.
        // Per-branch enumeration over the ≤5 objective/target-relevant skills; FIXED skills credit
        // points × unitValue on their axis, %HP is multiplicative on the dim, other PERCENT skills
        // credit ceil(reachableMax × pts × unitValue / 100). Granularity: exact for small branches,
        // else ceil-to-step (a partial step credited as full — over-count, sound).
        if ("noSkills" !in diag) {
            val skills = params.character.characterSkills

            fun relevantChar(c: Characteristic?): Boolean =
                c != null &&
                    (
                        c in requested ||
                            c == Characteristic.DAMAGE_INFLICTED ||
                            c in targetByChar ||
                            (blockAtLeastMax > 0 && c == Characteristic.BLOCK_PERCENTAGE) ||
                            // The tracked MP→DI ramp reads the MP dim even without an MP target.
                            (mpDiRamp != null && c == Characteristic.MOVEMENT_POINT)
                    )
            // Intelligence LAST: its %HP multiplies the whole flat HP, the Strength HP points included
            // — applied before them it left them unscaled, an HP under-count (pre-release review
            // 2026-10-01, caught by the banded review lock: %HP 50 + HP 48 reached 9 030 HP in CP-SAT).
            for (branch in listOf(skills.strength, skills.agility, skills.luck, skills.major, skills.intelligence)) {
                // A paired skill is relevant when EITHER half is (one point buys both halves).
                val relevant = branch.getCharacteristics().filter { sk -> skillComponents(sk).any { relevantChar(it.characteristic) } }
                if (relevant.isEmpty()) continue
                val budget = branch.maxPointsToAssign
                // Step 1 (exact) everywhere: the ceil-to-step credit was a sound over-count kept
                // for state-count reasons that the coarse grid has since erased.
                val step = 1

                fun creditOf(
                    sk: SkillCharacteristic,
                    pts: Int,
                ): Opt {
                    // A paired skill credits BOTH halves for the same points — the solver's
                    // buildSkillTerms does (review fix: the "elemental-only" assumption was wrong,
                    // Major "Movement Point and damage" carries +1 MP).
                    if (sk is SkillCharacteristic.PairedCharacteristic) {
                        return combineOpts(creditOf(sk.first, pts), creditOf(sk.second, pts))
                    }
                    // Irrelevant halves (elemental mastery, control, an untargeted range) feed none of our axes.
                    val skChar = sk.characteristic?.takeIf(::relevantChar) ?: return Opt(0L, 0)
                    // %HP scales the build's own HP — expressed multiplicatively on the HP dim (far
                    // tighter than a flat credit at the layer-independent reachable max, still sound).
                    if (sk.unitType == me.chosante.common.skills.UnitType.PERCENT && skChar == Characteristic.HP) {
                        return Opt(0L, 0, hpPct = pts * sk.unitValue, src = if (wantSrc && pts > 0) "%HP:$pts" else "")
                    }
                    val value =
                        if (sk.unitType == me.chosante.common.skills.UnitType.PERCENT) {
                            ceil(reachableMax(skChar).coerceAtLeast(0) * pts.toLong() * sk.unitValue / 100.0).toLong()
                        } else {
                            pts.toLong() * sk.unitValue
                        }
                    val v = value.coerceAtLeast(0L).toInt()
                    val srcTag = if (wantSrc && pts > 0) "${skChar.name}:$pts" else ""
                    return when {
                        skChar in requested -> Opt(m = value.coerceAtLeast(0L), d = 0, src = srcTag)
                        skChar == Characteristic.DAMAGE_INFLICTED -> Opt(0L, v, src = srcTag)
                        skChar == Characteristic.ACTION_POINT ->
                            Opt(0L, 0, ap = v, apLow = if (geo.assumeApThreshold >= 0) v else 0, src = srcTag)
                        skChar == Characteristic.MOVEMENT_POINT -> Opt(0L, 0, mp = v, src = srcTag)
                        skChar == Characteristic.CRITICAL_HIT ->
                            Opt(0L, 0, cc = v, ccLowRaw = if (geo.assumeCcThresholdRaw >= 0) v else 0, src = srcTag)
                        skChar == Characteristic.BLOCK_PERCENTAGE -> Opt(0L, 0, block = v, src = srcTag)
                        skChar == Characteristic.HP -> Opt(0L, 0, hp = v, src = srcTag)
                        // Major "Range and damage" (+1 range, paired with +40 elemental mastery).
                        skChar == Characteristic.RANGE -> Opt(0L, 0, range = v, src = srcTag)
                        else -> Opt(0L, 0)
                    }
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
                            pts += step
                        }
                    }
                    options = next
                }
                step("skills-${branch.javaClass.simpleName}", options.map { it.first }.distinct())
                if (debug) println("MM_M3V2_STAGE skills-${branch.javaClass.simpleName} states=${states.size} options=${options.size}")
            }
        }

        // Increment 8: EPIC/RELIC subs land after every other block source (items/normal subs/skills).
        epicRelicStages?.invoke()
        if (cancelled) return null

        // Collapse: mirror applyConstraintPenalty/bucketedIndex/buildPowerTable arithmetic exactly
        // (shared with [fullTargetsMultiplier], so the hard-leg unit conversion can never drift).
        val penalty = penaltyGeometry(params, targets)
        val totalExpected = penalty.totalExpected
        val bucketSize = penalty.bucketSize
        val maxIndex = penalty.maxIndex

        // Precomputed like the solver's buildPowerTable: the collapse calls this once per state
        // (millions on the full tier) and per-call BigInteger pow/divide was measurable GC churn.
        val powTable = LongArray(maxIndex + 1) { i -> penalty.power6(i) }

        fun power6(index: Int): Long = powTable[index]

        fun weight(char: Characteristic): Long = targetByChar[char]?.let { params.targetStats.scaledWeight(it) } ?: 0L

        fun targetOf(char: Characteristic): Long = targetByChar[char]?.target?.toLong() ?: 0L

        // Precompute the world-B SUBSET folds (2^n − 1, minus impossible two-EPIC combos): the
        // combined credits + the min cap of each subset, shared by every state's collapse. ([requestShape] bails
        // on more than 6 world-B subs.)
        check(worldBSubs.size <= 6) { "requestShape admitted ${worldBSubs.size} world-B subs" }
        val worldBSubsets: List<Pair<Long, Opt>> =
            (1 until (1 shl worldBSubs.size)).mapNotNull { mask ->
                val members = worldBSubs.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
                if (members.count { it.third } > 1) return@mapNotNull null
                val mCap = members.minOf { it.first }
                val combined = members.map { it.second }.reduce { a, b -> combineOpts(a, b) }
                mCap to combined
            }

        // Assume worlds credit their capped stat as a constant: the condition read is ≤ threshold, and
        // the FINAL stat the target reads adds what lands outside that read (start-of-combat lines,
        // ramps, passives — see [outsideReadMax]). Computed once per world, not per state.
        val apOutsideRead = if (geo.assumeApThreshold >= 0) outsideReadMax(Characteristic.ACTION_POINT, worldAssume, excludeEpics = true) else 0L
        // T1: with the soc dim tracked, the staged and world-B subs' start-of-combat crit is priced per state
        // (the dim / `extra`) — only passives, ramps and never-staged subs stay in this budget-free constant (it
        // used to credit every choosable sub's crit at max copies: Mesure III's world read CC ~92 of the 100
        // target on S2 for free, plan §8.16).
        val ccOutsideRead =
            if (geo.assumeCcThresholdRaw >= 0) {
                outsideReadMax(
                    Characteristic.CRITICAL_HIT,
                    worldAssume,
                    excludeEpics = true,
                    pricedElsewhere = if (geo.socCap > 0) socPricedSubs else emptySet()
                )
            } else {
                0L
            }

        // T3 (plan §8.18): the TARGETS-MET read rides the same collapse. A most-masteries HARD-leg result is the
        // optimum among builds with `actual ≥ target` for every required target > 0 (StatBuilder.
        // addRequiredTargetHardConstraints), and such a build's soft objective is `core × fullMultiplier × SCALE +
        // bonus` — the hard leg's converted stamp. Every read below is an over-count of the build's FINAL stat
        // (or ≥ the target once saturated at it), so a targets-met build's own state passes `read ≥ target` on every
        // target: filtering the other states out keeps a sound bound OF THAT SET, while the soft read must also
        // cover target-missing builds the hard leg never returns (+42% vs +24% on S2, plan §8.18).
        val fullMultiplier = power6(penalty.fullBucket)
        val hardChecked = targets.filter { it.target > 0 }
        var bestHardCore = 0L
        var bestHardFolded = 0L
        var hardBindingState = ""

        var bestCore = 0L
        var bestFolded = 0L
        var bindingState = ""
        var bindingKey = 0L
        var bindingM = 0L
        for ((k, mv) in states) {
            // ASSUME-world filters (A#1): the assumed cap sub is EPIC (needs an epic item), and the
            // condition must hold on the final sheet — the capped stat's LOW dim (real ≥ dim,
            // self-exclusion built in: the assumed sub was never staged) must stay ≤ the threshold.
            if (worldAssume != null) {
                if (geo.e(k) == 0) continue
                if (geo.assumeApThreshold >= 0 && geo.ap(k) > geo.assumeApThreshold) continue
                if (geo.assumeCcThresholdRaw >= 0 && geo.cc(k) > geo.assumeCcThresholdRaw) continue
            }
            // Increment 6: the deferred MP→DI ramp lands here at the PATH's own MP (the dim
            // over-counts real MP and contribution() is monotone — sound).
            val rampDi =
                if (geo.ramp(k) == 1 && mpDiRamp != null) {
                    maxOf(mpDiRamp.second.contribution((geo.mp(k) + assumedOpt.mp).coerceAtMost(geo.mpCap)), 0)
                } else {
                    0
                }

            // The per-state fold, parameterized by an extra credits layer ([extra] = the assumed
            // cap sub and/or a world-B sub) and an optional M-cap (world-B condition). The fold
            // reads: in an assume world the CAPPED stat's dim is a LOW read (using it would
            // UNDER-count) — credit it as the constant `threshold + the subs' own positive
            // contribution` instead (real final stat ≤ that; min(·, target) keeps it sound).
            fun foldWith(
                extra: Opt,
                mCap: Long?,
            ): Fold {
                val mvX = (mv + assumedOpt.m + extra.m).let { if (mCap != null) minOf(it, mCap) else it }
                val di = (geo.d(k).toLong() * diStep + assumedOpt.d + extra.d + rampDi).coerceAtMost(diCap.toLong())
                val core = (maxOf(mvX, 0L) * (100L + di) / 100L).coerceAtMost(MASTERY_SCORE_ABS_MAX)
                if (targets.isEmpty()) return Fold(core, core, targetsMet = true)
                val apRead =
                    if (geo.assumeApThreshold >= 0) {
                        (geo.assumeApThreshold + maxOf(assumedOpt.ap, 0)).toLong() + apOutsideRead + extra.ap
                    } else {
                        geo.ap(k).toLong() + assumedOpt.ap + extra.ap
                    }
                val ccRead =
                    if (geo.assumeCcThresholdRaw >= 0) {
                        (assumeThreshold + maxOf(assumedOpt.cc, 0)).toLong() + ccOutsideRead + extra.cc +
                            // T1: the start-of-combat crit this path carries (0 when untracked).
                            geo.soc(k).toLong() * geo.socStep
                    } else {
                        geo.cc(k).toLong() * ccStep + assumedOpt.cc + extra.cc
                    }
                val mpRead = geo.mp(k).toLong() + assumedOpt.mp + extra.mp
                // The assumed / world-B subs' flat HP lands after the skills stage: %HP-scaled here ([hpScaled]).
                val hpRead = geo.hp(k).toLong() * hpStep + hpScaled(assumedOpt.hp + extra.hp)
                val rangeRead = geo.range(k).toLong() + assumedOpt.range + extra.range
                // One term per TRACKED row (an untracked stat weighs 0 here, exactly like a 0-valued row in the model).
                val totalActual =
                    weight(Characteristic.ACTION_POINT) * minOf(apRead, targetOf(Characteristic.ACTION_POINT)) +
                        weight(Characteristic.MOVEMENT_POINT) * minOf(mpRead, targetOf(Characteristic.MOVEMENT_POINT)) +
                        weight(Characteristic.CRITICAL_HIT) * minOf(ccRead, targetOf(Characteristic.CRITICAL_HIT)) +
                        weight(Characteristic.HP) * minOf(hpRead, targetOf(Characteristic.HP)) +
                        weight(Characteristic.RANGE) * minOf(rangeRead, targetOf(Characteristic.RANGE))
                val bucket = (totalActual.coerceIn(1L, totalExpected) / bucketSize).toInt().coerceAtMost(maxIndex)
                val targetsMet =
                    hardChecked.all {
                        val read =
                            when (it.characteristic) {
                                Characteristic.ACTION_POINT -> apRead
                                Characteristic.MOVEMENT_POINT -> mpRead
                                Characteristic.CRITICAL_HIT -> ccRead
                                Characteristic.HP -> hpRead
                                Characteristic.RANGE -> rangeRead
                                // Unreachable: a row with target > 0 is tracked, so in SUPPORTED_TARGETS.
                                else -> error("untracked required target ${it.characteristic}")
                            }
                        read >= it.target
                    }
                return Fold(core, core * power6(bucket) * OVERSHOOT_SCALE_MIRROR + (OVERSHOOT_SCALE_MIRROR - 1), targetsMet)
            }

            fun consider(
                extra: Opt,
                mCap: Long?,
                tag: String,
            ) {
                val fold = foldWith(extra, mCap)
                val core = fold.core

                fun describe() =
                    "M=${mv + assumedOpt.m + extra.m} d=${geo.d(k) * diStep}+ramp$rampDi ap=${geo.ap(k)} mp=${geo.mp(k)} " +
                        "cc=${geo.cc(k) * ccStep} soc=${geo.soc(k) * geo.socStep} hp=${geo.hp(k) * hpStep} range=${geo.range(k)} " +
                        "e=${geo.e(k)} r=${geo.r(k)} assume=${worldAssume?.name?.fr ?: "-"}$tag mpMinus=${geo.mpMinus(k)} core=$core"
                if (core > bestCore) bestCore = core
                if (fold.folded > bestFolded) {
                    bestFolded = fold.folded
                    bindingKey = k
                    bindingM = mv
                    bindingState = describe()
                }
                if (fold.targetsMet) {
                    if (core > bestHardCore) bestHardCore = core
                    // Without required targets the two legs coincide (bare core); else the full-targets fold.
                    val hardFolded = if (targets.isEmpty()) core else core * fullMultiplier * OVERSHOOT_SCALE_MIRROR + (OVERSHOOT_SCALE_MIRROR - 1)
                    if (hardFolded > bestHardFolded) {
                        bestHardFolded = hardFolded
                        hardBindingState = describe()
                    }
                }
            }

            consider(EMPTY_OPT, null, "")
            // World B PER STATE (A#2), SUBSET fold (review 2026-07-15): a real build can carry
            // SEVERAL objective-capping subs (the Neutralité family is NORMAL rarity) and their
            // credits STACK — folding one sub at a time under-counted multi-carrier builds. Every
            // non-empty subset (two-EPIC ones are impossible and skipped) folds with the SUM of
            // its members' credits and the MIN of their caps (each condition bounds M on its own).
            for ((mCapB, bOpt) in worldBSubsets) consider(bOpt, mCapB, " worldB(cap=$mCapB)")
        }
        // Instrument: reconstruct the binding path backward — for each stage (last → first), find a
        // predecessor state + option that lands exactly on the current (key, m). Deterministic by
        // construction (the forward pass kept the max m per key).
        val bindingPath = mutableListOf<String>()
        if (provenance && stageLog != null && bindingState.isNotEmpty()) {
            var curK = bindingKey
            var curM = bindingM
            for ((label, preMap, options) in stageLog.reversed()) {
                var found = false
                outer@ for ((pk, pm) in preMap) {
                    for (o in options) {
                        if (pm + o.m != curM) continue
                        if (geo.applyOne(pk, o) == curK) {
                            if (o.src.isNotEmpty() || o.m != 0L || o.d != 0 || o.ap != 0 || o.mp != 0 || o.cc != 0 || o.hp != 0 || o.range != 0) {
                                bindingPath +=
                                    "$label: ${o.src.ifEmpty { "opt" }} " +
                                    "(m=${o.m} d=${o.d} ap=${o.ap} mp=${o.mp} cc=${o.cc} hp=${o.hp} hpPct=${o.hpPct} range=${o.range})"
                            }
                            curK = pk
                            curM = pm
                            found = true
                            break@outer
                        }
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
            hardFoldedBound = bestHardFolded,
            hardCoreBound = bestHardCore,
            hardBindingState = hardBindingState
        )
    }

    /** One collapse fold of a state: its core, its SOFT folded value, and whether its reads meet every required target > 0. */
    private class Fold(
        val core: Long,
        val folded: Long,
        val targetsMet: Boolean,
    )

    private val OVERSHOOT_SCALE_MIRROR = WakfuBuildSolver.OVERSHOOT_SCALE
    private val EMPTY_OPT = Opt(0L, 0)
}
