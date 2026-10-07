package me.chosante.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.ScenarioDamage
import me.chosante.autobuilder.domain.SpellRotation
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.common.Characteristic
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class BuildSearchModelStoppedDamageTest {
    private val build = BuildCombination(emptyList(), CharacterSkills(110))
    private val result = SolverResult(build, BigDecimal("123"), 20)

    private fun model(
        scope: CoroutineScope,
        finder: (WakfuBestBuildParams) -> kotlinx.coroutines.flow.Flow<SolverResult<BuildCombination>>,
        breakdown: (BuildCombination, WakfuBestBuildParams, Map<Characteristic, Int>, SpellRotation?) -> List<ScenarioDamage>,
    ) = BuildSearchModel(
        scope = scope,
        buildFinder = finder,
        damageBreakdown = breakdown,
        mainDispatcher = Dispatchers.Unconfined,
        ioDispatcher = Dispatchers.Unconfined,
        libraryPreferences = LibraryPreferences(null),
        backgroundProofCanceller = {},
        historyRepository = HistoryRepository(Files.createTempDirectory("stopped-damage"), ioDispatcher = Dispatchers.Unconfined)
    )

    @Test
    fun `a stopped damage search computes the kept build's breakdown off the caller thread with its original request`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val caller = Thread.currentThread()
            var searched: WakfuBestBuildParams? = null
            val model =
                model(scope, { params ->
                    searched = params
                    flow {
                        emit(result)
                        awaitCancellation()
                    }
                }, { kept, params, achieved, rotation ->
                    assertFalse(Thread.currentThread() === caller, "rotations run off the UI caller")
                    SpellRotationOptimizer.scenarioBreakdown(
                        kept,
                        params.character,
                        params.character.clazz,
                        params.damageScenario,
                        (achieved[Characteristic.MASTERY_BERSERK] ?: 0) > 0,
                        rotation?.totalExpectedDamage
                    )
                })
            try {
                model.setMode(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)
                model.setScenario(DamageScenario(orientation = Orientation.SIDE, berserk = true))
                model.search()
                awaitUntil { model.ui.build != null }
                val rotation = model.ui.spellRotation
                model.setScenario(DamageScenario(orientation = Orientation.BACK))
                model.cancel()
                awaitUntil { model.ui.scenarioDamages.isNotEmpty() }
                val params = searched!!
                val expected =
                    SpellRotationOptimizer.scenarioBreakdown(
                        build,
                        params.character,
                        params.character.clazz,
                        params.damageScenario,
                        false,
                        rotation?.totalExpectedDamage
                    )
                assertEquals(expected, model.ui.scenarioDamages)
                assertEquals(build, model.ui.build)
                assertTrue(model.ui.searchStopped)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a late stopped breakdown cannot overwrite a newer stopped search even when it returns the same build`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val returned = CountDownLatch(1)
            val calls = AtomicInteger()
            val old = listOf(ScenarioDamage(Orientation.FACE, false, 1.0))
            val fresh = listOf(ScenarioDamage(Orientation.BACK, true, 99.0))
            val model =
                model(scope, {
                    flow {
                        emit(result)
                        awaitCancellation()
                    }
                }, { _, _, _, _ ->
                    if (calls.incrementAndGet() == 1) {
                        started.countDown()
                        check(release.await(25, TimeUnit.SECONDS))
                        returned.countDown()
                        old
                    } else {
                        fresh
                    }
                })
            try {
                model.setMode(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)
                model.setVerifyOptimality(false)
                model.search()
                awaitUntil { model.ui.build != null }
                model.cancel()
                assertTrue(started.await(25, TimeUnit.SECONDS))
                model.search()
                awaitUntil { model.ui.build != null }
                model.cancel()
                awaitUntil { model.ui.scenarioDamages == fresh }
                assertEquals(fresh, model.ui.scenarioDamages)
                release.countDown()
                assertTrue(returned.await(25, TimeUnit.SECONDS))
                delay(100)
                assertEquals(fresh, model.ui.scenarioDamages)
                assertTrue(model.ui.searchStopped)
            } finally {
                release.countDown()
                scope.cancel()
            }
        }

    @Test
    fun `a mode switch parks the stopped build's breakdown and restores it when returning`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val details = listOf(ScenarioDamage(Orientation.SIDE, false, 42.0))
            val damage = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
            val precision = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
            val model =
                model(scope, {
                    flow {
                        emit(result)
                        awaitCancellation()
                    }
                }, { _, _, _, _ ->
                    started.countDown()
                    check(release.await(25, TimeUnit.SECONDS))
                    details
                })
            try {
                model.setMode(damage)
                model.search()
                awaitUntil { model.ui.build != null }
                model.setMode(precision)
                assertTrue(started.await(25, TimeUnit.SECONDS))
                release.countDown()
                awaitUntil {
                    model.ui.modeWorkspaces[damage]
                        ?.result
                        ?.scenarioDamages == details
                }
                assertEquals(precision, model.ui.mode)
                model.setMode(damage)
                assertEquals(details, model.ui.scenarioDamages)
                assertTrue(model.ui.searchStopped)
            } finally {
                release.countDown()
                scope.cancel()
            }
        }

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) { while (!predicate()) delay(10) }
    }
}
