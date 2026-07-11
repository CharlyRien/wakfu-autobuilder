package me.chosante.autobuilder.genetic.wakfu

import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationRarity

/**
 * M3 PROTOTYPE (docs/MOST_MASTERIES_PERF_PLAN.md P3 gate) — a one-cell sound upper bound on the
 * most-masteries UNPENALIZED objective `max(M, 0) × (100 + clamp(D)) / 100` where `M` is the sum of
 * the requested non-element masteries and `D` the build's Damage Inflicted.
 *
 * MEASUREMENT-ONLY: never wired to production. Every simplification is an OVER-count (sound for an
 * upper bound); the tightness test quantifies how much they cost:
 *  - negative mastery lines are ignored (they can only lower M);
 *  - rune sockets are colour-agnostic and every socket carries the best objective rune for its slot;
 *  - skills credit each branch's full budget at the branch's best per-point objective value, and the
 *    M and D budgets are credited independently (the same point can't buy both in reality);
 *  - sublimations ignore carrier sockets, colours and epic-binding; conditional subs are credited as
 *    if their condition held; the 10-normal/1-epic/1-relic caps ARE enforced (a small knapsack DP);
 *  - the EPIC/RELIC item caps (≤1 each) ARE enforced (DP states) — max-damage showed they matter.
 *
 * v1 GATE: bails (returns null) when the request wants elemental masteries (the random-element fold
 * and the min-over-elements are the mode's genuinely hard part — out of scope for M3).
 */
internal object MostMasteriesBoundPrototype {
    /** DP state: DI total (capped), epic item used, relic item used → best M. */
    private class Frontier(
        val diCap: Int,
    ) {
        // m[d][epic][relic]; Long.MIN_VALUE = unreachable.
        val m = Array(diCap + 1) { Array(2) { LongArray(2) { Long.MIN_VALUE } } }

        fun seed() {
            m[0][0][0] = 0L
        }

        inline fun forEachReachable(block: (d: Int, e: Int, r: Int, m: Long) -> Unit) {
            for (d in 0..diCap) {
                for (e in 0..1) {
                    for (r in 0..1) {
                        val v = m[d][e][r]
                        if (v != Long.MIN_VALUE) block(d, e, r, v)
                    }
                }
            }
        }
    }

    /**
     * One choice inside a stage: adds (m, d), possibly consuming the epic/relic ITEM budget, or
     * (for epic/relic SUBS) requiring that an epic/relic item is already equipped — Wakfu's
     * carrier-binding rule, mirrored from [gateSublimationsOnCarrierItems].
     */
    private data class Opt(
        val m: Long,
        val d: Int,
        val epic: Boolean = false,
        val relic: Boolean = false,
        val requiresEpicItem: Boolean = false,
        val requiresRelicItem: Boolean = false,
    )

    private fun Frontier.debugSummary(): String {
        var bestM = Long.MIN_VALUE
        var bestScore = 0L
        var bestD = 0
        forEachReachable { d, _, _, mv ->
            if (mv > bestM) bestM = mv
            val s = (maxOf(mv, 0L) * (100L + d)) / 100L
            if (s > bestScore) {
                bestScore = s
                bestD = d
            }
        }
        return "bestM=$bestM bestScore=$bestScore atD=$bestD"
    }

    private fun Frontier.applyStage(options: List<Opt>): Frontier {
        val next = Frontier(diCap)
        forEachReachable { d, e, r, mv ->
            for (o in options) {
                if (o.epic && e == 1) continue
                if (o.relic && r == 1) continue
                if (o.requiresEpicItem && e == 0) continue
                if (o.requiresRelicItem && r == 0) continue
                val ne = if (o.epic) 1 else e
                val nr = if (o.relic) 1 else r
                val nd = (d + o.d).coerceAtMost(diCap)
                val nm = mv + o.m
                if (nm > next.m[nd][ne][nr]) next.m[nd][ne][nr] = nm
            }
        }
        return next
    }

    /**
     * The bound, in SCORER units (comparable to `SolverResult.matchPercentage` for a targets-met
     * build, and `≥` it for any build since `penaltyFactor ≥ 1`). Null = v1 gate bail.
     */
    fun bound(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
    ): Long? {
        // v1 gate: no elemental-mastery request (random-element fold out of scope).
        if (params.targetStats.masteryElementsToMinimize.isNotEmpty()) return null
        val requested =
            params.targetStats
                .map { it.characteristic }
                .filter {
                    it in
                        listOf(
                            Characteristic.MASTERY_BACK,
                            Characteristic.MASTERY_BERSERK,
                            Characteristic.MASTERY_CRITICAL,
                            Characteristic.MASTERY_DISTANCE,
                            Characteristic.MASTERY_HEALING,
                            Characteristic.MASTERY_MELEE
                        )
                }.toSet()
        if (requested.isEmpty()) return null
        if (params.forcedItems.isNotEmpty() || params.forcedRunesByItem.isNotEmpty() || params.forcedSublimations.isNotEmpty()) return null

        val diCap = DAMAGE_DI_MAX.toInt()
        val level = params.character.level

        // Best objective-relevant rune value per slot (doubled where the slot favours it). Sockets are
        // exact-filled by the model, so every socket at the best value is the sound ceiling.
        val bestRunePerSlot = HashMap<ItemType, Int>()

        fun runeCeiling(
            type: ItemType,
            itemLevel: Int,
            slots: Int,
        ): Long {
            if (slots == 0 || !params.useRunes) return 0L
            val best =
                runes
                    .filter { it.characteristic in requested }
                    .maxOfOrNull { it.valueOn(type, itemLevel) } ?: 0
            bestRunePerSlot.merge(type, best, ::maxOf)
            return best.toLong() * slots
        }

        fun itemOpt(e: Equipment): Opt {
            val m =
                e.characteristics.entries.sumOf { (c, v) -> if (c in requested && v > 0) v.toLong() else 0L } +
                    runeCeiling(e.itemType, e.level, e.maxShardSlots)
            val d = (e.characteristics[Characteristic.DAMAGE_INFLICTED] ?: 0).coerceAtLeast(0)
            return Opt(m, d, epic = e.rarity == me.chosante.common.Rarity.EPIC, relic = e.rarity == me.chosante.common.Rarity.RELIC)
        }

        var f = Frontier(diCap).also { it.seed() }

        // Single slots (everything except weapons and rings — those need pair logic).
        val singleSlots =
            pool.keys - setOf(ItemType.RING, ItemType.ONE_HANDED_WEAPONS, ItemType.TWO_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS)
        for (slot in singleSlots) {
            val items = pool[slot].orEmpty()
            f = f.applyStage(listOf(Opt(0L, 0)) + items.map(::itemOpt))
            if (System.getenv("WAKFU_MM_M3_DEBUG") == "1") println("MM_M3_STAGE $slot: ${f.debugSummary()}")
        }

        // Rings: two distinct-name rings (or one, or none).
        run {
            val rings = pool[ItemType.RING].orEmpty().map { it to itemOpt(it) }
            val options = mutableListOf(Opt(0L, 0))
            rings.forEach { (_, o) -> options += o }
            for (i in rings.indices) {
                for (j in i + 1 until rings.size) {
                    val (ei, oi) = rings[i]
                    val (ej, oj) = rings[j]
                    if (ei.name.fr == ej.name.fr) continue
                    if (oi.epic && oj.epic) continue
                    if (oi.relic && oj.relic) continue
                    options += Opt(oi.m + oj.m, oi.d + oj.d, epic = oi.epic || oj.epic, relic = oi.relic || oj.relic)
                }
            }
            f = f.applyStage(options)
            if (System.getenv("WAKFU_MM_M3_DEBUG") == "1") println("MM_M3_STAGE rings: ${f.debugSummary()}")
        }

        // Weapons: a 2H alone, or 1H (+ optional off-hand), or off-hand alone, or nothing.
        run {
            val twoH = pool[ItemType.TWO_HANDED_WEAPONS].orEmpty().map(::itemOpt)
            val oneH = pool[ItemType.ONE_HANDED_WEAPONS].orEmpty().map(::itemOpt)
            val off = pool[ItemType.OFF_HAND_WEAPONS].orEmpty().map(::itemOpt)
            val options = mutableListOf(Opt(0L, 0))
            options += twoH
            options += oneH
            options += off
            for (a in oneH) {
                for (b in off) {
                    if (a.epic && b.epic) continue
                    if (a.relic && b.relic) continue
                    options += Opt(a.m + b.m, a.d + b.d, epic = a.epic || b.epic, relic = a.relic || b.relic)
                }
            }
            f = f.applyStage(options)
            if (System.getenv("WAKFU_MM_M3_DEBUG") == "1") println("MM_M3_STAGE weapons: ${f.debugSummary()}")
        }

        // Skills: EXACT per-branch enumeration. Each branch has at most one objective-relevant
        // m-skill (a requested FIXED mastery) and one d-skill (Damage Inflicted); enumerate every
        // split of the branch budget between them (all other skills contribute nothing to the
        // objective). This is the true per-branch (m, d) option set, not an over-count.
        run {
            val skills = params.character.characterSkills
            for (branch in listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major)) {
                val budget = branch.maxPointsToAssign
                val mSkill = branch.getCharacteristics().filter { it.characteristic in requested }
                val dSkill = branch.getCharacteristics().filter { it.characteristic == Characteristic.DAMAGE_INFLICTED }
                if (mSkill.isEmpty() && dSkill.isEmpty()) continue
                val mBest = mSkill.maxByOrNull { it.unitValue }
                val dBest = dSkill.maxByOrNull { it.unitValue }
                val options = mutableListOf<Opt>()
                for (mPts in 0..minOf(budget, mBest?.maxPointsAssignable ?: 0)) {
                    val dPts = minOf(budget - mPts, dBest?.maxPointsAssignable ?: 0)
                    options +=
                        Opt(
                            (mBest?.unitValue ?: 0).toLong() * mPts,
                            (dBest?.unitValue ?: 0) * dPts
                        )
                }
                f = f.applyStage(options)
                if (System.getenv("WAKFU_MM_M3_DEBUG") == "1") println("MM_M3_STAGE skills-${branch.javaClass.simpleName}: ${f.debugSummary()}")
            }
        }

        // Sublimations: knapsack over the choosable set under the 10-normal/1-epic/1-relic caps
        // (carriers/sockets/binding/conditions ignored — over-count). Copies of a cumulable sub are
        // unit options repeated maxCopies times (each copy full value).
        // Subs whose condition CAPS the objective itself: `Σ secondary masteries ≤ t` bounds M whole
        // (every v1-requested mastery is one of the six secondaries), and `crit mastery ≤ t` bounds it
        // when MASTERY_CRITICAL is requested. A build carrying such a sub therefore scores
        // ≤ (t × maxDiFactor) — world B below. World A excludes them. bound = max(A, B): sound, and
        // world B is negligible for the real subs (Neutrality-family thresholds are ~0).
        var objectiveCapT = -1L
        var dAllForWorldB = 0

        // Sound reachable maximum of a build stat: base + best positive item line per slot + best
        // skill spend + every positive choosable-sub line. Independent per-layer maxima, so ≥ any
        // real build's value — used to price perStatStep ramps tighter than their cap.
        fun reachableMax(stat: Characteristic): Int {
            var v = params.character.baseCharacteristicValues[stat] ?: 0
            for ((_, items) in pool) v += items.maxOfOrNull { maxOf(it.characteristics[stat] ?: 0, 0) } ?: 0
            val skills = params.character.characterSkills
            for (branch in listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major)) {
                v += branch
                    .getCharacteristics()
                    .filter { it.characteristic == stat }
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

        if (params.useSublimations) {
            data class SubOpt(
                val m: Long,
                val d: Int,
                val rarity: SublimationRarity,
            )
            val subOpts = mutableListOf<SubOpt>()
            for (sub in sublimations) {
                if (!sub.solverChoosable) continue
                // Elemental Concentration is choosable ONLY in single-element max-damage — the solver
                // excludes it here (SublimationModelBuilder), so the bound must too (exact, not lenient).
                if (sub.bestElementConcentration != null) continue
                val cond = sub.condition
                val capsObjective =
                    cond != null &&
                        (
                            cond.type == me.chosante.common.SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ||
                                (cond.type == me.chosante.common.SublimationConditionType.CRITICAL_MASTERY_AT_MOST && Characteristic.MASTERY_CRITICAL in requested)
                        )
                if (capsObjective) {
                    objectiveCapT = maxOf(objectiveCapT, (cond?.value ?: 0).toLong())
                    continue
                }
                var m = 0L
                var d = 0
                for (eff in sub.effects) {
                    when (eff) {
                        is SublimationEffect.StatEffect -> {
                            // EXACTLY the solver's fold predicate (SublimationModelBuilder): a gated effect
                            // the scenario/mode excludes contributes nothing to the model, so it must not
                            // inflate the bound either.
                            if (!WakfuBuildSolver.scenarioGateMatches(eff.scenarioGate, params)) continue
                            val value = eff.magnitudeAtLevel(level)
                            if (value <= 0) continue
                            if (eff.characteristic in requested) m += value
                            if (eff.characteristic == Characteristic.DAMAGE_INFLICTED) d += value
                        }
                        // Ramp (perStatStep, e.g. Poids Plume "+6 DI per MP above 4"): credit the
                        // contribution at the SOUND REACHABLE MAX of the source stat (≤ cap) — every
                        // real build's source value is ≤ that, so this over-counts, never under-counts.
                        is SublimationEffect.PerStatStep -> {
                            val credit = eff.contribution(reachableMax(eff.source))
                            if (eff.target in requested) m += credit
                            if (eff.target == Characteristic.DAMAGE_INFLICTED) d += credit
                        }
                        // Unreachable here: EC subs are skipped above (solver-excluded in this mode).
                        is SublimationEffect.BestElementConcentration -> {}
                        // A conversion INTO a requested mastery would need real modeling — none exists in
                        // the current catalog; bail if one appears rather than under-count.
                        is SublimationEffect.Conversion -> if (eff.to in requested) return null
                        else -> {}
                    }
                }
                if (m == 0L && d == 0) continue
                if (System.getenv("WAKFU_MM_M3_DEBUG") == "1") {
                    println("MM_M3_SUB ${sub.name.fr} rarity=${sub.rarity} m=$m d=$d copies=${sub.maxCopies} cond=${sub.condition?.type}")
                }
                repeat(sub.maxCopies.coerceAtLeast(1)) { subOpts += SubOpt(m, d, sub.rarity) }
            }

            // Small DP: for each rarity bucket take the cap's best by scanning options sorted by a
            // dominance-safe pass — here we do it exactly with a (count ≤ cap) frontier per bucket.
            fun bucketOptions(
                rarity: SublimationRarity,
                cap: Int,
            ): List<Opt> {
                val opts = subOpts.filter { it.rarity == rarity }
                if (opts.isEmpty()) return listOf(Opt(0L, 0))
                // frontier over (count, d) → max m
                var states = mutableMapOf(Pair(0, 0) to 0L)
                for (o in opts) {
                    val next = HashMap(states)
                    for ((key, mv) in states) {
                        val (cnt, d) = key
                        if (cnt >= cap) continue
                        val nk = Pair(cnt + 1, (d + o.d).coerceAtMost(diCap))
                        val nm = mv + o.m
                        if (nm > (next[nk] ?: Long.MIN_VALUE)) next[nk] = nm
                    }
                    states = next
                }
                // Epic/relic SUBS are carrier-bound: only usable when an epic/relic ITEM is equipped.
                return states.map { (k, mv) ->
                    Opt(
                        mv,
                        k.second,
                        requiresEpicItem = rarity == SublimationRarity.EPIC && k.first > 0,
                        requiresRelicItem = rarity == SublimationRarity.RELIC && k.first > 0
                    )
                }
            }
            f = f.applyStage(bucketOptions(SublimationRarity.NORMAL, 10))
            f = f.applyStage(bucketOptions(SublimationRarity.EPIC, 1))
            f = f.applyStage(bucketOptions(SublimationRarity.RELIC, 1))
            if (System.getenv("WAKFU_MM_M3_DEBUG") == "1") println("MM_M3_STAGE subs: ${f.debugSummary()}")
        }

        // Collapse: max over states of the scorer's fold (integer division mirrors the scorer).
        var best = 0L
        var dMaxReached = 0
        f.forEachReachable { d, _, _, mv ->
            val di = d.coerceAtMost(diCap)
            if (d > dMaxReached) dMaxReached = d
            val score = (maxOf(mv, 0L) * (100L + di)) / 100L
            if (score > best) best = score
        }
        dAllForWorldB = dMaxReached
        // World B: builds carrying an objective-capping sub — their M is ≤ the largest threshold, their
        // DI at most everything world A can reach plus the capped subs' own DI (over-count the latter
        // with a flat +100, far above any real sub's DI). Almost always ≪ world A.
        if (objectiveCapT >= 0) {
            val worldB = (objectiveCapT * (100L + minOf((dAllForWorldB + 100).toLong(), DAMAGE_DI_MAX))) / 100L
            if (worldB > best) best = worldB
        }
        return best.coerceAtMost(MASTERY_SCORE_ABS_MAX)
    }
}
