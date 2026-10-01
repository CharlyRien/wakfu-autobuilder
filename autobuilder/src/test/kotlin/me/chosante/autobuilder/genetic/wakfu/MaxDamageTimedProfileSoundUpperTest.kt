package me.chosante.autobuilder.genetic.wakfu

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks [WakfuBuildSolver.MaxDamageTimedProfile.soundUpper], the single door through which the soft-proof
 * code reads a CP-SAT solve as an upper bound. The danger it closes: a solve stopped (cancellation) or timed
 * out inside presolve reports UNKNOWN with the proto-default bound 0 — read raw, that "closes" any world
 * below an incumbent and can award a false "proven optimal" badge.
 */
class MaxDamageTimedProfileSoundUpperTest {
    private fun profile(
        status: String,
        objective: Long,
        bestBound: Long,
    ) = WakfuBuildSolver.MaxDamageTimedProfile(
        status = status,
        objective = objective,
        bestBound = bestBound,
        objectiveCutoff = null,
        wallTimeSec = 0.0,
        deterministicTime = 0.0,
        booleans = 0,
        branches = 0,
        conflicts = 0,
        restarts = 0,
        lpIterations = 0,
        variables = 0,
        constraints = 0,
        poolSize = 0,
        experiment = MaxDamageExperimentConfig(),
        selectedEquipmentIds = emptySet(),
        selectedSublimationStateIds = emptySet(),
        selectedSublimationCopies = emptyMap(),
        actualStats = emptyMap(),
        rawObjective = 0
    )

    @Test
    fun `proven and feasible solves read as their objective and dual`() {
        assertThat(profile("OPTIMAL", 1_000, 1_000).soundUpper()).isEqualTo(1_000)
        assertThat(profile("FEASIBLE", 1_000, 1_250).soundUpper()).isEqualTo(1_250)
        assertThat(profile("UNKNOWN", Long.MIN_VALUE, 1_250).soundUpper())
            .describedAs("a search that got past presolve still carries a real dual")
            .isEqualTo(1_250)
    }

    @Test
    fun `an unset or garbage native bound never reads as a closed world`() {
        assertThat(profile("UNKNOWN", Long.MIN_VALUE, 0).soundUpper())
            .describedAs("stopped inside presolve: the proto-default 0 is not a bound")
            .isEqualTo(Long.MAX_VALUE)
        assertThat(profile("UNKNOWN", Long.MIN_VALUE, -5).soundUpper()).isEqualTo(Long.MAX_VALUE)
        assertThat(profile("MODEL_INVALID", Long.MIN_VALUE, 0).soundUpper()).isEqualTo(Long.MAX_VALUE)
        assertThat(profile("FEASIBLE", 1_000, 900).soundUpper())
            .describedAs("a 'dual' below a found primal is not a dual")
            .isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `an infeasible model is empty only where the caller says emptiness is meaningful`() {
        assertThat(profile("INFEASIBLE", Long.MIN_VALUE, 0).soundUpper()).isEqualTo(Long.MAX_VALUE)
        assertThat(profile("INFEASIBLE", Long.MIN_VALUE, 0).soundUpper(ifInfeasible = Long.MIN_VALUE)).isEqualTo(Long.MIN_VALUE)
    }
}
