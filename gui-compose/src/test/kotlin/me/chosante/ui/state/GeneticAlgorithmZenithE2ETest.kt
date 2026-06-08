package me.chosante.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.genetic.wakfu.WakfuSolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Regression guard for the genetic-algorithm engine driving the Zenith export.
 *
 * The sibling [BuildSearchModelE2ETest] selects the GA solver but injects a fake `buildFinder`, so it
 * never exercises the real engine branch chosen by [WakfuSolver.GENETIC_ALGORITHM]. This test runs the
 * actual GA branch of `WakfuBestBuildFinderAlgorithm.run` end-to-end through the production
 * [BuildSearchModel] (real engine, real `Dispatchers.Swing` UI dispatcher) and asserts that a GA
 * search reaches [Phase.Done] with a non-null build, after which the Zenith buttons — gated on
 * `phase == Done` — work: [BuildSearchModel.openZenithBuild] / [BuildSearchModel.copyZenithLink]
 * produce a link.
 */
class GeneticAlgorithmZenithE2ETest {
    @Test
    fun `real GA search reaches Done and the Zenith export works`() =
        runBlocking {
            val openedLinks = mutableListOf<String>()
            val copiedLinks = mutableListOf<String>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val zenithUrl = "https://zenithwakfu.com/builder/ga-regression"
            val model =
                BuildSearchModel(
                    scope = scope,
                    // Default buildFinder = the real WakfuBestBuildFinderAlgorithm.run, so the GA
                    // engine branch is genuinely executed.
                    zenithBuilder = { zenithUrl },
                    openBrowser = { openedLinks += it },
                    copyToClipboard = { copiedLinks += it },
                    mainDispatcher = Dispatchers.Swing
                )

            try {
                model.setDuration("1")
                model.setSolver(WakfuSolver.GENETIC_ALGORITHM)

                model.search()
                withTimeout(90.seconds) {
                    while (model.ui.phase == Phase.Searching) {
                        delay(50)
                    }
                }

                assertNull(model.ui.error)
                assertEquals(Phase.Done, model.ui.phase)
                assertNotNull(model.ui.build)

                model.openZenithBuild()
                withTimeout(15.seconds) {
                    while (openedLinks.isEmpty() && model.ui.error == null) {
                        delay(50)
                    }
                }
                assertNull(model.ui.error)
                assertEquals(ZenithState.Ready, model.ui.zenith)
                assertEquals(zenithUrl, model.ui.zenithUrl)
                assertEquals(listOf(zenithUrl), openedLinks)

                model.copyZenithLink()
                withTimeout(15.seconds) {
                    while (copiedLinks.isEmpty() && model.ui.error == null) {
                        delay(50)
                    }
                }
                assertNull(model.ui.error)
                assertEquals(listOf(zenithUrl), copiedLinks)
            } finally {
                scope.cancel()
            }
        }
}
