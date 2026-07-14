package me.chosante.autobuilder.genetic.wakfu

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
 * M3-v2 PROTOTYPE (plan §8.9 amendment, maintainer GO 2026-07-12) — a TARGET-AWARE sound upper
 * bound on the most-masteries SOFT folded objective:
 *
 *   `⌊max(M,0) × (100 + clamp(D)) / 100⌋ × power6(bucket(totalActual)) × SCALE + (SCALE − 1)`
 *
 * [MostMasteriesBoundPrototype] (v1) bounded the unpenalized core only, so any targets-met
 * incumbent sat below it by the crit/AP-dump gap (+2.6% on F5). v2 tracks the required-target
 * achievements INSIDE the DP state, so mastery bought by dumping targets pays its penalty bucket:
 * state = (DI, AP, MP, CC, HP, epicItem, relicItem) → best M, with the achievement dims SATURATED
 * at their target (per-stat clamp in `totalActualScore`) and BUCKETED UP (an over-count of the
 * achievement can only raise the multiplier — sound).
 *
 * Sound-by-construction relaxations (each only ever RAISES the bound):
 *  - negative stat lines dropped everywhere;
 *  - per-item rune compositions are EXACT over the socket count and the available rune axes
 *    (mixing mastery + target runes on one item is a real build shape, so omitting mixes would
 *    UNDER-count — they are enumerated);
 *  - skills: per-branch enumeration over the objective-relevant skills, points credited in
 *    ceil-to-step granularity (step 1 when the branch is small); PERCENT skills credited at the
 *    layer-independent reachable max of their stat;
 *  - sublimations: v1's caps-only knapsack (10/1/1, epic/relic carrier binding), extended with the
 *    target axes; conditional subs credited as if their condition held; objective-capping subs go
 *    to world B (M ≤ threshold at the max multiplier);
 *  - ramps (perStatStep) priced at the sound reachable max of their source.
 *
 * Bails (null) instead of guessing: elemental-mastery requests (min-over-elements out of scope),
 * forced items/runes/subs, a required target outside {AP, MP, CC, HP}, a conversion into a
 * requested mastery.
 *
 * PRODUCTION (backup certificate, plan §8.9bis): triggered by [WakfuBestBuildFinderAlgorithm.
 * proveMostMasteriesQuality] AFTER a most-masteries search whose CP-SAT leg ended non-OPTIMAL —
 * on low-core machines the 1-worker proof takes 15-20 min while this single-thread DP delivers a
 * "proven within X%" statement in seconds. Default grid = COARSE (DI 1 / CC 10 / HP 500):
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
            Characteristic.HP
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
    )

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
        // too), and states not taking the sub are unchanged. [mpCapMinus] is the same idea for a
        // negative MAX_MOVEMENT_POINT rider (Armure lourde).
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
    ) {
        /** Component-wise dominance (same flags): a ≤-everywhere option can never beat this one. */
        fun dominates(o: Opt): Boolean =
            m >= o.m &&
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
    ) {
        init {
            // The packed-key field widths are FIXED; the grid steps are mutable. A too-fine step
            // overflows its field into the neighbour (state merging corrupts ⇒ the bound can
            // UNDER-count, not just loosen) — fail loudly instead of honoring the KDoc's
            // "any setting stays sound" with a corrupted key.
            require(hpBucketCap <= 0x1FF) { "hpBucketCap $hpBucketCap overflows the 9-bit hp field (hpStep too fine)" }
            require(ccBucketCap <= 0x7F) { "ccBucketCap $ccBucketCap overflows the 7-bit cc field (ccStep too fine)" }
            require(diBucketCap <= 0x1FFF) { "diBucketCap $diBucketCap overflows the 13-bit d field (diStep too fine)" }
            require(blockBucketCap <= 0xF) { "blockBucketCap $blockBucketCap overflows the 4-bit block field" }
        }

        // Packed key: block(4b @45) ramp(1b @44) mpMinus(1b @43) d(13b @28)
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
        ): Long =
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

        fun mpCapOf(mpMinus: Int): Int = (mpCap - mpMinus).coerceAtLeast(0)
    }

    private fun ceilDiv(
        a: Int,
        b: Int,
    ): Int = (a + b - 1) / b

    /** One state transition, or null when the option is inapplicable/rejected in [k]. */
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
        if (o.requiresBlockAtLeast > 0 && block(k) * BLOCK_STEP < o.requiresBlockAtLeast) return null
        // mpCapMinus stays SATURATING: a lowered MAX MP wastes excess MP, it never forbids it.
        val mpEff = mpCapOf(newMpMinus)
        val flatHpBuckets = hp(k) + ceilDiv(o.hp, hpStep)
        // %HP scales the dim's value (a sound upper of the build's own HP): value ≤ buckets×STEP,
        // so ceil(buckets × (100+pct) / 100) buckets still over-count the scaled value.
        val hpBuckets =
            if (o.hpPct > 0) ceilDiv(flatHpBuckets * (100 + o.hpPct), 100) else flatHpBuckets
        return key(
            (d(k) + ceilDiv(o.d, diStep)).coerceAtMost(diBucketCap),
            newAp,
            (mp(k) + o.mp).coerceAtMost(mpEff),
            newCcBuckets,
            hpBuckets.coerceAtMost(hpBucketCap),
            if (o.epic) 1 else e,
            if (o.relic) 1 else r,
            newMpMinus,
            if (o.ramp) 1 else ramp(k),
            (block(k) + ceilDiv(o.block, BLOCK_STEP)).coerceAtMost(blockBucketCap)
        )
    }

    private fun Geometry.apply(
        states: HashMap<Long, Long>,
        options: List<Opt>,
    ): HashMap<Long, Long> {
        val next = HashMap<Long, Long>(states.size * 2)
        for ((k, mv) in states) {
            for (o in options) {
                val nk = applyOne(k, o) ?: continue
                val nm = mv + o.m
                val cur = next[nk]
                if (cur == null || nm > cur) next[nk] = nm
            }
        }
        return next
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
        // Two-tier certificate: `false` skips the block dim (increment 8) — the QUICK tier (~15 s,
        // bound ~1.3pt looser). The GUI shows the quick badge first, then refines with the full
        // pass in the background. Both tiers are independently sound.
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
        // INTERNAL world-split recursion (review fix A#1) — never set by callers. worldDropCaps:
        // run the DP with every AT_MOST cap sub excluded; worldAssume: run it with THAT cap sub
        // assumed carried (LOW semantics on its capped stat, credits added at collapse).
        worldAssume: Sublimation? = null,
        worldDropCaps: Boolean = false,
    ): Result? {
        val t0 = System.nanoTime()
        val wantSrc = provenance || optionVeto != null
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
        if (targets.any { it.characteristic !in SUPPORTED_TARGETS }) return null
        val targetByChar = targets.associateBy { it.characteristic }
        val level = params.character.level
        val diCap = DAMAGE_DI_MAX.toInt()

        fun cap(char: Characteristic): Int = targetByChar[char]?.target ?: 0

        // Increment 4/5 pre-scan, redesigned by the 2026-07-14 review (A#1): the AT_MOST cap subs
        // (AP_AT_MOST/AP_EXACT/CRIT_AT_MOST — all EPIC, so a real build carries at most ONE). The
        // old in-state rejection accumulated per-option CEILs and could deny a real satisfying
        // build (an under-count); the sound form is a WORLD SPLIT: one world WITHOUT any cap sub
        // (bit-identical to the pre-increment-4 DP) plus one world per cap sub where it is ASSUMED
        // carried — the capped stat's dim switches to LOW semantics for the condition and the fold
        // credits it as a constant. The bound is the max over worlds (every real build lives in
        // exactly one).
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
                        worldAssume = assume,
                        worldDropCaps = assume == null
                    )
                }
            if (worlds.any { it == null }) return null
            val best = worlds.filterNotNull().maxByOrNull { it.foldedBound } ?: return null
            return Result(
                best.foldedBound,
                worlds.filterNotNull().maxOf { it.coreBound },
                worlds.filterNotNull().sumOf { it.states },
                (System.nanoTime() - t0) / 1_000_000,
                best.bindingState,
                best.bindingPath
            )
        }
        val assumeStat = worldAssume?.let { capStatOf(it) }
        val assumeThreshold = worldAssume?.condition?.value ?: -1

        // Increment 8 pre-scan: BLOCK_AT_LEAST conditions among the choosable subs — when present,
        // the block dim is tracked up to the LARGEST threshold (values above it are equivalent).
        val blockAtLeastMax =
            if (blockGate && params.useSublimations && "noSubs" !in diag && "noCondSubs" !in diag) {
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
            if (params.useSublimations && "noSubs" !in diag && "noRamps" !in diag) {
                sublimations
                    .filter { it.solverChoosable && ("noCondSubs" !in diag || it.condition == null) }
                    .flatMap { sub -> sub.effects.filterIsInstance<SublimationEffect.PerStatStep>().map { sub to it } }
                    .filter { (_, eff) -> eff.source == Characteristic.MOVEMENT_POINT && eff.target == Characteristic.DAMAGE_INFLICTED }
            } else {
                emptyList()
            }
        if (mpDiRamps.size > 1) return null
        val mpDiRamp = mpDiRamps.firstOrNull()

        val geo =
            Geometry(
                apCap =
                    (
                        if (assumeStat == Characteristic.ACTION_POINT) {
                            // Assume-world: the AP dim is the condition's LOW read, saturated one
                            // past the threshold (its fold contribution is a constant instead).
                            assumeThreshold + 1
                        } else {
                            cap(Characteristic.ACTION_POINT)
                        }
                    ).coerceAtMost(MAX_OUT_OF_COMBAT_AP.toInt()),
                mpCap =
                    maxOf(
                        cap(Characteristic.MOVEMENT_POINT),
                        if (mpDiRamp != null) MAX_OUT_OF_COMBAT_MP.toInt() else 0
                    ).coerceAtMost(MAX_OUT_OF_COMBAT_MP.toInt()),
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
                assumeCcThresholdRaw = if (assumeStat == Characteristic.CRITICAL_HIT) assumeThreshold else -1
            )

        // Positive parts of an equipment's objective/target lines.
        fun statOf(
            e: Equipment,
            c: Characteristic,
        ): Int = maxOf(e.characteristics[c] ?: 0, 0)

        // The rune axes that actually exist: for each supported axis, the best rune value per
        // (slot type, level). Axis order: [mastery, AP, MP, CC, HP] — absent axes get 0 everywhere.
        val runeAxes: List<(ItemType, Int) -> Int> =
            listOf<(RuneType) -> Boolean>(
                { it.characteristic in requested },
                { it.characteristic == Characteristic.ACTION_POINT && Characteristic.ACTION_POINT in targetByChar },
                { it.characteristic == Characteristic.MOVEMENT_POINT && Characteristic.MOVEMENT_POINT in targetByChar },
                { it.characteristic == Characteristic.CRITICAL_HIT && Characteristic.CRITICAL_HIT in targetByChar },
                { it.characteristic == Characteristic.HP && Characteristic.HP in targetByChar }
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
                    // included) is the tightest valid under-approximation.
                    apLow = if (geo.assumeApThreshold >= 0) (e.characteristics[Characteristic.ACTION_POINT] ?: 0) else 0,
                    ccLowRaw = if (geo.assumeCcThresholdRaw >= 0) (e.characteristics[Characteristic.CRITICAL_HIT] ?: 0) else 0,
                    src = if (wantSrc) e.name.fr else ""
                )
            val slots = if (params.useRunes) e.maxShardSlots else 0
            if (slots == 0) return listOf(base)
            val perAxis = runeAxes.map { it(e.itemType, e.level) }
            val out = mutableListOf<Opt>()
            // Compositions of `slots` sockets over the 5 axes (axes valued 0 collapse via dedup).
            for (a0 in 0..slots) {
                for (a1 in 0..(slots - a0)) {
                    for (a2 in 0..(slots - a0 - a1)) {
                        for (a3 in 0..(slots - a0 - a1 - a2)) {
                            val a4 = slots - a0 - a1 - a2 - a3
                            out +=
                                base.copy(
                                    m = base.m + a0.toLong() * perAxis[0],
                                    ap = base.ap + a1 * perAxis[1],
                                    mp = base.mp + a2 * perAxis[2],
                                    cc = base.cc + a3 * perAxis[3],
                                    hp = base.hp + a4 * perAxis[4],
                                    // Runes are positive and exact — they feed the LOW dims too.
                                    apLow = base.apLow + (if (geo.assumeApThreshold >= 0) a1 * perAxis[1] else 0),
                                    ccLowRaw = base.ccLowRaw + (if (geo.assumeCcThresholdRaw >= 0) a3 * perAxis[3] else 0)
                                )
                        }
                    }
                }
            }
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
                block = ceilDiv((baseValues[Characteristic.BLOCK_PERCENTAGE] ?: 0).coerceAtLeast(0), BLOCK_STEP).coerceAtMost(geo.blockBucketCap)
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
            val effective = if (optionVeto == null) options else options.filter { !optionVeto(label, it.src) }
            stageLog?.add(Triple(label, HashMap(states), effective))
            states = geo.apply(states, effective)
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
                var p = 0L
                var qn = 0L
                for (eff in sub.effects.filterIsInstance<SublimationEffect.StatEffect>()) {
                    if (eff.characteristic !in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS) continue
                    if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                    val x = eff.magnitudeAtLevel(level)
                    if (eff.characteristic in requested && x > 0) p += x
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
        val worldBSubs = mutableListOf<Pair<Long, Opt>>()
        if (params.useSublimations && "noSubs" !in diag) {
            data class SubOpt(
                val opt: Opt,
                val rarity: SublimationRarity,
            )
            val subOpts = mutableListOf<SubOpt>()
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                if (sub.bestElementConcentration != null) continue
                if ("noCondSubs" in diag && sub.condition != null) continue
                if ("noEpicSubs" in diag && sub.rarity == SublimationRarity.EPIC) continue
                if ("noNormalSubs" in diag && sub.rarity == SublimationRarity.NORMAL) continue
                if ("noRelicSubs" in diag && sub.rarity == SublimationRarity.RELIC) continue
                if ("noSecretCritique" in diag && sub.condition?.type == SublimationConditionType.CRITICAL_MASTERY_AT_MOST) continue
                val cond = sub.condition
                val capsObjective =
                    cond != null &&
                        (
                            cond.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ||
                                (cond.type == SublimationConditionType.CRITICAL_MASTERY_AT_MOST && Characteristic.MASTERY_CRITICAL in requested)
                        )
                // Review fix (A#1): AT_MOST cap subs never enter the stages — the world split
                // handles them (excluded in every world; the assumed one is credited at collapse).
                if (capStatOf(sub) != null && sub !== worldAssume) continue
                val blockRequirement =
                    if (blockAtLeastMax > 0 && cond?.type == SublimationConditionType.BLOCK_AT_LEAST) (cond.value ?: 0) else 0
                var opt = Opt(0L, 0, requiresBlockAtLeast = blockRequirement, src = if (wantSrc) sub.name.fr else "")
                var bail = false
                for (eff in sub.effects) {
                    when (eff) {
                        is SublimationEffect.StatEffect -> {
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            val value = eff.magnitudeAtLevel(level)
                            // Increment 4: a negative MAX_MOVEMENT_POINT rider (Armure lourde) lowers
                            // the MP ceiling of any real carrier — flagged so the state pays it.
                            if (eff.characteristic == Characteristic.MAX_MOVEMENT_POINT &&
                                value < 0 &&
                                Characteristic.MOVEMENT_POINT in targetByChar
                            ) {
                                if (-value > 1) return null // 1-bit flag; a deeper cut would under-model
                                opt = opt.copy(mpCapMinus = 1)
                                continue
                            }
                            if (value <= 0) {
                                // LOW dims (A#1): a NEGATIVE AP/CC line still lowers the real stat a
                                // condition reads — feed it to the under-approximating dims.
                                if (value < 0 && geo.assumeApThreshold >= 0 && eff.characteristic == Characteristic.ACTION_POINT) {
                                    opt = opt.copy(apLow = opt.apLow + value)
                                }
                                if (value < 0 && geo.assumeCcThresholdRaw >= 0 && eff.characteristic == Characteristic.CRITICAL_HIT) {
                                    opt = opt.copy(ccLowRaw = opt.ccLowRaw + value)
                                }
                                continue
                            }
                            opt =
                                when {
                                    eff.characteristic in requested -> opt.copy(m = opt.m + value)
                                    eff.characteristic == Characteristic.DAMAGE_INFLICTED -> opt.copy(d = opt.d + value)
                                    eff.characteristic == Characteristic.ACTION_POINT ->
                                        opt.copy(ap = opt.ap + value, apLow = opt.apLow + (if (geo.assumeApThreshold >= 0) value else 0))
                                    eff.characteristic == Characteristic.MOVEMENT_POINT -> opt.copy(mp = opt.mp + value)
                                    eff.characteristic == Characteristic.CRITICAL_HIT ->
                                        opt.copy(cc = opt.cc + value, ccLowRaw = opt.ccLowRaw + (if (geo.assumeCcThresholdRaw >= 0) value else 0))
                                    eff.characteristic == Characteristic.HP -> opt.copy(hp = opt.hp + value)
                                    eff.characteristic == Characteristic.BLOCK_PERCENTAGE && blockAtLeastMax > 0 ->
                                        opt.copy(block = opt.block + value)
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
                                    else -> opt
                                }
                        }

                        is SublimationEffect.Conversion -> if (eff.to in requested) bail = true
                        else -> {}
                    }
                }
                if (bail) return null
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
                            t + otherRequestedMasteriesMax()
                        }
                    worldBSubs += mCap to opt
                    continue
                }
                if (sub === worldAssume) {
                    // The assumed cap sub: credited at collapse, never staged (its own capped-stat
                    // contribution thereby never feeds its own condition — the game's rule).
                    assumedOpt = opt
                    continue
                }
                if (opt.m == 0L && opt.d == 0 && opt.ap == 0 && opt.mp == 0 && opt.cc == 0 && opt.hp == 0 && !opt.ramp) continue
                repeat(sub.maxCopies.coerceAtLeast(1)) { subOpts += SubOpt(opt, sub.rarity) }
            }

            fun bucketOptions(
                rarity: SublimationRarity,
                capCount: Int,
            ): List<Opt> {
                // Flag-carrying subs never enter the packing (it would erase their flags) — they are
                // applied as their own stages below.
                val opts =
                    subOpts
                        .filter {
                            it.rarity == rarity &&
                                it.opt.capKind == 0 &&
                                it.opt.mpCapMinus == 0 &&
                                !it.opt.ramp &&
                                it.opt.requiresBlockAtLeast == 0
                        }.map { it.opt }
                if (opts.isEmpty()) return listOf(Opt(0L, 0))
                // LOW dims (A#1): the packing cannot track per-subset signed sums, so every emitted
                // aggregate carries the pool-wide NEGATIVE parts — a valid lower bound of ANY
                // subset's contribution (positives deliberately uncounted: lenient, still sound).
                val knapApNeg = opts.sumOf { minOf(it.apLow, 0) }
                val knapCcNeg = opts.sumOf { minOf(it.ccLowRaw, 0) }
                // Knapsack frontier over (count, d, ap, mp, cc, hp) → max m, dims saturating like the
                // main DP (the stat dims reuse the global packing in the LOW 41 bits; the copy count
                // rides bits 44+ so it can never collide with the stat key).
                val statMask = (1L shl 49) - 1
                var sub = HashMap<Long, Long>()
                sub[0L] = 0L
                for (o in opts) {
                    val next = HashMap(sub)
                    for ((k, mv) in sub) {
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
                                block = (geo.block(stat) + ceilDiv(o.block, BLOCK_STEP)).coerceAtMost(geo.blockBucketCap)
                            )
                        val nk = ((cnt + 1).toLong() shl 50) or nStat
                        val nm = mv + o.m
                        val cur = next[nk]
                        if (cur == null || nm > cur) next[nk] = nm
                    }
                    sub = next
                }
                return sub.map { (k, mv) ->
                    val cnt = (k shr 50).toInt()
                    val stat = k and statMask
                    Opt(
                        m = mv,
                        d = geo.d(stat) * diStep,
                        ap = geo.ap(stat),
                        mp = geo.mp(stat),
                        cc = geo.cc(stat) * ccStep,
                        hp = geo.hp(stat) * hpStep,
                        block = geo.block(stat) * BLOCK_STEP,
                        requiresEpicItem = rarity == SublimationRarity.EPIC && cnt > 0,
                        requiresRelicItem = rarity == SublimationRarity.RELIC && cnt > 0,
                        apLow = knapApNeg,
                        ccLowRaw = knapCcNeg,
                        src = if (wantSrc) "$rarity x$cnt" else ""
                    )
                }
            }
            step("subs-normal", bucketOptions(SublimationRarity.NORMAL, 10))
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
            // flags of the increment-4 conditional epics (Inflexibilité, Constance, Mesure III).
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

        // Skills — AFTER the subs stage, because the %HP skill multiplies the build's WHOLE HP
        // (base + items + subs); applying it earlier would under-count the subs' flat HP share.
        // Per-branch enumeration over the ≤5 objective/target-relevant skills; FIXED skills credit
        // points × unitValue on their axis, %HP is multiplicative on the dim, other PERCENT skills
        // credit ceil(reachableMax × pts × unitValue / 100). Granularity: exact for small branches,
        // else ceil-to-step (a partial step credited as full — over-count, sound).
        if ("noSkills" !in diag) {
            val skills = params.character.characterSkills
            for (branch in listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major)) {
                val relevant =
                    branch.getCharacteristics().filter { sk ->
                        sk.characteristic in requested ||
                            sk.characteristic == Characteristic.DAMAGE_INFLICTED ||
                            sk.characteristic in targetByChar ||
                            (blockAtLeastMax > 0 && sk.characteristic == Characteristic.BLOCK_PERCENTAGE)
                    }
                if (relevant.isEmpty()) continue
                val budget = branch.maxPointsToAssign
                // Step 1 (exact) everywhere: the ceil-to-step credit was a sound over-count kept
                // for state-count reasons that the coarse grid has since erased.
                val step = 1

                fun creditOf(
                    sk: me.chosante.common.skills.SkillCharacteristic,
                    pts: Int,
                ): Opt {
                    // Paired skills carry a null characteristic (elemental-only today — banked v1
                    // fact); none feeds our axes, so they contribute nothing here.
                    val skChar = sk.characteristic ?: return Opt(0L, 0)
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
                        else -> Opt(0L, 0, hp = v, src = srcTag)
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

        // Collapse: mirror applyConstraintPenalty/bucketedIndex/buildPowerTable arithmetic exactly.
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

        fun power6(index: Int): Long =
            BigInteger
                .valueOf(index.toLong())
                .pow(6)
                .divide(powScale)
                .toLong()

        fun weight(char: Characteristic): Long = targetByChar[char]?.let { params.targetStats.scaledWeight(it) } ?: 0L

        fun targetOf(char: Characteristic): Long = targetByChar[char]?.target?.toLong() ?: 0L

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
            ): Pair<Long, Long> {
                val mvX = (mv + assumedOpt.m + extra.m).let { if (mCap != null) minOf(it, mCap) else it }
                val di = (geo.d(k).toLong() * diStep + assumedOpt.d + extra.d + rampDi).coerceAtMost(diCap.toLong())
                val core = (maxOf(mvX, 0L) * (100L + di) / 100L).coerceAtMost(MASTERY_SCORE_ABS_MAX)
                if (targets.isEmpty()) return core to core
                val apRead =
                    if (geo.assumeApThreshold >= 0) {
                        (geo.assumeApThreshold + maxOf(assumedOpt.ap, 0)).toLong() + extra.ap
                    } else {
                        geo.ap(k).toLong() + assumedOpt.ap + extra.ap
                    }
                val ccRead =
                    if (geo.assumeCcThresholdRaw >= 0) {
                        (assumeThreshold + maxOf(assumedOpt.cc, 0)).toLong() + extra.cc
                    } else {
                        geo.cc(k).toLong() * ccStep + assumedOpt.cc + extra.cc
                    }
                val totalActual =
                    weight(Characteristic.ACTION_POINT) * minOf(apRead, targetOf(Characteristic.ACTION_POINT)) +
                        weight(Characteristic.MOVEMENT_POINT) *
                        minOf(geo.mp(k).toLong() + assumedOpt.mp + extra.mp, targetOf(Characteristic.MOVEMENT_POINT)) +
                        weight(Characteristic.CRITICAL_HIT) * minOf(ccRead, targetOf(Characteristic.CRITICAL_HIT)) +
                        weight(Characteristic.HP) *
                        minOf(geo.hp(k).toLong() * hpStep + assumedOpt.hp + extra.hp, targetOf(Characteristic.HP))
                val bucket = (totalActual.coerceIn(1L, totalExpected) / bucketSize).toInt().coerceAtMost(maxIndex)
                return core to core * power6(bucket) * OVERSHOOT_SCALE_MIRROR + (OVERSHOOT_SCALE_MIRROR - 1)
            }

            fun consider(
                extra: Opt,
                mCap: Long?,
                tag: String,
            ) {
                val (core, folded) = foldWith(extra, mCap)
                if (core > bestCore) bestCore = core
                if (folded > bestFolded) {
                    bestFolded = folded
                    bindingKey = k
                    bindingM = mv
                    bindingState =
                        "M=${mv + assumedOpt.m + extra.m} d=${geo.d(k) * diStep}+ramp$rampDi ap=${geo.ap(k)} mp=${geo.mp(k)} " +
                        "cc=${geo.cc(k) * ccStep} hp=${geo.hp(k) * hpStep} e=${geo.e(k)} r=${geo.r(k)} " +
                        "assume=${worldAssume?.name?.fr ?: "-"}$tag mpMinus=${geo.mpMinus(k)} core=$core"
                }
            }

            consider(EMPTY_OPT, null, "")
            // World B PER STATE (A#2): each objective-capping sub folded on the state's own dims —
            // M clamped at its knapsack cap, its credits (DI/CC/…) added. The old analytic fold at
            // power6(maxIndex) assumed all targets met (unreachable for knapsack-limited carriers).
            for ((mCapB, bOpt) in worldBSubs) consider(bOpt, mCapB, " worldB(cap=$mCapB)")
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
                            if (o.src.isNotEmpty() || o.m != 0L || o.d != 0 || o.ap != 0 || o.mp != 0 || o.cc != 0 || o.hp != 0) {
                                bindingPath +=
                                    "$label: ${o.src.ifEmpty { "opt" }} " +
                                    "(m=${o.m} d=${o.d} ap=${o.ap} mp=${o.mp} cc=${o.cc} hp=${o.hp} hpPct=${o.hpPct})"
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
        return Result(bestFolded, bestCore, states.size, (System.nanoTime() - t0) / 1_000_000, bindingState, bindingPath)
    }

    private val OVERSHOOT_SCALE_MIRROR = WakfuBuildSolver.OVERSHOOT_SCALE
    private val EMPTY_OPT = Opt(0L, 0)
}
