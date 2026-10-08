package me.chosante.autobuilder.domain

import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the pair (CERTIFIER_VERSION, ENGINE_RESULTS_VERSION) so neither moves alone unnoticed. The rule (AGENTS.md §4): bump
 * ENGINE_RESULTS_VERSION on ANY change that can alter which build a search returns or a build's score — and a CERTIFIER_VERSION
 * bump implies one. Saved builds compare their stored ENGINE_RESULTS_VERSION with it to show the "obsolete" badge in My Builds.
 * When this fails, bump what the rule asks for, then update the pair below.
 */
class EngineResultsVersionTest {
    @Test
    fun `a certifier bump comes with an engine results bump`() {
        assertThat(WakfuBuildSolver.CERTIFIER_VERSION to ENGINE_RESULTS_VERSION)
            .describedAs(
                "CERTIFIER_VERSION and ENGINE_RESULTS_VERSION changed: a CERTIFIER_VERSION bump implies an ENGINE_RESULTS_VERSION " +
                    "bump, and ENGINE_RESULTS_VERSION is bumped on any change that can alter a search's build or a build's score " +
                    "(AGENTS.md §4). Bump as the rule asks, then update this pair."
            ).isEqualTo(60 to 5)
    }
}
