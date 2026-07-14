package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.scaledWeight
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationRarity
import java.math.BigInteger
import kotlin.math.ceil

/**
 * S4-1 PROTOTYPE (plan §9) — a target-aware sound upper bound on the MAX-DAMAGE SOFT folded
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
 * same target fold) with the CORE swapped. The value tracked per state is the single scalar
 *
 *   `W = (400 + critCap)·M' + 5·critCap·K`      (M' = ΣMastery, the +100 base rides the seed)
 *
 * which upper-bounds Graw for EVERY build: `crit ≤ critCap` ⟹
 * `Graw = 400·M + crit·(M + 5K) ≤ (400+critCap)·M + 5·critCap·K = W`. On the S4 shape the CC
 * target equals the crit cap (100), so the binding (target-saturated) states sit AT the cap and
 * the fold is near-exact there; unsaturated states over-credit the crit factor but pay power6.
 *
 * MEASUREMENT-ONLY simplifications vs the production MM certificate (each an OVER-count — sound):
 *  - conditional subs credited as if their condition held (NO world split, NO world B): dropping a
 *    constraint only enlarges the feasible set. The attribution harness prices this.
 *  - Elemental Concentration (choosable in single-element max-damage): its +DI credited, the
 *    3-weakest-elements mastery penalty ignored.
 *  - conversions credited additively at `percent · reachableMax(from)` without debiting the source.
 *  - ramps (PerStatStep) priced at the sound reachable max of their source (no ramp state bit).
 *  - no block dim (the AT_LEAST-gated subs are credited unconditionally instead — sound).
 *
 * DIFFERENCES vs the MM geometry, forced by the damage core:
 *  - the AP dim saturates at [MAX_OUT_OF_COMBAT_AP] (not the AP target): throughput[AP] GROWS with
 *    AP, so target-saturation would under-read the core of an above-target-AP build.
 *  - the CC dim saturates at max(cc target, critCap): the dim feeds BOTH the target fold and the
 *    crit factor... (the W fold uses critCap uniformly today; the dim headroom keeps a future
 *    per-state crit read sound).
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

    // Same grid as the MM certificate's default (coarse) profile.
    private const val CC_STEP = 10
    private const val HP_STEP = 500
    private const val DI_STEP = 1

    class Result(
        val foldedBound: Long,
        val coreBound: Long,
        val states: Int,
        val wallMs: Long,
        val bindingState: String = "",
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
    ) {
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
                requiresRelicItem == o.requiresRelicItem
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
    ) {
        init {
            require(hpBucketCap <= 0x1FF) { "hpBucketCap $hpBucketCap overflows the 9-bit hp field" }
            require(ccBucketCap <= 0x7F) { "ccBucketCap $ccBucketCap overflows the 7-bit cc field" }
            require(diBucketCap <= 0x1FFF) { "diBucketCap $diBucketCap overflows the 13-bit d field" }
        }

        // Packed key: d(13b @28) ap(5b @23) mp(5b @18) cc(7b @11) hp(9b @2) e(1b @1) r(1b @0)
        fun key(
            d: Int,
            ap: Int,
            mp: Int,
            cc: Int,
            hp: Int,
            e: Int,
            r: Int,
        ): Long =
            (d.toLong() shl 28) or (ap.toLong() shl 23) or (mp.toLong() shl 18) or
                (cc.toLong() shl 11) or (hp.toLong() shl 2) or (e.toLong() shl 1) or r.toLong()

        fun d(k: Long): Int = ((k shr 28) and 0x1FFF).toInt()

        fun ap(k: Long): Int = ((k shr 23) and 0x1F).toInt()

        fun mp(k: Long): Int = ((k shr 18) and 0x1F).toInt()

        fun cc(k: Long): Int = ((k shr 11) and 0x7F).toInt()

        fun hp(k: Long): Int = ((k shr 2) and 0x1FF).toInt()

        fun e(k: Long): Int = ((k shr 1) and 1L).toInt()

        fun r(k: Long): Int = (k and 1L).toInt()
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
        val flatHpBuckets = hp(k) + ceilDiv(o.hp, HP_STEP)
        val hpBuckets = if (o.hpPct > 0) ceilDiv(flatHpBuckets * (100 + o.hpPct), 100) else flatHpBuckets
        return key(
            (d(k) + ceilDiv(o.d, DI_STEP)).coerceAtMost(diBucketCap),
            (ap(k) + o.ap).coerceAtMost(apCap),
            (mp(k) + o.mp).coerceAtMost(mpCap),
            (cc(k) + ceilDiv(o.cc, CC_STEP)).coerceAtMost(ccBucketCap),
            hpBuckets.coerceAtMost(hpBucketCap),
            if (o.epic) 1 else e,
            if (o.relic) 1 else r
        )
    }

    private fun Geometry.apply(
        states: HashMap<Long, Long>,
        options: List<Opt>,
    ): HashMap<Long, Long> {
        val next = HashMap<Long, Long>(states.size * 2)
        for ((k, wv) in states) {
            for (o in options) {
                val nk = applyOne(k, o) ?: continue
                val nw = wv + o.w
                val cur = next[nk]
                if (cur == null || nw > cur) next[nk] = nw
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
        // Attribution seams (UNSOUND as a bound — pricing only): "noCondSubs", "noSubs",
        // "noSkills", "noRunes".
        diag: Set<String> = emptySet(),
    ): Result? {
        val t0 = System.nanoTime()
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return null
        val scenario = params.damageScenario
        // Scope gates — mirror the hard-leg certifier's conservative bails.
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

        val geo =
            Geometry(
                // throughput[AP] grows with AP: track the full out-of-combat reach, not the target.
                apCap = MAX_OUT_OF_COMBAT_AP.toInt(),
                mpCap = cap(Characteristic.MOVEMENT_POINT).coerceAtMost(MAX_OUT_OF_COMBAT_MP.toInt()),
                // The CC dim feeds the target fold AND (headroom for) the crit factor.
                ccBucketCap = ceilDiv(maxOf(cap(Characteristic.CRITICAL_HIT), critCap.toInt()), CC_STEP),
                hpBucketCap = ceilDiv(cap(Characteristic.HP), HP_STEP),
                diBucketCap = ceilDiv(diCap, DI_STEP)
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
                    relic = e.rarity == me.chosante.common.Rarity.RELIC
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
                                    hp = base.hp + a4 * perAxis[3]
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
                ceilDiv((baseValues[Characteristic.CRITICAL_HIT] ?: 0).coerceAtLeast(0), CC_STEP).coerceAtMost(geo.ccBucketCap),
                ceilDiv((baseValues[Characteristic.HP] ?: 0).coerceAtLeast(0), HP_STEP).coerceAtMost(geo.hpBucketCap),
                0,
                0
            )
        ] = seedW

        fun step(
            label: String,
            options: List<Opt>,
        ) {
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
                relic = a.relic || b.relic
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

        // Sound layer-independent reachable max of a stat (percent skills, ramps, conversions).
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

        // Sublimations: caps-only knapsack per rarity (10/1/1) with epic/relic carrier binding.
        // EVERY conditional sub credited as if its condition held (a pure relaxation — sound).
        if (params.useSublimations && "noSubs" !in diag) {
            data class SubOpt(
                val opt: Opt,
                val rarity: SublimationRarity,
            )
            val subOpts = mutableListOf<SubOpt>()
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                if ("noCondSubs" in diag && sub.condition != null) continue
                var opt = Opt(0L, 0)
                for (eff in sub.effects) {
                    when (eff) {
                        is SublimationEffect.StatEffect -> {
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            val value = eff.magnitudeAtLevel(level)
                            if (value <= 0) continue
                            opt =
                                when (eff.characteristic) {
                                    Characteristic.DAMAGE_INFLICTED -> opt.copy(d = opt.d + value)
                                    Characteristic.ACTION_POINT -> opt.copy(ap = opt.ap + value)
                                    Characteristic.MOVEMENT_POINT -> opt.copy(mp = opt.mp + value)
                                    Characteristic.CRITICAL_HIT -> opt.copy(cc = opt.cc + value)
                                    Characteristic.HP -> opt.copy(hp = opt.hp + value)
                                    else -> opt.copy(w = opt.w + wOf(eff.characteristic, value))
                                }
                        }
                        is SublimationEffect.PerStatStep -> {
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
                        // Conversion: credit `percent · reachableMax(from)` on the TO axis without
                        // debiting the source — over-count, sound.
                        is SublimationEffect.Conversion -> {
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            val moved = (reachableMax(eff.from).coerceAtLeast(0).toLong() * eff.percent / 100L).toInt()
                            if (moved <= 0) continue
                            opt =
                                when (eff.to) {
                                    Characteristic.DAMAGE_INFLICTED -> opt.copy(d = opt.d + moved)
                                    Characteristic.CRITICAL_HIT -> opt.copy(cc = opt.cc + moved)
                                    else -> opt.copy(w = opt.w + wOf(eff.to, moved))
                                }
                        }
                        else -> {}
                    }
                }
                if (opt.w == 0L && opt.d == 0 && opt.ap == 0 && opt.mp == 0 && opt.cc == 0 && opt.hp == 0) continue
                if (debug) println("S4_PROTO_SUB ${sub.name.fr} rarity=${sub.rarity} w=${opt.w} d=${opt.d} copies=${sub.maxCopies}")
                repeat(sub.maxCopies.coerceAtLeast(1)) { subOpts += SubOpt(opt, sub.rarity) }
            }

            fun bucketOptions(
                rarity: SublimationRarity,
                capCount: Int,
            ): List<Opt> {
                val opts = subOpts.filter { it.rarity == rarity }.map { it.opt }
                if (opts.isEmpty()) return listOf(Opt(0L, 0))
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
                                (geo.d(stat) + ceilDiv(o.d, DI_STEP)).coerceAtMost(geo.diBucketCap),
                                (geo.ap(stat) + o.ap).coerceAtMost(geo.apCap),
                                (geo.mp(stat) + o.mp).coerceAtMost(geo.mpCap),
                                (geo.cc(stat) + ceilDiv(o.cc, CC_STEP)).coerceAtMost(geo.ccBucketCap),
                                (geo.hp(stat) + ceilDiv(o.hp, HP_STEP)).coerceAtMost(geo.hpBucketCap),
                                0,
                                0
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
                        d = geo.d(stat) * DI_STEP,
                        ap = geo.ap(stat),
                        mp = geo.mp(stat),
                        cc = geo.cc(stat) * CC_STEP,
                        hp = geo.hp(stat) * HP_STEP,
                        requiresEpicItem = rarity == SublimationRarity.EPIC && cnt > 0,
                        requiresRelicItem = rarity == SublimationRarity.RELIC && cnt > 0
                    )
                }
            }
            step("subs-normal", bucketOptions(SublimationRarity.NORMAL, 10))
            step("subs-epic", prune(bucketOptions(SublimationRarity.EPIC, 1)))
            step("subs-relic", prune(bucketOptions(SublimationRarity.RELIC, 1)))
        }

        // Skills — after subs (the %HP skill multiplies the whole HP dim). Paired majors are
        // expanded into their two component credits (MP+elemental, Range+elemental, …): unlike
        // MM, the elemental half feeds THIS core.
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
                                c in targetByChar
                        )
                val relevant = branch.getCharacteristics().filter { sk -> componentsOf(sk).any { relevantChar(it.characteristic) } }
                if (relevant.isEmpty()) continue
                val budget = branch.maxPointsToAssign

                fun creditOf(
                    sk: me.chosante.common.skills.SkillCharacteristic,
                    pts: Int,
                ): Opt {
                    var acc = Opt(0L, 0)
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
                                Characteristic.ACTION_POINT -> acc.copy(ap = acc.ap + v)
                                Characteristic.MOVEMENT_POINT -> acc.copy(mp = acc.mp + v)
                                Characteristic.CRITICAL_HIT -> acc.copy(cc = acc.cc + v)
                                Characteristic.HP -> acc.copy(hp = acc.hp + v)
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
        for ((k, wv) in states) {
            val di = (geo.d(k).toLong() * DI_STEP).coerceAtMost(diCap.toLong())
            // W ≥ Graw for every build represented by this state; the chain below is monotone in
            // every factor, so it is ≥ the solver's damage var. Mirror the exact clamps/divisions.
            val grawUb = wv.coerceIn(0L, DAMAGE_GRAW_MAX)
            val perHit = ((100L + di) * grawUb).coerceAtMost(DAMAGE_SCORE_ABS_MAX)
            val perHitScaled = (perHit / PERHIT_DOWNSCALE).coerceAtMost(PERHIT_SCALED_MAX)
            val throughput = clampedTable[geo.ap(k).coerceIn(0, clampedTable.lastIndex)]
            val raw = (throughput * perHitScaled).coerceAtMost(ROTATION_RAW_MAX)
            val core = (raw * resFactor / FINAL_DOWNSCALE).coerceAtMost(DAMAGE_PERTURN_ABS_MAX)
            if (core > bestCore) bestCore = core
            val folded =
                if (targets.isEmpty()) {
                    core
                } else {
                    val totalActual =
                        weight(Characteristic.ACTION_POINT) * minOf(geo.ap(k).toLong(), targetOf(Characteristic.ACTION_POINT)) +
                            weight(Characteristic.MOVEMENT_POINT) * minOf(geo.mp(k).toLong(), targetOf(Characteristic.MOVEMENT_POINT)) +
                            weight(Characteristic.CRITICAL_HIT) * minOf(geo.cc(k).toLong() * CC_STEP, targetOf(Characteristic.CRITICAL_HIT)) +
                            weight(Characteristic.HP) * minOf(geo.hp(k).toLong() * HP_STEP, targetOf(Characteristic.HP))
                    val bucket = (totalActual.coerceIn(1L, totalExpected) / bucketSize).toInt().coerceAtMost(maxIndex)
                    core * powTable[bucket]
                }
            if (folded > bestFolded) {
                bestFolded = folded
                bindingState =
                    "W=$wv d=$di ap=${geo.ap(k)} mp=${geo.mp(k)} cc=${geo.cc(k) * CC_STEP} " +
                    "hp=${geo.hp(k) * HP_STEP} e=${geo.e(k)} r=${geo.r(k)} core=$core"
            }
        }
        return Result(bestFolded, bestCore, states.size, (System.nanoTime() - t0) / 1_000_000, bindingState)
    }
}
