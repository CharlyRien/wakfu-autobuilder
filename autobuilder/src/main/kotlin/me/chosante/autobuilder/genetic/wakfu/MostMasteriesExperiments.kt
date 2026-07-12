package me.chosante.autobuilder.genetic.wakfu

/**
 * Test-only A/B encodings for the most-masteries objective. Production and ordinary tests keep
 * [CURRENT]; manual performance harnesses opt into the alternatives through [WakfuBuildSolver.SolverTuning].
 */
internal enum class MmOvershootEncoding {
    /** The shipped `excess -> min -> max` encoding. */
    CURRENT,

    /** Hard-leg-only exact simplification: `actual >= target` makes the lower `max(., 0)` redundant. */
    HARD_EXACT_SIMPLIFIED,

    /**
     * Hard-leg-only objective-induced hypograph. At an optimum, the positive objective coefficient drives
     * `overflow` to `min(actual - target, target)`; unsupported weights fall back to [CURRENT].
     */
    HARD_HYPOGRAPH,
}

/** Exact alternative encodings for the remaining most-masteries `mastery * (100 + DI)` product. */
internal enum class MmProductEncoding {
    /** The shipped generic integer multiplication on guard-sized domains. */
    CURRENT,

    /** The same native multiplication/division, with tracked reachable domains propagated upstream. */
    TRACKED,

    /** Binary-expand the reachable DI factor and gate the mastery operand exactly. */
    BINARY,
}
