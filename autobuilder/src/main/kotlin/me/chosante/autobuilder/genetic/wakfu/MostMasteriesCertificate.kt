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
                requiresBlockAtLeast == o.requiresBlockAtLeast
        // src deliberately ignored: provenance never changes what an option contributes.
    }

    /** Drops options component-wise dominated by another with identical flags (exact pruning). */
    private fun prune(options: List<Opt>): List<Opt> {
        val distinct = options.distinct()
        return distinct.filter { o -> distinct.none { other -> other !== o && other.dominates(o) && other != o } }
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
        // Increment 4: cap table — capKind k (1-based) pins caps[k-1]; 0 = no cap. At most 3
        // (2 bits); the builder bails beyond that.
        val caps: List<CapSpec> = emptyList(),
        // Increment 8: block dim cap in BUCKETS (0 = untracked — no AT_LEAST block condition in play).
        val blockBucketCap: Int = 0,
    ) {
        // Packed key: block(4b @45) ramp(1b @44) mpMinus(1b @43) capKind(2b @41) d(13b @28)
        //             ap(5b @23) mp(5b @18) cc(7b @11) hp(9b @2) e(1b @1) r(1b @0)
        fun key(
            d: Int,
            ap: Int,
            mp: Int,
            cc: Int,
            hp: Int,
            e: Int,
            r: Int,
            capKind: Int = 0,
            mpMinus: Int = 0,
            ramp: Int = 0,
            block: Int = 0,
        ): Long =
            (block.toLong() shl 45) or (ramp.toLong() shl 44) or (mpMinus.toLong() shl 43) or (capKind.toLong() shl 41) or
                (d.toLong() shl 28) or (ap.toLong() shl 23) or (mp.toLong() shl 18) or
                (cc.toLong() shl 11) or (hp.toLong() shl 2) or (e.toLong() shl 1) or r.toLong()

        fun d(k: Long): Int = ((k shr 28) and 0x1FFF).toInt()

        fun ap(k: Long): Int = ((k shr 23) and 0x1F).toInt()

        fun mp(k: Long): Int = ((k shr 18) and 0x1F).toInt()

        fun cc(k: Long): Int = ((k shr 11) and 0x7F).toInt()

        fun hp(k: Long): Int = ((k shr 2) and 0x1FF).toInt()

        fun e(k: Long): Int = ((k shr 1) and 1L).toInt()

        fun r(k: Long): Int = (k and 1L).toInt()

        fun capKind(k: Long): Int = ((k shr 41) and 0x3).toInt()

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
        // A second AT_MOST-capping sub can never arrive (they are all EPIC, cap 1).
        val newCapKind = if (o.capKind != 0) o.capKind else capKind(k)
        val newMpMinus = (mpMinus(k) + o.mpCapMinus).coerceAtMost(1)
        val newAp = ap(k) + o.ap
        val newCcBuckets = cc(k) + ceilDiv(o.cc, ccStep)
        // Increment 5 — AT_MOST conditions are CONSTRAINTS, not caps: a path whose stat exceeds the
        // threshold cannot carry (or keep carrying) the sub, so the option is REJECTED. Sound with
        // the ceil-lenient budget: `bucket ≤ ceil(t/step)` admits every real value ≤ t (rounding-up
        // can only over-admit, never over-reject).
        if (newCapKind > 0) {
            val spec = caps[newCapKind - 1]
            // At pose time (o.capKind != 0) the condition is read WITHOUT the sub's own contribution
            // ("a conditional sub never feeds its own condition"); afterwards every permanent
            // addition counts (start-of-combat final sheet).
            val checkAp = if (o.capKind != 0) ap(k) else newAp
            val checkCc = if (o.capKind != 0) cc(k) else newCcBuckets
            if (spec.stat == Characteristic.ACTION_POINT && checkAp > spec.threshold) return null
            if (spec.stat == Characteristic.CRITICAL_HIT && checkCc > ceilDiv(spec.threshold, ccStep)) return null
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
            newAp.coerceAtMost(apCap),
            (mp(k) + o.mp).coerceAtMost(mpEff),
            newCcBuckets.coerceAtMost(ccBucketCap),
            hpBuckets.coerceAtMost(hpBucketCap),
            if (o.epic) 1 else e,
            if (o.relic) 1 else r,
            newCapKind,
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
    ): Result? {
        val t0 = System.nanoTime()
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

        // Increment 4/5 pre-scan: AT_MOST conditions on TRACKABLE stats (AP, CC) among the choosable
        // subs — each distinct (stat, threshold) becomes a capKind the DP state can carry (≤ 3,
        // 2 bits). Increment 5: the stat is tracked EVEN WITHOUT a target (dim sized to the largest
        // threshold + 1), so the condition binds exactly on S3-class requests too.
        val capSpecs = mutableListOf<CapSpec>()
        if (params.useSublimations && "noSubs" !in diag && "noCondSubs" !in diag) {
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                val cond = sub.condition ?: continue
                val stat =
                    when (cond.type) {
                        SublimationConditionType.AP_AT_MOST, SublimationConditionType.AP_EXACT -> Characteristic.ACTION_POINT
                        SublimationConditionType.CRIT_AT_MOST -> Characteristic.CRITICAL_HIT
                        else -> null
                    } ?: continue
                val threshold = cond.value ?: 0
                if (capSpecs.none { it.stat == stat && it.threshold == threshold }) capSpecs += CapSpec(stat, threshold)
            }
            if (capSpecs.size > 3) return null
        }

        fun specMax(char: Characteristic): Int = capSpecs.filter { it.stat == char }.maxOfOrNull { it.threshold } ?: -1

        // Increment 8 pre-scan: BLOCK_AT_LEAST conditions among the choosable subs — when present,
        // the block dim is tracked up to the LARGEST threshold (values above it are equivalent).
        val blockAtLeastMax =
            if (params.useSublimations && "noSubs" !in diag && "noCondSubs" !in diag) {
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
                    maxOf(cap(Characteristic.ACTION_POINT), specMax(Characteristic.ACTION_POINT) + 1)
                        .coerceAtMost(MAX_OUT_OF_COMBAT_AP.toInt()),
                mpCap =
                    maxOf(
                        cap(Characteristic.MOVEMENT_POINT),
                        if (mpDiRamp != null) MAX_OUT_OF_COMBAT_MP.toInt() else 0
                    ).coerceAtMost(MAX_OUT_OF_COMBAT_MP.toInt()),
                ccBucketCap = maxOf(ceilDiv(cap(Characteristic.CRITICAL_HIT), ccStep), ceilDiv(specMax(Characteristic.CRITICAL_HIT), ccStep) + 1),
                hpBucketCap = ceilDiv(cap(Characteristic.HP), hpStep),
                diBucketCap = ceilDiv(diCap, diStep),
                caps = capSpecs,
                blockBucketCap = ceilDiv(blockAtLeastMax, BLOCK_STEP)
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
                    src = if (provenance) e.name.fr else ""
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
                                    hp = base.hp + a4 * perAxis[4]
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
                (baseValues[Characteristic.ACTION_POINT] ?: 0).coerceIn(0, geo.apCap),
                (baseValues[Characteristic.MOVEMENT_POINT] ?: 0).coerceIn(0, geo.mpCap),
                ceilDiv((baseValues[Characteristic.CRITICAL_HIT] ?: 0).coerceAtLeast(0), ccStep).coerceAtMost(geo.ccBucketCap),
                ceilDiv((baseValues[Characteristic.HP] ?: 0).coerceAtLeast(0), hpStep).coerceAtMost(geo.hpBucketCap),
                0,
                0,
                block = ceilDiv((baseValues[Characteristic.BLOCK_PERCENTAGE] ?: 0).coerceAtLeast(0), BLOCK_STEP).coerceAtMost(geo.blockBucketCap)
            )
        ] = 0L

        // Provenance retention + the single stage-advance helper.
        val stageLog = if (provenance) mutableListOf<Triple<String, HashMap<Long, Long>, List<Opt>>>() else null

        fun step(
            label: String,
            options: List<Opt>,
        ) {
            stageLog?.add(Triple(label, HashMap(states), options))
            states = geo.apply(states, options)
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
                src = listOf(a.src, b.src).filter { it.isNotEmpty() }.joinToString("+")
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

        // Sound layer-independent reachable max of a stat (for percent skills and ramps).
        fun reachableMax(stat: Characteristic): Int {
            var v = baseValues[stat] ?: 0
            for ((_, items) in pool) v += items.maxOfOrNull { maxOf(it.characteristics[stat] ?: 0, 0) } ?: 0
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

        var epicRelicStages: (() -> Unit)? = null
        // Sublimations: caps-only knapsack per rarity (10/1/1) with epic/relic carrier binding —
        // v1's machinery extended with the target axes. Objective-capping subs go to world B.
        var objectiveCapT = -1L
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
                if (capsObjective) {
                    objectiveCapT = maxOf(objectiveCapT, (cond?.value ?: 0).toLong())
                    continue
                }
                // Increment 4: an AT_MOST condition on a TRACKED target pins the matching cap flag —
                // the credit is no longer free; the state pays the target shortfall the condition
                // forces on any real carrier.
                val capKind =
                    if (cond != null) {
                        val condStat =
                            when (cond.type) {
                                SublimationConditionType.AP_AT_MOST, SublimationConditionType.AP_EXACT -> Characteristic.ACTION_POINT
                                SublimationConditionType.CRIT_AT_MOST -> Characteristic.CRITICAL_HIT
                                else -> null
                            }
                        if (condStat != null) {
                            capSpecs.indexOfFirst { it.stat == condStat && it.threshold == (cond.value ?: 0) } + 1
                        } else {
                            0
                        }
                    } else {
                        0
                    }
                val blockRequirement =
                    if (blockAtLeastMax > 0 && cond?.type == SublimationConditionType.BLOCK_AT_LEAST) (cond.value ?: 0) else 0
                var opt = Opt(0L, 0, capKind = capKind, requiresBlockAtLeast = blockRequirement, src = if (provenance) sub.name.fr else "")
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
                            if (value <= 0) continue
                            opt =
                                when {
                                    eff.characteristic in requested -> opt.copy(m = opt.m + value)
                                    eff.characteristic == Characteristic.DAMAGE_INFLICTED -> opt.copy(d = opt.d + value)
                                    eff.characteristic == Characteristic.ACTION_POINT -> opt.copy(ap = opt.ap + value)
                                    eff.characteristic == Characteristic.MOVEMENT_POINT -> opt.copy(mp = opt.mp + value)
                                    eff.characteristic == Characteristic.CRITICAL_HIT -> opt.copy(cc = opt.cc + value)
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
                        src = if (provenance) "$rarity x$cnt" else ""
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
                step("subs-epic", singleSlotOptions(SublimationRarity.EPIC))
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
                        return Opt(0L, 0, hpPct = pts * sk.unitValue, src = if (provenance && pts > 0) "%HP:${'$'}pts" else "")
                    }
                    val value =
                        if (sk.unitType == me.chosante.common.skills.UnitType.PERCENT) {
                            ceil(reachableMax(skChar).coerceAtLeast(0) * pts.toLong() * sk.unitValue / 100.0).toLong()
                        } else {
                            pts.toLong() * sk.unitValue
                        }
                    val v = value.coerceAtLeast(0L).toInt()
                    val srcTag = if (provenance && pts > 0) "${'$'}{skChar.name}:${'$'}pts" else ""
                    return when {
                        skChar in requested -> Opt(m = value.coerceAtLeast(0L), d = 0, src = srcTag)
                        skChar == Characteristic.DAMAGE_INFLICTED -> Opt(0L, v, src = srcTag)
                        skChar == Characteristic.ACTION_POINT -> Opt(0L, 0, ap = v, src = srcTag)
                        skChar == Characteristic.MOVEMENT_POINT -> Opt(0L, 0, mp = v, src = srcTag)
                        skChar == Characteristic.CRITICAL_HIT -> Opt(0L, 0, cc = v, src = srcTag)
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
            // Increment 6: the deferred MP→DI ramp lands here at the PATH's own MP (the dim
            // over-counts real MP and contribution() is monotone — sound).
            val rampDi =
                if (geo.ramp(k) == 1 && mpDiRamp != null) {
                    maxOf(mpDiRamp.second.contribution(geo.mp(k)), 0)
                } else {
                    0
                }
            val di = (geo.d(k).toLong() * diStep + rampDi).coerceAtMost(diCap.toLong())
            val core = (maxOf(mv, 0L) * (100L + di) / 100L).coerceAtMost(MASTERY_SCORE_ABS_MAX)
            if (core > bestCore) bestCore = core
            val folded =
                if (targets.isEmpty()) {
                    core
                } else {
                    val totalActual =
                        weight(Characteristic.ACTION_POINT) * minOf(geo.ap(k).toLong(), targetOf(Characteristic.ACTION_POINT)) +
                            weight(Characteristic.MOVEMENT_POINT) * minOf(geo.mp(k).toLong(), targetOf(Characteristic.MOVEMENT_POINT)) +
                            weight(Characteristic.CRITICAL_HIT) *
                            minOf(geo.cc(k).toLong() * ccStep, targetOf(Characteristic.CRITICAL_HIT)) +
                            weight(Characteristic.HP) * minOf(geo.hp(k).toLong() * hpStep, targetOf(Characteristic.HP))
                    val bucket = (totalActual.coerceIn(1L, totalExpected) / bucketSize).toInt().coerceAtMost(maxIndex)
                    core * power6(bucket) * OVERSHOOT_SCALE_MIRROR + (OVERSHOOT_SCALE_MIRROR - 1)
                }
            if (folded > bestFolded) {
                bestFolded = folded
                bindingKey = k
                bindingM = mv
                bindingState =
                    "M=$mv d=${geo.d(k) * diStep}+ramp$rampDi ap=${geo.ap(k)} mp=${geo.mp(k)} " +
                    "cc=${geo.cc(k) * ccStep} hp=${geo.hp(k) * hpStep} e=${geo.e(k)} r=${geo.r(k)} " +
                    "capKind=${geo.capKind(k)} mpMinus=${geo.mpMinus(k)} core=$core"
            }
        }
        // World B: objective-capping subs — M ≤ threshold, every other axis at its ceiling.
        if (objectiveCapT >= 0) {
            val worldBCore = (objectiveCapT * (100L + diCap)) / 100L
            val worldB =
                if (targets.isEmpty()) worldBCore else worldBCore * power6(maxIndex) * OVERSHOOT_SCALE_MIRROR + (OVERSHOOT_SCALE_MIRROR - 1)
            if (worldB > bestFolded) {
                bestFolded = worldB
                bindingState = "worldB capT=$objectiveCapT"
            }
            if (worldBCore > bestCore) bestCore = worldBCore
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
}
