package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpModel
import com.google.ortools.sat.CpSolver
import com.google.ortools.sat.CpSolverSolutionCallback
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Passive
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.Assignable
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.common.skills.UnitType
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.min

/**
 * Two-tier max-damage certificate for a single element (see `docs/CERTIFICATE_PROD_PLAN.md` §P3). All
 * values are in OBJECTIVE units (the ONE scaling formula
 * `clampedTable[ap] * (maxPerHit / PERHIT_DOWNSCALE) * resFactor / FINAL_DOWNSCALE`), so they compare
 * directly against CP-SAT objectives.
 *
 * - [cellObjectives]: per-AP-cell certified upper bound — the EXACT value for [tier2Cells], the fast
 *   value for cells eliminated below the incumbent (both are sound upper bounds; eliminated cells only
 *   ever need `≤ incumbent`). Excludes [bailedCells].
 * - [bailedCells]: cells with NO sound bound at all (the fast pass shape-bailed — all-or-nothing in
 *   practice). Their presence forces [maxCellObjective] to `null`.
 * - [tier2Cells]: cells CONFIRMED by the exact pass. A surviving cell on which the exact pass itself
 *   bails keeps its (still sound) fast value and is deliberately NOT listed here — that is not a bail.
 * - [maxCellObjective]: `max` over [cellObjectives]; `null` iff [bailedCells] is non-empty (an
 *   unbounded cell means no provable ceiling for the element).
 */
data class CertLedger(
    val cellObjectives: Map<Int, Long>,
    val bailedCells: Set<Int>,
    val tier2Cells: Set<Int>,
    val maxCellObjective: Long?,
    // B4 (incumbent-free cache): the RAW, incumbent-independent per-cell parts, so a re-search of the SAME shape
    // with a different incumbent can reconstruct its ledger from cache instead of re-running the DPs.
    // - [fastObjectives]: the fast-tier upper bound for EVERY cell (empty iff [bailedCells] non-empty). Unlike
    //   [cellObjectives] (which is the exact value on [tier2Cells]), this is the pure fast array, so a new
    //   incumbent's elimination boundary can be recomputed. A value-typed Map (not LongArray) so data-class
    //   equality stays structural.
    // - [exactBailedCells]: survivors whose EXACT pass itself bailed (a sound bound still exists — they keep fast).
    //   Cached so the reconstruction knows to keep such a cell at fast rather than treating it as "not yet computed".
    // - [tier15Objectives] (B7): survivors sharpened by the step-1 tier-1.5 pass, incumbent-independent, in OBJECTIVE
    //   units. A cell CLEARED by tier-1.5 (bound ≤ incumbent) carries this value in [cellObjectives]; cached so a
    //   re-search reconstructs the elimination without re-running the sharpened DP. Empty on the oracle
    //   (`forceTier2All` / no-incumbent) path, which confirms every survivor exactly.
    val fastObjectives: Map<Int, Long> = emptyMap(),
    val exactBailedCells: Set<Int> = emptySet(),
    val tier15Objectives: Map<Int, Long> = emptyMap(),
    // E8 item A (perf): the winning (certifier world, crit-step) per EXACT-confirmed cell ([tier2Cells]). Captured
    // for free during the exact pass so the E8 fast-path replays that ONE (world, c) as a single explain pass to
    // recover the argmax build's items, instead of re-running the whole N-worlds provenance scan (~minutes at high
    // level). Additive + optional: a cell absent here (an old cache entry, or a tier-1.5-cleared argmax) makes the
    // fast-path fall back to the full scan — sound and correct, just slower. It changes NO bound, so there is NO
    // CERTIFIER_VERSION bump and NO oracle re-run.
    val cellProvenance: Map<Int, CellProvenance> = emptyMap(),
)

/**
 * E8 item A (perf): the winning (certifier world, crit-step) of a cell's EXACT certificate bound — see
 * [CertLedger.cellProvenance]. [worldIndex] indexes the deterministic `certifierWorlds` list and [c] is the
 * arithmetic crit total; both are re-derived under the same [WakfuBuildSolver.CERTIFIER_VERSION] (which the
 * cache key pins), so a cached pointer can never bind to a different world enumeration.
 */
@Serializable
data class CellProvenance(
    val worldIndex: Int,
    val c: Int,
)

// Solver-wide numeric bounds/scales, de-nested from the object so StatBuilder.kt (same package) sees them
// by bare name instead of importing 30+ object members (B1 of docs/code-review-followups.md).
internal const val STAT_ABS_MAX = 10_000_000L
internal const val PERCENT_ABS_MAX = 10_000L
internal const val PRODUCT_ABS_MAX = STAT_ABS_MAX * PERCENT_ABS_MAX
internal const val STAT_WITH_PERCENT_ABS_MAX = STAT_ABS_MAX + (PRODUCT_ABS_MAX / 100) + 10
internal const val MAX_POWER_TABLE_INDEX = 2_000
internal const val MAX_PENALTY_MULTIPLIER = 1_000_000L
internal const val MIN_PENALTY_MULTIPLIER = 1L // floor of every power-table bucket — see [penaltyMultiplier].
internal const val MAX_NORMAL_SUBLIMATIONS = 10L // Wakfu: at most 10 NORMAL sublimations (one per socketed gear slot).
internal const val MAX_SUBLIMATIONS_TOTAL = MAX_NORMAL_SUBLIMATIONS + 2L // + 1 epic + 1 relic (dedicated slots) = 12.
internal const val NORMAL_SUB_SOCKET_COST = 3L // a normal sublimation needs a 3-socket carrier for its ordered colour pattern.
internal const val MAX_OUT_OF_COMBAT_AP = 16L
internal const val MAX_OUT_OF_COMBAT_MP = 8L
internal const val MAX_OUT_OF_COMBAT_WP = 20L
internal const val MIN_OUT_OF_COMBAT_CRIT = -9L // negative-crit gear is condition-limited to ≥ −9% total.
internal const val DAMAGE_MASTERY_MAX = 100_000L
internal const val CLAMP_INTERMEDIATE_MAX = 8_000_000_000L
internal const val DAMAGE_GRAW_MAX = 400L * DAMAGE_MASTERY_MAX + 100L * (DAMAGE_MASTERY_MAX * 6)
internal const val DAMAGE_SCORE_ABS_MAX = (100L + DAMAGE_DI_MAX) * DAMAGE_GRAW_MAX
internal const val MAX_ROTATION_AP = 20L
internal const val PER_TURN_THROUGHPUT_MAX = 60_000L
internal const val RES_FACTOR_MIN = 10L // res capped at +90% → factor ≥ 10
internal const val RES_FACTOR_MAX = 200L // weakness floored at −100% → factor ≤ 200
internal const val PERHIT_DOWNSCALE = 100_000L
internal const val PERHIT_SCALED_MAX = DAMAGE_SCORE_ABS_MAX / PERHIT_DOWNSCALE + 1 // ≈ 5.1e6
internal const val ROTATION_RAW_MAX = PER_TURN_THROUGHPUT_MAX * PERHIT_SCALED_MAX // ≈ 3.06e11
internal const val ROTATION_RAW_RES_MAX = ROTATION_RAW_MAX * RES_FACTOR_MAX // ≈ 6.12e13
internal const val FINAL_DOWNSCALE = 20L
internal const val DAMAGE_PERTURN_ABS_MAX = ROTATION_RAW_RES_MAX / FINAL_DOWNSCALE // ≈ 3.06e12
internal const val FAST_C_SEGMENT_STEP = 8
internal const val EHP_HP_MAX = 1_000_000L
internal const val EHP_AVG_RESIST_CAP = 80L
internal const val EHP_MAX = EHP_HP_MAX * (100L + EHP_AVG_RESIST_CAP) / 100L
internal const val PRECISION_OVERFLOW_BOUND = 1_000_000_000L

/**
 * Scale of the required-target penalty power table over buckets `0..maxIndex`: `maxIndex⁶ /
 * MAX_PENALTY_MULTIPLIER` (1 for small tables), so the top bucket maps to ≈ [MAX_PENALTY_MULTIPLIER].
 */
internal fun penaltyPowScale(maxIndex: Long): BigInteger {
    val maxPow = BigInteger.valueOf(maxIndex).pow(6)
    val target = BigInteger.valueOf(MAX_PENALTY_MULTIPLIER)
    return if (maxPow > target) maxPow.divide(target) else BigInteger.ONE
}

/**
 * The required-target penalty multiplier of bucket [index]: `max(MIN_PENALTY_MULTIPLIER, index⁶ / powScale)`,
 * [powScale] from [penaltyPowScale]. The ONE definition every mirror shares — the CP-SAT soft objective
 * ([WakfuBuildSolver.applyConstraintPenalty]), both soft certificates ([MostMasteriesCertificate],
 * [MaxDamageSoftCertificate]) and their research harnesses — so a certificate can never price a bucket below
 * the solver (an under-count). The re-scorers cap their continuous factor at [MAX_PENALTY_MULTIPLIER], the same
 * floor relative to a target-meeting build.
 *
 * Why the floor: the integer division maps every bucket with `(index / maxIndex)⁶ < 1 / MAX_PENALTY_MULTIPLIER`
 * (a weighted target ratio below ~10%) to 0. When the targets were that far out of reach for EVERY build,
 * `core × 0` made the soft objective flat: the empty build tied the optimum and multi-worker solves returned
 * it. Flooring at 1 keeps the core's gradient there (those builds rank by the core alone, at ≈1e-6 of a
 * target-meeting build) and leaves every entry already ≥ 1 — so every objective value outside that region —
 * bit-identical. The table stays monotone non-decreasing in [index], which the certificates' soundness rests
 * on (an over-counted bucket never lowers the multiplier).
 */
internal fun penaltyMultiplier(
    index: Long,
    powScale: BigInteger,
): Long =
    BigInteger
        .valueOf(index)
        .pow(6)
        .divide(powScale)
        .toLong()
        .coerceAtLeast(MIN_PENALTY_MULTIPLIER)

object WakfuBuildSolver {
    private val logger = KotlinLogging.logger {}

    // MASTERY_SCORE_ABS_MAX is shared with the re-scorer — see ScoreComputationMode.kt.

    // Out-of-combat hardcaps (Wakfu): the equipped sheet can't exceed these. In-combat bonuses — including
    // start-of-combat sublimations — may go beyond, so the cap is on the PRE-sublimation value.

    // Bounds for the max-damage objective's nonlinear terms. Masteries / DI are clamped into these
    // (well above any real build) so the CP-SAT multiplication variables keep small, stable domains.
    // DAMAGE_DI_FLOOR / DAMAGE_DI_MAX are shared with the re-scorers — see ScoreComputationMode.kt.

    // Spell-aware / boss-aware per-turn damage (max-damage mode only). The per-turn value is
    // `(throughput × perHit) × resFactor`, scaled to keep every CP-SAT variable domain modest (≤ ~6e13,
    // well inside int64 so presolve never overflows) while preserving ranking resolution. The per-hit
    // core is first divided by [PERHIT_DOWNSCALE] (keeps ~5M levels — fine even for low-level builds),
    // then the `× resFactor` product is divided by [FINAL_DOWNSCALE] so the value — and then the
    // power-6 constraint penalty (× MAX_PENALTY_MULTIPLIER) — stays under Long.MAX/2.

    /**
     * Certifier semantics version — **bump on ANY change to the certifier** (fast pass, exact pass,
     * orchestrator, scaling formula, world enumeration). Keys the P4.3 session cache alongside
     * [me.chosante.common.WakfuData.VERSION], so a certifier change invalidates every cached per-cell bound
     * instead of silently serving a stale (possibly now-unsound) certificate.
     *
     * History — 8: fast-pass DenseDp port; 9: (superseded) subCap 10→12 + item-flag normal filter; 10: n
     * counts NORMAL-slot subs only — epic/relic subs ride their dedicated slots at the same n (budgets/topK
     * back on the normal cap) + exact sub-MP debit (no ≥0 clamp) for MP-sourced ramps; 11: (superseded) first
     * cut of cumulable stacking — the keptSubs pool-duplication was in place but the model's copy vars leaked
     * into the passive DI/mastery constants at their untracked ±1e7 domain, exploding the bound; 12: cumulable
     * stacking done right — [keptSubs] duplicates a cumulable sub's single-copy Raw [Sublimation.maxCopies]
     * times, and the model's copy vars are DROPPED from the certifier term lists ([certifierDroppedVars]) so
     * their value rides the base subVar term instead of leaking into the constants; 13: FORCED cumulable subs
     * stack as well — their base copy stays in the constants (and pre-charges its slot via `subCap`) while their
     * `maxCopies − 1` extra copies join the OPTIONAL pools, so the budget/crit-window machinery prices them like
     * any other copy; 14: multiplicity encoding — the DP passes collapse a cumulable sub's duplicated entries
     * into ONE stage taking j ∈ 0..maxCopies copies at once (`normalTransitionStages`). Same reachable set,
     * identical certified values (a mult > 1 sub is unconditional and never a ramp, so its per-copy contribution
     * is constant), but half the transition stages — v13 paid one full frontier sweep per COPY, which cost 3.4×
     * on the lvl-110 badge proof and ≥7.8× on a lvl-245 back+berserk request. keptSubs stays duplicated for the
     * slot-counting consumers (budgets / segment edges / minSubsToCover); 15: FAMILY BUDGETS — mono-axis
     * unconditional transition subs (pure-DI, pure-mastery) leave the DP stages entirely and are priced at
     * harvest as sorted-prefix budgets (`diPrefix` / `grawBudgetPrefix` + `budgetMax` split enumeration over the
     * free slots), exactly like the pre-existing pure-crit / pure-AP budgets; all-zero Raws (off-element DI subs
     * in a mono-element scenario) are dropped outright. Reachable value set identical (sorted-prefix selection
     * is exact for a mono-axis family) ⇒ certified values unchanged; only the DP frontier shrinks;
     * 16: INDEXED FAST HARVEST default ON (campaign-2 C-0, plan §8.6) — the fast pass hands the harvest a
     * packed `(state, AP-cell, crit-step)` coordinate list built during the DP sweep instead of re-scanning
     * `all cells × all crit steps`. Same visiting order and predicates ⇒ ledger byte-identical (locked by
     * [MaxDamageCertifierHarvestIndexTest]); fast tier −16%, total −9% serial. OFF seam:
     * `WAKFU_MAX_DAMAGE_CERT_INDEXED_HARVEST=0`;
     * 17: fixes the soft-proof conditions-stripped relaxation so STATIC_CONDITIONAL subs become
     * FLAT instead of being filtered out. This restores the required upper-bound relation on
     * conditional-carrying optima and invalidates every cached union computed by v16;
     * 18: adds the bounded external conditional-world B&B ahead of the soft DP. Its exhaustive
     * `{sub=0 | sub=1+exact-condition}` partitions either close at the incumbent or contribute a
     * sound global frontier dual; every cached v17 soft union must therefore be recomputed;
     * 19: confines that B&B to the measured level≤110 regime and makes its 180 s budget terminal
     * (inconclusive returns the sound frontier upper instead of stacking the old DP/oracle budgets).
     * OPTIMAL nodes also use their rounded exact objective, avoiding a +1 floating dual epsilon;
     * 20: removes per-candidate pinned probe solves from conditional-world branching. They only
     * improved the branch heuristic (not soundness) and consumed much of the real total budget;
     * 21: intersects every finite-time child dual with its inherited parent dual. A child is a
     * subset of its parent, so this is an exact free tightening and prevents timeout noise from
     * making a deeper frontier bound worse than an already-known ancestor bound.
     * 36: adds the per-carrier closure (silent refinement, journal 2026-07-21) — carrier-forced
     * full-exact CP worlds + STRICT blocker set-cover composing with the proven no-condition
     * oracle into an exact soft-leg upper; its memo is keyed by this version;
     * 37: every soft-proof CP read goes through [MaxDamageTimedProfile.soundUpper] — an UNKNOWN /
     * MODEL_INVALID solve without a real dual (stopped or timed out inside presolve: native bound 0)
     * no longer reads as "world closed at 0"; cap-sub world splits bail on a non-EPIC cap sub;
     * 38: most-masteries certificate under-count fixes (pre-release review 2026-10-01) — paired-skill
     * halves credited (major MP), Armure lourde's MAX_MP debit ignored instead of lowering the MP cap,
     * selected passives credited, MAX_ACTION_POINT folded into the assume-AP low read, start-of-combat
     * lines kept out of the LOW dims but added (with ramps/passives) to the assume-world constants and
     * world-B M-caps, block-only subs kept; hard-leg results compared in soft units. The max-damage
     * soft certificate gets the same low-read fixes (signed AP + MAX_AP, start-of-combat lines out of
     * the LOW dims, low-read/block-only subs kept), the outside-read constants (assume worlds, critZero
     * arm), the Major AP point always staged, and a bail on secondary lines outside the first-turn read.
     * 39: the required-target penalty multiplier is floored at 1 ([penaltyMultiplier]) in the solver AND
     * both soft certificates (MM `PenaltyGeometry.power6`, the max-damage collapse table and
     * `PenaltyProfile.multiplier`): buckets whose `index⁶ / powScale` floored to 0 (weighted target ratio
     * below ~10%) now price `core × 1` instead of 0 — the soft objective was flat there, so the empty build
     * tied the optimum. Entries already ≥ 1 are unchanged (bounds outside that region are bit-identical),
     * but every cached soft bound/union priced those states at 0 — an under-count against the new objective.
     * 40: the most-masteries certificate prunes every stage's options to their exact Pareto front
     * (`paretoPrune`) and advances on primitive maps ([LongLongMaxMap]) — bound and core bit-identical (S2
     * full tier 85 s → 9 s, 4.6M → 45k states); bumped per the standing rule, like v16's indexed harvest.
     * 41: most-masteries certificate T5 (MOST_MASTERIES_PERF_PLAN §8.18) — the 10-slot normal-sub knapsack is
     * EXACT per subset (stats summed raw and rounded once in the DP instead of once per copy; DI signed, so a
     * carried sub's negative rider pays; every other sub's DI is its NET, clamped at 0), plus two latent
     * under-counts found on the way: the AP/MP dims saturated at the 16/8 out-of-combat cap although
     * sublimations land above it (a target above the cap read one short), and a sub whose only tracked effect
     * is a negative pre-combat AP (Carapace II's −1 MAX_AP) was dropped instead of relaxing the assume-AP read.
     * 42: most-masteries certificate T1 — in the assume-CC worlds the start-of-combat crit of the subs a path
     * actually carries rides a saturating state dim (`soc`, the exact normal packing included) instead of the
     * budget-free `outsideReadMax(CRITICAL_HIT)` constant, which now keeps only passives, ramps and never-staged
     * subs (world-B subs' crit rides their per-state `extra`, no longer double-counted).
     * 43: most-masteries certificate T3 — the same DP pass is read twice at the collapse: the soft read, and a
     * TARGETS-MET read (states whose over-counted reads meet every required target > 0, folded at the full-targets
     * multiplier) that bounds the hard leg's feasible set; a result flagged
     * [me.chosante.autobuilder.genetic.SolverResult.mostMasteriesHardConstraintsMet] is compared with the latter.
     * 44: max-damage AP-cell coverage + two under-count fixes. (a) The GENERAL single-type rune fold — what any
     * HP / resistance / dodge / lock / initiative / off-scenario-mastery target row (even 0-valued, i.e. every
     * GUI-default request) puts in the model — is mirrored instead of bailing every cell: one per-item option per
     * rune pick, a non-damage pick being a zero-delta option the best damage rune dominates (an over-count).
     * (b) The pools' long-standing DROP of the Neutralité family (`each secondary mastery ≤ 0`) and of the EPIC block
     * sub Mesure under-counted any build whose optimum carries one (reproduced on seeded 4-item pools); each family
     * now gets AUX worlds (secondary-capped N / N×C, block-assumed M / N×M), run at the fast tier and folded into
     * every tier as a per-cell floor ([certifierAuxFloor]). Cells can only RISE vs v43 (v41–v43 changed only the
     * most-masteries certificate) — every cached bound is stale.
     * 45: most-masteries certificate — ONE source for the request-level bails (`requestShape`, MOST_MASTERIES_PERF_PLAN
     * §8.20), read by `bound` (every world) and `supportsRequest` (the search-time warm-up's gate, which had missed the
     * final-stat-upper conversion bail, the AP/MP field overflow, the cap-sub count/rarity bails and the second-ramp
     * bail). Hardening bails on shapes no current request reaches: one stat required twice (the fold read one row per
     * stat — an under-count), a choosable sub converting into DI or a tracked CC / HP / block (its moved value rode no
     * option — an under-count), and the CC / HP / block packed-field overflows (exceptions before). Bounds bit-identical
     * otherwise.
     * 46: most-masteries certificate COVERAGE (§8.20) — a 0-valued required row of any stat is an exact skip (the model
     * weighs it 0 in the penalty and the overshoot and its hard leg skips it; the objective still folds), and RANGE
     * targets get a saturating state dim (items, runes, subs incl. the exact packing, the Major "Range and damage"
     * point, passives, world-B / assumed credits; positive lines only; a target ≤ 31, no conversion into range). The
     * GUI-default request (RANGE 4, wind resistance 0, dodge 0) no longer bails; requests without RANGE or 0-valued rows
     * are bit-identical.
     * 47: most-masteries certificate — the %HP skill's share of late-staged sub HP (§8.20): the EPIC / RELIC sub stages
     * (after the skills) and the world-B / assumed cap subs (collapse-time credits) added flat HP the skills stage never
     * scaled — an under-count of any HP read (no choosable sub carries HP on 1.93). That HP is now scaled by the largest
     * reachable %HP (an over-count). Bounds bit-identical on the current catalog.
     * 48: the max-damage aux-world SCHEDULE ([certifierAuxPlan]): the six secondary-capped aux worlds are bounded by ONE relaxed
     * world (weapon split relaxed, Critical Secret / the block sub credited as slot-free constants) and their exact
     * split only runs when that bound exceeds the value it would floor — every certified value is the v44 value
     * (locked by the relaxed-vs-split equality test), at 3 instead of 8 eager aux passes; aux worlds also re-read the
     * thread count per world (a warm-up whose search ends midway fans out). Bumped per the standing rule.
     * 49: max-damage AP-cell certifier under-count B1 (`docs/perf-review-backlog.md` §E) — with an MP→DI ramp sub
     * modeled (Poids Plume III, choosable by default) the paired Major "Movement Point and damage" point (+1 MP AND +20
     * elemental mastery) fit neither the pure-MP list nor the graw fill of the skill-branch cells and was DROPPED in the
     * fast, tier-1.5, exact and explain passes — its mastery and the MP it feeds into the ramp lost (−5.3 % on a
     * repro cell; a wrong ProvenOptimal on a 3.8 % sub-optimal build). MP+graw skill vars now get their own exact split
     * (`mpGrawSplits`: each point rides the MP axis AND adds its graw) in all four, plus bails on the two var shapes no
     * list could hold (a DI var carrying another value axis, a negative MP+graw line). Cells can only RISE (the
     * zero-point split is the old cell): the lvl-245 fast ledger rose 0.3–3.4 % on AP 2–15, its max cell unchanged.
     * The extra Pareto points cost the 245 warm-up ledger +41 %, so a value-exact MP saturation clamp follows the skill
     * stages (`mpSaturationClamp`: MP past every ramp's saturation, later debits included, is rewritten to the clamp —
     * no path changes value) and brings it back to the v48 time.
     * 50: A1 — the assume worlds' LOW dims (the capped crit / AP read of an AT_MOST cap sub:
     * Constance, Mesure III, Inflexibilité) were floored at 0 after every stage, in the most-masteries certificate AND
     * the max-damage soft twin. The real pre-combat read goes negative (a −10-crit ring staged first), so the floored dim
     * rose above it and rejected the real carrier in its own world — an under-count (−15.4 % on the 3-item repro,
     * −0.81 % on real level-245 items, −4.8 % on the soft twin). The dims are now stored with an OFFSET grown by each
     * stage's worst negative delta (seed, items, knapsack and sub stages alike), so no transition floors them; the field
     * takes the key's 2 spare bits @61 (AP 7 bits, raw CC 9 bits — the level-245 catalog needs 118 + 51 ≤ 511) and a
     * world whose offset still outgrows it bails. Bounds only rise (S2 / S3 / GUI-default bit-identical).
     * 51: the A1 / B1 review follow-ups (`docs/perf-review-backlog.md` §E) — bails on shapes no shipped item or sub
     * reaches, each of which would under-count: in the most-masteries certificate a NEGATIVE capped-stat line (crit,
     * AP / MAX_ACTION_POINT) on a cap sub or a world-B sub (never staged into an assume world's LOW dim, yet in the
     * solver's pre-combat read), and a POSITIVE MAX_ACTION_POINT / MAX_MOVEMENT_POINT line on an item or a sub (no AP /
     * MP read folds it); in the max-damage soft twin the assumed cap sub's own negative capped-stat line (its world-B
     * cappers are staged, no bail needed) and the same positive MAX_* riders; in the AP-cell certifier's secondary-capped
     * world N a FLAT sub's ramp into a secondary mastery (priced as a read source). Plus the MM / soft provenance replay
     * undoing each stage's LOW-offset shift (instrument only) and the soft certificate's never-set `mpCapMinus` removed.
     * Bounds bit-identical (S2 / S3 locked).
     * 52: the TARGET-AWARE AP-cell certificate (`docs/CERTIFICATE_PROD_PLAN.md` §P5.6, research memo track 1). A HARD-LEG
     * result's ledger ([StatBuilder.certifierTargetAware]) enforces the request's required AP / MP / CC / RANGE rows in every
     * pass — fast, tier-1.5, exact and the aux worlds: cells below the AP row read 0, crit steps below the CC row are skipped,
     * a frontier point whose over-counted MP cannot reach the MP row is dropped (the v49 MP clamp raised to keep that test
     * exact, and clamping the axis on its own when no ramp is modeled), and RANGE rides a saturating lowest key digit
     * (items floored at 0, the Major range point exact; base, passives and every sub's positive range a free constant —
     * Furie II's `RANGE_AT_LEAST 4` excluded, its condition already implies the row) with a suffix-reachability prune. Each
     * filter reads a sound over-estimate of the build's own stat, so no targets-met build is ever dropped: the bound covers
     * exactly what the hard leg can return (under the dim the fast pass applies its item stages widest-first — values
     * unchanged). The flag keys the in-memory and disk caches; soft-leg and free results keep the target-blind ledger (a
     * row-less request gets it bit for bit even with the flag set — locked). GUI-default badge on the production-shaped
     * proof (4-core, 420 s incumbents): 7.83 % → 2.71 % at level 110, 13.05 % → 7.03 % at 200, 8.15 % → 3.70 % at 245.
     * Plus two PRE-EXISTING under-counts its fixtures exposed, both of which only raise a bound: the exact pass's ring
     * collapse dropped every graw-≤-0 ring — also one carrying AP / crit (a cell read 0 against a real build wearing it) —
     * and no pass listed an EPIC / RELIC item carrying no stat the scenario reads, although it is the carrier an epic /
     * relic sub needs (−15 % on the repro).
     * 53: the domination pre-filter's contract ([dominationShape], `docs/perf-review-backlog.md` §E) — the certificates
     * read the SAME reduced pool as the search, and that pool changes: an EPIC / RELIC item is only dominated by another
     * one while an epic / relic sub is modelled (a non-epic item used to evict the only carrier of Mesure III: the search
     * and the certificate both settled 16.7 % below the optimum with a ProvenOptimal badge — the PR #222 review repro); a
     * ring only when its dominators span two names (the same-name rule); a rune carrier only by an item whose level caps
     * its runes at least as high (EXACTLY as high, with equal sockets under the max-damage one-type-per-item model, when a
     * modelled rune type is a capped stat). Pools only grow, so every cached bound computed on a v52 pool is stale. Plus
     * a latent bail of the target-aware RANGE row: a RANGE_AT_LEAST sub whose own +range line is permanent (no shipped sub).
     * 54: max-damage rune-choice collapse books its best M-feeding rune under the rune's OWN characteristic,
     * preserving the equip-var substitution and the crit swap's suppression. Elemental runes no longer pay
     * the Neutralité family's secondary-mastery budget. The AP-cell mirror accepts those actual keys and
     * splits the crit option using the actual default; world N now reads an elemental default as E instead
     * of D, including its suppression delta. Old bounds could under-count the corrected model, so invalidate
     * every cached cell. CI locks the free/general-fold Neutralité repro and the elemental-default crit swap.
     * Carriers with a secondary default read by a cap also retain explicit rune picks: a smaller elemental
     * choice can free secondary budget for skills (the signed-rear helmet repro). The mirror handles those
     * picks beside the remaining collapsed defaults, without dropping equip-var aliases from item terms.
     * Those picks are the carrier's Pareto set over every read of the model ([MaxDamageRuneReads]): the cap was then
     * read as the SUM of all six secondaries (crit included) at weight 1 — wrong, see 56 — so equal-valued distance /
     * rear / crit runes cost the same budget and one represented them; a choice only a choosable cap keeps is gated on those subs
     * ([RuneModel.choiceGates]). Both cut dominated builds only, so the mirror reads the same (or a smaller) pick set
     * and the optimum it bounds is unchanged.
     * 55: the POOL DATA changes, not the certifier. The Dofus Pourpre's
     * "100% of the level as Elemental Mastery" (action 999, which the equipments extractor used to drop) is now
     * [me.chosante.common.Equipment.percentOfLevel], resolved into the item's stats when the request's pool is built
     * ([me.chosante.common.Equipment.atLevel]): +170 to +245 elemental mastery on every pool from level 170, and domination
     * keeps the item where Dofushu used to evict it (level ≥ 230). Both certificates read that pool, so every cached bound
     * computed on the old one (memory or disk, keyed by this version and the unchanged data version) is stale.
     * 56: the Neutralité family's `SECONDARY_MASTERIES_AT_MOST` holds EACH secondary mastery ≤ t on its own — the
     * game's criterion is an `and` of six per-stat atoms (State 67 → StaticEffect 68) — not their SUM, which let a
     * positive mastery be offset by a negative one (the reported Xelor build: distance +76 and crit +240 against rear
     * −304 and berserk −12 credited Neutralité III, Ambition III and Inflexibilité II). The model and the re-scorers now
     * read it per stat, and the certificates' inputs change with it: the max-damage rune choice collapse reads one bound
     * per secondary ([MaxDamageRuneReads]), so equal-valued distance / rear / crit runes are distinct picks again and the
     * AP-cell mirror reads that larger pick set. Every certificate read of the condition was a SUM budget — a RELAXATION
     * of the per-stat rule (each ≤ t ⇒ any k of them sum to ≤ k·t), sound as it stands for t ≤ 0 and now read at
     * [secondaryMasteriesSumBound] (6·t for t ≥ 0, t below — the raw t is stricter than the rule for t > 0; equal for
     * the shipped t = 0): world N's Lagrangian (S ≤ 0), the soft certificate's secZero arm, the MM world-B knapsack. One
     * cheap per-stat tightening: the MM world-B M-cap is also bounded by Σ over the requested masteries of (t + what
     * lands outside the first-turn read). The true optimum can only fall (the rule is stricter), so old bounds were
     * sound but may be loose — and the pick set changed: invalidate every cached cell.
     */
    const val CERTIFIER_VERSION: Int = 56

    // Min wall-clock gap between intermediate best-so-far emissions. Each emission re-runs the heavy
    // solutionToBuild + scoreFor (a knapsack rotation in max-damage) ON the native solve thread, stealing
    // cycles from search/proof. Intermediate snapshots are pure progress — re-rendering the in-flight build
    // more than ~twice a second has no UX value — so coalescing to one per 500ms returns those cycles to the
    // solver without affecting the result: the FINAL build is recomputed unconditionally after solve() and
    // delivered via a guaranteed (suspending) send, so throttling intermediates can never drop or reorder it.
    private const val INTERMEDIATE_EMIT_THROTTLE_MS = 500L

    // E8 fallback (see [dpConstructProvenOptimum]): deterministic-time budget for the full-pool
    // feasibility re-solve (`rawScore ≥ bound`, stop at first solution). It only runs when the restricted
    // fast path misses the bound — before the fallback existed those shapes produced NO construction at
    // all — so a generous budget trades bounded extra latency (async, badge-only path) for reliability.
    private const val E8_FALLBACK_DETERMINISTIC_BUDGET = 300.0

    // ...and its WALL-CLOCK cap (model build included). The det budget alone is no bound on a user's wait: it
    // measured ~265 s of single-thread CPU when it FAILS (2026-10 perf pass, probe P4, 4-core profile, level 110: fast
    // tier short, fallback exhausted → null after 269 s) — and the GUI keeps its "proven within X%" badge up with a "still
    // proving" cue for the whole attempt. Every measured construct SUCCESS comes from the fast
    // tier (level 245: 2.7 s), so the fallback only has to cover the "bound reachable, but not by the provenance items"
    // shape, which is a first-solution feasibility search; a minute is generous for that and bounds a futile attempt
    // to ~1/4 of the former wait. On expiry the rescue gives up (null) and the caller keeps the incumbent — sound.
    internal const val E8_FALLBACK_WALL_CAP_SECONDS = 60.0

    // FAST tier-1 certifier (P2): crit-grid step for the per-segment 3-D passes. Each segment folds point
    // graw at its top crit, so the fold looseness on the critM slice is bounded by ~step/c — smaller = tighter
    // but more segments (linear cost). Tune against the 110/245 fast-vs-exact ratios.

    // Survivability soft-floor (Lot 5, opt-in). The effective-HP proxy EHP ≈ HP·(100+avgResist)/100 is
    // bucketed against the floor and feeds a GENTLE power-2 penalty (vs the power-6 used for hard AP/MP
    // targets) so missing the floor only *nudges* the damage objective — never dominates it. Resistance
    // is averaged over the 4 elements and capped at EHP_AVG_RESIST_CAP (Wakfu's soft resist ceiling), so
    // one extreme element can't inflate the proxy. EHP_MAX bounds the proxy's CP-SAT domain (HP·1.8); it
    // is far above any real build.
    private const val SURVIVABILITY_PENALTY_POWER = 2

    // Max gentle-penalty multiplier; the EHP penalty rescales by this then divides back out, so meeting
    // the floor is a no-op and missing it scales the damage down by at most (max/atFloor) — a soft tax.
    private const val MAX_SURVIVABILITY_MULTIPLIER = 1_000L

    // Lexicographic scale for the "most masteries" overshoot tie-breaker. The primary objective tops
    // out at MASTERY_SCORE_ABS_MAX * MAX_PENALTY_MULTIPLIER = 1e14; multiplying it by this scale and
    // adding a bonus in [0, OVERSHOOT_SCALE) keeps the combined objective (~1e18) well under
    // Long.MAX/2 (~4.6e18) while guaranteeing one unit of primary always beats any overshoot bonus.
    // See [withOvershootTieBreaker].
    // Internal (not private) so the §8.2 S-A outer driver can fold interval bounds in the same units.
    internal const val OVERSHOOT_SCALE = 10_000L

    internal val NON_ELEMENTARY_MASTERIES =
        listOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_HEALING,
            Characteristic.MASTERY_MELEE
        )

    internal val NEGATIVE_MASTERY_PENALTY =
        listOf(
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_BERSERK
        )

    private val RANDOM_MASTERY_COUNTS =
        mapOf(
            Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 1,
            Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 2,
            Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 3
        )

    init {
        OrToolsNativeLoader.load()
    }

    private val warmedUp =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * Pays OR-Tools' one-time cold-start cost up front, off any search's critical path. The very
     * first real search is otherwise slow because touching this object loads the native library
     * (`init` above), the CP-SAT Java types are class-loaded on first use, and the solver spins up
     * its worker-thread pool / engine state on the first `solve`. We trigger all of that here on a
     * throwaway model so later searches start warm; subsequent searches are already fast because
     * none of this is repeated. Idempotent and safe to call from any thread (e.g. during app
     * startup, concurrently with other warm-up work).
     */
    fun warmUp() {
        if (!warmedUp.compareAndSet(false, true)) return
        // Referencing this object already ran `init { OrToolsNativeLoader.load() }`.
        val model = CpModel()
        val a = model.newBoolVar("warmup_a")
        val b = model.newBoolVar("warmup_b")
        model.addLessOrEqual(LinearExpr.sum(arrayOf<IntVar>(a, b)), 1L)
        model.maximize(
            LinearExpr
                .newBuilder()
                .addTerm(a, 1L)
                .addTerm(b, 1L)
                .build()
        )
        val solver = CpSolver()
        solver.parameters.maxTimeInSeconds = 1.0
        solver.parameters.logSearchProgress = false
        // Deliberately NOT the full core count. What warm-up actually pays off is the native-library
        // load, JNI/class initialization and the first solve's code paths — none of which need many
        // workers (CpSolver spins its worker pool up per solve, so nothing about a big pool persists
        // anyway). Saturating every core here starved the GUI's AWT event thread during startup: on
        // macOS any window operation (zoom, raise, resize) then stalled until warm-up finished and
        // the whole app appeared frozen. Two workers still exercise the multi-worker portfolio path
        // while leaving the UI thread (and the OS) breathing room.
        solver.parameters.numSearchWorkers = 2
        solver.solve(model)
    }

    /**
     * Fixed-point version of [TargetStats.weight] so sub-unit weights survive integer arithmetic — the one definition
     * ([fixedPointWeight], × [TARGET_WEIGHT_SCALE]) the scorers' per-element-row objective ([ElementRowObjective]) reads too.
     */
    internal fun TargetStats.scaledWeight(targetStat: TargetStat): Long = fixedPointWeight(targetStat)

    // The canonical element order of every multi-element fold — shared with the scorers through [ElementFamily].
    internal val ELEMENTARY_MASTERIES = ElementFamily.MASTERY.elements

    internal val ELEMENTARY_RESISTANCES = ElementFamily.RESISTANCE.elements

    // Upper bound for the "exceed the target once everything is met" tie-breaker. Far above any
    // realistic scaled overflow, so the clamp never triggers in practice while keeping the
    // lexicographic objective (hit targets first, then maximise overflow) inside Long range.

    internal val RANDOM_RESISTANCES =
        listOf(
            Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT,
            Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT,
            Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT
        )

    // Per-element random lines paired with how many distinct elements each rolls onto. Used to fold
    // random masteries/resistances into specific elements exactly as the scorers do.
    internal val MASTERY_RANDOM_BY_COUNT = ElementFamily.MASTERY.randomByCount

    internal val RESISTANCE_RANDOM_BY_COUNT = ElementFamily.RESISTANCE.randomByCount

    /**
     * The prefilter (a top-N-per-stat plus top-N-by-combined-mastery HEURISTIC that trades global optimality for
     * tractability) is needed only when a single elemental fold has **more than one** wanted element: that is exactly the case where
     * [applyGreedyRandom] mints the per-item × per-element assignment booleans + O(elements²) ordering
     * constraints that explode the full late-game pool. A SINGLE specific element (the common request, e.g.
     * "fire mastery") takes the cheap `effectiveCount == elementCount` random branch with no assignment vars,
     * so the full pool stays small and proves the true global optimum fast (measured: ≤3.5 s on the full
     * level-245 pool — see PrefilterBenchmark). Keeping the heuristic there only pruned the optimum for no
     * tractability gain, so we now solve those on the full pool. Multi-element / aggregate requests still
     * prefilter (the explosion is real for them); pool dominance-pruning to drop it there too is future work.
     */
    internal fun needsItemPrefilter(targetStats: TargetStats): Boolean = targetStats.needsItemPrefilter

    /**
     * Restricts each slot to the items that can plausibly matter for the requested stats. The full
     * pool produces a CP-SAT model with tens of thousands of booleans that presolve cannot reduce in
     * time; keeping only the strongest items per requested characteristic (plus forced items) shrinks
     * the model dramatically, so presolve stays fast and the search reaches strong solutions.
     *
     * Per slot it keeps the forced items, then the top [topPerCharacteristic] items of every relevant
     * characteristic, then the top [topPerCharacteristic] by [combinedMasteryScore]. Two rankings read that
     * score, because a well-rounded item (395 random-element + 395 distance mastery) is the best on no
     * single stat yet is what a multi-element optimum wears:
     *  - it breaks ties on a characteristic's own value. AP / MP / range / crit are small integers, so the
     *    cut falls inside a tie group of a dozen items and, ranked by value alone, pool order decided who
     *    survived; a stable sort keeps pool order for what is still tied, so the result stays deterministic;
     *  - it is a ranking of its own, so an item that ranks 9th on every stat still gets in on its overall
     *    mastery.
     * Still a heuristic: a build that needs an item ranked low on all of those is lost, so a prefiltered
     * request never earns an optimality badge (see [needsItemPrefilter]).
     */
    private fun prefilterRelevantEquipments(
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        params: WakfuBestBuildParams,
        topPerCharacteristic: Int = 8,
    ): Map<ItemType, List<Equipment>> {
        val relevant = relevantCharacteristics(params.targetStats)
        if (relevant.isEmpty()) return equipmentsByItemType
        val forced = params.forcedItems.map { it.lowercase() }.toSet()
        val wantedElements = params.targetStats.masteryElementsToMinimize
        val wantedNonElemental =
            params.targetStats
                .map { it.characteristic }
                .filter { it in NON_ELEMENTARY_MASTERIES }
                .distinct()

        return equipmentsByItemType.mapValues { (_, items) ->
            val keep = LinkedHashSet<Equipment>()
            items.filter { it.name.fr.lowercase() in forced }.forEach { keep.add(it) }
            // Scored once per slot: the rankings below read it for every characteristic.
            val scored = items.map { it to combinedMasteryScore(it, wantedElements, wantedNonElemental) }
            for (characteristic in relevant) {
                scored
                    .asSequence()
                    .filter { (item, _) -> item.valueFor(characteristic) > 0 }
                    .sortedWith(
                        compareByDescending<Pair<Equipment, Int>> { (item, _) -> item.valueFor(characteristic) }
                            .thenByDescending { (_, combined) -> combined }
                    ).take(topPerCharacteristic)
                    .forEach { (item, _) -> keep.add(item) }
            }
            scored
                .asSequence()
                .filter { (_, combined) -> combined > 0 }
                .sortedByDescending { (_, combined) -> combined }
                .take(topPerCharacteristic)
                .forEach { (item, _) -> keep.add(item) }
            if (keep.isEmpty()) items else keep.toList()
        }
    }

    /**
     * How much of the requested mastery an item can give across ALL the wanted elements at once — the prefilter's
     * answer to the item that is the best on no single stat. The most-masteries objective takes the minimum
     * over the [wantedElements], so what an item is worth is its share on every element, not its peak on one:
     * the sum over the wanted elements of the specific mastery it carries, plus the generic
     * [Characteristic.MASTERY_ELEMENTARY] once per element, plus each random-element line (rolled on k elements)
     * on `min(k, elements)` of them, plus every requested non-elemental mastery ([wantedNonElemental], e.g.
     * distance) once per element — an upper bound of its share of that minimum. Zero when no elemental mastery
     * is wanted (a resistance-only request ranks as before). Pure and integer, so equal inputs rank equally.
     */
    private fun combinedMasteryScore(
        equipment: Equipment,
        wantedElements: List<Characteristic>,
        wantedNonElemental: List<Characteristic>,
    ): Int {
        val elements = wantedElements.size
        if (elements == 0) return 0
        var total = elements * equipment.valueFor(Characteristic.MASTERY_ELEMENTARY)
        for (element in wantedElements) total += equipment.valueFor(element)
        for ((randomLine, rolledOn) in MASTERY_RANDOM_BY_COUNT) total += min(rolledOn, elements) * equipment.valueFor(randomLine)
        for (mastery in wantedNonElemental) total += elements * equipment.valueFor(mastery)
        return total
    }

    private fun relevantCharacteristics(targetStats: TargetStats): Set<Characteristic> {
        val result = mutableSetOf<Characteristic>()
        for (targetStat in targetStats) {
            val characteristic = targetStat.characteristic
            result.add(characteristic)
            when (characteristic) {
                // Aggregate request: every element is wanted, fed by specific + generic + random.
                Characteristic.MASTERY_ELEMENTARY -> {
                    result.addAll(ELEMENTARY_MASTERIES)
                    result.addAll(RANDOM_MASTERY_COUNTS.keys)
                }
                // Specific element (e.g. fire): the generic "+all elements" stat and random masteries
                // also feed it, so keep those items — but not the sibling elements, which do not.
                in ELEMENTARY_MASTERIES -> {
                    result.add(Characteristic.MASTERY_ELEMENTARY)
                    result.addAll(RANDOM_MASTERY_COUNTS.keys)
                }

                Characteristic.RESISTANCE_ELEMENTARY -> {
                    result.addAll(ELEMENTARY_RESISTANCES)
                    result.addAll(RANDOM_RESISTANCES)
                }

                in ELEMENTARY_RESISTANCES -> {
                    result.add(Characteristic.RESISTANCE_ELEMENTARY)
                    result.addAll(RANDOM_RESISTANCES)
                }

                else -> Unit
            }
        }
        return result
    }

    /**
     * Test-only knobs to make a solve bit-for-bit reproducible. Production never passes this — the
     * default `null` keeps the real search (wall-clock budget, a worker per core, no fixed seed),
     * which is fast but intentionally non-deterministic. A test that drives that real search can stop
     * at a *sub-optimal feasible* build on a slow/loaded CI runner (the AP/MP/range/crit targets are
     * objective penalties, not hard constraints, so a poor feasible solution can violate them). With
     * a tuning, CP-SAT instead runs a fixed worker count + fixed seed + a **deterministic-time**
     * budget — work-unit based, not wall-clock — so it returns the *same proven optimum* on any
     * machine, however slow or loaded.
     */
    internal data class SolverTuning(
        val numSearchWorkers: Int = 8,
        val randomSeed: Int = 1,
        val maxDeterministicTime: Double = 60.0,
        val maxDamageExperiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig(),
        // D3: `interleaveSearch` makes a 1-worker solve fully machine-reproducible (the canonical
        // deterministic protocol: 1 worker + interleave + fixed seed). Multi-worker results — even with a
        // det-time budget and a fixed seed — are worker-RACE-dependent, so a proof-by-deadline assert on
        // them flakes on oversubscribed CI. Default false keeps every existing tuning byte-identical.
        val interleaveSearch: Boolean = false,
        // C8(2) A/B seams: override the presolve-iteration cap / linearization level on the STANDARD solve
        // path (production pins presolve=1 + linearization=1 for the non-max-damage modes; the A/B measures
        // whether max-damage's 3/2 combination also wins for most-masteries). Null = today's behavior.
        val maxPresolveIterationsOverride: Int? = null,
        val linearizationLevelOverride: Int? = null,
        // Profiling seam: override the "domination only when tuning == null" production coupling, so a
        // deterministic (tuned) solve can measure the PRODUCTION pool (domination ON) — the C8(2)-era tuned
        // runs silently measured the full pool. Null = today's behavior (tuned ⇒ full pool).
        val applyDominationOverride: Boolean? = null,
        // C8(3) A/B seam: enable the most-masteries greedy warm start (instant emission + CP-SAT hint,
        // see [MostMasteriesWarmStart]) on the tuned path. Default false keeps every existing
        // deterministic test byte-identical; production follows its own gate in [optimize].
        val greedyWarmStart: Boolean = false,
        // Stop the search at the FIRST solution instead of running the budget out; the final emission delivers
        // the stopped-at solution. Two uses: the E8 fallback, with [optimize]'s `maxDamageRawFloor` — there ANY
        // feasible solution already sits at the certificate bound (the floor is a sound per-cell upper bound), so
        // proving optimality on top is pure waste; and tests that need the WEAKEST incumbent a search can hand
        // over — with 1 worker + interleave it is the same on every machine, however long the model takes to
        // reach it (a fixed det budget can end before the first solution once the model grows).
        val stopAtFirstSolution: Boolean = false,
        // P0.5 diagnostics (manual harnesses only — never production, see docs/MOST_MASTERIES_PERF_PLAN.md):
        // receive CP-SAT's own search log lines (dual-bound trajectory + per-subsolver attribution) —
        // the standard solve path otherwise hardcodes the log off. A Java-side callback, NOT stdout:
        // the native logger writes to the process's real fd 1, which bypasses the test JVM's capture.
        val searchLogSink: ((String) -> Unit)? = null,
        // Capture the final solution's full decision assignment (equipment/skill/rune/sublimation vars),
        // keyed by var name, after the solve completes. Same params + tuning ⇒ the same model ⇒ the same
        // names, so a captured map can be hinted back onto a fresh solve.
        val captureAssignment: ((Map<String, Long>) -> Unit)? = null,
        // The oracle hint: a previously captured assignment, applied by var name onto the fresh model.
        // Use with greedyWarmStart = false — a var must not be hinted twice.
        val assignmentHint: Map<String, Long>? = null,
        // Manual most-masteries performance campaign. CURRENT keeps the production encoding byte-identical;
        // alternatives are exercised only by the dedicated same-JVM A/B harness.
        val mmOvershootEncoding: MmOvershootEncoding = MmOvershootEncoding.CURRENT,
        val mmProductEncoding: MmProductEncoding = MmProductEncoding.CURRENT,
        // Measurement-only redundant DUAL cut: a sound upper bound on the most-masteries core
        // (`masteryScore`, scorer units), added as `masteryScore ≤ U`. The caller is responsible for
        // soundness — an under-estimating U silently truncates the optimum, so the A/B harness locks
        // optimum equality against the un-cut baseline. Production always passes null.
        val mmMasteryScoreUpperBound: Long? = null,
        // §8.2 S-A seam (test-only, most-masteries SOFT model): constrain the penalty bucket to this
        // interval and REMOVE the penalty product from the searched model. Non-singleton (or
        // [mmPenaltyBucketFoldedObjective] false): the objective is the bare core (mastery×DI) — the
        // outer branch-and-bound driver combines its proven bound with the power table into a sound
        // interval bound. Singleton + folded: the multiplier is a constant, so the exact penalized
        // objective (incl. the overshoot tie-break) is linear — no product equality remains.
        val mmPenaltyBucketInterval: IntRange? = null,
        val mmPenaltyBucketFoldedObjective: Boolean = false,
        // §8.2: reports the penalty geometry the driver needs for its bound math, at model build:
        // (maxIndex, power-table values, totalExpectedScore).
        val mmPenaltyGeometryProbe: ((Int, LongArray, Long) -> Unit)? = null,
        // §8.2: after a bucket-interval sub-solve with a solution, reports the solution's (core, bucket)
        // so the driver folds an exact incumbent (`core × power6(bucket) × OVERSHOOT_SCALE`, bonus ≥ 0
        // dropped — still a valid achievable lower bound) without duplicating scorer arithmetic.
        // Shared by §8.4 S-C, where the reported pair is (non-negative tier M, DI factor) instead.
        val mmPenaltyBucketSolutionCapture: ((Long, Long) -> Unit)? = null,
        // §8.4 S-C seam (test-only, mono-element most-masteries): constrain the DI FACTOR (100+DI,
        // clamped) to this interval and remove the mastery×DI product from the searched model — see
        // [StatBuilder.diAdjustedPerElementMasteryScore]. Same outer-driver contract as the bucket
        // interval; the two axes are never set together (a composed tree is a later, gated step).
        val mmDiFactorInterval: IntRange? = null,
        val mmDiFactorFoldedObjective: Boolean = false,
        // §8.5 S-D seams (test-only). Hard leg: required targets behind ASSUMPTION literals — weaker
        // propagation than the plain constraints, but a proven INFEASIBLE yields a sufficient core,
        // reported via the capture. Soft leg: the recycled no-good cut built from such a core
        // (`sum(met_i for i in core) ≤ |core|−1`, logically implied — the optimum is unchanged).
        val mmHardTargetsAsAssumptions: Boolean = false,
        val mmInfeasibilityCoreCapture: ((Set<Characteristic>) -> Unit)? = null,
        val mmSoftNoGoodCore: Set<Characteristic>? = null,
        // Relax-then-check ([relaxThenCheck]) on the TUNED path: production always solves a most-masteries request with floors
        // that way; a deterministic solve opts in here (its deterministic time split like the wall budget), so every existing
        // tuned test keeps the direct floored solve.
        val relaxFloorsFirst: Boolean = false,
    )

    fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
    ): Flow<SolverResult<BuildCombination>> = optimize(params, equipmentsByItemType, runes, sublimations, tuning = null)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning?,
    ): Flow<SolverResult<BuildCombination>> = optimize(params, equipmentsByItemType, emptyList(), emptyList(), tuning)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        tuning: SolverTuning?,
    ): Flow<SolverResult<BuildCombination>> = optimize(params, equipmentsByItemType, runes, emptyList(), tuning)

    internal fun optimize(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        tuning: SolverTuning?,
        // Max-damage hard-constraints-first pass: enforce the required targets as HARD `actual ≥ target`
        // constraints under a plain damage objective. INFEASIBLE (unreachable targets) ⇒ the flow emits nothing;
        // the caller ([MaxDamageSearch.optimizeHardThenSoft]) then re-runs with this false (the soft penalty).
        // Default false keeps every existing caller (all deterministic tests) byte-identical.
        hardConstraints: Boolean = false,
        // E8 fallback (max-damage only): a HARD `rawScore ≥ floor` constraint. With the floor set to a
        // certificate cell bound (a sound UPPER bound at that pinned AP cell), the solve becomes a
        // FEASIBILITY search: any solution it finds already reaches the bound — combine with
        // [SolverTuning.stopAtFirstSolution]. An unreachable floor (a loose bound) yields INFEASIBLE ⇒
        // the flow emits nothing, which the E8 caller maps to null (sound: no badge, never a wrong one).
        maxDamageRawFloor: Long? = null,
        // C8(3), max-damage: enable the greedy warm start (instant first emission + CP-SAT hint). Set ONLY
        // by [MaxDamageSearch]'s SINGLE-ELEMENT path — on the boss/multi-element probes the composed proof
        // still rides CP-SAT's in-model OPTIMAL, where the historical measurement showed a hint can slow it.
        maxDamageGreedyWarmStart: Boolean = false,
        // P2b (most-masteries hard leg only): two-stage lexicographic — stage 1 proves the PRIMARY alone
        // (no ×10 000 overshoot fold on the objective), stage 2 re-solves with the primary pinned and the
        // overshoot as the objective (short, near-forced) and delivers the final build. Exactly the same
        // lexicographic optimum as the folded objective; A/B seam before it becomes the default.
        mmTwoStageOvershoot: Boolean = false,
        // P2a fallback semantics: the REAL termination status of the (stage-1) solve, so the caller can
        // distinguish proven-INFEASIBLE (targets unreachable) from an UNKNOWN timeout without a solution —
        // the flow alone cannot (both emit nothing). Called once, before the flow closes; null = solver crash.
        onTermination: ((SolveOutcome?) -> Unit)? = null,
    ): Flow<SolverResult<BuildCombination>> =
        callbackFlow {
            // The native solve blocks its worker thread and cannot be interrupted by coroutine cancellation, so
            // hold the [CpSolver] here and stop it from [awaitClose] on flow teardown. The in-callback stop
            // (onSolutionCallback → stopSearch) only fires for models that reach a solution; an INFEASIBLE solve
            // never does, and without this would keep its native workers pinned until maxTimeInSeconds after the
            // collector is gone (the max-damage hard leg's infeasible case). CpSolver.stopSearch() is synchronized
            // (thread-safe), so calling it from the teardown thread is safe.
            val solverHandle =
                java.util.concurrent.atomic
                    .AtomicReference<CpSolver?>()
            val job =
                launch(Dispatchers.IO) {
                    // The leg's start: relax-then-check runs its two stages against ONE deadline from here.
                    val legStartMs = System.currentTimeMillis()
                    // C8(3) greedy warm start — computed BEFORE buildModel (which is ~seconds on the lvl-245
                    // max-damage shape and used to gate the first emission at ~6.4 s): the greedy needs only
                    // the raw pre-filtered pool, so the first build streams in ~0.3 s. The CP-SAT hint is
                    // applied after the model exists; a pick domination later removed simply is not hinted
                    // (hints are advisory). Optimality-neutral both ways. Gated to production (tuning == null)
                    // or the explicit A/B flag; max-damage additionally requires the caller's single-element
                    // opt-in (see [maxDamageGreedyWarmStart]).
                    val greedyEnabled = tuning?.greedyWarmStart ?: true
                    val warmStart =
                        when {
                            !greedyEnabled -> null
                            params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                                MostMasteriesWarmStart.greedyBuild(params, equipmentsByItemType.values.flatten())
                            params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE && maxDamageGreedyWarmStart ->
                                MostMasteriesWarmStart.greedyMaxDamageBuild(params, equipmentsByItemType.values.flatten())
                            else -> null
                        }
                    val warmScore =
                        warmStart?.let { combination ->
                            val score = scoreFor(params, combination)
                            // Marked as the greedy emission: it precedes the solve, so orchestrators must
                            // not read it as model feasibility or solver-verified provenance.
                            trySend(SolverResult(combination, score, 0, greedyWarmStartEmission = true))
                            score
                        }
                    // Domination runs only on the production (wall-clock) path: tuning == null. The deterministic
                    // test path keeps the full pool so existing tests are untouched; the soundness lock toggles it.
                    val mmTwoStage =
                        mmTwoStageOvershoot &&
                            hardConstraints &&
                            params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
                            params.targetStats.any { it.characteristic.isRequiredMostMasteriesTarget() }
                    // Backup certificate (§8.9bis): the emitted objective is certificate-comparable on
                    // the MM SOFT leg only (penalized units = the certificate's foldedBound units). NOT on
                    // the hard leg with required targets: its objective is the bare `core × 10⁴ + bonus`,
                    // while the soft objective multiplies the core by power6(bucket) — ≈1e6 even when every
                    // target is met — so comparing the two awarded "proven within ~1 000 000 %" badges
                    // (pre-release review 2026-10-01, reproduced on dist+AP+MP+HP at level 200). The hard
                    // leg is CONVERTED instead ([mmHardLegMultiplier] below), never stamped raw.
                    val mmObjectiveComparable =
                        params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
                            // (the model's exact fold predicate — a 0-valued required target still folds the objective)
                            (!hardConstraints || params.targetStats.none { it.characteristic.isRequiredMostMasteriesTarget() }) &&
                            // The measurement seams replace the searched objective — never comparable.
                            tuning?.mmPenaltyBucketInterval == null &&
                            tuning?.mmDiFactorInterval == null
                    // ...but the hard leg IS convertible: every emission meets the targets, so the same
                    // build's soft objective is `core × fullTargetsMultiplier × SCALE + bonus`. Not for the
                    // P2b two-stage solve (its stage 1 searches the bare primary, no overshoot bonus).
                    val mmHardLegMultiplier =
                        if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
                            hardConstraints &&
                            !mmTwoStage &&
                            tuning?.mmPenaltyBucketInterval == null &&
                            tuning?.mmDiFactorInterval == null
                        ) {
                            MostMasteriesCertificate.fullTargetsMultiplier(params)
                        } else {
                            null
                        }
                    // A most-masteries request with FLOORS: the relaxed model first, then the floored one ([relaxThenCheck]).
                    if (relaxesFloorsFirst(params, tuning, mmTwoStage, maxDamageRawFloor)) {
                        val outcome =
                            relaxThenCheck(
                                this@callbackFlow,
                                params,
                                equipmentsByItemType,
                                runes,
                                sublimations,
                                tuning,
                                hardConstraints,
                                warmStart,
                                warmScore,
                                legStartMs,
                                solverHandle,
                                mmObjectiveComparable,
                                mmHardLegMultiplier
                            )
                        onTermination?.invoke(outcome)
                        close()
                        return@launch
                    }
                    val built =
                        buildModel(
                            params,
                            equipmentsByItemType,
                            runes,
                            sublimations,
                            applyDomination = tuning?.applyDominationOverride ?: (tuning == null),
                            maxDamageExperiment = tuning?.maxDamageExperiment ?: MaxDamageExperimentConfig.DEFAULT,
                            hardConstraints = hardConstraints,
                            mmPlainPrimaryObjective = mmTwoStage,
                            mmOvershootEncoding = tuning?.mmOvershootEncoding ?: MmOvershootEncoding.CURRENT,
                            mmProductEncoding = tuning?.mmProductEncoding ?: MmProductEncoding.CURRENT,
                            mmMasteryScoreUpperBound = tuning?.mmMasteryScoreUpperBound,
                            mmPenaltyBucketInterval = tuning?.mmPenaltyBucketInterval,
                            mmPenaltyBucketFoldedObjective = tuning?.mmPenaltyBucketFoldedObjective ?: false,
                            mmPenaltyGeometryProbe = tuning?.mmPenaltyGeometryProbe,
                            mmDiFactorInterval = tuning?.mmDiFactorInterval,
                            mmDiFactorFoldedObjective = tuning?.mmDiFactorFoldedObjective ?: false,
                            mmHardTargetsAsAssumptions = tuning?.mmHardTargetsAsAssumptions ?: false,
                            mmSoftNoGoodCore = tuning?.mmSoftNoGoodCore
                        )
                    // C2: a hard-constraints model with a required target above its reachable ceiling is PROVABLY
                    // infeasible — skip the doomed CP-SAT solve entirely and emit nothing. The caller
                    // ([MaxDamageSearch.optimizeHardThenSoft]) already treats an empty hard leg as its fallback
                    // trigger, so the soft (penalized) leg runs exactly as it would after a CP-SAT INFEASIBLE — only
                    // without paying the full-budget native solve first.
                    if (built.maxDamageStaticallyInfeasible) {
                        close()
                        return@launch
                    }
                    // The model build above is blocking and cannot be cancelled mid-way. A flow torn down meanwhile
                    // (the E8 construct's wall cap / a superseded proof) found no solver to stop in `awaitClose` and
                    // has no consumer left — never start the native solve for it, or it would run out its whole budget.
                    if (!isActive) return@launch
                    // E8 fallback floor — see the parameter doc. rawScore is always populated in max-damage
                    // mode; a null (another mode) simply ignores the floor, and E8 never calls those modes.
                    if (maxDamageRawFloor != null) {
                        built.maxDamageRawScore?.let { built.model.addGreaterOrEqual(it, maxDamageRawFloor) }
                    }
                    // C8(3): the warm start streamed above; hint the equipment layer now the model exists,
                    // so the search starts from that incumbent instead of near zero. The greedy score also
                    // becomes the intermediate-emission floor — consumers keep the LAST emission, so a later,
                    // WORSE snapshot would visibly regress the displayed build.
                    warmStart?.let { combination ->
                        val picked = combination.equipments.toHashSet()
                        for ((equip, v) in built.equipVars) built.model.addHint(v, if (equip in picked) 1L else 0L)
                    }
                    // P0.5 oracle seam: hint a previously captured full assignment by var name (advisory,
                    // optimality-neutral; requires greedyWarmStart = false so no var is hinted twice).
                    tuning?.assignmentHint?.let { hint ->
                        for (v in diagnosticVars(built)) hint[v.name]?.let { built.model.addHint(v, it) }
                    }
                    val outcome =
                        executeSolverAndEmitResults(
                            built.model,
                            params,
                            built.allEquips,
                            built.equipVars,
                            built.skillVars,
                            built.runeModel,
                            built.subModel,
                            built.maxDamageRawScore,
                            this@callbackFlow,
                            tuning,
                            onSolverReady = { solverHandle.set(it) },
                            suppressBelowScore = warmScore,
                            mmObjectiveComparable = mmObjectiveComparable,
                            mmHardLegMultiplier = mmHardLegMultiplier
                        )
                    // P2b stage 2: the primary is proven — pin it and maximize the overshoot in a short
                    // near-forced solve; its guaranteed final send (same primary ⇒ same score) replaces
                    // stage 1's overshoot-less build. A stage-2 miss (timeout/crash) keeps stage 1's
                    // final emission — a correct-primary build with unspent overshoot, never nothing.
                    if (mmTwoStage &&
                        outcome?.objectiveValue != null &&
                        (
                            outcome.status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                                outcome.status == com.google.ortools.sat.CpSolverStatus.FEASIBLE
                        )
                    ) {
                        val built2 =
                            buildModel(
                                params,
                                equipmentsByItemType,
                                runes,
                                sublimations,
                                applyDomination = tuning?.applyDominationOverride ?: (tuning == null),
                                maxDamageExperiment = tuning?.maxDamageExperiment ?: MaxDamageExperimentConfig.DEFAULT,
                                hardConstraints = hardConstraints,
                                mmOvershootPinnedPrimary = outcome.objectiveValue,
                                mmOvershootEncoding = tuning?.mmOvershootEncoding ?: MmOvershootEncoding.CURRENT,
                                mmProductEncoding = tuning?.mmProductEncoding ?: MmProductEncoding.CURRENT,
                                mmMasteryScoreUpperBound = tuning?.mmMasteryScoreUpperBound
                            )
                        executeSolverAndEmitResults(
                            built2.model,
                            params,
                            built2.allEquips,
                            built2.equipVars,
                            built2.skillVars,
                            built2.runeModel,
                            built2.subModel,
                            built2.maxDamageRawScore,
                            this@callbackFlow,
                            tuning,
                            onSolverReady = { solverHandle.set(it) },
                            suppressBelowScore = warmScore,
                            finalIsOptimalOverride = outcome.status == com.google.ortools.sat.CpSolverStatus.OPTIMAL,
                            maxWallSecondsOverride = 30.0,
                            mmObjectiveOverride = if (mmObjectiveComparable) outcome.objectiveValue else null
                        )
                    }
                    onTermination?.invoke(outcome)
                    // P0.5 capture seam: read the final solution's full assignment off the finished solver
                    // (CpSolver retains the last response). Best-effort — an emission-less solve (INFEASIBLE)
                    // simply skips the capture.
                    tuning?.captureAssignment?.let { capture ->
                        solverHandle.get()?.let { solver ->
                            runCatching { diagnosticVars(built).associate { it.name to solver.value(it) } }
                                .onSuccess(capture)
                        }
                    }
                    // §8.5 S-D: on a proven-INFEASIBLE assumption-gated hard leg, extract the sufficient
                    // assumption core and report the target characteristics it names.
                    tuning?.mmInfeasibilityCoreCapture?.let { capture ->
                        val literals = built.mmAssumptionLiterals
                        val solver = solverHandle.get()
                        if (literals != null &&
                            solver != null &&
                            outcome?.status == com.google.ortools.sat.CpSolverStatus.INFEASIBLE
                        ) {
                            runCatching {
                                val coreIndices = solver.sufficientAssumptionsForInfeasibility().toSet()
                                capture(literals.filterValues { it.index in coreIndices }.keys)
                            }
                        }
                    }
                    // §8.2 S-A: read the sub-solve solution's (core, bucket) off the finished solver.
                    // Best-effort and solution-gated: an INFEASIBLE/emission-less solve skips the capture.
                    tuning?.mmPenaltyBucketSolutionCapture?.let { capture ->
                        val probeVars = built.mmPenaltyBucketProbeVars
                        val solver = solverHandle.get()
                        if (probeVars != null &&
                            solver != null &&
                            (
                                outcome?.status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                                    outcome?.status == com.google.ortools.sat.CpSolverStatus.FEASIBLE
                            )
                        ) {
                            runCatching { capture(solver.value(probeVars.first), solver.value(probeVars.second)) }
                        }
                    }
                    close()
                }
            awaitClose {
                solverHandle.get()?.stopSearch()
                job.cancel()
            }
        }

    /**
     * P0.5 diagnostics: every DECISION var of the model (equipment picks, skill points, rune fills,
     * sublimation picks + stacking copies) — the layers a full capture/hint round-trip needs. Var names
     * are deterministic for a given (params, tuning), so a captured name→value map re-applies cleanly.
     */
    private fun diagnosticVars(built: BuiltModel): List<IntVar> =
        built.equipVars.values +
            built.skillVars.values +
            built.runeModel.runeVars.values
                .flatMap { it.values } +
            built.subModel.subVars.values +
            built.subModel.copyVars.values
                .flatten()

    /** The share of a relax-then-check leg's budget its relaxed stage may spend at most ([relaxThenCheck]). */
    internal const val RELAXED_STAGE_SHARE = 0.5

    /** The share of a relax-then-check leg's budget its check of a proven relaxed optimum may spend at most ([relaxThenCheck]). */
    internal const val CHECK_STAGE_SHARE = 0.1

    /**
     * The check of [relaxThenCheck] may run as long as the relaxed stage's solve did (at least this, in seconds — or the same in
     * deterministic units on a tuned solve). Finding a floored build at the relaxed optimum takes a presolve and the hint: 0.4-0.7 of
     * the relaxed solve's time on the GUI's default request (9 workers), whether the relaxed solution keeps its floors or another
     * build at the same objective does. Proving that none exists — a floor that binds — took 2.5-3.8 times it: past the cap the check
     * gives up, and the floored stage, which needs no such proof, takes over.
     */
    private const val MIN_CHECK_TIME = 1.0

    // The smallest stage budget worth a solve (wall seconds or deterministic units): OR-Tools reads a limit of 0 as no limit.
    private const val MIN_STAGE_BUDGET = 0.05

    /**
     * Whether [optimize] solves this leg by [relaxThenCheck]: a most-masteries request with floors — in production, or on a tuned
     * solve that opts in ([SolverTuning.relaxFloorsFirst]) and sets none of the measurement seams, which read a single model. Not
     * a request with a negative target or priority (a CLI-only shape): its objective no longer only grows with every stat a row
     * reads, which the relaxation's argument needs.
     */
    private fun relaxesFloorsFirst(
        params: WakfuBestBuildParams,
        tuning: SolverTuning?,
        mmTwoStage: Boolean,
        maxDamageRawFloor: Long?,
    ): Boolean =
        params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
            params.targetStats.hasFloors &&
            params.targetStats.none { it.target < 0 || it.userDefinedWeight < 0 } &&
            !mmTwoStage &&
            maxDamageRawFloor == null &&
            (
                tuning == null ||
                    (
                        tuning.relaxFloorsFirst &&
                            tuning.assignmentHint == null &&
                            tuning.captureAssignment == null &&
                            tuning.mmMasteryScoreUpperBound == null &&
                            tuning.mmPenaltyBucketInterval == null &&
                            tuning.mmPenaltyBucketSolutionCapture == null &&
                            tuning.mmDiFactorInterval == null &&
                            !tuning.mmHardTargetsAsAssumptions &&
                            tuning.mmInfeasibilityCoreCapture == null &&
                            tuning.mmSoftNoGoodCore == null
                    )
            )

    /**
     * Relax-then-check's stage budgets out of a leg's [total] (wall milliseconds, or a tuned solve's deterministic time) once [spent]
     * of it is gone: the RELAXED stage at most [RELAXED_STAGE_SHARE] of it and the CHECK at most [CHECK_STAGE_SHARE], neither past
     * its end; the FLOORED stage whatever is left. Never negative — so the stages never add up to more than [total].
     */
    internal fun relaxedStageBudget(
        total: Double,
        spent: Double,
    ): Double = minOf(total * RELAXED_STAGE_SHARE, total - spent).coerceAtLeast(0.0)

    internal fun checkStageBudget(
        total: Double,
        spent: Double,
    ): Double = minOf(total * CHECK_STAGE_SHARE, total - spent).coerceAtLeast(0.0)

    internal fun flooredStageBudget(
        total: Double,
        spent: Double,
    ): Double = (total - spent).coerceAtLeast(0.0)

    /**
     * Whether [build] is a build of [params]' FLOORED leg as the scorers read it: every floor held and, on the hard leg, every
     * target met ([hardLegHolds]). What the relaxed stage of [relaxThenCheck] may show.
     */
    private fun keepsFloors(
        params: WakfuBestBuildParams,
        build: BuildCombination,
        hardLeg: Boolean,
    ): Boolean {
        val stats = FindMostMasteriesFromInputScoring.resolvedStats(params.targetStats, build, params.character.baseCharacteristicValues)
        return if (hardLeg) params.targetStats.hardLegHolds(stats) else !params.targetStats.floorBroken(stats)
    }

    /**
     * RELAX THEN CHECK — how [optimize] solves a most-masteries leg, hard or soft, of a request with FLOORS (rows of target 0 on a
     * required stat: the GUI's default "air resistance 0" / "dodge 0"). The floors slow CP-SAT's proof down (a `≥ 0` per floor on
     * the hard leg, a reified halving on the soft one), yet the best build very often keeps them anyway. So:
     *
     *  1. the RELAXED stage solves the same leg WITHOUT the floors ([StatBuilder.relaxFloors]: no floor read — no `≥ 0`, no
     *     halving — each resistance family folded over its wanted elements alone), on at most [RELAXED_STAGE_SHARE] of the budget.
     *     It shows a build only when that build keeps every floor (and, on the hard leg, meets every target) in the scorers' exact
     *     read ([keepsFloors]) and scores no less than one already shown, and stamps none with a certificate-comparable objective
     *     (its objective is the relaxed one). Its final build is no result of the leg;
     *  2. when it PROVED its optimum v (read EXACTLY off the objective variable), the CHECK: the floored model with `objective = v`,
     *     hinted with the relaxed solution, on at most [CHECK_STAGE_SHARE] of the budget and as long as the relaxed solve ran
     *     ([MIN_CHECK_TIME]). A build it finds keeps every floor at objective v: the floored optimum (the argument below). That build
     *     is the leg's result, proven;
     *  3. otherwise — a floor binds (the check proves no floored build reaches v), the check ran out, or the relaxed stage proved
     *     nothing — the FLOORED stage solves the real leg on what is left of the budget, hinted with the relaxed solution and NOT cut:
     *     a redundant `objective ≤ v` made CP-SAT's floored proof 5-10× slower on a floor that binds (measured), so the relaxed
     *     optimum only serves the check. Its final is the leg's result, its OPTIMAL CP-SAT's own proof.
     * The leg's optimality stamp comes from the check (by the argument) or from the floored stage's OPTIMAL — never from the relaxed
     * stage.
     *
     * THE ARGUMENT — for every build x, `floored objective(x) ≤ relaxed objective(x)`:
     *  - hard leg: both objectives are the mastery × DI core then the targets' overshoot, and neither reads a floor; the floored leg
     *    only forbids builds the relaxed one allows;
     *  - soft leg: the floored objective halves the core while a floor is broken — the core is ≥ 0, so that only lowers it — and
     *    is the relaxed one otherwise;
     *  - the random-element rolls: each relaxed fold reads the wanted elements alone and places every roll as the game lets it
     *    ([rollCover]: a positive roll on as many of them as it reaches, a negative one on as few as it must), so for any in-game
     *    placement — the floored model's included — it has one that reads every wanted element at least as high, and both
     *    objectives only grow with the wanted elements (every target and priority being ≥ 0, [relaxesFloorsFirst]).
     * So `floored objective(x) ≤ relaxed objective(x) ≤ v` for every build x once the relaxed stage proved its optimum v: no
     * floored build is worth more than v, and a floored build worth v — what the check looks for, its model accepting nothing else
     * — is the floored optimum. When the relaxed optimum keeps every floor at the same objective (the common case), the hint hands
     * the check that build at once. A relaxed stage proven INFEASIBLE proves the floored leg infeasible (it allows every floored
     * build).
     *
     * BUDGET — ONE deadline for the leg, from its start ([legStartMs], before any model build): the relaxed stage gets at most
     * [RELAXED_STAGE_SHARE] of the budget, the check at most [CHECK_STAGE_SHARE], the floored stage what is left when it starts
     * ([relaxedStageBudget], [checkStageBudget], [flooredStageBudget]) — never more than the request's budget in all (stages each
     * given the whole budget would burn several times it). A tuned solve splits its deterministic time the same way. A floored
     * stage with no budget left, or that ends unproven below the best build the relaxed stage showed (or with none), delivers that
     * build instead, unproven.
     */
    private suspend fun relaxThenCheck(
        scope: ProducerScope<SolverResult<BuildCombination>>,
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        tuning: SolverTuning?,
        hardConstraints: Boolean,
        warmStart: BuildCombination?,
        warmScore: BigDecimal?,
        legStartMs: Long,
        solverHandle: java.util.concurrent.atomic.AtomicReference<CpSolver?>,
        mmObjectiveComparable: Boolean,
        mmHardLegMultiplier: Long?,
    ): SolveOutcome? {
        fun model(relaxFloors: Boolean) =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                applyDomination = tuning?.applyDominationOverride ?: (tuning == null),
                maxDamageExperiment = tuning?.maxDamageExperiment ?: MaxDamageExperimentConfig.DEFAULT,
                hardConstraints = hardConstraints,
                mmOvershootEncoding = tuning?.mmOvershootEncoding ?: MmOvershootEncoding.CURRENT,
                mmProductEncoding = tuning?.mmProductEncoding ?: MmProductEncoding.CURRENT,
                relaxFloors = relaxFloors
            )
        val totalWallMs = params.searchDuration.inWholeMilliseconds.toDouble()

        fun elapsedMs() = (System.currentTimeMillis() - legStartMs).toDouble()

        // The best build shown so far, its floors held: the display never regresses, and a floored stage that misses delivers it.
        val shown =
            java.util.concurrent.atomic
                .AtomicReference<Pair<BuildCombination, BigDecimal>?>(null)

        fun offer(
            build: BuildCombination,
            score: BigDecimal,
        ): Boolean {
            if (!keepsFloors(params, build, hardConstraints)) return false
            val best = shown.get()
            if (best != null && score < best.second) return false
            shown.set(build to score)
            return true
        }

        // ---- 1. The RELAXED stage.
        val relaxed = model(relaxFloors = true)
        // The relaxed leg allows every floored build: nothing for it, nothing for the floored leg.
        if (relaxed.maxDamageStaticallyInfeasible || !scope.isActive) return null
        warmStart?.let { combination ->
            val picked = combination.equipments.toHashSet()
            for ((equip, v) in relaxed.equipVars) relaxed.model.addHint(v, if (equip in picked) 1L else 0L)
        }
        var relaxedSolver: CpSolver? = null
        val relaxedSolveStartMs = System.currentTimeMillis()
        val relaxedOutcome =
            executeSolverAndEmitResults(
                relaxed.model,
                params,
                relaxed.allEquips,
                relaxed.equipVars,
                relaxed.skillVars,
                relaxed.runeModel,
                relaxed.subModel,
                relaxed.maxDamageRawScore,
                scope,
                tuning,
                onSolverReady = {
                    solverHandle.set(it)
                    relaxedSolver = it
                },
                suppressBelowScore = warmScore,
                maxWallSecondsOverride = (relaxedStageBudget(totalWallMs, elapsedMs()) / 1000.0).coerceAtLeast(MIN_STAGE_BUDGET),
                maxDeterministicTimeOverride = tuning?.let { relaxedStageBudget(it.maxDeterministicTime, 0.0) },
                emitFilter = ::offer,
                sendFinal = false,
                progressStartMs = legStartMs
            )
        val relaxedSolveSeconds = (System.currentTimeMillis() - relaxedSolveStartMs) / 1000.0
        if (!scope.isActive) return relaxedOutcome
        val relaxedStatus = relaxedOutcome?.status
        if (relaxedStatus == com.google.ortools.sat.CpSolverStatus.INFEASIBLE) return relaxedOutcome
        val solver = relaxedSolver
        val relaxedSolved =
            relaxedStatus == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                relaxedStatus == com.google.ortools.sat.CpSolverStatus.FEASIBLE
        // v: the relaxed optimum, EXACT (an integer variable's value, never a double), when the relaxed stage proved it.
        val relaxedOptimum =
            if (relaxedStatus == com.google.ortools.sat.CpSolverStatus.OPTIMAL && solver != null) runCatching { solver.value(relaxed.objective) }.getOrNull() else null
        // Same params ⇒ the same decision variables, by name, in every model (the floors only change how the stats are read).
        val hint = if (relaxedSolved && solver != null) runCatching { diagnosticVars(relaxed).associate { it.name to solver.value(it) } }.getOrNull() else null
        // The relaxed final build is no result of the leg, but one that keeps the floors may still be delivered at the end.
        val relaxedFinal = relaxedOutcome?.finalBuild
        val relaxedFinalScore = relaxedOutcome?.finalScore
        if (relaxedFinal != null && relaxedFinalScore != null) offer(relaxedFinal, relaxedFinalScore)
        var spentDeterministic = relaxedOutcome?.deterministicTime ?: 0.0

        fun hinted(built: BuiltModel) = built.also { hint?.let { values -> for (v in diagnosticVars(built)) values[v.name]?.let { built.model.addHint(v, it) } } }

        // ---- 2. The CHECK: a floored build at the proven relaxed optimum v is the floored optimum.
        if (relaxedOptimum != null) {
            val check = model(relaxFloors = false)
            // No build can keep a floor (or meet a target): nothing to deliver — the relaxed stage showed none either.
            if (check.maxDamageStaticallyInfeasible) return null
            if (!scope.isActive) return relaxedOutcome
            check.model.addEquality(check.objective, relaxedOptimum)
            hinted(check)
            // As long as the relaxed solve ran at most, so a floor that binds costs the check little.
            val checkWallSeconds = minOf(checkStageBudget(totalWallMs, elapsedMs()) / 1000.0, maxOf(relaxedSolveSeconds, MIN_CHECK_TIME))
            val checkDeterministic =
                tuning?.let { minOf(checkStageBudget(it.maxDeterministicTime, spentDeterministic), maxOf(relaxedOutcome?.deterministicTime ?: 0.0, MIN_CHECK_TIME)) }
            if ((checkDeterministic ?: checkWallSeconds) >= MIN_STAGE_BUDGET) {
                val checked =
                    executeSolverAndEmitResults(
                        check.model,
                        params,
                        check.allEquips,
                        check.equipVars,
                        check.skillVars,
                        check.runeModel,
                        check.subModel,
                        check.maxDamageRawScore,
                        scope,
                        tuning,
                        onSolverReady = { solverHandle.set(it) },
                        suppressBelowScore = listOfNotNull(warmScore, shown.get()?.second).maxOrNull(),
                        maxWallSecondsOverride = checkWallSeconds,
                        maxDeterministicTimeOverride = checkDeterministic,
                        progressStartMs = legStartMs,
                        mmObjectiveComparable = mmObjectiveComparable,
                        mmHardLegMultiplier = mmHardLegMultiplier
                    )
                // Found: a floored build worth v — the floored optimum, sent as the leg's final (its objective fixed, CP-SAT's
                // OPTIMAL comes with the first solution).
                if (checked?.finalBuild != null) return checked
                spentDeterministic += checked?.deterministicTime ?: 0.0
            }
            if (!scope.isActive) return relaxedOutcome
        }

        // ---- 3. The FLOORED stage: the real leg, hinted, with no cut.
        val floored = model(relaxFloors = false)
        // No build can keep a floor (or meet a target): nothing to deliver — the relaxed stage showed none either.
        if (floored.maxDamageStaticallyInfeasible) return null
        if (!scope.isActive) return relaxedOutcome
        hinted(floored)
        val wallLeftSeconds = flooredStageBudget(totalWallMs, elapsedMs()) / 1000.0
        val deterministicLeft = tuning?.let { flooredStageBudget(it.maxDeterministicTime, spentDeterministic) }
        val flooredOutcome =
            if ((deterministicLeft ?: wallLeftSeconds) < MIN_STAGE_BUDGET) {
                null
            } else {
                executeSolverAndEmitResults(
                    floored.model,
                    params,
                    floored.allEquips,
                    floored.equipVars,
                    floored.skillVars,
                    floored.runeModel,
                    floored.subModel,
                    floored.maxDamageRawScore,
                    scope,
                    tuning,
                    onSolverReady = { solverHandle.set(it) },
                    suppressBelowScore = listOfNotNull(warmScore, shown.get()?.second).maxOrNull(),
                    maxWallSecondsOverride = wallLeftSeconds,
                    maxDeterministicTimeOverride = deterministicLeft,
                    progressStartMs = legStartMs,
                    mmObjectiveComparable = mmObjectiveComparable,
                    mmHardLegMultiplier = mmHardLegMultiplier
                )
            }
        // Unproven, the floored stage never ends below what the relaxed stage showed: that build is the leg's result then.
        val best = shown.get()
        val flooredScore = flooredOutcome?.finalScore
        if (best != null &&
            flooredOutcome?.status != com.google.ortools.sat.CpSolverStatus.OPTIMAL &&
            (flooredScore == null || flooredScore < best.second) &&
            scope.isActive
        ) {
            scope.send(SolverResult(best.first, best.second, 100))
        }
        return flooredOutcome ?: relaxedOutcome
    }

    private class BuiltModel(
        val model: CpModel,
        val objective: IntVar,
        // Max-damage only: the UNPENALIZED per-turn damage proxy var (see [MaxDamageObjectiveVars.rawScore]).
        // Read on the solved assignment to stamp [SolverResult.maxDamageRawProxy]; null in the other modes.
        val maxDamageRawScore: IntVar?,
        val allEquips: List<Equipment>,
        val equipVars: Map<Equipment, IntVar>,
        val skillVars: Map<SkillCharacteristic, IntVar>,
        val runeModel: RuneModel,
        val subModel: SublimationModel,
        // Max-damage only: every tracked objective-chain var with its name and reachable [LongRange], for
        // the soundness test (empty for the other modes). See [DomainTracker] / [maxDamageVarBoundsForTest].
        val maxDamageTracked: List<Triple<IntVar, String, LongRange>> = emptyList(),
        // Precision only: same, for [precisionVarBoundsForTest] (empty for the other modes).
        val precisionTracked: List<Triple<IntVar, String, LongRange>> = emptyList(),
        // Max-damage only: the EXACT per-AP-cell certifier objective (single-element), captured when buildModel
        // is called with certifyAllApForTest = true. Key = AP, value = certifier objective (or -1 where the
        // certifier bails to CP-SAT). Empty otherwise. See [certifierCellObjectivesForTest].
        val certifierObjectivesForTest: Map<Int, Long> = emptyMap(),
        // Max-damage only: the FAST tier-1 per-AP-cell objective (sound upper bound, -1 where it bails),
        // captured alongside the exact one when certifyAllApForTest = true and no audit cell filter is set.
        // Empty otherwise. Compared against [certifierObjectivesForTest] by the `fast ≥ exact` lock.
        val certifierFastObjectivesForTest: Map<Int, Long> = emptyMap(),
        // Max-damage only (B7): the TIER-1.5 sharpened per-AP-cell objective (sound upper bound, -1 where it bails),
        // captured alongside exact+fast when certifyAllApForTest = true. Empty otherwise. Sits between them in the
        // `fast ≥ tier1.5 ≥ exact` lock.
        val certifierTier15ObjectivesForTest: Map<Int, Long> = emptyMap(),
        // Max-damage only: the two-tier [CertLedger] (P3.2), captured when certifyLedgerForTest = true. Null otherwise.
        val certifierLedgerForTest: CertLedger? = null,
        // Provenance lines for [certifyExplainCellForTest] (empty otherwise).
        val certifierExplainForTest: List<String> = emptyList(),
        // E8 item A: the STRUCTURED provenance — the winning composition's equipmentIds (empty otherwise).
        val certifierExplainItemIds: List<Int> = emptyList(),
        // C2: max-damage hard-constraints only — true when a required target exceeds its reachable ceiling, so the
        // model is PROVABLY infeasible and [optimize] can skip the CP-SAT solve. Always false in the other modes.
        val maxDamageStaticallyInfeasible: Boolean = false,
        // C7: the crit·diff AM-GM bound actually added as a constraint (null = the cut did not fire). See
        // [StatBuilder.critDiffJointCutBoundForTest] / [maxDamageCritDiffCutBoundForTest].
        val critDiffJointCutBoundForTest: Long? = null,
        // §8.2 S-A only: (core, bucket) probe vars of a bucket-interval sub-model; null otherwise.
        val mmPenaltyBucketProbeVars: Pair<IntVar, IntVar>? = null,
        // §8.5 S-D only: the hard leg's assumption literals (target → literal); null otherwise.
        val mmAssumptionLiterals: Map<Characteristic, com.google.ortools.sat.BoolVar>? = null,
        // Test/research partition seam: resolved sheet-stat vars used by exact region oracles.
        // Keeping them on BuiltModel avoids rebuilding a second StatBuilder after the objective.
        val actualStatVars: Map<Characteristic, IntVar> = emptyMap(),
        // Max-damage only (v44): the AUX worlds' per-cell fast bound alone (already folded into the certifier
        // maps above), captured when certifyAllApForTest = true. Empty when the shape has no aux world.
        val certifierAuxObjectivesForTest: Map<Int, Long> = emptyMap(),
        // Max-damage only (v47): per AP cell, the relaxed capped aux world's objective vs the exact capped split's.
        val certifierAuxRelaxedVsSplitForTest: Map<Int, Pair<Long, Long>> = emptyMap(),
        // Test seam ([elementRowSolveForTest]): the per-element vars the request's elemental target rows read — each
        // family's fold — so a lock compares the model's claimed per-element values with the scorer's.
        val elementRowReads: Map<Characteristic, IntVar> = emptyMap(),
        // Test seam ([elementRowSolveForTest]): the floors the model reads ([StatBuilder.floorReads]) and the boolean that
        // halves its objective (precision's, or a soft leg's floor penalty), when the model built them.
        val floorReads: List<Pair<Characteristic, IntVar>> = emptyList(),
        val halvingFlag: IntVar? = null,
    )

    /**
     * Exact `floor((a · b) / divisor)` clamped to `[0, cap]`. Used where a HARD CP-SAT upper bound is derived
     * from a product of two Longs: computing it as a `Double` (`a.toDouble() * b`) silently loses precision once
     * the product exceeds 2^53, and rounding the bound DOWN below the true maximum would cut the optimum out of
     * the model. BigInteger keeps the floor exact; the clamp both enforces the `[0, cap]` domain and keeps the
     * final `.toLong()` in range (an over-cap product would otherwise wrap negative).
     */
    internal fun clampedProductQuotient(
        a: Long,
        b: Long,
        divisor: Long,
        cap: Long,
    ): Long =
        (BigInteger.valueOf(a) * BigInteger.valueOf(b) / BigInteger.valueOf(divisor))
            .max(BigInteger.ZERO)
            .min(BigInteger.valueOf(cap))
            .toLong()

    // C3: the number of distinct base-pool objects the domination memo retains before it clears (a single search
    // touches ONE pool object; this bounds cross-search retention so the identity map can't grow unboundedly).
    private const val DOMINATION_MEMO_MAX_POOLS = 8

    // C3 memo: (basePool identity → (shape → filtered pool)). The per-slot domination filter is re-run on every
    // [buildModel] call, and one max-damage search makes ~12–20 (element probes + AP probes + hard/soft legs). The
    // filtered pool is a PURE function of (basePool, shape), so memoize it. Keyed by basePool IDENTITY —
    // [MaxDamageSearch] threads the SAME pool object through every probe, and identity can never serve a wrong pool
    // — AND the [DominationShape] VALUE, which encodes the scored element (its `compared`/`minimized` sets),
    // targets, subs and floor: a different element yields a different shape ⇒ a SEPARATE entry, so the memo is
    // automatically PER-ELEMENT (sharing one element's dominance relation across elements would prune the wrong
    // per-element optimum). A prefiltered basePool is a fresh object each call ⇒ identity miss ⇒ recompute
    // (correct, just no win) — fine, the prefilter case is rare. Concurrent same-key computes are idempotent.
    private val dominationFilterMemo:
        java.util.IdentityHashMap<Map<ItemType, List<Equipment>>, MutableMap<DominationShape, Map<ItemType, List<Equipment>>>> =
        java.util.IdentityHashMap()

    private fun filterDominatedPoolMemoized(
        basePool: Map<ItemType, List<Equipment>>,
        shape: DominationShape,
    ): Map<ItemType, List<Equipment>> {
        val perShape =
            synchronized(dominationFilterMemo) {
                if (dominationFilterMemo.size > DOMINATION_MEMO_MAX_POOLS) dominationFilterMemo.clear()
                dominationFilterMemo.getOrPut(basePool) { java.util.concurrent.ConcurrentHashMap() }
            }
        // getOrPut on the concurrent inner map may double-compute under a race, but [filterDominatedPool] is a pure
        // deterministic function, so both threads produce a structurally-identical pool — a benign, idempotent race.
        return perShape.getOrPut(shape) { filterDominatedPool(basePool, shape) }
    }

    /**
     * Assembles the full CP-SAT model — item / skill / rune / sublimation vars, validity constraints, and the
     * mode's objective — and calls [CpModel.maximize]. Extracted from [optimize] so the test-only
     * [maxDamageObjectiveValueForTest] builds a byte-identical model (no drift between the production solve and
     * the objective-readout used by the bi-element tests).
     */
    private fun buildModel(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        // Max-damage only: declare the objective-chain vars sized to their reachable domains (so CP-SAT can
        // prove OPTIMAL). false reproduces the loose guard domains — the soundness test's reference build.
        tightDomains: Boolean = true,
        // Benchmark/test seam: bypass the prefilter so the full pool is solved regardless of the gate.
        forceFullPool: Boolean = false,
        // Test seam: force the per-stat rune COUNT model even where the single-type fold would apply, so a
        // test can assert the fold preserves the optimum (fold == count optimum on a rune pool).
        forceRuneCountModel: Boolean = false,
        // Test seam: force the rune socket cap back to `≤` (disable exact fill), so a test can assert
        // most-masteries exact fill preserves the ≤-model optimum (exact-fill optimum == ≤ optimum).
        forceRuneLeq: Boolean = false,
        // Test seams (the pruning-exactness lock): false keeps every max-damage collapse candidate rune on every carrier
        // (no Pareto pruning), resp. posts no choice gate ([RuneModel.choiceGates]). Production keeps both on.
        runeChoicePruning: Boolean = true,
        runeChoiceGating: Boolean = true,
        // Production path only: drop per-slot dominated items ([filterDominatedPool]) — provably optimum-
        // preserving in all three (monotone) modes. Off by default so the deterministic test path sees the full
        // pool unchanged; the production [optimize] passes true and the soundness lock toggles it.
        applyDomination: Boolean = false,
        maxDamageExperiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig.DEFAULT,
        maxDamageObjectiveCutoff: Long? = null,
        // Hard-constraints-first max-damage solve: required targets become HARD `actual ≥ target` constraints
        // under a plain damage objective (no shortfall penalty). Threaded to [buildMaxDamageObjective].
        hardConstraints: Boolean = false,
        // Build the AP/MP/CRIT/HP actual-stat vars (stat-bound pins + MaxDamageTimedProfile.actualStats).
        trackActualStats: Boolean = false,
        // P2b two-stage lexicographic (most-masteries hard leg) — see [buildMostMasteriesObjective].
        mmPlainPrimaryObjective: Boolean = false,
        mmOvershootPinnedPrimary: Long? = null,
        // Manual most-masteries A/B encodings. Production always passes CURRENT through [optimize].
        mmOvershootEncoding: MmOvershootEncoding = MmOvershootEncoding.CURRENT,
        mmProductEncoding: MmProductEncoding = MmProductEncoding.CURRENT,
        // Measurement-only redundant dual cut on the MM core; see [SolverTuning.mmMasteryScoreUpperBound].
        mmMasteryScoreUpperBound: Long? = null,
        // §8.2 S-A outer bucket B&B seam — see [SolverTuning.mmPenaltyBucketInterval].
        mmPenaltyBucketInterval: IntRange? = null,
        mmPenaltyBucketFoldedObjective: Boolean = false,
        mmPenaltyGeometryProbe: ((Int, LongArray, Long) -> Unit)? = null,
        // §8.4 S-C outer DI-factor seam — see [SolverTuning.mmDiFactorInterval].
        mmDiFactorInterval: IntRange? = null,
        mmDiFactorFoldedObjective: Boolean = false,
        // §8.5 S-D seams — see [SolverTuning.mmHardTargetsAsAssumptions] / [SolverTuning.mmSoftNoGoodCore].
        mmHardTargetsAsAssumptions: Boolean = false,
        mmSoftNoGoodCore: Set<Characteristic>? = null,
        // Test seam: when true, the max-damage build also runs [certifyMaxPerHitAtAp] for every AP cell and
        // stores the resulting objectives in [BuiltModel.certifierObjectivesForTest] (single-element only).
        certifyAllApForTest: Boolean = false,
        // Test seam: PROVENANCE — explain the winning certificate state of this AP cell into
        // [BuiltModel.certifierExplainForTest] (single-element only). See [StatBuilder.certifyExplainAtAp].
        certifyExplainCellForTest: Int? = null,
        // E8 item A (perf): a cached winning (world, crit-step) for [certifyExplainCellForTest] — when set, the
        // explain replays only that one pass instead of the N-worlds scan (see [certifyExplainAtApFromProvenance]).
        certifyExplainProvenanceForTest: CellProvenance? = null,
        // Test seam: thread count for the FAST pass (P3.1 warm-once parallelism); 1 = serial (default).
        certifyFastThreadsForTest: Int = 1,
        // Dynamic per-tier thread count for the certificate (see [StatBuilder.certifierThreadsProvider]).
        certifierThreadsProvider: ((CertTier) -> Int)? = null,
        // Dynamic incumbent, resolved right before elimination (see [StatBuilder.certifierIncumbentProvider]).
        certifierIncumbentProvider: (() -> Long?)? = null,
        // Cascade tier-1.5 (see [StatBuilder.certifyLedgerCascadeTier15]).
        certifyLedgerCascadeTier15: Boolean = false,
        // Test seam: run ONLY the fast pass in the certify-for-test block (skip the exact per-cell ledger).
        certifyFastOnlyForTest: Boolean = false,
        // Test seam (P3.2 orchestrator): compute the two-tier [CertLedger] into [BuiltModel.certifierLedgerForTest].
        certifyLedgerForTest: Boolean = false,
        certifyLedgerIncumbentForTest: Long? = null,
        certifyLedgerForceTier2AllForTest: Boolean = false,
        // B6: reuse a prior compute's SCALED per-cell fast bounds (+ bail set) for this shape, skipping the tier-1
        // fast DP in [certifyLedger] (a pure, byte-identical function of the shape). Null ⇒ compute the fast pass.
        certifyLedgerPrecomputedFast: Map<Int, Long>? = null,
        certifyLedgerPrecomputedBailed: Set<Int>? = null,
        certifyLedgerPrecomputedTier15: Map<Int, Long>? = null,
        certifyLedgerPrecomputedExact: Map<Int, Long>? = null,
        certifyLedgerPrecomputedProv: Map<Int, CellProvenance>? = null,
        // B8: polled once per certifier DP stage; when it flips true the certifier bails (sound) so a cancelled
        // proof stops promptly. Default never-cancel keeps the deterministic test/model builds byte-identical.
        certifierCancelled: () -> Boolean = { false },
        // CERTIFIER_VERSION 52: the certificate is for a HARD-LEG result ⇒ the target-aware ledger (see
        // [StatBuilder.certifierTargetAware]). False keeps the target-blind certifier.
        certifierTargetAware: Boolean = false,
        // Most-masteries only: the model WITHOUT the request's floors ([StatBuilder.relaxFloors]) — relax-then-check's relaxed
        // stage ([relaxThenCheck]).
        relaxFloors: Boolean = false,
    ): BuiltModel {
        // Phase timing (WAKFU_BUILD_MODEL_TIMING=1): where the ~seconds of model construction go on the
        // big shapes — one stderr line per buildModel call. No behavior change.
        val bmTimingEnabled = System.getenv("WAKFU_BUILD_MODEL_TIMING") == "1"
        val bmStart = if (bmTimingEnabled) System.nanoTime() else 0L
        var bmLast = bmStart
        val bmPhases = StringBuilder()

        fun bmMark(label: String) {
            if (!bmTimingEnabled) return
            val now = System.nanoTime()
            bmPhases
                .append(label)
                .append('=')
                .append((now - bmLast) / 1_000_000)
                .append("ms ")
            bmLast = now
        }
        val model = CpModel()

        // Full pool gives the provable *global* optimum and stays tractable for most queries.
        // Only multi-element mastery/resistance targets activate the heavy random-element modelling that
        // explodes on the full late-game pool, so we prefilter exactly (and only) those cases.
        val basePool =
            if (!forceFullPool && needsItemPrefilter(params.targetStats)) {
                prefilterRelevantEquipments(equipmentsByItemType, params)
            } else {
                equipmentsByItemType
            }
        // Sound per-slot domination: drop items provably beaten in their own slot (all three modes are monotone;
        // see [dominationShape]). Skipped for forced items / forced conditional subs and for forceFullPool
        // (the full reference). Removes no optimal build, so the proven optimum is identical — only the model shrinks.
        // Stats a dangerous (≤/exact/parity) conditional sub reads — non-monotone, so domination pins them AND
        // most-masteries exact socket fill must avoid them (null = un-analyzable / forced ⇒ both stay conservative).
        val dominationShape = dominationShape(params, sublimations)
        val activeDomination = if (applyDomination && !forceFullPool) dominationShape else null
        // C3: memoized so the ~12–20 buildModel calls of one search share the per-element filter result.
        val pool = if (activeDomination != null) filterDominatedPoolMemoized(basePool, activeDomination) else basePool
        bmMark("domination")
        val allEquips = orderEquipments(pool)
        val equipVars = model.createEquipmentVariables(allEquips)
        bmMark("equipVars")
        val skillVars = model.createSkillVariables(params.character.characterSkills)
        // The single-type rune fold (createRuneModel) is sound unless a sublimation IN PLAY requires a
        // POSITIVE secondary-mastery cap (`secondary ≤ N`, N>0): there an intra-item secondary/elemental
        // MIX can be optimal, which the fold can't express. Every solver-choosable secondary-cap sub has
        // N=0 (⇒ all-elemental, no mix), so the default search folds; this guard future-proofs the data and
        // a forced sub with N>0.
        // KNOWN GAP (OPEN, docs/perf-review-backlog.md §E): N=0 does not rule a mix out — an item's NEGATIVE
        // secondary line gives that secondary's cap (the cap holds EACH secondary mastery on its own) a positive
        // budget, which a mixed item can fill exactly. The per-stat count model beat the fold by 0.03–0.48 % on 6
        // seeded pools of the 2026-10-04 review fuzz (measured under the old SUM reading of the cap).
        val forcedSubNames = params.forcedSublimations.map { it.lowercase() }.toSet()
        val secondaryCapMixSubInPlay =
            sublimations.any { sub ->
                val inPlay = (sub.solverChoosable && params.useSublimations) || sub.name.fr.lowercase() in forcedSubNames || sub.name.en.lowercase() in forcedSubNames
                inPlay &&
                    sub.condition?.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST &&
                    (sub.condition?.value ?: 0) > 0
            }
        val allowRuneFold = !forceRuneCountModel && !secondaryCapMixSubInPlay
        val runeModel =
            model.createRuneModel(
                params,
                allEquips,
                equipVars,
                runes,
                allowRuneFold,
                dominationShape?.pinned,
                forceRuneLeq,
                reads = maxDamageRuneReads(params, sublimations, buildSkillTerms(skillVars).percent.keys),
                choicePruning = runeChoicePruning,
                choiceGating = runeChoiceGating
            )
        bmMark("runeModel")
        val subModel = model.createSublimationModel(params, allEquips, equipVars, sublimations)
        // The collapse's gated rune choices (`pick ≤ Σ subVar`): the reads come from the same modelled-sub list, so
        // every gate sub has a var; a missing one leaves its pick ungated (no cut — sound).
        for ((pick, subs) in runeModel.choiceGates) {
            val subVars = subs.mapNotNull { subModel.subVars[it] }
            if (subVars.size == subs.size) model.addLessOrEqual(pick, LinearExpr.sum(subVars.toTypedArray()))
        }
        bmMark("subModel")
        // A normal sublimation does NOT reserve rune sockets. Golden runes (colour-agnostic) form its ordered
        // colour pattern AND still carry their stat — doubling where the item favours that colour — so a carrier
        // keeps a full set of runes alongside the sub. Carrier eligibility (≥3-socket item) and the
        // ≤1-normal-sub-per-item cap live in createSublimationModel; rune capacity (Σ runes ≤ sockets) lives in
        // createRuneModel. The two no longer share a socket budget.

        model.addBuildValidityConstraints(allEquips, equipVars)
        model.addForcedItemsEquippedConstraints(params, allEquips, equipVars)
        bmMark("validity")

        var maxDamageTracked: List<Triple<IntVar, String, LongRange>> = emptyList()
        var maxDamageRawScore: IntVar? = null
        var precisionTracked: List<Triple<IntVar, String, LongRange>> = emptyList()
        var certifierObjectives: Map<Int, Long> = emptyMap()
        var certifierFastObjectives: Map<Int, Long> = emptyMap()
        var certifierTier15Objectives: Map<Int, Long> = emptyMap()
        var certifierAuxObjectives: Map<Int, Long> = emptyMap()
        var certifierAuxRelaxedVsSplit: Map<Int, Pair<Long, Long>> = emptyMap()
        var certifierLedger: CertLedger? = null
        var certifierExplain: List<String> = emptyList()
        var certifierExplainItemIds: List<Int> = emptyList()
        var critDiffJointCutBound: Long? = null
        // C2: set by the max-damage hard-constraints branch when a required target exceeds its reachable ceiling.
        var maxDamageStaticallyInfeasible = false
        // §8.2 S-A: (core, bucket) probe vars of a bucket-interval sub-model.
        var mmPenaltyProbeVars: Pair<IntVar, IntVar>? = null
        // §8.5 S-D: the hard leg's assumption literals (target → literal).
        var mmAssumptionLits: Map<Characteristic, com.google.ortools.sat.BoolVar>? = null
        var actualStatVars: Map<Characteristic, IntVar> = emptyMap()
        var elementRowReads: Map<Characteristic, IntVar> = emptyMap()
        var floorReads: List<Pair<Characteristic, IntVar>> = emptyList()
        var halvingFlag: IntVar? = null
        var mmStatBuilder: StatBuilder? = null
        val objective =
            when (params.scoreComputationMode) {
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT -> {
                    val mm =
                        model.buildMostMasteriesObjective(
                            params,
                            allEquips,
                            equipVars,
                            skillVars,
                            runeModel,
                            subModel,
                            hardConstraints,
                            mmPlainPrimaryObjective,
                            mmOvershootPinnedPrimary,
                            mmOvershootEncoding,
                            mmProductEncoding,
                            mmMasteryScoreUpperBound,
                            mmPenaltyBucketInterval,
                            mmPenaltyBucketFoldedObjective,
                            mmPenaltyGeometryProbe,
                            mmDiFactorInterval,
                            mmDiFactorFoldedObjective,
                            mmHardTargetsAsAssumptions,
                            mmSoftNoGoodCore,
                            relaxFloors = relaxFloors,
                            onStatBuilder = { mmStatBuilder = it }
                        )
                    mmStatBuilder?.let { statBuilder ->
                        elementRowReads = statBuilder.elementRowReads
                        floorReads = statBuilder.floorReadsForTest()
                        halvingFlag = statBuilder.halvingFlagForTest
                    }
                    maxDamageStaticallyInfeasible = mm.staticallyInfeasible
                    mmPenaltyProbeVars = mm.penaltyBucketProbeVars
                    mmAssumptionLits = mm.assumptionLiterals
                    mm.objective
                }

                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT -> {
                    // Declare the precision stat chain on its reachable domains (like max-damage), instead of
                    // the loose 10M guard: every reach is a sound superset of the attainable value (locked by
                    // [precisionVarBoundsForTest]), so the optimum is unchanged while presolve / the LP
                    // relaxation work on tight bounds. tightDomains=false reproduces the loose reference build.
                    val statBuilder =
                        StatBuilder(
                            model,
                            params,
                            allEquips,
                            equipVars,
                            skillVars,
                            runeModel,
                            subModel,
                            tight = tightDomains,
                            // Decouple from the max-damage experiment default (see [MaxDamageExperimentConfig.NON_MAX_DAMAGE]).
                            maxDamageExperiment = MaxDamageExperimentConfig.NON_MAX_DAMAGE
                        )
                    val obj = model.buildPrecisionObjective(params, statBuilder)
                    precisionTracked = statBuilder.tracker.tracked()
                    elementRowReads = statBuilder.elementRowReads
                    floorReads = statBuilder.floorReadsForTest()
                    halvingFlag = statBuilder.halvingFlagForTest
                    obj
                }

                ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE -> {
                    val statBuilder =
                        StatBuilder(
                            model,
                            params,
                            allEquips,
                            equipVars,
                            skillVars,
                            runeModel,
                            subModel,
                            tight = tightDomains,
                            maxDamageExperiment = maxDamageExperiment,
                            certifyForTest = certifyAllApForTest,
                            certifyExplainCell = certifyExplainCellForTest,
                            certifyExplainProvenance = certifyExplainProvenanceForTest,
                            certifyFastThreads = certifyFastThreadsForTest,
                            certifierThreadsProvider = certifierThreadsProvider,
                            certifierIncumbentProvider = certifierIncumbentProvider,
                            certifyLedgerCascadeTier15 = certifyLedgerCascadeTier15,
                            certifyFastOnly = certifyFastOnlyForTest,
                            certifyLedgerForTest = certifyLedgerForTest,
                            certifyLedgerIncumbent = certifyLedgerIncumbentForTest,
                            certifyLedgerForceTier2All = certifyLedgerForceTier2AllForTest,
                            certifyLedgerPrecomputedFast = certifyLedgerPrecomputedFast,
                            certifyLedgerPrecomputedBailed = certifyLedgerPrecomputedBailed,
                            certifyLedgerPrecomputedTier15 = certifyLedgerPrecomputedTier15,
                            certifyLedgerPrecomputedExact = certifyLedgerPrecomputedExact,
                            certifyLedgerPrecomputedProv = certifyLedgerPrecomputedProv,
                            certifierCancelled = certifierCancelled,
                            certifierTargetAware = certifierTargetAware
                        )
                    val built =
                        model.buildMaxDamageObjective(
                            params,
                            statBuilder,
                            maxDamageObjectiveCutoff,
                            hardConstraints,
                            mmPenaltyBucketInterval,
                            mmPenaltyBucketFoldedObjective,
                            mmPenaltyGeometryProbe
                        )
                    maxDamageRawScore = built.rawScore
                    maxDamageStaticallyInfeasible = built.staticallyInfeasible
                    mmPenaltyProbeVars = built.penaltyBucketProbeVars
                    maxDamageTracked = statBuilder.tracker.tracked()
                    certifierObjectives = statBuilder.certifierObjectivesForTest
                    certifierFastObjectives = statBuilder.certifierFastObjectivesForTest
                    certifierTier15Objectives = statBuilder.certifierTier15ObjectivesForTest
                    certifierAuxObjectives = statBuilder.certifierAuxObjectivesForTest
                    certifierAuxRelaxedVsSplit = statBuilder.certifierAuxRelaxedVsSplitForTest
                    certifierLedger = statBuilder.certifierLedgerForTest
                    certifierExplain = statBuilder.certifierExplainForTest
                    certifierExplainItemIds = statBuilder.certifierExplainItemIds
                    critDiffJointCutBound = statBuilder.critDiffJointCutBoundForTest
                    elementRowReads = statBuilder.elementRowReads
                    floorReads = statBuilder.floorReadsForTest()
                    halvingFlag = statBuilder.halvingFlagForTest
                    // Proof/research profiles only (stat-bound pins + reported actual stats): building
                    // actualStat(HP) adds the pre-HP sum + %HP product chain to every PRODUCTION model
                    // that has no HP target (pre-release review 2026-10-01 — main never paid it).
                    actualStatVars =
                        if (trackActualStats) {
                            listOf(
                                Characteristic.ACTION_POINT,
                                Characteristic.MOVEMENT_POINT,
                                Characteristic.CRITICAL_HIT,
                                Characteristic.HP
                            ).associateWith(statBuilder::actualStat)
                        } else {
                            emptyMap()
                        }
                    built.objective
                }
            }
        model.maximize(objective)

        bmMark("objective")
        if (bmTimingEnabled) {
            System.err.println(
                "BUILD_MODEL_TIMING mode=${params.scoreComputationMode} totalMs=${(System.nanoTime() - bmStart) / 1_000_000} $bmPhases"
            )
        }
        return BuiltModel(
            model,
            objective,
            maxDamageRawScore,
            allEquips,
            equipVars,
            skillVars,
            runeModel,
            subModel,
            maxDamageTracked,
            precisionTracked,
            certifierObjectives,
            certifierFastObjectives,
            certifierTier15Objectives,
            certifierLedger,
            certifierExplain,
            certifierExplainItemIds,
            maxDamageStaticallyInfeasible,
            critDiffJointCutBound,
            mmPenaltyProbeVars,
            mmAssumptionLits,
            actualStatVars,
            certifierAuxObjectives,
            certifierAuxRelaxedVsSplit,
            elementRowReads,
            floorReads,
            halvingFlag
        )
    }

    /** Configures a deterministic, machine-reproducible max-damage solver (full presolve + level-2 linearization). */
    private fun deterministicMaxDamageSolver(tuning: SolverTuning): CpSolver {
        val solver = CpSolver()
        solver.parameters.logSearchProgress = false
        solver.parameters.linearizationLevel = 2
        solver.parameters.numSearchWorkers = tuning.numSearchWorkers
        solver.parameters.randomSeed = tuning.randomSeed
        solver.parameters.maxDeterministicTime = tuning.maxDeterministicTime
        if (tuning.interleaveSearch) solver.parameters.interleaveSearch = true
        return solver
    }

    /**
     * Test-only: assemble the model exactly like [optimize] (via [buildModel]) and return the **maximized
     * objective value**, requiring a deterministic [SolverTuning] and a proven `OPTIMAL` status. Needed because
     * the streamed [SolverResult.matchPercentage] is computed by the single-element scorer and so
     * cannot read the bi-element objective for an interior split — see the Lot 2 tests in WakfuBuildSolverTest.
     */
    internal fun maxDamageObjectiveValueForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
    ): Long {
        val built = buildModel(params, equipmentsByItemType, emptyList(), emptyList(), maxDamageExperiment = tuning.maxDamageExperiment)
        val solver = deterministicMaxDamageSolver(tuning)
        val status = solver.solve(built.model)
        require(status == com.google.ortools.sat.CpSolverStatus.OPTIMAL) { "expected OPTIMAL, got $status" }
        return solver.objectiveValue().toLong()
    }

    /** A max-damage solve's outcome: its maximized objective and whether CP-SAT *proved* it optimal. */
    internal data class MaxDamageSolveOutcome(
        val objective: Long,
        val isOptimal: Boolean,
        val hasSolution: Boolean,
        // The equipmentIds selected in the solved assignment (empty when there is no solution). Lets a test assert
        // that a specific item survived the pool build and was actually picked — e.g. the A1 lock that a generic-
        // resistance item required to meet a hard resistance target is NOT domination-pruned.
        val selectedEquipmentIds: Set<Int> = emptySet(),
    )

    internal data class MaxDamageTimedProfile(
        val status: String,
        val objective: Long,
        val bestBound: Long,
        val objectiveCutoff: Long?,
        val wallTimeSec: Double,
        val deterministicTime: Double,
        val booleans: Long,
        val branches: Long,
        val conflicts: Long,
        val restarts: Long,
        val lpIterations: Long,
        val variables: Int,
        val constraints: Int,
        val poolSize: Int,
        val experiment: MaxDamageExperimentConfig,
        val selectedEquipmentIds: Set<Int>,
        val selectedSublimationStateIds: Set<Int>,
        val selectedSublimationCopies: Map<Int, Long>,
        val actualStats: Map<Characteristic, Long>,
        val rawObjective: Long,
    ) {
        val hasSolution: Boolean
            get() = objective != Long.MIN_VALUE

        /**
         * This solve as a SOUND upper bound of its model's optimum — the only way proof code may read it.
         * OPTIMAL → the objective; FEASIBLE → the (ceiled) dual; UNKNOWN → the dual only when the native
         * bound was actually set: a solve stopped or timed out inside presolve reports the proto default 0
         * (or a negative sentinel), which read as a bound would "close" the world it should have left open
         * (the 2026-07-18 MODEL_INVALID incident, see the B&B node guard); INFEASIBLE → [ifInfeasible]
         * (MIN_VALUE where an empty world is meaningful, the MAX_VALUE default where emptiness contradicts a
         * known feasible build); anything else (MODEL_INVALID…) → Long.MAX_VALUE.
         */
        fun soundUpper(ifInfeasible: Long = Long.MAX_VALUE): Long =
            when (status) {
                "OPTIMAL" -> objective
                "FEASIBLE" -> bestBound.takeIf { it >= objective } ?: Long.MAX_VALUE
                "UNKNOWN" -> bestBound.takeIf { it > 0L } ?: Long.MAX_VALUE
                "INFEASIBLE" -> ifInfeasible
                else -> Long.MAX_VALUE
            }
    }

    /** One outer-approximation step of [conditionalRefinementProfileForTest]. */
    internal data class ConditionalRefinementIteration(
        val iteration: Int,
        val status: String,
        val objective: Long,
        val bestBound: Long,
        val relaxedConditionIds: Set<Int>,
        val selectedSublimationStateIds: Set<Int>,
        val newlyEnforcedConditionIds: Set<Int>,
        val relaxedWallTimeSec: Double,
        val exactValidationStatus: String,
        val exactValidationWallTimeSec: Double,
    )

    /**
     * Research-only structural encoding for build-static sublimations.
     *
     * Start with every choosable condition removed, solve that upper model to optimality, then pin
     * its complete decision assignment in the exact model. If the exact model rejects it, restore
     * the exact gates of every selected condition-bearing sub and rebuild. Each failed iteration
     * activates at least one previously relaxed condition, so the loop converges in at most
     * `conditional sub count + 1` solves. When the pinned assignment is exact-feasible, its relaxed
     * objective is achievable in the exact model and equals the upper model's optimum: that is a
     * proof of the exact global optimum.
     *
     * This deliberately lives behind a test seam until its behaviour on the full S4 shape is known.
     */
    internal fun conditionalRefinementProfileForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        workers: Int,
        secondsPerIteration: Double,
        maxIterations: Int,
        applyDomination: Boolean,
    ): Pair<Boolean, List<ConditionalRefinementIteration>> {
        val conditionalIds =
            sublimations
                .asSequence()
                .filter {
                    it.solverChoosable &&
                        it.condition?.type in SUPPORTED_SUB_CONDITIONS
                }.mapTo(linkedSetOf()) { it.stateId }
        val enforcedIds = linkedSetOf<Int>()
        val iterations = arrayListOf<ConditionalRefinementIteration>()

        repeat(maxIterations) { index ->
            val mixedSubs =
                sublimations.map { sub ->
                    if (sub.stateId in enforcedIds) sub else sub.withRelaxedBuildStaticCondition()
                }
            val relaxedBuilt =
                buildModel(
                    params,
                    equipmentsByItemType,
                    runes,
                    mixedSubs,
                    applyDomination = applyDomination
                )
            val relaxedSolver = CpSolver()
            relaxedSolver.parameters.linearizationLevel = 2
            relaxedSolver.parameters.maxPresolveIterations = 3
            relaxedSolver.parameters.numSearchWorkers = workers
            relaxedSolver.parameters.randomSeed = 1
            relaxedSolver.parameters.maxTimeInSeconds = secondsPerIteration
            val relaxedStatus = relaxedSolver.solve(relaxedBuilt.model)
            val relaxedHasSolution =
                relaxedStatus == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                    relaxedStatus == com.google.ortools.sat.CpSolverStatus.FEASIBLE
            require(relaxedHasSolution) { "conditional refinement upper solve $index has no solution: $relaxedStatus" }
            val relaxedVars = diagnosticVars(relaxedBuilt)
            val assignment = relaxedVars.associate { it.name to relaxedSolver.value(it) }
            val selectedSubIds =
                relaxedBuilt.subModel.subVars
                    .filterValues { relaxedSolver.value(it) > 0L }
                    .keys
                    .mapTo(linkedSetOf()) { it.stateId }
            if (relaxedStatus != com.google.ortools.sat.CpSolverStatus.OPTIMAL) {
                iterations +=
                    ConditionalRefinementIteration(
                        iteration = index + 1,
                        status = relaxedStatus.toString(),
                        objective = kotlin.math.round(relaxedSolver.objectiveValue()).toLong(),
                        bestBound = kotlin.math.ceil(relaxedSolver.bestObjectiveBound()).toLong(),
                        relaxedConditionIds = conditionalIds - enforcedIds,
                        selectedSublimationStateIds = selectedSubIds,
                        newlyEnforcedConditionIds = emptySet(),
                        relaxedWallTimeSec = relaxedSolver.wallTime(),
                        exactValidationStatus = "NOT_RUN",
                        exactValidationWallTimeSec = 0.0
                    )
                return false to iterations
            }

            // Domination is shape-dependent: the relaxed sub effects can retain a different
            // item pool than the exact effects. Validate against models built on the already-
            // selected relaxed pool so every decision name remains identical.
            fun validatePinned(validationSubs: List<Sublimation>): Pair<com.google.ortools.sat.CpSolverStatus, CpSolver> {
                val validationBuilt =
                    buildModel(
                        params,
                        relaxedBuilt.allEquips.groupBy { it.itemType },
                        runes,
                        validationSubs,
                        forceFullPool = true,
                        applyDomination = false
                    )
                val validationVars = diagnosticVars(validationBuilt)
                val validationNames = validationVars.mapTo(linkedSetOf()) { it.name }
                require(validationNames == assignment.keys) {
                    "relaxed/exact decision-variable drift: relaxedOnly=${assignment.keys - validationNames}, " +
                        "exactOnly=${validationNames - assignment.keys}"
                }
                validationVars.forEach { validationBuilt.model.addEquality(it, assignment.getValue(it.name)) }
                val validationSolver = CpSolver()
                validationSolver.parameters.linearizationLevel = 2
                validationSolver.parameters.maxPresolveIterations = 3
                validationSolver.parameters.numSearchWorkers = 1
                validationSolver.parameters.randomSeed = 1
                validationSolver.parameters.maxTimeInSeconds = secondsPerIteration.coerceAtMost(30.0)
                return validationSolver.solve(validationBuilt.model) to validationSolver
            }

            val (exactStatus, exactSolver) = validatePinned(sublimations)
            val exactFeasible =
                exactStatus == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                    exactStatus == com.google.ortools.sat.CpSolverStatus.FEASIBLE

            val newlyEnforced =
                if (exactFeasible) {
                    emptySet()
                } else {
                    require(exactStatus == com.google.ortools.sat.CpSolverStatus.INFEASIBLE) {
                        "conditional refinement validation is inconclusive: $exactStatus"
                    }
                    val candidates =
                        selectedSubIds
                            .asSequence()
                            .filter { it in conditionalIds && it !in enforcedIds }
                            .toCollection(linkedSetOf())
                    require(candidates.isNotEmpty()) {
                        "exact assignment is infeasible but no selected relaxed condition can explain it; " +
                            "selected=$selectedSubIds enforced=$enforcedIds"
                    }
                    // Identify the actual offenders instead of reifying every selected condition.
                    // Each probe restores ONE candidate on top of the already-enforced set while
                    // all decisions are pinned, so presolve normally decides it immediately.
                    // If no condition fails alone (an interaction), conservatively restore all.
                    val individuallyViolated =
                        candidates
                            .asSequence()
                            .filter { candidateId ->
                                val probeSubs =
                                    sublimations.map { sub ->
                                        if (sub.stateId in enforcedIds || sub.stateId == candidateId) {
                                            sub
                                        } else {
                                            sub.withRelaxedBuildStaticCondition()
                                        }
                                    }
                                val (probeStatus, _) = validatePinned(probeSubs)
                                require(
                                    probeStatus == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                                        probeStatus == com.google.ortools.sat.CpSolverStatus.FEASIBLE ||
                                        probeStatus == com.google.ortools.sat.CpSolverStatus.INFEASIBLE
                                ) { "single-condition validation is inconclusive for $candidateId: $probeStatus" }
                                probeStatus == com.google.ortools.sat.CpSolverStatus.INFEASIBLE
                            }.toCollection(linkedSetOf())
                    individuallyViolated.ifEmpty { candidates }
                }
            iterations +=
                ConditionalRefinementIteration(
                    iteration = index + 1,
                    status = relaxedStatus.toString(),
                    objective = kotlin.math.round(relaxedSolver.objectiveValue()).toLong(),
                    bestBound = kotlin.math.ceil(relaxedSolver.bestObjectiveBound()).toLong(),
                    relaxedConditionIds = conditionalIds - enforcedIds,
                    selectedSublimationStateIds = selectedSubIds,
                    newlyEnforcedConditionIds = newlyEnforced,
                    relaxedWallTimeSec = relaxedSolver.wallTime(),
                    exactValidationStatus = exactStatus.toString(),
                    exactValidationWallTimeSec = exactSolver.wallTime()
                )
            if (exactFeasible) {
                require(exactSolver.objectiveValue().toLong() == relaxedSolver.objectiveValue().toLong()) {
                    "exact-feasible assignment changed objective: relaxed=${relaxedSolver.objectiveValue()}, " +
                        "exact=${exactSolver.objectiveValue()}"
                }
                return true to iterations
            }
            newlyEnforced.forEach { enforcedIds += it }
        }
        return false to iterations
    }

    internal data class ConditionalWorldBranchRead(
        val node: Int,
        val requiredConditionIds: Set<Int>,
        val excludedConditionIds: Set<Int>,
        val status: String,
        val objective: Long,
        val bestBound: Long,
        val selectedSublimationStateIds: Set<Int>,
        val branchedOnStateId: Int?,
        val wallTimeSec: Double,
        val deterministicTime: Double,
        val disposition: String,
    )

    internal sealed interface ConditionalWorldProof {
        val reads: List<ConditionalWorldBranchRead>

        /** Every leaf has a sound dual at most [upper]. */
        data class Proven(
            val upper: Long,
            override val reads: List<ConditionalWorldBranchRead>,
        ) : ConditionalWorldProof

        /** The bounded run stopped; [upper] still soundly covers every open leaf. */
        data class Inconclusive(
            val upper: Long,
            override val reads: List<ConditionalWorldBranchRead>,
        ) : ConditionalWorldProof

        /** A completely pinned assignment is feasible in the exact model above the proposed incumbent. */
        data class Counterexample(
            val objective: Long,
            override val reads: List<ConditionalWorldBranchRead>,
        ) : ConditionalWorldProof
    }

    // A tree whose ROOT stayed FEASIBLE (dual within the bail band but unproven) gets this
    // reduced budget: it sometimes closes (worth a chance) but often crawls to the full budget
    // and pays the DP fall-through on top.
    private const val UNPROVEN_ROOT_TREE_BUDGET_SECONDS = 75.0

    // Two-node prognosis (§9.36, data-backed): the root alone cannot discriminate a crawling
    // tree (enutrof and cra80 share a +14% root dual, but only cra80 closes) — the cumulative
    // DETERMINISTIC cost of the root + its first child does (enutrof 142 vs cra80 70-118).
    private const val TWO_NODE_DET_BAIL = 130.0

    // Consecutive full-budget nodes contributing NOTHING (dual == inherited) mark a walled
    // branch class (enutrof's `7115` chain produced four in a row) — terminate at this count.
    private const val ZERO_PROGRESS_STALL_LIMIT = 2

    /**
     * Research-only external branch-and-bound over conditional-sub selection. For an invalid
     * relaxed assignment using `s`, children `{s = 0}` and `{s = 1, condition(s) exact}` partition
     * every exact build in the parent. A node is discarded as soon as its sound upper is at most
     * [incumbentObjective], avoiding a monolithic model containing every indicator.
     */
    internal fun conditionalWorldBranchAndBound(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        incumbentObjective: Long,
        workers: Int,
        totalSeconds: Double,
        maxSecondsPerNode: Double,
        deterministicLimitPerNode: Double? = null,
        interleave: Boolean = false,
        maxNodes: Int,
        applyDomination: Boolean,
        requiredFirst: Boolean = false,
        // Root prognosis (generality-matrix fix, 2026-07-18): when the ROOT node's dual exceeds
        // incumbent × (1 + fraction), the reification wall makes closure hopeless in any bounded
        // budget (iop110-full: root +142% vs cra80-ap10's +14% which closes) — bail after the
        // single root solve so the caller can fall through to the DP union instead.
        rootBailFraction: Double? = null,
        shouldContinue: () -> Boolean = { true },
    ): ConditionalWorldProof {
        data class Node(
            val enforced: Set<Int> = emptySet(),
            val required: Set<Int> = emptySet(),
            val excluded: Set<Int> = emptySet(),
            val inheritedUpper: Long = Long.MAX_VALUE,
            // Parent's mixed-solve assignment, used as a CP-SAT solution hint: child models share
            // most of the structure, so the parent primal seeds a strong incumbent immediately.
            // Hints may violate the child's fixes — CP-SAT repairs them; soundness is unaffected.
            val hint: Map<String, Long>? = null,
        )

        val subByStateId = sublimations.associateBy { it.stateId }
        val candidateConditionalIds =
            sublimations
                .asSequence()
                .filter { it.solverChoosable && it.condition?.type in SUPPORTED_SUB_CONDITIONS }
                .mapTo(linkedSetOf()) { it.stateId }
        // Sibling nodes are independent CP solves and small models scale poorly past ~4 CP
        // workers, so two concurrent nodes at workers/2 beat one node at full width. All mutable
        // tree state below is guarded by [lock]; in-flight nodes stay part of the open frontier
        // so a timeout that fires mid-solve still reports a sound inconclusive upper.
        val lock = Object()
        val queue = java.util.ArrayDeque<Node>()
        queue.add(Node())
        val inFlight = java.util.IdentityHashMap<Node, Unit>()
        val reads = arrayListOf<ConditionalWorldBranchRead>()
        val startedAt = System.nanoTime()
        var deadline = startedAt + (totalSeconds.coerceAtLeast(0.0) * 1_000_000_000.0).toLong()
        var closedUpper = Long.MIN_VALUE
        var nodesTaken = 0
        var terminal: ConditionalWorldProof? = null
        var zeroProgressStreak = 0

        fun secondsRemaining(): Double = ((deadline - System.nanoTime()).coerceAtLeast(0L) / 1_000_000_000.0)

        // Callers must hold [lock].
        fun frontierUpper(currentUpper: Long? = null): Long =
            sequenceOf(currentUpper ?: Long.MIN_VALUE)
                .plus(queue.asSequence().map { it.inheritedUpper })
                .plus(inFlight.keys.asSequence().map { it.inheritedUpper })
                .maxOrNull() ?: Long.MAX_VALUE

        // Callers must hold [lock].
        fun inconclusive(currentUpper: Long? = null): ConditionalWorldProof.Inconclusive =
            ConditionalWorldProof.Inconclusive(
                upper = maxOf(closedUpper, frontierUpper(currentUpper)),
                reads = reads.toList()
            )

        fun soundIntegralUpper(value: Double): Long =
            when {
                value.isNaN() || value == Double.POSITIVE_INFINITY -> Long.MAX_VALUE
                value == Double.NEGATIVE_INFINITY -> Long.MIN_VALUE
                value >= Long.MAX_VALUE.toDouble() -> Long.MAX_VALUE
                value <= Long.MIN_VALUE.toDouble() -> Long.MIN_VALUE
                else -> kotlin.math.ceil(value).toLong()
            }

        // 2 node-workers × workers/2 CP threads. Measured at cra80-ap10 (2026-07-18): 4×2 was a
        // REGRESSION (tree stopped closing — 2 CP threads cannot close the required-nodes that
        // 4 threads prove in 8-14 s, and the machine is CPU-bound at 2×4 anyway).
        val nodeWorkerCount = if (workers >= 4) 2 else 1
        val cpWorkersPerNode = maxOf(1, workers / nodeWorkerCount)

        fun configure(
            solver: CpSolver,
            seconds: Double,
        ) {
            solver.parameters.linearizationLevel = 2
            solver.parameters.maxPresolveIterations = 3
            solver.parameters.numSearchWorkers = cpWorkersPerNode
            solver.parameters.randomSeed = 1
            solver.parameters.maxTimeInSeconds = seconds
            deterministicLimitPerNode?.let { solver.parameters.maxDeterministicTime = it }
            solver.parameters.interleaveSearch = interleave
        }

        // Perf-only heuristic (soundness is independent of the choice): branch first on the
        // relaxed credit with the largest rough max-damage marginal. This approximates one
        // strong-branching decision without solving 2×candidate child probes.
        fun branchImpact(stateId: Int): Long {
            val sub = subByStateId.getValue(stateId)
            val flatImpact =
                sub.effects.filterIsInstance<SublimationEffect.StatEffect>().sumOf { effect ->
                    val weight =
                        when (effect.characteristic) {
                            Characteristic.ACTION_POINT -> 100_000L
                            Characteristic.MOVEMENT_POINT -> 50_000L
                            Characteristic.RANGE -> 20_000L
                            Characteristic.DAMAGE_INFLICTED -> 10_000L
                            Characteristic.CRITICAL_HIT, Characteristic.BLOCK_PERCENTAGE -> 1_000L
                            else -> 1L
                        }
                    kotlin.math.abs(effect.magnitudeAtLevel(params.character.level).toLong()) * weight
                }
            return flatImpact + (sub.conversion?.percent?.toLong() ?: 0L) * 1_000L
        }

        // A processed node's effect on the shared tree, applied atomically by the driver: the
        // read id is assigned under [lock], children keep the serial pop order, and a terminal
        // outcome (counterexample / inconclusive) wins over any concurrent sibling.
        class NodeOutcome(
            val read: ((Int) -> ConditionalWorldBranchRead)? = null,
            val children: List<Node> = emptyList(),
            val closedContribution: Long? = null,
            val counterexample: Long? = null,
            val inconclusiveUpper: Long? = null,
            val isInconclusive: Boolean = false,
        )

        fun processNode(node: Node): NodeOutcome {
            val mixedSubs =
                sublimations.map { sub ->
                    if (sub.stateId in node.enforced) sub else sub.withRelaxedBuildStaticCondition()
                }
            val built =
                buildModel(
                    params,
                    equipmentsByItemType,
                    runes,
                    mixedSubs,
                    // Sound outer-bound chain: exact-node optimum ≤ mixed-node optimum.
                    // [dominationShape] pins every stat read by the conditions that remain exact,
                    // so domination preserves the mixed optimum; stripped conditions need no pin.
                    // Therefore mixed dominated optimum still upper-bounds the exact node.
                    applyDomination = applyDomination
                )

            fun applyNodeFixes(target: BuiltModel) {
                val varsById =
                    target.subModel.subVars.entries
                        .associate { it.key.stateId to it.value }
                node.required.forEach { stateId -> target.model.addEquality(varsById.getValue(stateId), 1L) }
                node.excluded.forEach { stateId -> target.model.addEquality(varsById.getValue(stateId), 0L) }
            }
            applyNodeFixes(built)
            // NOTE (measured 2026-07-18): posting `objective ≤ inheritedUpper` on the mixed node
            // is sound but SLOWER (161 s vs 140 s tree closure at cra80-ap10) — consistent with
            // the §9.22 "objective caps in either direction" do-not-retry.
            node.hint?.let { hint ->
                val seen = HashSet<String>()
                diagnosticVars(built).forEach { v ->
                    if (seen.add(v.name)) hint[v.name]?.let { built.model.addHint(v, it) }
                }
            }
            val solver = CpSolver()
            // The ROOT node's read drives the route prognosis: give it headroom (production
            // measured cra80's root closing at 11-13 s — a 15 s cap left no thermal margin and a
            // loose FEASIBLE root dual triggered a FALSE bail).
            val nodeBudget =
                if (rootBailFraction != null && node.required.isEmpty() && node.excluded.isEmpty()) {
                    maxOf(maxSecondsPerNode, 30.0)
                } else {
                    maxSecondsPerNode
                }
            configure(solver, minOf(nodeBudget, secondsRemaining()).coerceAtLeast(0.001))
            // The prognosis-driving ROOT gets double the deterministic budget (its read decides
            // the whole route); deterministic budgets are load-invariant where wall budgets went
            // erratic under bench load.
            if (rootBailFraction != null && node.required.isEmpty() && node.excluded.isEmpty()) {
                deterministicLimitPerNode?.let { solver.parameters.maxDeterministicTime = it * 2 }
            } else if (rootBailFraction != null && deterministicLimitPerNode != null) {
                // The SECOND node only needs to answer the two-node prognosis: cap it at the
                // remaining prognosis budget (a cheaper read that would trigger the bail anyway).
                val rootDet = synchronized(lock) { if (reads.size == 1) reads.first().deterministicTime else null }
                if (rootDet != null) {
                    solver.parameters.maxDeterministicTime =
                        minOf(deterministicLimitPerNode, (TWO_NODE_DET_BAIL - rootDet + 1.0).coerceAtLeast(10.0))
                }
            }
            // A cancelled proof stops the node within a tick too (pre-release review: B&B nodes ran
            // their full 45 s budget after a cancel). The stopped read is FEASIBLE/UNKNOWN — handled below.
            val status = withStopWatcher(solver, shouldContinue) { solver.solve(built.model) }
            // ONLY these three statuses carry usable information. Anything else (MODEL_INVALID,
            // UNKNOWN with a garbage native bound, …) must end the tree inconclusively — routing
            // it through the prune test once turned a MODEL_INVALID node's bound=0 into a fake
            // closed contribution (caught by the production self-check, 2026-07-18).
            val hasSolution =
                status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                    status == com.google.ortools.sat.CpSolverStatus.FEASIBLE
            if (!hasSolution && status != com.google.ortools.sat.CpSolverStatus.INFEASIBLE) {
                return NodeOutcome(isInconclusive = true, inconclusiveUpper = node.inheritedUpper)
            }
            // The model objective is integral, but OR-Tools exposes both values as Double. A proof path must
            // never floor a dual that happens to arrive as 100.999999999: ceil is the conservative integral
            // upper bound. Conversely, a complete primal assignment has an integral objective, so round it.
            val objective = if (hasSolution) kotlin.math.round(solver.objectiveValue()).toLong() else Long.MIN_VALUE
            val solverUpper =
                if (status == com.google.ortools.sat.CpSolverStatus.OPTIMAL && hasSolution) {
                    objective
                } else {
                    soundIntegralUpper(solver.bestObjectiveBound())
                }
            // The exact/mixed child feasible set is a subset of its parent world. A short child
            // solve can expose a numerically WORSE dual than its already-solved parent, but the
            // parent's bound remains valid for every descendant. Intersect them for free.
            val upper = minOf(solverUpper, node.inheritedUpper)
            val branchableIds =
                built.subModel.subVars.keys
                    .asSequence()
                    .map { it.stateId }
                    .filterTo(linkedSetOf()) { it in candidateConditionalIds }
            val selectedIds =
                if (hasSolution) {
                    built.subModel.subVars
                        .filterValues { solver.value(it) > 0L }
                        .keys
                        .mapTo(linkedSetOf()) { it.stateId }
                } else {
                    emptySet()
                }

            if (status == com.google.ortools.sat.CpSolverStatus.INFEASIBLE || upper <= incumbentObjective) {
                return NodeOutcome(
                    read = { id ->
                        ConditionalWorldBranchRead(
                            id,
                            node.required,
                            node.excluded,
                            status.toString(),
                            objective,
                            upper,
                            selectedIds,
                            null,
                            solver.wallTime(),
                            deterministicTimeFrom(solver.responseStats()),
                            "PRUNED"
                        )
                    },
                    closedContribution = if (status != com.google.ortools.sat.CpSolverStatus.INFEASIBLE) upper else null
                )
            }
            if (!hasSolution) {
                return NodeOutcome(
                    isInconclusive = true,
                    inconclusiveUpper = if (upper == Long.MIN_VALUE) node.inheritedUpper else upper
                )
            }
            val assignment = diagnosticVars(built).associate { it.name to solver.value(it) }

            fun validatePinned(validationSubs: List<Sublimation>): Pair<com.google.ortools.sat.CpSolverStatus, Long> {
                val validation =
                    buildModel(
                        params,
                        built.allEquips.groupBy { it.itemType },
                        runes,
                        validationSubs,
                        forceFullPool = true,
                        applyDomination = false
                    )
                applyNodeFixes(validation)
                val vars = diagnosticVars(validation)
                require(vars.mapTo(linkedSetOf()) { it.name } == assignment.keys)
                vars.forEach { validation.model.addEquality(it, assignment.getValue(it.name)) }
                val validationSolver = CpSolver()
                validationSolver.parameters.numSearchWorkers = 1
                validationSolver.parameters.maxTimeInSeconds = minOf(10.0, secondsRemaining()).coerceAtLeast(0.001)
                val validationStatus = validationSolver.solve(validation.model)
                val validationHasSolution =
                    validationStatus == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                        validationStatus == com.google.ortools.sat.CpSolverStatus.FEASIBLE
                return validationStatus to
                    if (validationHasSolution) {
                        kotlin.math.round(validationSolver.objectiveValue()).toLong()
                    } else {
                        Long.MIN_VALUE
                    }
            }

            val (exactStatus, exactObjective) = validatePinned(sublimations)
            if (exactStatus == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                exactStatus == com.google.ortools.sat.CpSolverStatus.FEASIBLE
            ) {
                val remainingIds = branchableIds.filter { it !in node.enforced && it !in node.excluded }
                val fallbackBranchId = remainingIds.maxByOrNull(::branchImpact)
                val disposition =
                    when {
                        exactObjective > incumbentObjective -> "COUNTEREXAMPLE"
                        fallbackBranchId != null -> "BRANCH_VALID"
                        else -> "INCONCLUSIVE"
                    }
                val readBuilder = { id: Int ->
                    ConditionalWorldBranchRead(
                        id,
                        node.required,
                        node.excluded,
                        status.toString(),
                        objective,
                        upper,
                        selectedIds,
                        fallbackBranchId,
                        solver.wallTime(),
                        deterministicTimeFrom(solver.responseStats()),
                        disposition
                    )
                }
                return when (disposition) {
                    "BRANCH_VALID" -> {
                        val branchId = requireNotNull(fallbackBranchId)
                        val requiredChild =
                            node.copy(
                                enforced = node.enforced + branchId,
                                required = node.required + branchId,
                                inheritedUpper = upper,
                                hint = assignment
                            )
                        val excludedChild =
                            node.copy(excluded = node.excluded + branchId, inheritedUpper = upper, hint = assignment)
                        NodeOutcome(
                            read = readBuilder,
                            children =
                                if (requiredFirst) {
                                    listOf(requiredChild, excludedChild)
                                } else {
                                    listOf(excludedChild, requiredChild)
                                }
                        )
                    }
                    "COUNTEREXAMPLE" -> NodeOutcome(read = readBuilder, counterexample = exactObjective)
                    else -> NodeOutcome(read = readBuilder, isInconclusive = true, inconclusiveUpper = upper)
                }
            }
            if (exactStatus != com.google.ortools.sat.CpSolverStatus.INFEASIBLE) {
                return NodeOutcome(isInconclusive = true, inconclusiveUpper = upper)
            }

            val candidates =
                selectedIds
                    .asSequence()
                    .filter { it in branchableIds && it !in node.enforced && it !in node.excluded }
                    .toList()
            if (candidates.isEmpty()) return NodeOutcome(isInconclusive = true, inconclusiveUpper = upper)
            // Any selected conditional gives the exhaustive `{s=0 | s=1+exact-condition}` split.
            // Earlier code rebuilt and solved one pinned model PER candidate merely to prefer an
            // individually violated gate. That was soundness-neutral pseudo strong branching and
            // consumed a large, unreported share of the total wall budget on full catalogs.
            // k-ary SOS partition over the whole selected-conditional support: children
            // {c1 required} ∪ {c1 excluded, c2 required} ∪ … ∪ {all k excluded}. Identical
            // coverage to iterating the binary split, but the k independent required-children
            // exist IMMEDIATELY — the binary chain kept the frontier 1-2 nodes wide, which
            // starved the node-workers (measured 1.2× on 2 workers at cra80-ap10).
            val ordered = candidates.sortedByDescending(::branchImpact)
            val branchId = ordered.first()
            val readBuilder = { id: Int ->
                ConditionalWorldBranchRead(
                    id,
                    node.required,
                    node.excluded,
                    status.toString(),
                    objective,
                    upper,
                    selectedIds,
                    branchId,
                    solver.wallTime(),
                    deterministicTimeFrom(solver.responseStats()),
                    "BRANCH"
                )
            }
            val children = mutableListOf<Node>()
            val runningExcluded = mutableSetOf<Int>()
            for (candidate in ordered) {
                children +=
                    node.copy(
                        enforced = node.enforced + candidate,
                        required = node.required + candidate,
                        excluded = node.excluded + runningExcluded,
                        inheritedUpper = upper,
                        hint = assignment
                    )
                runningExcluded += candidate
            }
            children += node.copy(excluded = node.excluded + runningExcluded, inheritedUpper = upper, hint = assignment)
            if (!requiredFirst) children.reverse()
            return NodeOutcome(read = readBuilder, children = children)
        }

        fun workerLoop() {
            while (true) {
                val node: Node? =
                    synchronized(lock) {
                        when {
                            terminal != null -> return
                            queue.isEmpty() && inFlight.isEmpty() -> return
                            !shouldContinue() || secondsRemaining() <= 0.0 -> {
                                terminal = inconclusive()
                                return
                            }
                            queue.isEmpty() -> null
                            nodesTaken >= maxNodes ->
                                if (inFlight.isEmpty()) {
                                    terminal = inconclusive()
                                    return
                                } else {
                                    null
                                }
                            else -> {
                                nodesTaken++
                                queue.removeFirst().also { inFlight[it] = Unit }
                            }
                        }
                    }
                if (node == null) {
                    Thread.sleep(5)
                    continue
                }
                val outcome =
                    try {
                        processNode(node)
                    } catch (t: Throwable) {
                        synchronized(lock) {
                            inFlight.remove(node)
                            if (terminal == null) terminal = inconclusive(node.inheritedUpper)
                        }
                        throw t
                    }
                synchronized(lock) {
                    inFlight.remove(node)
                    outcome.read?.let { read ->
                        val r = read(reads.size + 1)
                        reads += r
                        if (rootBailFraction != null && terminal == null) {
                            // Two-node prognosis: nodes priced beyond any closable budget.
                            if (reads.size == 2 && reads.sumOf { it.deterministicTime } > TWO_NODE_DET_BAIL) {
                                terminal = inconclusive(reads.first().bestBound)
                            }
                            // Walled-branch class: FULL-budget reads contributing nothing. The
                            // spent-budget condition keeps fast FEASIBLE reads of a closing tree
                            // from counting.
                            val spentBudget = deterministicLimitPerNode?.let { r.deterministicTime >= it * 0.9 } ?: (r.wallTimeSec >= maxSecondsPerNode * 0.9)
                            zeroProgressStreak =
                                if (r.status == "FEASIBLE" && r.bestBound >= node.inheritedUpper && spentBudget) zeroProgressStreak + 1 else 0
                            if (zeroProgressStreak >= ZERO_PROGRESS_STALL_LIMIT && terminal == null) {
                                terminal = inconclusive(r.bestBound)
                            }
                        }
                    }
                    // Root prognosis: a hopeless root dual means no bounded budget will close the
                    // tree — end here (one node's cost) so the caller falls through to the DP.
                    if (rootBailFraction != null && reads.size == 1 && terminal == null) {
                        val root = reads.first()
                        if (root.bestBound > incumbentObjective + (incumbentObjective.toDouble() * rootBailFraction).toLong()) {
                            terminal = inconclusive(root.bestBound)
                        } else if (root.status != "OPTIMAL") {
                            // The root dual is within the band but the root itself did not CLOSE:
                            // such trees sometimes finish (worth a chance) but often crawl
                            // (enutrof125 burned the full 180 s then paid the DP anyway =
                            // 324 s). Shrink the tree budget — proven-root trees (cra80/cra140)
                            // keep the full one.
                            deadline =
                                minOf(
                                    deadline,
                                    System.nanoTime() + (UNPROVEN_ROOT_TREE_BUDGET_SECONDS * 1_000_000_000.0).toLong()
                                )
                        }
                    }
                    outcome.closedContribution?.let { closedUpper = maxOf(closedUpper, it) }
                    when {
                        outcome.counterexample != null -> {
                            if (terminal == null) {
                                terminal = ConditionalWorldProof.Counterexample(outcome.counterexample, reads.toList())
                            }
                        }
                        outcome.isInconclusive -> {
                            if (terminal == null) terminal = inconclusive(outcome.inconclusiveUpper)
                        }
                        else -> outcome.children.asReversed().forEach { queue.addFirst(it) }
                    }
                }
            }
        }

        if (nodeWorkerCount == 1) {
            workerLoop()
        } else {
            val pool =
                java.util.concurrent.Executors
                    .newFixedThreadPool(nodeWorkerCount - 1)
            try {
                val extras =
                    (1 until nodeWorkerCount).map {
                        pool.submit(java.util.concurrent.Callable { workerLoop() })
                    }
                workerLoop()
                extras.forEach { it.get() }
            } finally {
                pool.shutdownNow()
            }
        }
        synchronized(lock) {
            terminal?.let { return it }
            return if (queue.isEmpty() && inFlight.isEmpty()) {
                ConditionalWorldProof.Proven(closedUpper, reads.toList())
            } else {
                inconclusive()
            }
        }
    }

    /** Compatibility seam for the manual S4 harness while the bounded engine graduates to production. */
    internal fun conditionalWorldBranchAndBoundForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        incumbentObjective: Long,
        workers: Int,
        secondsPerNode: Double,
        deterministicLimitPerNode: Double? = null,
        interleave: Boolean = false,
        maxNodes: Int,
        applyDomination: Boolean,
    ): Pair<Boolean, List<ConditionalWorldBranchRead>> {
        val result =
            conditionalWorldBranchAndBound(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                incumbentObjective,
                workers,
                totalSeconds = secondsPerNode * maxNodes,
                maxSecondsPerNode = secondsPerNode,
                deterministicLimitPerNode = deterministicLimitPerNode,
                interleave = interleave,
                maxNodes = maxNodes,
                applyDomination = applyDomination
            )
        return (result is ConditionalWorldProof.Proven) to result.reads
    }

    internal fun timedMaxDamageProfileForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
        workers: Int,
        seconds: Double,
        applyDomination: Boolean,
        randomSeed: Int = 1,
        experiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig.DEFAULT,
        maxPresolveIterations: Int = 3,
        linearizationLevel: Int = 2,
        deterministicLimit: Double? = null,
        // Pure CP-SAT solver-parameter knobs (no model change) — A/B research only. CP-SAT proves the SAME
        // optimum regardless of these, so they are soundness-safe by construction; the only question they answer
        // is whether the proof closes in less deterministic time. null/false = CP-SAT's default.
        symmetryLevel: Int? = null,
        probingLevel: Int? = null,
        objectiveShaving: Boolean = false,
        searchBranching: Int? = null,
        // Cut on the FINAL soft-penalized objective only. Keep separate from [objectiveCutoff],
        // which is also threaded into perTurnDamageScore as a raw D·Graw cutoff and therefore has
        // different units whenever required-target penalty folding is active.
        penalizedObjectiveCutoff: Long? = null,
        objectiveCutoff: Long? = null,
        // Portfolio-composition research knobs (parameter-only, soundness-safe like the above).
        logSearch: Boolean = false,
        sharedTreeWorkers: Int? = null,
        ignoreSubsolvers: List<String> = emptyList(),
        extraSubsolvers: List<String> = emptyList(),
        interleave: Boolean = false,
        numFullSubsolvers: Int? = null,
        detectLinearizedProduct: Boolean = false,
        // C4: screen the CONSTRAINED hard-leg shape (required targets as `actual ≥ target`, plain objective) — the
        // shape whose bilinear dual gap C6 targets — instead of the soft-penalty relaxation. Default false = today.
        hardConstraints: Boolean = false,
        // Test-only partition oracle: require at least one modeled condition-bearing sublimation.
        // Used to lock the certificate's complementary {no condition | some condition} split.
        requireAnyConditionalSublimation: Boolean = false,
        // Research-only carrier-world seam: force one modeled sublimation while the caller chooses
        // which other conditions stay exact/relaxed in [sublimations].
        requiredSublimationStateId: Int? = null,
        // Multi-carrier variant (per-carrier closure blocker subsets): every listed sub is forced.
        requiredSublimationStateIds: Set<Int> = emptySet(),
        // Cooperative cancellation: polled every 500 ms by a watcher thread that calls
        // CpSolver.stopSearch() so a multi-minute proof solve stops within a second of the caller
        // cancelling (a fresh search must never compete with an abandoned proof for CPU). The
        // stopped solve returns its current status/dual — still sound, and the caller discards it.
        shouldContinue: (() -> Boolean)? = null,
        // Research-only exact-region seam. Bounds are posted on the resolved sheet stats and can
        // therefore be used to validate a DP-complement partition without changing the objective.
        statLowerBounds: Map<Characteristic, Long> = emptyMap(),
        statUpperBounds: Map<Characteristic, Long> = emptyMap(),
        // Frontier-region coverage pins (review fix 2026-07-20). The soft certificate's DP cells
        // are keyed by CREDITED stats (negative item lines clamped at 0, negative sub lines
        // dropped), so covering "every build whose state maps into a cell" needs pins on the
        // CREDITED value, not the real sheet stat: credited = real + Σ elided debits, an exact
        // per-build identity, posted as `actualStat + Σ debit_e·x_e + Σ debit_s·s ≥ v`. The
        // [creditedHpPctFactor] over-scales HP debits by the maximum hp% multiplier (the DP
        // multiplies CREDITED flats, so the elided gap can exceed the raw debit — over-relaxing
        // the debit side keeps the pin implied by coverage, i.e. sound).
        creditedStatLowerBounds: Map<Characteristic, Long> = emptyMap(),
        creditedHpPctFactor: Int = 100,
        // Research-only provenance validator: pin the discrete equipment/sub assignment exposed
        // by a certificate path, while leaving runes and skills free for the exact model to
        // optimize. null means unpinned; an empty map/set deliberately pins every choice to zero.
        pinnedEquipmentIds: Set<Int>? = null,
        pinnedSublimationCopies: Map<Int, Int>? = null,
    ): MaxDamageTimedProfile {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                applyDomination = applyDomination,
                maxDamageExperiment = experiment,
                maxDamageObjectiveCutoff = objectiveCutoff,
                hardConstraints = hardConstraints,
                trackActualStats = true
            )
        (penalizedObjectiveCutoff ?: objectiveCutoff)?.let { built.model.addGreaterOrEqual(built.objective, it) }
        if (requireAnyConditionalSublimation) {
            val conditionalVars =
                built.subModel.subVars
                    .filterKeys { it.condition != null }
                    .values
                    .toTypedArray()
            require(conditionalVars.isNotEmpty()) { "conditional partition requested with no modeled conditional sublimation" }
            built.model.addGreaterOrEqual(LinearExpr.sum(conditionalVars), 1L)
        }
        (requiredSublimationStateIds + listOfNotNull(requiredSublimationStateId)).forEach { stateId ->
            val required =
                built.subModel.subVars.entries
                    .singleOrNull { it.key.stateId == stateId }
                    ?.value
                    ?: error("required sublimation $stateId is not modeled in this world")
            built.model.addEquality(required, 1L)
        }
        statLowerBounds.forEach { (stat, lower) ->
            built.model.addGreaterOrEqual(requireNotNull(built.actualStatVars[stat]) { "unsupported lower-bound stat $stat" }, lower)
        }
        statUpperBounds.forEach { (stat, upper) ->
            built.model.addLessOrEqual(requireNotNull(built.actualStatVars[stat]) { "unsupported upper-bound stat $stat" }, upper)
        }
        creditedStatLowerBounds.forEach { (stat, lower) ->
            val expr = LinearExpr.newBuilder()
            expr.add(requireNotNull(built.actualStatVars[stat]) { "unsupported credited-bound stat $stat" })
            val pctFactor = if (stat == Characteristic.HP) creditedHpPctFactor else 100
            for ((equipment, equipVar) in built.equipVars) {
                val raw = equipment.characteristics[stat] ?: 0
                if (raw < 0) expr.addTerm(equipVar, ((-raw).toLong() * pctFactor + 99) / 100)
            }
            for ((sub, subVar) in built.subModel.subVars) {
                val debit =
                    sub.effects
                        .filterIsInstance<me.chosante.common.SublimationEffect.StatEffect>()
                        .filter { it.characteristic == stat && scenarioGateMatches(it.scenarioGate, params) }
                        .sumOf { (-minOf(it.magnitudeAtLevel(built.subModel.characterLevel), 0)).toLong() }
                if (debit > 0L) {
                    val scaled = (debit * pctFactor + 99) / 100
                    expr.addTerm(subVar, scaled)
                    for (copyVar in built.subModel.copyVars[sub].orEmpty()) expr.addTerm(copyVar, scaled)
                }
            }
            built.model.addGreaterOrEqual(expr, lower)
        }
        pinnedEquipmentIds?.let { selectedIds ->
            built.equipVars.forEach { (equipment, variable) ->
                built.model.addEquality(variable, if (equipment.equipmentId in selectedIds) 1L else 0L)
            }
        }
        pinnedSublimationCopies?.let { selectedCopies ->
            built.subModel.subVars.forEach { (sub, baseVariable) ->
                val variables = (listOf(baseVariable) + built.subModel.copyVars[sub].orEmpty()).toTypedArray()
                built.model.addEquality(LinearExpr.sum(variables), selectedCopies.getOrDefault(sub.stateId, 0).toLong())
            }
        }
        val solver = CpSolver()
        solver.parameters.logSearchProgress = logSearch
        solver.parameters.linearizationLevel = linearizationLevel
        solver.parameters.maxPresolveIterations = maxPresolveIterations
        solver.parameters.numSearchWorkers = workers
        solver.parameters.randomSeed = randomSeed
        if (symmetryLevel != null) solver.parameters.symmetryLevel = symmetryLevel
        if (probingLevel != null) solver.parameters.cpModelProbingLevel = probingLevel
        if (objectiveShaving) solver.parameters.useObjectiveShavingSearch = true
        if (searchBranching != null) {
            solver.parameters.searchBranching =
                com.google.ortools.sat.SatParameters.SearchBranching
                    .forNumber(searchBranching)
        }
        if (sharedTreeWorkers != null) solver.parameters.sharedTreeNumWorkers = sharedTreeWorkers
        ignoreSubsolvers.forEach { solver.parameters.addIgnoreSubsolvers(it) }
        extraSubsolvers.forEach { solver.parameters.addExtraSubsolvers(it) }
        if (interleave) solver.parameters.interleaveSearch = true
        if (numFullSubsolvers != null) solver.parameters.numFullSubsolvers = numFullSubsolvers
        if (detectLinearizedProduct) solver.parameters.detectLinearizedProduct = true
        // Production proves with a DETERMINISTIC-time limit (reproducible, and a much faster solver mode for
        // this problem than a wall-clock limit). When deterministicLimit is set, use it as the real budget and
        // keep maxTimeInSeconds only as a safety cap so a stuck run can't hang.
        if (deterministicLimit != null) {
            solver.parameters.maxDeterministicTime = deterministicLimit
        }
        solver.parameters.maxTimeInSeconds = seconds
        val status = withStopWatcher(solver, shouldContinue) { solver.solve(built.model) }
        if (System.getenv("WAKFU_MAX_DAMAGE_CERT_DEBUG") == "1" &&
            (status == com.google.ortools.sat.CpSolverStatus.OPTIMAL || status == com.google.ortools.sat.CpSolverStatus.FEASIBLE)
        ) {
            val skills =
                built.skillVars.entries
                    .mapNotNull { (ch, v) -> solver.value(v).takeIf { it > 0 }?.let { "${ch.characteristic}=$it" } }
            System.err.println("SOLVE_DEBUG ap=${params.maxDamageApTarget} obj=${solver.objectiveValue().toLong()} skills=$skills")
            val subs =
                built.subModel.subVars.entries
                    .filter { solver.value(it.value) > 0 }
                    .map { it.key.name.en }
            System.err.println("SOLVE_DEBUG_SUBS $subs")
        }
        val hasSolution =
            status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                status == com.google.ortools.sat.CpSolverStatus.FEASIBLE
        val proto = built.model.model()
        val stats = solver.responseStats()
        return MaxDamageTimedProfile(
            status = status.toString(),
            // Review fix (2026-07-20): a complete primal assignment has an integral objective —
            // ROUND it; the dual arrives as a double and must be CEILED, never floored (these
            // values are consumed as sound UPPER authorities by the soft-proof oracles).
            objective = if (hasSolution) kotlin.math.round(solver.objectiveValue()).toLong() else Long.MIN_VALUE,
            bestBound = kotlin.math.ceil(solver.bestObjectiveBound()).toLong(),
            objectiveCutoff = objectiveCutoff,
            wallTimeSec = solver.wallTime(),
            deterministicTime = deterministicTimeFrom(stats),
            booleans = longStatFrom(stats, "booleans"),
            branches = solver.numBranches(),
            conflicts = solver.numConflicts(),
            restarts = longStatFrom(stats, "restarts"),
            lpIterations = longStatFrom(stats, "lp_iterations"),
            variables = proto.variablesCount,
            constraints = proto.constraintsCount,
            poolSize = built.allEquips.size,
            experiment = experiment,
            selectedEquipmentIds =
                if (hasSolution) {
                    built.equipVars
                        .filterValues { solver.value(it) > 0L }
                        .keys
                        .mapTo(linkedSetOf()) { it.equipmentId }
                } else {
                    emptySet()
                },
            selectedSublimationStateIds =
                if (hasSolution) {
                    built.subModel.subVars
                        .filterValues { solver.value(it) > 0L }
                        .keys
                        .mapTo(linkedSetOf()) { it.stateId }
                } else {
                    emptySet()
                },
            selectedSublimationCopies =
                if (hasSolution) {
                    built.subModel.subVars
                        .mapNotNull { (sub, baseVariable) ->
                            val copies =
                                solver.value(baseVariable) +
                                    built.subModel.copyVars[sub]
                                        .orEmpty()
                                        .sumOf(solver::value)
                            sub.stateId.takeIf { copies > 0L }?.let { it to copies }
                        }.toMap()
                } else {
                    emptyMap()
                },
            actualStats =
                if (hasSolution) built.actualStatVars.mapValues { (_, variable) -> solver.value(variable) } else emptyMap(),
            rawObjective =
                if (hasSolution && built.maxDamageRawScore != null) solver.value(built.maxDamageRawScore) else Long.MIN_VALUE
        )
    }

    /**
     * Test-only: assemble the max-damage model like [optimize] and return the EXACT per-AP-cell certifier
     * objective for every AP cell (single-element only). Key = AP, value = the certifier's objective for a
     * build pinned to that AP, or -1 where [certifyMaxPerHitAtAp] bails (the production path falls back to
     * CP-SAT there). One model build certifies every cell, so a soundness / exactness test can compare the
     * certifier against the per-cell CP-SAT optimum (via [timedMaxDamageProfileForTest] with a pinned
     * `maxDamageApTarget`) without rebuilding per AP.
     */
    internal fun certifierCellObjectivesForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
    ): Map<Int, Long> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyAllApForTest = true
        ).certifierObjectivesForTest

    /**
     * Test-only: like [certifierCellObjectivesForTest] but returns BOTH the exact and the FAST tier-1
     * per-cell objectives from a single model build (`exact to fast`). The `fast ≥ exact` soundness lock
     * asserts `fast[a] ≥ exact[a]` for every non-bailed cell — a single violation is a release-blocking
     * under-count (the fast pass would certify a build below a real one).
     */
    internal fun certifierExactAndFastCellObjectivesForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
    ): Pair<Map<Int, Long>, Map<Int, Long>> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyAllApForTest = true
        ).let { it.certifierObjectivesForTest to it.certifierFastObjectivesForTest }

    /**
     * Test-only (B7): the exact, FAST tier-1, and sharpened TIER-1.5 per-cell objectives from a single model build
     * (`Triple(exact, fast, tier15)`). The `fast ≥ tier1.5 ≥ exact` soundness lock asserts the ordering per
     * non-bailed cell — an under-count anywhere (a bound below a real build) is a release-blocking wrong badge.
     */
    internal fun certifierExactFastTier15CellObjectivesForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        // v52: the hard-leg (target-aware) passes — see [StatBuilder.certifierTargetAware].
        targetAware: Boolean = false,
    ): Triple<Map<Int, Long>, Map<Int, Long>, Map<Int, Long>> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyAllApForTest = true,
            certifierTargetAware = targetAware
        ).let { Triple(it.certifierObjectivesForTest, it.certifierFastObjectivesForTest, it.certifierTier15ObjectivesForTest) }

    /**
     * Test-only: the FAST tier-1 per-cell objective ledger computed with [threads] worker threads (P3.1
     * warm-once parallelism). At `threads = 1` this equals [certifierExactAndFastCellObjectivesForTest]'s
     * fast map; the parallel-equality lock asserts it is identical for `threads > 1` on every iteration.
     */
    internal fun certifierFastLedgerForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        threads: Int = 1,
        // v52: the hard-leg (target-aware) pass — see [StatBuilder.certifierTargetAware].
        targetAware: Boolean = false,
    ): Map<Int, Long> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyAllApForTest = true,
            certifyFastThreadsForTest = threads,
            certifyFastOnlyForTest = true,
            certifierTargetAware = targetAware
        ).certifierFastObjectivesForTest

    /**
     * Test-only (v44): the FAST per-cell ledger together with the AUX worlds' share of it — `(fast, aux)`, both in
     * objective units; `aux` is empty when the shape has no aux world ([certifierAuxWorlds]). Lets a harness see
     * whether the secondary-capped / block-assumed worlds BIND (aux ≥ the normal worlds) on a real shape.
     */
    internal fun certifierFastAndAuxCellObjectivesForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        threads: Int = 1,
        // v52: the hard-leg (target-aware) pass — see [StatBuilder.certifierTargetAware].
        targetAware: Boolean = false,
    ): Pair<Map<Int, Long>, Map<Int, Long>> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyAllApForTest = true,
            certifyFastThreadsForTest = threads,
            certifyFastOnlyForTest = true,
            certifierTargetAware = targetAware
        ).let { it.certifierFastObjectivesForTest to it.certifierAuxObjectivesForTest }

    /**
     * Test-only (v47): per AP cell, `(relaxed, split)` — the relaxed capped aux world's objective and the max over the
     * exact capped split it stands for ([certifierAuxPlan]); empty when the shape has no relaxed world. The relaxation
     * must dominate the split at every cell (`relaxed ≥ split`, -1 = a bail).
     */
    internal fun certifierAuxRelaxedVsSplitForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
    ): Map<Int, Pair<Long, Long>> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyAllApForTest = true,
            certifyFastThreadsForTest = 1,
            certifyFastOnlyForTest = true
        ).certifierAuxRelaxedVsSplitForTest

    /**
     * Default worker-thread count for the certificate orchestrator (P3.2). Memory-aware (B2): the parallel path
     * is correct (locked by the panel-scale parallel-equality + determinism tests) but each concurrent exact
     * tier-2 DP holds ~1 GB of frontier state at level 245, so fanning the 6 worlds of a survivor cell across
     * threads OOMs a stock ~4 GB heap. Rather than ship serial-by-default forever, [certifierThreadsForHeap]
     * bounds the worker count by the runtime's free heap: a stock ~4 GiB heap still resolves to 1 (the previous
     * safe default), while a large heap opens the parallel tier for a ~2–3× faster badge. No `CERTIFIER_VERSION`
     * bump — the thread count changes no bound value (the merge is an order-independent max; determinism locked).
     */
    internal fun certifierDefaultThreads(): Int = certifierThreadsForHeap(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors())

    /**
     * Pure, total heap→worker-count formula behind [certifierDefaultThreads] (extracted so it is unit-testable
     * without a specific `-Xmx`). Reserve ~2 GiB for the model + warm fast-pass caches + base app, then allow one
     * worker per ~1.25 GiB of the remainder (each concurrent world-DP is ~1 GB + headroom), capped at
     * `min(6, cores − 1)` to leave the UI/OS a core. Floors at 1 (never zero, even on a tiny heap). A −Xmx4g heap
     * resolves to 1 (2 GiB remainder / 1.25 GiB = 1) — the safe serial default; ≥ ~5.5 GiB opens a second worker.
     */
    internal fun certifierThreadsForHeap(
        maxMemoryBytes: Long,
        cores: Int,
    ): Int {
        val gib = 1024L * 1024 * 1024
        val reserved = 2 * gib
        val perWorker = gib + gib / 4 // 1.25 GiB per concurrent exact DP (with headroom)
        val byHeap = ((maxMemoryBytes - reserved) / perWorker).toInt()
        val upper = min(6, maxOf(1, cores - 1))
        return byHeap.coerceIn(1, upper)
    }

    /**
     * Worker count for the TIER-1.5 pass specifically. Its (cell × world) tasks are step-1 FAST DPs — dense
     * boxes plus small Pareto frontiers — not the ~1 GiB exact tier-2 DPs the [certifierThreadsForHeap]
     * formula is calibrated for. Measured on the real lvl-245 back+berserk shape: 6 concurrent tier-1.5
     * workers held the WHOLE process at ~2.6 GiB RSS (~0.4 GiB per worker including shared caches), while the
     * exact-tier formula resolves a stock heap to 1 worker and left production's dominant certificate stage
     * serial. So tier-1.5 gets its own heap-aware sizing: reserve ~1.5 GiB (model + warm fast caches + app),
     * one worker per ~0.4 GiB of remainder, same `min(6, cores − 1)` cap, floor 1. The packaged GUI's -Xmx3g
     * resolves to 3 workers; a stock 4 GiB heap to 6; tiny heaps stay serial.
     */
    internal fun certifierTier15Threads(): Int = certifierTier15ThreadsForHeap(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors())

    /**
     * Worker count for the FAST tier's per-world passes. A fast world DP is heavier than a tier-1.5
     * single-cell one (its dense box spans EVERY AP cell) but far lighter than an exact tier-2 DP, so it
     * gets its own sizing: reserve ~1.5 GiB, one worker per ~0.6 GiB of remainder, capped at
     * `min(5, cores − 1)` — at most 5 worlds ever remain after the serial warm-once world. A stock 4 GiB
     * heap resolves to 4, the packaged GUI's -Xmx3g to 2, tiny heaps stay serial.
     */
    internal fun certifierFastWorldThreads(): Int = certifierFastWorldThreadsForHeap(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors())

    internal fun certifierFastWorldThreadsForHeap(
        maxMemoryBytes: Long,
        cores: Int,
    ): Int {
        val gib = 1024L * 1024 * 1024
        val reserved = gib + gib / 2
        val perWorker = 3 * gib / 5 // ~0.6 GiB per concurrent all-cells fast world DP
        val byHeap = ((maxMemoryBytes - reserved) / perWorker).toInt()
        val upper = min(5, maxOf(1, cores - 1))
        return byHeap.coerceIn(1, upper)
    }

    internal fun certifierTier15ThreadsForHeap(
        maxMemoryBytes: Long,
        cores: Int,
    ): Int {
        val gib = 1024L * 1024 * 1024
        val reserved = gib + gib / 2 // 1.5 GiB base (model + warm fast-pass caches + app)
        val perWorker = 2 * gib / 5 // ~0.4 GiB per concurrent (cell × world) step-1 fast DP (measured)
        val byHeap = ((maxMemoryBytes - reserved) / perWorker).toInt()
        val upper = min(6, maxOf(1, cores - 1))
        return byHeap.coerceIn(1, upper)
    }

    /**
     * PRODUCTION certificate API (P4.1). Builds the max-damage model once for a **single-element** [params]
     * scenario (the certifier needs `StatBuilder`'s terms — this initializes OR-Tools natives, which the
     * production path has already warmed), then runs the two-tier orchestrator ([CertLedger]).
     *
     * Returns `null` when no certificate is available: a **multi-element / boss** scenario (the model's
     * per-element certifier seam only fires for one candidate element — compose per element instead), a
     * non-max-damage mode, or a forced-rune / forced-sublimation shape the certifier bails on. A non-null
     * result is a sound upper-bound ledger in OBJECTIVE units (directly comparable to CP-SAT objectives).
     *
     * @param incumbentObjective a feasible objective (the best build found) — cells whose bound is `≤` it are
     *   eliminated on the fast value; `null` confirms every non-bailed cell exactly.
     * @param threads worker count for both tiers; defaults to [certifierDefaultThreads] (serial — see its doc).
     * @param isCancelled (B8) polled once per certifier DP stage; a cancelled run bails (sound) and returns null,
     *   so the caller declines the badge (Unavailable) and — crucially — never caches an incomplete ledger.
     */
    fun maxDamageCertificate(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = true,
        incumbentObjective: Long? = null,
        threads: Int = certifierDefaultThreads(),
        // Re-read at each tier's start when set (takes precedence over [threads]) — the warm-up passes a
        // provider that returns 1 while the search runs and [certifierDefaultThreads] once it is done.
        threadsProvider: ((CertTier) -> Int)? = null,
        // Re-read once, right before elimination — the warm-up passes the search's LATEST feasible proxy so
        // the certificate eliminates against the final incumbent instead of the weak first-streamed one.
        incumbentProvider: (() -> Long?)? = null,
        // Cascade tier-1.5 (short-search rescue) — see [StatBuilder.certifyLedgerCascadeTier15].
        cascadeTier15: Boolean = false,
        isCancelled: () -> Boolean = { false },
        // B6: reuse a prior compute's SCALED fast bounds (+ bail set) for this shape ⇒ skip the tier-1 fast DP.
        precomputedFast: Map<Int, Long>? = null,
        precomputedBailed: Set<Int>? = null,
        // B4/B7 compute-path reuse: per-cell tier-1.5 / exact confirms (+ provenance) from a prior compute.
        precomputedTier15: Map<Int, Long>? = null,
        precomputedExact: Map<Int, Long>? = null,
        precomputedProv: Map<Int, CellProvenance>? = null,
        // CERTIFIER_VERSION 52: certify a HARD-LEG result — the ledger then bounds the targets-met builds only (every pass
        // enforces the request's AP / MP / CC / RANGE rows, see [StatBuilder.certifierTargetAware]). The caller keys its cache
        // on it ([MaxDamageCertificateCache]); false (soft leg / free request) is the target-blind ledger.
        targetAware: Boolean = false,
    ): CertLedger? {
        // The ledger's AT_MOST windows read apConst/critConst, which fold the passives' flat stats,
        // while the solver's pre-combat read excludes passives — a passive granting AP or crit would
        // over-reject real carriers. None does today (they grant MP or block): bail if one appears
        // (pre-release review 2026-10-01).
        if (resolvedPassives(params).any { passive ->
                passive.flatStats.keys.any { it.foldedToUsableStat() in setOf(Characteristic.ACTION_POINT, Characteristic.CRITICAL_HIT) }
            }
        ) {
            return null
        }
        val ledger =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                applyDomination = applyDomination,
                certifyFastThreadsForTest = threads,
                // Default: per-tier calibrated counts — production proofs get parallel tier-1.5 and
                // fast-world workers even on a stock heap; the exact tier keeps the caller's [threads].
                certifierThreadsProvider =
                    threadsProvider
                        ?: { tier ->
                            when (tier) {
                                CertTier.TIER15 -> maxOf(threads, certifierTier15Threads())
                                CertTier.FAST -> maxOf(threads, certifierFastWorldThreads())
                                else -> threads
                            }
                        },
                certifierIncumbentProvider = incumbentProvider,
                certifyLedgerCascadeTier15 = cascadeTier15,
                certifyLedgerForTest = true,
                certifyLedgerIncumbentForTest = incumbentObjective,
                certifyLedgerForceTier2AllForTest = false,
                certifyLedgerPrecomputedFast = precomputedFast,
                certifyLedgerPrecomputedBailed = precomputedBailed,
                certifyLedgerPrecomputedTier15 = precomputedTier15,
                certifyLedgerPrecomputedExact = precomputedExact,
                certifyLedgerPrecomputedProv = precomputedProv,
                certifierCancelled = isCancelled,
                certifierTargetAware = targetAware
            ).certifierLedgerForTest
        // A cancelled run may have bailed mid-way (a sound but incomplete ledger). Never surface or cache it.
        return if (isCancelled()) null else ledger
    }

    /**
     * Test-only: the two-tier [CertLedger] (P3.2 orchestrator) for these params. [incumbentObjective] drives
     * elimination (null = confirm every non-bailed cell); [forceTier2All] confirms every non-bailed cell
     * exactly regardless of the incumbent (the oracle-equality lock). [threads] worker count for both tiers.
     */
    internal fun certifyLedgerForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        incumbentObjective: Long? = null,
        forceTier2All: Boolean = false,
        threads: Int = 1,
        // v52: the hard-leg (target-aware) ledger — see [StatBuilder.certifierTargetAware].
        targetAware: Boolean = false,
    ): CertLedger =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyFastThreadsForTest = threads,
            certifyLedgerForTest = true,
            certifyLedgerIncumbentForTest = incumbentObjective,
            certifyLedgerForceTier2AllForTest = forceTier2All,
            certifierTargetAware = targetAware
        ).certifierLedgerForTest!!

    /** Test-only PROVENANCE: the backtracked composition of [cell]'s winning certificate state. */
    internal fun certifierExplainForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        cell: Int,
    ): List<String> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyExplainCellForTest = cell
        ).certifierExplainForTest

    /**
     * E8 item A: the STRUCTURED provenance of a cell's winning certificate state — the winning composition's
     * equipmentIds, so the fast-path restricts the pool by id instead of regex-parsing the `slot:` [certifierExplainForTest]
     * strings (which break on item names containing `" + "` / `"(di+"`). Empty when the certifier bails ⇒ the seam falls back.
     */
    internal fun certifierExplainItemIdsForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        cell: Int,
        // Polled once per certifier DP stage (B8): a cancelled scan bails with NO ids, which the E8 caller reads
        // as "no provenance" — it checks its own cancel flag right after, so the construct stops instead of falling back.
        isCancelled: () -> Boolean = { false },
    ): List<Int> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyExplainCellForTest = cell,
            certifierCancelled = isCancelled
        ).certifierExplainItemIds

    /**
     * E8 item A (perf): the same STRUCTURED provenance as [certifierExplainItemIdsForTest] but from a CACHED winning
     * (world, crit-step) [provenance] — replays only that one explain pass instead of the full N-worlds scan (which
     * costs ~minutes at high level). Empty if the certifier bails or the pointer is out of range ⇒ the seam falls back.
     */
    internal fun certifierExplainItemIdsFromProvenanceForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        applyDomination: Boolean = false,
        cell: Int,
        provenance: CellProvenance,
        isCancelled: () -> Boolean = { false },
    ): List<Int> =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            applyDomination = applyDomination,
            certifyExplainCellForTest = cell,
            certifyExplainProvenanceForTest = provenance,
            certifierCancelled = isCancelled
        ).certifierExplainItemIds

    /**
     * E8 fast-path (plan §4 E8, measured GO — SOLVER_PERFORMANCE §7): CONSTRUCT the proven optimum from the
     * certificate DP instead of a full CP-SAT solve. Two tiers:
     *  1. FAST: backtrack the argmax cell's provenance ITEMS, restrict the pool to them, and re-solve that tiny
     *     pool (CP-SAT re-derives runes/subs/skills freely). Measured: free lvl-110 (1,310,980) and lvl-245
     *     (16,909,590) both construct in one tiny re-solve (ratio 1.0) — DP-seconds instead of CP-SAT-minutes.
     *  2. FALLBACK: the provenance items need not REALIZE the bound — the frontier abstraction can credit a sub
     *     whose value only a slightly different item set unlocks (the runes+subs lvl-110 optimum: 10 normal subs
     *     where the provenance set only realizes 9) — so when the fast tier misses, re-solve the FULL pool at the
     *     pinned argmax cell as a FEASIBILITY problem (`rawScore ≥ bound` + stop-at-first-solution): any solution
     *     found sits at the bound, no optimality proof needed.
     * SOUND by construction: the DP's argmax bound is a sound UPPER bound on the global optimum, so a result is
     * returned ONLY when a re-solved raw proxy REACHES that bound (`proxy ≥ cellBound`) — which certifies the build
     * IS the global optimum. Returns null when neither tier can (a loose bound, an invalid build) ⇒ the caller
     * keeps the incumbent, so best-effort construction is safe (a miss only costs the badge, never correctness).
     * Free single-element max-damage only (the DP-provable shape): a request whose rows constrain the problem
     * (a required AP / MP / range / HP… target) is refused, but a MAXIMIZED-mastery row — which max-damage
     * ignores — is not (see [isFreeMaxDamageShape]), nor a FLOOR (a required row of target 0): the fast re-solve then
     * runs the hard leg, so the constructed build meets every floor (the ledger, which ignores them, still bounds it), and
     * the full-pool fallback is skipped — a fast miss there most likely means a binding floor, which it cannot get past.
     *
     * BOUNDED + CANCELLABLE: [isCancelled] is polled between the steps and while a re-solve runs (the native solve
     * is stopped through the flow's teardown), so a superseded search / proof abandons the rescue at once; and the
     * full-pool fallback — the only open-ended step — gives up after [fallbackWallCapSeconds] of wall clock. Either
     * way the answer is null (keep the incumbent), never a wrong "proven".
     */
    internal suspend fun dpConstructProvenOptimum(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        incumbentObjective: Long? = null,
        // The caller ALREADY holds the ledger to construct from (the warm-up's cascade path): skip the
        // cache round-trip — a cascaded PARTIAL entry cannot always be reconstructed for this incumbent,
        // and recomputing it here would pay the full tier-1.5 batch the cascade exists to avoid.
        precomputedLedger: CertLedger? = null,
        isCancelled: () -> Boolean = { false },
        fallbackWallCapSeconds: Double = E8_FALLBACK_WALL_CAP_SECONDS,
    ): SolverResult<BuildCombination>? {
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return null
        // Free shapes only — the DP can't model required targets. A maximized-mastery row is not a constraint.
        if (!isFreeMaxDamageShape(params.targetStats)) return null
        if (isCancelled()) return null
        val ledger =
            if (precomputedLedger != null) {
                precomputedLedger
            } else if (incumbentObjective != null) {
                // Production path (an incumbent from the completed search): reuse the CACHED certificate —
                // `proveOptimality` populated it under the same key, so construction adds no extra ledger DP. The
                // argmax cell (objective > the incumbent it's rescuing) is confirmed at the exact tier, so its bound
                // is tight enough for the re-solve to reach.
                MaxDamageCertificateCache.certificate(
                    params,
                    equipmentsByItemType,
                    runes,
                    sublimations,
                    applyDomination = true,
                    incumbentObjective = incumbentObjective,
                    threads = certifierDefaultThreads(),
                    cascadeTier15 = true,
                    isCancelled = isCancelled
                ) ?: return null
            } else {
                // Standalone (no incumbent, e.g. the manual proof test): force EVERY cell to the exact tier so any
                // shape's argmax is exact — no cache, no incumbent pruning.
                certifyLedgerForTest(
                    params,
                    equipmentsByItemType,
                    runes,
                    sublimations,
                    applyDomination = true,
                    incumbentObjective = null,
                    forceTier2All = true
                )
            }
        var argmax =
            ledger.cellObjectives.entries
                .filter { it.value >= 0 }
                .maxByOrNull { it.value } ?: return null
        var constructLedger = ledger
        if (precomputedLedger == null && incumbentObjective != null && argmax.key !in ledger.cellProvenance) {
            // The cascaded ledger's argmax is an UNCONFIRMED fast bound (the cascade's bet missed) — the
            // re-solve below could never reach it. Recompute the FULL ledger (cascade off; the cache
            // reuses every fast bound and confirmed cell, so only the skipped survivors pay) and
            // construct from its exactly-confirmed argmax — verdict parity with the pre-cascade path.
            constructLedger =
                MaxDamageCertificateCache.certificate(
                    params,
                    equipmentsByItemType,
                    runes,
                    sublimations,
                    applyDomination = true,
                    incumbentObjective = incumbentObjective,
                    threads = certifierDefaultThreads(),
                    isCancelled = isCancelled
                ) ?: return null
            argmax =
                constructLedger.cellObjectives.entries
                    .filter { it.value >= 0 }
                    .maxByOrNull { it.value } ?: return null
        }
        if (isCancelled()) return null
        val cell = argmax.key
        val bound = argmax.value
        // E8 item A: recover the argmax cell's winning items as typed equipmentIds (no fragile `slot:`-string parse).
        // Phase 2 (perf): the badge's exact pass captured the argmax cell's winning (world, crit-step) into the
        // cached ledger, so replay just that ONE explain pass. When it is absent (an old cache entry, or a
        // tier-1.5-cleared argmax that never ran exactly), fall back to the full N-worlds scan — sound, just slower.
        val provIds =
            (
                constructLedger.cellProvenance[cell]?.let { prov ->
                    certifierExplainItemIdsFromProvenanceForTest(
                        params,
                        equipmentsByItemType,
                        runes,
                        sublimations,
                        applyDomination = true,
                        cell = cell,
                        provenance = prov,
                        isCancelled = isCancelled
                    )
                } ?: certifierExplainItemIdsForTest(
                    params,
                    equipmentsByItemType,
                    runes,
                    sublimations,
                    applyDomination = true,
                    cell = cell,
                    isCancelled = isCancelled
                )
            ).toSet()
        // A cancelled explain bailed with no ids — stop here instead of falling through to the full-pool fallback.
        if (isCancelled()) return null
        val debug = System.getenv("WAKFU_E8_DEBUG") == "1"
        // The request's FLOORS (rows of target 0 on a required stat — "air resistance 0", "dodge 0") are the one constraint a
        // free shape still carries: the ledger ignores them (a relaxation, so its bound stays an upper bound of the floored
        // optimum), but the build constructed here must meet them, or the badge would crown a build the search's hard leg
        // forbids. So the re-solve runs the hard leg — `actual ≥ 0` on every floor, the plain damage objective — whenever the
        // request has floors (and the full-pool fallback is skipped, see below); without one the hard leg adds nothing, and the
        // plain solve stays as it was.
        val hardFloors = params.targetStats.hasFloors
        // FAST path: re-solve the pool restricted to the provenance items — ~seconds, and reaches the bound on
        // most shapes (measured: free lvl-110 / lvl-245 construct in one tiny re-solve).
        val fast =
            if (provIds.isNotEmpty()) {
                val restricted =
                    equipmentsByItemType
                        .mapValues { (_, items) -> items.filter { it.equipmentId in provIds } }
                        .filterValues { it.isNotEmpty() }
                if (restricted.isEmpty()) {
                    null
                } else {
                    // Cancellable, not wall-capped: the tiny restricted pool answers in seconds, its own det-120 budget bounds it.
                    collectWithinBudget(
                        optimize(
                            params.copy(maxDamageApTarget = cell),
                            restricted,
                            runes,
                            sublimations,
                            SolverTuning(maxDeterministicTime = 120.0),
                            hardConstraints = hardFloors
                        ),
                        budgetMillis = null,
                        isCancelled = isCancelled
                    ).items.maxByOrNull { it.matchPercentage }
                }
            } else {
                null
            }
        if (isCancelled()) return null
        // For a FREE shape objective == raw proxy (no penalty); maxDamageObjective is always populated, the raw
        // proxy only when its var survives — so fall back. Both are the ledger-comparable scaled units.
        val fastProxy = fast?.let { it.maxDamageRawProxy ?: it.maxDamageObjective }
        if (debug) System.err.println("E8_DBG fast cell=$cell bound=$bound proxy=$fastProxy valid=${fast?.individual?.isValid()}")
        if (fast != null && fastProxy != null && fastProxy >= bound && fast.individual.isValid()) {
            // A floored re-solve is a hard leg: the build meets every floor in the solver's exact arithmetic.
            return fast.copy(isOptimal = true, maxDamageHardConstraintsMet = hardFloors)
        }
        // A request with FLOORS whose fast re-solve fell short: the ledger's bound ignores the floors, so the likeliest reason is a
        // floor the argmax cell's best build breaks — and then no floored build reaches the bound, and the full-pool feasibility
        // search below can only run out its wall cap (twice per search: in it, competing with CP-SAT, and after it). Skipped: the
        // incumbent keeps its "within X%" badge. (Every measured construct success comes from the fast tier anyway, see
        // [E8_FALLBACK_WALL_CAP_SECONDS].)
        if (hardFloors) {
            if (debug) System.err.println("E8_DBG floored fast tier missed cell=$cell bound=$bound proxy=$fastProxy — fallback skipped")
            return null
        }
        // FALLBACK: the provenance item-set need not REALIZE the bound — the certifier's frontier abstraction can
        // credit a sublimation whose value only a slightly different item set unlocks (e.g. the 10th normal sub on
        // a fuller sub loadout), so the restricted re-solve tops out below the bound. Re-solve the FULL pool at the
        // pinned argmax cell with a HARD `rawScore ≥ bound` floor: a pure FEASIBILITY search (the bound is a sound
        // upper bound at that cell, so any solution found sits exactly at it — no optimality proof, which is the
        // part the timed search couldn't close), stopped at the first solution, under the canonical deterministic
        // protocol (1 worker + interleave) so the construction is machine-reproducible. A loose (unreachable)
        // bound comes back INFEASIBLE ⇒ empty flow ⇒ null — the caller keeps the incumbent, soundness untouched.
        // Open-ended otherwise (the bound can be loose yet not provably unreachable), hence the wall-clock cap
        // [fallbackWallCapSeconds] and the cooperative cancel: on either, the solve is stopped and the rescue gives up.
        val fallbackRun =
            collectWithinBudget(
                optimize(
                    params.copy(maxDamageApTarget = cell),
                    equipmentsByItemType,
                    runes,
                    sublimations,
                    SolverTuning(
                        numSearchWorkers = 1,
                        interleaveSearch = true,
                        maxDeterministicTime = E8_FALLBACK_DETERMINISTIC_BUDGET,
                        stopAtFirstSolution = true
                    ),
                    maxDamageRawFloor = bound
                ),
                budgetMillis = (fallbackWallCapSeconds * 1000.0).toLong(),
                isCancelled = isCancelled
            )
        if (fallbackRun.end == CollectEnd.TIMED_OUT) {
            logger.info { "E8 construct: the full-pool fallback gave up after ${fallbackWallCapSeconds}s (cell=$cell bound=$bound) — keeping the incumbent." }
        }
        if (fallbackRun.end == CollectEnd.CANCELLED || isCancelled()) return null
        val fallback = fallbackRun.items.maxByOrNull { it.matchPercentage } ?: return null
        val proxy = fallback.maxDamageRawProxy ?: fallback.maxDamageObjective ?: return null
        if (debug) System.err.println("E8_DBG fallback cell=$cell bound=$bound proxy=$proxy valid=${fallback.individual.isValid()}")
        return if (proxy >= bound && fallback.individual.isValid()) fallback.copy(isOptimal = true) else null
    }

    /**
     * Test-only: solve a max-damage model with either reachable ([tightDomains] = true) or loose guard
     * ([tightDomains] = false) declared domains, returning its objective + status WITHOUT requiring OPTIMAL.
     * The optimum-preservation lock asserts the two declare the same optimum (tight only changes provability,
     * never the answer) and that the tight build is the one that *proves* it.
     */
    internal fun maxDamageSolveForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
        tightDomains: Boolean,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        forceRuneCountModel: Boolean = false,
        applyDomination: Boolean = false,
        forceRuneLeq: Boolean = false,
        // Enforce the required targets as HARD `actual ≥ target` constraints (INFEASIBLE ⇒ hasSolution false),
        // matching the production hard-constraints-first pass. Default false keeps existing callers byte-identical.
        hardConstraints: Boolean = false,
        // Reference solve for the heuristic-prefilter soundness lock (any scoring mode).
        forceFullPool: Boolean = false,
        // The rune-choice pruning-exactness lock: see [buildModel].
        runeChoicePruning: Boolean = true,
        runeChoiceGating: Boolean = true,
    ): MaxDamageSolveOutcome {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                tightDomains,
                forceRuneCountModel = forceRuneCountModel,
                applyDomination = applyDomination,
                forceRuneLeq = forceRuneLeq,
                runeChoicePruning = runeChoicePruning,
                runeChoiceGating = runeChoiceGating,
                hardConstraints = hardConstraints,
                forceFullPool = forceFullPool,
                maxDamageExperiment = tuning.maxDamageExperiment
            )
        val solver = deterministicMaxDamageSolver(tuning)
        val status = solver.solve(built.model)
        val hasSolution =
            status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                status == com.google.ortools.sat.CpSolverStatus.FEASIBLE
        return MaxDamageSolveOutcome(
            objective = if (hasSolution) solver.objectiveValue().toLong() else Long.MIN_VALUE,
            isOptimal = status == com.google.ortools.sat.CpSolverStatus.OPTIMAL,
            hasSolution = hasSolution,
            selectedEquipmentIds =
                if (hasSolution) {
                    built.equipVars
                        .filterValues { solver.value(it) > 0L }
                        .keys
                        .map { it.equipmentId }
                        .toSet()
                } else {
                    emptySet()
                }
        )
    }

    /** Result of [elementRowSolveForTest]. */
    internal class ElementRowSolve(
        val status: com.google.ortools.sat.CpSolverStatus,
        val objective: Long?,
        val build: BuildCombination?,
        // The model's claimed value of every per-element var the elemental target rows read (each family's fold).
        val modelElementValues: Map<Characteristic, Long>,
        // The model's value of every floor it reads (a required row of target 0 — StatBuilder.floorReads; a floor no build of
        // the pool can break is not read), and whether it halved its objective (precision, or a soft leg's broken floor;
        // null when the model has no such boolean — the hard legs).
        val modelFloorValues: Map<Characteristic, Long> = emptyMap(),
        val modelHalved: Boolean? = null,
    ) {
        val hasSolution: Boolean get() = objective != null
        val isOptimal: Boolean get() = status == com.google.ortools.sat.CpSolverStatus.OPTIMAL
    }

    /**
     * Test seam (the per-element-row fold locks): builds the [params] model on exactly [equipmentsByItemType] (no
     * prefilter, no domination, unless [forceFullPool] is false), the required targets as HARD constraints when
     * [hardConstraints], optionally PINS a build — [pinnedEquipmentIds] (every other item off), [pinSkillsToZero] (no skill
     * point spent) and/or [pinnedDecisions] (every decision var by name, an absent one 0) — solves it with the deterministic
     * [tuning], and returns the solved build beside the model's own claimed value of every per-element var its rows read.
     * [relaxFloors]: the most-masteries model without the request's floors, relax-then-check's relaxed stage ([relaxThenCheck]).
     */
    internal fun elementRowSolveForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
        hardConstraints: Boolean,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        pinnedEquipmentIds: Set<Int>? = null,
        pinSkillsToZero: Boolean = false,
        pinnedDecisions: Map<String, Long>? = null,
        forceFullPool: Boolean = true,
        relaxFloors: Boolean = false,
    ): ElementRowSolve {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                forceFullPool = forceFullPool,
                hardConstraints = hardConstraints,
                maxDamageExperiment = tuning.maxDamageExperiment,
                relaxFloors = relaxFloors
            )
        if (built.maxDamageStaticallyInfeasible) {
            return ElementRowSolve(com.google.ortools.sat.CpSolverStatus.INFEASIBLE, null, null, emptyMap())
        }
        pinnedEquipmentIds?.let { ids ->
            for ((equip, v) in built.equipVars) built.model.addEquality(v, if (equip.equipmentId in ids) 1L else 0L)
        }
        if (pinSkillsToZero) for (v in built.skillVars.values) built.model.addEquality(v, 0L)
        pinnedDecisions?.let { values ->
            val decisions = diagnosticVars(built)
            val missing = values.keys - decisions.map { it.name }.toSet()
            require(missing.isEmpty()) { "pinned decisions absent from the model: $missing" }
            for (v in decisions) built.model.addEquality(v, values[v.name] ?: 0L)
        }
        val solver = CpSolver()
        solver.parameters.logSearchProgress = false
        solver.parameters.numSearchWorkers = tuning.numSearchWorkers
        solver.parameters.randomSeed = tuning.randomSeed
        solver.parameters.maxDeterministicTime = tuning.maxDeterministicTime
        if (tuning.interleaveSearch) solver.parameters.interleaveSearch = true
        if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) solver.parameters.linearizationLevel = 2
        val status = solver.solve(built.model)
        val hasSolution =
            status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                status == com.google.ortools.sat.CpSolverStatus.FEASIBLE
        if (!hasSolution) return ElementRowSolve(status, null, null, emptyMap())
        val build = solutionToBuild(params, built.allEquips, built.equipVars, built.skillVars, built.runeModel, built.subModel) { solver.value(it) }
        return ElementRowSolve(
            status = status,
            // Rounded, not truncated: the objective comes back as a double (961354.9999 must read 961355).
            objective = Math.round(solver.objectiveValue()),
            build = build,
            modelElementValues = built.elementRowReads.mapValues { (_, v) -> solver.value(v) },
            modelFloorValues = built.floorReads.associate { (characteristic, v) -> characteristic to solver.value(v) },
            modelHalved = built.halvingFlag?.let { solver.value(it) == 1L }
        )
    }

    /** Test seam: the per-slot domination pre-filter applied to [pool], pinning [pinned] to equality (empty = full). */
    internal fun filterDominatedPoolForTest(
        pool: Map<ItemType, List<Equipment>>,
        pinned: Set<Characteristic> = emptySet(),
    ): Map<ItemType, List<Equipment>> = filterDominatedPool(pool, DominationShape(pinned))

    /** A tracked objective-chain var's solved value against its declared reachable `[lo, hi]`. */
    internal data class MaxDamageVarBound(
        val name: String,
        val value: Long,
        val lo: Long,
        val hi: Long,
    ) {
        val withinBound: Boolean get() = value in lo..hi
    }

    internal data class MaxDamageReachableRange(
        val name: String,
        val lo: Long,
        val hi: Long,
    ) {
        val span: Long get() = hi - lo
    }

    internal fun maxDamageReachableRangesForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        tightDomains: Boolean = true,
        applyDomination: Boolean = false,
        experiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig.DEFAULT,
    ): List<MaxDamageReachableRange> {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                tightDomains = tightDomains,
                applyDomination = applyDomination,
                maxDamageExperiment = experiment
            )
        return built.maxDamageTracked.map { (_, name, range) -> MaxDamageReachableRange(name, range.first, range.last) }
    }

    /**
     * Test-only **soundness probe**: build the max-damage model with **loose** guard domains (so the solver is
     * free to push every objective-chain var as high as a real build allows) while still *recording* each var's
     * propagated reachable `[lo, hi]`, solve it, and return every tracked var's solved value vs that range. A
     * value outside its range means the interval arithmetic UNDER-estimated — i.e. the production (tight) build
     * would have declared a too-small domain and **silently cut the optimum**. The proven optimum's own var
     * values are exactly the ones that must fit, so this catches every optimum-threatening under-estimate.
     */
    internal fun maxDamageVarBoundsForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        // false (default) = the soundness-probe use: loose guard domains so the solver can push each var to its
        // true max. true = the plan §2.2 bound-layer AUDIT use: PRODUCTION (tight) domains so the same
        // reachable box is recorded but the solve is tractable at lvl-245 (loose product encodings blow up there).
        tightDomains: Boolean = false,
        // false (default, soundness probe keeps the full pool). true = the AUDIT use: run the production domination
        // pre-filter so the lvl-245 full-epic pool doesn't blow the model up on build (sound — domination preserves
        // the optimum, and the recorded boxes then match the production model).
        applyDomination: Boolean = false,
    ): List<MaxDamageVarBound> {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                runes,
                sublimations,
                tightDomains = tightDomains,
                applyDomination = applyDomination,
                maxDamageExperiment = tuning.maxDamageExperiment
            )
        val solver = deterministicMaxDamageSolver(tuning)
        val status = solver.solve(built.model)
        require(status == com.google.ortools.sat.CpSolverStatus.OPTIMAL || status == com.google.ortools.sat.CpSolverStatus.FEASIBLE) {
            "soundness probe needs a solution, got $status"
        }
        return built.maxDamageTracked.map { (v, name, range) ->
            MaxDamageVarBound(name, solver.value(v), range.first, range.last)
        }
    }

    /**
     * C2 test seam: whether the HARD-constraints max-damage model is statically infeasible — i.e. some required
     * target exceeds its reachable ceiling, so [optimize] skips the CP-SAT solve and falls straight to the soft
     * leg. Builds the model only (no solve) and returns [BuiltModel.maxDamageStaticallyInfeasible].
     */
    internal fun maxDamageStaticallyInfeasibleForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
    ): Boolean = buildModel(params, equipmentsByItemType, runes, sublimations, hardConstraints = true).maxDamageStaticallyInfeasible

    /**
     * C7 test seam: the crit·diff AM-GM bound the model-build actually ADDED as a constraint, or null when
     * the cut did not fire (flag off, %-skill bail, or self-disabled against term's declared reach). The
     * firing fixture asserts non-null so the exhaustive-optimum comparison provably exercises the cut.
     */
    internal fun maxDamageCritDiffCutBoundForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
        experiment: MaxDamageExperimentConfig = MaxDamageExperimentConfig.DEFAULT,
    ): Long? =
        buildModel(
            params,
            equipmentsByItemType,
            runes,
            sublimations,
            maxDamageExperiment = experiment
        ).critDiffJointCutBoundForTest

    /** C3 test seam: the memoized per-slot domination filter (keyed on basePool identity + [DominationShape] value). */
    internal fun filterDominatedPoolMemoizedForTest(
        basePool: Map<ItemType, List<Equipment>>,
        shape: DominationShape,
    ): Map<ItemType, List<Equipment>> = filterDominatedPoolMemoized(basePool, shape)

    /**
     * Test-only soundness probe for the **precision** reachable domains — the precision analogue of
     * [maxDamageVarBoundsForTest]. Builds the precision model with loose guard domains (so the solver freely
     * pushes every stat-chain var as high as a real build allows) while still recording each var's propagated
     * reachable `[lo, hi]`, solves, and returns each tracked var's solved value vs that range. A value outside
     * its range means the interval arithmetic UNDER-estimated — i.e. the production (tight) build would have
     * declared a too-small domain and silently cut the optimum.
     */
    internal fun precisionVarBoundsForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
        runes: List<RuneType> = emptyList(),
        sublimations: List<Sublimation> = emptyList(),
    ): List<MaxDamageVarBound> {
        require(params.scoreComputationMode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT) {
            "precisionVarBoundsForTest needs precision params, got ${params.scoreComputationMode}"
        }
        val built = buildModel(params, equipmentsByItemType, runes, sublimations, tightDomains = false)
        val solver = deterministicMaxDamageSolver(tuning)
        val status = solver.solve(built.model)
        require(status == com.google.ortools.sat.CpSolverStatus.OPTIMAL || status == com.google.ortools.sat.CpSolverStatus.FEASIBLE) {
            "soundness probe needs a solution, got $status"
        }
        return built.precisionTracked.map { (v, name, range) ->
            MaxDamageVarBound(name, solver.value(v), range.first, range.last)
        }
    }

    /** Benchmark probe (any mode): model size + solve status/score, with the prefilter optionally bypassed. */
    internal data class BenchOutcome(
        val status: String,
        val numVariables: Int,
        val numConstraints: Int,
        val wallTimeSec: Double,
        val score: BigDecimal,
        val poolSize: Int,
    )

    /**
     * Test-only: build + solve [params] on [equipmentsByItemType] with a deterministic [tuning], optionally
     * bypassing the item prefilter ([forceFullPool]); reports CP-SAT status, model size and the *scored*
     * result so a benchmark can compare full-pool vs prefiltered on objective AND tractability.
     */
    internal fun solveForBenchmark(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        tuning: SolverTuning,
        forceFullPool: Boolean,
    ): BenchOutcome {
        val built =
            buildModel(
                params,
                equipmentsByItemType,
                emptyList(),
                emptyList(),
                tightDomains = true,
                forceFullPool = forceFullPool,
                maxDamageExperiment = tuning.maxDamageExperiment
            )
        val solver = CpSolver()
        solver.parameters.logSearchProgress = false
        solver.parameters.numSearchWorkers = tuning.numSearchWorkers
        solver.parameters.randomSeed = tuning.randomSeed
        solver.parameters.maxDeterministicTime = tuning.maxDeterministicTime
        if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) solver.parameters.linearizationLevel = 2
        val status = solver.solve(built.model)
        val hasSolution =
            status == com.google.ortools.sat.CpSolverStatus.OPTIMAL ||
                status == com.google.ortools.sat.CpSolverStatus.FEASIBLE
        val score =
            if (hasSolution) {
                val build = solutionToBuild(params, built.allEquips, built.equipVars, built.skillVars, built.runeModel, built.subModel) { solver.value(it) }
                scoreFor(params, build)
            } else {
                BigDecimal.ZERO
            }
        val proto = built.model.model()
        return BenchOutcome(
            status = status.toString(),
            numVariables = proto.variablesCount,
            numConstraints = proto.constraintsCount,
            wallTimeSec = solver.wallTime(),
            score = score,
            poolSize = built.allEquips.size
        )
    }

    /** Test seam: the items the multi-element prefilter ([prefilterRelevantEquipments]) keeps of [equipmentsByItemType]. */
    internal fun prefilteredPoolForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
    ): Map<ItemType, List<Equipment>> = prefilterRelevantEquipments(equipmentsByItemType, params)

    /** Test-only: whether [params] would be prefiltered, and the resulting distinct-item pool size (no solve). */
    internal fun gatedPoolSizeForTest(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
    ): Pair<Boolean, Int> {
        val prefiltered = needsItemPrefilter(params.targetStats)
        val pool = if (prefiltered) prefilterRelevantEquipments(equipmentsByItemType, params) else equipmentsByItemType
        return prefiltered to orderEquipments(pool).size
    }

    private fun CpModel.createSkillVariables(characterSkills: CharacterSkills): Map<SkillCharacteristic, IntVar> {
        val skillVars = mutableMapOf<SkillCharacteristic, IntVar>()

        fun addCategory(
            assignable: Assignable<*>,
            namePrefix: String,
        ) {
            val sumExpr = LinearExpr.newBuilder()
            for (skill in assignable.getCharacteristics()) {
                val varName = "skill_${namePrefix}_${skill.name.toIdentifier()}"
                val skillVar = newIntVar(0, skillVariableCap(skill, assignable), varName)
                skillVars[skill] = skillVar
                sumExpr.addTerm(skillVar, 1)
            }
            addLessOrEqual(sumExpr.build(), assignable.maxPointsToAssign.toLong())
        }

        addCategory(characterSkills.intelligence, "intel")
        addCategory(characterSkills.strength, "strength")
        addCategory(characterSkills.agility, "agility")
        addCategory(characterSkills.luck, "luck")
        addCategory(characterSkills.major, "major")

        return skillVars
    }

    private fun skillVariableCap(
        skill: SkillCharacteristic,
        assignable: Assignable<*>,
    ): Long = min(skill.maxPointsAssignable, assignable.maxPointsToAssign).toLong()

    internal fun skillVariableCaps(characterSkills: CharacterSkills): Map<SkillCharacteristic, Long> {
        val caps = mutableMapOf<SkillCharacteristic, Long>()

        fun add(assignable: Assignable<*>) {
            assignable.getCharacteristics().forEach { skill ->
                caps[skill] = skillVariableCap(skill, assignable)
            }
        }

        add(characterSkills.intelligence)
        add(characterSkills.strength)
        add(characterSkills.agility)
        add(characterSkills.luck)
        add(characterSkills.major)

        return caps
    }

    private fun CpModel.createEquipmentVariables(allEquips: List<Equipment>): Map<Equipment, IntVar> =
        allEquips.associateWith { equip ->
            newBoolVar("equip_${equip.equipmentId}")
        }

    /**
     * Whether a scenario-gated effect can fire for this request — the solver's `params`-shaped adapter over the
     * shared [scenarioGateMatchesCore] (the single source of truth for the gate decision; see SublimationSemantics).
     * Kept here as `WakfuBuildSolver.scenarioGateMatches` because the CP-SAT model builders call it that way.
     */
    internal fun scenarioGateMatches(
        gate: me.chosante.common.ScenarioGate?,
        params: WakfuBestBuildParams,
    ): Boolean =
        scenarioGateMatchesCore(
            gate,
            params.scoreComputationMode,
            params.damageScenario,
            params.character.level,
            params.targetStats.masteryElementsWanted.keys
        )

    private fun CpModel.addBuildValidityConstraints(
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
    ) {
        val itemTypesLimits =
            mapOf(
                ItemType.AMULET to 1L,
                ItemType.EMBLEM to 1L,
                ItemType.SHOULDER_PADS to 1L,
                ItemType.RING to 2L,
                ItemType.BOOTS to 1L,
                ItemType.CHEST_PLATE to 1L,
                ItemType.CAPE to 1L,
                ItemType.HELMET to 1L,
                ItemType.PETS to 1L,
                ItemType.MOUNTS to 1L,
                ItemType.BELT to 1L
            )

        for ((type, limit) in itemTypesLimits) {
            val typeEquips = allEquips.filter { it.itemType == type }
            if (typeEquips.isNotEmpty()) {
                val sumExpr = LinearExpr.sum(typeEquips.map { equipVars.getValue(it) }.toTypedArray())
                addLessOrEqual(sumExpr, limit)
            }
        }

        // Weapons rules
        val twoHanded = allEquips.filter { it.itemType == ItemType.TWO_HANDED_WEAPONS }
        val oneHanded = allEquips.filter { it.itemType == ItemType.ONE_HANDED_WEAPONS }
        val offHand = allEquips.filter { it.itemType == ItemType.OFF_HAND_WEAPONS }

        val sumTwoHanded = LinearExpr.sum(twoHanded.map { equipVars.getValue(it) }.toTypedArray())
        val sumOneHanded = LinearExpr.sum(oneHanded.map { equipVars.getValue(it) }.toTypedArray())
        val sumOffHand = LinearExpr.sum(offHand.map { equipVars.getValue(it) }.toTypedArray())

        addLessOrEqual(
            LinearExpr
                .newBuilder()
                .add(sumTwoHanded)
                .add(sumOneHanded)
                .build(),
            1L
        )
        addLessOrEqual(
            LinearExpr
                .newBuilder()
                .add(sumTwoHanded)
                .add(sumOffHand)
                .build(),
            1L
        )

        // Rarity rules
        val relics = allEquips.filter { it.rarity == Rarity.RELIC }.map { equipVars.getValue(it) }.toTypedArray()
        if (relics.isNotEmpty()) {
            addLessOrEqual(LinearExpr.sum(relics), 1L)
        }

        val epics = allEquips.filter { it.rarity == Rarity.EPIC }.map { equipVars.getValue(it) }.toTypedArray()
        if (epics.isNotEmpty()) {
            addLessOrEqual(LinearExpr.sum(epics), 1L)
        }

        // Same ring name is not allowed
        val ringsByName =
            allEquips
                .filter { it.itemType == ItemType.RING }
                .groupBy { it.name.fr.lowercase() }
        for ((_, equips) in ringsByName) {
            if (equips.size > 1) {
                val sumExpr = LinearExpr.sum(equips.map { equipVars.getValue(it) }.toTypedArray())
                addLessOrEqual(sumExpr, 1L)
            }
        }
    }

    /**
     * Forces every user-imposed item ([WakfuBestBuildParams.forcedItems], matched on the French name) to be
     * *equipped* — not merely "the only candidate in its slot". [WakfuBestBuildFinderAlgorithm.groupAndFilterEquipments]
     * narrows a forced slot's pool down to the forced item, but the slot itself is still optional (`Σ ≤ 1`), so an
     * imposed item that improves no requested stat would otherwise be left unequipped — reading as "forcing didn't
     * work". Constraining `Σ(same-named) ≥ 1` makes "forcer un objet" mean the item is actually in the build.
     *
     * Matched per French name and gated on existence, so a typo'd / out-of-data forced name is a no-op (you cannot
     * force a non-existent item). Two distinct items forced into the same single-occupancy slot is a contradictory
     * request that makes the model infeasible (no build); surfacing a clear pre-search message for that is tracked
     * separately (backlog ENG-2). Rings are forced per name and have two slots, so two forced rings both equip.
     */
    private fun CpModel.addForcedItemsEquippedConstraints(
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
    ) {
        if (params.forcedItems.isEmpty()) return
        for (forcedName in params.forcedItems.map { it.lowercase() }.toSet()) {
            val sameName = allEquips.filter { it.name.fr.lowercase() == forcedName }
            if (sameName.isEmpty()) continue
            addGreaterOrEqual(LinearExpr.sum(sameName.map { equipVars.getValue(it) }.toTypedArray()), 1L)
        }
    }

    /** The most-masteries objective plus the hard-leg static-infeasibility flag (mirrors [MaxDamageObjectiveVars]). */
    private class MostMasteriesObjectiveVars(
        val objective: IntVar,
        val staticallyInfeasible: Boolean = false,
        // §8.2 S-A only: the (core, bucket) vars of a bucket-interval sub-model, read on the solved
        // assignment so the outer driver folds an exact incumbent without duplicating scorer arithmetic.
        val penaltyBucketProbeVars: Pair<IntVar, IntVar>? = null,
        // §8.5 S-D only: the hard leg's assumption literals (target → literal), read on a proven
        // INFEASIBLE to extract the sufficient core.
        val assumptionLiterals: Map<Characteristic, com.google.ortools.sat.BoolVar>? = null,
    )

    /**
     * Objective for "most masteries" mode: maximize the *requested* masteries — scaled by the build's global
     * **% Damage Inflicted** so the proxy is damage-faithful (see [StatBuilder.diAdjustedPerElementMasteryScore]) —
     * under the required-stat constraints. There is deliberately no tie-breaker that fills otherwise-empty
     * slots, so a slot whose items cannot improve any requested stat (nor the DI factor) is left empty in the
     * proven optimum. This is why, e.g., an item set asking only for distance mastery + AP/MP/HP comes
     * back with no mount: every mount in the data carries only [Characteristic.MASTERY_ELEMENTARY],
     * which contributes to none of those targets, so adding one cannot raise the objective and the
     * proven optimum leaves the slot empty. (Decision: keep as-is; see the engine discussion in
     * AGENTS.md §4.)
     */
    private fun CpModel.buildMostMasteriesObjective(
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        runeModel: RuneModel,
        subModel: SublimationModel,
        // P2a hard-constraints leg (docs/MOST_MASTERIES_PERF_PLAN.md): required targets become HARD
        // `actual ≥ target` constraints and the objective is the PLAIN (unpenalized) mastery×DI score —
        // the power-6 penalty product, whose LP relaxation CP-SAT can only prove against by tree
        // exhaustion (§1bis P0.5 measurement), vanishes from the searched model. INFEASIBLE (unreachable
        // targets) ⇒ the flow emits nothing and the caller falls back to the soft (penalized) model.
        hardConstraints: Boolean = false,
        // P2b two-stage lexicographic (hard leg only). Stage 1: the PRIMARY alone is the objective —
        // no ×10 000 overshoot fold, no division, ~1e4 smaller objective domain, no overshoot-only
        // incumbent churn during the expensive proof. Stage 2 ([mmOvershootPinnedPrimary] = stage 1's
        // proven objective value): the primary is PINNED to that value and the overshoot score becomes
        // the objective — a short, near-forced solve that restores the exact lexicographic build.
        mmPlainPrimaryObjective: Boolean = false,
        mmOvershootPinnedPrimary: Long? = null,
        mmOvershootEncoding: MmOvershootEncoding = MmOvershootEncoding.CURRENT,
        mmProductEncoding: MmProductEncoding = MmProductEncoding.CURRENT,
        mmMasteryScoreUpperBound: Long? = null,
        // §8.2 S-A outer bucket B&B seam — see [SolverTuning.mmPenaltyBucketInterval].
        mmPenaltyBucketInterval: IntRange? = null,
        mmPenaltyBucketFoldedObjective: Boolean = false,
        mmPenaltyGeometryProbe: ((Int, LongArray, Long) -> Unit)? = null,
        // §8.4 S-C outer DI-factor seam — see [SolverTuning.mmDiFactorInterval].
        mmDiFactorInterval: IntRange? = null,
        mmDiFactorFoldedObjective: Boolean = false,
        // §8.5 S-D seams — see [SolverTuning.mmHardTargetsAsAssumptions] / [SolverTuning.mmSoftNoGoodCore].
        mmHardTargetsAsAssumptions: Boolean = false,
        mmSoftNoGoodCore: Set<Characteristic>? = null,
        // Relax-then-check's relaxed stage: the model without the request's floors ([StatBuilder.relaxFloors]).
        relaxFloors: Boolean = false,
        // Hands the stat builder to [buildModel] (its row-read test seam); no effect on the model.
        onStatBuilder: (StatBuilder) -> Unit = {},
    ): MostMasteriesObjectiveVars {
        val statBuilder =
            StatBuilder(
                this,
                params,
                allEquips,
                equipVars,
                skillVars,
                runeModel,
                subModel,
                // The TRACKED/BINARY arms deliberately tighten the whole MM stat chain so the product's
                // big-Ms and declared domains consume the same reachable ranges. CURRENT is byte-identical.
                tight = mmProductEncoding != MmProductEncoding.CURRENT,
                // Decouple from the max-damage experiment default (see [MaxDamageExperimentConfig.NON_MAX_DAMAGE]).
                maxDamageExperiment = MaxDamageExperimentConfig.NON_MAX_DAMAGE,
                relaxFloors = relaxFloors
            ).also(onStatBuilder)
        statBuilder.applyOutOfCombatCaps()
        val targetStats = params.targetStats
        val targetCharacteristics = targetStats.map { it.characteristic }.toSet()

        // Damage-faithful proxy: maximized mastery sum × (1 + DI/100). Mirrored exactly by the re-scorer
        // (FindMostMasteriesFromInputScoring) so the solver optimum and the scored optimum stay in lockstep.
        // C8: [diAdjustedPerElementMasteryScore] now also returns a sound reachable ceiling on that score, used as
        // the product-box bound below (was the loose MASTERY_SCORE_ABS_MAX), tightening the objective's McCormick
        // envelope on the required-target path.
        val (masteryScore, masteryScoreReach) =
            statBuilder.diAdjustedPerElementMasteryScore(
                targetStats,
                targetCharacteristics,
                mmProductEncoding,
                mmDiFactorInterval,
                mmDiFactorFoldedObjective
            )

        // Piste-4 A/B: a redundant `core ≤ U` dual cut from an EXTERNAL sound bound (the M3 DP prototype).
        // Redundant for any correct U, so the optimum is unchanged; the measurement question is whether
        // handing CP-SAT the tight external ceiling shortens the by-exhaustion dual proof (P0.5 wall).
        if (mmMasteryScoreUpperBound != null) {
            addLessOrEqual(masteryScore, mmMasteryScoreUpperBound)
        }

        val requiredTargets = targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }

        // HARD leg: targets as constraints, plain objective (no penalty product). The overshoot
        // tie-breaker keeps its exact secondary semantics — hard-leg ties still prefer overshoot,
        // either folded (single-stage) or via the P2b two-stage split.
        if (hardConstraints) {
            // §8.5 S-D: assumption-gated targets (measurement seam) vs the plain production constraints.
            val assumptionLiterals: Map<Characteristic, com.google.ortools.sat.BoolVar>?
            val staticallyInfeasible: Boolean
            if (mmHardTargetsAsAssumptions) {
                val (literals, static) = statBuilder.addRequiredTargetAssumptions()
                assumptionLiterals = literals
                staticallyInfeasible = static
            } else {
                assumptionLiterals = null
                staticallyInfeasible = statBuilder.addRequiredTargetHardConstraints()
            }
            if (requiredTargets.isEmpty()) {
                return MostMasteriesObjectiveVars(masteryScore, staticallyInfeasible, statBuilder.mmDiFactorProbeVars)
            }
            // P2b stage 1: prove the primary alone.
            if (mmPlainPrimaryObjective) {
                return MostMasteriesObjectiveVars(masteryScore, staticallyInfeasible, assumptionLiterals = assumptionLiterals)
            }
            val totalExpectedScore =
                requiredTargets
                    .sumOf { it.target.toLong() * targetStats.scaledWeight(it) }
                    .coerceAtLeast(1L)
            val overshoot = statBuilder.overshootScore(requiredTargets, totalExpectedScore, targetStats, mmOvershootEncoding)
            // P2b stage 2: pin the primary at stage 1's proven value, maximize overshoot alone —
            // provably the same lexicographic optimum as the folded objective, without its domain.
            if (mmOvershootPinnedPrimary != null) {
                addEquality(masteryScore, newConstant(mmOvershootPinnedPrimary))
                return MostMasteriesObjectiveVars(overshoot, staticallyInfeasible, assumptionLiterals = assumptionLiterals)
            }
            return MostMasteriesObjectiveVars(
                withOvershootTieBreaker(masteryScore, masteryScoreReach, overshoot, totalExpectedScore),
                staticallyInfeasible,
                assumptionLiterals = assumptionLiterals
            )
        }

        // §8.5 S-D: the recycled hard-leg infeasibility core as a logically-implied soft no-good.
        if (mmSoftNoGoodCore != null) {
            statBuilder.addInfeasibilityCoreNoGood(mmSoftNoGoodCore)
        }

        // §8.2 S-A: the outer driver owns the penalty axis — bucket-constrained sub-model, no
        // product equality. Soft model with required targets only (the hard leg returned above).
        if (mmPenaltyBucketInterval != null) {
            val bucketCore =
                constrainPenaltyBucketInterval(
                    statBuilder,
                    targetStats,
                    masteryScore,
                    masteryScoreReach,
                    mmPenaltyBucketInterval,
                    mmPenaltyBucketFoldedObjective,
                    mmPenaltyGeometryProbe
                )
            if (bucketCore != null) {
                if (!bucketCore.singletonFolded) {
                    return MostMasteriesObjectiveVars(bucketCore.objective, penaltyBucketProbeVars = bucketCore.probeVars)
                }
                val overshoot =
                    statBuilder.overshootScore(requiredTargets, bucketCore.totalExpectedScore, targetStats, MmOvershootEncoding.CURRENT)
                return MostMasteriesObjectiveVars(
                    withOvershootTieBreaker(bucketCore.objective, bucketCore.objectiveBound, overshoot, bucketCore.totalExpectedScore),
                    penaltyBucketProbeVars = bucketCore.probeVars
                )
            }
        }

        val penalized = applyConstraintPenalty(params, statBuilder, masteryScore, masteryScoreReach)
        if (requiredTargets.isEmpty()) {
            // §8.4 S-C: with no required target the penalty is a passthrough, so the S-C probe vars
            // (tier, DI factor) ride the shared capture channel.
            return MostMasteriesObjectiveVars(penalized.objective, penaltyBucketProbeVars = statBuilder.mmDiFactorProbeVars)
        }

        val totalExpectedScore =
            requiredTargets
                .sumOf { it.target.toLong() * targetStats.scaledWeight(it) }
                .coerceAtLeast(1L)

        // Lexicographic tie-breaker: among builds the primary objective ranks equally, prefer the one
        // that exceeds the required targets the most (weighted by the same per-constraint priorities).
        // This is what makes the solver spend otherwise objective-neutral skill points into HP/CC%
        // (and, among ties, pick gear that overshoots) instead of leaving them unused — free in-game
        // value the player would always take. It can never trade a maximized-mastery point for
        // overshoot; see [withOvershootTieBreaker].
        // The alternative overshoot encodings rely on the hard `actual >= target` constraints above.
        // The soft fallback therefore always keeps the shipped exact chain.
        val overshoot = statBuilder.overshootScore(requiredTargets, totalExpectedScore, targetStats, MmOvershootEncoding.CURRENT)
        return MostMasteriesObjectiveVars(withOvershootTieBreaker(penalized.objective, penalized.bound, overshoot, totalExpectedScore))
    }

    /**
     * The two max-damage objective vars: [rawScore] is the **unpenalized** per-turn damage proxy (before the
     * survivability floor and the required-target multiplier) — the value in [CertLedger] units, surfaced so the
     * post-search certificate can compare against it even for required-target requests; [objective] is what
     * CP-SAT actually maximizes.
     */
    private data class MaxDamageObjectiveVars(
        val rawScore: IntVar,
        val objective: IntVar,
        // C2: true when a hard-constraints solve is PROVABLY infeasible (a required target exceeds its reachable
        // ceiling). Lets [optimize] skip the doomed CP-SAT solve. Always false outside the hard-constraints path.
        val staticallyInfeasible: Boolean = false,
        // §8.2bis S-E only: the (core, bucket) vars of a bucket-interval sub-model (soft leg); null otherwise.
        val penaltyBucketProbeVars: Pair<IntVar, IntVar>? = null,
    )

    /**
     * Objective for "max-damage" mode: maximize expected damage for the requested [DamageScenario]
     * (Wakfu's exact formula, see [FindMaxDamageScoring]). The build-dependent core is the product
     * `D · Graw` with `D = 100 + ΣDI`, `Graw = 400·M + crit·(M + 5·criticalMastery)` and
     * `M = 100 + ΣMastery` — derived so that `D·Graw ∝ E[dmg]` (the scenario's constant Base /
     * orientation / resistance factors are dropped since they scale every build equally). Required
     * AP/MP/range/… targets are then enforced with the same shortfall penalty as most-masteries mode.
     * Unlike most-masteries this has no overshoot tie-breaker: the damage objective already strongly
     * differentiates builds, so there is no large class of objective-ties left to refine. Returns both the
     * penalized [MaxDamageObjectiveVars.objective] and the unpenalized [MaxDamageObjectiveVars.rawScore].
     */
    private fun CpModel.buildMaxDamageObjective(
        params: WakfuBestBuildParams,
        statBuilder: StatBuilder,
        objectiveCutoff: Long? = null,
        // Hard-constraints-first solve: enforce the required AP/MP/range/… targets as HARD constraints
        // (`actual ≥ target`) under a PLAIN damage objective, instead of the soft shortfall penalty. The
        // caller ([WakfuBuildSolver.optimize] with hardConstraints = true) tries this first; if the model is
        // INFEASIBLE (unreachable targets) it re-solves with the penalty (this flag false). See
        // [StatBuilder.addRequiredTargetHardConstraints].
        hardConstraints: Boolean = false,
        // §8.2bis S-E: outer bucket B&B on the SOFT leg's penalty axis — see [SolverTuning.mmPenaltyBucketInterval].
        penaltyBucketInterval: IntRange? = null,
        penaltyBucketFoldedObjective: Boolean = false,
        penaltyGeometryProbe: ((Int, LongArray, Long) -> Unit)? = null,
    ): MaxDamageObjectiveVars {
        statBuilder.applyOutOfCombatCaps()
        // External-loop AP probe: pin the build to exactly N AP so each breakpoint can be evaluated (used by the
        // debuff AP-window probes in MaxDamageSearch).
        params.maxDamageApTarget?.let { addEquality(statBuilder.actionPointVar(), newConstant(it.toLong())) }
        params.maxDamageMpPin?.let { addEquality(statBuilder.movementPointVar(), newConstant(it.toLong())) }
        val damageScore = statBuilder.perTurnDamageScore(params.damageScenario, params.character.clazz, objectiveCutoff)
        // Survivability soft-floor (opt-in): gently tax the damage score when the build's effective-HP
        // proxy is below the floor, BEFORE the hard-target penalty. Folding it into the core score (rather
        // than chaining a second multiply onto the already-near-Long.MAX/2 penalized objective) keeps the
        // objective on the same DAMAGE_PERTURN_ABS_MAX domain, so applyConstraintPenalty's bounds are untouched.
        val scenario = params.damageScenario
        val survivableScore =
            if (scenario.survivabilityFloor && scenario.minEffectiveHp > 0) {
                // Clamp to the proxy's reachable ceiling — a min-EHP above EHP_MAX would be unsatisfiable by
                // construction and collapse every build's multiplier toward zero (a damage-blind objective).
                applySurvivabilityFloor(statBuilder, damageScore, scenario.minEffectiveHp.toLong().coerceAtMost(EHP_MAX))
            } else {
                damageScore
            }
        // rawScore is the unpenalized damage proxy (`damageScore`), the value the certificate ledger bounds.
        // The survivability floor and the required-target penalty both wrap it into `objective`; the certificate
        // does not model either, so [proveOptimality] compares against rawScore (and bails on a survivability
        // floor, whose per-build multiplier makes even rawScore non-comparable).
        // HARD-constraints mode: required targets are `actual ≥ target` constraints (added below) and the
        // objective is the PLAIN damage score — no penalty product, so the model is the shape CP-SAT proves.
        // If no required target exists the hard pass is identical to the un-penalised solve.
        if (hardConstraints) {
            val staticallyInfeasible = statBuilder.addRequiredTargetHardConstraints()
            return MaxDamageObjectiveVars(rawScore = damageScore, objective = survivableScore, staticallyInfeasible = staticallyInfeasible)
        }
        // §8.2bis S-E: the outer driver owns the penalty axis — same decomposition as most-masteries
        // S-A, minus the overshoot fold (max-damage has none). The core here is the survivable score,
        // so an opted-in survivability floor stays INSIDE the sub-model (S-E removes only the
        // required-target product).
        if (penaltyBucketInterval != null) {
            val bucketCore =
                constrainPenaltyBucketInterval(
                    statBuilder,
                    params.targetStats,
                    survivableScore,
                    DAMAGE_PERTURN_ABS_MAX,
                    penaltyBucketInterval,
                    penaltyBucketFoldedObjective,
                    penaltyGeometryProbe
                )
            if (bucketCore != null) {
                return MaxDamageObjectiveVars(
                    rawScore = damageScore,
                    objective = bucketCore.objective,
                    penaltyBucketProbeVars = bucketCore.probeVars
                )
            }
        }
        return MaxDamageObjectiveVars(
            rawScore = damageScore,
            objective = applyConstraintPenalty(params, statBuilder, survivableScore, DAMAGE_PERTURN_ABS_MAX).objective
        )
    }

    /**
     * Multiplies the max-damage [coreScore] by a **gentle** survivability penalty so a build whose
     * effective-HP proxy ([StatBuilder.effectiveHpVar]) is below [minEffectiveHp] ranks below an
     * equal-damage tankier build, while a build at or above the floor is left untouched. The penalty
     * reuses the exact required-target machinery — bucket `min(EHP, floor)` against the floor, look the
     * bucket up in a power table, multiply — but with a power-2 table (not the power-6 used for hard
     * AP/MP/range targets), so survivability only *nudges* the optimum, never dominates damage.
     *
     * Because the table is normalised so the at-or-above-floor bucket maps to [MAX_SURVIVABILITY_MULTIPLIER]
     * and we divide the product back out by that same max, meeting the floor is an exact no-op
     * (`score · max / max = score`) and missing it scales the score down by `bucket^2 / maxIndex^2` (at
     * most ×1/[MAX_SURVIVABILITY_MULTIPLIER] — the table is floored at 1, so a hopeless floor still ranks
     * builds by damage) — a smooth soft tax that vanishes at the floor. The result is clamped back onto [DAMAGE_PERTURN_ABS_MAX]
     * so downstream bounds are unchanged.
     */
    private fun CpModel.applySurvivabilityFloor(
        statBuilder: StatBuilder,
        coreScore: IntVar,
        minEffectiveHp: Long,
    ): IntVar {
        val ehp = statBuilder.effectiveHpVar()
        // cappedEhp = min(EHP, floor): only the shortfall below the floor matters; overshoot is not rewarded.
        val cappedEhp = newIntVar(0L, minEffectiveHp, "ehpCappedAtFloor")
        addMinEquality(cappedEhp, arrayOf(ehp, newConstant(minEffectiveHp)))

        val (indexVar, maxIndex) = bucketedIndex(cappedEhp, minEffectiveHp)
        val powerTable = buildGentlePowerTable(maxIndex.toLong())

        val bucketMultiplier = newIntVar(0, powerTable.maxValue, "survivabilityBucketMultiplier")
        addElement(indexVar, powerTable.values, bucketMultiplier)
        // The integer bucketing can map the floor value itself to maxIndex-1 (when the bucket size doesn't
        // divide the floor), which would tax a build that MEETS the floor. Force the multiplier to its max
        // whenever the floor is cleared (cappedEhp == floor ⟺ EHP ≥ floor), so "meeting the floor is an exact
        // no-op" holds for every floor, not just floors ≤ MAX_POWER_TABLE_INDEX.
        val clearsFloor = newBoolVar("ehpClearsFloor")
        addEquality(cappedEhp, newConstant(minEffectiveHp)).onlyEnforceIf(clearsFloor)
        addLessOrEqual(cappedEhp, newConstant(minEffectiveHp - 1)).onlyEnforceIf(clearsFloor.not())
        val clearsBonus = newIntVar(0L, powerTable.maxValue, "survivabilityClearsBonus")
        addEquality(clearsBonus, LinearExpr.term(clearsFloor, powerTable.maxValue))
        val multiplier = newIntVar(0, powerTable.maxValue, "survivabilityMultiplier")
        addMaxEquality(multiplier, arrayOf(bucketMultiplier, clearsBonus))

        // boosted = coreScore · multiplier, then ÷ maxMultiplier → back onto the core's domain.
        val boostedBound = safeMultiply(DAMAGE_PERTURN_ABS_MAX, powerTable.maxValue)
        val boosted = newIntVar(0L, boostedBound, "survivabilityBoosted")
        addMultiplicationEquality(boosted, coreScore, multiplier)
        val penalized = newIntVar(0L, DAMAGE_PERTURN_ABS_MAX, "survivabilityPenalized")
        addDivisionEquality(penalized, boosted, newConstant(powerTable.maxValue.coerceAtLeast(1L)))
        return penalized
    }

    /**
     * Wraps a build-dependent [coreScore] (mastery sum or expected damage) with the required-target
     * shortfall penalty: when no required targets exist the core score is the objective; otherwise it
     * is multiplied by a power-6 penalty multiplier driven by how fully the AP/MP/range/… constraints
     * are met — floored at 1 ([penaltyMultiplier]), so a request whose targets are far out of reach for
     * every build still ranks builds by their core instead of collapsing to a flat 0. Returns the
     * penalized objective var and the absolute bound of its domain — the latter is what the
     * most-masteries overshoot tie-breaker needs. [coreScoreAbsMax] bounds the result.
     *
     * A FLOOR below 0 (a required row of target 0, [StatBuilder.floorReads]) HALVES that product — once, however many floors
     * are below 0, as precision halves: the core is halved (truncated) before it meets the multiplier. A row of target 0 has
     * no share of the power-6 ratio (its expected score is 0), so the halving is its penalty: a factor ≤ 1, so the leg stays
     * feasible, never raises an objective (every certificate's bound, which ignores the floors, stays an upper bound of it),
     * and it applies on top of the multiplier floor, so even a build whose targets are hopeless still pays it. The scorers
     * divide by 2 on top of their shortfall factor.
     */
    private fun CpModel.applyConstraintPenalty(
        params: WakfuBestBuildParams,
        statBuilder: StatBuilder,
        coreScore: IntVar,
        coreScoreAbsMax: Long,
    ): PenalizedObjective {
        val targetStats = params.targetStats
        val requiredTargets = targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        if (requiredTargets.isEmpty()) {
            return PenalizedObjective(coreScore, coreScoreAbsMax)
        }

        val totalExpectedScore =
            requiredTargets
                .sumOf { it.target.toLong() * targetStats.scaledWeight(it) }
                .coerceAtLeast(1L)

        val totalActualScore = statBuilder.totalActualScore(requiredTargets, totalExpectedScore, targetStats)
        val totalActualScoreForPenalty = maxVar(totalActualScore, 1L, totalExpectedScore, "totalActualScoreForPenalty")

        val (indexVar, maxIndex) = bucketedIndex(totalActualScoreForPenalty, totalExpectedScore)
        val powerTable = buildPowerTable(maxIndex.toLong())

        val multiplier = newIntVar(0, powerTable.maxValue, "penaltyMultiplier")
        addElement(indexVar, powerTable.values, multiplier)

        // The floors' halving is taken on the CORE, before the product: the product's domain is near int64's limit (CP-SAT
        // refuses a model whose variable domains sum past it), the core's is small. `⌊core / 2⌋ × multiplier` is the halved
        // objective up to the truncation of an odd core — still ≤ the unhalved one, which is all the certificates rely on.
        val floorBroken = statBuilder.floorViolation
        val core =
            if (floorBroken == null) {
                coreScore
            } else {
                // Declared on the core's own domain (CP-SAT's division truncates toward 0, like Kotlin's), so the product below
                // keeps the core's tight bounds — a loose factor would weaken its relaxation.
                val coreDomain = coreScore.domain
                val (lo, hi) = coreDomain.min() to coreDomain.max()
                val halved = newIntVar(lo / 2, hi / 2, "coreHalved")
                addDivisionEquality(halved, coreScore, newConstant(2L))
                val penalized = newIntVar(minOf(lo, lo / 2), maxOf(hi, hi / 2), "coreFloorPenalized")
                addEquality(penalized, coreScore).onlyEnforceIf(floorBroken.not())
                addEquality(penalized, halved).onlyEnforceIf(floorBroken)
                penalized
            }

        val maxObjective = safeMultiply(coreScoreAbsMax, powerTable.maxValue)
        val objectiveBound = maxObjective.coerceAtMost(Long.MAX_VALUE / 2)
        val objective = newIntVar(-objectiveBound, objectiveBound, "objectiveScore")
        addMultiplicationEquality(objective, core, multiplier)
        return PenalizedObjective(objective, objectiveBound)
    }

    internal data class PenalizedObjective(
        val objective: IntVar,
        val bound: Long,
    )

    /** Result of [constrainPenaltyBucketInterval] — see its doc. */
    private class BucketIntervalCore(
        // The bare core (interval node) or the linear `core × power6(b)` (singleton folded node).
        val objective: IntVar,
        val objectiveBound: Long,
        val singletonFolded: Boolean,
        val totalExpectedScore: Long,
        // (core, bucket) — read on the solved assignment by the outer driver's incumbent capture.
        val probeVars: Pair<IntVar, IntVar>,
    )

    /**
     * §8.2 S-A sub-model: the penalty bucket is CONSTRAINED to [interval] and the
     * `core × multiplier` product is absent from the searched model. Mode-agnostic: the same
     * required-target penalty axis wraps the most-masteries core AND the max-damage soft leg's
     * survivable score (§8.2bis S-E), so both objective builders share this.
     *
     * - Interval node (or [foldedObjective] false): the objective is the bare core. The outer driver
     *   turns its proven bound C into a sound interval bound: `C × power6(hi)` when `C ≥ 0`, else
     *   `C × power6(lo)` (the power table is monotone non-decreasing, so a negative core is hurt
     *   LEAST by the smallest multiplier).
     * - Singleton node with [foldedObjective]: the multiplier is the constant `power6(b)`, so the
     *   exact penalized objective is linear in the core — no product equality remains. The
     *   most-masteries caller folds its overshoot tie-break on top; max-damage has none.
     *
     * The bucket chain (totalActualScore → maxVar → bucketedIndex) is byte-identical to
     * [applyConstraintPenalty]'s, so bucket semantics cannot drift between the two models.
     * Returns null when no required target exists (no penalty axis to decompose).
     */
    private fun CpModel.constrainPenaltyBucketInterval(
        statBuilder: StatBuilder,
        targetStats: TargetStats,
        core: IntVar,
        coreAbsMax: Long,
        interval: IntRange,
        foldedObjective: Boolean,
        geometryProbe: ((Int, LongArray, Long) -> Unit)?,
    ): BucketIntervalCore? {
        val requiredTargets = targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }
        if (requiredTargets.isEmpty()) return null
        // These measurement seams price the bucket's power-6 multiplier only, not the floors' halving (applyConstraintPenalty):
        // refuse a request with a floor rather than search a model that is not the soft leg's.
        require(!targetStats.hasFloors) { "the penalty-bucket seams do not model the floors' halving" }
        val totalExpectedScore =
            requiredTargets
                .sumOf { it.target.toLong() * targetStats.scaledWeight(it) }
                .coerceAtLeast(1L)
        val totalActualScore = statBuilder.totalActualScore(requiredTargets, totalExpectedScore, targetStats)
        val totalActualScoreForPenalty = maxVar(totalActualScore, 1L, totalExpectedScore, "totalActualScoreForPenalty")
        val (indexVar, maxIndex) = bucketedIndex(totalActualScoreForPenalty, totalExpectedScore)
        val powerTable = buildPowerTable(maxIndex.toLong())
        geometryProbe?.invoke(maxIndex, powerTable.values, totalExpectedScore)

        val lo = interval.first.coerceIn(0, maxIndex).toLong()
        val hi = interval.last.coerceIn(0, maxIndex).toLong()
        addGreaterOrEqual(indexVar, lo)
        addLessOrEqual(indexVar, hi)

        if (!foldedObjective || lo != hi) {
            return BucketIntervalCore(core, coreAbsMax, false, totalExpectedScore, core to indexVar)
        }

        val constMultiplier = powerTable.values[lo.toInt()]
        val foldedBound = safeMultiply(coreAbsMax, constMultiplier).coerceAtLeast(1L)
        val folded = newIntVar(-foldedBound, foldedBound, "bucketFoldedScore")
        addEquality(folded, LinearExpr.term(core, constMultiplier))
        return BucketIntervalCore(folded, foldedBound, true, totalExpectedScore, core to indexVar)
    }

    /**
     * Folds a lexicographic overshoot tie-breaker under [primaryObjective], returning
     * `primaryObjective * OVERSHOOT_SCALE + bonus` where `bonus ∈ [0, OVERSHOOT_SCALE)` is the
     * weighted overshoot normalised into that range. Because `bonus < OVERSHOOT_SCALE`, even a
     * one-unit improvement in the (integer) primary objective — worth `OVERSHOOT_SCALE` after scaling
     * — always dominates any overshoot gain. So this never sacrifices a maximized-mastery point for
     * overshoot; it only ranks builds the primary objective already considers tied. [totalExpectedScore]
     * (≥ 1) is the same denominator the penalty uses, so the bonus is proportional to how far the
     * build exceeds its targets relative to what was asked.
     */
    private fun CpModel.withOvershootTieBreaker(
        primaryObjective: IntVar,
        primaryBound: Long,
        rawOvershoot: IntVar,
        totalExpectedScore: Long,
    ): IntVar {
        val scaledRaw = newIntVar(0, safeMultiply(totalExpectedScore, OVERSHOOT_SCALE - 1), "overshootScaled")
        addEquality(scaledRaw, LinearExpr.term(rawOvershoot, OVERSHOOT_SCALE - 1))

        val bonus = newIntVar(0, OVERSHOOT_SCALE - 1, "overshootBonus")
        addDivisionEquality(bonus, scaledRaw, newConstant(totalExpectedScore))

        val combinedBound = safeMultiply(primaryBound, OVERSHOOT_SCALE) + OVERSHOOT_SCALE
        val combined = newIntVar(-combinedBound, combinedBound, "objectiveWithOvershoot")
        addEquality(
            combined,
            LinearExpr
                .newBuilder()
                .addTerm(primaryObjective, OVERSHOOT_SCALE)
                .addTerm(bonus, 1)
                .build()
        )
        return combined
    }

    private fun CpModel.buildPrecisionObjective(
        params: WakfuBestBuildParams,
        statBuilder: StatBuilder,
    ): IntVar {
        statBuilder.applyOutOfCombatCaps()
        return statBuilder.precisionScore(params.targetStats)
    }

    /** Exact score of a candidate build, using the scorer that matches the requested mode. */
    private fun scoreFor(
        params: WakfuBestBuildParams,
        combination: BuildCombination,
    ): BigDecimal =
        when (params.scoreComputationMode) {
            ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                FindMostMasteriesFromInputScoring.computeScore(
                    targetStats = params.targetStats,
                    buildCombination = combination,
                    characterBaseCharacteristics = params.character.baseCharacteristicValues
                )

            ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                FindClosestBuildFromInputScoring.computeScore(
                    targetStats = params.targetStats,
                    buildCombination = combination,
                    characterBaseCharacteristics = params.character.baseCharacteristicValues
                )

            ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE ->
                maxDamageRotationScore(params, combination)
        }

    /**
     * Spell-aware / boss-aware max-damage score: the best **per-turn rotation** damage the build can
     * deal — across all candidate elements (boss-aware element choice) using the class's real spell kit
     * — divided by the same required-target shortfall penalty as [FindMaxDamageScoring]. Kept in lockstep
     * with [buildMaxDamageObjective]: both compute `max_e (throughput_e × perHit_e × resFactor_e)` and
     * apply the AP/MP/range penalty, so the build the objective maximizes is the one the solver emits.
     * The rotation also folds in the scenario's positional ×multiplier (face/side/back) — a uniform constant
     * the objective deliberately drops since it scales every build equally, so it sharpens the *displayed*
     * per-turn damage without changing which build (or element) ranks highest.
     */
    private fun maxDamageRotationScore(
        params: WakfuBestBuildParams,
        combination: BuildCombination,
    ): BigDecimal {
        val rotationDamage =
            SpellRotationOptimizer
                .bestAcrossElements(combination, params.character, params.character.clazz, params.damageScenario)
                .totalExpectedDamage
                .toBigDecimal()
        // The scorer's own stats (FindMaxDamageScoring.penaltyStats): the real resistance targets — an emptyMap read them as 0,
        // mis-ranking builds when the user sets a required resistance in max-damage mode — their rolls (and a floor's) placed
        // where the solver's joint fold places them, and the scenario-gated sublimation effects applied as the model applies them.
        val stats = FindMaxDamageScoring.penaltyStats(params.targetStats, combination, params.character.baseCharacteristicValues, params.damageScenario)
        val penalty = FindMaxDamageScoring.requiredConstraintPenaltyFactor(params.targetStats, stats)
        return rotationDamage.divide(penalty, 4, RoundingMode.FLOOR)
    }

    private suspend fun executeSolverAndEmitResults(
        model: CpModel,
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        runeModel: RuneModel,
        subModel: SublimationModel,
        // Max-damage only: the unpenalized damage-proxy var, read on each emitted solution to stamp
        // [SolverResult.maxDamageRawProxy] (the certificate-comparable value). Null in the other modes.
        maxDamageRawScoreVar: IntVar?,
        scope: ProducerScope<SolverResult<BuildCombination>>,
        tuning: SolverTuning?,
        // Hands the freshly-created solver to the caller so it can stop the (otherwise uninterruptible) native
        // solve from another thread on flow teardown — see the `awaitClose` in [optimize].
        onSolverReady: (CpSolver) -> Unit = {},
        // C8(3): floor for best-effort INTERMEDIATE emissions — the greedy warm start already streamed a
        // build with this score, and consumers keep the LAST emission, so streaming a worse snapshot would
        // visibly regress the displayed build. The final (guaranteed) send stays unconditional.
        suppressBelowScore: BigDecimal? = null,
        // P2b stage 2 (overshoot-only solve with the primary pinned): the final emission's `isOptimal`
        // must report STAGE 1's proof of the primary (the value users care about), not this short
        // secondary solve's own status. Null = report this solve's status (every other caller).
        finalIsOptimalOverride: Boolean? = null,
        // P2b stage 2: cap this solve's PRODUCTION wall budget (the pinned-primary overshoot solve is
        // near-forced and must never eat the user's remaining duration). Null = the params duration.
        maxWallSecondsOverride: Double? = null,
        // Backup certificate (§8.9bis): stamp [SolverResult.mostMasteriesObjective] on every emission —
        // set by [optimize] iff the searched objective is certificate-comparable as is (the MM soft leg,
        // in penalized units; or a request without required targets, where both legs coincide).
        mmObjectiveComparable: Boolean = false,
        // ...or convertible: the MM hard leg with required targets stamps its objective scaled into soft
        // units by this multiplier ([MostMasteriesCertificate.fullTargetsMultiplier]).
        mmHardLegMultiplier: Long? = null,
        // P2b stage 2 (review fix 2026-07-20): the overshoot solve's own objective is NOT the MM
        // primary — stamp the PINNED stage-1 primary instead so the final displayed emission keeps
        // a certificate-comparable objective (else the backup badge gate reads null and never runs).
        mmObjectiveOverride: Long? = null,
        // Relax-then-check ([relaxThenCheck]) — the knobs of its stages:
        //  - the TUNED path's deterministic budget of this stage (null = the tuning's whole budget);
        maxDeterministicTimeOverride: Double? = null,
        //  - an intermediate build is shown only when this accepts it (the relaxed stage: its floors held in the scorers' read);
        emitFilter: ((BuildCombination, BigDecimal) -> Boolean)? = null,
        //  - false: no final send (the relaxed stage's final build is no result of the leg — its outcome carries it instead);
        sendFinal: Boolean = true,
        //  - the leg's start, which the progress percentage counts from (null = this solve's own start).
        progressStartMs: Long? = null,
    ): SolveOutcome? {
        val solver = CpSolver()
        onSolverReady(solver)
        solver.parameters.logSearchProgress = false
        // Max-damage declares its objective-chain vars with reachable domains, which finally makes the LP
        // relaxation worth building; engage the level-2 linearization so CP-SAT can certify the bound and
        // prove OPTIMAL. Gated to max-damage to leave the other modes' tuned search paths untouched.
        val maxDamage = params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
        if (maxDamage) solver.parameters.linearizationLevel = 2
        if (tuning == null) {
            // Production: a parallel portfolio (CP-SAT's equivalent of the GA's parallel scoring),
            // bounded by the user's wall-clock search duration.
            //
            // We deliberately leave one core for the host: CP-SAT spawns this many *native* threads
            // and pins them at 100%, which otherwise starves the Compose render thread / EDT and
            // makes the GUI window visibly freeze during a search. One fewer worker is a negligible
            // throughput loss next to a responsive UI (and keeps the terminal responsive for the CLI).
            //
            // The single-iteration presolve cap was a symptom of the old loose domains (full presolve never
            // terminated on the loose max-damage objective). With reachable domains presolve folds the tight
            // bounds through the constraint network quickly, so max-damage affords a few iterations — the
            // other modes keep the light single pass their larger/looser models rely on to stay in budget.
            solver.parameters.maxPresolveIterations = if (maxDamage) 3 else 1
            // Millisecond precision (not inWholeSeconds, which FLOORS): the max-damage external loop slices
            // its phase budget into sub-second per-probe limits, and a floored 0.0 means "no time limit" to
            // OR-Tools — which is exactly how a fan-out of probes could run unbounded. Floored to 50ms so a
            // valid budget is never rounded down to the unlimited sentinel.
            solver.parameters.maxTimeInSeconds =
                (maxWallSecondsOverride ?: (params.searchDuration.inWholeMilliseconds.toDouble() / 1000.0)).coerceAtLeast(0.05)
            solver.parameters.numSearchWorkers =
                params.solverWorkers ?: (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
        } else {
            // Deterministic, machine-independent solve for tests — see [SolverTuning]. A fixed worker
            // count + seed + a deterministic-time budget (not wall-clock) make CP-SAT reach the same
            // proven optimum on every machine, removing the flakiness of a wall-clock-bounded search.
            // Presolve runs in full here (tests only ever solve the small, prefiltered model) so the
            // optimality proof finishes quickly.
            solver.parameters.numSearchWorkers = tuning.numSearchWorkers
            solver.parameters.randomSeed = tuning.randomSeed
            solver.parameters.maxDeterministicTime = maxDeterministicTimeOverride ?: tuning.maxDeterministicTime
            if (tuning.interleaveSearch) solver.parameters.interleaveSearch = true
            tuning.maxPresolveIterationsOverride?.let { solver.parameters.maxPresolveIterations = it }
            tuning.linearizationLevelOverride?.let { solver.parameters.linearizationLevel = it }
            // P0.5 diagnostics: re-enable the search log this function hardcodes off above and route it
            // through the Java-side callback (the native stdout path bypasses the test JVM's capture).
            // Tuned path only — production/GUI can never turn it on.
            tuning.searchLogSink?.let { sink ->
                solver.parameters.logSearchProgress = true
                solver.parameters.logToStdout = false
                solver.setLogCallback { line -> sink(line) }
            }
        }

        val startTime = System.currentTimeMillis()
        val progressOrigin = progressStartMs ?: startTime

        val cb =
            object : CpSolverSolutionCallback() {
                private var lastEmitMs = 0L

                override fun onSolutionCallback() {
                    // The native solve blocks the IO thread, so coroutine cancellation can't interrupt
                    // it directly; stopping the search here (next time a solution is found) is the
                    // CP-SAT idiom that lets the GUI's cancel actually end the work. Kept BEFORE the
                    // throttle so cancel latency is unchanged.
                    if (!scope.isActive) {
                        stopSearch()
                        return
                    }

                    // E8 fallback: the first solution is already at the raw-score floor (= the certificate
                    // bound), so stop immediately — the guaranteed final send below delivers it. No
                    // intermediate emission needed.
                    if (tuning?.stopAtFirstSolution == true) {
                        stopSearch()
                        return
                    }

                    // Throttle the heavy rescore: building + scoring every improving solution on the solve
                    // thread starves the search. Snapshots are best-effort progress (trySend, consumers keep
                    // only the last), so skipping some is invisible — the proven final build is emitted
                    // separately and unconditionally below. The first solution always passes (lastEmitMs = 0).
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs < INTERMEDIATE_EMIT_THROTTLE_MS) return
                    lastEmitMs = now

                    val combination = solutionToBuild(params, allEquips, equipVars, skillVars, runeModel, subModel) { value(it) }
                    val actualScore = scoreFor(params, combination)
                    if (suppressBelowScore != null && actualScore < suppressBelowScore) return
                    if (emitFilter != null && !emitFilter(combination, actualScore)) return

                    val progress = ((now - progressOrigin).toDouble() / params.searchDuration.inWholeMilliseconds.toDouble() * 100).toInt()
                    scope.trySend(
                        SolverResult(
                            combination,
                            actualScore,
                            progress.coerceAtMost(100),
                            maxDamageObjective = if (maxDamage) objectiveValue().toLong() else null,
                            maxDamageRawProxy = if (maxDamage) maxDamageRawScoreVar?.let { value(it) } else null,
                            mostMasteriesObjective =
                                mmStampedObjective(objectiveValue().toLong(), mmObjectiveComparable, mmHardLegMultiplier, mmObjectiveOverride)
                        )
                    )
                }
            }

        return try {
            val status = solver.solve(model, cb)
            logger.debug { "Solver status returned: $status" }
            logger.debug { "Solver response stats:\n${solver.responseStats()}" }

            if (status == com.google.ortools.sat.CpSolverStatus.OPTIMAL || status == com.google.ortools.sat.CpSolverStatus.FEASIBLE) {
                val finalComb = solutionToBuild(params, allEquips, equipVars, skillVars, runeModel, subModel) { solver.value(it) }
                val finalScore = scoreFor(params, finalComb)
                // Guaranteed delivery (suspending send, not trySend): intermediate best-so-far
                // emissions are best-effort progress and may be dropped under back-pressure, but the
                // final/optimal build must never be lost to a saturated callbackFlow buffer.
                if (sendFinal && scope.isActive) {
                    scope.send(
                        SolverResult(
                            individual = finalComb,
                            matchPercentage = finalScore,
                            progressPercentage = 100,
                            // OPTIMAL (including stage 1's override) proves only the searched pool. The
                            // heuristic top-8 prefilter can discard the global optimum in EVERY mode.
                            isOptimal =
                                !needsItemPrefilter(params.targetStats) &&
                                    (finalIsOptimalOverride ?: (status == com.google.ortools.sat.CpSolverStatus.OPTIMAL)),
                            maxDamageObjective = if (maxDamage) solver.objectiveValue().toLong() else null,
                            maxDamageRawProxy = if (maxDamage) maxDamageRawScoreVar?.let { solver.value(it) } else null,
                            mostMasteriesObjective =
                                mmStampedObjective(solver.objectiveValue().toLong(), mmObjectiveComparable, mmHardLegMultiplier, mmObjectiveOverride)
                        )
                    )
                }
                SolveOutcome(
                    status = status,
                    objectiveValue = solver.objectiveValue().toLong(),
                    bestObjectiveBound = solver.bestObjectiveBound().toLong(),
                    deterministicTime = deterministicTimeFrom(solver.responseStats()),
                    branches = solver.numBranches(),
                    conflicts = solver.numConflicts(),
                    finalBuild = finalComb,
                    finalScore = finalScore
                )
            } else {
                SolveOutcome(
                    status = status,
                    objectiveValue = null,
                    bestObjectiveBound = solver.bestObjectiveBound().toLong(),
                    deterministicTime = deterministicTimeFrom(solver.responseStats()),
                    branches = solver.numBranches(),
                    conflicts = solver.numConflicts()
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Solver failed while searching for the best build." }
            null
        }
    }

    /**
     * The termination of one CP-SAT solve: the REAL solver status (so callers can distinguish a
     * proven-INFEASIBLE model from an UNKNOWN timeout without a solution — the flow alone cannot,
     * both emit nothing) and the objective value when a solution exists (P2b stage-1 → stage-2 pin).
     */
    internal class SolveOutcome(
        val status: com.google.ortools.sat.CpSolverStatus,
        val objectiveValue: Long?,
        val bestObjectiveBound: Long,
        val deterministicTime: Double,
        val branches: Long,
        val conflicts: Long,
        // The solve's final build and its score, when it has one — sent or not (relax-then-check's relaxed stage sends none).
        val finalBuild: BuildCombination? = null,
        val finalScore: BigDecimal? = null,
    )

    /**
     * The player's selected passive loadout: each [WakfuBestBuildParams.forcedPassives] name resolved to a
     * [Passive] of the character's class, de-duplicated and capped to the level's passive slots
     * ([PassiveCatalog.slotsForLevel]). Unknown names are dropped. Shared by the stat-folding ([StatBuilder])
     * and the result ([solutionToBuild]) so what is scored equals what the build carries.
     */
    internal fun resolvedPassives(params: WakfuBestBuildParams): List<Passive> {
        if (params.forcedPassives.isEmpty()) return emptyList()
        val slots = PassiveCatalog.slotsForLevel(params.character.level)
        return params.forcedPassives
            .mapNotNull { PassiveCatalog.findByName(params.character.clazz, it) }
            .distinct()
            .take(slots)
    }

    /**
     * Rebuilds a [BuildCombination] from a solved assignment. Skill points are mapped back by the
     * *position* of each skill in [CharacterSkills.allCharacteristic] — identical between the params'
     * skills (used to create the variables) and the fresh skills here — never by name: two distinct
     * skills share the name "Resistance Elementary" (Intelligence vs Major), so a name lookup would
     * cross-assign their points and corrupt both the build and its recomputed score.
     */

    private fun solutionToBuild(
        params: WakfuBestBuildParams,
        allEquips: List<Equipment>,
        equipVars: Map<Equipment, IntVar>,
        skillVars: Map<SkillCharacteristic, IntVar>,
        runeModel: RuneModel,
        subModel: SublimationModel,
        valueOf: (IntVar) -> Long,
    ): BuildCombination {
        val equippedItems = allEquips.filter { valueOf(equipVars.getValue(it)) > 0L }

        val optimizedSkills = CharacterSkills(params.character.level)
        val originalSkills = params.character.characterSkills.allCharacteristic
        optimizedSkills.allCharacteristic.forEachIndexed { index, skill ->
            skillVars[originalSkills[index]]?.let { skill.setPointAssigned(valueOf(it).toInt()) }
        }

        val runes =
            equippedItems
                .associateWith { equip ->
                    runeModel.runeVars[equip].orEmpty().flatMap { (stat, runeVar) ->
                        // Fold model: runeVar is a boolean pick ⇒ that one type fills ALL of the item's sockets.
                        val count =
                            if (runeModel.singleTypePerItem) {
                                if (valueOf(runeVar) > 0L) equip.maxShardSlots else 0
                            } else {
                                valueOf(runeVar).toInt()
                            }
                        val effectiveCount =
                            if (runeModel.isSuppressed(equip, stat, valueOf)) {
                                0
                            } else {
                                count
                            }
                        val rune = runeModel.runeTypeFor(runeVar, stat)
                        if (effectiveCount > 0 && rune != null) List(effectiveCount) { rune } else emptyList()
                    }
                }.filterValues { it.isNotEmpty() }

        // Sublimations keyed by carrier item: a normal sub by its assignment var, an epic/relic sub by the
        // equipped epic/relic item (whose dedicated slot hosts it). This is what the GUI renders per item.
        val epicItem = equippedItems.firstOrNull { it.rarity == Rarity.EPIC }
        val relicItem = equippedItems.firstOrNull { it.rarity == Rarity.RELIC }
        val normalCarrierItems = equippedItems.filter { it.maxShardSlots >= NORMAL_SUB_SOCKET_COST }
        var nextNormalCarrierIndex = 0
        val sublimationsByItem = mutableMapOf<Equipment, MutableList<Sublimation>>()
        for ((sub, subVar) in subModel.subVars) {
            // A cumulable NORMAL sub can be socketed multiple times: its copy count is the base var plus its copy
            // vars, and each copy lands on its OWN distinct carrier. Greedy carrier pick: any equipped ≥3-socket
            // item hosts a normal sub identically (stats come from the sub, not the item), and the model's aggregate
            // normal-carrier capacity constraint guarantees enough distinct carriers — objective-neutral. Epic/relic
            // are single-copy (never cumulable).
            val copies =
                when (sub.rarity) {
                    SublimationRarity.NORMAL ->
                        (valueOf(subVar) + subModel.copyVars[sub].orEmpty().sumOf { valueOf(it) }).toInt()
                    else -> if (valueOf(subVar) > 0L) 1 else 0
                }
            repeat(copies) {
                val carrier =
                    when (sub.rarity) {
                        SublimationRarity.NORMAL -> normalCarrierItems.getOrNull(nextNormalCarrierIndex++)
                        SublimationRarity.EPIC -> epicItem
                        SublimationRarity.RELIC -> relicItem
                    }
                if (carrier != null) sublimationsByItem.getOrPut(carrier) { mutableListOf() }.add(sub)
            }
        }

        return BuildCombination(equippedItems, optimizedSkills, runes, sublimationsByItem, resolvedPassives(params))
    }

    // Splits each skill's contribution into fixed / percent terms keyed by characteristic, mirroring
    // CharacteristicValues. The Major "% Inflicted Damage" aptitude lands in fixed[DAMAGE_INFLICTED];
    // only the max-damage objective reads that stat, so it stays inert in the most-masteries / precision
    // modes — exactly like the scorer. See the NOTE in computeCharacteristicsValues.
    internal fun buildSkillTerms(skillVars: Map<SkillCharacteristic, IntVar>): SkillTerms {
        val fixed = mutableMapOf<Characteristic, MutableList<Term>>()
        val percent = mutableMapOf<Characteristic, MutableList<Term>>()

        fun addTerm(
            char: Characteristic?,
            variable: IntVar,
            unitValue: Int,
            unitType: UnitType,
        ) {
            if (char == null || unitValue == 0) return
            val target = if (unitType == UnitType.FIXED) fixed else percent
            target.getOrPut(char) { mutableListOf() }.add(Term(variable, unitValue.toLong()))
        }

        for ((skill, variable) in skillVars) {
            when (skill) {
                is SkillCharacteristic.PairedCharacteristic -> {
                    addTerm(skill.first.characteristic, variable, skill.first.unitValue, skill.first.unitType)
                    addTerm(skill.second.characteristic, variable, skill.second.unitValue, skill.second.unitType)
                }

                else -> addTerm(skill.characteristic, variable, skill.unitValue, skill.unitType)
            }
        }

        return SkillTerms(
            fixed = fixed.mapValues { it.value.toList() },
            percent = percent.mapValues { it.value.toList() }
        )
    }

    /**
     * The [SolverResult.mostMasteriesObjective] stamp: the raw objective when it is certificate-comparable
     * as is; the MM hard leg's `core × SCALE + bonus` scaled into soft units (`core × multiplier × SCALE +
     * bonus` — every hard-leg emission meets the targets, so that IS the same build's soft objective);
     * else the P2b override (null for every other caller). An overflowing conversion stamps null (no
     * badge) rather than a wrapped value.
     */
    private fun mmStampedObjective(
        objective: Long,
        comparable: Boolean,
        hardLegMultiplier: Long?,
        override: Long?,
    ): Long? =
        when {
            comparable -> objective
            hardLegMultiplier != null ->
                runCatching {
                    Math.addExact(
                        Math.multiplyExact(Math.multiplyExact(Math.floorDiv(objective, OVERSHOOT_SCALE), hardLegMultiplier), OVERSHOOT_SCALE),
                        Math.floorMod(objective, OVERSHOOT_SCALE)
                    )
                }.getOrNull()
            else -> override
        }

    /**
     * Runs [solve] under a daemon watcher that calls [CpSolver.stopSearch] every 500 ms once
     * [shouldContinue] turns false — RE-ISSUED on every tick: OR-Tools' Java stopSearch() is a silent
     * no-op until solve() has created its native wrapper, so a cancel landing while the model was still
     * being handed over would otherwise be lost and the solve would run its full budget. Inert (no
     * thread) when [shouldContinue] is null. A stopped solve returns FEASIBLE/UNKNOWN: callers must read
     * it through the sound accessors ([MaxDamageTimedProfile.soundUpper], the B&B status guard).
     */
    private fun <T> withStopWatcher(
        solver: CpSolver,
        shouldContinue: (() -> Boolean)?,
        solve: () -> T,
    ): T {
        val watcher =
            shouldContinue?.let { cont ->
                Thread {
                    try {
                        while (!Thread.currentThread().isInterrupted) {
                            if (!cont()) solver.stopSearch()
                            Thread.sleep(500)
                        }
                    } catch (_: InterruptedException) {
                        // Solve finished — nothing left to stop.
                    }
                }.apply {
                    isDaemon = true
                    name = "wakfu-proof-stop-watcher"
                    start()
                }
            }
        try {
            return solve()
        } finally {
            watcher?.interrupt()
        }
    }

    internal fun Equipment.valueFor(char: Characteristic): Int {
        val base = characteristics[char] ?: 0
        return when (char) {
            Characteristic.ACTION_POINT -> base + (characteristics[Characteristic.MAX_ACTION_POINT] ?: 0)
            Characteristic.MOVEMENT_POINT -> base + (characteristics[Characteristic.MAX_MOVEMENT_POINT] ?: 0)
            Characteristic.WAKFU_POINT -> base + (characteristics[Characteristic.MAX_WAKFU_POINTS] ?: 0)
            else -> base
        }
    }

    private fun String.toIdentifier(): String =
        lowercase()
            .replace(" ", "_")
            .replace("-", "_")

    internal fun CpModel.sumVar(
        name: String,
        vars: List<IntVar>,
        min: Long,
        max: Long,
    ): IntVar {
        if (vars.isEmpty()) return newConstant(0L)
        val sumVar = newIntVar(min, max, name)
        addEquality(sumVar, LinearExpr.sum(vars.toTypedArray()))
        return sumVar
    }

    internal fun CpModel.sumVar(
        name: String,
        terms: List<Term>,
        constant: Long,
        min: Long,
        max: Long,
    ): IntVar {
        if (terms.isEmpty()) return newConstant(constant)
        val builder = LinearExpr.newBuilder().add(constant)
        terms.forEach { builder.addTerm(it.variable, it.coefficient) }
        val sumVar = newIntVar(min, max, name)
        addEquality(sumVar, builder.build())
        return sumVar
    }

    private fun CpModel.applyPercent(
        value: IntVar,
        percent: IntVar,
        name: String,
    ): IntVar {
        val product = newIntVar(-PRODUCT_ABS_MAX, PRODUCT_ABS_MAX, "${name}_prod")
        addMultiplicationEquality(product, arrayOf(value, percent))

        val quotient = newIntVar(-(PRODUCT_ABS_MAX / 100) - 1, (PRODUCT_ABS_MAX / 100) + 1, "${name}_quot")
        addDivisionEquality(quotient, product, newConstant(100L))

        val remainder = newIntVar(-99, 99, "${name}_rem")
        addModuloEquality(remainder, product, 100L)

        val inc = newBoolVar("${name}_inc")
        addGreaterOrEqual(remainder, 50).onlyEnforceIf(inc)
        addLessOrEqual(remainder, 49).onlyEnforceIf(inc.not())

        val dec = newBoolVar("${name}_dec")
        addLessOrEqual(remainder, -51).onlyEnforceIf(dec)
        addGreaterOrEqual(remainder, -50).onlyEnforceIf(dec.not())

        addLessOrEqual(
            LinearExpr
                .newBuilder()
                .addTerm(inc, 1)
                .addTerm(dec, 1)
                .build(),
            1
        )

        val rounded = newIntVar(-(PRODUCT_ABS_MAX / 100) - 2, (PRODUCT_ABS_MAX / 100) + 2, "${name}_rounded")
        addEquality(
            rounded,
            LinearExpr
                .newBuilder()
                .addTerm(quotient, 1)
                .addTerm(inc, 1)
                .addTerm(dec, -1)
                .build()
        )

        val withPercent = newIntVar(-STAT_WITH_PERCENT_ABS_MAX, STAT_WITH_PERCENT_ABS_MAX, name)
        addEquality(
            withPercent,
            LinearExpr
                .newBuilder()
                .addTerm(value, 1)
                .addTerm(rounded, 1)
                .build()
        )
        return withPercent
    }

    private fun CpModel.maxVar(
        value: IntVar,
        minValue: Long,
        maxValue: Long,
        name: String,
    ): IntVar {
        val maxVar = newIntVar(minValue, maxValue, name)
        addMaxEquality(maxVar, arrayOf(value, newConstant(minValue)))
        return maxVar
    }

    /** clamp(value, low, high) as an IntVar with domain [low, high]. */
    internal fun CpModel.clampVar(
        value: IntVar,
        low: Long,
        high: Long,
        name: String,
    ): IntVar {
        val lowered = newIntVar(low, CLAMP_INTERMEDIATE_MAX, "${name}Lo")
        addMaxEquality(lowered, arrayOf(value, newConstant(low)))
        val clamped = newIntVar(low, high, name)
        addMinEquality(clamped, arrayOf(lowered, newConstant(high)))
        return clamped
    }

    private fun CpModel.bucketedIndex(
        totalActualScore: IntVar,
        totalExpectedScore: Long,
    ): Pair<IntVar, Int> {
        if (totalExpectedScore <= MAX_POWER_TABLE_INDEX) {
            return totalActualScore to totalExpectedScore.toInt()
        }

        val bucketSize = ceil(totalExpectedScore.toDouble() / MAX_POWER_TABLE_INDEX.toDouble()).toLong()
        val maxIndex = ((totalExpectedScore + bucketSize - 1) / bucketSize).toInt()
        val bucketVar = newIntVar(0, maxIndex.toLong(), "scoreBucket")
        addDivisionEquality(bucketVar, totalActualScore, newConstant(bucketSize))
        return bucketVar to maxIndex
    }

    /** The required-target power table over buckets `0..maxIndex` — every entry is [penaltyMultiplier]'s, floor included. */
    private fun buildPowerTable(maxIndex: Long): PowerTable {
        val powScale = penaltyPowScale(maxIndex)
        val table = LongArray(maxIndex.toInt() + 1) { index -> penaltyMultiplier(index.toLong(), powScale) }

        return PowerTable(
            values = table,
            maxValue = table.last()
        )
    }

    /**
     * Gentle power table for the survivability soft-floor: `index^SURVIVABILITY_PENALTY_POWER`, scaled so
     * the top bucket equals [MAX_SURVIVABILITY_MULTIPLIER]. Like [buildPowerTable] but with the much
     * smaller power-2 exponent, so the implied damage tax for missing the EHP floor stays mild (a build at
     * half the floor keeps ~1/4 of its score from this factor) instead of the near-veto a power-6 imposes.
     * Floored at 1 like [penaltyMultiplier]: a floor far above every build's EHP (< ~3% reached) mapped every
     * bucket to 0 and flattened the damage objective — the empty build tied the optimum.
     */
    private fun buildGentlePowerTable(maxIndex: Long): PowerTable {
        if (maxIndex <= 0) return PowerTable(longArrayOf(MAX_SURVIVABILITY_MULTIPLIER), MAX_SURVIVABILITY_MULTIPLIER)
        val maxPow = BigInteger.valueOf(maxIndex).pow(SURVIVABILITY_PENALTY_POWER)
        val target = BigInteger.valueOf(MAX_SURVIVABILITY_MULTIPLIER)
        val powScale = if (maxPow > target) maxPow.divide(target) else BigInteger.ONE

        val table =
            LongArray(maxIndex.toInt() + 1) { index ->
                BigInteger
                    .valueOf(index.toLong())
                    .pow(SURVIVABILITY_PENALTY_POWER)
                    .divide(powScale)
                    .toLong()
                    .coerceAtLeast(MIN_PENALTY_MULTIPLIER)
            }
        return PowerTable(values = table, maxValue = table.last().coerceAtLeast(1L))
    }

    private fun safeMultiply(
        a: Long,
        b: Long,
    ): Long {
        val product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
        val maxSafe = BigInteger.valueOf(Long.MAX_VALUE / 2)
        return if (product > maxSafe) (Long.MAX_VALUE / 2) else product.toLong()
    }

    /** Interval product of two reachable ranges (all four corner products; handles mixed signs). */
    internal fun mulRange(
        a: LongRange,
        b: LongRange,
    ): LongRange {
        val corners =
            longArrayOf(
                a.first * b.first,
                a.first * b.last,
                a.last * b.first,
                a.last * b.last
            )
        return corners.min()..corners.max()
    }

    internal fun ceilDivPositive(
        numerator: Long,
        denominator: Long,
    ): Long {
        require(numerator >= 0L) { "ceilDivPositive numerator must be non-negative: $numerator" }
        require(denominator > 0L) { "ceilDivPositive denominator must be positive: $denominator" }
        return if (numerator == 0L) 0L else 1L + (numerator - 1L) / denominator
    }

    private fun deterministicTimeFrom(responseStats: String): Double =
        Regex("""deterministic_time:\s*([0-9.Ee+-]+)""")
            .find(responseStats)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: Double.NaN

    private fun longStatFrom(
        responseStats: String,
        key: String,
    ): Long =
        Regex("""(?m)^\s*$key:\s*([0-9]+)""")
            .find(responseStats)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: 0L

    private fun orderEquipments(equipmentsByItemType: Map<ItemType, List<Equipment>>): List<Equipment> =
        ItemType.entries
            .flatMap { type ->
                equipmentsByItemType[type].orEmpty().sortedBy { it.equipmentId }
            }.distinctBy { it.equipmentId }
}

/**
 * The conceptual "M-feeding" elemental-mastery stat set for a max-damage [scenario]: the generic
 * +all-elements mastery, the spell element's own mastery, the range-band mastery, plus the conditional
 * back / berserk / healing masteries. This is the OBJECTIVE / domination set — it INCLUDES the specific
 * element mastery.
 *
 * The rune sites deliberately use DIFFERENT sets and must NOT route through this helper: runes have only a
 * single GENERIC elemental-mastery rune (no per-element rune exists), so the rune-model mastery set omits
 * the specific element mastery, and `relevantRuneStats` additionally adds `MASTERY_CRITICAL`. Folding the
 * element mastery into those would inject a non-existent per-element rune and corrupt the rune model.
 */
internal fun scenarioMasteryStats(scenario: DamageScenario): List<Characteristic> =
    buildList {
        add(Characteristic.MASTERY_ELEMENTARY)
        add(scenario.element.masteryCharacteristic)
        add(scenario.rangeBand.masteryCharacteristic)
        if (scenario.orientation.grantsRearMastery) add(Characteristic.MASTERY_BACK)
        if (scenario.berserk) add(Characteristic.MASTERY_BERSERK)
        if (scenario.healing) add(Characteristic.MASTERY_HEALING)
    }
