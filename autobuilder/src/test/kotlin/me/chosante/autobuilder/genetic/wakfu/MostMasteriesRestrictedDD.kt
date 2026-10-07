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
import kotlin.math.ceil

/**
 * P&B-1 PROTOTYPE (plan §8.15, maintainer GO 2026-07-14) — the RESTRICTED DD: the same stage
 * machinery as [MostMasteriesCertificate] with EXACT-PRIMAL semantics and a beam width W.
 * Every surviving final state's folded value is ≤ some REAL build's value, so the maximum is a
 * TRUE LOWER bound (an incumbent for the Peel-and-Bound search); the certificate is the mirror
 * upper bound.
 *
 * Exactness deltas vs the certificate — every relaxation choice rounds DOWN:
 *  - RAW signed accumulation, no per-option ceil (re-laid packed key, hp exact on 14 bits);
 *    an option that would take any dim below 0 is REJECTED (a real build whose prefix dips
 *    negative in our stage order is merely under-counted — sound);
 *  - saturation at the target stays EXACT for the fold (totalActual clamps per stat at target);
 *  - negative REQUESTED-mastery lines are subtracted; negative PENALIZED secondary-mastery lines
 *    are netted per item (per-item netting under-counts the model's cross-item min(total, 0) —
 *    the mirror of why netNegatives was unsound as an upper bound);
 *  - the TRUE 10-normal-sub cap is IN the state (4-bit count; each normal sub = its own stage,
 *    which also makes every condition check exact against the live dims);
 *  - PERCENT non-HP skills, unmodeled-condition subs (AP_EXACT, objective-capping worlds,
 *    conversions), non-tracked ramps and element-concentration subs are DROPPED (0 credit);
 *  - %HP is floor arithmetic on the exact HP dim.
 *
 * Beam: after each stage, keep the top-W states ranked by the PARTIAL FOLD of the live state
 * (mirrored collapse arithmetic) — dropping states is the restricted-DD move (never unsound on
 * the primal side; it only loses builds).
 */
internal object MostMasteriesRestrictedDD {
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

    class Result(
        val foldedBest: Long,
        val coreBest: Long,
        val states: Int,
        val maxLayerWidth: Int,
        val wallMs: Long,
        val bestState: String = "",
        // P&B-0 piggyback: pre-trim layer width per stage (exact-prefix scoping).
        val layerWidths: List<Pair<String, Int>> = emptyList(),
    )

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
        val capKind: Int = 0,
        val mpCapMinus: Int = 0,
        val block: Int = 0,
        val requiresBlockAtLeast: Int = 0,
        val ramp: Boolean = false,
        // EXACT 10-normal-sub cap: how many normal-sub copies this option consumes.
        val subCount: Int = 0,
        // Review fix (A#3): the solver requires one DISTINCT >=3-socket carrier item per NORMAL sub
        // copy — items grant carrier budget, sub copies consume it (items all precede subs in the
        // stage order, so a single running budget in the state is EXACT).
        val carriers: Int = 0,
        // Weapons split (two stages instead of one 1H×off pair blow-up): a 2H sets the state bit;
        // an off-hand is rejected when it is set. Exact same 2H | 1H(+off) | off-alone semantics.
        val twoHanded: Boolean = false,
        val offHand: Boolean = false,
    ) {
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
                subCount <= o.subCount &&
                carriers >= o.carriers &&
                twoHanded == o.twoHanded &&
                offHand == o.offHand
    }

    private fun prune(options: List<Opt>): List<Opt> {
        val distinct = options.distinct()
        // STRICT domination (mutual domination = stat-equality): a non-strict test annihilates
        // both copies of a stat-identical pair — see the certificate's prune() for the full story.
        return distinct.filter { o -> distinct.none { other -> other !== o && other.dominates(o) && !o.dominates(other) } }
    }

    // Packed key (exact raw values): block(7b @54) cnt(4b @50) ramp(1b @49) mpMinus(1b @48)
    // capKind(2b @46) d(13b @33) ap(5b @28) mp(5b @23) cc(7b @16) hp(14b @2) e(1b @1) r(1b @0)
    private class Geometry(
        val apCap: Int,
        val mpCap: Int,
        val ccCap: Int,
        val hpCap: Int,
        val diCap: Int,
        val caps: List<MostMasteriesCertificate.CapSpec> = emptyList(),
        val blockCap: Int = 0,
    ) {
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
            cnt: Int = 0,
            block: Int = 0,
            twoH: Int = 0,
        ): Long =
            (twoH.toLong() shl 61) or
                (block.toLong() shl 54) or (cnt.toLong() shl 50) or (ramp.toLong() shl 49) or (mpMinus.toLong() shl 48) or
                (capKind.toLong() shl 46) or (d.toLong() shl 33) or (ap.toLong() shl 28) or (mp.toLong() shl 23) or
                (cc.toLong() shl 16) or (hp.toLong() shl 2) or (e.toLong() shl 1) or r.toLong()

        fun d(k: Long): Int = ((k shr 33) and 0x1FFF).toInt()

        fun ap(k: Long): Int = ((k shr 28) and 0x1F).toInt()

        fun mp(k: Long): Int = ((k shr 23) and 0x1F).toInt()

        fun cc(k: Long): Int = ((k shr 16) and 0x7F).toInt()

        fun hp(k: Long): Int = ((k shr 2) and 0x3FFF).toInt()

        fun e(k: Long): Int = ((k shr 1) and 1L).toInt()

        fun r(k: Long): Int = (k and 1L).toInt()

        fun capKind(k: Long): Int = ((k shr 46) and 0x3).toInt()

        fun mpMinus(k: Long): Int = ((k shr 48) and 1L).toInt()

        fun ramp(k: Long): Int = ((k shr 49) and 1L).toInt()

        fun cnt(k: Long): Int = ((k shr 50) and 0xF).toInt()

        fun block(k: Long): Int = ((k shr 54) and 0x7F).toInt()

        fun twoH(k: Long): Int = ((k shr 61) and 1L).toInt()
    }

    /** One state transition, or null when rejected — exact semantics (see class doc). */
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
        if (o.offHand && twoH(k) == 1) return null
        // cnt = REMAINING carrier budget: items with >=3 sockets add (capped at the 10-normal-sub
        // ceiling), each normal sub copy consumes one — a sub without a free carrier is rejected.
        val newCnt = (cnt(k) + o.carriers).coerceAtMost(10) - o.subCount
        if (newCnt < 0) return null
        val newCapKind = if (o.capKind != 0) o.capKind else capKind(k)
        val newMpMinus = (mpMinus(k) + o.mpCapMinus).coerceAtMost(1)
        val newAp = ap(k) + o.ap
        val newCc = cc(k) + o.cc
        val newD = d(k) + o.d
        val newBlock = block(k) + o.block
        var newHp = hp(k) + o.hp
        if (newAp < 0 || newCc < 0 || newD < 0 || newHp < 0 || newBlock < 0) return null
        val newMp = mp(k) + o.mp
        if (newMp < 0) return null
        if (newCapKind > 0) {
            val spec = caps[newCapKind - 1]
            val checkAp = if (o.capKind != 0) ap(k) else newAp
            val checkCc = if (o.capKind != 0) cc(k) else newCc
            if (spec.stat == Characteristic.ACTION_POINT && checkAp > spec.threshold) return null
            if (spec.stat == Characteristic.CRITICAL_HIT && checkCc > spec.threshold) return null
        }
        if (o.requiresBlockAtLeast > 0 && block(k) < o.requiresBlockAtLeast) return null
        val mpEff = (mpCap - newMpMinus).coerceAtLeast(0)
        // %HP (skills stage) — floor arithmetic on the EXACT dim (the dim under-counts the real
        // HP whenever anything saturated earlier, and floor ≤ the model's rounding — sound).
        if (o.hpPct > 0) newHp = newHp * (100 + o.hpPct) / 100
        return key(
            newD.coerceAtMost(diCap),
            newAp.coerceAtMost(apCap),
            newMp.coerceAtMost(mpEff),
            newCc.coerceAtMost(ccCap),
            newHp.coerceAtMost(hpCap),
            if (o.epic) 1 else e,
            if (o.relic) 1 else r,
            newCapKind,
            newMpMinus,
            if (o.ramp) 1 else ramp(k),
            newCnt,
            newBlock.coerceAtMost(blockCap),
            if (o.twoHanded) 1 else twoH(k)
        )
    }

    fun bound(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        beamWidth: Int = 200_000,
        debug: Boolean = false,
    ): Result? {
        val t0 = System.nanoTime()
        if (params.targetStats.masteryElementsToMinimize.isNotEmpty()) return null
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
        val targets = params.targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        if (targets.any { it.characteristic !in SUPPORTED_TARGETS }) return null
        val targetByChar = targets.associateBy { it.characteristic }
        val level = params.character.level
        val diCap = DAMAGE_DI_MAX.toInt()

        fun cap(char: Characteristic): Int = targetByChar[char]?.target ?: 0

        // AT_MOST conditions on trackable stats — EXACT reject semantics on the raw dims.
        val capSpecs = mutableListOf<MostMasteriesCertificate.CapSpec>()
        if (params.useSublimations) {
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                val cond = sub.condition ?: continue
                val stat =
                    when (cond.type) {
                        SublimationConditionType.AP_AT_MOST -> Characteristic.ACTION_POINT
                        SublimationConditionType.CRIT_AT_MOST -> Characteristic.CRITICAL_HIT
                        else -> null
                    } ?: continue
                val threshold = cond.value ?: 0
                if (capSpecs.none { it.stat == stat && it.threshold == threshold }) {
                    capSpecs += MostMasteriesCertificate.CapSpec(stat, threshold)
                }
            }
            if (capSpecs.size > 3) return null
        }

        fun specMax(char: Characteristic): Int = capSpecs.filter { it.stat == char }.maxOfOrNull { it.threshold } ?: -1

        val blockAtLeastMax =
            if (params.useSublimations) {
                sublimations
                    .filter { it.solverChoosable && it.condition?.type == SublimationConditionType.BLOCK_AT_LEAST }
                    .maxOfOrNull { it.condition?.value ?: 0 } ?: 0
            } else {
                0
            }

        val mpDiRamps =
            if (params.useSublimations) {
                sublimations
                    .filter { it.solverChoosable }
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
                ccCap = maxOf(cap(Characteristic.CRITICAL_HIT), specMax(Characteristic.CRITICAL_HIT) + 1).coerceAtMost(127),
                hpCap = cap(Characteristic.HP).coerceAtMost(16_383),
                diCap = diCap,
                caps = capSpecs,
                blockCap = blockAtLeastMax.coerceAtMost(127)
            )

        // SIGNED stat read — negative target-dim lines must be paid (the reject-below-0 rule in
        // applyOne keeps the packed dims non-negative).
        fun statOf(
            e: Equipment,
            c: Characteristic,
        ): Int = e.characteristics[c] ?: 0

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
                        if (params.useRunes) matching.maxOfOrNull { it.valueOn(type, lvl) } ?: 0 else 0
                    }
                )
            }

        fun itemOpts(e: Equipment): List<Opt> {
            // m = SIGNED requested-mastery lines + negative PENALIZED secondary lines netted
            // per item (≤ the model's cross-item min(total, 0) — under-count, sound primal).
            val m =
                e.characteristics.entries.sumOf { (c, v) ->
                    when {
                        c in requested -> v.toLong()
                        c in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS && v < 0 -> v.toLong()
                        else -> 0L
                    }
                }
            val base =
                Opt(
                    m = m,
                    d = statOf(e, Characteristic.DAMAGE_INFLICTED),
                    ap = statOf(e, Characteristic.ACTION_POINT),
                    mp = statOf(e, Characteristic.MOVEMENT_POINT),
                    cc = statOf(e, Characteristic.CRITICAL_HIT),
                    hp = statOf(e, Characteristic.HP),
                    epic = e.rarity == me.chosante.common.Rarity.EPIC,
                    relic = e.rarity == me.chosante.common.Rarity.RELIC,
                    block = if (blockAtLeastMax > 0) statOf(e, Characteristic.BLOCK_PERCENTAGE) else 0,
                    // A#3: >=3-socket items are the NORMAL subs' carriers (one distinct item each).
                    carriers = if (e.maxShardSlots >= 3) 1 else 0
                )
            val slots = if (params.useRunes) e.maxShardSlots else 0
            if (slots == 0) return listOf(base)
            val perAxis = runeAxes.map { it(e.itemType, e.level) }
            val out = mutableListOf<Opt>()
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

        // Collapse constants — precomputed here so the beam rank can use the partial fold.
        val totalExpected =
            targets
                .sumOf { it.target.toLong() * params.targetStats.scaledWeight(it) }
                .coerceAtLeast(1L)
        val bucketSize =
            if (totalExpected <= MAX_POWER_TABLE_INDEX) 1L else ceil(totalExpected.toDouble() / MAX_POWER_TABLE_INDEX).toLong()
        val maxIndex = if (totalExpected <= MAX_POWER_TABLE_INDEX) totalExpected.toInt() else ((totalExpected + bucketSize - 1) / bucketSize).toInt()
        val powScale = penaltyPowScale(maxIndex.toLong())
        val powCache = HashMap<Int, Long>()

        fun power6(index: Int): Long = powCache.getOrPut(index) { penaltyMultiplier(index.toLong(), powScale) }

        fun weight(char: Characteristic): Long = targetByChar[char]?.let { params.targetStats.scaledWeight(it) } ?: 0L

        fun targetOf(char: Characteristic): Long = targetByChar[char]?.target?.toLong() ?: 0L

        fun foldedRaw(
            mv: Long,
            d: Long,
            ap: Long,
            mp: Long,
            cc: Long,
            hp: Long,
            ramp: Boolean,
        ): Long {
            val rampDi = if (ramp && mpDiRamp != null) maxOf(mpDiRamp.second.contribution(mp.toInt()), 0) else 0
            val di = (d + rampDi).coerceAtMost(diCap.toLong())
            val core = (maxOf(mv, 0L) * (100L + di) / 100L).coerceAtMost(MASTERY_SCORE_ABS_MAX)
            if (targets.isEmpty()) return core
            val totalActual =
                weight(Characteristic.ACTION_POINT) * minOf(ap, targetOf(Characteristic.ACTION_POINT)) +
                    weight(Characteristic.MOVEMENT_POINT) * minOf(mp, targetOf(Characteristic.MOVEMENT_POINT)) +
                    weight(Characteristic.CRITICAL_HIT) * minOf(cc, targetOf(Characteristic.CRITICAL_HIT)) +
                    weight(Characteristic.HP) * minOf(hp, targetOf(Characteristic.HP))
            val bucket = (totalActual.coerceIn(1L, totalExpected) / bucketSize).toInt().coerceAtMost(maxIndex)
            return core * power6(bucket) * OVERSHOOT_SCALE_MIRROR + (OVERSHOOT_SCALE_MIRROR - 1)
        }

        fun foldedOf(
            k: Long,
            mv: Long,
        ): Long =
            foldedRaw(
                mv,
                geo.d(k).toLong(),
                geo.ap(k).toLong(),
                geo.mp(k).toLong(),
                geo.cc(k).toLong(),
                geo.hp(k).toLong(),
                geo.ramp(k) == 1
            )

        val baseValues = params.character.baseCharacteristicValues
        var states = HashMap<Long, Long>()
        states[
            geo.key(
                0,
                (baseValues[Characteristic.ACTION_POINT] ?: 0).coerceIn(0, geo.apCap),
                (baseValues[Characteristic.MOVEMENT_POINT] ?: 0).coerceIn(0, geo.mpCap),
                (baseValues[Characteristic.CRITICAL_HIT] ?: 0).coerceIn(0, geo.ccCap),
                (baseValues[Characteristic.HP] ?: 0).coerceIn(0, geo.hpCap),
                0,
                0,
                block = (baseValues[Characteristic.BLOCK_PERCENTAGE] ?: 0).coerceIn(0, geo.blockCap)
            )
        ] = 0L

        val layerWidths = mutableListOf<Pair<String, Int>>()
        var maxLayerWidth = 0

        // Stage COLLECTION: the whole stage sequence is built first so the beam rank can use an
        // OPTIMISTIC completion (state ⊕ suffix maxima) — a purely retrospective rank measured a
        // ~72% S2 plateau (it keeps early target-saturators, drops mastery-rich late-fillers).
        val stages = mutableListOf<Pair<String, List<Opt>>>()

        fun step(
            label: String,
            options: List<Opt>,
        ) {
            stages += label to options
        }

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
                subCount = a.subCount + b.subCount,
                carriers = a.carriers + b.carriers
            )

        // Stage order mirrors the certificate: big exact-pair stages first.
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
        // Weapons in TWO stages via the twoH state bit (the single-stage 1H×off pair product was
        // 4.2e9 transitions on S2 — 93% of the whole run): main hand [none | 2H | 1H], then
        // off-hand [none | off] with off rejected behind a 2H. Exact same shapes.
        run {
            val main = mutableListOf(Opt(0L, 0))
            main +=
                pool[ItemType.TWO_HANDED_WEAPONS].orEmpty().flatMap { e ->
                    prune(itemOpts(e)).map { it.copy(twoHanded = true) }
                }
            main += pool[ItemType.ONE_HANDED_WEAPONS].orEmpty().flatMap { prune(itemOpts(it)) }
            step("weapon-main", main)
            val off = mutableListOf(Opt(0L, 0))
            off +=
                pool[ItemType.OFF_HAND_WEAPONS].orEmpty().flatMap { e ->
                    prune(itemOpts(e)).map { it.copy(offHand = true) }
                }
            step("weapon-off", off)
        }
        val singleSlots =
            pool.keys - setOf(ItemType.RING, ItemType.ONE_HANDED_WEAPONS, ItemType.TWO_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS)
        for (slot in singleSlots) {
            step(slot.name, listOf(Opt(0L, 0)) + pool[slot].orEmpty().flatMap { prune(itemOpts(it)) })
        }

        // Sublimations — one stage per NORMAL sub copy (exact 10-cap via the state count, exact
        // condition checks against the live dims); EPIC/RELIC as one single-choice stage each.
        val epicOpts = mutableListOf<Opt>()
        val relicOpts = mutableListOf<Opt>()
        if (params.useSublimations) {
            val normalOpts = mutableListOf<Pair<String, Opt>>()
            subs@ for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                if (sub.bestElementConcentration != null) continue
                val cond = sub.condition
                var capKind = 0
                var blockRequirement = 0
                if (cond != null) {
                    when (cond.type) {
                        SublimationConditionType.AP_AT_MOST, SublimationConditionType.CRIT_AT_MOST -> {
                            val condStat =
                                if (cond.type == SublimationConditionType.AP_AT_MOST) {
                                    Characteristic.ACTION_POINT
                                } else {
                                    Characteristic.CRITICAL_HIT
                                }
                            capKind = capSpecs.indexOfFirst { it.stat == condStat && it.threshold == (cond.value ?: 0) } + 1
                        }
                        SublimationConditionType.BLOCK_AT_LEAST -> blockRequirement = cond.value ?: 0
                        // Any condition without an exact model is a DROPPED sub (under-count).
                        else -> continue@subs
                    }
                }
                var opt = Opt(0L, 0, capKind = capKind, requiresBlockAtLeast = blockRequirement)
                for (eff in sub.effects) {
                    when (eff) {
                        is SublimationEffect.StatEffect -> {
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            val value = eff.magnitudeAtLevel(level)
                            if (eff.characteristic == Characteristic.MAX_MOVEMENT_POINT &&
                                value < 0 &&
                                Characteristic.MOVEMENT_POINT in targetByChar
                            ) {
                                if (-value > 1) continue@subs
                                opt = opt.copy(mpCapMinus = 1)
                                continue
                            }
                            opt =
                                when {
                                    eff.characteristic in requested -> opt.copy(m = opt.m + value)
                                    eff.characteristic in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS && value < 0 ->
                                        opt.copy(m = opt.m + value)
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
                            if (mpDiRamp != null && eff === mpDiRamp.second) {
                                opt = opt.copy(ramp = true)
                            }
                            // Other ramps: 0 credit (the certificate prices them at reachableMax —
                            // an over-count the primal cannot afford).
                        }

                        // A conversion has no exact model here — the whole sub is dropped.
                        is SublimationEffect.Conversion -> continue@subs
                        else -> {}
                    }
                }
                if (opt.m == 0L && opt.d == 0 && opt.ap == 0 && opt.mp == 0 && opt.cc == 0 && opt.hp == 0 && !opt.ramp) continue
                when (sub.rarity) {
                    SublimationRarity.NORMAL ->
                        repeat(sub.maxCopies.coerceAtLeast(1)) { i ->
                            normalOpts += "${sub.name.fr}#$i" to opt.copy(subCount = 1)
                        }
                    SublimationRarity.EPIC -> epicOpts += opt.copy(requiresEpicItem = true)
                    SublimationRarity.RELIC -> relicOpts += opt.copy(requiresRelicItem = true)
                }
            }
            for ((label, opt) in normalOpts) step("sub:$label", listOf(Opt(0L, 0), opt))
        }

        // Skills — before the EPIC/RELIC sub stages (kept AFTER skills like the certificate so
        // block conditions read the fullest sheet; their flat HP missing the %HP multiplier is
        // an under-count — sound).
        run {
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

                fun creditOf(
                    sk: me.chosante.common.skills.SkillCharacteristic,
                    pts: Int,
                ): Opt {
                    val skChar = sk.characteristic ?: return Opt(0L, 0)
                    if (sk.unitType == me.chosante.common.skills.UnitType.PERCENT) {
                        // %HP is exact (multiplicative floor on the dim); every OTHER percent skill
                        // is dropped — the certificate's reachableMax pricing over-counts.
                        return if (skChar == Characteristic.HP) Opt(0L, 0, hpPct = pts * sk.unitValue) else Opt(0L, 0)
                    }
                    val value = pts.toLong() * sk.unitValue
                    val v = value.toInt()
                    return when {
                        skChar in requested -> Opt(m = value, d = 0)
                        skChar == Characteristic.DAMAGE_INFLICTED -> Opt(0L, v)
                        skChar == Characteristic.ACTION_POINT -> Opt(0L, 0, ap = v)
                        skChar == Characteristic.MOVEMENT_POINT -> Opt(0L, 0, mp = v)
                        skChar == Characteristic.CRITICAL_HIT -> Opt(0L, 0, cc = v)
                        skChar == Characteristic.BLOCK_PERCENTAGE -> Opt(0L, 0, block = v)
                        else -> Opt(0L, 0, hp = v)
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
                            pts += 1
                        }
                    }
                    options = next
                }
                step("skills-${branch.javaClass.simpleName}", options.map { it.first }.distinct())
            }
        }

        if (epicOpts.isNotEmpty()) step("subs-epic", listOf(Opt(0L, 0)) + prune(epicOpts))
        if (relicOpts.isNotEmpty()) step("subs-relic", listOf(Opt(0L, 0)) + prune(relicOpts))

        // Suffix maxima per stage (component-wise max option, summed over the remaining stages) —
        // the rank's OPTIMISTIC completion. Heuristic only (hpPct ignored): never affects
        // soundness, only which states the beam keeps.
        class Sfx(
            val m: Long,
            val d: Long,
            val ap: Long,
            val mp: Long,
            val cc: Long,
            val hp: Long,
        )

        val suffix = arrayOfNulls<Sfx>(stages.size + 1)
        suffix[stages.size] = Sfx(0, 0, 0, 0, 0, 0)
        for (i in stages.indices.reversed()) {
            val opts = stages[i].second
            val s = suffix[i + 1]!!
            suffix[i] =
                Sfx(
                    s.m + opts.maxOf { it.m }.coerceAtLeast(0L),
                    s.d + opts.maxOf { it.d }.coerceAtLeast(0).toLong(),
                    s.ap + opts.maxOf { it.ap }.coerceAtLeast(0).toLong(),
                    s.mp + opts.maxOf { it.mp }.coerceAtLeast(0).toLong(),
                    s.cc + opts.maxOf { it.cc }.coerceAtLeast(0).toLong(),
                    s.hp + opts.maxOf { it.hp }.coerceAtLeast(0).toLong()
                )
        }

        // The SWEEP. Trim rank = optimistic fold of (state ⊕ remaining suffix maxima); computed
        // ONCE per entry (a per-comparison selector was the first run's CPU wall).
        for ((i, stage) in stages.withIndex()) {
            val (label, options) = stage
            val sfx = suffix[i + 1]!!

            fun rank(
                k: Long,
                mv: Long,
            ): Long =
                foldedRaw(
                    mv + sfx.m,
                    geo.d(k) + sfx.d,
                    geo.ap(k) + sfx.ap,
                    geo.mp(k) + sfx.mp,
                    geo.cc(k) + sfx.cc,
                    geo.hp(k) + sfx.hp,
                    geo.ramp(k) == 1 || mpDiRamp != null
                )

            fun trim(map: HashMap<Long, Long>): HashMap<Long, Long> {
                if (map.size <= beamWidth) return map
                val ranked = arrayOfNulls<Triple<Long, Long, Long>>(map.size)
                var n = 0
                for ((k, mv) in map) ranked[n++] = Triple(k, mv, rank(k, mv))
                @Suppress("UNCHECKED_CAST")
                (ranked as Array<Triple<Long, Long, Long>>).sortByDescending { it.third }
                val kept = HashMap<Long, Long>(beamWidth * 2)
                for (j in 0 until beamWidth) kept[ranked[j].first] = ranked[j].second
                return kept
            }

            var next = HashMap<Long, Long>(minOf(states.size * 2, beamWidth * 4))
            var produced = 0L
            for ((k, mv) in states) {
                for (o in options) {
                    val nk = geo.applyOne(k, o) ?: continue
                    val nm = mv + o.m
                    val cur = next[nk]
                    if (cur == null || nm > cur) next[nk] = nm
                    produced++
                }
                // Exact keys barely collide, so |states|×|options| would materialize whole —
                // compact MID-STAGE (dropping is always allowed on the primal side).
                if (next.size > beamWidth * 10) next = trim(next)
            }
            layerWidths += label to maxOf(next.size, if (produced > Int.MAX_VALUE) Int.MAX_VALUE else produced.toInt())
            if (next.size > maxLayerWidth) maxLayerWidth = next.size
            states = trim(next)
            if (debug) println("MM_PNB1_STAGE $label produced=$produced width=${next.size} kept=${states.size}")
        }

        var bestCore = 0L
        var bestFolded = 0L
        var bestState = ""
        for ((k, mv) in states) {
            val rampDi =
                if (geo.ramp(k) == 1 && mpDiRamp != null) maxOf(mpDiRamp.second.contribution(geo.mp(k)), 0) else 0
            val di = (geo.d(k).toLong() + rampDi).coerceAtMost(diCap.toLong())
            val core = (maxOf(mv, 0L) * (100L + di) / 100L).coerceAtMost(MASTERY_SCORE_ABS_MAX)
            if (core > bestCore) bestCore = core
            val folded = foldedOf(k, mv)
            if (folded > bestFolded) {
                bestFolded = folded
                bestState =
                    "M=$mv d=${geo.d(k)}+ramp$rampDi ap=${geo.ap(k)} mp=${geo.mp(k)} cc=${geo.cc(k)} " +
                    "hp=${geo.hp(k)} e=${geo.e(k)} r=${geo.r(k)} cnt=${geo.cnt(k)} core=$core"
            }
        }
        return Result(bestFolded, bestCore, states.size, maxLayerWidth, (System.nanoTime() - t0) / 1_000_000, bestState, layerWidths)
    }

    private val OVERSHOOT_SCALE_MIRROR = WakfuBuildSolver.OVERSHOOT_SCALE
}
