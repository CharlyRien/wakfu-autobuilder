package me.chosante.autobuilder.genetic

import java.math.BigDecimal

/**
 * A best-so-far result streamed by the build engine: the build ([individual]), its score / match %, search
 * progress and whether it is the proven optimum. The single streaming type consumed identically by the CLI and
 * the GUI; the OR-Tools CP-SAT solver ([me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver]) is its only
 * producer.
 *
 * (Formerly `GeneticAlgorithmResult`, from the long-removed genetic-algorithm engine — renamed so the name no
 * longer implies a GA that does not exist. The enclosing `genetic` package keeps its historical name.)
 */
data class SolverResult<T>(
    val individual: T,
    val matchPercentage: BigDecimal,
    val progressPercentage: Int,
    val isOptimal: Boolean = false,
    /**
     * Max-damage mode only: true when this result is a **heuristic max over structure-changing probes** (the
     * resistance-debuff AP-window sequencing, which only runs for Sram/Sadida) rather than a provable solve.
     * Those probes are what keep the result `!isOptimal` even though the per-turn damage objective is itself
     * provable — so a non-optimal result with this flag is *structurally* heuristic (more time won't move it),
     * whereas a non-optimal result WITHOUT it is merely time-limited (a bigger budget could prove it). Set by
     * [me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch].
     */
    val maxDamageHeuristicPhases: Boolean = false,
    /**
     * Max-damage mode only: the raw CP-SAT objective value of this build (`null` in the other modes and on
     * results that never carried it). In OBJECTIVE units — directly comparable to a [me.chosante.autobuilder
     * .genetic.wakfu.CertLedger]'s per-cell bounds — for the requested single-element scenario. With required
     * targets the value depends on which leg produced it: a **soft-shortfall-penalty** result carries the
     * PENALIZED objective (damage × the shortfall multiplier), which is not comparable to the damage-only
     * certificate; a **hard-constraint-leg** result ([maxDamageHardConstraintsMet]) carries the PLAIN
     * (unpenalized) damage score. Either way [maxDamageRawProxy] carries the certificate-comparable value.
     */
    val maxDamageObjective: Long? = null,
    /**
     * Max-damage mode only: the build's **unpenalized** per-turn damage proxy — the objective BEFORE the
     * required-target shortfall multiplier (and before the survivability floor). Equal to [maxDamageObjective]
     * when the request has no required targets. This is the value in the same OBJECTIVE units as a
     * [me.chosante.autobuilder.genetic.wakfu.CertLedger]'s per-cell bounds, so the post-search certificate can
     * compare it against the ledger even for required-target requests (see `MaxDamageSearch.proveOptimality`).
     * `null` in the other modes and on results that never carried it.
     */
    val maxDamageRawProxy: Long? = null,
    /**
     * Max-damage mode only: true when this build was produced by the **hard-constraints leg** — i.e. the solver
     * enforced every required AP/MP/range/… target as a hard `actual ≥ target` constraint, so the build provably
     * meets every required target in the solver's exact arithmetic. Set by
     * [me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch.optimizeHardThenSoft] on hard-leg emissions.
     *
     * The certificate badge ([me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch.proveOptimality]) trusts this
     * as the authoritative "targets met" verdict instead of re-deriving it through the scorer — the two use
     * different, deliberately-irreducible percent-rounding math (solver `tPercent` vs scorer `applyPercent`), so
     * a hard-leg build can be solver-feasible yet read one point short on the scorer grid for a percent-affected
     * target (only HP among the required targets). Trusting the solver's own verdict here keeps that rounding
     * quirk from denying a deserved badge, and is sound because the hard constraint is what actually bounded the
     * solve. `false` for soft-shortfall-penalty results and in the other modes.
     */
    val maxDamageHardConstraintsMet: Boolean = false,
    /**
     * True when this emission is the GREEDY WARM START's instant first build — streamed BEFORE the
     * CP-SAT solve even starts, so it is NOT a solution of the model. Orchestrators must never treat
     * it as evidence the model is feasible (the hard-then-soft fallback decision) nor stamp it with
     * solver-verified provenance like [maxDamageHardConstraintsMet].
     */
    val greedyWarmStartEmission: Boolean = false,
)
