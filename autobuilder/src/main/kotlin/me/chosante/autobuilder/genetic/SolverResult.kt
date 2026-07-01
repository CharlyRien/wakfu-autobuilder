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
)
