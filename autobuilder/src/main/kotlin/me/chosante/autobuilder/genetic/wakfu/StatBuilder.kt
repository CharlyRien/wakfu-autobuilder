package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpModel
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ELEMENTARY_MASTERIES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ELEMENTARY_RESISTANCES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.MASTERY_RANDOM_BY_COUNT
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.NEGATIVE_MASTERY_PENALTY
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.NON_ELEMENTARY_MASTERIES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.RESISTANCE_RANDOM_BY_COUNT
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.buildSkillTerms
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.clampVar
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.mulRange
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.resolvedPassives
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.scaledWeight
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.skillVariableCaps
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.sumVar
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.valueFor
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.Sublimation
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.SkillCharacteristic
import kotlin.math.min

// StatBuilder + DomainTracker extracted from WakfuBuildSolver.kt (B1 of docs/code-review-followups.md).
// They stay behavior-identical; object-level members they use are now `internal`.

internal class DomainTracker(
    val tight: Boolean,
) {
    private val ranges = LinkedHashMap<IntVar, LongRange>()
    private val names = HashMap<IntVar, String>()

    /** Seed a leaf var (equip/skill/rune/sub bool or a constant) with its exact domain. */
    fun seed(
        v: IntVar,
        range: LongRange,
        name: String,
    ): IntVar {
        ranges[v] = range
        names[v] = name
        return v
    }

    /** Record a derived var's reachable range (used by downstream [of] lookups and the soundness test). */
    fun record(
        v: IntVar,
        range: LongRange,
        name: String,
    ): IntVar {
        ranges[v] = range
        names[v] = name
        return v
    }

    /**
     * Reachable range of [v]. Untracked vars fall back to the stat-level guard `±STAT_ABS_MAX`, which is
     * a sound over-estimate for every var that can appear as a stat-sum term (item/rune/skill/sub values
     * are all within it); product operands on the damage chain are always explicitly tracked, so the
     * fallback never under-bounds a large intermediate.
     */
    fun of(v: IntVar): LongRange = ranges[v] ?: (-STAT_ABS_MAX..STAT_ABS_MAX)

    /** The `[lo, hi]` to actually declare a var with: the reachable range (clamped into the guard) when [tight], else the guard. */
    fun decl(
        reach: LongRange,
        guardLo: Long,
        guardHi: Long,
    ): Pair<Long, Long> =
        if (tight) {
            reach.first.coerceIn(guardLo, guardHi) to reach.last.coerceIn(guardLo, guardHi)
        } else {
            guardLo to guardHi
        }

    /** Every tracked var with its name and reachable range — read back by the soundness test. */
    fun tracked(): List<Triple<IntVar, String, LongRange>> = ranges.entries.map { Triple(it.key, names[it.key] ?: "?", it.value) }
}

internal class StatBuilder(
    internal val model: CpModel,
    internal val params: WakfuBestBuildParams,
    internal val allEquips: List<Equipment>,
    internal val equipVars: Map<Equipment, IntVar>,
    internal val skillVars: Map<SkillCharacteristic, IntVar>,
    internal val runeModel: RuneModel,
    internal val subModel: SublimationModel,
    // Reachable-domain tracking for the max-damage objective chain. [tight] = true (max-damage only)
    // declares each chain var sized to its reachable range; false reproduces the loose guard domains
    // (every other mode, and the soundness-test reference). See [DomainTracker].
    tight: Boolean = false,
    internal val maxDamageExperiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig.DEFAULT,
    // Test seam: when true, [perTurnDamageScore] certifies the exact per-AP-cell max objective for every
    // AP into [certifierObjectivesForTest] (single-element only). See [certifyMaxPerHitAtAp].
    internal val certifyForTest: Boolean = false,
    // Test seam: PROVENANCE — explain this AP cell's winning certificate state (single-element only).
    internal val certifyExplainCell: Int? = null,
    // E8 item A (perf): when set alongside [certifyExplainCell], the explain SKIPS the N-worlds scan and replays
    // only this cached winning (world, crit-step) — the E8 fast-path's cheap provenance path. Null ⇒ full scan.
    internal val certifyExplainProvenance: CellProvenance? = null,
    // Test seam: thread count for the FAST pass (P3.1 warm-once parallelism). 1 = serial (default, so the
    // existing exact/fast audits stay byte-identical). Only meaningful with [certifyForTest].
    internal val certifyFastThreads: Int = 1,
    // DYNAMIC thread count for the certificate tiers: when set, [certifyLedger] re-reads it at the START of
    // each tier (tier-1 fast, tier-1.5, exact) instead of using the fixed [certifyFastThreads]. This exists
    // for the search-overlapped certificate warm-up: it starts at 1 thread so it never competes with the
    // search's CP-SAT workers, but the search (~60 s) ends long before tier-1.5 (the certificate's dominant
    // stage, minutes-to-hours) even starts — so the provider lets the warm-up scale to the full worker count
    // the moment the search is done. Thread count changes no bound value (the merges are order-independent
    // maxes; parallel determinism is locked by the panel tests), so this needs no CERTIFIER_VERSION bump.
    internal val certifierThreadsProvider: ((CertTier) -> Int)? = null,
    // DYNAMIC incumbent for the certificate's elimination/skip thresholds: when set, [certifyLedger]
    // resolves it ONCE, right before elimination (i.e. after the tier-1 fast DP). This exists for the
    // search-overlapped warm-up, which is LAUNCHED on the first streamed result (a weak early incumbent)
    // while elimination only happens minutes later — by then the search has finished and its FINAL, much
    // stronger incumbent is known, eliminating more cells and skipping more tier-1.5 segments. Sound for
    // any feasible incumbent (a stronger one only prunes more); the cached raw bounds are
    // incumbent-independent, so this changes no certified value and needs no CERTIFIER_VERSION bump.
    internal val certifierIncumbentProvider: (() -> Long?)? = null,
    // CASCADE tier-1.5 (the short-search rescue): when true, the incumbent path confirms survivors ONE
    // cell at a time (descending fast bound) — tier-1.5, then exact if not cleared — and STOPS at the
    // first cell whose EXACT value exceeds the incumbent (the B1 break, moved up a tier). Unprocessed
    // cells keep their (sound, looser) FAST bounds — the ledger's documented mixed-tier semantics — so
    // the caller can E8-CONSTRUCT the confirmed argmax build and either finish proven or fall back to a
    // full (cascade-off) ledger. Never set on the oracle/forceTier2All paths.
    internal val certifyLedgerCascadeTier15: Boolean = false,
    // Test seam: when true, the [certifyForTest] block runs ONLY the fast pass (skips the expensive exact
    // per-cell ledger) — used by the P3.1 parallel-equality lock, which reads only the fast map.
    internal val certifyFastOnly: Boolean = false,
    // Test seam (P3.2 orchestrator): when true, compute the two-tier [CertLedger] into
    // [certifierLedgerForTest]. [certifyLedgerIncumbent] is the feasible objective for elimination (null =
    // no elimination); [certifyLedgerForceTier2All] confirms every non-bailed cell exactly.
    internal val certifyLedgerForTest: Boolean = false,
    internal val certifyLedgerIncumbent: Long? = null,
    internal val certifyLedgerForceTier2All: Boolean = false,
    // B6: SCALED per-cell fast bounds (+ shape bail set) from a prior compute of this shape; when set,
    // [certifyLedger] skips the tier-1 fast DP and reuses them (a pure, byte-identical function of the shape).
    internal val certifyLedgerPrecomputedFast: Map<Int, Long>? = null,
    internal val certifyLedgerPrecomputedBailed: Set<Int>? = null,
    // B4/B7 reuse on the COMPUTE path (not just reconstruct): per-cell tier-1.5 / exact OBJECTIVE values
    // (+ provenance) confirmed by a PRIOR compute of this shape. A cell decided by a cached value skips
    // its DP entirely — without this, every certificate call after a cascaded (partial) entry re-paid the
    // cascade's tier-1.5 work (measured: 3 × ~44 s on one short-search CLI run).
    internal val certifyLedgerPrecomputedTier15: Map<Int, Long>? = null,
    internal val certifyLedgerPrecomputedExact: Map<Int, Long>? = null,
    internal val certifyLedgerPrecomputedProv: Map<Int, CellProvenance>? = null,
    // B8 cooperative cancellation: the certifier's exact/fast DP polls this once per DP stage; when it flips
    // true the stage bails ([certifyMaxPerHitAtApPass] returns Long.MAX_VALUE — always a sound over-count), so a
    // cancelled proof (the user restarted / closed the search) stops within a stage instead of running the whole
    // ~minutes-per-cell pass to completion. Default never-cancel keeps every existing caller byte-identical.
    internal val certifierCancelled: () -> Boolean = { false },
) {
    /**
     * Seeds the cumulable-sub COPY vars as the plain booleans they are. They are minted inside
     * `createSublimationModel`, whose receiver is the raw [CpModel] — no tracker in scope — so nothing else
     * registers them, and [DomainTracker.of] falls back to `±STAT_ABS_MAX` for an UNTRACKED var. That fallback
     * turned every copy var's term into `coefficient · (±1e7)` when [reachableSumDomain] sized the objective-chain
     * domains: at level 245 with a BACK scenario (whose extra positional multiplier pushes the chain furthest) the
     * blown-up boxes made the whole max-damage model **INFEASIBLE** — the CLI/GUI answered "no build found" for
     * every subs-on, back-facing end-game request. Seeding them `0..1` restores exact interval arithmetic.
     */
    val tracker =
        DomainTracker(tight).also { domains ->
            for ((sub, copies) in subModel.copyVars) {
                copies.forEachIndexed { index, copy -> domains.seed(copy, 0L..1L, "subCopy_${sub.stateId}_${index + 1}") }
            }
        }

    // Test seam (see [certifyForTest]): AP cell → certifier objective, or -1 where the certifier bails.
    val certifierObjectivesForTest = linkedMapOf<Int, Long>()

    // Test seam (see [certifyForTest]): the FAST tier-1 pass's AP cell → objective (sound upper bound,
    // -1 where it bails). Compared against [certifierObjectivesForTest] by the `fast ≥ exact` lock. The
    // fast pass computes every cell in one shared DP; here it is still built cell-by-cell (P2 in progress).
    val certifierFastObjectivesForTest = linkedMapOf<Int, Long>()

    // Test seam (B7, see [certifyForTest]): the TIER-1.5 sharpened fast pass's AP cell → objective (sound upper
    // bound, -1 where it bails). The `fast ≥ tier1.5 ≥ exact` lock asserts it sits between the two.
    val certifierTier15ObjectivesForTest = linkedMapOf<Int, Long>()

    // Test seam (see [certifyLedgerForTest]): the two-tier certificate ledger (P3.2 orchestrator).
    var certifierLedgerForTest: CertLedger? = null

    // Test seam (see [certifyExplainCell]): the backtracked composition of the winning certificate state.
    val certifierExplainForTest = mutableListOf<String>()

    // E8 item A: the STRUCTURED counterpart of [certifierExplainForTest] — the winning composition's equipmentIds,
    // so the fast-path recovers the items as typed ids instead of regex-parsing the `slot:` strings.
    val certifierExplainItemIds = mutableListOf<Int>()

    // Test seam (C7): the crit·diff AM-GM bound actually ADDED as a constraint by [perHitDamageScore]
    // (null = the cut did not fire — flag off, bail, or self-disabled). The firing fixture asserts non-null.
    var critDiffJointCutBoundForTest: Long? = null

    private val baseValues = params.character.baseCharacteristicValues
    internal val skillTerms = buildSkillTerms(skillVars)
    private val skillCaps = skillVariableCaps(params.character.characterSkills)

    // Reverse lookup of item-gated vars (equipment picks and their rune vars) → carrier item, so a stat
    // sum's reachable bound can be computed PER mutually-exclusive slot (one item per slot, two rings)
    // instead of summing every candidate. Runes need this too: a rune var can only fire when its carrier
    // item is equipped, so counting every socketable candidate at once recreates the old huge domains.
    internal val carrierByVar: Map<IntVar, Equipment> =
        buildMap {
            equipVars.forEach { (equip, v) -> put(v, equip) }
            runeModel.runeVars.forEach { (equip, perStat) ->
                perStat.values.forEach { put(it, equip) }
            }
        }
    internal val subByVar: Map<IntVar, Sublimation> = subModel.subVars.entries.associate { (sub, v) -> v to sub }

    init {
        // Seed every leaf variable's exact domain so the interval arithmetic can propagate from them.
        equipVars.forEach { (equip, v) -> tracker.seed(v, 0L..1L, "equip_${equip.equipmentId}") }
        skillVars.forEach { (skill, v) -> tracker.seed(v, 0L..skillCaps.getValue(skill), "skill_${skill.name}") }
        runeModel.runeVars.forEach { (equip, perStat) ->
            // Fold ⇒ each var is a boolean PICK (0..1, contributes slots·coeff); count model ⇒ 0..slots.
            val runeHi = if (runeModel.singleTypePerItem) 1L else equip.maxShardSlots.toLong()
            perStat.forEach { (stat, v) -> tracker.seed(v, 0L..runeHi, "rune_${equip.equipmentId}_${stat.name}") }
        }
        subModel.subVars.forEach { (sub, v) -> tracker.seed(v, 0L..1L, "sub_${sub.stateId}") }
    }

    // ---- Domain-tracked variable builders (the max-damage objective chain) ----------------------
    // Each mirrors a plain CpModel helper but declares the new var from its inputs' reachable ranges
    // (via [DomainTracker]) and records its own range for downstream propagation. With tracker.tight =
    // false they declare the exact same guard-sized domains as the untracked helpers, so non-max-damage
    // objectives are unchanged.

    /** A constant, seeded as the singleton range `v..v`. */
    private fun tConst(v: Long): IntVar = tracker.seed(model.newConstant(v), v..v, "const_$v")

    /** Reachable range of a stat sum `constant + Σ coeff·var`, **per mutually-exclusive slot** for item terms. */
    internal fun reachableSumDomain(
        terms: List<Term>,
        constant: Long,
    ): LongRange {
        // Aggregate coefficients per variable before folding. A conversion sublimation's "moved" var appears in two
        // concatenated term lists with OPPOSITE signs (+w via subTermsByStat[to], −w via subTermsByStat[from]);
        // folding each term independently banks +w·hi from one and 0 from the other — double-counting the moved
        // mastery and inflating the declared box (sound but loose, which blocks sub-heavy proofs). Summing
        // coefficients first is exact interval arithmetic — `Σ cᵢ·v = (Σ cᵢ)·v` pointwise, so the reachable set is
        // unchanged and only the declared box tightens — and a no-op for the common single-occurrence variable. (C1)
        val terms = terms.groupBy { it.variable }.map { (v, ts) -> Term(v, ts.sumOf { it.coefficient }) }
        var lo = constant
        var nonCarrierHi = constant
        // Item-gated terms: one item per slot (two per RING). Aggregate all terms for the same carrier
        // first (e.g. the item's own stat plus its rune contribution), then bound each itemType by the
        // best carrier range instead of summing every candidate. This is a sound over-estimate (it even
        // allows 2H+1H+off-hand together, which the validity rules forbid) and keeps rune-heavy stats on
        // real build-sized domains.
        val rangesByCarrier = LinkedHashMap<Equipment, LongRange>()
        val rangesBySub = LinkedHashMap<Sublimation, LongRange>()
        for (term in terms) {
            val equip = carrierByVar[term.variable]
            if (equip != null) {
                val d = tracker.of(term.variable)
                val scaled = term.scaledRange(d)
                val current = rangesByCarrier[equip] ?: (0L..0L)
                rangesByCarrier[equip] = current.first + scaled.first..current.last + scaled.last
            } else {
                val sub = subByVar[term.variable]
                val d = tracker.of(term.variable)
                val scaled = term.scaledRange(d)
                if (sub != null) {
                    val current = rangesBySub[sub] ?: (0L..0L)
                    rangesBySub[sub] = current.first + scaled.first..current.last + scaled.last
                } else {
                    lo += scaled.first
                    nonCarrierHi += scaled.last
                }
            }
        }
        val rangesByType =
            rangesByCarrier
                .entries
                .groupBy({ it.key.itemType }, { it.key to it.value })
        for ((type, ranges) in rangesByType) {
            val limit = if (type == ItemType.RING) 2L else 1L
            lo += limit * minOf(0L, ranges.minOf { it.second.first })
        }
        for (range in rangesBySub.values) {
            lo += minOf(0L, range.first)
        }
        val hi = nonCarrierHi + rarityAwareUpper(rangesByType) + sublimationAwareUpper(rangesBySub)
        return lo..hi
    }

    private fun sublimationAwareUpper(rangesBySub: Map<Sublimation, LongRange>): Long {
        data class State(
            val count: Int,
            val epic: Int,
            val relic: Int,
        )

        fun rarityCounts(sub: Sublimation): Pair<Int, Int> =
            when (sub.rarity) {
                SublimationRarity.EPIC -> 1 to 0
                SublimationRarity.RELIC -> 0 to 1
                else -> 0 to 0
            }

        var dp = mapOf(State(0, 0, 0) to 0L)
        // NOTE on stacking: a cumulable sub's EXTRA copies enter the domain via their own copy vars, which are not
        // in `subByVar`, so their terms fold into `nonCarrierHi` (an unconditional add) at the caller — a sound
        // over-estimate of the extra copies, since it ignores the ≤10-normal cap. It is only sound because those
        // copy vars are SEEDED `0..1` (see [tracker]); untracked they defaulted to ±STAT_ABS_MAX and this ceiling
        // exploded by ~1e7×. Here we bound only the BASE sub var per sub (one slot each), cap-aware; summed with
        // nonCarrierHi the ceiling stays ≥ the stacked-achievable value.
        for ((sub, range) in rangesBySub) {
            val value = range.last
            if (value <= 0L) continue
            val (epicCost, relicCost) = rarityCounts(sub)
            val next = dp.toMutableMap()
            for ((state, current) in dp) {
                val count = state.count + 1
                val epic = state.epic + epicCost
                val relic = state.relic + relicCost
                if (count - epic - relic <= MAX_NORMAL_SUBLIMATIONS.toInt() && epic <= 1 && relic <= 1) {
                    val candidate = State(count, epic, relic)
                    next[candidate] = maxOf(next[candidate] ?: Long.MIN_VALUE, current + value)
                }
            }
            dp = next
        }
        return dp.values.maxOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    /**
     * Upper bound for item-gated terms that respects the global ≤1 EPIC / ≤1 RELIC build budgets. The
     * equality constraints still enforce exact item choices; this only tightens the declared reachable
     * domain used by product big-Ms. Slot feasibility stays an over-estimate (notably weapon combinations
     * and same-name rings), but we stop granting every slot its own epic/relic fantasy item.
     */
    private fun rarityAwareUpper(rangesByType: Map<ItemType, List<Pair<Equipment, LongRange>>>): Long {
        data class Option(
            val value: Long,
            val epic: Int,
            val relic: Int,
        )

        fun counts(equip: Equipment): Pair<Int, Int> =
            when (equip.rarity) {
                Rarity.EPIC -> 1 to 0
                Rarity.RELIC -> 0 to 1
                else -> 0 to 0
            }

        fun topByRarity(entries: List<Pair<Equipment, LongRange>>): List<Option> {
            val best = HashMap<Pair<Int, Int>, Option>()
            for ((equip, range) in entries) {
                val value = range.last
                if (value <= 0L) continue
                val (epic, relic) = counts(equip)
                val key = epic to relic
                if (value > (best[key]?.value ?: Long.MIN_VALUE)) best[key] = Option(value, epic, relic)
            }
            return listOf(Option(0L, 0, 0)) + best.values
        }

        fun ringOptions(entries: List<Pair<Equipment, LongRange>>): List<Option> {
            val rings = entries.mapNotNull { (equip, range) -> range.last.takeIf { it > 0L }?.let { equip to it } }
            // sameNameRingBound: enumerate every valid ring configuration EXACTLY — 0 rings, 1 ring, or a pair of
            // DISTINCT-name rings within the ≤1-epic / ≤1-relic budget. Rings per slot are few, so O(rings²) is
            // cheap. The old `take(2)`-by-value pruning was UNSOUND here: when the top-2 by value share a name (a
            // same-name pair the exclusion then drops), it left the best distinct-name partner out of the pool and
            // UNDER-declared the ring domain below a real pair (cut the proven optimum — the negative-AP Twin test).
            if (maxDamageExperiment.sameNameRingBound) {
                val options = mutableListOf(Option(0L, 0, 0))
                for ((equip, value) in rings) {
                    val (epic, relic) = counts(equip)
                    options.add(Option(value, epic, relic))
                }
                for (i in rings.indices) {
                    val (e1, v1) = rings[i]
                    val (epic1, relic1) = counts(e1)
                    for (j in i + 1 until rings.size) {
                        val (e2, v2) = rings[j]
                        if (e1.name.fr.equals(e2.name.fr, ignoreCase = true)) continue
                        val (epic2, relic2) = counts(e2)
                        if (epic1 + epic2 <= 1 && relic1 + relic2 <= 1) options.add(Option(v1 + v2, epic1 + epic2, relic1 + relic2))
                    }
                }
                return options
            }

            val byRarity = HashMap<Pair<Int, Int>, MutableList<Pair<Equipment, Long>>>()
            for ((equip, value) in rings) {
                byRarity.getOrPut(counts(equip)) { mutableListOf() }.add(equip to value)
            }
            val top =
                byRarity.mapValues { (_, values) ->
                    values
                        .sortedByDescending { it.second }
                        .take(2)
                }
            val options = mutableListOf(Option(0L, 0, 0))
            for ((state, values) in top) {
                values.firstOrNull()?.let { options.add(Option(it.second, state.first, state.second)) }
            }

            fun addPair(
                leftState: Pair<Int, Int>,
                rightState: Pair<Int, Int>,
            ) {
                val left = top[leftState].orEmpty()
                val right = top[rightState].orEmpty()
                for ((leftEquip, leftValue) in left) {
                    for ((rightEquip, rightValue) in right) {
                        if (leftEquip == rightEquip) continue
                        val epic = leftState.first + rightState.first
                        val relic = leftState.second + rightState.second
                        if (epic <= 1 && relic <= 1) options.add(Option(leftValue + rightValue, epic, relic))
                    }
                }
            }

            val normal = 0 to 0
            val epic = 1 to 0
            val relic = 0 to 1
            addPair(normal, normal)
            addPair(normal, epic)
            addPair(normal, relic)
            addPair(epic, relic)
            return options
        }

        fun combineOptions(
            left: List<Option>,
            right: List<Option>,
        ): List<Option> {
            val options = mutableListOf<Option>()
            for (a in left) {
                for (b in right) {
                    val epic = a.epic + b.epic
                    val relic = a.relic + b.relic
                    if (epic <= 1 && relic <= 1) options.add(Option(a.value + b.value, epic, relic))
                }
            }
            return options
        }

        fun weaponOptions(): List<Option> {
            val oneHanded = topByRarity(rangesByType[ItemType.ONE_HANDED_WEAPONS].orEmpty())
            val offHand = topByRarity(rangesByType[ItemType.OFF_HAND_WEAPONS].orEmpty())
            val twoHanded = topByRarity(rangesByType[ItemType.TWO_HANDED_WEAPONS].orEmpty())
            // Wakfu weapon rules: either a 2H weapon, or a 1H/off-hand pair (including either side alone).
            return combineOptions(oneHanded, offHand) + twoHanded
        }

        val weaponTypes = setOf(ItemType.ONE_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS, ItemType.TWO_HANDED_WEAPONS)
        val optionSets =
            buildList {
                for ((type, entries) in rangesByType) {
                    if (type in weaponTypes) continue
                    add(if (type == ItemType.RING) ringOptions(entries) else topByRarity(entries))
                }
                if (weaponTypes.any { it in rangesByType }) add(weaponOptions())
            }

        var dp = Array(2) { LongArray(2) { Long.MIN_VALUE / 4 } }
        dp[0][0] = 0L
        for (options in optionSets) {
            val next = Array(2) { LongArray(2) { Long.MIN_VALUE / 4 } }
            for (usedEpic in 0..1) {
                for (usedRelic in 0..1) {
                    val current = dp[usedEpic][usedRelic]
                    if (current <= Long.MIN_VALUE / 8) continue
                    for (option in options) {
                        val epic = usedEpic + option.epic
                        val relic = usedRelic + option.relic
                        if (epic <= 1 && relic <= 1) next[epic][relic] = maxOf(next[epic][relic], current + option.value)
                    }
                }
            }
            dp = next
        }
        return dp.maxOf { row -> row.maxOrNull() ?: 0L }.coerceAtLeast(0L)
    }

    /** Stat sum with a precomputed reachable [reach]; declares tight-or-guard and records [reach]. */
    internal fun tSum(
        name: String,
        terms: List<Term>,
        constant: Long,
        reach: LongRange,
        guardLo: Long,
        guardHi: Long,
    ): IntVar {
        if (terms.isEmpty()) return tConst(constant)
        val (lo, hi) = tracker.decl(reach, guardLo, guardHi)
        val v = model.newIntVar(lo, hi, name)
        val builder = LinearExpr.newBuilder().add(constant)
        terms.forEach { builder.addTerm(it.variable, it.coefficient) }
        model.addEquality(v, builder.build())
        return tracker.record(v, reach, name)
    }

    /** Stat sum whose reachable range is the naive interval sum of its (already-tracked) terms. */
    internal fun tSumNaive(
        name: String,
        terms: List<Term>,
        constant: Long,
        guardLo: Long,
        guardHi: Long,
    ): IntVar {
        var reach = constant..constant
        for (term in terms) {
            val d = tracker.of(term.variable)
            val scaled = term.scaledRange(d)
            reach = reach.first + scaled.first..reach.last + scaled.last
        }
        return tSum(name, terms, constant, reach, guardLo, guardHi)
    }

    /** clamp(value, low, high): reachable range = the value's range clamped into `[low, high]`. */
    internal fun tClamp(
        value: IntVar,
        low: Long,
        high: Long,
        name: String,
    ): IntVar {
        val v = tracker.of(value)
        val loweredReach = maxOf(low, v.first)..maxOf(low, v.last).coerceAtMost(CLAMP_INTERMEDIATE_MAX)
        val (lLo, lHi) = tracker.decl(loweredReach, low, CLAMP_INTERMEDIATE_MAX)
        val lowered = model.newIntVar(lLo, lHi, "${name}Lo")
        model.addMaxEquality(lowered, arrayOf(value, model.newConstant(low)))
        val clampedReach = v.first.coerceIn(low, high)..v.last.coerceIn(low, high)
        val (cLo, cHi) = tracker.decl(clampedReach, low, high)
        val clamped = model.newIntVar(cLo, cHi, name)
        model.addMinEquality(clamped, arrayOf(lowered, model.newConstant(high)))
        return tracker.record(clamped, clampedReach, name)
    }

    /** z = x·y, declared from the interval product of the operands' reachable ranges. */
    internal fun tMul(
        name: String,
        x: IntVar,
        y: IntVar,
        guardLo: Long,
        guardHi: Long,
    ): IntVar {
        val reach = mulRange(tracker.of(x), tracker.of(y))
        val (lo, hi) = tracker.decl(reach, guardLo, guardHi)
        val z = model.newIntVar(lo, hi, name)
        model.addMultiplicationEquality(z, arrayOf(x, y))
        return tracker.record(z, reach, name)
    }

    /**
     * z = x·b for a boolean [selector]. In tight production builds the big-M constants use [x]'s tracked
     * reachable range; in the loose reference build they use [xGuard] so the soundness probe is not itself
     * constrained by a possibly-underestimated tracked range.
     */
    internal fun tBoolGate(
        name: String,
        x: IntVar,
        selector: IntVar,
        xGuard: LongRange,
    ): IntVar {
        val xReach = tracker.of(x)
        val reach = minOf(0L, xReach.first)..maxOf(0L, xReach.last)
        val guard = minOf(0L, xGuard.first)..maxOf(0L, xGuard.last)
        val (lo, hi) = tracker.decl(reach, guard.first, guard.last)
        val z = model.newIntVar(lo, hi, name)

        val m = if (tracker.tight) xReach else xGuard
        val lower = m.first
        val upper = m.last

        // z <= U·b
        model.addLessOrEqual(
            LinearExpr
                .newBuilder()
                .addTerm(z, 1L)
                .addTerm(selector, -upper)
                .build(),
            0L
        )
        // z >= L·b
        model.addGreaterOrEqual(
            LinearExpr
                .newBuilder()
                .addTerm(z, 1L)
                .addTerm(selector, -lower)
                .build(),
            0L
        )
        // z <= x - L·(1-b)
        model.addLessOrEqual(
            LinearExpr
                .newBuilder()
                .addTerm(z, 1L)
                .addTerm(x, -1L)
                .addTerm(selector, -lower)
                .build(),
            -lower
        )
        // z >= x - U·(1-b)
        model.addGreaterOrEqual(
            LinearExpr
                .newBuilder()
                .addTerm(z, 1L)
                .addTerm(x, -1L)
                .addTerm(selector, -upper)
                .build(),
            -upper
        )

        return tracker.record(z, reach, name)
    }

    /** Creates an exact one-hot encoding of [value] over the inclusive integer range [low, high]. */
    private fun tValueSelectors(
        name: String,
        value: IntVar,
        low: Long,
        high: Long,
    ): List<Pair<Long, IntVar>> {
        require(low <= high) { "empty selector domain for $name: $low..$high" }
        val selectors =
            (low..high).map { v ->
                val selector = model.newBoolVar("${name}_$v")
                tracker.seed(selector, 0L..1L, "${name}_$v")
                v to selector
            }

        model.addEquality(LinearExpr.sum(selectors.map { it.second }.toTypedArray()), 1L)

        val valueExpr = LinearExpr.newBuilder()
        for ((v, selector) in selectors) {
            if (v != 0L) valueExpr.addTerm(selector, v)
        }
        model.addEquality(value, valueExpr.build())
        return selectors
    }

    /**
     * Exact replacement for table[index]·factor. The index is one-hot encoded over the table positions and
     * [factor] is gated behind the selected AP value, avoiding a generic product between an element lookup
     * and the per-hit score.
     */
    internal fun tTableProduct(
        name: String,
        index: IntVar,
        table: LongArray,
        factor: IntVar,
        factorGuard: LongRange,
        productGuard: LongRange,
    ): IntVar {
        val selectors = tValueSelectors("${name}_idx", index, 0L, (table.size - 1).toLong())
        val factorReach = tracker.of(factor)
        val values = table.flatMap { throughput -> listOf(throughput * factorReach.first, throughput * factorReach.last) }
        val reach = values.minOrNull()!!..values.maxOrNull()!!
        val terms = mutableListOf<Term>()

        selectors.forEach { (indexValue, selector) ->
            val throughput = table[indexValue.toInt()]
            if (throughput == 0L) return@forEach
            val gated = tBoolGate("${name}_gate_$indexValue", factor, selector, factorGuard)
            terms.add(Term(gated, throughput))
        }

        return tSum(name, terms, 0L, reach, productGuard.first, productGuard.last)
    }

    /** Exact replacement for `(offset + valueRange.first) * factor` using a binary expansion of [offset]. */
    internal fun tBinaryOffsetProduct(
        name: String,
        offset: IntVar,
        valueRange: LongRange,
        factor: IntVar,
        factorGuard: LongRange,
        productGuard: LongRange,
    ): IntVar {
        val span = valueRange.last - valueRange.first
        require(span >= 0L) { "empty binary product range for $name: $valueRange" }
        val factorReach = tracker.of(factor)
        val reach = mulRange(valueRange, factorReach)
        if (span == 0L) {
            return tSum(name, listOf(Term(factor, valueRange.first)), 0L, reach, productGuard.first, productGuard.last)
        }

        val bits = mutableListOf<Pair<Long, IntVar>>()
        var weight = 1L
        while (weight <= span) {
            val bit = model.newBoolVar("${name}_bit_$weight")
            tracker.seed(bit, 0L..1L, "${name}_bit_$weight")
            bits.add(weight to bit)
            if (weight > Long.MAX_VALUE / 2L) break
            weight *= 2L
        }

        val offsetExpr = LinearExpr.newBuilder()
        bits.forEach { (bitWeight, bit) -> offsetExpr.addTerm(bit, bitWeight) }
        model.addEquality(offset, offsetExpr.build())

        val terms = mutableListOf<Term>()
        if (valueRange.first != 0L) terms.add(Term(factor, valueRange.first))
        bits.forEach { (bitWeight, bit) ->
            val gated = tBoolGate("${name}_gate_$bitWeight", factor, bit, factorGuard)
            terms.add(Term(gated, bitWeight))
        }
        return tSum(name, terms, 0L, reach, productGuard.first, productGuard.last)
    }

    /**
     * Exact source expansion for `(100 + DI) * factor`, when DI is not clamped and every variable DI source
     * is boolean. Returns null when that proof obligation is not met, so callers can fall back to a generic
     * exact product encoding.
     */
    internal fun tSourceExpandedDamageInflictedProduct(
        name: String,
        factor: IntVar,
        factorGuard: LongRange,
        productGuard: LongRange,
    ): IntVar? {
        if (skillTerms.percent[Characteristic.DAMAGE_INFLICTED].orEmpty().isNotEmpty()) return null
        val (diTerms, diBase) = prePercentTermsFor(Characteristic.DAMAGE_INFLICTED)
        val diReach = reachableSumDomain(diTerms, diBase)
        if (diReach.first < -DAMAGE_DI_FLOOR || diReach.last > DAMAGE_DI_MAX) return null

        var constant = 100L + diBase
        val terms = mutableListOf<Term>()
        for ((index, term) in diTerms.withIndex()) {
            val d = tracker.of(term.variable)
            if (d.first == d.last) {
                constant += d.first * term.coefficient
                continue
            }
            if (d.first != 0L || d.last != 1L) return null
            val gated = tBoolGate("${name}_diSrc_$index", factor, term.variable, factorGuard)
            terms.add(Term(gated, term.coefficient))
        }
        if (constant != 0L) terms.add(Term(factor, constant))

        val factorReach = tracker.of(factor)
        val reach = mulRange(100L + diReach.first..100L + diReach.last, factorReach)
        return tSum(name, terms, 0L, reach, productGuard.first, productGuard.last)
    }

    /** q = num / divisor (truncated, divisor > 0), declared from num's range divided by [divisor]. */
    internal fun tDiv(
        name: String,
        num: IntVar,
        divisor: Long,
        guardLo: Long,
        guardHi: Long,
    ): IntVar {
        val n = tracker.of(num)
        val reach = n.first / divisor..n.last / divisor // truncation toward zero matches CP-SAT integer division
        val (lo, hi) = tracker.decl(reach, guardLo, guardHi)
        val q = model.newIntVar(lo, hi, name)
        model.addDivisionEquality(q, num, model.newConstant(divisor))
        return tracker.record(q, reach, name)
    }

    /** target = table[index]: reachable range = `[min(table), max(table)]`. */
    private fun tElement(
        name: String,
        index: IntVar,
        table: LongArray,
    ): IntVar {
        val reach = table.min()..table.max()
        val (lo, hi) = tracker.decl(reach, 0L, table.max().coerceAtLeast(0L))
        val target = model.newIntVar(lo, hi, name)
        model.addElement(index, table, target)
        return tracker.record(target, reach, name)
    }

    /** withPercent = value + round(value·percent / 100): mirrors [applyPercent], tightening every var. */
    private fun tPercent(
        value: IntVar,
        percent: IntVar,
        name: String,
    ): IntVar {
        val productReach = mulRange(tracker.of(value), tracker.of(percent))
        val (pLo, pHi) = tracker.decl(productReach, -PRODUCT_ABS_MAX, PRODUCT_ABS_MAX)
        val product = model.newIntVar(pLo, pHi, "${name}_prod")
        model.addMultiplicationEquality(product, arrayOf(value, percent))

        val quotientReach = productReach.first / 100..productReach.last / 100
        val (qLo, qHi) = tracker.decl(quotientReach, -(PRODUCT_ABS_MAX / 100) - 1, (PRODUCT_ABS_MAX / 100) + 1)
        val quotient = model.newIntVar(qLo, qHi, "${name}_quot")
        model.addDivisionEquality(quotient, product, model.newConstant(100L))

        val remainder = model.newIntVar(-99, 99, "${name}_rem")
        model.addModuloEquality(remainder, product, 100L)

        val inc = model.newBoolVar("${name}_inc")
        model.addGreaterOrEqual(remainder, 50).onlyEnforceIf(inc)
        model.addLessOrEqual(remainder, 49).onlyEnforceIf(inc.not())

        val dec = model.newBoolVar("${name}_dec")
        model.addLessOrEqual(remainder, -51).onlyEnforceIf(dec)
        model.addGreaterOrEqual(remainder, -50).onlyEnforceIf(dec.not())

        model.addLessOrEqual(
            LinearExpr
                .newBuilder()
                .addTerm(inc, 1)
                .addTerm(dec, 1)
                .build(),
            1
        )

        val roundedReach = quotientReach.first - 1..quotientReach.last + 1
        val (rLo, rHi) = tracker.decl(roundedReach, -(PRODUCT_ABS_MAX / 100) - 2, (PRODUCT_ABS_MAX / 100) + 2)
        val rounded = model.newIntVar(rLo, rHi, "${name}_rounded")
        model.addEquality(
            rounded,
            LinearExpr
                .newBuilder()
                .addTerm(quotient, 1)
                .addTerm(inc, 1)
                .addTerm(dec, -1)
                .build()
        )

        val withReach = tracker.of(value).first + roundedReach.first..tracker.of(value).last + roundedReach.last
        val (wLo, wHi) = tracker.decl(withReach, -STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX)
        val withPercent = model.newIntVar(wLo, wHi, name)
        model.addEquality(
            withPercent,
            LinearExpr
                .newBuilder()
                .addTerm(value, 1)
                .addTerm(rounded, 1)
                .build()
        )
        return tracker.record(withPercent, withReach, name)
    }

    private val prePercentCache = mutableMapOf<Characteristic, IntVar>()
    private val preSubCache = mutableMapOf<Characteristic, IntVar>()
    private val preCombatCache = mutableMapOf<Characteristic, IntVar>()

    // Per-solve memo for the (scenario-pure) max-damage pre-mastery term list. damagePreMasteryTerms is
    // called ~3× per perHitDamageScore (once via damagePreMastery, twice via damageMasteryCriticalReach)
    // and rebuilds the full per-equipment random-mastery fold each time. The result depends only on the
    // scenario and references already-created vars (it mints none), so caching it is value-identical. The
    // cached LinearTermSum.terms list is read-only at every consumer; do not mutate it in place.
    private val damagePreMasteryTermsCache = mutableMapOf<DamageScenario, LinearTermSum?>()
    private val actualCache = mutableMapOf<Characteristic, IntVar>()
    private val elementCache = mutableMapOf<Pair<Characteristic, List<Characteristic>>, Map<Characteristic, IntVar>>()
    internal val appliesVarCache = mutableMapOf<Sublimation, IntVar>()

    // The PERMANENT (out-of-combat / character-sheet) sublimation contributions — the subset of FLAT-sub
    // effects flagged [SublimationEffect.appliesBeforeCombat]. These are present BEFORE combat starts, so
    // they (and only they) feed build-static start-of-combat conditions via [preCombatStat]: a permanent
    // +crit (Influence II) counts toward another sub's CRIT_AT_MOST, while a start-of-combat / conditional
    // +crit (Secondary Devastation II, Ambition) does not. Gated by the raw subVar (FLAT subs have no
    // condition), so referencing it from [reifyCondition] never recurses through [appliesVar]. Built BEFORE
    // [subTermsByStat] because that map's init reifies conditions, which read [preCombatStat] → this map.
    internal val permanentSubTermsByStat: Map<Characteristic, List<Term>> = buildPermanentSubTerms()

    // Per-element DI sub contributions (Brûlure/Gel/Tellurisme/Ventilation) routed by their OWN element's
    // mastery, in most-masteries mode only — kept OUT of the global DAMAGE_INFLICTED so a "+12% fire damage"
    // sub multiplies only the fire damage line of [diAdjustedPerElementMasteryScore], not water's. Populated
    // as a side effect of [buildSublimationTerms]; declared before it so it is initialized first (empty).
    internal val elementDiTermsByMastery = mutableMapOf<Characteristic, MutableList<Term>>()

    // Per-sub reified "scenario element is the build's strongest" boolean for best-element concentration subs
    // (Elemental Concentration). Declared BEFORE [subTermsByStat] because that map's eager init
    // ([buildSublimationTerms]) reads it via [scenarioElementStrongestVar].
    internal val bestElementStrongestCache = HashMap<Int, IntVar>()

    // Conversion `moved` variables (subConvMoved_*): the value a CONVERSION sub moves between two stats,
    // gated on its subVar. Neither carrier- nor sub- nor skill-keyed, so the AP-cell certifier must
    // EXCLUDE them from its term lists (else they leak into its passive constants at domain-max) and
    // account the conversion analytically instead — see [certifyMaxPerHitAtAp]. Declared BEFORE
    // [subTermsByStat] whose eager init populates it.
    internal val conversionMovedVars = mutableSetOf<IntVar>()

    // Sub-gated DERIVED vars (currently the per-stat-step ramps, e.g. Featherweight's clamped DI) mapped
    // back to their sublimation. Like the conversion `moved` vars they are not subVar-keyed, so without
    // this map the certifier's passive fold would credit them UNCONDITIONALLY at their cap — a free +24 DI
    // that costs no sub slot and competes with nothing. Mapping them lets [perSubValue] attribute them to
    // their sub, which turns them into normal DP transitions (slot + rarity + per-state choice).
    internal val subDerivedVars = mutableMapOf<IntVar, Sublimation>()

    // Sublimation stat contributions folded into the term loop, grouped by the (AP/MP/WP-folded)
    // characteristic they feed — including a CONVERSION's ±moved pair on its from/to stats. Built
    // eagerly so prePercentStat sees them. Conditions reify against [preCombatStat] (base + items +
    // runes + skills + PERMANENT subs) to keep the constraint network acyclic.
    private val subTermsByStat: Map<Characteristic, List<Term>> = buildSublimationTerms()

    // The selected passives' flat stats ([Passive.flatStats] — the extractor's permanent + unconditional
    // + flat + positive subset, safe to fold for ANY passive), added as constants (a passive is a fixed
    // player choice, not a solver variable). The conditional/triggered part of a passive (combat state
    // the static solver can't see) is not modeled; the full loadout still rides on the build for display.
    // Grouped by the AP/MP/WP-folded stat, like [subTermsByStat].
    private val passiveTermsByStat: Map<Characteristic, List<Term>> = buildPassiveTerms()

    // Featherweight-style "per <source> above threshold, +<target> (capped)" ramps ([Sublimation.perStatStep]),
    // grouped by their target stat. Their magnitude depends on a build VARIABLE (the source stat), so unlike a
    // flat sub effect they can't be a constant term — [prePercentTermsFor] adds a memoized reified clamped var
    // ([perStatStepGatedVar]) built from actualStat(source). source ≠ target (e.g. MP vs DI), so building it
    // never re-enters the target stat's own term loop. COMBAT_CONDITIONAL subs are skipped (like the flat loop).
    private val perStatStepSpecsByTarget: Map<Characteristic, List<Sublimation>> =
        subModel.subVars.keys
            .filter { it.kind != SublimationKind.COMBAT_CONDITIONAL && it.perStatStep != null }
            .groupBy { it.perStatStep!!.target.foldedToUsableStat() }
    internal val perStatStepVarCache = HashMap<Int, IntVar>()

    fun totalActualScore(
        requiredTargets: List<TargetStat>,
        totalExpectedScore: Long,
        targetStats: TargetStats,
    ): IntVar {
        // Score each constraint as weight * clamp(actual, -target, target). Clamping *before*
        // multiplying by the (fixed-point) weight keeps the weighted domain tight (~target*weight)
        // instead of STAT_WITH_PERCENT_ABS_MAX*weight, which otherwise blows the integer domains up
        // and makes the model intractable on the full item set. The low clamp at -target is
        // faithful to the scorer: totalActualScore is floored at 1 before the penalty ratio, so a
        // constraint dragged below -target already maxes out the penalty either way.
        val contributions =
            requiredTargets.map { targetStat ->
                val actual = requiredActualStat(targetStat.characteristic)
                val weight = targetStats.scaledWeight(targetStat)
                val target = targetStat.target.toLong()
                val name = targetStat.characteristic.name

                val cappedAtTarget = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, target.coerceAtLeast(0), "capAt_$name")
                model.addMinEquality(cappedAtTarget, arrayOf(actual, model.newConstant(target)))

                val clamped = model.newIntVar(-target.coerceAtLeast(0), target.coerceAtLeast(0), "clamp_$name")
                model.addMaxEquality(clamped, arrayOf(cappedAtTarget, model.newConstant(-target)))

                val expectedScore = target * weight
                val contribution = model.newIntVar(-expectedScore, expectedScore, "contrib_$name")
                model.addEquality(contribution, LinearExpr.term(clamped, weight))
                contribution
            }

        return model.sumVar("totalActualScore", contributions, -totalExpectedScore, totalExpectedScore)
    }

    /**
     * Weighted amount by which a build *exceeds* its required targets — the raw input to the
     * lexicographic overshoot tie-breaker (see [WakfuBuildSolver.withOvershootTieBreaker]). Mirrors
     * [totalActualScore] (same `requiredActualStat` values, same per-constraint weights), but scores
     * `weight * clamp(actual - target, 0, target)`: only the part above the target, and capped at
     * one extra target's worth so the whole sum stays ≤ [totalExpectedScore] and no single stat can
     * dominate. The shared weights mean leftover skill points flow to the highest-priority stat
     * exactly like the constraints themselves decide, with no separate distribution policy to encode.
     */
    fun overshootScore(
        requiredTargets: List<TargetStat>,
        totalExpectedScore: Long,
        targetStats: TargetStats,
        encoding: MmOvershootEncoding = MmOvershootEncoding.CURRENT,
    ): IntVar {
        val contributions =
            requiredTargets.map { targetStat ->
                val actual = requiredActualStat(targetStat.characteristic)
                val weight = targetStats.scaledWeight(targetStat)
                val target = targetStat.target.toLong().coerceAtLeast(0)
                val name = targetStat.characteristic.name

                // Zero-target/weight and negative custom-weight requests do not satisfy the objective-induced
                // assumptions below. Keep the shipped exact encoding for those uncommon shapes.
                val effectiveEncoding =
                    if (target > 0L && weight > 0L) encoding else MmOvershootEncoding.CURRENT
                val positiveExcess =
                    when (effectiveEncoding) {
                        MmOvershootEncoding.CURRENT -> {
                            val excess = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, "excess_$name")
                            model.addEquality(
                                excess,
                                LinearExpr
                                    .newBuilder()
                                    .addTerm(actual, 1)
                                    .add(-target)
                                    .build()
                            )
                            val cappedExcess = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, target, "excessCap_$name")
                            model.addMinEquality(cappedExcess, arrayOf(excess, model.newConstant(target)))
                            val positive = model.newIntVar(0, target, "excessPos_$name")
                            model.addMaxEquality(positive, arrayOf(cappedExcess, model.newConstant(0L)))
                            positive
                        }

                        MmOvershootEncoding.HARD_EXACT_SIMPLIFIED -> {
                            // The hard leg already adds actual >= target. Expose that fact in this auxiliary's
                            // declared lower bound, eliminating the now-redundant max(excess, 0) propagator.
                            val excess = model.newIntVar(0L, STAT_WITH_PERCENT_ABS_MAX, "excess_$name")
                            model.addEquality(
                                excess,
                                LinearExpr
                                    .newBuilder()
                                    .addTerm(actual, 1)
                                    .add(-target)
                                    .build()
                            )
                            val positive = model.newIntVar(0L, target, "excessPos_$name")
                            model.addMinEquality(positive, arrayOf(excess, model.newConstant(target)))
                            positive
                        }

                        MmOvershootEncoding.HARD_HYPOGRAPH -> {
                            // Exact at every optimum: this var has a positive objective coefficient and is bounded
                            // above by both target and actual-target, so maximization drives it to their minimum.
                            val positive = model.newIntVar(0L, target, "excessPos_$name")
                            model.addLessOrEqual(
                                LinearExpr
                                    .newBuilder()
                                    .addTerm(positive, 1L)
                                    .addTerm(actual, -1L)
                                    .add(target)
                                    .build(),
                                0L
                            )
                            positive
                        }
                    }

                val contribution = model.newIntVar(0, target * weight, "overshoot_$name")
                model.addEquality(contribution, LinearExpr.term(positiveExcess, weight))
                contribution
            }

        return model.sumVar("overshootScore", contributions, 0, totalExpectedScore)
    }

    /**
     * Models [FindClosestBuildFromInputScoring] as a CP-SAT objective: every requested stat
     * scores min(weight * actual, weight * target); the aggregate elementary stats average the
     * four elements. Two refinements mirror the scorer: a target-0 stat that ends up negative
     * halves the score, and once every target is fully met the build that exceeds the targets
     * the most ranks higher (lexicographic capped-then-overflow objective).
     */
    fun precisionScore(targetStats: TargetStats): IntVar {
        val capped = mutableListOf<IntVar>()
        val uncapped = mutableListOf<IntVar>()
        var totalExpected = 0L

        for (targetStat in targetStats) {
            val weight = targetStats.scaledWeight(targetStat)
            if (weight == 0L) continue
            val expected = targetStat.target.toLong() * weight
            val name = targetStat.characteristic.name
            val (cappedVar, uncappedVar) =
                when (targetStat.characteristic) {
                    Characteristic.MASTERY_ELEMENTARY ->
                        averagedContribution(elementMasteryVars(ELEMENTARY_MASTERIES).values.toList(), weight, expected, name)

                    Characteristic.RESISTANCE_ELEMENTARY ->
                        averagedContribution(elementResistanceVars(ELEMENTARY_RESISTANCES).values.toList(), weight, expected, name)

                    else -> cappedContribution(foldedElementalStat(targetStat.characteristic), weight, expected, name)
                }
            capped.add(cappedVar)
            uncapped.add(uncappedVar)
            totalExpected += expected
        }

        val totalExpectedScore = totalExpected.coerceAtLeast(1L)
        val maxWeight = (targetStats.maxOfOrNull { targetStats.scaledWeight(it) } ?: 1L).coerceAtLeast(1L)
        val bound = STAT_WITH_PERCENT_ABS_MAX * maxWeight * targetStats.size.coerceAtLeast(1)

        val cappedSum = model.sumVar("precisionCapped", capped, -bound, totalExpectedScore)
        val uncappedSum = model.sumVar("precisionUncapped", uncapped, -bound, bound)
        val penalizedCapped = negativeTargetPenalty(targetStats, cappedSum, -bound, totalExpectedScore)

        // overflow = how far the build exceeds the targets; always >= 0 because each capped term
        // is <= its uncapped term. It is only rewarded once every target is met (see fullyMet).
        val rawOverflow = model.newIntVar(0, 2 * bound, "precisionRawOverflow")
        model.addEquality(
            rawOverflow,
            LinearExpr
                .newBuilder()
                .addTerm(uncappedSum, 1)
                .addTerm(cappedSum, -1)
                .build()
        )
        val clampedOverflow = model.newIntVar(0, PRECISION_OVERFLOW_BOUND, "precisionOverflow")
        model.addMinEquality(clampedOverflow, arrayOf(rawOverflow, model.newConstant(PRECISION_OVERFLOW_BOUND)))

        val fullyMet = model.newBoolVar("precisionFullyMet")
        model.addGreaterOrEqual(penalizedCapped, totalExpectedScore).onlyEnforceIf(fullyMet)
        model.addLessOrEqual(penalizedCapped, totalExpectedScore - 1).onlyEnforceIf(fullyMet.not())

        val bonus = model.newIntVar(0, PRECISION_OVERFLOW_BOUND, "precisionBonus")
        model.addEquality(bonus, clampedOverflow).onlyEnforceIf(fullyMet)
        model.addEquality(bonus, 0L).onlyEnforceIf(fullyMet.not())

        val objective = model.newIntVar(-bound, totalExpectedScore + PRECISION_OVERFLOW_BOUND, "precisionObjective")
        model.addEquality(
            objective,
            LinearExpr
                .newBuilder()
                .addTerm(penalizedCapped, 1)
                .addTerm(bonus, 1)
                .build()
        )
        return objective
    }

    private fun cappedContribution(
        actual: IntVar,
        weight: Long,
        expected: Long,
        name: String,
    ): Pair<IntVar, IntVar> {
        val span = STAT_WITH_PERCENT_ABS_MAX * weight
        val weighted = model.newIntVar(-span, span, "precWeighted_$name")
        model.addEquality(weighted, LinearExpr.term(actual, weight))
        val cappedVar = model.newIntVar(-span, expected, "precCapped_$name")
        model.addMinEquality(cappedVar, arrayOf(weighted, model.newConstant(expected)))
        return cappedVar to weighted
    }

    private fun averagedContribution(
        elementActuals: List<IntVar>,
        weight: Long,
        expected: Long,
        name: String,
    ): Pair<IntVar, IntVar> {
        val cappedElements = mutableListOf<IntVar>()
        val uncappedElements = mutableListOf<IntVar>()
        elementActuals.forEachIndexed { index, element ->
            val (cappedVar, uncappedVar) = cappedContribution(element, weight, expected, "${name}_$index")
            cappedElements.add(cappedVar)
            uncappedElements.add(uncappedVar)
        }
        val count = elementActuals.size.coerceAtLeast(1)
        val span = STAT_WITH_PERCENT_ABS_MAX * weight * count
        val cappedSum = model.sumVar("precElemCapped_$name", cappedElements, -span, expected * count)
        val uncappedSum = model.sumVar("precElemUncapped_$name", uncappedElements, -span, span)
        val cappedAvg = model.newIntVar(-span, expected, "precElemCappedAvg_$name")
        model.addDivisionEquality(cappedAvg, cappedSum, model.newConstant(count.toLong()))
        val uncappedAvg = model.newIntVar(-span, span, "precElemUncappedAvg_$name")
        model.addDivisionEquality(uncappedAvg, uncappedSum, model.newConstant(count.toLong()))
        return cappedAvg to uncappedAvg
    }

    private fun negativeTargetPenalty(
        targetStats: TargetStats,
        cappedSum: IntVar,
        low: Long,
        high: Long,
    ): IntVar {
        val zeroTargets =
            targetStats.filter {
                it.target == 0 &&
                    it.characteristic != Characteristic.MASTERY_ELEMENTARY &&
                    it.characteristic != Characteristic.RESISTANCE_ELEMENTARY
            }
        if (zeroTargets.isEmpty()) return cappedSum

        val flagsSum = LinearExpr.newBuilder()
        for (targetStat in zeroTargets) {
            val actual = actualStat(targetStat.characteristic)
            val isNegative = model.newBoolVar("precNeg_${targetStat.characteristic.name}")
            model.addLessOrEqual(actual, -1L).onlyEnforceIf(isNegative)
            model.addGreaterOrEqual(actual, 0L).onlyEnforceIf(isNegative.not())
            flagsSum.addTerm(isNegative, 1)
        }
        val anyNegative = model.newBoolVar("precAnyNegativeTarget0")
        val flags = flagsSum.build()
        model.addGreaterOrEqual(flags, 1L).onlyEnforceIf(anyNegative)
        model.addLessOrEqual(flags, 0L).onlyEnforceIf(anyNegative.not())

        val halved = model.newIntVar(low, high, "precHalvedCapped")
        model.addDivisionEquality(halved, cappedSum, model.newConstant(2L))

        val penalized = model.newIntVar(low, high, "precPenalizedCapped")
        model.addEquality(penalized, cappedSum).onlyEnforceIf(anyNegative.not())
        model.addEquality(penalized, halved).onlyEnforceIf(anyNegative)
        return penalized
    }

    /**
     * The "most-masteries" objective: maximize the requested masteries scaled by a damage-faithful
     * `(1 + DI/100)` factor (the same factor the max-damage objective and the real hit formula use). The
     * previous single GLOBAL fold becomes a **per-element** fold, so a per-element "+X% <element> damage"
     * sublimation (Brûlure/Gel/Tellurisme/Ventilation) multiplies ONLY its own element's damage line:
     *
     *  - BRANCH A (no element requested — `minElements` empty): one product `clamp(nonElemNeg,≥0) × (100 +
     *    globalDI) / 100`. Byte-identical (in value) to the old `finalMasteryScore × diAdjustedMasteryScore`
     *    (where `lowestElementMastery == 0`). The common distance/melee-only request stays on this path.
     *  - BRANCH B (element(s) requested): `min over e in minElements of clamp(nonElemNeg + mastery_e, ≥0) ×
     *    (100 + clamp(globalDI + elementDI_e, [-FLOOR,MAX])) / 100`. Each element's OWN per-element DI sits
     *    inside ITS factor; the weakest element governs (the existing balance philosophy). With no per-element
     *    DI all `factor_e` are equal, so by monotonicity `min_e floor(g(m_e)) == floor(g(min_e m_e))` ⇒ value
     *    reduces to BRANCH A — no-element / no-sub requests are unchanged.
     *
     * The combined DI is clamped as ONE sum `clamp(globalDI + elementDI_e)`, mirroring the old mono path
     * (which folded the per-element +12 into the global DAMAGE_INFLICTED bucket). The re-scorer
     * (FindMostMasteriesFromInputScoring) mirrors this exactly, including the per-element integer truncation.
     *
     * Each product clamps `mastery ≥ 0` before the multiply (the factor `100 + DI` is positive, so multiplying
     * a negative mastery would invert the DI incentive), divides by 100 (floor), and clamps onto
     * `[0, MASTERY_SCORE_ABS_MAX]`, so the result keeps the same domain as before ⇒ every downstream penalty /
     * overshoot bound is unchanged.
     */
    fun diAdjustedPerElementMasteryScore(
        targetStats: TargetStats,
        targetCharacteristics: Set<Characteristic>,
        productEncoding: MmProductEncoding = MmProductEncoding.CURRENT,
    ): Pair<IntVar, Long> {
        val nonElementaries =
            targetStats
                .filter { it.characteristic in NON_ELEMENTARY_MASTERIES }
                .map { actualStat(it.characteristic) }
        val nonElemSum = model.sumVar("nonElemMastery", nonElementaries, -MASTERY_SCORE_ABS_MAX, MASTERY_SCORE_ABS_MAX)
        // C8: a GENEROUS but SOUND reachable ceiling on this score (`mastery × (1+DI/100)`), used below to declare
        // the score var — and hence the objective-product McCormick box in [applyConstraintPenalty] — on a real
        // bound instead of the loose `MASTERY_SCORE_ABS_MAX` (1e8). It over-estimates on purpose (sums each
        // component's tracked reach, ignoring slot competition and the negative-mastery penalty, and uses the max
        // DI factor), so it can only be looser than the true max, never tighter — an untracked component falls back
        // to the loose guard, so the worst case is simply no tightening. Clamped to the existing cap. Guarded by the
        // exhaustive most-masteries optimum tests: an under-count would cap the objective below the brute-force best.
        val diFactorMax = 100L + DAMAGE_DI_MAX
        val nonElemReachMax = nonElementaries.sumOf { maxOf(0L, tracker.of(it).last) }

        val negativePenaltyVars =
            NEGATIVE_MASTERY_PENALTY
                .filter { it !in targetCharacteristics }
                .map { char ->
                    val actual = actualStat(char)
                    val negativePart = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, 0, "neg_${char.name}")
                    model.addMinEquality(negativePart, arrayOf(actual, model.newConstant(0L)))
                    negativePart
                }
        val negativePenalty = model.sumVar("negMasteryPenalty", negativePenaltyVars, -MASTERY_SCORE_ABS_MAX, 0)

        // The element-independent base shared by every per-element damage line: non-element masteries
        // (distance/melee/crit/…) + the negative-mastery penalty.
        val nonElemNeg =
            model.sumVar(
                "mmNonElemNeg",
                listOf(Term(nonElemSum, 1L), Term(negativePenalty, 1L)),
                0L,
                -MASTERY_SCORE_ABS_MAX,
                MASTERY_SCORE_ABS_MAX
            )

        val globalDiSource = actualStat(Characteristic.DAMAGE_INFLICTED)
        val globalDi =
            if (productEncoding == MmProductEncoding.CURRENT) {
                model.clampVar(globalDiSource, -DAMAGE_DI_FLOOR, DAMAGE_DI_MAX, "mmDI")
            } else {
                tClamp(globalDiSource, -DAMAGE_DI_FLOOR, DAMAGE_DI_MAX, "mmDI")
            }
        val productBound = MASTERY_SCORE_ABS_MAX * (100L + DAMAGE_DI_MAX)

        // The shared `clamp(mastery,≥0) × factor / 100 → clamp` kernel (one bilinear product).
        fun diProduct(
            masteryTier: IntVar,
            diFactor: IntVar,
            masteryReachHi: Long,
            tag: String,
        ): IntVar {
            if (productEncoding == MmProductEncoding.CURRENT) {
                val nonNeg = model.clampVar(masteryTier, 0L, MASTERY_SCORE_ABS_MAX, "mmNN_$tag")
                val product = model.newIntVar(0L, productBound, "mmProd_$tag")
                model.addMultiplicationEquality(product, arrayOf(nonNeg, diFactor))
                val scaled = model.newIntVar(0L, productBound / 100L, "mmScaled_$tag")
                model.addDivisionEquality(scaled, product, model.newConstant(100L))
                return model.clampVar(scaled, 0L, MASTERY_SCORE_ABS_MAX, "mmAdj_$tag")
            }

            // The manual reach deliberately ignores negative mastery penalties and slot competition, so it
            // over-estimates the attainable tier. Recording it upstream lets every following big-M/domain consume
            // the bound instead of discovering only the final downstream coreHi clamp.
            val tierHi = masteryReachHi.coerceIn(0L, MASTERY_SCORE_ABS_MAX)
            tracker.record(masteryTier, -MASTERY_SCORE_ABS_MAX..tierHi, "mmTierReach_$tag")
            val nonNeg = tClamp(masteryTier, 0L, MASTERY_SCORE_ABS_MAX, "mmNN_$tag")
            val factorReach = tracker.of(diFactor)
            val reachableProductHi = (tierHi * maxOf(0L, factorReach.last)).coerceAtMost(productBound)
            val product =
                when (productEncoding) {
                    MmProductEncoding.TRACKED ->
                        tMul("mmProd_$tag", nonNeg, diFactor, 0L, productBound)

                    MmProductEncoding.BINARY -> {
                        val span = factorReach.last - factorReach.first
                        val offset =
                            tSum(
                                "mmDiOffset_$tag",
                                listOf(Term(diFactor, 1L)),
                                -factorReach.first,
                                0L..span,
                                0L,
                                100L + DAMAGE_DI_MAX
                            )
                        tBinaryOffsetProduct(
                            "mmProd_$tag",
                            offset,
                            factorReach,
                            nonNeg,
                            0L..tierHi,
                            0L..productBound
                        )
                    }

                    MmProductEncoding.CURRENT -> error("handled above")
                }
            val scaled = tDiv("mmScaled_$tag", product, 100L, 0L, productBound / 100L)
            val reachableScaledHi = (reachableProductHi / 100L).coerceAtMost(MASTERY_SCORE_ABS_MAX)
            return tClamp(scaled, 0L, reachableScaledHi, "mmAdj_$tag")
        }

        val minElements = targetStats.masteryElementsToMinimize
        if (minElements.isEmpty()) {
            // BRANCH A: no element requested ⇒ one product on (nonElemNeg × globalFactor).
            val factor =
                if (productEncoding == MmProductEncoding.CURRENT) {
                    model.sumVar("mmDiFactor", listOf(Term(globalDi, 1L)), 100L, 100L - DAMAGE_DI_FLOOR, 100L + DAMAGE_DI_MAX)
                } else {
                    tSumNaive("mmDiFactor", listOf(Term(globalDi, 1L)), 100L, 100L - DAMAGE_DI_FLOOR, 100L + DAMAGE_DI_MAX)
                }
            val coreHi = WakfuBuildSolver.clampedProductQuotient(nonElemReachMax, diFactorMax, 100L, MASTERY_SCORE_ABS_MAX).coerceAtLeast(1L)
            return model.clampVar(diProduct(nonElemNeg, factor, nonElemReachMax, "global"), 0L, coreHi, "mmCoreHi") to coreHi
        }

        // BRANCH B: per-element products (each with its own element DI inside its factor), then weakest-min.
        val foldElements = targetStats.masteryElementsWanted.keys.toList()
        val elementVars = elementMasteryVars(foldElements)
        val perElementAdj =
            minElements.associateWith { e ->
                val tier =
                    model.sumVar(
                        "mmTier_${e.name}",
                        listOf(Term(nonElemNeg, 1L), Term(elementVars.getValue(e), 1L)),
                        0L,
                        -MASTERY_SCORE_ABS_MAX,
                        MASTERY_SCORE_ABS_MAX
                    )
                // combinedDi = clamp(globalDI + elementDI_e, [-FLOOR, MAX]) — ONE clamp of the sum (mirrors mono).
                val diSum =
                    if (productEncoding == MmProductEncoding.CURRENT) {
                        model.sumVar(
                            "mmDiSum_${e.name}",
                            listOf(Term(globalDi, 1L), Term(elementDiVar(e), 1L)),
                            0L,
                            -DAMAGE_DI_FLOOR,
                            DAMAGE_DI_MAX + DAMAGE_DI_MAX
                        )
                    } else {
                        tSumNaive(
                            "mmDiSum_${e.name}",
                            listOf(Term(globalDi, 1L), Term(elementDiVar(e), 1L)),
                            0L,
                            -DAMAGE_DI_FLOOR,
                            DAMAGE_DI_MAX + DAMAGE_DI_MAX
                        )
                    }
                val combinedDi =
                    if (productEncoding == MmProductEncoding.CURRENT) {
                        model.clampVar(diSum, -DAMAGE_DI_FLOOR, DAMAGE_DI_MAX, "mmDiClamp_${e.name}")
                    } else {
                        tClamp(diSum, -DAMAGE_DI_FLOOR, DAMAGE_DI_MAX, "mmDiClamp_${e.name}")
                    }
                val factor =
                    if (productEncoding == MmProductEncoding.CURRENT) {
                        model.sumVar("mmDiFactor_${e.name}", listOf(Term(combinedDi, 1L)), 100L, 100L - DAMAGE_DI_FLOOR, 100L + DAMAGE_DI_MAX)
                    } else {
                        tSumNaive(
                            "mmDiFactor_${e.name}",
                            listOf(Term(combinedDi, 1L)),
                            100L,
                            100L - DAMAGE_DI_FLOOR,
                            100L + DAMAGE_DI_MAX
                        )
                    }
                val tierReachMax = nonElemReachMax + maxOf(0L, tracker.of(elementVars.getValue(e)).last)
                diProduct(tier, factor, tierReachMax, e.name)
            }
        // The score is the MIN over requested elements, so it is bounded by the smallest element's (nonElem +
        // element) reach × the max DI factor — a sound over-estimate (each `tier` clamps ≥ 0 and adds the negative
        // penalty, both only lowering it).
        val coreHi =
            minElements
                .minOf { e ->
                    val tierReachMax = nonElemReachMax + maxOf(0L, tracker.of(elementVars.getValue(e)).last)
                    WakfuBuildSolver.clampedProductQuotient(tierReachMax, diFactorMax, 100L, MASTERY_SCORE_ABS_MAX)
                }.coerceAtLeast(1L)
        val result = model.newIntVar(0L, coreHi, "mmDiAdjustedMin")
        model.addMinEquality(result, minElements.map { perElementAdj.getValue(it) }.toTypedArray())
        return result to coreHi
    }

    // The build's resolved Action Points variable (base + gear + skills), for the external-loop AP probe.
    fun actionPointVar(): IntVar = actualStat(Characteristic.ACTION_POINT)

    /**
     * Monotonic **effective-HP proxy** for the survivability soft-floor (Lot 5):
     * `EHP ≈ HP · (100 + avgResist) / 100`, with `avgResist` the average of the four elemental
     * resistances clamped to `[0, EHP_AVG_RESIST_CAP]`. This is a *linear* CP-SAT expression — exact
     * `1/(1 − res)` damage mitigation is non-linear and cannot be modeled here — so it ranks builds
     * by survivability (more HP and more resist both raise it) without being a true effective-HP.
     * Resistance is averaged (not summed) so a single high element can't masquerade as overall
     * tankiness; the cap mirrors Wakfu's soft resistance ceiling and keeps the proxy honest against
     * a few extreme resist rolls. Each element is read through [foldedElementalStat] so the generic
     * "+all elements" resistance and random-resistance gear count exactly as they do elsewhere.
     */
    fun effectiveHpVar(): IntVar {
        val hp = model.clampVar(actualStat(Characteristic.HP), 0L, EHP_HP_MAX, "ehpHp")
        val resSum =
            model.sumVar(
                "ehpResSum",
                ELEMENTARY_RESISTANCES.map { foldedElementalStat(it) },
                -STAT_WITH_PERCENT_ABS_MAX,
                STAT_WITH_PERCENT_ABS_MAX
            )
        val avgResist = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, "ehpAvgRes")
        model.addDivisionEquality(avgResist, resSum, model.newConstant(ELEMENTARY_RESISTANCES.size.toLong()))
        val avgResistClamped = model.clampVar(avgResist, 0L, EHP_AVG_RESIST_CAP, "ehpAvgResClamped")

        // factor = 100 + avgResist ∈ [100, 100 + cap]; EHP = HP · factor / 100.
        val factor = model.sumVar("ehpFactor", listOf(Term(avgResistClamped, 1L)), 100L, 100L, 100L + EHP_AVG_RESIST_CAP)
        val product = model.newIntVar(0L, EHP_HP_MAX * (100L + EHP_AVG_RESIST_CAP), "ehpProduct")
        model.addMultiplicationEquality(product, arrayOf(hp, factor))
        val ehp = model.newIntVar(0L, EHP_MAX, "ehp")
        model.addDivisionEquality(ehp, product, model.newConstant(100L))
        return ehp
    }

    /**
     * Out-of-combat hardcaps: the equipped sheet — gear + skills + runes, i.e. the PRE-sublimation value,
     * since sublimations activate at start of combat — cannot exceed 16 AP / 8 MP / 20 WP. In-combat
     * bonuses (those subs, active spells, …) may push past these; they aren't part of the equipped build.
     */
    fun applyOutOfCombatCaps() {
        model.addLessOrEqual(preSubStat(Characteristic.ACTION_POINT), MAX_OUT_OF_COMBAT_AP)
        if (maxDamageExperiment.apCeiling) {
            model.addLessOrEqual(actualStat(Characteristic.ACTION_POINT), actualActionPointCeiling())
        }
        model.addLessOrEqual(preSubStat(Characteristic.MOVEMENT_POINT), MAX_OUT_OF_COMBAT_MP)
        model.addLessOrEqual(preSubStat(Characteristic.WAKFU_POINT), MAX_OUT_OF_COMBAT_WP)
        // Negative-crit gear is condition-limited: the sheet can't drop below −9% Critical Hit.
        model.addGreaterOrEqual(preSubStat(Characteristic.CRITICAL_HIT), MIN_OUT_OF_COMBAT_CRIT)
    }

    internal fun actualActionPointCeiling(): Long =
        (
            MAX_OUT_OF_COMBAT_AP +
                positiveSublimationUpper(Characteristic.ACTION_POINT) +
                positiveUpper(passiveTermsByStat[Characteristic.ACTION_POINT].orEmpty())
        ).coerceIn(0L, MAX_ROTATION_AP)

    private fun positiveSublimationUpper(char: Characteristic): Long {
        val rangesBySub = LinkedHashMap<Sublimation, LongRange>()
        var other = 0L
        for (term in subTermsByStat[char].orEmpty()) {
            val d = tracker.of(term.variable)
            val scaled = term.scaledRange(d)
            val sub = subByVar[term.variable]
            if (sub != null) {
                val current = rangesBySub[sub] ?: (0L..0L)
                rangesBySub[sub] = current.first + scaled.first..current.last + scaled.last
            } else {
                other += scaled.last.coerceAtLeast(0L)
            }
        }
        return other + sublimationAwareUpper(rangesBySub)
    }

    private fun positiveUpper(terms: List<Term>): Long =
        terms.sumOf { term ->
            term.maxContribution(tracker.of(term.variable)).coerceAtLeast(0L)
        }

    /** Per carrier (item), the summed max contribution of [terms] (positive coef ⇒ item's high value). */
    internal fun perCarrierContribution(terms: List<Term>): LinkedHashMap<Equipment, Long> {
        val byCarrier = LinkedHashMap<Equipment, Long>()
        for (term in terms) {
            val equip = carrierByVar[term.variable] ?: continue
            val d = tracker.of(term.variable)
            val contrib = term.maxContribution(d)
            byCarrier[equip] = (byCarrier[equip] ?: 0L) + contrib
        }
        return byCarrier
    }

    /**
     * Per carrier (item), the EXACT summed contribution of [terms] when the item is equipped — the raw
     * coefficient sum. COST dimensions (AP / crit) must use this instead of [perCarrierContribution]:
     * the optimistic per-term max floors every NEGATIVE coefficient at 0 (variable at domain-min, i.e.
     * "just don't equip it"), silently un-charging negative-stat items — a Souvenir ancestral (−1
     * max-AP) rode the AP-16 certificate cell for free while the model pays its real cost. Only valid
     * for terms carried by the equip var itself (AP/crit never ride rune vars — the rune-shape bail
     * guarantees it); value dimensions keep the optimistic max, which the rune-option split expects.
     */
    internal fun perCarrierExactValue(terms: List<Term>): LinkedHashMap<Equipment, Long> {
        val byCarrier = LinkedHashMap<Equipment, Long>()
        for (term in terms) {
            val equip = carrierByVar[term.variable] ?: continue
            byCarrier[equip] = (byCarrier[equip] ?: 0L) + term.coefficient
        }
        return byCarrier
    }

    /** Per sublimation, the summed max contribution of [terms]. */
    private fun perSubContribution(terms: List<Term>): LinkedHashMap<Sublimation, Long> {
        val bySub = LinkedHashMap<Sublimation, Long>()
        for (term in terms) {
            val sub = subByVar[term.variable] ?: continue
            val d = tracker.of(term.variable)
            val contrib = term.maxContribution(d)
            bySub[sub] = (bySub[sub] ?: 0L) + contrib
        }
        return bySub
    }

    /**
     * Per sublimation, the EXACT contribution of [terms] when the sub is SELECTED (its 0/1 var = 1) — i.e.
     * the raw coefficient sum, which (unlike [perSubContribution]) keeps NEGATIVE effects such as Carapace's
     * MAX_ACTION_POINT −2. The certifier always either takes a sub whole or not at all, so this is the value
     * to fold in; the max-form would silently zero a negative AP/stat and hide that lever.
     */
    internal fun perSubValue(terms: List<Term>): LinkedHashMap<Sublimation, Long> {
        val bySub = LinkedHashMap<Sublimation, Long>()
        for (term in terms) {
            val direct = subByVar[term.variable]
            if (direct != null) {
                // The sub's own 0/1 var: value-when-taken is the raw coefficient.
                bySub[direct] = (bySub[direct] ?: 0L) + term.coefficient
                continue
            }
            val derived = subDerivedVars[term.variable] ?: continue
            // Sub-gated derived var (per-stat-step ramp): value-when-taken is its OPTIMISTIC max — the
            // tracked reach ceiling (already ≤ the ramp cap) — an upper bound since the source stat is
            // not tracked by the certifier.
            val d = tracker.of(term.variable)
            val v = term.maxContribution(d)
            bySub[derived] = (bySub[derived] ?: 0L) + v
        }
        return bySub
    }

    /** The summed max contribution of [terms] that come from neither an item nor a sublimation (skills). */
    private fun nonCarrierNonSubContribution(terms: List<Term>): Long {
        var sum = 0L
        for (term in terms) {
            if (carrierByVar[term.variable] != null || subByVar[term.variable] != null) continue
            val d = tracker.of(term.variable)
            sum += term.maxContribution(d)
        }
        return sum
    }

    /**
     * Knapsack DP over carrier slots (ring ≤2 as two pseudo-slots) + sublimations (≤MAX_SUBLIMATIONS_TOTAL), AP as the
     * capacity axis. Returns dp[a] = max Σ value over builds whose item+sub AP totals EXACTLY a, for a in
     * 0..[axisMax]. Skips epic/relic rarity caps (probe). Lets the caller read the exact AP band a build with a
     * given total AP must occupy.
     */
    private fun apAxisValueDp(
        valueByCarrier: Map<Equipment, Long>,
        apByCarrier: Map<Equipment, Long>,
        valueBySub: Map<Sublimation, Long>,
        apBySub: Map<Sublimation, Long>,
        axisMax: Int,
    ): LongArray {
        data class Opt(
            val value: Long,
            val ap: Int,
        )
        val slots = mutableListOf<List<Opt>>()
        for ((type, equips) in valueByCarrier.keys.groupBy { it.itemType }) {
            val bestByAp = HashMap<Int, Long>()
            for (e in equips) {
                val v = valueByCarrier[e] ?: 0L
                if (v <= 0L) continue
                val ap = (apByCarrier[e] ?: 0L).toInt().coerceIn(0, axisMax)
                bestByAp[ap] = maxOf(bestByAp[ap] ?: Long.MIN_VALUE, v)
            }
            val opts = listOf(Opt(0L, 0)) + bestByAp.map { Opt(it.value, it.key) }
            repeat(if (type == ItemType.RING) 2 else 1) { slots.add(opts) }
        }

        var dp = LongArray(axisMax + 1) { if (it == 0) 0L else Long.MIN_VALUE }

        fun apply(opts: List<Opt>) {
            val nd = dp.copyOf()
            for (a in 0..axisMax) {
                if (dp[a] == Long.MIN_VALUE) continue
                for (o in opts) {
                    if (o.value == 0L && o.ap == 0) continue
                    val na = a + o.ap
                    if (na > axisMax) continue
                    if (dp[a] + o.value > nd[na]) nd[na] = dp[a] + o.value
                }
            }
            dp = nd
        }
        for (slotOpts in slots) apply(slotOpts)
        // Sublimations: 0/1 each, ≤MAX_SUBLIMATIONS_TOTAL — track count as a second axis only while applying.
        val subList = valueBySub.entries.filter { (it.value) > 0L }
        if (subList.isNotEmpty()) {
            val maxSubs = MAX_SUBLIMATIONS_TOTAL.toInt()
            var dpk = Array(maxSubs + 1) { k -> if (k == 0) dp else LongArray(axisMax + 1) { Long.MIN_VALUE } }
            for ((sub, value) in subList) {
                val ap = (apBySub[sub] ?: 0L).toInt().coerceIn(0, axisMax)
                val nd = Array(maxSubs + 1) { k -> dpk[k].copyOf() }
                for (k in 0 until maxSubs) {
                    for (a in 0..axisMax) {
                        if (dpk[k][a] == Long.MIN_VALUE) continue
                        val na = a + ap
                        if (na > axisMax) continue
                        if (dpk[k][a] + value > nd[k + 1][na]) nd[k + 1][na] = dpk[k][a] + value
                    }
                }
                dpk = nd
            }
            val merged = LongArray(axisMax + 1) { Long.MIN_VALUE }
            for (k in 0..maxSubs) {
                for (a in 0..axisMax) merged[a] = maxOf(merged[a], dpk[k][a])
            }
            dp = merged
        }
        return dp
    }

    /**
     * `M = 100 + ΣMastery` for the max-damage formula, built as ONE slot-aware linear sum. Summing the
     * already-aggregated elemental and secondary mastery vars loses the carrier correlation: the fire
     * bound can pick the best fire item in a slot while the distance bound picks a different best-distance
     * item in that same slot. Building the direct term list lets [reachableSumDomain] score each candidate
     * item by its combined fire + generic + random + distance contribution and then keep only the best
     * item per slot (two rings), which is the sound bound CP-SAT needs to close the damage proof.
     */
    internal fun damagePreMastery(
        suffix: String,
        fallbackElementMasteryVar: IntVar,
        scenario: DamageScenario,
    ): IntVar {
        val direct = damagePreMasteryTerms(scenario)
        if (direct != null) {
            return tSum(
                "dmgPreM_$suffix",
                direct.terms,
                direct.constant,
                reachableSumDomain(direct.terms, direct.constant),
                -CLAMP_INTERMEDIATE_MAX,
                CLAMP_INTERMEDIATE_MAX
            )
        }

        val terms = mutableListOf(Term(fallbackElementMasteryVar, 1L), Term(actualStat(scenario.rangeBand.masteryCharacteristic), 1L))
        if (scenario.orientation.grantsRearMastery) terms.add(Term(actualStat(Characteristic.MASTERY_BACK), 1L))
        if (scenario.berserk) terms.add(Term(actualStat(Characteristic.MASTERY_BERSERK), 1L))
        if (scenario.healing) terms.add(Term(actualStat(Characteristic.MASTERY_HEALING), 1L))
        return tSumNaive("dmgPreM_$suffix", terms, 100L, -CLAMP_INTERMEDIATE_MAX, CLAMP_INTERMEDIATE_MAX)
    }

    internal fun damagePreMasteryTerms(scenario: DamageScenario): LinearTermSum? = damagePreMasteryTermsCache.getOrPut(scenario) { computeDamagePreMasteryTerms(scenario) }

    private fun computeDamagePreMasteryTerms(scenario: DamageScenario): LinearTermSum? {
        val directStats = scenarioMasteryStats(scenario).distinct()

        // No current mastery skill is percent-based. If a future data/model change adds one, preserve
        // exactness by falling back to the older var-sum path for that stat instead of silently skipping
        // the percent application.
        if (directStats.any { skillTerms.percent[it].orEmpty().isNotEmpty() }) {
            return null
        }

        val terms = mutableListOf<Term>()
        var constant = 100L
        for (stat in directStats) {
            val (statTerms, statBase) = prePercentTermsFor(stat)
            terms.addAll(statTerms)
            constant += statBase
        }

        for (equip in allEquips) {
            val equipVar = equipVars.getValue(equip)
            for ((randomCharacteristic, count) in MASTERY_RANDOM_BY_COUNT) {
                if (min(count, 1) == 0) continue
                val value = equip.characteristics[randomCharacteristic] ?: 0
                if (value != 0) terms.add(Term(equipVar, value.toLong()))
            }
        }

        return LinearTermSum(terms, constant)
    }

    internal fun damageMasteryCriticalReach(
        scenario: DamageScenario,
        masteryWeight: Long,
        criticalMasteryWeight: Long,
        guardHi: Long,
    ): LongRange {
        val mastery = damagePreMasteryTerms(scenario) ?: return 0L..guardHi
        if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return 0L..guardHi

        val (criticalTerms, criticalBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
        val terms = mutableListOf<Term>()
        mastery.terms.forEach { terms.add(Term(it.variable, it.coefficient * masteryWeight)) }
        criticalTerms.forEach { terms.add(Term(it.variable, it.coefficient * criticalMasteryWeight)) }
        val constant = mastery.constant * masteryWeight + criticalBase * criticalMasteryWeight

        // E2 (conversionConservationCut): the clamp slack — how far clamp(preStat, 0, ·) rises above a raw sum that
        // went negative — must NOT count a CONVERSION `moved` term. `moved ∈ [0, percent%·preSubStat(from)]` can never
        // drive the source below its no-conversion min, so a `−moved` in criticalReach.first credits a clamp-restoration
        // for an unreachable state — double-banking the moved mastery already netted EXACTLY by the combined reach (C1).
        // Excluding the moved vars drops that phantom while keeping the real gear-driven slack. Sound: exhaustive panel + fuzz.
        val clampMasteryTerms =
            if (maxDamageExperiment.conversionConservationCut) mastery.terms.filter { it.variable !in conversionMovedVars } else mastery.terms
        val clampCriticalTerms =
            if (maxDamageExperiment.conversionConservationCut) criticalTerms.filter { it.variable !in conversionMovedVars } else criticalTerms
        val masteryReach = reachableSumDomain(clampMasteryTerms, mastery.constant)
        val criticalReach = reachableSumDomain(clampCriticalTerms, criticalBase)
        val clampSlack = masteryWeight * -minOf(0L, masteryReach.first) + criticalMasteryWeight * -minOf(0L, criticalReach.first)
        val hi = (reachableSumDomain(terms, constant).last + clampSlack).coerceAtLeast(0L).coerceAtMost(guardHi)
        return 0L..hi
    }

    /**
     * C7: sound upper bound on `μ·crit + diff` over every build — the C(μ) numerator of the AM-GM crit·diff
     * cut (see `DamageObjective.maxCritDiffProductBound`), with μ = [criticalHitWeight]. It is
     * [damageMasteryCriticalReach] extended with a third weighted axis: the raw `CRITICAL_HIT` pre-percent
     * terms at weight μ. All three objective factors are LOWER-clamped (`crit = clamp(rawCrit, 0, critCap)`,
     * `m = clamp(preM, 0, ·)`, `K = clamp(preK, 0, ·)`), and for any build `clamp(x, 0, ·) ≤ x + max(0, −xLo)`
     * with `xLo` a sound lower reach — so each axis carries that clamp slack at its weight. Pricing the m/K
     * lower clamps at zero (a naive [reachableSumDomain] over the raw term lists) was exactly the reverted
     * 2026-07-07 under-count (perf-review-backlog §C7). Null ⇒ a %-skill term (or no direct mastery
     * decomposition) makes the slot decomposition unsound — the caller must skip the cut.
     */
    internal fun critDiffJointReachHi(
        scenario: DamageScenario,
        criticalHitWeight: Long,
    ): Long? {
        val mastery = damagePreMasteryTerms(scenario) ?: return null
        if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return null
        if (skillTerms.percent[Characteristic.CRITICAL_HIT].orEmpty().isNotEmpty()) return null

        val (criticalTerms, criticalBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
        val (critHitTerms, critHitBase) = prePercentTermsFor(Characteristic.CRITICAL_HIT)
        val terms = mutableListOf<Term>()
        mastery.terms.forEach { terms.add(Term(it.variable, it.coefficient)) }
        criticalTerms.forEach { terms.add(Term(it.variable, it.coefficient * 5L)) }
        critHitTerms.forEach { terms.add(Term(it.variable, it.coefficient * criticalHitWeight)) }
        val constant = mastery.constant + criticalBase * 5L + critHitBase * criticalHitWeight

        // Same E2 exclusion as [damageMasteryCriticalReach]: a conversion `moved ∈ [0, pct·preSubStat(from)]`
        // can never drive its source below the no-conversion min, so it must not credit clamp restoration.
        fun slackTerms(list: List<Term>) = if (maxDamageExperiment.conversionConservationCut) list.filter { it.variable !in conversionMovedVars } else list
        val masteryLo = reachableSumDomain(slackTerms(mastery.terms), mastery.constant).first
        val criticalLo = reachableSumDomain(slackTerms(criticalTerms), criticalBase).first
        val critHitLo = reachableSumDomain(slackTerms(critHitTerms), critHitBase).first
        val clampSlack =
            -minOf(0L, masteryLo) +
                5L * -minOf(0L, criticalLo) +
                criticalHitWeight * -minOf(0L, critHitLo)
        return reachableSumDomain(terms, constant).last + clampSlack
    }

    /**
     * Elemental mastery for the scenario's spell element, with generic "+all elements" mastery and
     * random-element lines folded in onto that single element — matching how [FindMaxDamageScoring]
     * (via computeCharacteristicsValues with that one wanted element) resolves it.
     */
    internal fun scenarioElementMasteryVar(element: Characteristic): IntVar =
        elementVars(
            wantedElements = listOf(element),
            genericCharacteristic = Characteristic.MASTERY_ELEMENTARY,
            targets = mapOf(element to 1),
            randomByCount = MASTERY_RANDOM_BY_COUNT
        ).getValue(element)

    private fun elementMasteryVars(wantedElements: List<Characteristic>): Map<Characteristic, IntVar> =
        elementVars(
            wantedElements = wantedElements,
            genericCharacteristic = Characteristic.MASTERY_ELEMENTARY,
            targets = params.targetStats.masteryElementsWanted,
            randomByCount = MASTERY_RANDOM_BY_COUNT
        )

    private fun elementResistanceVars(wantedElements: List<Characteristic>): Map<Characteristic, IntVar> =
        elementVars(
            wantedElements = wantedElements,
            genericCharacteristic = Characteristic.RESISTANCE_ELEMENTARY,
            targets = params.targetStats.resistanceElementsWanted,
            randomByCount = RESISTANCE_RANDOM_BY_COUNT
        )

    /**
     * Actual per-element value of an elemental mastery/resistance with the generic "+all elements"
     * stat folded in — the way the scorers compute it (see `currentStatSpecificElements`). Falls
     * back to the plain [actualStat] for any non-elemental characteristic. Routing single-element
     * targets through here is what lets the solver see generic-mastery / generic-resistance gear
     * and the Major aptitudes (which carry [Characteristic.MASTERY_ELEMENTARY] /
     * [Characteristic.RESISTANCE_ELEMENTARY]); without it those contributions were invisible and
     * the matching items/aptitudes were never selected.
     */
    private fun foldedElementalStat(characteristic: Characteristic): IntVar =
        when (characteristic) {
            in ELEMENTARY_MASTERIES -> elementMasteryVars(listOf(characteristic)).getValue(characteristic)
            in ELEMENTARY_RESISTANCES -> elementResistanceVars(listOf(characteristic)).getValue(characteristic)
            else -> actualStat(characteristic)
        }

    /**
     * Actual value of a *required* (most-masteries) target. Same as [foldedElementalStat], except
     * the aggregate [Characteristic.RESISTANCE_ELEMENTARY] resolves to the **minimum** of the four
     * folded elemental resistances — the scorer stores that aggregate as the min of the elements
     * (see `computeCharacteristicsValues`), so the constraint must use the min too.
     */
    private fun requiredActualStat(characteristic: Characteristic): IntVar =
        if (characteristic == Characteristic.RESISTANCE_ELEMENTARY) {
            val elementResistances = elementResistanceVars(ELEMENTARY_RESISTANCES)
            val minVar = model.newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, "minElementResistance")
            model.addMinEquality(minVar, elementResistances.values.toTypedArray())
            minVar
        } else {
            foldedElementalStat(characteristic)
        }

    /**
     * HARD-constraint form of the required-target rule: forbid any build that misses an AP/MP/range/
     * resistance/… target outright (`actual ≥ target`), instead of taxing the objective with the shortfall
     * penalty ([WakfuBuildSolver.applyConstraintPenalty]). The hard-constraints-first max-damage solve uses
     * this: when the targets are REACHABLE it yields the TRUE constrained optimum under a PLAIN (un-penalized)
     * damage objective — the shape CP-SAT can actually prove — instead of the penalty product, whose foggy LP
     * relaxation traps the search at a sub-optimal build. It generalises to EVERY required stat (resistance
     * included, via [requiredActualStat]'s min-of-four for the aggregate) — where the damage certificate cannot.
     * A non-positive target is trivially met and skipped.
     *
     * Returns `staticallyInfeasible` (C2): true iff some required target exceeds its var's tracked reachable
     * ceiling (`tracker.of(actual).last`, a sound over-estimate) — i.e. NO build can meet it, so the hard model is
     * provably INFEASIBLE and the caller ([WakfuBuildSolver.optimize]) can skip the doomed CP-SAT solve and fall
     * straight to the soft leg. One-directional: a sound over-estimate can never falsely flag a feasible model
     * (an untracked var falls back to the loose `STAT_ABS_MAX` bound ⇒ never flags — e.g. the aggregate-resistance
     * `min`-of-four var, which is deliberately left inert; AP/MP/range/crit are tracked and do flag).
     */
    internal fun addRequiredTargetHardConstraints(): Boolean {
        val requiredTargets = params.targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() && it.target > 0 }
        var staticallyInfeasible = false
        for (targetStat in requiredTargets) {
            val actual = requiredActualStat(targetStat.characteristic)
            if (targetStat.target > tracker.of(actual).last) staticallyInfeasible = true
            model.addGreaterOrEqual(actual, targetStat.target.toLong())
        }
        return staticallyInfeasible
    }

    /**
     * Builds, for each requested element, an [IntVar] equal to that element's own stat plus the
     * generic "+all elements" stat ([genericCharacteristic]), with random-element lines greedily
     * assigned and percent skills applied — mirroring `computeCharacteristicsValues`. Works for
     * both elemental masteries and elemental resistances.
     */
    private fun elementVars(
        wantedElements: List<Characteristic>,
        genericCharacteristic: Characteristic,
        targets: Map<Characteristic, Int>,
        randomByCount: List<Pair<Characteristic, Int>>,
    ): Map<Characteristic, IntVar> {
        val key = genericCharacteristic to wantedElements.toList()
        return elementCache.getOrPut(key) {
            val genericBase = prePercentStat(genericCharacteristic)
            val baseElements =
                wantedElements.associateWith { element ->
                    val baseElement = prePercentStat(element)
                    // baseElement + genericBase: both are per-slot-tight, so their interval sum is too.
                    tSumNaive(
                        name = "pre_${element.name}",
                        terms = listOf(Term(baseElement, 1L), Term(genericBase, 1L)),
                        constant = 0L,
                        guardLo = -STAT_WITH_PERCENT_ABS_MAX,
                        guardHi = STAT_WITH_PERCENT_ABS_MAX
                    )
                }

            // For a monotone objective in the element vars the per-roll greedy ordering is an expensive,
            // provably-suboptimal heuristic, so we let CP-SAT pick the random assignment FREELY (only the
            // cardinality constraint) — the objective drives it to the true optimum — which also removes the
            // O(elements²) ordering that exploded the multi-element pool. The scorer mirrors this exactly.
            //  - most-masteries: the objective is a MIN (mastery always; aggregate resistance when
            //    RESISTANCE_ELEMENTARY is requested) ⇒ exact max-min scorer ([assignMaxMinMasteryRandomValues]).
            //  - precision: the objective is the capped sum (both mastery and resistance) ⇒ exact max-capped
            //    scorer ([assignMaxCappedMasteryRandomValues]).
            // max-damage stays greedy (the objective plays a single element ⇒ assignment is degenerate anyway).
            val freeAssignment =
                when (params.scoreComputationMode) {
                    ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                        genericCharacteristic == Characteristic.MASTERY_ELEMENTARY ||
                            (
                                genericCharacteristic == Characteristic.RESISTANCE_ELEMENTARY &&
                                    params.targetStats.any { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY }
                            )

                    ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT -> true
                    ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE -> false
                }
            val prePercentElements =
                applyGreedyRandom(wantedElements, baseElements, targets, buildRandomEntries(randomByCount), freeAssignment)

            prePercentElements.mapValues { (element, preElement) ->
                val percentTerms = skillTerms.percent[element].orEmpty()
                if (percentTerms.isEmpty()) {
                    preElement
                } else {
                    val percent = tSumNaive("pct_${element.name}", percentTerms, 0L, -PERCENT_ABS_MAX, PERCENT_ABS_MAX)
                    tPercent(preElement, percent, "stat_${element.name}")
                }
            }
        }
    }

    private fun applyGreedyRandom(
        wantedElements: List<Characteristic>,
        baseElements: Map<Characteristic, IntVar>,
        targets: Map<Characteristic, Int>,
        randomEntries: List<RandomEntry>,
        // When true (most-masteries min objective): pick the per-roll element assignment FREELY — only the
        // cardinality constraint (exactly `count` elements when equipped) — and let the maximized min drive
        // CP-SAT to the optimal assignment. This drops the O(elements²) reified ordering that forces the
        // suboptimal deficit-greedy and explodes the multi-element pool. When false: the original greedy.
        freeAssignment: Boolean,
    ): Map<Characteristic, IntVar> {
        if (wantedElements.isEmpty()) return baseElements
        if (targets.isEmpty()) return baseElements
        if (randomEntries.isEmpty()) return baseElements

        if (wantedElements.size == 1) {
            val element = wantedElements.single()
            val terms = mutableListOf(Term(baseElements.getValue(element), 1L))
            randomEntries
                .filter { min(it.count, 1) > 0 }
                .groupingBy { it.equipVar }
                .fold(0L) { acc, entry -> acc + entry.value.toLong() }
                .forEach { (equipVar, value) ->
                    if (value != 0L) terms.add(Term(equipVar, value))
                }
            return mapOf(
                element to
                    tSum(
                        "rand_${element.name}",
                        terms,
                        0L,
                        reachableSumDomain(terms, 0L),
                        -STAT_WITH_PERCENT_ABS_MAX,
                        STAT_WITH_PERCENT_ABS_MAX
                    )
            )
        }

        val priorityScale = 10L
        val elementCount = wantedElements.size

        var current = baseElements
        randomEntries.forEachIndexed { index, entry ->
            val effectiveCount = min(entry.count, elementCount)
            if (effectiveCount == 0) return@forEachIndexed

            if (effectiveCount == elementCount) {
                current =
                    wantedElements.associateWith { element ->
                        tSumNaive(
                            "rand_${index}_${element.name}",
                            listOf(Term(current.getValue(element), 1L), Term(entry.equipVar, entry.value.toLong())),
                            0L,
                            -STAT_WITH_PERCENT_ABS_MAX,
                            STAT_WITH_PERCENT_ABS_MAX
                        )
                    }
                return@forEachIndexed
            }

            val assigns =
                wantedElements.associateWith { element ->
                    val tag = "assign_${index}_${entry.nameSuffix}_${element.name}"
                    model.newBoolVar(tag).also { tracker.seed(it, 0L..1L, tag) }
                }

            // Cardinality: exactly `effectiveCount` of the wanted elements get this roll, and only when equipped.
            model.addEquality(
                LinearExpr.sum(assigns.values.toTypedArray()),
                LinearExpr.term(entry.equipVar, effectiveCount.toLong())
            )

            if (!freeAssignment) {
                // Force the chosen elements to be the highest-deficit ones (replicates the scorer's greedy).
                val priorities = wantedElements.mapIndexed { i, element -> element to (wantedElements.size - i) }.toMap()
                val adjustedDeficits =
                    wantedElements.associateWith { element ->
                        val currentValue = current.getValue(element)
                        val target = targets.getValue(element).toLong()
                        val deficit =
                            model.newIntVar(
                                -STAT_WITH_PERCENT_ABS_MAX - 10_000L,
                                STAT_WITH_PERCENT_ABS_MAX + 10_000L,
                                "def_${index}_${element.name}"
                            )
                        model.addEquality(
                            deficit,
                            LinearExpr
                                .newBuilder()
                                .add(target)
                                .addTerm(currentValue, -1)
                                .build()
                        )

                        val adjusted =
                            model.newIntVar(
                                -STAT_WITH_PERCENT_ABS_MAX * priorityScale,
                                STAT_WITH_PERCENT_ABS_MAX * priorityScale + priorityScale,
                                "adj_${index}_${element.name}"
                            )
                        model.addEquality(
                            adjusted,
                            LinearExpr
                                .newBuilder()
                                .addTerm(deficit, priorityScale)
                                .add(priorities.getValue(element).toLong())
                                .build()
                        )
                        adjusted
                    }

                for (i in wantedElements) {
                    for (j in wantedElements) {
                        if (i == j) continue
                        model
                            .addGreaterOrEqual(adjustedDeficits.getValue(i), adjustedDeficits.getValue(j))
                            .onlyEnforceIf(arrayOf(assigns.getValue(i), assigns.getValue(j).not()))
                    }
                }
            }

            current =
                wantedElements.associateWith { element ->
                    tSumNaive(
                        "rand_${index}_${element.name}",
                        listOf(Term(current.getValue(element), 1L), Term(assigns.getValue(element), entry.value.toLong())),
                        0L,
                        -STAT_WITH_PERCENT_ABS_MAX,
                        STAT_WITH_PERCENT_ABS_MAX
                    )
                }
        }

        return current
    }

    private fun buildRandomEntries(randomByCount: List<Pair<Characteristic, Int>>): List<RandomEntry> {
        // Grouped by element-count (1s, then 2s, then 3s) to preserve the original assignment order.
        val entriesByCount = randomByCount.map { mutableListOf<RandomEntry>() }

        for (equip in allEquips) {
            val equipVar = equipVars.getValue(equip)
            randomByCount.forEachIndexed { groupIndex, (randomCharacteristic, count) ->
                val value = equip.characteristics[randomCharacteristic] ?: 0
                if (value != 0) {
                    entriesByCount[groupIndex].add(RandomEntry(equipVar, value, count, "${count}_${equip.equipmentId}"))
                }
            }
        }

        return entriesByCount.flatten()
    }

    internal fun actualStat(char: Characteristic): IntVar =
        actualCache.getOrPut(char) {
            val pre = prePercentStat(char)
            val percentTerms = skillTerms.percent[char].orEmpty()
            if (percentTerms.isEmpty()) {
                pre
            } else {
                val percent = tSumNaive("pct_${char.name}", percentTerms, 0L, -PERCENT_ABS_MAX, PERCENT_ABS_MAX)
                tPercent(pre, percent, "stat_${char.name}")
            }
        }

    /** Item + rune + fixed-skill terms (and base) for [char], excluding sublimations. */
    private fun baseTermsFor(char: Characteristic): Pair<MutableList<Term>, Long> {
        val terms = mutableListOf<Term>()

        for (equip in allEquips) {
            val value = equip.valueFor(char)
            if (value != 0) {
                terms.add(Term(equipVars.getValue(equip), value.toLong()))
            }
        }

        // Runes contribute exactly like item stats: a constant value per socketed rune of this
        // stat (max rune level + WakForge doubling on favoured slots), times the per-item rune
        // count var. The socket-cap / equipped-only constraints live on those vars (createRuneModel).
        for ((equip, perStat) in runeModel.runeVars) {
            val runeVar = perStat[char] ?: continue
            // Rune level is capped by the carrier ITEM's level, not the character's (fix 36918746).
            val coefficient = runeModel.coefficientFor(equip, char)
            // Fold model: runeVar is a boolean PICK — one pick fills all `slots` sockets, so it
            // contributes slots·coeff. Count model: runeVar is the count and contributes coeff each.
            val multiplier = if (runeModel.singleTypePerItem) equip.maxShardSlots.toLong() else 1L
            if (coefficient != 0L) {
                terms.add(Term(runeVar, coefficient * multiplier))
            }
        }
        terms.addAll(runeModel.extraTerms[char].orEmpty())

        terms.addAll(skillTerms.fixed[char].orEmpty())
        val base = baseValues[char]?.toLong() ?: 0L
        return terms to base
    }

    internal fun prePercentTermsFor(char: Characteristic): Pair<MutableList<Term>, Long> {
        val (terms, base) = baseTermsFor(char)
        // Sublimation contributions fold in exactly like item/rune stats (FLAT always, STATIC
        // under a reified condition, CONVERSION moving value between two stats).
        terms.addAll(subTermsByStat[char].orEmpty())
        // Selected passives' flat stats fold in the same way (always-on constants).
        terms.addAll(passiveTermsByStat[char].orEmpty())
        // Featherweight-style ramps that TARGET this stat: a build-static clamp(perStep·(source−threshold), 0,
        // cap), gated on the sub. source ≠ target (MP vs DI), so actualStat(source) inside never re-enters here.
        for (sub in perStatStepSpecsByTarget[char].orEmpty()) {
            terms.add(Term(perStatStepGatedVar(sub), 1L))
        }
        return terms to base
    }

    private fun prePercentStat(char: Characteristic): IntVar =
        prePercentCache.getOrPut(char) {
            val (terms, base) = prePercentTermsFor(char)
            tSum("pre_${char.name}", terms, base, reachableSumDomain(terms, base), -STAT_ABS_MAX, STAT_ABS_MAX)
        }

    /**
     * Pre-sublimation actual value of [char] (item + rune + skill + base, NO subs) — the base for a
     * CONVERSION's `from` stat and the out-of-combat AP/MP/WP/crit caps. Start-of-combat **conditions** read
     * [preCombatStat] instead (which adds the permanent subs).
     */
    internal fun preSubStat(char: Characteristic): IntVar =
        preSubCache.getOrPut(char) {
            val (terms, base) = baseTermsFor(char)
            tSum("preSub_${char.name}", terms, base, reachableSumDomain(terms, base), -STAT_ABS_MAX, STAT_ABS_MAX)
        }

    /**
     * Pre-combat (character-sheet) value of [char]: `preSubStat` + the PERMANENT sublimation contributions
     * ([permanentSubTermsByStat]). This is exactly what a build-static start-of-combat condition is evaluated
     * against — a permanent +crit (Influence II) IS part of the pre-combat crit and so can push the build past
     * a CRIT_AT_MOST, whereas a start-of-combat / conditional +crit (Secondary Devastation II, Ambition) is
     * excluded (it is applied *at* combat start, after the condition is read; a conditional sub must never
     * feed its own condition). Acyclic: permanent terms are gated by the raw `subVar`, never a reified bool.
     */
    internal fun preCombatStat(char: Characteristic): IntVar =
        preCombatCache.getOrPut(char) {
            val (terms, base) = baseTermsFor(char)
            terms.addAll(permanentSubTermsByStat[char].orEmpty())
            tSum("preCombat_${char.name}", terms, base, reachableSumDomain(terms, base), -STAT_ABS_MAX, STAT_ABS_MAX)
        }

    /** Constant flat-stat contributions of the selected passives (see [resolvedPassives]). */
    private fun buildPassiveTerms(): Map<Characteristic, List<Term>> {
        val map = mutableMapOf<Characteristic, MutableList<Term>>()
        for (passive in resolvedPassives(params)) {
            for ((characteristic, value) in passive.flatStats) {
                map
                    .getOrPut(characteristic.foldedToUsableStat()) { mutableListOf() }
                    .add(Term(tConst(value.toLong()), 1L))
            }
        }
        return map
    }
}
