package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.IntVar
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.SkillCharacteristic
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.min

// MaxDamageCertifier — the two-tier max-damage optimality certifier extracted from StatBuilder (B1 of
// docs/code-review-followups.md). The methods are StatBuilder extension functions (they read its model /
// tracker / term maps by bare name via the receiver); the Frontier / DenseDp / CertWorld DP structures are
// certifier-only. Behaviour is unchanged — this is a move, not a redesign.

/**
 * A Pareto frontier of non-dominated (DI, graw, mp) triples — all maximized. mp is the selection's ITEM movement
 * points, carried so MP-sourced DI ramps (Featherweight) can be valued PER POINT: an item set maxing mastery is
 * not the item set maxing MP, and only the frontier sees both axes at once. mp defaults to 0 so 2-D callers
 * (ring/skill cells, debug prototypes) behave exactly as before.
 *
 * B3 storage: the points are packed into ONE geometrically-grown stride-3 `LongArray` (di, graw, mp per point)
 * instead of an `ArrayList<LongArray>` of 3-long arrays — ~24 bytes/point of payload with no per-point object
 * header/reference (~56–72 bytes before), so the live DP frontier shrinks ~2.5–3× (~1 GB → ~350–400 MB at lvl
 * 245). BYTE-IDENTICAL: [add] keeps the exact previous semantics — reject if dominated, drop points the newcomer
 * dominates (order-preserving compaction), append at the end — so the point SEQUENCE every reader sees, and the
 * per-stage point counts, are unchanged (locked by the oracle + fuzz + CERT_STATS tests).
 */
internal class Frontier {
    // Packed [di0, graw0, mp0, di1, graw1, mp1, ...]; only the first [size] points are live.
    private var data = EMPTY
    var size = 0
        private set

    fun di(i: Int): Long = data[i * 3]

    fun graw(i: Int): Long = data[i * 3 + 1]

    fun mp(i: Int): Long = data[i * 3 + 2]

    /** Iterate live points as (di, graw, mp) — inline so the hot convolution loops allocate nothing. */
    inline fun forEachPoint(action: (Long, Long, Long) -> Unit) {
        for (i in 0 until size) action(di(i), graw(i), mp(i))
    }

    fun add(
        di: Long,
        graw: Long,
        mp: Long = 0L,
    ) {
        // Perf instrumentation only (WAKFU_MAX_DAMAGE_CERT_STATS): count total add() calls, the ACTUAL
        // points visited by the dominance/compaction loops (the frontier investigation's work metric — the
        // rejection loop early-exits, so entry-size would overcount), rejected adds, and the largest live
        // frontier. The flag defaults false, so an unset env var leaves these never-taken branches — no
        // behavior change and no result change.
        if (statsEnabled) {
            statsAddCalls++
            if (size > statsMaxFrontier) statsMaxFrontier = size
        }
        // dominated by an existing point? (identical to the old `for (p in pts) if (p ≥ new) return`)
        // Frontier-investigation verdict (2026-07-09, SOLVER_PERFORMANCE §7): the rejection scan averages
        // ~21 of ~96 live points; newest-first and self-organizing-transpose orders moved the scan count
        // ±2–4% and the WALL TIME NOT AT ALL — the scan is cache-friendly sequential work that is not the
        // binding cost at this size. Keep the plain forward scan.
        for (i in 0 until size) {
            if (data[i * 3] >= di && data[i * 3 + 1] >= graw && data[i * 3 + 2] >= mp) {
                if (statsEnabled) {
                    statsPointsScanned += i + 1
                    statsRejected++
                }
                return
            }
        }
        if (statsEnabled) statsPointsScanned += 2L * size // full rejection scan + the compaction scan below
        // drop every point the newcomer dominates, compacting in place — preserves the survivors' order exactly
        // as the old `pts.removeAll { new ≥ it }` did.
        var w = 0
        for (r in 0 until size) {
            val a = data[r * 3]
            val b = data[r * 3 + 1]
            val c = data[r * 3 + 2]
            if (a <= di && b <= graw && c <= mp) continue
            if (w != r) {
                data[w * 3] = a
                data[w * 3 + 1] = b
                data[w * 3 + 2] = c
            }
            w++
        }
        size = w
        // append at the end (grow geometrically)
        if ((size + 1) * 3 > data.size) data = data.copyOf(maxOf(6, data.size * 2))
        data[size * 3] = di
        data[size * 3 + 1] = graw
        data[size * 3 + 2] = mp
        size++
    }

    fun copy(): Frontier {
        if (statsEnabled) {
            statsCopies++
            statsCopiedPoints += size
        }
        val f = Frontier()
        f.data = data.copyOf()
        f.size = size
        return f
    }

    /** Materialize live points as `[di, graw, mp]` arrays — diagnostics/explain only (allocates; never on the hot path). */
    fun toArrays(): List<LongArray> = List(size) { longArrayOf(di(it), graw(it), mp(it)) }

    companion object {
        private val EMPTY = LongArray(0)

        // Perf instrumentation only. [statsEnabled] is flipped on by the exact pass when
        // WAKFU_MAX_DAMAGE_CERT_STATS=1 and stays false otherwise; [statsAddCalls] is reset per pass.
        @JvmStatic var statsEnabled = false

        @JvmStatic var statsAddCalls = 0L

        // ACTUAL points visited by the dominance/compaction loops (fast-tier investigation work metric).
        @JvmStatic var statsPointsScanned = 0L

        // add() calls rejected by the dominance scan (the newcomer was dominated).
        @JvmStatic var statsRejected = 0L

        @JvmStatic var statsMaxFrontier = 0

        // copy() calls + total points copied (the per-stage carry-forward cost — the other fast-tier suspect).
        @JvmStatic var statsCopies = 0L

        @JvmStatic var statsCopiedPoints = 0L
    }
}

/**
 * Certifier tuning seams (test-only toggles + instrumentation counters). Production never touches these;
 * tests flip [cLoopPruneEnabled] in a try/finally to A/B the exact pass's crit-step pruning against the
 * plain ascending loop (the two must be value- AND provenance-identical), and read [cPruneSkippedForTest]
 * to assert the pruning actually FIRES.
 */
internal object CertifierTuning {
    /** Exact-pass c-loop pruning (see [certifyMaxPerHitAtApPass]). On in production; tests may disable. */
    @Volatile
    var cLoopPruneEnabled = true

    /** Total crit-steps skipped by the pruning across all exact passes (thread-safe; tests reset + assert > 0). */
    val cPruneSkippedForTest =
        java.util.concurrent.atomic
            .AtomicLong()

    /** Tier-1.5 segment skip below the incumbent (see [certifyCellsTier15]). On in production; tests may disable. */
    @Volatile
    var tier15SegmentSkipEnabled = true

    /** Total step-1 segments skipped by tier-1.5 across all passes (thread-safe; tests reset + assert > 0). */
    val tier15SegmentsSkippedForTest =
        java.util.concurrent.atomic
            .AtomicLong()

    /**
     * A/B seam for the FAST-pass harvest coordinate index. Off by default, so production keeps the
     * reference `all cells × all crit steps` scan until the experiment is measured and accepted.
     * The environment switch is intentionally experimental; flipping the production default would be
     * a certifier change and therefore requires a [WakfuBuildSolver.CERTIFIER_VERSION] bump.
     */
    @Volatile
    var indexedFastHarvestEnabled = System.getenv("WAKFU_MAX_DAMAGE_CERT_INDEXED_HARVEST") == "1"

    /** Number of indexed `(state, AP-cell, crit-step)` coordinates actually handed to the harvest. */
    val indexedFastHarvestCoordinatesForTest =
        java.util.concurrent.atomic
            .AtomicLong()
}

/**
 * Direct AP-cell index for one FAST-pass DP state's pre-sub AP coordinate.
 *
 * Returned pairs are packed as `[cell0, minimumGapSubs0, cell1, minimumGapSubs1, ...]`, in the same
 * ascending-cell order as the reference scan. Cells outside the exact arithmetic band, or whose AP
 * gap cannot be covered by the available pure-AP budget, are omitted. This helper is internal so a
 * focused exhaustive test can lock the indexed enumeration byte-for-byte against the reference loop.
 */
internal fun indexedFastHarvestApCoordinates(
    stateAp: Int,
    cellCount: Int,
    apConst: Long,
    minSubAp: Int,
    maxSubAp: Int,
    apPlusSorted: List<Long>,
    apMinusSorted: List<Long>,
): IntArray {
    if (cellCount <= 0) return IntArray(0)
    val first = maxOf(0L, apConst + stateAp + minSubAp).coerceAtMost(cellCount.toLong())
    val last = minOf(cellCount.toLong() - 1L, apConst + stateAp + maxSubAp)
    if (first > last) return IntArray(0)

    val packed = IntArray(((last - first + 1L) * 2L).toInt())
    var size = 0
    for (cellLong in first..last) {
        val gap = cellLong - apConst - stateAp
        val cover =
            if (gap >= 0L) {
                minimumBudgetUnitsToCover(gap, apPlusSorted)
            } else {
                minimumBudgetUnitsToCover(-gap, apMinusSorted)
            }
        if (cover == Int.MAX_VALUE) continue
        packed[size++] = cellLong.toInt()
        packed[size++] = cover
    }
    return if (size == packed.size) packed else packed.copyOf(size)
}

/**
 * Direct crit-step index for one FAST-pass DP state's pre-sub crit coordinate within one c segment.
 * The packed output is `[c0, minimumGapSubs0, ...]` in the reference loop's ascending-c order.
 * `c == 0` deliberately keeps its special clamp band and zero gap charge.
 */
internal fun indexedFastHarvestCritCoordinates(
    stateCrit: Int,
    cLow: Int,
    cHigh: Int,
    critConst: Long,
    critOff: Int,
    maxSubCrit: Long,
    csWorldCrit: Long,
    forcedStartCritTotal: Long,
    critBudgetSortedForCharge: List<Long>,
): IntArray {
    if (cLow > cHigh) return IntArray(0)
    val zeroIncluded =
        cLow <= 0 &&
            cHigh >= 0 &&
            stateCrit >= -critOff &&
            stateCrit <= -critConst
    val firstPositive = maxOf(cLow.toLong(), 1L, stateCrit + critConst)
    val lastPositive = minOf(cHigh.toLong(), stateCrit + critConst + maxSubCrit)
    val positiveCount = if (firstPositive <= lastPositive) (lastPositive - firstPositive + 1L).toInt() else 0
    val packed = IntArray(2 * (positiveCount + if (zeroIncluded) 1 else 0))
    var size = 0
    if (zeroIncluded) {
        packed[size++] = 0
        packed[size++] = 0
    }
    if (positiveCount > 0) {
        for (cLong in firstPositive..lastPositive) {
            val gap = cLong - critConst - stateCrit - csWorldCrit - forcedStartCritTotal
            val cover = minimumBudgetUnitsToCover(gap, critBudgetSortedForCharge)
            if (cover == Int.MAX_VALUE) continue
            packed[size++] = cLong.toInt()
            packed[size++] = cover
        }
    }
    return if (size == packed.size) packed else packed.copyOf(size)
}

private fun minimumBudgetUnitsToCover(
    gap: Long,
    sortedDesc: List<Long>,
): Int {
    if (gap <= 0L) return 0
    var covered = 0L
    for ((index, value) in sortedDesc.withIndex()) {
        covered += value
        if (covered >= gap) return index + 1
    }
    return Int.MAX_VALUE
}

/**
 * Dense DP state store for the exact certifier: the packed state key (see `key(...)`) is already a
 * mixed-radix index into the box `apDim × critDim × 2 × 2 × (subCap+1)`, so it addresses a flat
 * `Frontier?` array directly — no hashing, no `Long` boxing. Occupied slots are tracked in a
 * geometrically-grown `liveKeys` list so iteration and `clear()` touch only live entries (the box is
 * mostly empty). Two instances are double-buffered per pass (allocated once, sized for the largest
 * crit step) and swapped each DP stage, so no per-stage allocation happens at all.
 */
internal class DenseDp(
    cap: Int,
) {
    @JvmField val slots = arrayOfNulls<Frontier>(cap)

    @JvmField var liveKeys = IntArray(64)

    @JvmField var liveCount = 0

    /** Empty the store, touching only the slots that were actually written. */
    fun clear() {
        for (i in 0 until liveCount) slots[liveKeys[i]] = null
        liveCount = 0
    }

    fun get(k: Int): Frontier? = slots[k]

    fun getOrPut(k: Int): Frontier {
        val existing = slots[k]
        if (existing != null) return existing
        val f = Frontier()
        slots[k] = f
        if (liveCount == liveKeys.size) liveKeys = liveKeys.copyOf(liveKeys.size * 2)
        liveKeys[liveCount++] = k
        return f
    }

    /** Assign a frontier to a slot (the carry-forward "skip this stage" option). Registers first touch. */
    fun put(
        k: Int,
        f: Frontier,
    ) {
        if (slots[k] == null) {
            if (liveCount == liveKeys.size) liveKeys = liveKeys.copyOf(liveKeys.size * 2)
            liveKeys[liveCount++] = k
        }
        slots[k] = f
    }
}

/**
 * EXACT certifier (stage 1: subs + crit step-1 + (DI,graw) frontier; weapons as separate slots ⇒ SOUND
 * upper bound, slightly loose pending the weapon/rarity tightening). Returns the exact-or-over max perHit
 * = max D·Graw over builds with total AP == [apTarget]. Long.MAX_VALUE ⇒ cannot certify this scenario
 * (percent skills present) — caller falls back to CP-SAT. Single-element only.
 */
internal fun StatBuilder.certifyMaxPerHitAtAp(
    scenario: DamageScenario,
    apTarget: Int,
    // E8 item A (perf): when non-null (size ≥ 2), receives `[worldIndex, winningC]` of the argmax world — the
    // provenance the E8 fast-path later replays as a single explain pass. Untouched on a bail (MAX_VALUE return);
    // no effect on the returned value. Ties resolve to the FIRST (lowest-index) argmax world (deterministic).
    provOut: IntArray? = null,
    // Two-tier speed: tier-1.5's per-(world-index) per-crit-step harvest rows for THIS cell (indices align
    // with [certifierWorlds]; null row / null array ⇒ that world's exact c-loop runs unpruned).
    ubByWorld: Array<LongArray?>? = null,
): Long {
    // The world split (conversion / Critical-Secret / weapon-axis) lives in [certifierWorlds] — the
    // single source of truth shared with the fast pass, the parallel exact tier and the provenance
    // path. Exact `max` over worlds; ANY world bail bails the cell (the bail conditions are
    // per-scenario shapes, not per-cell).
    val worlds = certifierWorlds(scenario) ?: return Long.MAX_VALUE
    var best = 0L
    var bestWorld = -1
    var bestC = -1
    for ((wi, w) in worlds.withIndex()) {
        val cOut = if (provOut != null) IntArray(1) { -1 } else null
        val v =
            certifyMaxPerHitAtApPass(
                scenario,
                apTarget,
                convTaken = w.conv,
                critSecret = w.cs,
                critSecretExcluded = w.csExcluded,
                weaponsRestricted = w.wr,
                winningCOut = cOut,
                cPruneUbIn = ubByWorld?.getOrNull(wi)
            )
        if (v == Long.MAX_VALUE) return Long.MAX_VALUE
        if (bestWorld == -1 || v > best) {
            best = v
            bestWorld = wi
            bestC = cOut?.get(0) ?: -1
        }
    }
    provOut?.let {
        it[0] = bestWorld
        it[1] = bestC
    }
    return best
}

/**
 * One certifier "world": a force-taken CONVERSION / CRITICAL_SECRET split and a weapon-axis
 * restriction. See [certifyMaxPerHitAtAp] for what each split covers.
 */
internal class CertWorld(
    val conv: Sublimation?,
    val cs: Sublimation?,
    val csExcluded: Sublimation?,
    val wr: Boolean,
)

/**
 * The certifier world enumeration — the SINGLE source of truth consumed by [certifyMaxPerHitAtAp]
 * (serial exact), [certifyAllCellsFast] (fast tier), [exactForCells] (parallel exact tier) and
 * [certifyExplainAtAp] (provenance). Returns `null` when the sub shape is unsupported, so every
 * caller bails to CP-SAT identically. The splits:
 *
 * - CONVERSION (Unraveling): a TWO-WORLD max — NOT taken (its `moved` terms dropped; also covers
 *   taken-but-moved-0 builds, whose stats equal their not-taken twin's with strictly less sub
 *   capacity) vs FORCE-taken (conversion applied analytically to every PRE-SUB critical-mastery
 *   source — base + items + runes + skills, matching [preSubStat]). Only the single known shape is
 *   supported (one conversion sub, MASTERY_CRITICAL → a mastery folding into the scenario element,
 *   percent ≤ 100); anything else bails.
 * - CRITICAL_MASTERY_AT_MOST ≤ 0 (Critical Secret, +30% crit iff the sheet has NO crit mastery):
 *   crediting its crit in the shared budget would let a state bank the +30 crit AND thousands of
 *   critM in graw — contradictory. World C force-takes it: every PRE-COMBAT critM source zeroed,
 *   its crit joins the budget, and (EPIC) it consumes the epic-sub slot; the other worlds exclude
 *   it from the budget ([CertWorld.csExcluded]) — sound because world C covers its takers. Only the
 *   single-EPIC-sub shape is split; other shapes keep it in the budget (sound, just looser).
 * - NO_OFFHAND_OR_TWO_HANDED (Light Weapons Expert II): splits the WEAPON axis. World W
 *   ([CertWorld.wr]) force-allows those subs but limits the weapon slot to {empty, one-handed};
 *   the normal worlds exclude the subs but keep weapons free. Orthogonal to the epic splits, so it
 *   doubles each world.
 */
internal fun StatBuilder.certifierWorlds(scenario: DamageScenario): List<CertWorld>? {
    val conversionSubs = subModel.subVars.keys.filter { it.kind == SublimationKind.CONVERSION }
    if (conversionSubs.size > 1) return null
    val convSub = conversionSubs.singleOrNull()
    if (convSub != null) {
        val conv = convSub.conversion
        if (conv == null ||
            conv.from != Characteristic.MASTERY_CRITICAL ||
            (conv.to != Characteristic.MASTERY_ELEMENTARY && conv.to != scenario.element.masteryCharacteristic) ||
            conv.percent !in 1..100 ||
            convSub.zeroesElementalMastery
        ) {
            return null
        }
    }
    val critSecretLike =
        subModel.subVars.keys.filter {
            it.condition?.type == SublimationConditionType.CRITICAL_MASTERY_AT_MOST && (it.condition?.value ?: 0) <= 0
        }
    val critSecretSub = critSecretLike.singleOrNull()?.takeIf { it.rarity == SublimationRarity.EPIC }
    val csExcluded = critSecretSub
    val hasNoWeaponSubs = subModel.subVars.keys.any { it.condition?.type == SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED }
    val weaponWorlds = if (hasNoWeaponSubs) listOf(false, true) else listOf(false)

    // ---- P5.3 Inc 3: FORCED conversion / Critical-Secret / epic-relic world filtering ------------
    // A forced world-special stays EQUIPPED in every real build. Worlds where it is NOT taken model
    // it INERT — its slot is charged by the plain-forced machinery of the pass (it lands in
    // `forcedPlainSubs` there) with ZERO credit, which is exact for condition-broken / moved-0
    // builds; its own world models it ACTIVE. Coverage bails:
    // - a forced CS-like sub that is not THE single-EPIC [critSecretSub] has no world to honor it;
    // - both specials forced (both pinned on ONE epic slot ⇒ CP-SAT infeasible), or one forced with
    //   the other present in a NON-both-EPIC pairing (a both-ACTIVE build would be covered by no
    //   world; both-EPIC pairs can never both be equipped — Σ epicSub ≤ 1 — so there the split is
    //   exact and the other special's ACTIVE world is dropped);
    // - a forced special whose ≤1 epic/relic slot is already held by a forced plain sub of the same
    //   rarity (both pinned ⇒ CP-SAT infeasible).
    val forcedCsLike = critSecretLike.filter { it in subModel.forced }
    if (forcedCsLike.isNotEmpty() && (forcedCsLike.size > 1 || forcedCsLike.single() != critSecretSub)) return null
    val forcedConv = convSub?.takeIf { it in subModel.forced }
    val forcedCs = critSecretSub?.takeIf { it in subModel.forced }
    if (forcedConv != null && forcedCs != null) return null
    val bothEpic =
        convSub?.rarity == SublimationRarity.EPIC && critSecretSub?.rarity == SublimationRarity.EPIC
    if ((forcedConv != null || forcedCs != null) && convSub != null && critSecretSub != null && !bothEpic) return null
    // Forced PLAIN (non-special) epic/relic subs occupy THE ≤1 epic/relic sub slot for their rarity.
    val forcedPlain = subModel.forced.filter { it != convSub && it != critSecretSub }
    if (forcedPlain.count { it.rarity == SublimationRarity.EPIC } > 1) return null
    if (forcedPlain.count { it.rarity == SublimationRarity.RELIC } > 1) return null

    fun specialAllowed(special: Sublimation): Boolean =
        special.rarity == SublimationRarity.NORMAL ||
            forcedPlain.none { it.rarity == special.rarity }
    if (forcedConv != null && !specialAllowed(forcedConv)) return null
    if (forcedCs != null && !specialAllowed(forcedCs)) return null

    val worlds = mutableListOf<CertWorld>()
    for (wr in weaponWorlds) {
        worlds += CertWorld(null, null, csExcluded, wr)
        // A special's ACTIVE world is impossible when the OTHER special is forced (both-EPIC — the
        // forced one holds the shared epic slot) or a forced plain sub holds its rarity slot.
        if (convSub != null && forcedCs == null && specialAllowed(convSub)) worlds += CertWorld(convSub, null, csExcluded, wr)
        if (critSecretSub != null && forcedConv == null && specialAllowed(critSecretSub)) worlds += CertWorld(null, critSecretSub, null, wr)
    }
    return worlds
}

/**
 * FAST tier-1 pass (P2): a per-cell SOUND UPPER BOUND on maxPerHit for every AP cell `0 until
 * [cellCount]`, `Long.MAX_VALUE` where the shape is unsupported (same bail set as the exact pass).
 * The production goal is one shared 4-D-frontier DP that harvests every cell at once (see
 * `docs/CERTIFICATE_PROD_PLAN.md` §P2); until that lands this delegates to the exact per-cell pass, so
 * it is trivially sound (`fast == exact`) and the `fast ≥ exact` lock is wired and green. Replacing the
 * body must keep every returned value ≥ the exact pass — an under-count here voids the production proof.
 */
internal fun StatBuilder.certifyAllCellsFast(
    scenario: DamageScenario,
    cellCount: Int,
    threads: Int = 1,
    // Floor speed: when non-null (sized `certifierWorlds(scenario).size`), receives each world's
    // per-(cell, crit-step) step-8 harvest ([fastPerCellCOutHolder]) — the tier-1.5 segment-skip bounds.
    // Indices align with `certifierWorlds`; a slot stays null on a bail (the ledger bails anyway).
    perCellCByWorldOut: Array<Array<LongArray>?>? = null,
    // Re-read BEFORE EACH WORLD when set (overrides [threads]): the warm-up runs this pass at 1 thread
    // while the search owns the cores, but a short search ends mid-pass — without re-reading, the
    // remaining worlds stayed serial and the proof trailed the search by the whole single-threaded pass.
    threadsProvider: (() -> Int)? = null,
): LongArray {
    if (cellCount <= 0) return LongArray(0)
    val bailed = LongArray(cellCount) { Long.MAX_VALUE }
    // Same world determination as [certifyMaxPerHitAtAp] — the fast pass runs each world once (all cells)
    // and takes the per-cell max. A shape-level bail (null worlds) bails the whole ledger (as the exact
    // per-cell pass does), since the bail conditions are per-scenario, not per-cell.
    val worlds = certifierWorlds(scenario) ?: return bailed
    val maxCell = cellCount - 1

    // One task = one world's fast pass over ALL cells. PURE: reads the shared (warm) StatBuilder caches
    // and allocates its own DP, so once the caches are warm the tasks parallelize safely (P3.1).
    // Per-world timing (WAKFU_MAX_DAMAGE_CERT_TIMING=1): the world-prefix-factoring question — do
    // multiple worlds delay the badge beyond the search? — needs the per-world cost distribution,
    // which the aggregate CERT_TIMING fastMs line cannot show. One stderr line per world.
    val worldTimingEnabled = System.getenv("WAKFU_MAX_DAMAGE_CERT_TIMING") == "1"

    fun runWorld(
        wi: Int,
        w: CertWorld,
    ): LongArray? {
        val tw = if (worldTimingEnabled) System.nanoTime() else 0L
        val out = LongArray(cellCount) { 0L }
        val perCellCHolder = if (perCellCByWorldOut != null) arrayOfNulls<Array<LongArray>>(1) else null
        val r =
            certifyMaxPerHitAtApPass(
                scenario,
                maxCell,
                convTaken = w.conv,
                critSecret = w.cs,
                critSecretExcluded = w.csExcluded,
                weaponsRestricted = w.wr,
                fastAllCellsOut = out,
                fastCellCount = cellCount,
                fastPerCellCOutHolder = perCellCHolder
            )
        if (worldTimingEnabled) {
            System.err.println(
                "CERT_WORLD_TIMING world=$wi/${worlds.size} conv=${w.conv?.name?.fr} cs=${w.cs?.name?.fr} " +
                    "wr=${w.wr} bailed=${r == Long.MAX_VALUE} ms=${(System.nanoTime() - tw) / 1_000_000}"
            )
        }
        if (r == Long.MAX_VALUE) return null // shape-level bail
        if (perCellCByWorldOut != null && perCellCHolder != null && wi < perCellCByWorldOut.size) {
            perCellCByWorldOut[wi] = perCellCHolder[0]
        }
        return out
    }

    fun currentThreads(): Int = threadsProvider?.invoke() ?: threads

    // Warm-once (P3.1): world[0] ALWAYS runs serially so every lazy cache key the other worlds READ is
    // populated single-threaded (CpModel var creation is not thread-safe). All worlds touch the same,
    // apTarget-independent cache keys, so the rest are then pure reads and can run in parallel. The
    // thread count is re-read before each remaining world: the moment it rises above 1 (the search
    // ended), ALL remaining worlds fan out at that count — values are an order-independent max, so the
    // switch changes nothing but wall clock.
    val worldOuts = ArrayList<LongArray?>(worlds.size)
    worldOuts += (runWorld(0, worlds[0]) ?: return bailed)
    var nextWorld = 1
    while (nextWorld < worlds.size) {
        val t = currentThreads()
        if (t <= 1) {
            // Serial: short-circuit the moment any world bails (the whole ledger bails anyway).
            worldOuts += (runWorld(nextWorld, worlds[nextWorld]) ?: return bailed)
            nextWorld++
        } else {
            val remaining = (nextWorld until worlds.size).toList()
            val pool = Executors.newFixedThreadPool(min(t, remaining.size))
            try {
                worldOuts +=
                    pool
                        .invokeAll(remaining.map { wi -> Callable { runWorld(wi, worlds[wi]) } })
                        .map { it.get() }
            } finally {
                pool.shutdown()
            }
            nextWorld = worlds.size
        }
    }
    if (worldOuts.any { it == null }) return bailed
    val result = LongArray(cellCount) { 0L }
    for (out in worldOuts) {
        for (a in 0 until cellCount) result[a] = maxOf(result[a], out!![a])
    }
    return result
}

/**
 * The EXACT tier-2 max-per-hit for each of [cells] (raw units, `Long.MAX_VALUE` = the exact pass bailed
 * on that cell). Serial when [threads] ≤ 1; otherwise a parallel (cell × world) work queue over the
 * warm caches. Each per-cell result is the `max` over worlds, or `Long.MAX_VALUE` if ANY world bails —
 * identical to [certifyMaxPerHitAtAp], just fanned out. The caches are already warm (the fast pass ran
 * every world), so each task is a pure read + a locally allocated DP ⇒ thread-safe and deterministic
 * (the merge is an order-independent max).
 */
internal fun StatBuilder.exactForCells(
    scenario: DamageScenario,
    cells: List<Int>,
    threads: Int,
    // E8 item A (perf): when non-null, receives the winning (world, crit-step) of each NON-BAILED cell — the
    // argmax over worlds, so the E8 fast-path can replay that one (world, c) instead of re-scanning them all.
    // Bailed cells are omitted (no sound provenance). No effect on the returned bounds.
    provOut: MutableMap<Int, CellProvenance>? = null,
    // Two-tier speed: tier-1.5's FREE per-(cell, world-index) per-crit-step rows ([Tier15Result.perCByCell])
    // — each world's exact c-loop prunes against its own row. Missing cell/world ⇒ that loop runs unpruned.
    ubByWorldCell: Map<Int, Array<LongArray?>>? = null,
): Map<Int, Long> {
    if (cells.isEmpty()) return emptyMap()
    val worlds = if (threads <= 1) null else certifierWorlds(scenario)
    if (worlds == null) {
        // Serial (or unsupported shape — which should not occur once the fast pass has succeeded).
        val out = LinkedHashMap<Int, Long>()
        for (a in cells) {
            val pv = if (provOut != null) IntArray(2) { -1 } else null
            val v = certifyMaxPerHitAtAp(scenario, a, provOut = pv, ubByWorld = ubByWorldCell?.get(a))
            out[a] = v
            if (provOut != null && pv != null && v != Long.MAX_VALUE && pv[0] >= 0) provOut[a] = CellProvenance(pv[0], pv[1])
        }
        return out
    }

    data class Task(
        val cell: Int,
        val worldIndex: Int,
        val world: CertWorld,
    )

    data class TaskResult(
        val cell: Int,
        val worldIndex: Int,
        val value: Long,
        val winningC: Int,
    )
    val tasks = cells.flatMap { a -> worlds.withIndex().map { (wi, w) -> Task(a, wi, w) } }
    val pool = Executors.newFixedThreadPool(min(threads, tasks.size))
    val results: List<TaskResult> =
        try {
            pool
                .invokeAll(
                    tasks.map { t ->
                        Callable {
                            val cOut = if (provOut != null) IntArray(1) { -1 } else null
                            val v =
                                certifyMaxPerHitAtApPass(
                                    scenario,
                                    t.cell,
                                    convTaken = t.world.conv,
                                    critSecret = t.world.cs,
                                    critSecretExcluded = t.world.csExcluded,
                                    weaponsRestricted = t.world.wr,
                                    winningCOut = cOut,
                                    cPruneUbIn = ubByWorldCell?.get(t.cell)?.getOrNull(t.worldIndex)
                                )
                            TaskResult(t.cell, t.worldIndex, v, cOut?.get(0) ?: -1)
                        }
                    }
                ).map { it.get() }
        } finally {
            pool.shutdown()
        }
    val out = LinkedHashMap<Int, Long>()
    for (a in cells) out[a] = 0L
    val bailedCells = HashSet<Int>()
    for (r in results) {
        if (r.value == Long.MAX_VALUE) bailedCells += r.cell
    }
    val nonBailed = results.filter { it.cell !in bailedCells }
    for (r in nonBailed) out[r.cell] = maxOf(out.getValue(r.cell), r.value)
    if (provOut != null) {
        // Per cell, the (world, c) of the argmax over worlds. groupBy + maxByOrNull preserve task order, so ties
        // resolve to the FIRST (lowest-index) argmax world — deterministic, matching the serial path.
        for ((cell, group) in nonBailed.groupBy { it.cell }) {
            val argmax = group.maxByOrNull { it.value } ?: continue
            if (argmax.worldIndex >= 0) provOut[cell] = CellProvenance(argmax.worldIndex, argmax.winningC)
        }
    }
    for (a in bailedCells) out[a] = Long.MAX_VALUE
    return out
}

/**
 * B7 — the TIER-1.5 sharpened fast bound for each of [cells] (raw units; `Long.MAX_VALUE` = bailed). Same
 * machinery as the tier-1 fast pass ([certifyAllCellsFast]) but run per cell with the c-grid step pinned to 1
 * (so the graw fold is EXACT per crit level instead of folded at a coarse segment top) and the AP ceiling
 * pinned to that single cell (`apTarget == fastCellCount - 1 == cell`, so the DP explores only that cell's AP
 * band). Every remaining simplification (rings as two independent slots, per-axis skill maxima) stays a SOUND
 * OVER-COUNT, so per cell `fast ≥ tier1.5 ≥ exact` (the fuzz lock). It sits between elimination and the
 * ~minutes-per-cell exact tier: a survivor whose (still sound, tighter) tier-1.5 bound already falls
 * `≤ incumbent` is DONE without paying the exact DP. Serial when [threads] ≤ 1; otherwise a parallel
 * (cell × world) queue over the warm caches — the max over worlds, or `Long.MAX_VALUE` if ANY world bails
 * (same all-or-nothing as the exact/fast passes). The caches are already warm (the tier-1 fast pass ran every
 * world before elimination), so each task is a pure read + a locally allocated DP ⇒ thread-safe, deterministic.
 */
internal fun StatBuilder.certifyCellsTier15(
    scenario: DamageScenario,
    cells: List<Int>,
    threads: Int,
    // Floor speed: tier-1's per-world per-(cell, crit-step) step-8 harvest (indices align with
    // `certifierWorlds`) + the per-cell RAW threshold at-or-below which a value cannot exceed the
    // incumbent once scaled. A (cell, world) step-1 pass then SKIPS every segment whose step-8 bounds
    // sit `≤` the threshold — the clearing decision is provably identical (step-1 ≤ step-8 per c), the
    // recorded bound stays sound (the step-8 value carries), only the DP count changes. Null ⇒ no skip.
    skipUbByWorld: Array<Array<LongArray>?>? = null,
    skipBelowRawByCell: Map<Int, Long>? = null,
): Tier15Result {
    if (cells.isEmpty()) return Tier15Result(emptyMap(), emptyMap())
    val worlds = certifierWorlds(scenario) ?: return Tier15Result(cells.associateWith { Long.MAX_VALUE }, emptyMap())
    val skipEnabled = skipUbByWorld != null && skipBelowRawByCell != null && CertifierTuning.tier15SegmentSkipEnabled

    // (value, per-crit-step harvest) of one (cell, world) step-1 pass. The per-c row is the FREE
    // by-product the exact tier later consumes as its c-loop pruning bound ([cPruneUbIn]) — sound for
    // exactly this (cell, world) pair (`fast ≥ exact` per c at step 1). Null row on a world bail.
    fun cellWorld(
        cell: Int,
        wi: Int,
        w: CertWorld,
    ): Pair<Long, LongArray?> {
        val out = LongArray(cell + 1) { 0L }
        val perCHolder = arrayOfNulls<LongArray>(1)
        val r =
            certifyMaxPerHitAtApPass(
                scenario,
                cell, // apTarget = the single cell = the loosest (and only harvested) cell
                convTaken = w.conv,
                critSecret = w.cs,
                critSecretExcluded = w.csExcluded,
                weaponsRestricted = w.wr,
                fastAllCellsOut = out,
                fastCellCount = cell + 1,
                fastCSegmentStep = 1,
                fastPerCOutHolder = perCHolder,
                fastSegmentSkipUb = if (skipEnabled) skipUbByWorld?.getOrNull(wi)?.getOrNull(cell) else null,
                fastSkipBelowRaw = if (skipEnabled) (skipBelowRawByCell?.get(cell) ?: Long.MIN_VALUE) else Long.MIN_VALUE
            )
        return if (r == Long.MAX_VALUE) Long.MAX_VALUE to null else out[cell] to perCHolder[0]
    }

    val perCByCell = LinkedHashMap<Int, Array<LongArray?>>()
    if (threads <= 1) {
        val out = LinkedHashMap<Int, Long>()
        for (a in cells) {
            var best = 0L
            var bail = false
            val rows = arrayOfNulls<LongArray>(worlds.size)
            for ((wi, w) in worlds.withIndex()) {
                val (v, row) = cellWorld(a, wi, w)
                if (v == Long.MAX_VALUE) {
                    bail = true
                    break
                }
                rows[wi] = row
                best = maxOf(best, v)
            }
            out[a] = if (bail) Long.MAX_VALUE else best
            if (!bail) perCByCell[a] = rows
        }
        return Tier15Result(out, perCByCell)
    }

    data class Task(
        val cell: Int,
        val worldIndex: Int,
        val world: CertWorld,
    )
    val tasks = cells.flatMap { a -> worlds.withIndex().map { (wi, w) -> Task(a, wi, w) } }
    val pool = Executors.newFixedThreadPool(min(threads, tasks.size))
    val results: List<Triple<Int, Int, Pair<Long, LongArray?>>> =
        try {
            pool.invokeAll(tasks.map { t -> Callable { Triple(t.cell, t.worldIndex, cellWorld(t.cell, t.worldIndex, t.world)) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
    val out = LinkedHashMap<Int, Long>()
    for (a in cells) out[a] = 0L
    val bailedCells = HashSet<Int>()
    for ((a, _, vr) in results) if (vr.first == Long.MAX_VALUE) bailedCells += a
    for ((a, wi, vr) in results) {
        if (a in bailedCells) continue
        out[a] = maxOf(out.getValue(a), vr.first)
        perCByCell.getOrPut(a) { arrayOfNulls(worlds.size) }[wi] = vr.second
    }
    for (a in bailedCells) out[a] = Long.MAX_VALUE
    return Tier15Result(out, perCByCell)
}

/**
 * B7 tier-1.5 output: [values] = the sharpened per-cell bound (raw units, `Long.MAX_VALUE` = bailed), and
 * [perCByCell] = the FREE per-(cell, world-index) per-crit-step harvest rows (world indices align with
 * `certifierWorlds`; a null row = that world's pass produced none). The exact tier consumes a row as its
 * c-loop pruning bound for the SAME (cell, world) — zero extra DP on the production badge path.
 */
internal class Tier15Result(
    val values: Map<Int, Long>,
    val perCByCell: Map<Int, Array<LongArray?>>,
)

/** The certificate tier asking for a worker count — lets a dynamic provider size each tier differently. */
enum class CertTier { FAST, TIER15, EXACT }

/**
 * Two-tier certificate orchestrator (P3.2). Tier 1 (fast, warm-once parallel) bounds every AP cell;
 * cells whose bound is `≤ incumbentObjective` are DONE (the incumbent is feasible, the bound an upper
 * bound ⇒ no build there beats it); the survivors are confirmed by the EXACT tier. All values are scaled
 * to OBJECTIVE units via the ONE formula so they compare directly against CP-SAT objectives.
 *
 * @param incumbentObjective a FEASIBLE objective already found; `null` skips elimination (every
 *   non-bailed cell goes to tier 2 — tests only; production always passes one).
 * @param forceTier2All confirm EVERY non-bailed cell exactly, ignoring the incumbent (oracle lock).
 * @param threads worker threads for both tiers' parallelism; 1 = fully serial. When the enclosing
 *   [StatBuilder.certifierThreadsProvider] is set it takes precedence, re-read at each tier's start —
 *   the warm-up path uses it to scale from 1 (search running) to the full count (search done).
 */
internal fun StatBuilder.certifyLedger(
    scenario: DamageScenario,
    cellCount: Int,
    clampedTable: LongArray,
    resFactor: Long,
    // The feasible incumbent at CALL time; superseded by [StatBuilder.certifierIncumbentProvider] (when
    // set) right before elimination — see [incumbentObjective] below.
    incumbentObjectiveIn: Long?,
    forceTier2All: Boolean,
    threads: Int,
    // B6: the incumbent-independent SCALED per-cell fast bounds (+ shape bail set) from a prior compute of THIS
    // shape. When non-null, the ~seconds-to-minutes tier-1 fast DP is SKIPPED and these are reused verbatim (a pure
    // function of the shape — byte-identical to recomputing them). Only the incumbent-dependent elimination + tier
    // 1.5 / exact confirmation re-run. Null ⇒ compute the fast pass as usual.
    precomputedFast: Map<Int, Long>? = null,
    precomputedBailed: Set<Int>? = null,
): CertLedger {
    fun scale(
        a: Int,
        maxPerHit: Long,
    ): Long = clampedTable[a] * (maxPerHit / PERHIT_DOWNSCALE) * resFactor / FINAL_DOWNSCALE

    // Re-read per tier so the warm-up scales up the moment the search finishes (see the provider's doc),
    // and per-TIER so tier-1.5's memory-light workers are not capped by the exact tier's heap formula.
    fun tierThreads(tier: CertTier): Int = certifierThreadsProvider?.invoke(tier) ?: threads

    // Stage timing (WAKFU_MAX_DAMAGE_CERT_TIMING=1): one stderr line with the tier-1 / tier-1.5 / exact
    // wall-clock split — the floor-decomposition measurement. No behavior change.
    val timingEnabled = System.getenv("WAKFU_MAX_DAMAGE_CERT_TIMING") == "1"
    val timeStart = if (timingEnabled) System.currentTimeMillis() else 0L
    var timeFastDoneMs = 0L
    var timeTier15DoneMs = 0L

    // Floor speed: on the incumbent path, harvest tier-1's per-world per-(cell, crit-step) bounds so
    // tier-1.5 can skip the step-1 segments that provably cannot cross the incumbent (its dominant cost).
    // Only worth collecting when tier-1.5 will actually run (incumbent path, skip enabled); the B6
    // precomputed branch has no fresh tier-1 run, so tier-1.5 stays unskipped there (sound, just slower).
    val tier15SkipUbByWorld: Array<Array<LongArray>?>? =
        if ((incumbentObjectiveIn != null || certifierIncumbentProvider != null) && !forceTier2All && CertifierTuning.tier15SegmentSkipEnabled && precomputedFast == null) {
            certifierWorlds(scenario)?.let { arrayOfNulls(it.size) }
        } else {
            null
        }

    // Tier 1: fast upper bound for every cell. A fast bail is shape-level (all-or-nothing) — no sound
    // bound anywhere ⇒ every cell bailed, maxCellObjective null. (B6: reused from cache when supplied.)
    val fastObj: LongArray
    val bailed: Set<Int>
    if (precomputedFast != null && precomputedBailed != null) {
        bailed = precomputedBailed
        if (bailed.isNotEmpty()) return CertLedger(emptyMap(), bailed, emptySet(), null)
        fastObj = LongArray(cellCount) { precomputedFast.getValue(it) }
    } else {
        val fast =
            certifyAllCellsFast(
                scenario,
                cellCount,
                tierThreads(CertTier.FAST),
                perCellCByWorldOut = tier15SkipUbByWorld,
                threadsProvider = { tierThreads(CertTier.FAST) }
            )
        bailed = (0 until cellCount).filter { fast[it] == Long.MAX_VALUE }.toSet()
        if (bailed.isNotEmpty()) return CertLedger(emptyMap(), bailed, emptySet(), null)
        fastObj = LongArray(cellCount) { scale(it, fast[it]) }
    }
    if (timingEnabled) timeFastDoneMs = System.currentTimeMillis() - timeStart
    // Resolve the LIVE incumbent now — after the tier-1 fast DP, right before elimination. The warm-up path
    // launches this ledger on the search's FIRST streamed result, but by this point (minutes in) the search
    // has finished and its FINAL incumbent is far stronger: using it eliminates more cells and sharpens every
    // tier-1.5 skip threshold. Any FEASIBLE incumbent is sound here (elimination only compares bounds to it),
    // and the raw bounds cached from this run are incumbent-independent. Never lower than the call-time value
    // (a weaker late read could only widen survivor work; max() keeps this monotone).
    val incumbentObjective =
        certifierIncumbentProvider?.invoke()?.let { live -> maxOf(live, incumbentObjectiveIn ?: live) }
            ?: incumbentObjectiveIn
    // B4: the raw incumbent-independent fast array (keyed by cell), exposed for the session cache so a re-search
    // with a different incumbent can recompute the elimination boundary without re-running the fast DP.
    val fastObjectives = (0 until cellCount).associateWith { fastObj[it] }
    // B4: survivors whose EXACT pass bailed (still sound — they keep fast). Cached so the reconstruction can keep
    // them at fast rather than treating them as not-yet-computed (which would force a needless recompute).
    val exactBailed = linkedSetOf<Int>()

    // Elimination: a cell whose fast ceiling cannot beat the incumbent is done on the fast value alone.
    val survivors =
        (0 until cellCount).filter { a ->
            forceTier2All || incumbentObjective == null || fastObj[a] > incumbentObjective
        }

    // Tier 2: exact confirmation per survivor. If the exact pass itself bails on a survivor
    // (Long.MAX_VALUE), keep its (still sound) fast value and leave it OUT of tier2Cells — that is not
    // a bail (a sound bound still exists), so bailedCells stays empty.
    val exactObj = LinkedHashMap<Int, Long>()
    // B7: tier-1.5 objectives for the survivors it sharpened (incumbent-independent; cached by the session cache
    // so a re-search reconstructs without re-running the DP). A cell CLEARED by tier-1.5 keeps this value.
    val tier15Obj = LinkedHashMap<Int, Long>()
    // E8 item A (perf): the winning (world, crit-step) captured for free alongside each EXACT confirmation — keyed
    // by the same cells as [exactObj] (the tier-2 cells). The E8 fast-path replays the argmax cell's pointer.
    val provByCell = LinkedHashMap<Int, CellProvenance>()
    if (incumbentObjective != null && !forceTier2All) {
        // B7 tier-1.5, then B1 flood control. First SHARPEN every survivor with the step-1 tier-1.5 pass: a
        // survivor whose (still sound, tighter) tier-1.5 bound already falls ≤ the incumbent is DONE and skips
        // the ~minutes-per-cell exact DP entirely. The rest fall through to the exact tier below.
        val survivorsSorted = survivors.sortedByDescending { fastObj[it] }
        // Floor speed: the largest RAW value that still scales `≤ incumbent` for each survivor — the
        // tier-1.5 segment-skip threshold. `scale` is monotone in raw (integer steps of PERHIT_DOWNSCALE),
        // so binary-search the quotient; a zero multiplier means scale ≡ 0 ≤ incumbent (skip everything —
        // such a cell was fast-eliminated anyway and never reaches tier-1.5).
        val skipBelowRawByCell: Map<Int, Long>? =
            if (tier15SkipUbByWorld != null) {
                survivorsSorted.associateWith { a ->
                    if (clampedTable[a] <= 0L || resFactor <= 0L) {
                        Long.MAX_VALUE
                    } else {
                        var lo = 0L
                        var hi = PERHIT_SCALED_MAX
                        while (lo < hi) {
                            val mid = (lo + hi + 1) / 2
                            if (clampedTable[a] * mid * resFactor / FINAL_DOWNSCALE <= incumbentObjective) lo = mid else hi = mid - 1
                        }
                        lo * PERHIT_DOWNSCALE + (PERHIT_DOWNSCALE - 1)
                    }
                }
            } else {
                null
            }
        // CASCADE (short-search rescue, [StatBuilder.certifyLedgerCascadeTier15]): with a WEAK incumbent
        // (a very short search) elimination leaves many survivors and the tier-1.5 batch pays a step-1
        // pass for EVERY one of them. Confirm one cell at a time instead, descending fast bound, exact
        // immediately when tier-1.5 cannot clear — and stop at the first exact > incumbent (the B1 break,
        // one tier earlier). Unprocessed cells keep their sound FAST bounds; the caller then constructs
        // the confirmed argmax (E8) or falls back to a full ledger. Values per processed cell are
        // byte-identical to the batch path (same passes, same inputs — only the SET of processed cells
        // changes), so the fuzz/oracle locks (cascade off) are unaffected.
        val tier15: Tier15Result
        val stillSurviving = ArrayList<Int>()

        // B4/B7 compute-path reuse: a cell already confirmed by a PRIOR compute of this shape (its
        // tier-1.5 / exact objective is incumbent-independent) is decided from the cache — no DP.
        fun cachedExactOf(a: Int): Long? = certifyLedgerPrecomputedExact?.get(a)

        fun cachedTier15Of(a: Int): Long? = certifyLedgerPrecomputedTier15?.get(a)
        if (certifyLedgerCascadeTier15) {
            val perCAccum = LinkedHashMap<Int, Array<LongArray?>>()
            for (a in survivorsSorted) {
                val cachedExact = cachedExactOf(a)
                if (cachedExact != null) {
                    exactObj[a] = cachedExact
                    certifyLedgerPrecomputedProv?.get(a)?.let { provByCell[a] = it }
                    if (cachedExact > incumbentObjective) break else continue
                }
                val cachedT15 = cachedTier15Of(a)
                if (cachedT15 != null && cachedT15 <= incumbentObjective) {
                    tier15Obj[a] = cachedT15
                    continue
                }
                if (cachedT15 == null) {
                    val one = certifyCellsTier15(scenario, listOf(a), tierThreads(CertTier.TIER15), skipUbByWorld = tier15SkipUbByWorld, skipBelowRawByCell = skipBelowRawByCell)
                    one.perCByCell[a]?.let { perCAccum[a] = it }
                    val raw = one.values[a] ?: Long.MAX_VALUE
                    if (raw != Long.MAX_VALUE) {
                        val obj = scale(a, raw)
                        tier15Obj[a] = obj
                        if (obj <= incumbentObjective) continue
                    }
                } else {
                    tier15Obj[a] = cachedT15 // over-incumbent cached tier-1.5 ⇒ straight to exact
                }
                // Not cleared: confirm exactly NOW; a confirmed over-incumbent cell ends the cascade.
                val mph = exactForCells(scenario, listOf(a), tierThreads(CertTier.EXACT), provOut = provByCell, ubByWorldCell = perCAccum).getValue(a)
                if (mph == Long.MAX_VALUE) {
                    exactBailed += a
                    continue
                }
                val obj = scale(a, mph)
                exactObj[a] = obj
                if (obj > incumbentObjective) break // remaining survivors keep their sound fast bounds
            }
            // [values] deliberately empty: the cascade already folded cleared cells into [tier15Obj] and
            // confirmed the rest exactly — the only later consumer of this result is [Tier15Result.perCByCell].
            tier15 = Tier15Result(emptyMap(), perCAccum)
            if (timingEnabled) timeTier15DoneMs = System.currentTimeMillis() - timeStart
        } else {
            // Cells decided by cached confirms never enter the batch.
            val undecided = ArrayList<Int>()
            for (a in survivorsSorted) {
                val cachedExact = cachedExactOf(a)
                if (cachedExact != null) {
                    exactObj[a] = cachedExact
                    certifyLedgerPrecomputedProv?.get(a)?.let { provByCell[a] = it }
                    continue
                }
                val cachedT15 = cachedTier15Of(a)
                if (cachedT15 != null) {
                    tier15Obj[a] = cachedT15
                    if (cachedT15 <= incumbentObjective) continue
                    stillSurviving += a
                    continue
                }
                undecided += a
            }
            tier15 = certifyCellsTier15(scenario, undecided, tierThreads(CertTier.TIER15), skipUbByWorld = tier15SkipUbByWorld, skipBelowRawByCell = skipBelowRawByCell)
            if (timingEnabled) timeTier15DoneMs = System.currentTimeMillis() - timeStart
            val tier15Raw = tier15.values
            for (a in undecided) {
                val raw = tier15Raw[a] ?: Long.MAX_VALUE
                if (raw != Long.MAX_VALUE) {
                    val obj = scale(a, raw)
                    tier15Obj[a] = obj
                    if (obj <= incumbentObjective) continue // cleared by tier-1.5 ⇒ no exact needed
                }
                stillSurviving += a // tier-1.5 could not clear it (or bailed) ⇒ confirm exactly
            }
        }

        // B1 flood control: for a required-target incumbent whose proxy sits far below the unconstrained
        // ceilings, elimination leaves MANY survivors, each ~minutes of exact DP. But once ONE survivor's
        // EXACT value exceeds the incumbent the badge is already lost — maxCell ≥ that value > incumbent ⇒
        // ProvenWithin — so the remaining survivors can keep their (still sound) tier-1.5/fast bound instead of
        // paying the exact pass. Confirm from the LOOSEST fast bound down (stillSurviving preserves that order)
        // so the over-incumbent cell surfaces first. VERDICT-PRESERVING: a ProvenOptimal request (every
        // survivor's exact ≤ incumbent) never breaks, so it confirms every still-surviving cell exactly; only a
        // lost-badge request stops early, and its ProvenWithin bound stays a sound global upper bound — mixing
        // fast/tier-1.5/exact per cell is already the ledger's documented semantics. (We deliberately do NOT also
        // skip the exact pass by a fast-vs-incumbent MARGIN: that relies on measured tightness, legitimately
        // loose on crit-band / coupling shapes, so it could flip a true ProvenOptimal to ProvenWithin. tier-1.5
        // clears only on a COMPUTED bound ≤ incumbent, never on a margin — so it never causes a false negative.)
        for (a in stillSurviving) {
            // Two-tier speed: tier-1.5's per-(cell, world) per-crit-step rows prune this exact pass's
            // c-loop for FREE (the step-1 DP already ran above) — value/provenance byte-identical.
            val mph = exactForCells(scenario, listOf(a), tierThreads(CertTier.EXACT), provOut = provByCell, ubByWorldCell = tier15.perCByCell).getValue(a)
            if (mph == Long.MAX_VALUE) {
                exactBailed += a
                continue // exact bailed ⇒ keep tier-1.5/fast (still sound), not a tier-2 confirm
            }
            val obj = scale(a, mph)
            exactObj[a] = obj
            if (obj > incumbentObjective) break
        }
    } else {
        // Oracle / forceTier2All path: no tier-1.5 ran, so no free per-c bounds — the exact loops run
        // unpruned (a per-(cell, world) bound recompute was MEASURED to cost more than it saves).
        val exactRaw = exactForCells(scenario, survivors, tierThreads(CertTier.EXACT), provOut = provByCell)
        for ((a, mph) in exactRaw) if (mph == Long.MAX_VALUE) exactBailed += a else exactObj[a] = scale(a, mph)
    }

    if (timingEnabled) {
        val total = System.currentTimeMillis() - timeStart
        System.err.println(
            "CERT_TIMING fastMs=$timeFastDoneMs tier15Ms=${(timeTier15DoneMs - timeFastDoneMs).coerceAtLeast(0)} " +
                "exactMs=${(total - maxOf(timeTier15DoneMs, timeFastDoneMs)).coerceAtLeast(0)} totalMs=$total " +
                "survivors=${survivors.size} exactCells=${exactObj.size}"
        )
    }
    val cellObjectives = LinkedHashMap<Int, Long>()
    // Tightest available sound bound per cell: exact ≤ tier-1.5 ≤ fast. (A cell absent from all three maps is an
    // eliminated non-survivor ⇒ its fast bound, ≤ incumbent, carries.)
    for (a in 0 until cellCount) cellObjectives[a] = exactObj[a] ?: tier15Obj[a] ?: fastObj[a]
    return CertLedger(
        cellObjectives,
        emptySet(),
        exactObj.keys.toSet(),
        cellObjectives.values.maxOrNull(),
        fastObjectives,
        exactBailed,
        tier15Obj,
        // Only exactly-confirmed cells carry provenance; a cell dropped from [exactObj] on the flood-control break
        // (an exact bail) is not added. Restrict to [exactObj] keys so the map never outlives its tier-2 cells.
        provByCell.filterKeys { it in exactObj }
    )
}

/**
 * The backtracked composition of a cell's winning certificate state: human-readable [lines] plus the E8 item-A
 * STRUCTURED provenance [itemIds] (the winning composition's equipmentIds). [itemIds] is empty when the certifier
 * bails (a diagnostic-only [lines]) — the E8 seam then falls back, which is sound.
 */
internal data class CertExplain(
    val lines: List<String>,
    val itemIds: List<Int>,
)

/**
 * PROVENANCE (diagnostics): explain the winning certificate state of [apTarget] — the same worlds as
 * [certifyMaxPerHitAtAp] are enumerated, the winning (world, crit-step) is re-run in explain mode, and
 * the backtracked composition (items, subs, skill allocation, budget cover) is returned as text.
 */
internal fun StatBuilder.certifyExplainAtAp(
    scenario: DamageScenario,
    apTarget: Int,
): CertExplain {
    val worlds =
        certifierWorlds(scenario)
            ?: return CertExplain(listOf("cell $apTarget: certifier bails (unsupported sub shape — no worlds)"), emptyList())
    var bestVal = -1L
    var bestWorld: CertWorld? = null
    var bestWorldC = -1
    for (w in worlds) {
        val cOut = IntArray(1) { -1 }
        val v = certifyMaxPerHitAtApPass(scenario, apTarget, w.conv, w.cs, w.csExcluded, w.wr, winningCOut = cOut)
        if (v == Long.MAX_VALUE) {
            return CertExplain(
                listOf("cell $apTarget: certifier bails (world conv=${w.conv?.name?.en} cs=${w.cs?.name?.en} wr=${w.wr})"),
                emptyList()
            )
        }
        if (v > bestVal) {
            bestVal = v
            bestWorld = w
            bestWorldC = cOut[0]
        }
    }
    val w = bestWorld ?: return CertExplain(listOf("cell $apTarget: no world produced a state"), emptyList())
    val out = mutableListOf<String>()
    val ids = mutableListOf<Int>()
    certifyMaxPerHitAtApPass(scenario, apTarget, w.conv, w.cs, w.csExcluded, w.wr, explainC = bestWorldC, explainOut = out, explainItemIds = ids)
    return CertExplain(out, ids)
}

/**
 * E8 item A (perf): the same backtrack as [certifyExplainAtAp] but WITHOUT the N-worlds scan — the winning
 * (world, crit-step) was captured for free during the badge's exact pass ([CellProvenance]) and cached, so this
 * replays only that ONE pass. That collapses the E8 fast-path's provenance cost from the whole scan (~minutes at
 * high level) to a single crit-step DP (~seconds). A stale/out-of-range pointer (should not happen — the cache key
 * pins [WakfuBuildSolver.CERTIFIER_VERSION], and a world-enumeration change bumps it) returns empty ⇒ the seam
 * falls back to the full [certifyExplainAtAp], which is sound.
 */
internal fun StatBuilder.certifyExplainAtApFromProvenance(
    scenario: DamageScenario,
    apTarget: Int,
    provenance: CellProvenance,
): CertExplain {
    val worlds =
        certifierWorlds(scenario)
            ?: return CertExplain(listOf("cell $apTarget: certifier bails (unsupported sub shape — no worlds)"), emptyList())
    val w =
        worlds.getOrNull(provenance.worldIndex)
            ?: return CertExplain(listOf("cell $apTarget: provenance world ${provenance.worldIndex} out of range (${worlds.size})"), emptyList())
    val out = mutableListOf<String>()
    val ids = mutableListOf<Int>()
    certifyMaxPerHitAtApPass(scenario, apTarget, w.conv, w.cs, w.csExcluded, w.wr, explainC = provenance.c, explainOut = out, explainItemIds = ids)
    return CertExplain(out, ids)
}

/**
 * One certifier pass (see [certifyMaxPerHitAtAp]). [convTaken] = null certifies the no-conversion
 * world (conversion `moved` terms dropped). Non-null FORCE-takes that CONVERSION sub: it consumes the
 * slot its rarity dictates — a NORMAL one charges the normal cap (subCap − 1), an EPIC/RELIC one its
 * dedicated rarity slot (the matching rarity stage is skipped and the harvest requires an equipped
 * epic/relic item) — its condition gates every harvested state via
 * [subAllowedAt], and every pre-sub critM source is folded into mastery at `percent` — ceiling per
 * source, ≥ the model's single floored total, so the certified value stays an upper bound.
 */
internal fun StatBuilder.certifyMaxPerHitAtApPass(
    scenario: DamageScenario,
    apTarget: Int,
    convTaken: Sublimation?,
    // World C: this CRITICAL_MASTERY_AT_MOST≤0 budget sub (Critical Secret) force-taken — its crit
    // joins the budget, every PRE-COMBAT critM source is zeroed (satisfying its condition), and it
    // consumes the epic sub slot. [critSecretExcluded]: the same sub EXCLUDED from this world's
    // budget (worlds A/B when C exists — C covers its takers, so exclusion is sound and tight).
    critSecret: Sublimation? = null,
    critSecretExcluded: Sublimation? = null,
    // World W: NO_OFFHAND_OR_TWO_HANDED subs allowed, weapon slot limited to {empty, one-handed}.
    // false: those subs excluded, weapons free (the two worlds jointly cover every build).
    weaponsRestricted: Boolean = false,
    // PROVENANCE (diagnostics): when [explainC] is set, only that crit-step runs, the frontier is
    // snapshotted after every DP stage, and the winning point is backtracked to the concrete
    // item/sub/skill choices that compose it — appended to [explainOut]. [winningCOut] (size ≥ 1)
    // receives the winning crit-step of a normal run so the caller knows which c to explain.
    explainC: Int? = null,
    explainOut: MutableList<String>? = null,
    // E8 item A: the parallel STRUCTURED provenance — the winning composition's equipmentIds (item stages only),
    // so the E8 fast-path recovers the items as typed ids instead of regex-parsing [explainOut]'s `slot:` strings.
    explainItemIds: MutableList<Int>? = null,
    winningCOut: IntArray? = null,
    // FAST tier-1 mode (P2): when non-null, skip the exact per-c loop and instead run ONE shared 4-D
    // DP, harvesting a SOUND upper bound for every AP cell `0 until fastCellCount` into this array
    // (returns 0L; the caller reads the array). [apTarget] must be the loosest cell (fastCellCount-1)
    // so the shared DP's AP ceiling covers every cell. The exact path is unchanged when this is null.
    fastAllCellsOut: LongArray? = null,
    fastCellCount: Int = 0,
    // B7 tier-1.5: the c-grid segment step for the fast pass. The default coarse step (P2.6) folds graw at
    // each segment's TOP crit (a sound over-count); passing 1 makes every segment width-1, so the graw fold
    // is EXACT per crit level — a strictly TIGHTER-or-equal, still-sound bound (`fast ≥ tier1.5 ≥ exact`).
    fastCSegmentStep: Int = FAST_C_SEGMENT_STEP,
    // Two-tier speed: when non-null (fast mode only, a size-1 holder), the pass allocates a
    // `LongArray(cEnumMax + 1)` into `holder[0]` and ALSO harvests the per-CRIT-STEP maximum for the TOP
    // cell (`fastCellCount - 1`) into it — `holder[0][c] ≥` this shape's exact dp(c) at that cell when
    // [fastCSegmentStep] == 1 (same graw fold per c, relaxed state space). A holder (not a caller array)
    // because cEnumMax is pass-internal. Tier-1.5 harvests this for free; the ledger threads it back in
    // as [cPruneUbIn] for the SAME (cell, world) exact pass.
    fastPerCOutHolder: Array<LongArray?>? = null,
    // Two-tier speed (exact mode only): a caller-supplied SOUND per-crit-step upper bound on this very
    // pass's dp(c) — the SAME (cell, world) tier-1.5 harvest ([fastPerCOut]) — used to prune the c-loop.
    // Alignment is the caller's contract: the bound must come from a step-1 fast pass with IDENTICAL
    // (apTarget, world) args, else it is not a sound bound for this pass. Null ⇒ the plain ascending loop.
    cPruneUbIn: LongArray? = null,
    // Floor speed (fast mode, ALL-cells tier-1 run): when non-null (a size-1 holder), the pass allocates
    // `Array(fastCellCount) { LongArray(cEnumMax + 1) }` into `holder[0]` and harvests the per-(cell,
    // crit-step) maximum for EVERY cell — each entry is a sound `≥ dp_exact(cell, c)` (graw folded at the
    // segment top ≥ its value at any c in the segment; the full-ceiling DP explores a superset of the
    // cell-pinned state space). The ledger threads a (world, cell) row into tier-1.5 as [fastSegmentSkipUb].
    fastPerCellCOutHolder: Array<Array<LongArray>?>? = null,
    // Floor speed (fast mode, single-cell tier-1.5 run): tier-1's step-8 per-c row for THIS (cell, world)
    // plus the raw threshold at-or-below which a value cannot exceed the incumbent once scaled. A segment
    // whose row values all sit `≤` the threshold is SKIPPED — its step-1 value is `≤` the row there, so it
    // can never cross the incumbent and the CLEARING DECISION is provably identical; the (sound, step-8)
    // row values are carried into the outputs instead. CONTRACT: under a skip only the TOP cell's outputs
    // ([fastAllCellsOut]`[fastCellCount-1]`, [fastPerCOutHolder]) are meaningful — lower cells are
    // silently under-harvested — so only the single-cell tier-1.5 caller may pass this.
    fastSegmentSkipUb: LongArray? = null,
    fastSkipBelowRaw: Long = Long.MIN_VALUE,
): Long {
    val mastery = damagePreMasteryTerms(scenario) ?: return Long.MAX_VALUE
    if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return Long.MAX_VALUE
    if (skillTerms.percent[Characteristic.DAMAGE_INFLICTED].orEmpty().isNotEmpty()) return Long.MAX_VALUE
    if (skillTerms.percent[Characteristic.ACTION_POINT].orEmpty().isNotEmpty()) return Long.MAX_VALUE
    if (skillTerms.percent[Characteristic.CRITICAL_HIT].orEmpty().isNotEmpty()) return Long.MAX_VALUE
    // Forced subs (P5.3): the blanket bail is narrowed to a per-sub shape guard near `subEntries` below —
    // a creditable forced sub is applied via constants + a slot charge; every unhandled shape still bails.
    // Best-element-concentration / per-stat-step subs (added after this certifier was written) are NOT
    // bailed: their Damage-Inflicted enters [diTerms] and is folded at each term's MAX contribution
    // below, so the certified value stays a SOUND upper bound (looser, never an under-estimate). Tighten
    // + add covering tests before wiring the certifier into the production proof (see
    // docs/code-review-followups.md).
    val convPercent = convTaken?.conversion?.percent ?: 0

    // Ceiling per source ⇒ Σ ≥ the model's floor(percent% · preSub total): the converted-mastery gain
    // never under-counts. Residual keeps `x − floor(percent%·x)` per source — likewise ≥ the real
    // leftover. Both exact at percent = 100 (Unraveling).
    fun convGain(x: Long): Long = if (convTaken == null) 0L else Math.ceilDiv(convPercent * x, 100L)

    fun convResidual(x: Long): Long = if (convTaken == null) x else x - Math.floorDiv(convPercent * x, 100L)

    // World C (critSecret taken): every PRE-COMBAT critM source is zeroed — the condition demands a
    // critM-free sheet. Start-of-combat sub critM (cmS) stays: it never feeds the sheet. Otherwise
    // compose with the conversion residual (the two worlds never coexist — both consume the epic slot).
    fun cmWorld(x: Long): Long = if (critSecret != null) 0L else convResidual(x)

    // Vars the certifier must EXCLUDE from every term list — else their max contribution leaks into the passive
    // constants at their (untracked) domain max. Two kinds:
    //  • [conversionMovedVars]: a CONVERSION's ±moved value, accounted analytically per-Raw instead.
    //  • Cumulable-sub COPY vars (b1..b_{k-1}): the SOLVER models a k-copy sub as its base subVar PLUS these
    //    extra booleans (SublimationModelBuilder). The certifier re-derives stacking by DUPLICATING the sub's
    //    single-copy Raw [Sublimation.maxCopies] times in [keptSubs], so it must drop the model's copy vars —
    //    their VALUE rides the kept base subVar term (perSubValue attributes it; the duplication scales it k×).
    //    Left in, [passivePart] would fold each copy into [diConst]/[mConst] as an ALWAYS-ON constant, double-
    //    counting what the duplication already prices (and, before the copy vars were seeded `0..1`, doing so at
    //    their ±1e7 fallback domain — a phantom hundreds-of-millions constant that exploded the bound ~2.9e6×).
    val certifierDroppedVars: Set<IntVar> =
        subModel.copyVars.values
            .flatten()
            .toSet()
            .let { copies -> if (copies.isEmpty()) conversionMovedVars else conversionMovedVars + copies }

    fun dropMoved(terms: List<Term>): List<Term> = if (certifierDroppedVars.isEmpty()) terms else terms.filter { it.variable !in certifierDroppedVars }

    val masteryTerms = dropMoved(mastery.terms)
    val (critMTermsAll, critMBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
    val critMTerms = dropMoved(critMTermsAll)
    val (diTermsAll, diBase) = prePercentTermsFor(Characteristic.DAMAGE_INFLICTED)
    val diTerms = dropMoved(diTermsAll)
    val (apTermsAll, apBase) = prePercentTermsFor(Characteristic.ACTION_POINT)
    val apTerms = dropMoved(apTermsAll)
    val (critTermsAll, critBase) = prePercentTermsFor(Characteristic.CRITICAL_HIT)
    val critTerms = dropMoved(critTermsAll)

    val diI = perCarrierContribution(diTerms)
    val mI = perCarrierContribution(masteryTerms)
    val cmI = perCarrierContribution(critMTerms)
    // AP/crit are COST dimensions: exact when-equipped sums (negatives kept), NOT optimistic maxes.
    val apI = perCarrierExactValue(apTerms)
    val crI = perCarrierExactValue(critTerms)
    val diS = perSubValue(diTerms)
    val mS = perSubValue(masteryTerms)
    val cmS = perSubValue(critMTerms)
    val apS = perSubValue(apTerms)
    val crS = perSubValue(critTerms)

    // Raw stat tuple per carrier / sub: (di, m, critM, ap, crit, epic, relic, mp). graw filled per
    // crit c; mp rides the frontier for MP-sourced ramp valuation (items, subs and rings alike —
    // only base + passive MP stays in the constant [mpFreeMax]).
    data class Raw(
        val di: Long,
        val m: Long,
        val critM: Long,
        val ap: Int,
        val crit: Int,
        val epic: Int,
        val relic: Int,
        val mp: Long = 0L,
    )

    // P5.3: FORCED subs are always equipped, so they are credited into the pass CONSTANTS (not the
    // optional pools) and charge a sub slot — mirroring `convTaken`. The classification below mirrors
    // [appliesVar] EXACTLY:
    //  • COMBAT_CONDITIONAL: the model credits NOTHING ([buildSublimationTerms] skips the kind) — an
    //    equipped-but-inert sub. Zero credit here too; the slot charge (subCap) and the epic/relic
    //    occupancy (forcedEpicPlain/forcedRelicPlain) still apply.
    //  • No condition, or an UNSUPPORTED condition type: the model credits the effects unconditionally
    //    on the pinned subVar. DI/mastery/critM fold into the value constants; crit/AP into the STATE
    //    axes — the permanent part into critConst/apConst (feeding both the c/AP arithmetic and the
    //    pre-combat condition windows), start-of-combat crit into a free, always-present crit budget
    //    (`forcedStartCritTotal`: widens the item-crit band + the AT_MOST hiding capacity, reduces the
    //    optional budget gap, and extends cEnumMax — it can never feed a pre-combat window).
    //  • A SUPPORTED condition: gated per harvested state in the EXACT pass (same [subAllowedAt] gate
    //    the choosable subs use, matching the model's `subVar ∧ condHolds`; the slot stays charged
    //    either way) and credited UNCONDITIONALLY in the FAST pass (sound over-count; keeps
    //    `fast ≥ exact`). Each credit axis is clamped ≥ 0: a NEGATIVE conditional effect credited at a
    //    state whose builds may BREAK the condition would under-count them — the clamp only ever
    //    raises the bound. NO_OFFHAND_OR_TWO_HANDED is world-split exactly like the optional keptSubs:
    //    full constant credit in the weapons-restricted world (every build there satisfies it), inert
    //    in the free world (its satisfying builds are covered by the restricted world).
    // Bails (sound): a forced perStatStep ramp (Featherweight — its valuation is not folded for forced
    // subs), start-of-combat AP, negative start-of-combat crit, and a supported-conditional sub
    // carrying crit/AP (a state axis cannot be gated per condition).
    // NOTE: a forced conditional sub's terms ride its GATED var, attributed back to the sub through
    // [subDerivedVars] (tracked [0,1]) — [perSubValue]'s derived path returns the raw coefficient.
    val forcedSubs = subModel.forced
    // A forced CONVERSION / CRITICAL-SECRET sub is handled by ITS world (convTaken/critSecret): the
    // world charges its slot, gates its condition and applies its effect — it must not double-enter
    // the plain forced credits/charges below. [certifierWorlds] guarantees such a sub only ever
    // reaches a pass as the world's own special.
    val forcedPlainSubs = forcedSubs.filter { it != convTaken && it != critSecret }
    // A forced EPIC/RELIC plain sub occupies THE ≤1 epic/relic sub slot of its rarity: the choosable
    // rarity-sub stages are emptied below, and every harvested state must host the carrier item.
    val forcedEpicPlain = forcedPlainSubs.any { it.rarity == SublimationRarity.EPIC }
    val forcedRelicPlain = forcedPlainSubs.any { it.rarity == SublimationRarity.RELIC }
    // Pre-combat-condition split (mirrors [preCombatStat] / [permanentSubTermsByStat]): only PERMANENT
    // (appliesBeforeCombat) sub crit/AP shows on the character sheet, so only it feeds a build-static
    // start-of-combat condition — a start-of-combat crit still reaches the in-combat total c.
    val permCritByVar = permanentSubTermsByStat[Characteristic.CRITICAL_HIT].orEmpty().associate { it.variable to it.coefficient }
    val permApByVar = permanentSubTermsByStat[Characteristic.ACTION_POINT].orEmpty().associate { it.variable to it.coefficient }

    fun permCritOf(sub: Sublimation) = subModel.subVars[sub]?.let { permCritByVar[it] } ?: 0L

    fun permApOf(sub: Sublimation) = subModel.subVars[sub]?.let { permApByVar[it] } ?: 0L

    var forcedDiConst = 0L
    var forcedMConst = 0L
    var forcedCmConst = 0L
    var forcedPermCritTotal = 0L
    var forcedStartCritTotal = 0L
    var forcedPermApTotal = 0L
    val forcedCondCredits = mutableListOf<Pair<Sublimation, Raw>>()
    for (sub in forcedPlainSubs) {
        // A forced WORLD-SPECIAL (CONVERSION / the CS shape) in a world where it is NOT taken is
        // EQUIPPED-BUT-INERT: its slot is charged (subCap below), it is out of every optional
        // pool/budget (keptSubs / csExcluded), and its effect is zero by definition — nothing to
        // credit. [certifierWorlds] guarantees the special-ACTIVE builds get their own world.
        val inertSpecial =
            sub.kind == SublimationKind.CONVERSION ||
                (
                    sub.condition?.type == SublimationConditionType.CRITICAL_MASTERY_AT_MOST &&
                        (sub.condition?.value ?: 0) <= 0
                )
        if (inertSpecial) continue
        // Equipped-but-inert: zero model credit, slot + rarity occupancy already charged.
        if (sub.kind == SublimationKind.COMBAT_CONDITIONAL) continue
        if (sub.perStatStep != null) return Long.MAX_VALUE
        val di = diS[sub] ?: 0L
        val m = mS[sub] ?: 0L
        val cm = cmS[sub] ?: 0L
        val ap = apS[sub] ?: 0L
        val cr = crS[sub] ?: 0L
        val cond = sub.condition
        when {
            cond == null || cond.type !in SUPPORTED_SUB_CONDITIONS -> {
                val permCrit = permCritOf(sub)
                val startCrit = cr - permCrit
                // A negative start-of-combat crit would let the sheet crit exceed the in-combat total
                // c, breaking the pre-combat window bounds — no such sub exists; bail if one appears.
                if (startCrit < 0) return Long.MAX_VALUE
                // Start-of-combat AP has no band/window machinery (Vivacity/Carapace are permanent).
                if (ap != permApOf(sub)) return Long.MAX_VALUE
                forcedDiConst += di
                forcedMConst += m
                forcedCmConst += cm
                forcedPermCritTotal += permCrit
                forcedStartCritTotal += startCrit
                forcedPermApTotal += ap
            }
            cond.type == SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED -> {
                if (cr != 0L || ap != 0L) return Long.MAX_VALUE
                if (weaponsRestricted) {
                    forcedDiConst += di
                    forcedMConst += m
                    forcedCmConst += cm
                }
            }
            else -> {
                if (cr != 0L || ap != 0L) return Long.MAX_VALUE
                forcedCondCredits += sub to Raw(di.coerceAtLeast(0L), m.coerceAtLeast(0L), cm.coerceAtLeast(0L), 0, 0, 0, 0)
            }
        }
    }
    val forcedCondDiTotal = forcedCondCredits.sumOf { it.second.di }
    val forcedCondMTotal = forcedCondCredits.sumOf { it.second.m }
    val forcedCondCmTotal = forcedCondCredits.sumOf { it.second.critM }
    // Skill points are a per-branch SHARED pool (addSkillConstraints: Σ branch points ≤ pool), so the
    // certifier cannot fold each objective skill stat at its individual max — that over-allocates the
    // pool (e.g. Luck's 61 pts can't be 20 crit AND 61 critM at once). Split skill contributions into
    // the PASSIVE constant part (base + always-on passives) which stays a constant, and the SKILL-VAR
    // part which is folded into the DP per branch as a pseudo-slot (below) so the allocation is solved
    // jointly with items/subs. mastery/critM may also reach the constant via passives — kept general.
    val skillVarSet = skillVars.values.toSet()

    fun passivePart(terms: List<Term>): Long {
        var sum = 0L
        for (t in terms) {
            if (carrierByVar[t.variable] != null ||
                subByVar[t.variable] != null ||
                subDerivedVars[t.variable] != null ||
                t.variable in skillVarSet
            ) {
                continue
            }
            val d = tracker.of(t.variable)
            sum += t.maxContribution(d)
        }
        return sum
    }
    // The conversion reads preSubStat(from) = the BASE constant + item/rune/skill sources — passives
    // and other subs' critM enter the sheet after it, so only critMBase converts here; the item/rune
    // part converts per-Raw ([convertRaw]) and the skill part per-point (branchInfos).
    // Forced-sub unconditional credits fold in as constants: they are always equipped, so their
    // value effects are part of every state's baseline and their PERMANENT crit/AP is part of the
    // sheet floor every pre-combat window reads. Forced-sub critM is NOT world-converted: a
    // conversion reads preSubStat (no subs) and world C zeroes only PRE-COMBAT critM sources — a
    // start-of-combat sub critM legally survives both.
    val mConst = mastery.constant + passivePart(masteryTerms) + convGain(critMBase) + forcedMConst
    val diConst = diBase + passivePart(diTerms) + forcedDiConst
    val critMConst = cmWorld(critMBase) + (if (critSecret != null) 0L else passivePart(critMTerms)) + forcedCmConst
    val critConst = critBase + passivePart(critTerms) + forcedPermCritTotal
    val apConst = apBase + passivePart(apTerms) + forcedPermApTotal

    // ---- MP plumbing for MP-sourced DI ramps (Featherweight) ---------------------------------------
    // The ramp's value depends on the build's MP, which competes with MASTERY for the same item slots —
    // a coupling visible only when item MP rides the (di, graw, mp) frontier itself (an item set that
    // maxes mastery is not the one that maxes MP; every aggregate MP bound stayed loose). Item MP is
    // carried per Raw; everything item-independent — base, passives, skills, subs, and the rings' MP
    // (the ring stage collapses to graw) — folds into [mpFreeMax] at its own sound max. Disabled
    // (ramps keep their tracked optimistic cap) when %-MP skills make the split unsound to price.
    val hasMpRampSubs = subModel.subVars.keys.any { it.perStatStep?.source == Characteristic.MOVEMENT_POINT }
    val mpRampEnabled = hasMpRampSubs && skillTerms.percent[Characteristic.MOVEMENT_POINT].orEmpty().isEmpty()
    val mpI: Map<Equipment, Long>
    val mpS: Map<Sublimation, Long>
    val mpSkillByVar: Map<IntVar, Long>
    val mpFreeMax: Long
    if (mpRampEnabled) {
        val (mpTermsAll, mpBase) = prePercentTermsFor(Characteristic.MOVEMENT_POINT)
        val mpTerms = dropMoved(mpTermsAll)
        mpI = perCarrierContribution(mpTerms)
        // Sub MP rides the sub's own Raw (arrives only WITH the sub — Swiftness II's +1 MP no longer
        // splits from its −10 DI); skill MP is a priced branch-cell axis; ring MP rides the explicit
        // ring options. mpFreeMax keeps only what no DP axis carries: base + passives.
        mpS = perSubValue(mpTerms)
        val skillVarsForMp = skillVars.values.toSet()
        mpSkillByVar =
            mpTerms
                .filter { it.variable in skillVarsForMp }
                .groupBy { it.variable }
                .mapValues { (_, ts) -> ts.sumOf { it.coefficient } }
        // A forced conditional sub whose MP would need per-state gating has no machinery — bail.
        if (forcedCondCredits.any { (mpS[it.first] ?: 0L) != 0L }) return Long.MAX_VALUE
        // FORCED subs' MP is always equipped (Swiftness +1 / Heavy Armor −1): an exact, always-on
        // part of the ramp source. passivePart skips sub-var terms, so this is the only credit.
        val forcedSubMp = forcedPlainSubs.sumOf { mpS[it] ?: 0L }
        // Epic/relic sub MP cannot ride the frontier (their stage runs after the ramp valuation) —
        // keep it free but bounded to the ≤1-epic + ≤1-relic slots (none carries MP today). A slot
        // occupied by a forced plain sub contributes via forcedSubMp instead.
        val mpRaritySubFree =
            subModel.subVars.keys
                .filter { it.rarity != SublimationRarity.NORMAL && it !in forcedSubs }
                .filter { !(it.rarity == SublimationRarity.EPIC && forcedEpicPlain) }
                .filter { !(it.rarity == SublimationRarity.RELIC && forcedRelicPlain) }
                .mapNotNull { mpS[it]?.takeIf { v -> v > 0L } }
                .sortedDescending()
                .take(2)
                .sum()
        mpFreeMax = mpBase + passivePart(mpTerms) + mpRaritySubFree + forcedSubMp
    } else {
        mpI = emptyMap()
        mpS = emptyMap()
        mpSkillByVar = emptyMap()
        mpFreeMax = 0L
    }

    // Per objective-relevant skill var: its per-point contribution to crit / ap / di / mastery / critM
    // (read from the model's own term coefficients), and the points it can take (its cap ∧ the pool).
    data class SkillVarInfo(
        val crit: Int,
        val ap: Int,
        val di: Long,
        val m: Long,
        val critM: Long,
        val cap: Int,
        val mp: Long = 0L,
    )

    fun skillCoef(
        terms: List<Term>,
        v: IntVar,
    ): Long = terms.filter { it.variable == v }.sumOf { it.coefficient }

    fun branchInfos(
        chars: List<SkillCharacteristic>,
        pool: Int,
    ): List<SkillVarInfo> =
        chars.mapNotNull { ch ->
            val v = skillVars[ch] ?: return@mapNotNull null
            val crit = skillCoef(critTerms, v)
            val ap = skillCoef(apTerms, v)
            val di = skillCoef(diTerms, v)
            val m = skillCoef(masteryTerms, v)
            val cm = skillCoef(critMTerms, v)
            val mp = mpSkillByVar[v] ?: 0L
            if (crit == 0L && ap == 0L && di == 0L && m == 0L && cm == 0L && mp == 0L) {
                null
            } else {
                // Skill critM is a pre-sub source: under a taken conversion its per-point value feeds
                // mastery instead (ceiling per point ≥ the floored total — see convGain).
                SkillVarInfo(crit.toInt(), ap.toInt(), di, m + convGain(cm), cmWorld(cm), minOf(ch.maxPointsAssignable, pool), mp)
            }
        }

    val cs = params.character.characterSkills
    val skillBranches =
        listOf(cs.strength, cs.intelligence, cs.agility, cs.luck, cs.major)
            .mapNotNull { b ->
                branchInfos(b.getCharacteristics(), b.maxPointsToAssign)
                    .takeIf { it.isNotEmpty() }
                    ?.let { b.maxPointsToAssign to it }
            }
    // The per-branch knapsack assumes ≤1 crit var and ≤1 ap var, and no var mixing a cost dimension
    // (crit/ap) with anything else. True for the current skill tree; bail otherwise (sound fallback).
    for ((_, infos) in skillBranches) {
        if (infos.count { it.crit != 0 } > 1 || infos.count { it.ap != 0 } > 1) return Long.MAX_VALUE
        if (infos.any { (it.crit != 0 || it.ap != 0) && (it.di != 0L || it.m != 0L || it.critM != 0L || it.mp != 0L) }) {
            return Long.MAX_VALUE
        }
        if (infos.any { it.crit != 0 && it.ap != 0 }) return Long.MAX_VALUE
    }

    fun raw(e: Equipment) =
        Raw(
            diI[e] ?: 0,
            mI[e] ?: 0,
            cmI[e] ?: 0,
            // ap/crit are charged EXACTLY, negatives included: flooring a Souvenir's −1 AP at 0 let
            // the DP host it for free, certifying a real AP-14 build into the AP-16 cell.
            (apI[e] ?: 0).toInt(),
            (crI[e] ?: 0).toInt(),
            if (e.rarity ==
                Rarity.EPIC
            ) {
                1
            } else {
                0
            },
            if (e.rarity == Rarity.RELIC) 1 else 0,
            // mp stays floored at 0: it is a VALUE axis (feeds the MP→DI ramp), never a cell
            // coordinate, so the floor only widens the bound (sound) — negative-MP tank items
            // would otherwise drag the frontier for no soundness gain.
            (mpI[e] ?: 0L).coerceAtLeast(0L)
        )

    fun rawSub(s: Sublimation): Raw =
        Raw(
            diS[s] ?: 0,
            mS[s] ?: 0,
            cmS[s] ?: 0,
            (apS[s] ?: 0).toInt(),
            (crS[s] ?: 0).toInt(),
            if (s.rarity ==
                SublimationRarity.EPIC
            ) {
                1
            } else {
                0
            },
            if (s.rarity == SublimationRarity.RELIC) 1 else 0,
            // EXACT (not clamped ≥ 0): a sub's MP malus must debit the state's mp axis, or an MP-sourced
            // ramp is valued at phantom MP. Concrete fantasy the old clamp caused: Heavy Armor I (−1 max
            // MP) + Featherweight I (+6% DI per MP above 4) on a 5-MP build — the model prices FW at 0
            // (MP drops to 4), the clamped certifier credited +6 DI on top of HA's +10 and inflated the
            // lvl-110 cell-13 bound past the true optimum (badge stuck at ProvenWithin, E8 unreachable).
            // Sound: non-ramp subs run BEFORE ramp subs (see orderedNormal's sort), so the debit is the
            // real build's MP at ramp-valuation time — tighter, never below a real build's value.
            mpS[s] ?: 0L
        )

    // Max-damage runes fold to ONE type per item (maxDamageRuneChoiceCollapse): the best M-feeding
    // mastery rune OR the critical-mastery rune — never both. perCarrierContribution counted BOTH
    // (mI has the mastery rune, cmI the critM rune), so split a dual-rune item into two Raw options
    // (mastery-rune-on / critM-rune-on); the DP keeps whichever wins at each crit rate c. Items with a
    // single always-on rune (or none) keep their single Raw. Bail on rune models we cannot mirror.
    val rangeBandMasteryChar = scenario.rangeBand.masteryCharacteristic
    if (runeModel.runeVars.isNotEmpty()) {
        if (!runeModel.singleTypePerItem) return Long.MAX_VALUE
        val allowed = setOf(rangeBandMasteryChar, Characteristic.MASTERY_CRITICAL)
        if (runeModel.runeVars.any { (_, perStat) -> perStat.keys.any { it !in allowed } }) return Long.MAX_VALUE
    }

    // Item + rune critM is a pre-sub source: under a taken conversion it feeds mastery instead.
    // Applied AFTER the rune-option split so each option converts exactly what it actually carries.
    fun convertRaw(r: Raw): Raw = if (convTaken == null && critSecret == null) r else r.copy(m = r.m + convGain(r.critM), critM = cmWorld(r.critM))

    fun rawOptions(e: Equipment): List<Raw> {
        val base = raw(e)
        runeModel.runeVars[e]?.get(Characteristic.MASTERY_CRITICAL) ?: return listOf(convertRaw(base))
        val slots = e.maxShardSlots.toLong()
        val runeMastery = runeModel.coefficientFor(e, rangeBandMasteryChar) * slots
        val runeCritM = runeModel.coefficientFor(e, Characteristic.MASTERY_CRITICAL) * slots
        // base counts BOTH runes; strip the one not chosen for each option.
        return listOf(convertRaw(base.copy(critM = base.critM - runeCritM)), convertRaw(base.copy(m = base.m - runeMastery)))
    }

    // mpI too: an MP-only item (pure-MP boots) has no damage stat yet is exactly what feeds an
    // MP-sourced ramp — omitting it would silently value the ramp at the item-free MP floor.
    val itemEquips = (diI.keys + mI.keys + cmI.keys + apI.keys + crI.keys + mpI.keys).distinct()

    // Forced-item pinning (P5.1): a forced SINGLE-OCCUPANCY slot is restricted to the forced-name options
    // and made a MANDATORY pick in the exact pass. The certifier does the restriction ITSELF (not only via
    // the upstream pool filter), so the exact ledger stays == the pinned CP-SAT optimum on a RAW pool too.
    //
    // Not pinned (⇒ the certifier BAILS, badge honestly absent):
    //  - WEAPONS: the combined 1H/off-hand/2H slot bakes in explicit empties.
    //  - RINGS: forcing one ring leaves the SECOND ring free (any distinct-name), which restricting the
    //    ring pool to the forced name can't express — a raw-pool `Twin + free-ring` build would be missed
    //    (an under-count). A sound two-pick "forced first + free second" ring rework is deferred.
    val forcedNames = params.forcedItems.map { it.lowercase() }.toSet()
    val forcedSlots =
        if (forcedNames.isEmpty()) {
            emptySet()
        } else {
            itemEquips.filter { it.name.fr.lowercase() in forcedNames }.map { it.itemType }.toSet()
        }
    val unpinnableForcedSlots =
        setOf(ItemType.ONE_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS, ItemType.TWO_HANDED_WEAPONS, ItemType.RING)
    if (forcedSlots.any { it in unpinnableForcedSlots }) return Long.MAX_VALUE

    fun keepIfForced(e: Equipment): Boolean = e.itemType !in forcedSlots || e.name.fr.lowercase() in forcedNames

    // Equipment vars are boolean, so the build equips TWO DISTINCT rings (Σ ringVar ≤ 2). Applying the
    // ring cells twice would let the certifier double the single best ring — keep rings per-equip and
    // pick the top-2 DISTINCT rings per cost cell below. Other slots flatten to their rune options.
    val itemsByType =
        itemEquips
            .filter { it.itemType != ItemType.RING }
            .filter { keepIfForced(it) }
            .groupBy { it.itemType }
            .mapValues { (_, equips) -> equips.flatMap { rawOptions(it) } }

    // Ring options keep their carrier's NAME: the model forbids wearing two same-name rings
    // (Σ same-fr-name ring vars ≤ 1 — a Wakfu rule), so the ring-pair stage must never pair a
    // Mythic with its own Legendary sibling. Both certificate fantasies at cell 16 were exactly
    // such pairs (Souvenir ancestral ×2, then Anneau Chuchotis ancestral ×2).
    data class RingEntry(
        val nameKey: String,
        val options: List<Raw>,
    )
    val ringOptionsByEquip =
        itemEquips
            .filter { it.itemType == ItemType.RING }
            .filter { keepIfForced(it) }
            .map { RingEntry(it.name.fr.lowercase(), rawOptions(it)) }
    // The ring stage keeps only each ring's best GRAW per cost cell — any ring DI would be silently
    // DROPPED (an under-count). No ring in the current dataset carries Damage Inflicted, but bail if
    // one ever does rather than certify a value below the true cell max.
    if (ringOptionsByEquip.any { e -> e.options.any { it.di != 0L } }) return Long.MAX_VALUE

    // Negative-stat items (Souvenir ancestral −1 max-AP, L'un seul −5 crit, Epaulectriques −10 crit)
    // are charged their EXACT cost. Clamping them at 0 shifted every build containing one into the
    // WRONG cell: a real AP-14 Souvenir-pair build certified as an AP-16 state and took cell 16's hit
    // multiplier — the certificate's residual fantasy at cells 15/16 — while CRIT_AT_MOST conditions
    // read an inflated pre-combat crit (an UNDER-count risk on the value side). The DP axes stay
    // non-negative by seeding the start state at (apOff, critOff): stored = real + offset, and the
    // offsets sum each slot's worst-case negative (rings twice, weapons as the worse of 1H+off vs 2H),
    // so no stage sequence can underflow.
    val weaponTypes = setOf(ItemType.ONE_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS, ItemType.TWO_HANDED_WEAPONS)

    fun worstAp(options: List<Raw>) = minOf(options.minOfOrNull { it.ap } ?: 0, 0)

    fun worstCrit(options: List<Raw>) = minOf(options.minOfOrNull { it.crit } ?: 0, 0)
    val weaponWorstAp =
        minOf(
            worstAp(itemsByType[ItemType.ONE_HANDED_WEAPONS].orEmpty()) + worstAp(itemsByType[ItemType.OFF_HAND_WEAPONS].orEmpty()),
            worstAp(itemsByType[ItemType.TWO_HANDED_WEAPONS].orEmpty())
        )
    val weaponWorstCrit =
        minOf(
            worstCrit(itemsByType[ItemType.ONE_HANDED_WEAPONS].orEmpty()) + worstCrit(itemsByType[ItemType.OFF_HAND_WEAPONS].orEmpty()),
            worstCrit(itemsByType[ItemType.TWO_HANDED_WEAPONS].orEmpty())
        )
    val nonWeaponSlotOptions = itemsByType.filterKeys { it !in weaponTypes }.values
    val allRingOptions = ringOptionsByEquip.flatMap { it.options }
    val apOff = -(nonWeaponSlotOptions.sumOf { worstAp(it) } + weaponWorstAp + 2 * worstAp(allRingOptions))
    val critOff = -(nonWeaponSlotOptions.sumOf { worstCrit(it) } + weaponWorstCrit + 2 * worstCrit(allRingOptions))

    // Effective crit clamps at critCap (the scorer's coerceIn(0, 100) upper side). A build whose
    // item+skill crit ALONE exceeds critCap − critConst is pruned above critItemHigh at every
    // enumerated c, so the DP would silently UNDER-count it. Unreachable with today's catalog
    // (max item+skill crit ≈ 80 at level 245 vs 97) — bail loudly if the data ever gets there.
    fun bestCrit(options: List<Raw>) = maxOf(options.maxOfOrNull { it.crit } ?: 0, 0)
    val weaponBestCrit =
        maxOf(
            bestCrit(itemsByType[ItemType.ONE_HANDED_WEAPONS].orEmpty()) + bestCrit(itemsByType[ItemType.OFF_HAND_WEAPONS].orEmpty()),
            bestCrit(itemsByType[ItemType.TWO_HANDED_WEAPONS].orEmpty())
        )
    val ringBestCrit =
        ringOptionsByEquip
            .map { e -> bestCrit(e.options) }
            .sortedDescending()
            .take(2)
            .sum()
    val maxItemCrit = nonWeaponSlotOptions.sumOf { bestCrit(it) } + weaponBestCrit + ringBestCrit
    val subEntries =
        (diS.keys + mS.keys + cmS.keys + apS.keys + crS.keys)
            .distinct()
            .map { it to rawSub(it) }

    // (P5.3 forced-sub crediting/bailing happens in the classification above, before the constants —
    // it protects BOTH passes.)
    val critCap =
        scenario.critCapPercent
            .toLong()
            .coerceIn(0L, 100L)
            .toInt()
    // The crit DP dimension is item crit = c − critConst, enumerated over total crit c ∈ 0..critCap, so
    // it assumes critCap ≥ critConst (the always-on base+passive crit). If the scenario caps usable crit
    // BELOW the base (critCapPercent < base crit — never the production default of 100, but the CLI/GUI
    // can lower it), every c < critConst is skipped and the DP would under-count to 0. Bail to CP-SAT.
    if (critCap < critConst) return Long.MAX_VALUE
    val maxSkillCrit =
        skillBranches.sumOf { (pool, infos) ->
            infos.firstOrNull { it.crit > 0 }?.let { it.crit * minOf(it.cap, pool) } ?: 0
        }
    // OVER-CAP cells: effective crit clamps at critCap (the scorer's coerceIn upper side), but item
    // crit rides BUNDLED on gear (you can't shed it without shedding the mastery it comes with), so a
    // top-mastery build can arithmetically exceed the cap — at 245 the per-slot crit maxes + skills
    // reach ~110. Those builds live in cells c > critCap (exact arithmetic bands as usual) VALUED at
    // the capped rate; without these cells they are pruned above critItemHigh at every c ≤ critCap
    // and the certificate silently UNDER-counts them. Budget-sub overshoot needs no extra cells: a
    // pure-crit sub wasted past the cap can be dropped for free (value unchanged, a slot back).
    // Forced start-of-combat crit is always present, so the top reachable arithmetic total includes it.
    val cEnumMax = maxOf(critCap, (critConst + maxItemCrit + maxSkillCrit + forcedStartCritTotal).toInt())
    val apHigh = (apTarget - apConst).toInt()
    // Cells BELOW the AP constant require charging net-negative AP (wearing −AP gear to drop below the
    // always-on AP). The exact negative-AP DP UNDER-COUNTS that charge — a systematic, sometimes
    // double-digit-% under-count the P6.1 fuzz lock surfaced across many random pools (exact < CP-SAT
    // ONLY ever at apHigh < 0; at/above the constant the exact pass is exact). An under-count is the one
    // fatal class, so the exact pass BAILS on every below-constant cell (Long.MAX_VALUE → exact-bail
    // marker); the orchestrator keeps the SOUND fast bound there (fast ≥ CP-SAT is locked and holds on
    // every below-base cell), which still eliminates these low-AP cells — the optimum always wants MORE
    // AP, never less, so a below-base cell is never the survivor a tight bound would be needed for. The
    // old threshold `apHigh < -apOff` (only the arithmetically-infeasible floor at −apOff) let the buggy
    // negative-charge band [−apOff, 0) through and emit an unsound (too-low) exact value.
    if (apHigh < 0) return Long.MAX_VALUE
    // The DP's `n` dimension counts NORMAL-slot consumers ONLY: transition subs taken by the normal stages
    // plus the budget subs charged at harvest. Wakfu allows 10 normal subs (one per distinct ≥3-socket
    // carrier) + 1 epic + 1 relic ON TOP; the epic/relic subs ride their DEDICATED slots ([applyRaritySub]
    // adds at the SAME n) and must NOT charge this cap. Accordingly a force-taken conversion or a forced
    // plain sub charges a normal slot only when it is NORMAL rarity — an EPIC/RELIC one consumes its rarity
    // slot instead (already enforced: the matching rarity stage is skipped/emptied and the harvest requires
    // the equipped epic/relic item). Critical Secret is wrapper-guaranteed EPIC — never a normal charge.
    // (The alternative — an n that counts TOTAL subs, capped at 12, filtered by `n − epicItem − relicItem
    // ≤ 10` at harvest — was tried and is LOOSE: it widened every take(subCap)/topK budget-and-hiding
    // capacity and let states charge 11–12 normal-slot subs whenever epic/relic ITEMS were merely equipped;
    // the lvl-110 cell-13 bound crossed the true optimum and the badge could never be won. See
    // docs/SUBLIMATION_STACKING_PLAN.md.)
    val subCap =
        (System.getenv("WAKFU_MAX_DAMAGE_CERT_SUBCAP")?.toIntOrNull() ?: MAX_NORMAL_SUBLIMATIONS.toInt()) -
            (if (convTaken?.rarity == SublimationRarity.NORMAL) 1 else 0) -
            forcedPlainSubs.count { it.rarity == SublimationRarity.NORMAL }
    if (subCap < 0) return Long.MAX_VALUE

    // --- Sublimation couplings (mirror createSublimationModel) -------------------------------
    // Subs touch DI / crit% / AP / mastery (no current choosable sub gives mastery, but a FLAT one
    // would fold into graw via grawOf — kept fully general). Couplings vs the old free 0/1 pool:
    //  • epic/relic subs need an equipped epic/relic ITEM (Σ subRarity ≤ Σ itemRarity); modeled as a
    //    dedicated slot requiring state itemRarity≥1 — they do NOT consume the item's own ≤1 cap.
    //  • a NORMAL sub needs a distinct ≥3-socket carrier item (Σ normalSub ≤ Σ carriers); vacuous
    //    when there are ≥ that many carrier slots (the build hosts a damage-irrelevant carrier in
    //    any otherwise-spent slot) — guarded, else bail.
    //  • conditions read PRE-sub build stats. AP/CRIT are tracked, so gated EXACTLY; a defensive
    //    condition a max-damage build never satisfies (secMast≤0 when the objective stacks a
    //    secondary mastery, so satisfying it strips M to its elemental part; block≥40) is dropped
    //    (sound, locked by ==CP-SAT). Conditions a damage build naturally meets are credited.
    // Crit reaches a total `c` from items (the tracked DP dimension) OR pure-crit subs (a free
    // budget). The graw-max build sources crit from subs first, so pre-sub crit = c − subCrit;
    // tracking ITEM crit lets every CRIT_AT_MOST condition be gated exactly per state.
    val objectiveSecondaryOverlap =
        scenarioMasteryStats(scenario).any { it in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS }

    fun structurallyDropped(sub: Sublimation): Boolean {
        val cond = sub.condition ?: return false
        return when (cond.type) {
            SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ->
                (cond.value ?: 0) <= 0 && objectiveSecondaryOverlap
            SublimationConditionType.BLOCK_AT_LEAST -> true
            else -> false
        }
    }

    // DIAGNOSTIC ablation hook (manual audits only — never set in production/tests): excluding a
    // sub UNDER-counts by design; it exists to quantify each sub's contribution to a cell.
    val ablatedIds =
        System
            .getenv("WAKFU_MAX_DAMAGE_CERT_EXCLUDE_SUBS")
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()
    val keptSubs =
        subEntries
            .filter { (sub, _) ->
                // Exclude FORCED subs (P5.3): their SINGLE base copy is already applied via the constants +
                // slot charge, so leaving it in the OPTIONAL pools (budgets / transitions) would double-count it.
                sub !in forcedSubs &&
                    !structurallyDropped(sub) &&
                    sub.stateId !in ablatedIds &&
                    (weaponsRestricted || sub.condition?.type != SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED)
            }
            // STACKING: a cumulable NORMAL sub can be socketed up to [Sublimation.maxCopies] times, its value scaling
            // exactly k× (the FLOOR maxCopies keeps every copy full — constant marginal). Model each copy as an
            // INDEPENDENT normal-slot unit by duplicating the entry: the pools (budget / transition) then treat the
            // copies identically, the DP's `n ≤ subCap` (10 normal) + the carrier bound cap the total, and picking
            // more copies only raises a sound upper bound. maxCopies == 1 (non-cumulable, epic/relic) ⇒ no change.
            // (The DP passes do NOT run one stage per duplicate — see [normalTransitionStages].)
            .flatMap { entry -> List(entry.first.maxCopies) { entry } } +
            // FORCED cumulable subs stack too (the model gives their pinned base var free copy vars). The BASE copy
            // rides the constants above; its `maxCopies − 1` EXTRA copies are OPTIONAL — the solver takes one only
            // when the normal slot it charges is worth its value — so they belong in the optional pools, exactly
            // like a choosable sub's copies. Adding fewer would UNDER-count a stacked forced build (a wrong badge);
            // adding the base again would double-count it. A cumulable sub is unconditional by construction
            // ([Sublimation.maxCopies] demands no condition / conversion / ramp / best-element), so it can never be
            // the world's `convTaken` / `critSecret` special, and epic/relic are never cumulable.
            subEntries
                .filter { (sub, _) ->
                    sub in forcedSubs &&
                        sub.rarity == SublimationRarity.NORMAL &&
                        sub.maxCopies > 1 &&
                        !structurallyDropped(sub) &&
                        sub.stateId !in ablatedIds
                }.flatMap { entry -> List(entry.first.maxCopies - 1) { entry } }

    // Pure-crit / pure-AP subs (a crit%-only or AP-only effect) form free BUDGETS that fill the gap
    // between the build's PRE-sub crit/AP (the tracked DP dimensions) and the pinned total; every other
    // kept sub is a frontier transition (di + graw). The budgets make CRIT_AT_MOST / AP_AT_MOST exact
    // per state (pre-sub crit = critConst + critDim; pre-sub AP = apConst + apDim). Vivacity is +1 AP,
    // Carapace −2 AP (lets the build over-equip AP gear, the budget pulls the total back to the pin). A
    // kept sub mixing crit% or AP with di/mastery would need an extra source axis — bail instead.
    fun isPureCrit(r: Raw) = r.crit > 0 && r.di == 0L && r.m == 0L && r.critM == 0L && r.ap == 0 && r.mp == 0L

    fun isPureAp(r: Raw) = r.ap != 0 && r.di == 0L && r.m == 0L && r.critM == 0L && r.crit == 0 && r.mp == 0L
    if (keptSubs.any { (it.second.crit != 0 || it.second.ap != 0) && !isPureCrit(it.second) && !isPureAp(it.second) }) {
        return Long.MAX_VALUE
    }
    val critBudgetSubs = keptSubs.filter { isPureCrit(it.second) && it.first != critSecretExcluded }
    val apBudgetSubs = keptSubs.filter { isPureAp(it.second) }
    // The budget pools charge NORMAL sub slots at harvest ([minSubsToCover] counts against `n0 + gaps ≤
    // subCap`); a pure-crit/pure-AP sub of EPIC/RELIC rarity would ride its dedicated rarity slot instead —
    // an un-modeled charge shape, so BAIL (always sound). The force-taken specials are exempt: world C's
    // Critical Secret (EPIC) deliberately sits in the capacity pool while [critBudgetSortedForCharge]
    // excludes it from charging. No other non-NORMAL pure-crit/AP sub exists in the current choosable set.
    if ((critBudgetSubs + apBudgetSubs).any { it.first != critSecret && it.first != convTaken && it.first.rarity != SublimationRarity.NORMAL }) {
        return Long.MAX_VALUE
    }

    fun isTransition(r: Raw) = !isPureCrit(r) && !isPureAp(r)
    val normalTransitionSubs =
        keptSubs.filter { isTransition(it.second) && it.first.rarity == SublimationRarity.NORMAL }
    // MULTIPLICITY ENCODING for the DP passes: [keptSubs] duplicates a cumulable sub so every
    // slot-COUNTING consumer (budgets, segment edges, minSubsToCover) sees one unit per copy — but the
    // DP must NOT run one stage per copy. k identical stages double the stage count AND make stage k
    // sweep a frontier already inflated by the sub's own earlier copies (measured 3.4× on the lvl-110
    // badge proof, ≥7.8× on a lvl-245 back+berserk request). Collapse the duplicates into ONE stage per
    // sub carrying its multiplicity: taking j ∈ 1..mult copies in that stage adds exactly j× the
    // single-copy contribution — constant per copy, because a mult > 1 sub is unconditional and never a
    // ramp/conversion (all guaranteed by [Sublimation.maxCopies]) — so the reachable state set, and
    // therefore every certified value, is IDENTICAL to the per-copy encoding; only the stage count drops.
    val normalTransitionStages =
        normalTransitionSubs
            .groupBy { it.first }
            .map { (sub, entries) -> Triple(sub, entries.first().second, entries.size) }
    // A forced plain EPIC/RELIC sub occupies THE ≤1 slot of its rarity ⇒ no choosable sub of that
    // rarity can also be socketed — empty its stage (used by BOTH passes).
    val epicSubs =
        if (forcedEpicPlain) {
            emptyList()
        } else {
            keptSubs.filter { isTransition(it.second) && it.first.rarity == SublimationRarity.EPIC }
        }
    val relicSubs =
        if (forcedRelicPlain) {
            emptyList()
        } else {
            keptSubs.filter { isTransition(it.second) && it.first.rarity == SublimationRarity.RELIC }
        }

    // ---- FAMILY BUDGETS (v15): mono-axis unconditional transition subs leave the DP -----------------
    // A pure-DI or pure-mastery unconditional sub trades against nothing per copy — the optimal k-slot
    // selection from such a family is a sorted prefix, exactly like the existing pure-crit / pure-AP
    // budgets. So they are pulled OUT of the DP stages (each stage sweeps the whole frontier, and the
    // DI × mastery × copies combinatorics were what inflated it under stacking) and priced at HARVEST:
    // free slots = subCap − n0 − critGapSubs − apGapSubs, and the bound enumerates every split
    // k_DI + k_graw ≤ free over the two prefix-sum arrays. The reachable value set is IDENTICAL to the
    // staged encoding (sorted-prefix selection is exact for a mono-axis family, and slots are charged
    // the same way), so every certified value must be unchanged — locked by the fuzz == CP-SAT suite
    // and the banked 245 oracle. Anything conditional / ramp / conversion / best-element keeps its
    // exact per-state DP stage; an all-zero Raw (e.g. an off-element DI sub in a mono-element
    // scenario) is dropped outright (it can never move a max, it only wasted a stage).
    fun isBudgetEligible(sub: Sublimation) = sub.condition == null && sub.perStatStep == null && sub.conversion == null && sub.bestElementConcentration == null

    fun isZeroRaw(r: Raw) = r.di == 0L && r.m == 0L && r.critM == 0L && r.mp == 0L && r.crit == 0 && r.ap == 0

    fun isPureDi(r: Raw) = r.di != 0L && r.m == 0L && r.critM == 0L && r.mp == 0L && r.crit == 0 && r.ap == 0

    fun isPureGraw(r: Raw) = r.di == 0L && (r.m != 0L || r.critM != 0L) && r.mp == 0L && r.crit == 0 && r.ap == 0
    // One entry PER COPY (the multiplicity re-expands here — a family unit is one socketed copy).
    val diBudgetUnits =
        normalTransitionStages
            .filter { (sub, r, _) -> isBudgetEligible(sub) && isPureDi(r) && r.di > 0 }
            .flatMap { (_, r, mult) -> List(mult) { r.di } }
    val grawBudgetUnits =
        normalTransitionStages
            .filter { (sub, r, _) -> isBudgetEligible(sub) && isPureGraw(r) }
            .flatMap { (_, r, mult) -> List(mult) { r.m to r.critM } }
    // What is LEFT for the DP: mixed-axis (DI↔MP), ramps, conditionals — and a pure-DI ≤ 0 unit is
    // dropped like a zero Raw (a maximizing bound never sockets a strictly harmful unconditional sub).
    val stagedTransitions =
        normalTransitionStages.filter { (sub, r, _) ->
            !isZeroRaw(r) &&
                !(isBudgetEligible(sub) && isPureDi(r)) &&
                !(isBudgetEligible(sub) && isPureGraw(r))
        }
    val diBudgetSorted = diBudgetUnits.sortedDescending()
    val diPrefix =
        LongArray(minOf(diBudgetSorted.size, subCap) + 1).also { p ->
            for (i in 1 until p.size) p[i] = p[i - 1] + diBudgetSorted[i - 1]
        }

    // The graw value of a mastery/critM unit depends on the crit rate it is folded at — build the
    // sorted prefix per effective crit (a handful of units; negligible). Negative-valued units at this
    // c are excluded (never part of a max).
    fun grawBudgetPrefix(cEff: Long): LongArray {
        val vals =
            grawBudgetUnits
                .map { (m, cm) -> (400L + cEff) * m + 5L * cEff * cm }
                .filter { it > 0L }
                .sortedDescending()
        val n = minOf(vals.size, subCap)
        val prefix = LongArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + vals[i]
        return prefix
    }

    // The harvest bound over every budget split: max over k_DI + k_graw ≤ free of
    // (dBase + diPrefix[k_DI]) × (gBase + gp[k_graw]). Both prefixes are non-decreasing, so for a fixed
    // k_DI only the k_graw ENDPOINTS can win (max if the DI factor is positive, 0 if it is negative) —
    // ≤ 2·(free+1) products per point, free ≤ subCap.
    fun budgetMax(
        dBase: Long,
        gBase: Long,
        free: Int,
        gp: LongArray,
    ): Long {
        var bestV = dBase * gBase
        if (free <= 0 || (diPrefix.size == 1 && gp.size == 1)) return bestV
        for (kd in 0..minOf(free, diPrefix.size - 1)) {
            val d = dBase + diPrefix[kd]
            val kg = minOf(free - kd, gp.size - 1)
            val vMax = d * (gBase + gp[kg])
            if (vMax > bestV) bestV = vMax
            val v0 = d * gBase
            if (v0 > bestV) bestV = v0
        }
        return bestV
    }

    // Budgets deliberately OVER-count (sound: a wider budget only raises the upper bound, never cuts
    // a build) but are capped at their top-[subCap] values — a build can never socket more than
    // subCap subs, so anything beyond that is pure slack. Forced start-of-combat crit joins the
    // budget as an always-present part (it consumes no optional slot — its slot is already charged):
    // the item-crit band must reach down by it, or a real build wearing low-crit gear over the forced
    // crit would fall outside the band and be silently under-counted.
    val maxSubCrit =
        critBudgetSubs
            .map { it.second.crit.toLong() }
            .sortedDescending()
            .take(subCap)
            .sum() + forcedStartCritTotal
    // Sub AP ranges over [minSubAp, maxSubAp]; items + skills supply the pre-sub AP dimension and the
    // budget fills the gap to the pin, so apDim ranges [apHigh − maxSubAp, apHigh − minSubAp].
    val maxSubAp =
        apBudgetSubs
            .map { it.second.ap }
            .filter { it > 0 }
            .sortedDescending()
            .take(subCap)
            .sum()
    val minSubAp =
        apBudgetSubs
            .map { it.second.ap }
            .filter { it < 0 }
            .sorted()
            .take(subCap)
            .sum()
    val apCeil = apHigh - minSubAp
    // No floor at 0: negative-AP items (Souvenir ancestral −1 max-AP) can pull the item+skill AP
    // below zero while the plus-budget (Vivacity…) still covers the gap to the pin.
    val apFloor = apHigh - maxSubAp

    // Start-of-combat vs permanent crit/AP for the pre-combat windows (permCritOf/permApOf are defined
    // with the forced-sub classification above): a start-of-combat crit budget sub (Secondary
    // Devastation II, +7) still reaches the in-combat crit `c` but does NOT raise the pre-combat crit a
    // CRIT_AT_MOST reads; a permanent one (Influence II, +15) does. So at a state the pre-combat crit
    // can be pushed as low as max(itemCrit, c − Σstart) (hide the rest behind start subs) or as high as
    // min(c, itemCrit + Σperm). Forced start crit is ALWAYS hiding (it is always equipped), so it joins
    // the Σstart capacity.
    fun topK(values: List<Long>): Long =
        values
            .filter { it > 0L }
            .sortedDescending()
            .take(subCap)
            .sum()
    val maxStartCrit = topK(critBudgetSubs.map { it.second.crit - permCritOf(it.first) }) + forcedStartCritTotal
    val maxPermCrit = topK(critBudgetSubs.map { permCritOf(it.first) })
    val maxStartAp = topK(apBudgetSubs.map { it.second.ap - permApOf(it.first) })
    val maxPermAp = topK(apBudgetSubs.map { permApOf(it.first) })

    // NO bail when the kept subs outnumber the sub slots or the ≥3-socket carriers: transition subs
    // are exactly counted by the DP's n ≤ subCap dimension, and the budget/carrier assumptions only
    // OVER-count what a real build can socket — an upper bound survives over-counting, never a bail.
    // (The old `keptSubs.size > subCap` / carrier-count bails made the certifier bail on EVERY cell
    // of the real catalog — ~30 damage-relevant choosable subs vs 10 slots.)

    // BUDGET SUBS CONSUME SLOTS TOO. The crit/AP gaps a harvested state claims are filled by budget
    // subs, and every one of them occupies a real sub slot the DP's n dimension never charged — so
    // the DP could take subCap DI transitions AND a full crit budget (phantom slots). At harvest,
    // charge each gap its MINIMUM cover count (greedy over the sorted budget values — no set of
    // fewer subs can cover the gap, so the charge never over-prunes a real build).
    val critBudgetSorted = critBudgetSubs.map { it.second.crit.toLong() }.filter { it > 0 }.sortedDescending()
    // World C: Critical Secret's crit is credited before counting gap subs (it is EPIC — it occupies
    // the dedicated epic sub slot, whose stage this world skips), and it never counts as one of the
    // gap-covering budget subs.
    val csWorldCrit = critSecret?.let { (crS[it] ?: 0L).coerceAtLeast(0L) } ?: 0L
    val critBudgetSortedForCharge =
        if (critSecret == null) {
            critBudgetSorted
        } else {
            critBudgetSubs
                .filter { it.first != critSecret }
                .map { it.second.crit.toLong() }
                .filter { it > 0 }
                .sortedDescending()
        }
    val apPlusSorted =
        apBudgetSubs
            .map { it.second.ap.toLong() }
            .filter { it > 0 }
            .sortedDescending()
    val apMinusSorted =
        apBudgetSubs
            .map { -it.second.ap.toLong() }
            .filter { it > 0 }
            .sortedDescending()

    fun minSubsToCover(
        gap: Long,
        sortedDesc: List<Long>,
    ): Int {
        if (gap <= 0) return 0
        var covered = 0L
        for ((k, v) in sortedDesc.withIndex()) {
            covered += v
            if (covered >= gap) return k + 1
        }
        return Int.MAX_VALUE // gap not coverable ⇒ the state is unreachable
    }

    // Whether [sub]'s condition can hold at a DP state with item crit/AP `preCrit`/`preAp` and total
    // in-combat crit `c`. The condition reads the PRE-COMBAT (character-sheet) crit/AP — base + item +
    // rune + skill + PERMANENT subs — NOT the start-of-combat budget (see [preCombatStat]). AT_MOST can
    // hide crit behind start-of-combat subs (min pre-combat = max(itemCrit, c − Σstart)); AT_LEAST can
    // stack permanent subs (max pre-combat = min(c, itemCrit + Σperm)). AP has no start-of-combat source
    // (Vivacity / Carapace are permanent), so pre-combat AP == the pinned total apTarget. Conditions on
    // stats the certifier does not track fall through to `true`.
    fun subAllowedAt(
        sub: Sublimation,
        preCrit: Int,
        preAp: Int,
        c: Int,
    ): Boolean {
        val cond = sub.condition ?: return true
        val n = (cond.value ?: 0).toLong()
        val preCombatCritMin = maxOf(critConst + preCrit, c - maxStartCrit)
        val preCombatCritMax = minOf(c.toLong(), critConst + preCrit + maxPermCrit)
        val preCombatApMin = maxOf(apConst + preAp, apTarget - maxStartAp)
        val preCombatApMax = minOf(apTarget.toLong(), apConst + preAp + maxPermAp)
        return when (cond.type) {
            SublimationConditionType.AP_AT_MOST -> preCombatApMin <= n
            SublimationConditionType.AP_AT_LEAST -> preCombatApMax >= n
            SublimationConditionType.AP_EXACT -> preCombatApMin <= n && n <= preCombatApMax
            SublimationConditionType.CRIT_AT_MOST -> preCombatCritMin <= n
            SublimationConditionType.CRIT_AT_LEAST -> preCombatCritMax >= n
            else -> true
        }
    }

    if (System.getenv("WAKFU_MAX_DAMAGE_CERT_DEBUG") == "1") {
        System.err.println(
            "CERT_DEBUG ap=$apTarget apBase=$apBase apConst=$apConst apHigh=$apHigh critCap=$critCap " +
                "critConst=$critConst diConst=$diConst critMConst=$critMConst mConst=$mConst " +
                "apOff=$apOff critOff=$critOff branches=${skillBranches.map { it.first }}"
        )
        System.err.println(
            "CERT_DEBUG_MP rampEnabled=$mpRampEnabled mpFreeMax=$mpFreeMax " +
                "mpItems=${mpI.entries.filter { it.value != 0L }.map { "${it.key.name.en}=${it.value}" }}"
        )
        for ((sub, _) in subModel.subVars) {
            val di = diS[sub] ?: 0L
            val m = mS[sub] ?: 0L
            val cm = cmS[sub] ?: 0L
            val ap = apS[sub] ?: 0L
            val cr = crS[sub] ?: 0L
            if (di == 0L && m == 0L && cm == 0L && ap == 0L && cr == 0L) continue
            System.err.println(
                "CERT_DEBUG_SUB id=${sub.stateId} '${sub.name.en}' rarity=${sub.rarity} kind=${sub.kind} " +
                    "cond=${sub.condition?.type}/${sub.condition?.value} forced=${sub in subModel.forced} " +
                    "di=$di m=$m critM=$cm ap=$ap crit=$cr"
            )
        }
        val socketByType =
            allEquips.groupBy { it.itemType }.mapValues { (_, es) ->
                "${es.count { it.maxShardSlots >= NORMAL_SUB_SOCKET_COST }}/${es.size}"
            }
        System.err.println("CERT_DEBUG_SOCKETS perType=$socketByType")
        val branches =
            mapOf(
                "STR" to params.character.characterSkills.strength,
                "INT" to params.character.characterSkills.intelligence,
                "AGI" to params.character.characterSkills.agility,
                "LUCK" to params.character.characterSkills.luck,
                "MAJOR" to params.character.characterSkills.major
            )

        fun coef(
            terms: List<Term>,
            v: IntVar,
        ) = terms.filter { it.variable == v }.sumOf { it.coefficient }
        for ((bn, branch) in branches) {
            System.err.println("CERT_DEBUG_BRANCH $bn pool=${branch.maxPointsToAssign}")
            for (ch in branch.getCharacteristics()) {
                val v = skillVars[ch] ?: continue
                val cm = coef(masteryTerms, v)
                val ccm = coef(critMTerms, v)
                val ccr = coef(critTerms, v)
                val cap = coef(apTerms, v)
                val cdi = coef(diTerms, v)
                if (cm == 0L && ccm == 0L && ccr == 0L && cap == 0L && cdi == 0L) continue
                System.err.println(
                    "CERT_DEBUG_SKILL $bn ${ch.characteristic} cap=${ch.maxPointsAssignable} " +
                        "m=$cm critM=$ccm crit=$ccr ap=$cap di=$cdi"
                )
            }
        }
        val epicItems = allEquips.count { it.rarity == Rarity.EPIC }
        val relicItems = allEquips.count { it.rarity == Rarity.RELIC }
        System.err.println("CERT_DEBUG_RARITY epicItems=$epicItems relicItems=$relicItems")
    }

    // Perf instrumentation (WAKFU_MAX_DAMAGE_CERT_STATS): aggregate DP size over every stage of every
    // crit step, so this pass's cost is a thermal-noise-free number (states + frontier points visited,
    // total Frontier.add calls). One CERT_STATS line per (world, cell) at the tail. No behavior change.
    val statsEnabled = System.getenv("WAKFU_MAX_DAMAGE_CERT_STATS") == "1"
    var statStages = 0L
    var statStates = 0L
    var statPoints = 0L
    if (statsEnabled) {
        Frontier.statsEnabled = true
        Frontier.statsAddCalls = 0L
    }

    // ======================= FAST tier-1 pass (P2) =======================================================
    // ONE 4-D-frontier DP (di, m, critM, mp) — graw NOT pre-folded — covering ALL crit levels at once,
    // harvested for every AP cell. Every simplification vs the exact pass is a SOUND OVER-COUNT (never an
    // under-count): ∃-relaxed sub-condition gates, rings as two independent slots (same-name pairs
    // allowed), skills valued by independent per-axis maxima. The `fast ≥ exact` lock guards soundness.
    if (fastAllCellsOut != null) {
        val maxCell = fastCellCount - 1
        // Stage-timing instrumentation (WAKFU_MAX_DAMAGE_CERT_TIMING=1): the per-pass DP-vs-harvest split —
        // the fast pass's own floor decomposition. No behavior change.
        val fastTimingEnabled = System.getenv("WAKFU_MAX_DAMAGE_CERT_TIMING") == "1"
        var fastDpNanos = 0L
        var fastHarvestNanos = 0L
        var fastHarvestCoordinates = 0L
        var fastSegmentsRun = 0
        var fastSegmentsSkipped = 0
        // Two-tier speed: allocate the per-crit-step harvest for the top cell (see [fastPerCOutHolder]).
        val fastPerCOut = fastPerCOutHolder?.let { h -> LongArray(cEnumMax + 1).also { h[0] = it } }
        // Floor speed: the ALL-cells per-(cell, crit-step) harvest (see [fastPerCellCOutHolder]).
        val fastPerCellC = fastPerCellCOutHolder?.let { h -> Array(fastCellCount) { LongArray(cEnumMax + 1) }.also { h[0] = it } }
        // Crit spans the FULL reachable range of the crit AXIS (no per-c band). The axis accumulates
        // item crit AND skill crit (the branch stage adds its crit points to it — `critItemHigh` in the
        // exact pass is a misnomer), so the ceiling must be their SUM: capping at maxItemCrit alone
        // pruned real item+skill-crit states and under-counted every cell (~0.5% on the panel).
        val critHighF = maxItemCrit + maxSkillCrit
        val critDimF = critHighF + 2 * critOff + 1
        val apCeilF = apCeil // apTarget was passed as the loosest cell, so this is the DP's AP ceiling.

        fun keyF(
            ap: Int,
            crit: Int,
            epic: Int,
            relic: Int,
            n: Int,
        ) = ((((ap.toLong() * critDimF + crit) * 2 + epic) * 2 + relic) * (subCap + 1)) + n

        // ∃-gate (P2.3, c-bucketed): allow a sub iff its condition holds for SOME (c ∈ [cLow,cHigh],
        // apTarget ∈ [0,maxCell]) — using the endpoint that makes it easiest. Over-allowing is a sound
        // over-count. The c range is the current gate BUCKET (see below), not always the full
        // [0,cEnumMax]: bucketing kills the worst contradiction (a CRIT_AT_MOST DI sub admitted via a
        // low c, then harvested at a high c stacking critM) because each bucket's harvest only reads
        // the c values its own gates were relaxed over.
        fun subAllowedExists(
            sub: Sublimation,
            preCrit: Int,
            preAp: Int,
            cLow: Int,
            cHigh: Int,
        ): Boolean {
            val cond = sub.condition ?: return true
            val n = (cond.value ?: 0).toLong()
            val critMinAchievable = maxOf(critConst + preCrit, cLow - maxStartCrit) // c pushed to cLow
            val critMaxAchievable = minOf(cHigh.toLong(), critConst + preCrit + maxPermCrit) // c = cHigh
            val apMinAchievable = maxOf(apConst + preAp, 0L - maxStartAp) // apTarget = 0
            val apMaxAchievable = minOf(maxCell.toLong(), apConst + preAp + maxPermAp) // apTarget = maxCell
            return when (cond.type) {
                SublimationConditionType.CRIT_AT_MOST -> critMinAchievable <= n
                SublimationConditionType.CRIT_AT_LEAST -> critMaxAchievable >= n
                SublimationConditionType.AP_AT_MOST -> apMinAchievable <= n
                SublimationConditionType.AP_AT_LEAST -> apMaxAchievable >= n
                SublimationConditionType.AP_EXACT -> apMinAchievable <= n && n <= apMaxAchievable
                else -> true
            }
        }

        // ---- coarse c-grid of 3-D passes (P2.6 — MEASURED as the tight/fast structure) ---------------
        // A single shared 4-D frontier (di, m, critM, mp) exploded combinatorially on the real catalog
        // (OOM uncapped; capped, its component-wise fold mixed one path's mastery with another's critM
        // and over-counted ~16 % even with NO subs at all once budget crit raised the harvest c). So:
        // split [0, cEnumMax] into SEGMENTS and run a 3-D (di, graw, mp) pass per segment — graw folded
        // at the segment's TOP crit (both coefficients of graw_c grow with c, so graw_top ≥ graw_c for
        // every c in the segment: sound). 3-D frontiers stay naturally small (scalar graw collapses the
        // m/critM tradeoff — the exact pass's shape), so no cap and no axis mixing; the looseness is
        // bounded by the grid step instead. Segment edges also sit on kept crit-condition thresholds
        // (CRIT_AT_MOST n flips at n + maxStartCrit; CRIT_AT_LEAST at n), making the per-segment
        // ∃-gates near-exact for free.
        val segmentEdges =
            (
                (0..cEnumMax step fastCSegmentStep) +
                    keptSubs.mapNotNull { (sub, _) ->
                        val cond = sub.condition ?: return@mapNotNull null
                        val n = (cond.value ?: 0).toInt()
                        when (cond.type) {
                            SublimationConditionType.CRIT_AT_MOST -> (n + maxStartCrit.toInt() + 1)
                            SublimationConditionType.CRIT_AT_LEAST -> n
                            else -> null
                        }
                    }
            ).filter { it in 0..cEnumMax }
                .distinct()
                .sorted()

        // Raw option lists are segment-independent — build once, fold per segment.
        val weaponRawsF: List<Raw> =
            if (weaponTypes.any { it in itemsByType }) {
                val oneH = itemsByType[ItemType.ONE_HANDED_WEAPONS].orEmpty()
                val offH = if (weaponsRestricted) emptyList() else itemsByType[ItemType.OFF_HAND_WEAPONS].orEmpty()
                val twoH = if (weaponsRestricted) emptyList() else itemsByType[ItemType.TWO_HANDED_WEAPONS].orEmpty()
                val oneOpts = listOf(Raw(0, 0, 0, 0, 0, 0, 0)) + oneH
                val offOpts = listOf(Raw(0, 0, 0, 0, 0, 0, 0)) + offH
                val combined = mutableListOf<Raw>()
                for (a in oneOpts) {
                    for (b in offOpts) {
                        if (a.epic + b.epic > 1 || a.relic + b.relic > 1) continue
                        combined.add(Raw(a.di + b.di, a.m + b.m, a.critM + b.critM, a.ap + b.ap, a.crit + b.crit, a.epic + b.epic, a.relic + b.relic, a.mp + b.mp))
                    }
                }
                // Identical Raw vectors are indistinguishable to the DP — dedupe ONCE per pass instead of
                // re-adding (and re-rejecting) them in perCostF at every segment; rune variants collide often.
                (combined + twoH).distinct()
            } else {
                emptyList()
            }
        // Rings: singles + DISTINCT-NAME pairs as ONE slot, mirroring the exact pass's name discipline
        // (two independent ring slots let the best ring pair with itself / its own rune variant).
        val ringRawsF = mutableListOf<Raw>()
        for (i in ringOptionsByEquip.indices) {
            val ei = ringOptionsByEquip[i]
            for (r in ei.options) {
                if (r.ap > apCeilF + apOff || r.crit > critHighF + critOff) continue
                ringRawsF += r
                for (j in i + 1 until ringOptionsByEquip.size) {
                    val ej = ringOptionsByEquip[j]
                    if (ej.nameKey == ei.nameKey) continue
                    for (r2 in ej.options) {
                        if (r.epic + r2.epic > 1 || r.relic + r2.relic > 1) continue
                        ringRawsF +=
                            Raw(
                                r.di + r2.di,
                                r.m + r2.m,
                                r.critM + r2.critM,
                                r.ap + r2.ap,
                                r.crit + r2.crit,
                                r.epic + r2.epic,
                                r.relic + r2.relic,
                                r.mp + r2.mp
                            )
                    }
                }
            }
        }

        // Identical single/pair ring Raw vectors — same dedupe rationale as the weapons above.
        val ringRawsDeduped = ringRawsF.distinct()

        val orderedNormalF = stagedTransitions.sortedBy { (sub, _, _) -> if (sub.perStatStep?.source == Characteristic.MOVEMENT_POINT) 1 else 0 }
        // Per-STAGE candidate-volume instrumentation (WAKFU_MAX_DAMAGE_CERT_STATS=1, valid at threads=1 —
        // the deltas read process-global Frontier counters): Frontier.add calls + points scanned per stage
        // label, summed over every segment of this pass; one CERT_STAGE_STATS line per world at the tail.
        val stageStatsEnabled = System.getenv("WAKFU_MAX_DAMAGE_CERT_STATS") == "1"
        val stageAdds = LinkedHashMap<String, Long>()
        val stageScans = LinkedHashMap<String, Long>()

        fun <T> stageStat(
            label: String,
            block: () -> T,
        ): T {
            if (!stageStatsEnabled) return block()
            val a0 = Frontier.statsAddCalls
            val s0 = Frontier.statsPointsScanned
            val r = block()
            stageAdds.merge(label, Frontier.statsAddCalls - a0, Long::plus)
            stageScans.merge(label, Frontier.statsPointsScanned - s0, Long::plus)
            return r
        }
        // Fast pass credits forced conditional DI UNCONDITIONALLY (sound over-count — never below a real
        // build, which applies it only when its condition holds — so `fast ≥ exact` holds; the exact pass
        // gates it per state). Forced FLAT DI is already in [diConst].
        val dConst = 100L + diConst + forcedCondDiTotal
        // Dense DP storage for the fast pass (the P1.3 pattern, ported): [keyF] is already a mixed-radix
        // index into apDimF × critDimF × 2 × 2 × (subCap+1), so a flat Frontier?[] replaces the old
        // HashMap<Long, Frontier> — no hashing, no Long boxing, no per-stage map allocation. MEASURED
        // (CERT_FAST_TIMING @245): the segment DPs were ~70 % of the fast tier. Two buffers double-buffer
        // each stage (allocated once per pass, cleared per segment/stage via the live list); the harvested
        // values are identical (same point sets — only iteration order changes, and every consumer is an
        // order-independent max / Pareto frontier).
        val apDimF = apCeilF + 2 * apOff + 1
        val denseBoxF = apDimF * critDimF * 4 * (subCap + 1)
        val bufAF = DenseDp(denseBoxF)
        val bufBF = DenseDp(denseBoxF)
        var dpF = bufAF
        var ndF = bufBF
        // A/B seam (off by default): AP feasibility depends only on the state's AP coordinate, so build
        // its exact ascending `(cell, gap-charge)` list once per pass instead of rechecking every cell
        // for every live state in every c segment.
        val indexedHarvestEnabled = CertifierTuning.indexedFastHarvestEnabled
        val indexedApCoordinates =
            if (indexedHarvestEnabled) {
                Array(apDimF) { storedAp ->
                    indexedFastHarvestApCoordinates(
                        stateAp = storedAp - apOff,
                        cellCount = fastCellCount,
                        apConst = apConst,
                        minSubAp = minSubAp,
                        maxSubAp = maxSubAp,
                        apPlusSorted = apPlusSorted,
                        apMinusSorted = apMinusSorted
                    )
                }
            } else {
                null
            }
        for ((si, cLow) in segmentEdges.withIndex()) {
            val cHigh = if (si + 1 < segmentEdges.size) segmentEdges[si + 1] - 1 else cEnumMax
            // Floor speed (tier-1.5 single-cell runs): if tier-1's step-8 bounds cap every crit step of
            // this segment at-or-below the incumbent threshold, its step-1 DP cannot change the clearing
            // decision (step-1 ≤ step-8 per c) — carry the sound step-8 values and skip the DP entirely.
            if (fastSegmentSkipUb != null &&
                (cLow..cHigh).all { (if (it < fastSegmentSkipUb.size) fastSegmentSkipUb[it] else 0L) <= fastSkipBelowRaw }
            ) {
                for (c in cLow..cHigh) {
                    val ub = if (c < fastSegmentSkipUb.size) fastSegmentSkipUb[c] else 0L
                    if (ub > fastAllCellsOut[maxCell]) fastAllCellsOut[maxCell] = ub
                    if (fastPerCOut != null && c < fastPerCOut.size && ub > fastPerCOut[c]) fastPerCOut[c] = ub
                }
                CertifierTuning.tier15SegmentsSkippedForTest.incrementAndGet()
                fastSegmentsSkipped++
                continue
            }
            fastSegmentsRun++
            val segmentDpStart = if (fastTimingEnabled) System.nanoTime() else 0L
            // Fold graw at the segment's top EFFECTIVE crit (values clamp at critCap like the harvest).
            val cEffHi = minOf(cHigh, critCap).toLong()

            fun grawOfF(r: Raw) = (400L + cEffHi) * r.m + 5L * cEffHi * r.critM

            dpF.clear()
            dpF.getOrPut(keyF(apOff, critOff, 0, 0, 0).toInt()).add(0, 0)

            // Carry every live state into [ndF] (the "skip this stage's option" transition), then swap.
            // Mirrors the old `nd[k] = fr.copy()` seeding, minus the HashMap.
            fun beginStageF() {
                ndF.clear()
                for (i in 0 until dpF.liveCount) {
                    val k = dpF.liveKeys[i]
                    dpF.slots[k]?.let { ndF.put(k, it.copy()) }
                }
            }

            fun endStageF() {
                val t = dpF
                dpF = ndF
                ndF = t
            }

            fun perCostF(options: List<Raw>): Map<Int, Frontier> {
                val m = HashMap<Int, Frontier>()
                for (r in options) {
                    if (r.ap > apCeilF + apOff || r.crit > critHighF + critOff) continue
                    val g = grawOfF(r)
                    if (r.di <= 0 && g <= 0 && r.epic + r.relic == 0 && r.crit == 0 && r.ap == 0 && r.mp == 0L) continue
                    val ck = (((r.ap + apOff) * critDimF + (r.crit + critOff)) * 2 + r.epic) * 2 + r.relic
                    m.getOrPut(ck) { Frontier() }.add(r.di, g, r.mp)
                }
                return m
            }

            fun applyCellsF(cells: Map<Int, Frontier>) {
                beginStageF()
                for (i in 0 until dpF.liveCount) {
                    val k = dpF.liveKeys[i]
                    val fr = dpF.slots[k] ?: continue
                    val n0 = k % (subCap + 1)
                    var rest = k / (subCap + 1)
                    val relic0 = rest % 2
                    rest /= 2
                    val epic0 = rest % 2
                    rest /= 2
                    val crit0 = rest % critDimF
                    val ap0 = rest / critDimF
                    for ((ck, cfr) in cells) {
                        val rrelic = ck % 2
                        val repic = (ck / 2) % 2
                        val rcrit = (ck / 4) % critDimF - critOff
                        val rap = (ck / 4) / critDimF - apOff
                        val ap1 = ap0 + rap
                        val crit1 = crit0 + rcrit
                        val epic1 = epic0 + repic
                        val relic1 = relic0 + rrelic
                        if (ap1 > apCeilF + 2 * apOff || crit1 > critHighF + 2 * critOff || epic1 > 1 || relic1 > 1) continue
                        val tgt = ndF.getOrPut(keyF(ap1, crit1, epic1, relic1, n0).toInt())
                        fr.forEachPoint { pd, pg, pm -> cfr.forEachPoint { qd, qg, qm -> tgt.add(pd + qd, pg + qg, pm + qm) } }
                    }
                }
                endStageF()
            }

            // Item slots (non-weapon), the grouped weapon slot, then the ring singles+pairs slot.
            for ((type, entries) in itemsByType) {
                if (type in weaponTypes) continue
                stageStat("slot:${type.name}") { applyCellsF(perCostF(entries)) }
            }
            if (weaponRawsF.isNotEmpty()) stageStat("weapons") { applyCellsF(perCostF(weaponRawsF)) }
            if (ringRawsDeduped.isNotEmpty()) stageStat("rings") { applyCellsF(perCostF(ringRawsDeduped)) }

            // Skills: per branch, enumerate the crit/ap/di/mp point split; the remaining points fill
            // graw greedily by rate at the segment's fold crit — the exact pass's own shape (exact at
            // the fold point, ≥ any c in the segment).
            fun branchCellsF(
                infos: List<SkillVarInfo>,
                pool: Int,
            ): Map<Int, Frontier> {
                val critVar = infos.firstOrNull { it.crit != 0 }
                val apVar = infos.firstOrNull { it.ap != 0 }
                val diVars = infos.filter { it.di != 0L }.sortedByDescending { it.di }
                val mpVars = infos.filter { it.mp > 0L && it.di == 0L && it.m == 0L && it.critM == 0L && it.crit == 0 && it.ap == 0 }.sortedByDescending { it.mp }
                val mpCapTotal = mpVars.sumOf { it.cap }
                val grawVars = infos.filter { it.di == 0L && it.crit == 0 && it.ap == 0 && it.mp == 0L }

                fun fillGraw(points: Int): Long {
                    var rem = points
                    var g = 0L
                    for (gv in grawVars.sortedByDescending { (400L + cEffHi) * it.m + 5L * cEffHi * it.critM }) {
                        if (rem <= 0) break
                        val per = (400L + cEffHi) * gv.m + 5L * cEffHi * gv.critM
                        if (per <= 0) continue
                        val take = minOf(rem, gv.cap)
                        g += take * per
                        rem -= take
                    }
                    return g
                }
                val diCapTotal = diVars.sumOf { it.cap }
                val cells = HashMap<Int, Frontier>()
                val critPts = critVar?.let { minOf(it.cap, pool) } ?: 0
                val apPtsMax = apVar?.let { minOf(it.cap, pool) } ?: 0
                for (ccPts in 0..critPts) {
                    val crit = ccPts * (critVar?.crit ?: 0)
                    if (crit > critHighF + critOff) break
                    for (apPts in 0..minOf(apPtsMax, pool - ccPts)) {
                        val ap = apPts * (apVar?.ap ?: 0)
                        if (ap > apCeilF + apOff) break
                        val rem0 = pool - ccPts - apPts
                        for (d in 0..minOf(rem0, diCapTotal)) {
                            var di = 0L
                            var left = d
                            for (dv in diVars) {
                                val take = minOf(left, dv.cap)
                                di += take * dv.di
                                left -= take
                                if (left <= 0) break
                            }
                            for (mpPts in 0..minOf(rem0 - d, mpCapTotal)) {
                                var mpv = 0L
                                var leftMp = mpPts
                                for (mv in mpVars) {
                                    val take = minOf(leftMp, mv.cap)
                                    mpv += take * mv.mp
                                    leftMp -= take
                                    if (leftMp <= 0) break
                                }
                                val ck = ((ap + apOff) * critDimF + (crit + critOff)) * 2 * 2
                                cells.getOrPut(ck) { Frontier() }.add(di, fillGraw(rem0 - d - mpPts), mpv)
                            }
                        }
                    }
                }
                return cells
            }
            for ((pool, infos) in skillBranches) {
                stageStat("skills") { applyCellsF(branchCellsF(infos, pool)) }
            }

            // Normal transition subs (j ∈ 0..mult each, n ≤ subCap; ap==crit==0). Ramps LAST, valued per
            // point from its mp axis. ∃-gate over the state's item crit/ap within this segment's c range.
            for ((sub, r, mult) in orderedNormalF) {
                if (r.ap > apCeilF) continue
                stageStat("subs") {
                    val g = grawOfF(r)
                    val pssMp =
                        if (mpRampEnabled) {
                            sub.perStatStep?.takeIf { it.source == Characteristic.MOVEMENT_POINT && it.target == Characteristic.DAMAGE_INFLICTED }
                        } else {
                            null
                        }
                    // A ramp's per-copy DI reads the state's accumulated MP — never stacked (maxCopies > 1
                    // demands perStatStep == null); defensive clamp so a future data drift stays sound.
                    val stageMult = if (pssMp == null) mult else 1
                    beginStageF()
                    for (i in 0 until dpF.liveCount) {
                        val k = dpF.liveKeys[i]
                        val fr = dpF.slots[k] ?: continue
                        val n0 = k % (subCap + 1)
                        if (n0 >= subCap) continue
                        var rest = k / (subCap + 1)
                        rest /= 2
                        rest /= 2
                        val crit0 = rest % critDimF
                        val ap0 = rest / critDimF
                        if (!subAllowedExists(sub, crit0 - critOff, ap0 - apOff, cLow, cHigh)) continue
                        for (j in 1..minOf(stageMult, subCap - n0)) {
                            val tgt = ndF.getOrPut(k + j) // n0 → n0+j, other coords unchanged (ap==crit==0)
                            fr.forEachPoint { pd, pg, pm ->
                                val di1 = if (pssMp == null) r.di else minOf(r.di, pssMp.contribution((mpFreeMax + pm).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()).toLong())
                                tgt.add(pd + j * di1, pg + j * g, pm + j * r.mp)
                            }
                        }
                    }
                    endStageF()
                }
            }

            // Epic/relic sub slot: at most one, on an equipped epic/relic item (state rarity ≥ 1). It rides
            // its DEDICATED slot, so it does NOT increment the n dimension (which counts normal-slot
            // consumers only) — the take merges into the SAME key (skip = the carried copy, take = the
            // added points; one stage call ⇒ at most one rarity sub per path).
            fun applyRaritySubF(
                options: List<Pair<Sublimation, Raw>>,
                epic: Boolean,
            ) {
                if (options.isEmpty()) return
                beginStageF()
                for (i in 0 until dpF.liveCount) {
                    val k = dpF.liveKeys[i]
                    val fr = dpF.slots[k] ?: continue
                    var rest = k / (subCap + 1)
                    val relic0 = rest % 2
                    rest /= 2
                    val epic0 = rest % 2
                    rest /= 2
                    val crit0 = rest % critDimF
                    val ap0 = rest / critDimF
                    if (epic && epic0 < 1) continue
                    if (!epic && relic0 < 1) continue
                    for ((sub, r) in options) {
                        if (!subAllowedExists(sub, crit0 - critOff, ap0 - apOff, cLow, cHigh)) continue
                        val tgt = ndF.getOrPut(k)
                        fr.forEachPoint { pd, pg, pm -> tgt.add(pd + r.di, pg + grawOfF(r), pm) }
                    }
                }
                endStageF()
            }
            if (convTaken?.rarity != SublimationRarity.EPIC && critSecret == null) applyRaritySubF(epicSubs, epic = true)
            if (convTaken?.rarity != SublimationRarity.RELIC) applyRaritySubF(relicSubs, epic = false)
            val segmentHarvestStart =
                if (fastTimingEnabled) {
                    fastDpNanos += System.nanoTime() - segmentDpStart
                    System.nanoTime()
                } else {
                    0L
                }

            // Harvest THIS SEGMENT's c range: every (cell a, crit step c) reads the states in its exact
            // band with the same budget/slot coupling as the exact pass. Point graw is folded at the
            // segment's top effective crit (≥ its value at any c in the segment); the CONSTANTS stay
            // exact per c. The force-taken conversion's condition is checked with the EXACT per-(a, c)
            // arithmetic (both are known here — same bounds as the exact pass's subAllowedAt).
            // Family-budget graw prefixes per crit step of this segment (the budget is priced with the
            // EXACT per-c fold at harvest — tighter than the segment-top point fold, still ≥ any build).
            val gpByC = Array(cHigh - cLow + 1) { grawBudgetPrefix(minOf((cLow + it), critCap).toLong()) }
            // Crit feasibility is likewise a function only of the state's crit coordinate and this
            // segment. Preserve the reference order exactly: the special clamped c=0 coordinate first,
            // then the positive direct interval in ascending order, each carrying its precomputed charge.
            val indexedCritCoordinates =
                if (indexedHarvestEnabled) {
                    Array(critDimF) { storedCrit ->
                        indexedFastHarvestCritCoordinates(
                            stateCrit = storedCrit - critOff,
                            cLow = cLow,
                            cHigh = cHigh,
                            critConst = critConst,
                            critOff = critOff,
                            maxSubCrit = maxSubCrit,
                            csWorldCrit = csWorldCrit,
                            forcedStartCritTotal = forcedStartCritTotal,
                            critBudgetSortedForCharge = critBudgetSortedForCharge
                        )
                    }
                } else {
                    null
                }

            fun harvestCoordinate(
                fr: Frontier,
                n0: Int,
                relic: Int,
                epic: Int,
                crit: Int,
                ap: Int,
                a: Int,
                apGapSubsA: Int,
                c: Int,
                critGapSubsC: Int,
            ) {
                if (n0 + critGapSubsC + apGapSubsA > subCap) return
                if (convTaken != null) {
                    if (convTaken.rarity == SublimationRarity.EPIC && epic == 0) return
                    if (convTaken.rarity == SublimationRarity.RELIC && relic == 0) return
                    val cond = convTaken.condition
                    if (cond != null) {
                        val n = (cond.value ?: 0).toLong()
                        val preCombatCritMin = maxOf(critConst + crit, c - maxStartCrit)
                        val preCombatCritMax = minOf(c.toLong(), critConst + crit + maxPermCrit)
                        val preCombatApMin = maxOf(apConst + ap, a - maxStartAp)
                        val preCombatApMax = minOf(a.toLong(), apConst + ap + maxPermAp)
                        val ok =
                            when (cond.type) {
                                SublimationConditionType.AP_AT_MOST -> preCombatApMin <= n
                                SublimationConditionType.AP_AT_LEAST -> preCombatApMax >= n
                                SublimationConditionType.AP_EXACT -> preCombatApMin <= n && n <= preCombatApMax
                                SublimationConditionType.CRIT_AT_MOST -> preCombatCritMin <= n
                                SublimationConditionType.CRIT_AT_LEAST -> preCombatCritMax >= n
                                else -> true
                            }
                        if (!ok) return
                    }
                }
                if (critSecret != null && epic == 0) return
                val cEff = minOf(c, critCap)
                // Conditional forced credits are unconditional in the FAST pass (sound
                // over-count, clamped ≥ 0 — keeps `fast ≥ exact` vs the gated exact harvest).
                val grawConstC = (400L + cEff) * (mConst + forcedCondMTotal) + 5L * cEff * (critMConst + forcedCondCmTotal)
                val freeSlots = subCap - n0 - critGapSubsC - apGapSubsA
                val gpC = gpByC[c - cLow]
                fr.forEachPoint { pd, pg, _ ->
                    val perHit = budgetMax(dConst + pd, grawConstC + pg, freeSlots, gpC)
                    if (perHit > fastAllCellsOut[a]) fastAllCellsOut[a] = perHit
                    // Per-crit-step harvest for the top cell (exact-pass c-loop pruning bounds).
                    if (fastPerCOut != null && a == maxCell && c < fastPerCOut.size && perHit > fastPerCOut[c]) fastPerCOut[c] = perHit
                    // ALL-cells per-(cell, crit-step) harvest (tier-1.5 segment-skip bounds).
                    if (fastPerCellC != null && c < fastPerCellC[a].size && perHit > fastPerCellC[a][c]) fastPerCellC[a][c] = perHit
                }
            }

            for (ki in 0 until dpF.liveCount) {
                val k = dpF.liveKeys[ki]
                val fr = dpF.slots[k] ?: continue
                val n0 = k % (subCap + 1)
                var rest = k / (subCap + 1)
                val relic = rest % 2
                rest /= 2
                val epic = rest % 2
                rest /= 2
                val crit = rest % critDimF - critOff
                val ap = rest / critDimF - apOff
                if (indexedHarvestEnabled) {
                    val apCoordinates = indexedApCoordinates!![ap + apOff]
                    val critCoordinates = indexedCritCoordinates!![crit + critOff]
                    var ai = 0
                    while (ai < apCoordinates.size) {
                        val a = apCoordinates[ai]
                        val apGapSubsA = apCoordinates[ai + 1]
                        var ci = 0
                        while (ci < critCoordinates.size) {
                            val c = critCoordinates[ci]
                            val critGapSubsC = critCoordinates[ci + 1]
                            fastHarvestCoordinates++
                            CertifierTuning.indexedFastHarvestCoordinatesForTest.incrementAndGet()
                            harvestCoordinate(fr, n0, relic, epic, crit, ap, a, apGapSubsA, c, critGapSubsC)
                            ci += 2
                        }
                        ai += 2
                    }
                } else {
                    for (a in 0..maxCell) {
                        val apHighA = a - apConst
                        if (ap < apHighA - maxSubAp || ap > apHighA - minSubAp) continue
                        val apGapA = apHighA - ap
                        val apGapSubsA = if (apGapA >= 0) minSubsToCover(apGapA, apPlusSorted) else minSubsToCover(-apGapA, apMinusSorted)
                        if (apGapSubsA == Int.MAX_VALUE) continue
                        for (c in cLow..cHigh) {
                            val critItemHighC = (c - critConst).toInt()
                            val critLowBandC = if (c == 0) -critOff else critItemHighC - maxSubCrit.toInt()
                            if (crit < critLowBandC || crit > critItemHighC) continue
                            // Forced start-of-combat crit is credited before counting gap subs (mirrors
                            // the exact harvest): always present, slot already charged.
                            val critGapSubsC =
                                if (c == 0) {
                                    0
                                } else {
                                    minSubsToCover(
                                        c.toLong() - critConst - crit - csWorldCrit - forcedStartCritTotal,
                                        critBudgetSortedForCharge
                                    )
                                }
                            if (critGapSubsC == Int.MAX_VALUE) continue
                            fastHarvestCoordinates++
                            harvestCoordinate(fr, n0, relic, epic, crit, ap, a, apGapSubsA, c, critGapSubsC)
                        }
                    }
                }
            }
            if (fastTimingEnabled) fastHarvestNanos += System.nanoTime() - segmentHarvestStart
        }
        if (fastTimingEnabled) {
            System.err.println(
                "CERT_FAST_TIMING conv=${convTaken?.name?.en ?: "-"} critSecret=${critSecret?.name?.en ?: "-"} wr=$weaponsRestricted " +
                    "step=$fastCSegmentStep cells=$fastCellCount dpMs=${fastDpNanos / 1_000_000} harvestMs=${fastHarvestNanos / 1_000_000} " +
                    "harvestIndexed=$indexedHarvestEnabled coordinates=$fastHarvestCoordinates " +
                    "segmentsRun=$fastSegmentsRun segmentsSkipped=$fastSegmentsSkipped"
            )
        }
        if (stageStatsEnabled && stageAdds.isNotEmpty()) {
            val byScans = stageScans.entries.sortedByDescending { it.value }
            System.err.println(
                "CERT_STAGE_STATS conv=${convTaken?.name?.en ?: "-"} cs=${critSecret?.name?.en ?: "-"} wr=$weaponsRestricted " +
                    byScans.joinToString(" ") { (label, scans) -> "$label=${stageAdds[label] ?: 0}/$scans" }
            )
        }
        return 0L
    }
    // ===================== end FAST tier-1 pass ==========================================================

    // Dense DP storage (P1.3): the packed state key is a mixed-radix index into the box
    // apDim × critDim × 2 × 2 × (subCap+1), so it addresses a flat Frontier?[] directly (no hashing,
    // no Long boxing). apDim covers the loosest AP headroom (apCeil + 2·apOff); critDim grows with c,
    // so size the box for the largest crit step (c = cEnumMax) and DOUBLE-BUFFER two stores swapped
    // each stage — allocated ONCE per pass, cleared (via the live list) between crit steps and stages,
    // so no per-stage allocation happens. Every key computed for a smaller c is < this box (critDim(c)
    // ≤ maxCritDim), so one size serves all crit steps.
    val apDim = apCeil + 2 * apOff + 1
    val maxCritDim = (cEnumMax - critConst).toInt() + 2 * critOff + 1
    val denseBox = apDim * maxCritDim * 4 * (subCap + 1)
    val bufA = DenseDp(denseBox)
    val bufB = DenseDp(denseBox)

    // ---- Exact-pass c-loop pruning (two-tier speed) ---------------------------------------------------
    // The exact answer is `max over c of dp(c)` — ~cEnumMax nearly-identical full DPs, of which only a
    // handful can win. When the caller SUPPLIES a per-crit-step upper bound `ub[c] ≥ dp(c)` ([cPruneUbIn]
    // — harvested for free from tier-1.5's step-1 fast pass, whose per-c graw fold is exact and whose
    // state space is a strict relaxation: ∃-gates, independent skill maxima — the per-cell `fast ≥ exact`
    // lock argument, applied per c): run the argmax-ub step FIRST to seed `best` high, scan the rest
    // ascending, and SKIP any c with `ub[c] < best` (strict — `dp(c) ≤ ub(c) < best` can never raise the
    // max, and a tie `ub == best` is never skipped, so the tie-aware update below lands `bestC` on the
    // SMALLEST max-achieving c exactly like the plain ascending loop). Value and provenance are
    // byte-identical; only the number of full DPs changes. The pass deliberately does NOT compute the
    // bound itself: a per-(cell, world) step-1 self-call was MEASURED to cost as much as the exact loop
    // it prunes (lvl-110 forceTier2All 835 s → 1560 s — a 1.87× regression), so pruning happens only
    // where the bound already exists (the production badge path, where tier-1.5 just ran). Off in
    // explain mode (a single pinned c) and via [CertifierTuning.cLoopPruneEnabled] (the A/B test seam).
    val cPruneUb: LongArray? =
        if (explainC == null && CertifierTuning.cLoopPruneEnabled && cPruneUbIn != null && cPruneUbIn.size >= cEnumMax + 1) {
            cPruneUbIn
        } else {
            null
        }
    val cSeed = cPruneUb?.let { ub -> (0..cEnumMax).maxByOrNull { ub[it] } } ?: -1
    // Processing order: the seed first (its DP sets `best` near the max so the ascending scan prunes),
    // then ascending. Without bounds this is the plain ascending loop, byte-for-byte.
    val cOrder = if (cPruneUb != null && cSeed >= 0) listOf(cSeed) + (0..cEnumMax).filter { it != cSeed } else (0..cEnumMax).toList()

    var best = 0L
    var bestDbg = ""
    var bestC = -1
    var bestKey = 0L
    var bestPt: LongArray? = null
    for (c in cOrder) {
        if (explainC != null && c != explainC) continue
        if (cPruneUb != null && c != cSeed && cPruneUb[c] < best) {
            CertifierTuning.cPruneSkippedForTest.incrementAndGet()
            continue // ub[c] < best ⇒ dp(c) ≤ ub[c] < best — this crit step can never raise the max
        }
        // c is the ARITHMETIC crit total (bands, budget gaps and conditions use it exactly); the
        // VALUE uses the capped effective rate — over-cap cells price their crit at critCap.
        val cEff = minOf(c, critCap)
        // The crit dimension is the build's PRE-sub crit (base+passive folded into critConst): items,
        // runes and folded skill (Luck) crit. Pure-crit subs fill the gap to the total crit c.
        // Item crit can be NEGATIVE (L'un seul −5, Epaulectriques −10), so the reachable band is
        // [−critOff, critItemHigh]; a cell below even the most negative wearable crit is infeasible.
        val critItemHigh = (c - critConst).toInt()
        if (critItemHigh < -critOff) continue
        val critItemLow = critItemHigh - maxSubCrit.toInt()
        val grawConst = (400L + cEff) * mConst + 5L * cEff * critMConst
        val dConst = 100L + diConst
        // Family-budget graw prefix at this exact crit step (see the budget block above the passes).
        val gpExact = grawBudgetPrefix(cEff.toLong())

        fun grawOf(r: Raw) = (400L + cEff) * r.m + 5L * cEff * r.critM
        // state key packs (ap, crit, epic, relic, subCount). epic/relic in {0,1}, subCount in 0..subCap.
        // ap/crit are STORED with the negative-item offsets (stored = real + apOff/critOff ≥ 0), so
        // the base-critDim packing and the % / decodes below stay non-negative. The dimension leaves
        // TWICE the offset of headroom: an intermediate stage may overshoot the final band by the
        // not-yet-applied negative capacity (equip the +2-AP amulet BEFORE the −1-AP rings) and come
        // back down — only the HARVEST enforces the exact band. 2·off covers any reachable
        // intermediate, and at c = 0 it keeps the start coordinate critOff inside critDim.
        val critDim = critItemHigh + 2 * critOff + 1

        fun key(
            ap: Int,
            crit: Int,
            epic: Int,
            relic: Int,
            n: Int,
        ) = ((((ap.toLong() * critDim + crit) * 2 + epic) * 2 + relic) * (subCap + 1)) + n

        // Double-buffered dense stores: dp is the current one; each stage clears the OTHER and fills it.
        var dp = bufA
        dp.clear()
        dp.getOrPut(key(apOff, critOff, 0, 0, 0).toInt()).add(0, 0)
        // Provenance snapshots: (stage name, deep copy of dp AFTER the stage). Only in explain mode.
        val snaps = if (explainC == c && explainOut != null) mutableListOf<Pair<String, HashMap<Long, Frontier>>>() else null

        fun snap(name: String) {
            // Perf tally: snap() runs at EVERY stage boundary (init, each slot, weapons, rings, skills,
            // every sub) regardless of explain mode, so it is the single hook that measures DP size per
            // stage. Sums states (dp keys) and frontier points after each stage across all crit steps.
            if (statsEnabled) {
                statStages++
                statStates += dp.liveCount.toLong()
                for (i in 0 until dp.liveCount) statPoints += (dp.slots[dp.liveKeys[i]]?.size ?: 0).toLong()
            }
            if (snaps == null) return
            // Materialize the dense store into the old map shape (explain-only path; perf irrelevant).
            val copy = HashMap<Long, Frontier>()
            for (i in 0 until dp.liveCount) {
                val k = dp.liveKeys[i]
                dp.slots[k]?.let { copy[k.toLong()] = it.copy() }
            }
            snaps.add(name to copy)
        }
        snap("init")

        // Reduce a slot's items to a Frontier per (ap,crit,epic,relic) cost bucket — collapses ~470 raw
        // items to a handful of cost cells, the key speedup. Then one DP transition per cost cell.
        fun perCost(
            options: List<Raw>,
            keepWorthless: Boolean = false,
        ): Map<Int, Frontier> {
            val m = HashMap<Int, Frontier>()
            for (r in options) {
                if (r.ap > apCeil + apOff || r.crit > critItemHigh + critOff) continue
                val g = grawOf(r)
                // Skip only items worthless on EVERY axis: crit/AP are COST dimensions but also
                // resources — a pure-crit (or pure-AP) item is how high-c (high-AP) states become
                // reachable at all, so dropping it would UNDER-count those cells (unsound). A FORCED
                // slot ([keepWorthless]) keeps even a null option: equipping a zero-stat forced item is a
                // real (0,0,0) transition the MANDATORY slot needs so the state doesn't die (under-count).
                if (!keepWorthless && r.di <= 0 && g <= 0 && r.epic + r.relic == 0 && r.crit == 0 && r.ap == 0 && r.mp == 0L) continue
                val ck = (((r.ap + apOff) * critDim + (r.crit + critOff)) * 2 + r.epic) * 2 + r.relic
                m.getOrPut(ck) { Frontier() }.add(r.di, g, r.mp)
            }
            return m
        }

        // [apUpper]/[critUpper] are the STORED-coordinate upper bounds this stage may keep. They are
        // stage-aware: apUpper = apCeil + apOff + (worst negative AP still available in the not-yet-
        // applied stages). A state above that can never be pulled back into the exact harvest band by
        // any remaining stage, so dropping it here is sound (see the item-stage apply loop below). At
        // the last negative-capable stage the remaining negative is 0 and apUpper collapses to the
        // exact band apCeil + apOff — the axis stops being 2× wide.
        fun applyCells(
            cells: Map<Int, Frontier>,
            apUpper: Int,
            critUpper: Int,
            mandatory: Boolean = false,
        ) {
            val nd = if (dp === bufA) bufB else bufA
            nd.clear()
            // Carry every state forward (the "skip this stage" option = leaving the slot empty). The
            // source frontier is already non-dominated, so copy() reuses its points directly —
            // byte-identical to re-adding them through the O(n) dominance check, but without the
            // per-point rescan. A MANDATORY (forced-item, P5.1) stage skips this: the slot must be
            // equipped, so every state has to take one of the restricted forced-name cells.
            if (!mandatory) {
                for (i in 0 until dp.liveCount) {
                    val k = dp.liveKeys[i]
                    nd.put(k, (dp.slots[k] ?: continue).copy())
                }
            }
            for (i in 0 until dp.liveCount) {
                val k = dp.liveKeys[i]
                val fr = dp.slots[k] ?: continue
                val n0 = k % (subCap + 1)
                var rest = k / (subCap + 1)
                val relic0 = rest % 2
                rest /= 2
                val epic0 = rest % 2
                rest /= 2
                val crit0 = rest % critDim
                val ap0 = rest / critDim
                for ((ck, cfr) in cells) {
                    // Cost cells carry their deltas BIASED by (+apOff, +critOff) so every packed
                    // component is non-negative (mixed-radix decode breaks on a negative low digit:
                    // crit −10 with ap 0 would decode as ap −1 / crit +8). Un-bias on read.
                    val rrelic = ck % 2
                    val repic = (ck / 2) % 2
                    val rcrit = (ck / 4) % critDim - critOff
                    val rap = (ck / 4) / critDim - apOff
                    val ap1 = ap0 + rap
                    val crit1 = crit0 + rcrit
                    val epic1 = epic0 + repic
                    val relic1 = relic0 + rrelic
                    if (ap1 > apUpper || crit1 > critUpper || epic1 > 1 || relic1 > 1) continue
                    val tgt = nd.getOrPut(key(ap1, crit1, epic1, relic1, n0).toInt())
                    fr.forEachPoint { pd, pg, pm -> cfr.forEachPoint { qd, qg, qm -> tgt.add(pd + qd, pg + qg, pm + qm) } }
                }
            }
            dp = nd
        }

        // Item slots as a REORDERABLE stage list. Only item stages carry negative AP/crit (skills add
        // only; transition subs are ap==crit==0 — the isPureAp/isPureCrit bail above guarantees it), so
        // [apOff]/[critOff] sum exactly these stages' worst negatives. Applying the biggest-negative
        // slots FIRST front-loads the headroom tightening: once a stage's negative capacity is spent,
        // every later stage prunes at a tighter band (down to the exact band after the last one). Stage
        // order is mathematically arbitrary (the DP is a commutative convolution) and provenance matches
        // stages by name, so the sort is sound. Each stage stores its worst negative (≤ 0) per axis.
        class ItemStage(
            val name: String,
            val cells: Map<Int, Frontier>,
            val apNeg: Int,
            val critNeg: Int,
            val mandatory: Boolean = false,
        )
        val itemStages = mutableListOf<ItemStage>()
        // Weapons grouped (1H+offhand OR 2H) like rarityAwareUpper.weaponOptions.
        for ((type, entries) in itemsByType) {
            if (type in weaponTypes) continue
            val forced = type in forcedSlots
            itemStages += ItemStage("slot:${type.name}", perCost(entries, keepWorthless = forced), worstAp(entries), worstCrit(entries), mandatory = forced)
        }
        if (weaponTypes.any { it in itemsByType }) {
            val oneH = itemsByType[ItemType.ONE_HANDED_WEAPONS].orEmpty()
            // World W: the NO_OFFHAND_OR_TWO_HANDED condition holds by construction — off-hand and
            // two-handed options are simply not offered (one-handed alone is allowed by the condition).
            val offH = if (weaponsRestricted) emptyList() else itemsByType[ItemType.OFF_HAND_WEAPONS].orEmpty()
            val twoH = if (weaponsRestricted) emptyList() else itemsByType[ItemType.TWO_HANDED_WEAPONS].orEmpty()
            // 1H/off-hand pair (each side optional) plus the 2H alternative, as ONE combined slot.
            val pair = mutableListOf(Raw(0, 0, 0, 0, 0, 0, 0))
            val oneOpts = listOf(Raw(0, 0, 0, 0, 0, 0, 0)) + oneH
            val offOpts = listOf(Raw(0, 0, 0, 0, 0, 0, 0)) + offH
            val combined = mutableListOf<Raw>()
            for (a in oneOpts) {
                for (b in offOpts) {
                    if (a.epic + b.epic > 1 || a.relic + b.relic > 1) continue
                    combined.add(Raw(a.di + b.di, a.m + b.m, a.critM + b.critM, a.ap + b.ap, a.crit + b.crit, a.epic + b.epic, a.relic + b.relic, a.mp + b.mp))
                }
            }
            pair.clear()
            pair.addAll(combined + twoH)
            itemStages += ItemStage("slot:weapons", perCost(pair), weaponWorstAp, weaponWorstCrit)
        }

        // Ring slot = TWO DISTINCT rings. Per plain ring take its best rune option's graw at this
        // crit rate, keep the top-2 graw per cost cell, then offer every 1-ring and 2-distinct-ring
        // combination as one slot (the same ring is never paired with itself; a same-cost pair uses
        // best + 2nd-best). MP-CARRYING rings are excluded from that pool and offered EXPLICITLY —
        // single, paired with each cost cell's best plain ring, and paired with each other — so their
        // (graw, mp) tradeoff rides the frontier instead of their MP being granted for free.
        if (ringOptionsByEquip.isNotEmpty()) {
            val mpRingEquips = ringOptionsByEquip.filter { e -> e.options.any { it.mp > 0L } }
            val plainRingEquips = ringOptionsByEquip.filter { e -> e.options.none { it.mp > 0L } }

            // Per cost cell: top-2 GRAW among DISTINCT-NAME rings. A same-name sibling (other rarity)
            // in the SAME cell is a pure alternative — it can never appear in any pair its better
            // sibling couldn't — so only the better one is kept; ACROSS cells both survive (different
            // costs) and the pairing below refuses same-name combinations like the model does.
            class RingBest(
                val g: Long,
                val name: String,
            )
            val top2 = HashMap<Int, MutableList<RingBest>>()
            for ((nameKey, opts) in plainRingEquips) {
                var bestG = Long.MIN_VALUE
                // A negative-AP/crit ring packs to a NEGATIVE ck, so "no option" needs a real flag.
                var bestCk = 0
                var bestFound = false
                for (r in opts) {
                    if (r.ap > apCeil + apOff || r.crit > critItemHigh + critOff) continue
                    val g = grawOf(r)
                    if (g > bestG) {
                        bestG = g
                        bestCk = (((r.ap + apOff) * critDim + (r.crit + critOff)) * 2 + r.epic) * 2 + r.relic
                        bestFound = true
                    }
                }
                if (!bestFound || bestG <= 0) continue
                val lst = top2.getOrPut(bestCk) { mutableListOf() }
                val sameName = lst.indexOfFirst { it.name == nameKey }
                if (sameName >= 0) {
                    if (bestG > lst[sameName].g) lst[sameName] = RingBest(bestG, nameKey)
                } else {
                    lst += RingBest(bestG, nameKey)
                }
                lst.sortByDescending { it.g }
                while (lst.size > 2) lst.removeAt(2)
            }
            val cks = top2.keys.toList()
            val ringCells = HashMap<Int, Frontier>()

            // Cost cells carry deltas BIASED by (+apOff, +critOff) — see perCost — so plain
            // arithmetic decodes; un-bias on read, re-bias on re-encode.
            fun decodeAp(ck: Int) = (ck / 4) / critDim - apOff

            fun decodeCrit(ck: Int) = (ck / 4) % critDim - critOff

            fun decodeEpic(ck: Int) = (ck / 2) % 2

            fun decodeRelic(ck: Int) = ck % 2

            fun combineCells(
                a: Int,
                b: Int,
            ): Int? {
                val ap = decodeAp(a) + decodeAp(b)
                val crit = decodeCrit(a) + decodeCrit(b)
                val epic = decodeEpic(a) + decodeEpic(b)
                val relic = decodeRelic(a) + decodeRelic(b)
                if (ap > apCeil + apOff || crit > critItemHigh + critOff || epic > 1 || relic > 1) return null
                return (((ap + apOff) * critDim + (crit + critOff)) * 2 + epic) * 2 + relic
            }
            for (i in cks.indices) {
                ringCells.getOrPut(cks[i]) { Frontier() }.add(0, top2.getValue(cks[i])[0].g) // one ring
                for (j in i until cks.size) {
                    val a = cks[i]
                    val b = cks[j]
                    val la = top2.getValue(a)
                    val lb = top2.getValue(b)
                    val graw =
                        if (a == b) {
                            // Same cell: the two entries are distinct names by construction.
                            (la.getOrNull(1) ?: continue).g + la[0].g
                        } else if (la[0].name != lb[0].name) {
                            la[0].g + lb[0].g
                        } else {
                            // Cross-cell name collision (Mythic vs Legendary of one ring): pair each
                            // best with the other cell's runner-up; skip if neither cell has one.
                            maxOf(
                                la.getOrNull(1)?.let { it.g + lb[0].g } ?: Long.MIN_VALUE,
                                lb.getOrNull(1)?.let { la[0].g + it.g } ?: Long.MIN_VALUE
                            ).takeIf { it != Long.MIN_VALUE } ?: continue
                        }
                    val ck = combineCells(a, b) ?: continue
                    ringCells.getOrPut(ck) { Frontier() }.add(0, graw)
                }
            }
            // MP rings: explicit options (few — their MP is why a build would wear them at all).
            val mpRingOpts =
                mpRingEquips.map { e ->
                    e.nameKey to e.options.filter { it.ap <= apCeil + apOff && it.crit <= critItemHigh + critOff }
                }
            for (i in mpRingOpts.indices) {
                val (nameI, optsI) = mpRingOpts[i]
                for (r in optsI) {
                    val ckR = (((r.ap + apOff) * critDim + (r.crit + critOff)) * 2 + r.epic) * 2 + r.relic
                    val gR = grawOf(r)
                    ringCells.getOrPut(ckR) { Frontier() }.add(0, gR, r.mp) // mp ring alone
                    for (ckPlain in cks) {
                        val lst = top2.getValue(ckPlain)
                        // Same-name partner (the plain sibling of this MP ring) → the runner-up.
                        val partner = (if (lst[0].name != nameI) lst[0] else lst.getOrNull(1)) ?: continue
                        val ck = combineCells(ckR, ckPlain) ?: continue
                        ringCells.getOrPut(ck) { Frontier() }.add(0, gR + partner.g, r.mp)
                    }
                    for (j in i + 1 until mpRingOpts.size) {
                        val (nameJ, optsJ) = mpRingOpts[j]
                        if (nameJ == nameI) continue
                        for (r2 in optsJ) {
                            val ck2 = (((r2.ap + apOff) * critDim + (r2.crit + critOff)) * 2 + r2.epic) * 2 + r2.relic
                            val ck = combineCells(ckR, ck2) ?: continue
                            ringCells.getOrPut(ck) { Frontier() }.add(0, gR + grawOf(r2), r.mp + r2.mp)
                        }
                    }
                }
            }
            // Rings apply as ONE combined stage; both picks can be the most-negative ring, so the stage's
            // worst negative is twice the worst single ring — matching [apOff]/[critOff]'s `2 * worstAp`.
            // Never mandatory today: a forced RING bails above (its free second slot can't be pinned yet);
            // the flag is kept wired for the future two-pick ring rework.
            itemStages += ItemStage("slot:rings", ringCells, 2 * worstAp(allRingOptions), 2 * worstCrit(allRingOptions), mandatory = ItemType.RING in forcedSlots)
        }

        // Apply the item stages biggest-negative-capacity first, tightening the headroom as the summed
        // remaining negative shrinks. apNegLeft/critNegLeft start at the full offset (all stages pending)
        // and each apNeg (≤ 0) reduces them; after the last stage they are 0 ⇒ the exact band apCeil+apOff
        // / critItemHigh+critOff. A state above apCeil+apOff+apNegLeft can never be pulled back into the
        // harvest band by the remaining stages, so pruning it here is sound (byte-identical harvest).
        itemStages.sortBy { it.apNeg + it.critNeg }
        var apNegLeft = apOff
        var critNegLeft = critOff
        for (st in itemStages) {
            // B8 cooperative cancellation: poll once per DP stage (the coarsest place that still bounds latency
            // to one stage's work). A bail here returns a SOUND over-count — the fast pass turns it into a
            // shape-level bail, the exact pass keeps the cell's fast bound — so a cancelled run is never unsound,
            // only incomplete (the caller then declines to cache it and reports the badge as Unavailable).
            if (certifierCancelled()) return Long.MAX_VALUE
            // A MANDATORY (forced) stage with no in-band option would empty the DP into a 0 certificate —
            // an under-count. Keep-worthless makes a real forced item's stage non-empty; if it is still
            // empty (e.g. a worthless forced ring dropped by the graw-≤-0 prune), BAIL — sound, badge
            // honestly absent — rather than certify below a real build.
            if (st.mandatory && st.cells.isEmpty()) return Long.MAX_VALUE
            apNegLeft += st.apNeg
            critNegLeft += st.critNeg
            applyCells(st.cells, apCeil + apOff + apNegLeft, critItemHigh + critOff + critNegLeft, mandatory = st.mandatory)
            snap(st.name)
        }

        // Skill branches as pseudo-slots: enumerate the pool allocation between the single crit var
        // (→ crit dimension) and ap var (→ ap dimension), with the remaining points giving the best
        // (di, graw) frontier — di vars on the frontier, mastery/critM filled greedily by graw-per-point.
        // This solves the skill allocation jointly with items/subs under the real per-branch pool cap.
        fun branchCells(
            infos: List<SkillVarInfo>,
            pool: Int,
        ): Map<Int, Frontier> {
            val critVar = infos.firstOrNull { it.crit != 0 }
            val apVar = infos.firstOrNull { it.ap != 0 }
            val diVars = infos.filter { it.di != 0L }.sortedByDescending { it.di }
            // Pure-MP skill points (the Movement Points major) ride the frontier's mp axis so an
            // MP-sourced ramp only sees them when the pool actually spends the points.
            val mpVars =
                infos
                    .filter { it.mp > 0L && it.di == 0L && it.m == 0L && it.critM == 0L && it.crit == 0 && it.ap == 0 }
                    .sortedByDescending { it.mp }
            val mpCapTotal = mpVars.sumOf { it.cap }
            val grawVars = infos.filter { it.di == 0L && it.crit == 0 && it.ap == 0 && it.mp == 0L }

            fun fillGraw(points: Int): Long {
                var rem = points
                var g = 0L
                for (gv in grawVars.sortedByDescending { (400L + cEff) * it.m + 5L * cEff * it.critM }) {
                    if (rem <= 0) break
                    val per = (400L + cEff) * gv.m + 5L * cEff * gv.critM
                    if (per <= 0) continue
                    val take = minOf(rem, gv.cap)
                    g += take * per
                    rem -= take
                }
                return g
            }
            val diCapTotal = diVars.sumOf { it.cap }
            val cells = HashMap<Int, Frontier>()
            val critCap = critVar?.let { minOf(it.cap, pool) } ?: 0
            val apCap = apVar?.let { minOf(it.cap, pool) } ?: 0
            for (ccPts in 0..critCap) {
                val crit = ccPts * (critVar?.crit ?: 0)
                if (crit > critItemHigh + critOff) break
                for (apPts in 0..minOf(apCap, pool - ccPts)) {
                    val ap = apPts * (apVar?.ap ?: 0)
                    if (ap > apCeil + apOff) break
                    val rem0 = pool - ccPts - apPts
                    for (d in 0..minOf(rem0, diCapTotal)) {
                        var di = 0L
                        var left = d
                        for (dv in diVars) {
                            val take = minOf(left, dv.cap)
                            di += take * dv.di
                            left -= take
                            if (left <= 0) break
                        }
                        for (mpPts in 0..minOf(rem0 - d, mpCapTotal)) {
                            var mpv = 0L
                            var leftMp = mpPts
                            for (mv in mpVars) {
                                val take = minOf(leftMp, mv.cap)
                                mpv += take * mv.mp
                                leftMp -= take
                                if (leftMp <= 0) break
                            }
                            val graw = fillGraw(rem0 - d - mpPts)
                            val ck = ((ap + apOff) * critDim + (crit + critOff)) * 2 * 2
                            cells.getOrPut(ck) { Frontier() }.add(di, graw, mpv)
                        }
                    }
                }
            }
            return cells
        }
        // Skills add AP/crit only (no negative capacity), so they run at the exact band — a skill that
        // pushes AP above apCeil can never be pulled back down and is correctly dropped here.
        for ((bi, branch) in skillBranches.withIndex()) {
            applyCells(branchCells(branch.second, branch.first), apCeil + apOff, critItemHigh + critOff)
            snap("skills:$bi")
        }

        // Normal transition subs: j ∈ 0..mult each, count ≤ subCap (state's n field). crit==0 (pure-crit
        // subs are the budget, not transitions) so they never move the item-crit dimension. They
        // never consume the item epic/relic budget; mastery/critM (if any) fold into graw.
        // Ramps LAST: their per-point valuation reads the mp accumulated by every other normal
        // transition (Swiftness II's +1 MP now arrives only WITH its −10 DI, on the same point).
        val orderedNormalTransitions =
            stagedTransitions.sortedBy { (sub, _, _) -> if (sub.perStatStep?.source == Characteristic.MOVEMENT_POINT) 1 else 0 }
        for ((sub, r, mult) in orderedNormalTransitions) {
            if (r.ap > apCeil) continue
            val g = grawOf(r)
            // MP-sourced DI ramp: valued PER FRONTIER POINT from that point's own item MP (plus the
            // item-independent maximum) — the mastery↔MP slot competition is finally priced, because
            // a point that banked mastery did not bank MP and vice versa.
            val pssMp =
                if (mpRampEnabled) {
                    sub.perStatStep?.takeIf {
                        it.source == Characteristic.MOVEMENT_POINT && it.target == Characteristic.DAMAGE_INFLICTED
                    }
                } else {
                    null
                }
            // A ramp's per-copy DI reads the state's accumulated MP — never stacked (maxCopies > 1
            // demands perStatStep == null); defensive clamp so a future data drift stays sound.
            val stageMult = if (pssMp == null) mult else 1
            val nd = if (dp === bufA) bufB else bufA
            nd.clear()
            // Carry every state forward (the "skip this stage" option). The source frontier is already
            // non-dominated, so copy() reuses its points directly — byte-identical to re-adding them
            // through the O(n) dominance check, but without the per-point rescan.
            for (i in 0 until dp.liveCount) {
                val k = dp.liveKeys[i]
                nd.put(k, (dp.slots[k] ?: continue).copy())
            }
            for (i in 0 until dp.liveCount) {
                val k = dp.liveKeys[i]
                val fr = dp.slots[k] ?: continue
                val n0 = k % (subCap + 1)
                if (n0 >= subCap) continue
                var rest = k / (subCap + 1)
                val relic0 = rest % 2
                rest /= 2
                val epic0 = rest % 2
                rest /= 2
                val crit0 = rest % critDim
                val ap0 = rest / critDim
                if (!subAllowedAt(sub, crit0 - critOff, ap0 - apOff, c)) continue
                val ap1 = ap0 + r.ap
                // Subs run after every item stage, so the AP axis is already at the exact band (no
                // negative capacity remains). Transition subs are ap==0 anyway, so this never fires.
                if (ap1 < 0 || ap1 > apCeil + apOff) continue
                for (j in 1..minOf(stageMult, subCap - n0)) {
                    val tgt = nd.getOrPut(key(ap1, crit0, epic0, relic0, n0 + j).toInt())
                    fr.forEachPoint { pd, pg, pm ->
                        val di1 =
                            if (pssMp == null) {
                                r.di
                            } else {
                                minOf(r.di, pssMp.contribution((mpFreeMax + pm).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()).toLong())
                            }
                        if (pssMp != null && System.getenv("WAKFU_MAX_DAMAGE_CERT_DEBUG") == "1" && di1 > 0) {
                            System.err.println("CERT_DEBUG_RAMP c=$c sub=${sub.name.en} stateMp=$pm di1=$di1 at ap=$ap0 crit=$crit0")
                        }
                        tgt.add(pd + j * di1, pg + j * g, pm + j * r.mp)
                    }
                }
            }
            dp = nd
            snap("sub:${sub.name.en}")
        }

        // Epic/relic sub slot: at most ONE, hosted on an equipped epic/relic ITEM (state rarity ≥1).
        // It does NOT increment the item rarity count (Σ subRarity ≤ Σ itemRarity, they coexist), and it
        // rides its DEDICATED slot — NOT the n dimension (which counts normal-slot consumers only): the
        // take merges into the SAME key (skip = the carried copy, take = the added points; one stage call
        // ⇒ at most one rarity sub per path). Per-state condition gate uses the state's item crit so
        // CRIT_AT_MOST is exact.
        fun applyRaritySub(
            options: List<Pair<Sublimation, Raw>>,
            epic: Boolean,
        ) {
            if (options.isEmpty()) return
            val nd = if (dp === bufA) bufB else bufA
            nd.clear()
            // Carry every state forward (the "skip this stage" option). The source frontier is already
            // non-dominated, so copy() reuses its points directly — byte-identical to re-adding them
            // through the O(n) dominance check, but without the per-point rescan.
            for (i in 0 until dp.liveCount) {
                val k = dp.liveKeys[i]
                nd.put(k, (dp.slots[k] ?: continue).copy())
            }
            for (i in 0 until dp.liveCount) {
                val k = dp.liveKeys[i]
                val fr = dp.slots[k] ?: continue
                val n0 = k % (subCap + 1)
                var rest = k / (subCap + 1)
                val relic0 = rest % 2
                rest /= 2
                val epic0 = rest % 2
                rest /= 2
                val crit0 = rest % critDim
                val ap0 = rest / critDim
                if (epic && epic0 < 1) continue
                if (!epic && relic0 < 1) continue
                for ((sub, r) in options) {
                    if (!subAllowedAt(sub, crit0 - critOff, ap0 - apOff, c)) continue
                    val ap1 = ap0 + r.ap
                    // Exact band: subs run after all item stages (no negative capacity left); ap==0 here.
                    if (ap1 < 0 || ap1 > apCeil + apOff) continue
                    val tgt = nd.getOrPut(key(ap1, crit0, epic0, relic0, n0).toInt())
                    fr.forEachPoint { pd, pg, pm -> tgt.add(pd + r.di, pg + grawOf(r), pm) }
                }
            }
            dp = nd
        }
        // A force-taken epic/relic sub (conversion or Critical-Secret world) occupies THE ≤1
        // epic/relic sub slot for its rarity.
        if (convTaken?.rarity != SublimationRarity.EPIC && critSecret == null) {
            applyRaritySub(epicSubs, epic = true)
            snap("sub:epic-slot")
        }
        if (convTaken?.rarity != SublimationRarity.RELIC) {
            applyRaritySub(relicSubs, epic = false)
            snap("sub:relic-slot")
        }

        for (hi in 0 until dp.liveCount) {
            val k = dp.liveKeys[hi]
            val fr = dp.slots[k] ?: continue
            val n0 = k % (subCap + 1)
            var rest = k / (subCap + 1)
            val relic = rest % 2
            rest /= 2
            val epic = rest % 2
            rest /= 2
            // Stored coordinates carry the negative-item offsets — translate back to REAL item
            // crit/AP (possibly negative) for the gap arithmetic and condition gates below.
            val crit = rest % critDim - critOff
            val ap = rest / critDim - apOff
            // Effective crit clamps at 0 (the scorer's coerceIn(0, 100)), so the c = 0 cell owns
            // EVERY state whose arithmetic total is ≤ 0 — a −10-crit item worn for its mastery
            // lands at total −7, is valued at c = 0, and must not be dropped by the exact-match
            // band (that would silently UNDER-count the c = 0 optimum).
            val critLowBand = if (c == 0) -critOff else critItemLow
            // apDim is PRE-sub AP; the sub-AP budget fills the gap to the pin ⇒ apDim ∈ [apFloor, apCeil].
            // Exact final band: intermediate stages ran with 2·off headroom, so states that
            // overshot and never came back (crit above this cell's arithmetic band) die here —
            // they belong to a higher c cell, which enumerates them with their own exact band.
            if (ap < apFloor || ap > apCeil || crit < critLowBand || crit > critItemHigh) continue
            // Slot coupling: the crit gap (c − const − items) and the AP gap to the pin are filled by
            // budget subs, each consuming a real sub slot next to the n transition subs. At c = 0 the
            // band above already guarantees the arithmetic total is ≤ 0 (clamped to an effective 0),
            // so no budget sub is needed — demanding subs to climb UP to zero would unreachable-skip
            // a build that qualifies as-is.
            // Forced start-of-combat crit is credited before counting gap subs (like the CS world's
            // crit): always present, its slot already charged — never one of the gap-covering subs.
            val critGapSubs =
                if (c == 0) 0 else minSubsToCover(c.toLong() - critConst - crit - csWorldCrit - forcedStartCritTotal, critBudgetSortedForCharge)
            val apGap = apHigh.toLong() - ap
            val apGapSubs =
                if (apGap >= 0) minSubsToCover(apGap, apPlusSorted) else minSubsToCover(-apGap, apMinusSorted)
            if (critGapSubs == Int.MAX_VALUE || apGapSubs == Int.MAX_VALUE) continue
            if (n0 + critGapSubs + apGapSubs > subCap) continue
            // Force-taken conversion sub: its rarity needs the matching equipped item, and its own
            // condition must hold at this state (same exact gate every other sub gets).
            if (convTaken != null) {
                if (convTaken.rarity == SublimationRarity.EPIC && epic == 0) continue
                if (convTaken.rarity == SublimationRarity.RELIC && relic == 0) continue
                if (!subAllowedAt(convTaken, crit, ap, c)) continue
            }
            // World C: Critical Secret is EPIC (wrapper-guaranteed) and its condition holds by
            // construction (every pre-combat critM source was zeroed) — only the item gate remains.
            if (critSecret != null && epic == 0) continue
            // P5.3: a forced plain EPIC/RELIC sub can only be socketed on an equipped item of its
            // rarity (Σ subRarity ≤ Σ itemRarity) — EXACT in the forced space: every real build hosts
            // the carrier, so no real build maps to a discarded state.
            if (forcedEpicPlain && epic == 0) continue
            if (forcedRelicPlain && relic == 0) continue
            // P5.3: a FORCED conditional sub credits its DI/mastery/critM at THIS state only when its
            // condition holds here (same exact pre-crit/pre-AP gate the choosable subs use). Its slot
            // is already charged (subCap) whether or not the condition holds — an equipped-but-inert
            // sub. Matches the model side: [appliesVar] gates a forced sub's effect on `subVar ∧
            // condHolds`. Credits are clamped ≥ 0 upstream (a negative at a maybe-holds state would
            // under-count its condition-broken builds).
            var forcedCondDiHere = 0L
            var forcedCondGrawHere = 0L
            for ((sub, credit) in forcedCondCredits) {
                if (subAllowedAt(sub, crit, ap, c)) {
                    forcedCondDiHere += credit.di
                    forcedCondGrawHere += grawOf(credit)
                }
            }
            val dConstHere = dConst + forcedCondDiHere
            val freeSlots = subCap - n0 - critGapSubs - apGapSubs
            for (i in 0 until fr.size) {
                val pd = fr.di(i)
                val pg = fr.graw(i)
                val prod = budgetMax(dConstHere + pd, grawConst + forcedCondGrawHere + pg, freeSlots.toInt(), gpExact)
                // The tie clause (`prod == best && c < bestC`) exists for the PRUNED order only: the seed
                // c runs first, so a smaller c tying its value must still win `bestC` — "smallest
                // max-achieving c", exactly the plain ascending loop's outcome (where the clause can
                // never fire, since any later tie has c ≥ bestC). bestC == -1 keeps it inert (c < -1).
                if (prod > best || (prod == best && c < bestC)) {
                    best = prod
                    bestC = c
                    bestKey = k.toLong()
                    bestPt = longArrayOf(pd, pg, fr.mp(i))
                    if (System.getenv("WAKFU_MAX_DAMAGE_CERT_DEBUG") == "1") {
                        bestDbg = "c=$c ap=$ap critDim$crit di=${dConstHere + pd} graw=${grawConst + forcedCondGrawHere + pg}"
                    }
                }
            }
        }

        // ---- Provenance backtrack (diagnostics only, explain mode) ----------------------------------
        // Walk the stage snapshots from the winning point backwards: at each stage find the (parent
        // point, option) whose application yields the current (state, point). Labels name the concrete
        // item / sub / skill allocation — turning the certificate VALUE into a candidate BUILD.
        if (snaps != null && explainOut != null && bestC == c) {
            val winPt = bestPt
            if (winPt == null) {
                explainOut.add("cell $apTarget: no harvested state (cell infeasible at c=$c)")
            } else {
                data class Opt(
                    val label: String,
                    val dAp: Int,
                    val dCrit: Int,
                    val dEpic: Int,
                    val dRelic: Int,
                    val dN: Int,
                    val di: Long,
                    val graw: Long,
                    val mp: Long,
                    val ramp: Sublimation? = null,
                    // E8 item A: the equipmentId(s) this option equips (empty for non-item stages — skills/subs/runes).
                    // Carried so the winning composition's ITEMS are recovered as typed ids, not parsed from [label].
                    val ids: List<Int> = emptyList(),
                )

                fun rawOpt(
                    label: String,
                    r: Raw,
                    ids: List<Int> = emptyList(),
                ) = Opt(label, r.ap, r.crit, r.epic, r.relic, 0, r.di, grawOf(r), r.mp, ids = ids)

                fun ringCk(r: Raw) = (((r.ap + apOff) * critDim + (r.crit + critOff)) * 2 + r.epic) * 2 + r.relic

                // Cost cells carry deltas BIASED by (+apOff, +critOff) — see the ring stage.
                fun ckAp(ck: Int) = (ck / 4) / critDim - apOff

                fun ckCrit(ck: Int) = (ck / 4) % critDim - critOff

                fun ckEpic(ck: Int) = (ck / 2) % 2

                fun ckRelic(ck: Int) = ck % 2

                fun stageOptions(name: String): List<Opt> =
                    when {
                        name == "slot:weapons" -> {
                            val res = mutableListOf<Opt>()
                            val none = Triple("(none)", Raw(0, 0, 0, 0, 0, 0, 0), emptyList<Int>())
                            val oneOpts =
                                listOf(none) +
                                    itemEquips
                                        .filter { it.itemType == ItemType.ONE_HANDED_WEAPONS }
                                        .flatMap { e -> rawOptions(e).map { Triple(e.name.en, it, listOf(e.equipmentId)) } }
                            val offOpts =
                                listOf(none) +
                                    (if (weaponsRestricted) emptyList() else itemEquips.filter { it.itemType == ItemType.OFF_HAND_WEAPONS }).flatMap { e ->
                                        rawOptions(e).map { Triple(e.name.en, it, listOf(e.equipmentId)) }
                                    }
                            for ((la, a, aIds) in oneOpts) {
                                for ((lb, b, bIds) in offOpts) {
                                    if (a.epic + b.epic > 1 || a.relic + b.relic > 1) continue
                                    res +=
                                        rawOpt(
                                            "$la + $lb",
                                            Raw(
                                                a.di + b.di,
                                                a.m + b.m,
                                                a.critM + b.critM,
                                                a.ap + b.ap,
                                                a.crit + b.crit,
                                                a.epic + b.epic,
                                                a.relic + b.relic,
                                                a.mp + b.mp
                                            ),
                                            aIds + bIds
                                        )
                                }
                            }
                            if (!weaponsRestricted) {
                                for (e in itemEquips.filter { it.itemType == ItemType.TWO_HANDED_WEAPONS }) {
                                    for (r in rawOptions(e)) {
                                        res +=
                                            rawOpt(e.name.en, r, listOf(e.equipmentId))
                                    }
                                }
                            }
                            res
                        }
                        name == "slot:rings" -> {
                            val res = mutableListOf<Opt>()
                            val rings = itemEquips.filter { it.itemType == ItemType.RING }
                            val mpRings = rings.filter { e -> rawOptions(e).any { it.mp > 0L } }
                            val plain = rings - mpRings.toSet()

                            // Mirrors the ring stage exactly: distinct-NAME top-2 per cell, and no
                            // same-name pair anywhere (the model forbids two same-name rings).
                            data class BestRing(
                                val g: Long,
                                val ck: Int,
                                val label: String,
                                val name: String,
                                val id: Int,
                            )
                            val top2 = HashMap<Int, MutableList<BestRing>>()
                            for (e in plain) {
                                var b: BestRing? = null
                                for (r in rawOptions(e)) {
                                    if (r.ap > apCeil + apOff || r.crit > critItemHigh + critOff) continue
                                    val g = grawOf(r)
                                    if (b == null || g > b.g) b = BestRing(g, ringCk(r), e.name.en, e.name.fr.lowercase(), e.equipmentId)
                                }
                                if (b == null || b.g <= 0) continue
                                val lst = top2.getOrPut(b.ck) { mutableListOf() }
                                val sameName = lst.indexOfFirst { it.name == b.name }
                                if (sameName >= 0) {
                                    if (b.g > lst[sameName].g) lst[sameName] = b
                                } else {
                                    lst += b
                                }
                                lst.sortByDescending { it.g }
                                while (lst.size > 2) lst.removeAt(2)
                            }
                            for ((ck, lst) in top2) {
                                res += Opt(lst[0].label, ckAp(ck), ckCrit(ck), ckEpic(ck), ckRelic(ck), 0, 0, lst[0].g, 0, ids = listOf(lst[0].id))
                                for ((ck2, lst2) in top2) {
                                    val same = ck == ck2
                                    val g2 =
                                        if (same) {
                                            lst.getOrNull(1) ?: continue
                                        } else if (lst[0].name != lst2[0].name) {
                                            lst2[0]
                                        } else {
                                            lst2.getOrNull(1) ?: continue
                                        }
                                    res +=
                                        Opt(
                                            "${lst[0].label} + ${g2.label}",
                                            ckAp(ck) + ckAp(ck2),
                                            ckCrit(ck) + ckCrit(ck2),
                                            ckEpic(ck) + ckEpic(ck2),
                                            ckRelic(ck) + ckRelic(ck2),
                                            0,
                                            0,
                                            lst[0].g + g2.g,
                                            0,
                                            ids = listOf(lst[0].id, g2.id)
                                        )
                                }
                            }
                            for (i in mpRings.indices) {
                                val nameI = mpRings[i].name.fr.lowercase()
                                for (r in rawOptions(mpRings[i])) {
                                    if (r.ap > apCeil + apOff || r.crit > critItemHigh + critOff) continue
                                    val g = grawOf(r)
                                    res += Opt(mpRings[i].name.en, r.ap, r.crit, r.epic, r.relic, 0, 0, g, r.mp, ids = listOf(mpRings[i].equipmentId))
                                    for ((ck2, lst2) in top2) {
                                        val partner = (if (lst2[0].name != nameI) lst2[0] else lst2.getOrNull(1)) ?: continue
                                        res +=
                                            Opt(
                                                "${mpRings[i].name.en} + ${partner.label}",
                                                r.ap + ckAp(ck2),
                                                r.crit + ckCrit(ck2),
                                                r.epic + ckEpic(ck2),
                                                r.relic + ckRelic(ck2),
                                                0,
                                                0,
                                                g + partner.g,
                                                r.mp,
                                                ids = listOf(mpRings[i].equipmentId, partner.id)
                                            )
                                    }
                                    for (j in i + 1 until mpRings.size) {
                                        if (mpRings[j].name.fr.lowercase() == nameI) continue
                                        for (r2 in rawOptions(mpRings[j])) {
                                            if (r2.ap > apCeil + apOff || r2.crit > critItemHigh + critOff) continue
                                            res +=
                                                Opt(
                                                    "${mpRings[i].name.en} + ${mpRings[j].name.en}",
                                                    r.ap + r2.ap,
                                                    r.crit + r2.crit,
                                                    r.epic + r2.epic,
                                                    r.relic + r2.relic,
                                                    0,
                                                    0,
                                                    g + grawOf(r2),
                                                    r.mp + r2.mp,
                                                    ids = listOf(mpRings[i].equipmentId, mpRings[j].equipmentId)
                                                )
                                        }
                                    }
                                }
                            }
                            res
                        }
                        name.startsWith("slot:") -> {
                            val slot = name.removePrefix("slot:")
                            itemEquips.filter { it.itemType.name == slot }.flatMap { e -> rawOptions(e).map { r -> rawOpt(e.name.en, r, listOf(e.equipmentId)) } }
                        }
                        name.startsWith("skills:") -> {
                            val bi = name.removePrefix("skills:").toInt()
                            val (pool, infos) = skillBranches[bi]
                            val res = mutableListOf<Opt>()
                            val critVar = infos.firstOrNull { it.crit != 0 }
                            val apVarI = infos.firstOrNull { it.ap != 0 }
                            val diVars = infos.filter { it.di != 0L }.sortedByDescending { it.di }
                            val mpVars =
                                infos
                                    .filter {
                                        it.mp > 0L && it.di == 0L && it.m == 0L && it.critM == 0L && it.crit == 0 && it.ap == 0
                                    }.sortedByDescending { it.mp }
                            val grawVars = infos.filter { it.di == 0L && it.crit == 0 && it.ap == 0 && it.mp == 0L }

                            fun fillG(points: Int): Long {
                                var rem = points
                                var g = 0L
                                for (gv in grawVars.sortedByDescending { (400L + cEff) * it.m + 5L * cEff * it.critM }) {
                                    if (rem <= 0) break
                                    val per = (400L + cEff) * gv.m + 5L * cEff * gv.critM
                                    if (per <= 0) continue
                                    val take = minOf(rem, gv.cap)
                                    g += take * per
                                    rem -= take
                                }
                                return g
                            }
                            val diCapTotal = diVars.sumOf { it.cap }
                            val mpCapTotal = mpVars.sumOf { it.cap }
                            for (ccPts in 0..(critVar?.let { minOf(it.cap, pool) } ?: 0)) {
                                for (apPts in 0..(apVarI?.let { minOf(it.cap, pool - ccPts) } ?: 0)) {
                                    val rem0 = pool - ccPts - apPts
                                    for (d in 0..minOf(rem0, diCapTotal)) {
                                        var di = 0L
                                        var left = d
                                        for (dv in diVars) {
                                            val take = minOf(left, dv.cap)
                                            di += take * dv.di
                                            left -= take
                                            if (left <= 0) break
                                        }
                                        for (mpPts in 0..minOf(rem0 - d, mpCapTotal)) {
                                            var mpv = 0L
                                            var leftMp = mpPts
                                            for (mv in mpVars) {
                                                val take = minOf(leftMp, mv.cap)
                                                mpv += take * mv.mp
                                                leftMp -= take
                                                if (leftMp <= 0) break
                                            }
                                            res +=
                                                Opt(
                                                    "branch$bi cc=$ccPts ap=$apPts diPts=$d mpPts=$mpPts",
                                                    apPts * (apVarI?.ap ?: 0),
                                                    ccPts * (critVar?.crit ?: 0),
                                                    0,
                                                    0,
                                                    0,
                                                    di,
                                                    fillG(
                                                        rem0 - d - mpPts
                                                    ),
                                                    mpv
                                                )
                                        }
                                    }
                                }
                            }
                            res
                        }
                        // A rarity sub occupies the epic/relic SUB slot without consuming the ITEM
                        // rarity budget (applyRaritySub) — the option must not carry dEpic/dRelic — and
                        // rides its dedicated slot, not the n dimension (dN = 0, mirroring the stage).
                        name == "sub:epic-slot" -> epicSubs.map { (sub, r) -> rawOpt(sub.name.en, r).copy(dN = 0, dAp = 0, dCrit = 0, dEpic = 0, dRelic = 0, mp = 0L) }
                        name == "sub:relic-slot" -> relicSubs.map { (sub, r) -> rawOpt(sub.name.en, r).copy(dN = 0, dAp = 0, dCrit = 0, dEpic = 0, dRelic = 0, mp = 0L) }
                        name.startsWith("sub:") -> {
                            val subName = name.removePrefix("sub:")
                            orderedNormalTransitions.filter { it.first.name.en == subName }.flatMap { (sub, r, mult) ->
                                val ramp =
                                    if (mpRampEnabled) {
                                        sub.perStatStep?.takeIf { it.source == Characteristic.MOVEMENT_POINT && it.target == Characteristic.DAMAGE_INFLICTED }?.let { sub }
                                    } else {
                                        null
                                    }
                                // Mirror the multiplicity stage: one option per copy count j (a mult > 1 sub is
                                // never a ramp, so the j-fold is linear). A dN=1-only option would break the
                                // backtrack on every stacked transition and kick E8 onto the full-pool fallback.
                                (1..(if (ramp == null) mult else 1)).map { j ->
                                    Opt(if (j == 1) sub.name.en else "${sub.name.en} ×$j", 0, 0, 0, 0, j, j * r.di, j * grawOf(r), j * r.mp, ramp)
                                }
                            }
                        }
                        else -> emptyList()
                    }

                var curKey = bestKey
                var curPt = winPt.copyOf()
                val lines = ArrayList<String>()
                for (si in snaps.indices.reversed()) {
                    if (si == 0) break
                    val stageName = snaps[si].first
                    val prev = snaps[si - 1].second
                    var matched = false
                    val options = stageOptions(stageName)
                    outer@ for ((k0, fr0) in prev) {
                        val n0 = (k0 % (subCap + 1)).toInt()
                        var rest = k0 / (subCap + 1)
                        val relic0 = (rest % 2).toInt()
                        rest /= 2
                        val epic0 = (rest % 2).toInt()
                        rest /= 2
                        val crit0 = (rest % critDim).toInt()
                        val ap0 = (rest / critDim).toInt()
                        for (o in options) {
                            // ap0/crit0 are STORED (offset) coordinates; option deltas are real. This is a
                            // deliberately LOOSE pre-filter: the forward pass now prunes per-stage (tighter),
                            // but 2·off is ≥ any stage's headroom, so it still admits every real parent —
                            // the exact match is the key/point equality below, so a looser filter only
                            // skips impossible parents, never a real one. Explain output is unchanged.
                            if (ap0 + o.dAp > apCeil + 2 * apOff || crit0 + o.dCrit > critItemHigh + 2 * critOff) continue
                            if (epic0 + o.dEpic > 1 || relic0 + o.dRelic > 1 || n0 + o.dN > subCap) continue
                            if (key(ap0 + o.dAp, crit0 + o.dCrit, epic0 + o.dEpic, relic0 + o.dRelic, n0 + o.dN) != curKey) continue
                            for (p0 in fr0.toArrays()) {
                                val di1 =
                                    if (o.ramp != null) {
                                        val pss = o.ramp.perStatStep!!
                                        minOf(o.di, pss.contribution((mpFreeMax + p0[2]).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()).toLong())
                                    } else {
                                        o.di
                                    }
                                if (p0[0] + di1 == curPt[0] && p0[1] + o.graw == curPt[1] && p0[2] + o.mp == curPt[2]) {
                                    lines += "$stageName: ${o.label}  (di+$di1 graw+${o.graw} mp+${o.mp})"
                                    if (stageName.startsWith("slot:")) explainItemIds?.addAll(o.ids)
                                    curKey = k0
                                    curPt = p0.copyOf()
                                    matched = true
                                    break@outer
                                }
                            }
                        }
                    }
                    if (!matched) {
                        val skipFr = prev[curKey]
                        if (skipFr != null && skipFr.toArrays().any { it[0] == curPt[0] && it[1] == curPt[1] && it[2] == curPt[2] }) {
                            // silent skip — the stage contributed nothing to the winning point
                        } else {
                            lines += "$stageName: ??? no transition matched (provenance broken here)"
                            // Near-miss dump: decode the target state and show the closest parents.
                            var r0 = curKey / (subCap + 1)
                            val tRelic = (r0 % 2).toInt()
                            r0 /= 2
                            val tEpic = (r0 % 2).toInt()
                            r0 /= 2
                            lines +=
                                "  target key: ap=${(r0 / critDim).toInt() - apOff} crit=${(r0 % critDim).toInt() - critOff} epic=$tEpic relic=$tRelic n=${(curKey % (subCap + 1)).toInt()} pt=(${curPt[0]},${curPt[1]},${curPt[2]})"
                            lines += "  options: " + options.take(8).joinToString { "${it.label}[di${it.di},g${it.graw},mp${it.mp},dN${it.dN}]" }
                            // Exact-parent query: same (ap,crit,epic,relic) at each n — list the points.
                            var rq = curKey / (subCap + 1)
                            val qRelic = (rq % 2).toInt()
                            rq /= 2
                            val qEpic = (rq % 2).toInt()
                            rq /= 2
                            val qCrit = (rq % critDim).toInt()
                            val qAp = (rq / critDim).toInt()
                            for (nq in 0..subCap) {
                                val kq = key(qAp, qCrit, qEpic, qRelic, nq)
                                val fq = prev[kq] ?: continue
                                val near = fq.toArrays().filter { it[1] == curPt[1] }
                                lines += "  prev n=$nq sameGraw: ${near.joinToString { "(${it[0]},${it[1]},${it[2]})" }} (${fq.size} pts)"
                            }
                            for ((k0, fr0) in prev) {
                                for (p0 in fr0.toArrays()) {
                                    val dDi = curPt[0] - p0[0]
                                    val dG = curPt[1] - p0[1]
                                    val dMp = curPt[2] - p0[2]
                                    if (dDi in 0..40 && dMp in 0L..3L && k0 != curKey) {
                                        var rr = k0 / (subCap + 1)
                                        val pRelic = (rr % 2).toInt()
                                        rr /= 2
                                        val pEpic = (rr % 2).toInt()
                                        rr /= 2
                                        lines +=
                                            "  near parent: key(ap=${(rr / critDim).toInt() - apOff} crit=${(rr % critDim).toInt() - critOff} e=$pEpic r=$pRelic n=${(k0 % (subCap + 1)).toInt()}) delta=(di$dDi,g$dG,mp$dMp)"
                                        if (lines.size > 40) break
                                    }
                                }
                                if (lines.size > 40) break
                            }
                            break
                        }
                    }
                }
                lines.reverse()
                var rest = bestKey / (subCap + 1)
                rest /= 4
                val winCritDim = (rest % critDim).toInt() - critOff
                val critGap = c - critConst - winCritDim
                val cover = mutableListOf<String>()
                var covered = 0L
                for ((sub, r) in critBudgetSubs.sortedByDescending { it.second.crit }) {
                    if (covered >= critGap) break
                    covered += r.crit
                    cover += "${sub.name.en}(+${r.crit})"
                }
                explainOut.add(
                    "cell $apTarget world(conv=${convTaken?.name?.en ?: "-"} critSecret=${critSecret?.name?.en ?: "-"} weaponsRestricted=$weaponsRestricted) " +
                        "c=$c di=${100 + diConst + winPt[0]} graw=${grawConst + winPt[1]} mp=${winPt[2]} perHit=$best"
                )
                explainOut.add("crit budget cover for gap $critGap: ${cover.joinToString()}")
                explainOut.addAll(lines)
            }
        }
    }
    winningCOut?.set(0, bestC)
    if (System.getenv("WAKFU_MAX_DAMAGE_CERT_DEBUG") == "1") System.err.println("CERT_DEBUG_BEST $bestDbg")
    if (statsEnabled) {
        System.err.println(
            "CERT_STATS ap=$apTarget conv=${convTaken?.name?.en ?: "-"} " +
                "critSecret=${critSecret?.name?.en ?: "-"} weaponsRestricted=$weaponsRestricted " +
                "stages=$statStages states=$statStates points=$statPoints addCalls=${Frontier.statsAddCalls}"
        )
    }
    return best
}

internal data class LinearTermSum(
    val terms: MutableList<Term>,
    val constant: Long,
)
