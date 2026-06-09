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
)
