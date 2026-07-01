package me.chosante.autobuilder.genetic.wakfu

/**
 * A/B experiment knobs for the max-damage CP-SAT objective. **Production always uses [DEFAULT]** —
 * `WakfuBuildSolver.optimize` passes it and never exposes these flags. The non-default variants exist only so
 * the max-damage tuning tests (`WakfuBuildSolverTest`) can measure alternative product encodings / cut
 * strategies against the shipped one. Kept out of `WakfuBuildSolver` so the engine file reflects the single
 * production encoding, not the research history behind it.
 */
internal data class MaxDamageExperimentConfig(
    val apCeiling: Boolean = false,
    // BINARY is the production default: binary-expanding the D and crit one-hot product selectors (~180
    // booleans → ~14 bits) collapses the tree-exhaustion search ~5.5× (measured, sound). TABLE is the old
    // one-hot encoding, kept only for A/B. See solver-performance-audit.
    val critProduct: CritProductMode = CritProductMode.BINARY,
    val dProduct: DProductMode = DProductMode.BINARY,
    val sameNameRingBound: Boolean = false,
    val perApRotRawCut: Boolean = false,
    val perHitOnlyObjective: Boolean = false,
    val dGrawCutoff: Boolean = false,
    val dGrawJointBound: Boolean = false,
) {
    companion object {
        val DEFAULT = MaxDamageExperimentConfig()
    }
}

internal enum class CritProductMode {
    TABLE,
    GENERIC,
    BINARY,
}

internal enum class DProductMode {
    TABLE,
    BINARY,
    SOURCE_DI,
}
