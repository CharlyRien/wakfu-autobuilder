package me.chosante.autobuilder.genetic

import java.math.BigDecimal

/**
 * A best-so-far result streamed by the build engine: the build, its score / match %, search progress
 * and whether it is the proven optimum.
 *
 * The name is historical — it used to be emitted by the genetic algorithm. That engine has been
 * removed; the OR-Tools CP-SAT solver ([me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver]) is
 * now the only producer. Kept as the shared streaming type consumed identically by the CLI and GUI.
 */
data class GeneticAlgorithmResult<T>(
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
