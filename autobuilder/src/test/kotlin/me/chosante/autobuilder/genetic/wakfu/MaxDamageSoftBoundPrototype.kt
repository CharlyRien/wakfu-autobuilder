package me.chosante.autobuilder.genetic.wakfu

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
 * S4-2 PROTOTYPE (plan §9) — a target-aware sound upper bound on the MAX-DAMAGE SOFT folded
 * objective (the targets-unreachable fallback leg — the only workload that never proves):
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
 * items/runes/subs, a required target outside {AP, MP, CC, HP}.
 */
internal object MaxDamageSoftBoundPrototype {
    private val SUPPORTED_TARGETS =
        setOf(
            Characteristic.ACTION_POINT,
            Characteristic.MOVEMENT_POINT,
            Characteristic.CRITICAL_HIT,
            Characteristic.HP
        )

    // Same grid as the MM certificate's default (coarse) profile. MUTABLE (harness only): the
    // provenance pass retains every stage map, so it needs a coarser grid to fit the test heap.
    @Volatile
    var ccStep = 10

    @Volatile
    var hpStep = 500

    @Volatile
    var diStep = 1

    private const val BLOCK_STEP = 5

    class Result(
        val foldedBound: Long,
        val coreBound: Long,
        val states: Int,
        val wallMs: Long,
        val bindingState: String = "",
        // Instrument only ([bound] provenance=true): the reconstructed binding PATH.
        val bindingPath: List<String> = emptyList(),
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
        init {
            require(hpBucketCap <= 0x1FF) { "hpBucketCap $hpBucketCap overflows the 9-bit hp field" }
            require(ccBucketCap <= 0x7F) { "ccBucketCap $ccBucketCap overflows the 7-bit cc field" }
            require(diBucketCap <= 0x1FFF) { "diBucketCap $diBucketCap overflows the 13-bit d field" }
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
                (ap(k) + o.ap).coerceAtMost(apCap)
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

    /** Transitions below this stay single-threaded (ported from the MM certificate). */
    private const val PARALLEL_APPLY_MIN_TRANSITIONS = 4_000_000L

    private fun Geometry.applySequential(
        entries: List<Map.Entry<Long, Long>>,
        options: List<Opt>,
        expectedSize: Int,
    ): HashMap<Long, Long> {
        val next = HashMap<Long, Long>(expectedSize)
        for ((k, wv) in entries) {
            for (o in options) {
                val nk = applyOne(k, o) ?: continue
                val nw = wv + o.w
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
    ): HashMap<Long, Long> {
        val transitions = states.size.toLong() * options.size
        val workers = Runtime.getRuntime().availableProcessors() - 1
        if (transitions < PARALLEL_APPLY_MIN_TRANSITIONS || workers < 2) {
            return applySequential(states.entries.toList(), options, states.size * 2)
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
                        applySequential(entries.subList(from, to), options, (to - from) * 2)
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
        // INTERNAL world-split recursion — never set by callers (MM certificate A#1 pattern).
        worldAssume: Sublimation? = null,
        worldDropCaps: Boolean = false,
    ): Result? {
        val t0 = System.nanoTime()
        val wantSrc = provenance
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return null
        val scenario = params.damageScenario
        if (scenario.survivabilityFloor) return null
        if (params.maxDamageApTarget != null) return null
        val candidates = scenario.candidateElements()
        if (candidates.size != 1) return null
        if (params.forcedItems.isNotEmpty() ||
            params.forcedRunes.isNotEmpty() ||
            params.forcedRunesByItem.isNotEmpty() ||
            params.forcedSublimations.isNotEmpty()
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
        if (capSubs.isNotEmpty() && worldAssume == null && !worldDropCaps) {
            val worlds: List<Result?> =
                (listOf<Sublimation?>(null) + capSubs).map { assume ->
                    bound(
                        params,
                        pool,
                        runes,
                        sublimations,
                        debug,
                        diag,
                        blockGate = if (assume == null) blockGate else false,
                        provenance = provenance,
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

        val blockAtLeastMax =
            if (blockGate && params.useSublimations && "noSubs" !in diag && "noCondSubs" !in diag) {
                sublimations
                    .filter { it.solverChoosable && it.condition?.type == SublimationConditionType.BLOCK_AT_LEAST }
                    .maxOfOrNull { it.condition?.value ?: 0 } ?: 0
            } else {
                0
            }

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
                            assumeThreshold + 1
                        } else {
                            // throughput[AP] grows with AP: track the full out-of-combat reach.
                            MAX_OUT_OF_COMBAT_AP.toInt()
                        }
                    ).coerceAtMost(MAX_OUT_OF_COMBAT_AP.toInt()),
                mpCap =
                    maxOf(
                        cap(Characteristic.MOVEMENT_POINT),
                        if (mpDiRamp != null) MAX_OUT_OF_COMBAT_MP.toInt() else 0
                    ).coerceAtMost(MAX_OUT_OF_COMBAT_MP.toInt()),
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

        fun statOf(
            e: Equipment,
            c: Characteristic,
        ): Int = maxOf(e.characteristics[c] ?: 0, 0)

        /** Weighted-Graw value of one positive stat line. */
        fun wOf(
            c: Characteristic,
            v: Int,
        ): Long =
            when {
                v <= 0 -> 0L
                c in masteryStats || c in randomStats -> wMastery * v
                c == Characteristic.MASTERY_CRITICAL -> wCritMastery * v
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

        fun itemOpts(e: Equipment): List<Opt> {
            val base =
                Opt(
                    w = e.characteristics.entries.sumOf { (c, v) -> wOf(c, v) },
                    d = statOf(e, Characteristic.DAMAGE_INFLICTED),
                    ap = statOf(e, Characteristic.ACTION_POINT),
                    mp = statOf(e, Characteristic.MOVEMENT_POINT),
                    cc = statOf(e, Characteristic.CRITICAL_HIT),
                    hp = statOf(e, Characteristic.HP),
                    epic = e.rarity == me.chosante.common.Rarity.EPIC,
                    relic = e.rarity == me.chosante.common.Rarity.RELIC,
                    block = if (blockAtLeastMax > 0) statOf(e, Characteristic.BLOCK_PERCENTAGE) else 0,
                    apLow = if (geo.assumeApThreshold >= 0) (e.characteristics[Characteristic.ACTION_POINT] ?: 0) else 0,
                    ccLowRaw = if (geo.assumeCcThresholdRaw >= 0) (e.characteristics[Characteristic.CRITICAL_HIT] ?: 0) else 0,
                    src = if (wantSrc) e.name.fr else ""
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
                                    ccLowRaw = base.ccLowRaw + (if (geo.assumeCcThresholdRaw >= 0) a3 * perAxis[2] else 0)
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
        var states = HashMap<Long, Long>()
        states[
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
        ] = seedW

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
            states = geo.apply(states, options)
            if (debug) println("S4_PROTO_STAGE $label states=${states.size} options=${options.size}")
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
        }
        val singleSlots =
            pool.keys - setOf(ItemType.RING, ItemType.ONE_HANDED_WEAPONS, ItemType.TWO_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS)
        for (slot in singleSlots) {
            step(slot.name, listOf(Opt(0L, 0)) + pool[slot].orEmpty().flatMap { prune(itemOpts(it)) })
        }

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

        // W-caps for the world-B carriers: the NON-capped components at their reachable max, the
        // capped component at its condition-derived cap — a sound global upper of any carrier's W.
        val elementalMasteryReach: Long by lazy {
            (masteryStats + randomStats)
                .filter { it !in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS }
                .sumOf { reachableMax(it).toLong() }
        }
        val secondaryMasteryReach: Long by lazy {
            masteryStats
                .filter { it in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS }
                .sumOf { reachableMax(it).toLong() }
        }
        val critMasteryReach: Long by lazy { reachableMax(Characteristic.MASTERY_CRITICAL).toLong() }

        var epicRelicStages: (() -> Unit)? = null
        var assumedOpt = Opt(0L, 0)
        // World-B subs (objective-capping conditions): per-sub (W-cap, credits), folded PER STATE.
        val worldBSubs = mutableListOf<Pair<Long, Opt>>()
        if (params.useSublimations && "noSubs" !in diag) {
            data class SubOpt(
                val opt: Opt,
                val rarity: SublimationRarity,
            )
            val subOpts = mutableListOf<SubOpt>()
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
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
                val blockRequirement =
                    if (blockAtLeastMax > 0 && cond?.type == SublimationConditionType.BLOCK_AT_LEAST) (cond.value ?: 0) else 0
                var opt = Opt(0L, 0, requiresBlockAtLeast = blockRequirement, src = if (wantSrc) sub.name.fr else "")
                for (eff in sub.effects) {
                    when (eff) {
                        is SublimationEffect.StatEffect -> {
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            val value = eff.magnitudeAtLevel(level)
                            // A negative MAX_MOVEMENT_POINT rider lowers any carrier's MP ceiling.
                            if (eff.characteristic == Characteristic.MAX_MOVEMENT_POINT &&
                                value < 0 &&
                                Characteristic.MOVEMENT_POINT in targetByChar
                            ) {
                                if (-value > 1) return null
                                opt = opt.copy(mpCapMinus = 1)
                                continue
                            }
                            if (value <= 0) {
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
                            val moved = (reachableMax(eff.from).coerceAtLeast(0).toLong() * eff.percent / 100L).toInt()
                            if (moved <= 0) continue
                            opt =
                                when (eff.to) {
                                    Characteristic.DAMAGE_INFLICTED -> opt.copy(d = opt.d + moved)
                                    Characteristic.CRITICAL_HIT -> opt.copy(cc = opt.cc + moved)
                                    else -> {
                                        val netPerUnit = (wOf(eff.to, 1) - wOf(eff.from, 1)).coerceAtLeast(0L)
                                        opt.copy(w = opt.w + netPerUnit * moved)
                                    }
                                }
                        }
                        else -> {}
                    }
                }
                if (capsObjective) {
                    // The sound W-cap for a CARRIER of this sub: the capped mastery component at
                    // its condition-derived cap, the others at their reachable max (+100 base).
                    val t = (cond?.value ?: 0).toLong()
                    val wCap =
                        if (cond?.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST) {
                            wMastery * (100L + elementalMasteryReach + secondaryBudgetCap(t)) + wCritMastery * critMasteryReach
                        } else {
                            wMastery * (100L + elementalMasteryReach + secondaryMasteryReach) + wCritMastery * t
                        }
                    worldBSubs += wCap to opt
                    continue
                }
                if (sub === worldAssume) {
                    assumedOpt = opt
                    continue
                }
                if (opt.w == 0L && opt.d == 0 && opt.ap == 0 && opt.mp == 0 && opt.cc == 0 && opt.hp == 0 && !opt.ramp) continue
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
                val statMask = (1L shl 49) - 1
                var sub = HashMap<Long, Long>()
                sub[0L] = 0L
                for (o in opts) {
                    val next = HashMap(sub)
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
                                block = (geo.block(stat) + ceilDiv(o.block, BLOCK_STEP)).coerceAtMost(geo.blockBucketCap)
                            )
                        val nk = ((cnt + 1).toLong() shl 50) or nStat
                        val nw = wv + o.w
                        val cur = next[nk]
                        if (cur == null || nw > cur) next[nk] = nw
                    }
                    sub = next
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
                        requiresEpicItem = rarity == SublimationRarity.EPIC && cnt > 0,
                        requiresRelicItem = rarity == SublimationRarity.RELIC && cnt > 0,
                        apLow = knapApNeg,
                        ccLowRaw = knapCcNeg,
                        src = if (wantSrc) "$rarity x$cnt" else ""
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
            }
        }

        epicRelicStages?.invoke()
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
        for ((k, wv) in states) {
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
            ): Pair<Long, Long> {
                val wvX = (wv + assumedOpt.w + extra.w).let { if (wCap != null) minOf(it, wCap) else it }
                val di = (geo.d(k).toLong() * diStep + assumedOpt.d + extra.d + rampDi).coerceAtMost(diCap.toLong())
                val grawUb = wvX.coerceIn(0L, DAMAGE_GRAW_MAX)
                val perHit = ((100L + di) * grawUb).coerceAtMost(DAMAGE_SCORE_ABS_MAX)
                val perHitScaled = (perHit / PERHIT_DOWNSCALE).coerceAtMost(PERHIT_SCALED_MAX)
                val apRead =
                    if (geo.assumeApThreshold >= 0) {
                        ((geo.assumeApThreshold + maxOf(assumedOpt.ap, 0)).toLong() + extra.ap)
                    } else {
                        geo.ap(k).toLong() + assumedOpt.ap + extra.ap
                    }
                val throughput = clampedTable[apRead.coerceIn(0L, clampedTable.lastIndex.toLong()).toInt()]
                val raw = (throughput * perHitScaled).coerceAtMost(ROTATION_RAW_MAX)
                val core = (raw * resFactor / FINAL_DOWNSCALE).coerceAtMost(DAMAGE_PERTURN_ABS_MAX)
                if (targets.isEmpty()) return core to core
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
                return core to core * powTable[bucket]
            }

            fun consider(
                extra: Opt,
                wCap: Long?,
                tag: String,
            ) {
                val (core, folded) = foldWith(extra, wCap)
                if (core > bestCore) bestCore = core
                if (folded > bestFolded) {
                    bestFolded = folded
                    bindingKey = k
                    bindingW = wv
                    bindingState =
                        "W=${wv + assumedOpt.w + extra.w} d=${geo.d(k) * diStep}+ramp$rampDi ap=${geo.ap(k)} mp=${geo.mp(k)} " +
                        "cc=${geo.cc(k) * ccStep} hp=${geo.hp(k) * hpStep} e=${geo.e(k)} r=${geo.r(k)} " +
                        "assume=${worldAssume?.name?.fr ?: "-"}$tag core=$core"
                }
            }

            consider(EMPTY_OPT, null, "")
            for ((wCapB, bOpt) in worldBSubs) consider(bOpt, wCapB, " worldB(cap=$wCapB)")
        }
        // Instrument: reconstruct the binding path backward — for each stage (last → first), find
        // a predecessor state + option that lands exactly on the current (key, w).
        val bindingPath = mutableListOf<String>()
        if (provenance && stageLog != null && bindingState.isNotEmpty()) {
            var curK = bindingKey
            var curW = bindingW
            for ((label, preMap, options) in stageLog.reversed()) {
                var found = false
                outer@ for ((pk, pw) in preMap) {
                    for (o in options) {
                        if (pw + o.w != curW) continue
                        if (geo.applyOne(pk, o) == curK) {
                            if (o.src.isNotEmpty() || o.w != 0L || o.d != 0 || o.ap != 0 || o.mp != 0 || o.cc != 0 || o.hp != 0) {
                                bindingPath +=
                                    "$label: ${o.src.ifEmpty { "opt" }} " +
                                    "(w=${o.w} d=${o.d} ap=${o.ap} mp=${o.mp} cc=${o.cc} hp=${o.hp} hpPct=${o.hpPct})"
                            }
                            curK = pk
                            curW = pw
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

    private val EMPTY_OPT = Opt(0L, 0)
}
