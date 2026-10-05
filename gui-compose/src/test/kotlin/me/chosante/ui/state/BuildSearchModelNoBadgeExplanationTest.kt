package me.chosante.ui.state

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.common.history.TargetSnapshot
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.stats.MatchHero
import me.chosante.ui.stats.NO_PROOF_TAG
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the stats headline tells a player about a result that has no optimality badge. A request on several elements of one
 * family — two specific elements, or an aggregate "all masteries" / "all resistances" row — is searched on a heuristic selection
 * of the items, so no search of it is ever proven, however long it runs: its finished result, and a saved build of it loaded
 * back, explain that ([UiState.prefilteredRequest]) instead of suggesting "raise the search duration". A result of a request that
 * merely ran out of time keeps that hint.
 *
 * The flag belongs to the RESULT: it is read from the request the search was started with (or the save was restored with), so
 * editing the target rows afterwards changes nothing about what the result says of itself.
 */
@OptIn(ExperimentalTestApi::class)
class BuildSearchModelNoBadgeExplanationTest {
    private val masteries = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
    private val precision = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
    private val damage = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE

    private val foundBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))

    /** How many times the engine's post-search optimality check (either mode's) was started: a request nothing can prove starts none. */
    private val proofChecks = AtomicInteger()

    /** A search's end result as the engine streams it for a request it could not prove: a build, objectives, but no proof. */
    private fun unproven(greedyWarmStart: Boolean = false) =
        SolverResult(
            individual = foundBuild,
            matchPercentage = BigDecimal("1500"),
            progressPercentage = if (greedyWarmStart) 0 else 100,
            isOptimal = false,
            maxDamageObjective = if (greedyWarmStart) null else 5_000L,
            mostMasteriesObjective = if (greedyWarmStart) null else 1_000L,
            greedyWarmStartEmission = greedyWarmStart
        )

    private fun newModel(
        scope: CoroutineScope,
        repository: HistoryRepository = HistoryRepository(baseDir = Files.createTempDirectory("wakfu-test-history"), ioDispatcher = Dispatchers.Unconfined),
        finder: () -> Flow<SolverResult<BuildCombination>> = { flowOf(unproven()) },
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = { finder() },
            // The engine's answer for a result it cannot prove (a prefiltered request, a build that is only the greedy first
            // estimate…): "unavailable", at once.
            optimalityProver = { _, _, _, _ ->
                proofChecks.incrementAndGet()
                MaxDamageSearch.MaxDamageProof.Unavailable
            },
            mmQualityProver = { _, _, _ ->
                proofChecks.incrementAndGet()
                WakfuBestBuildFinderAlgorithm.MostMasteriesProof.Unavailable
            },
            zenithBuilder = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
            backgroundProofCanceller = {},
            buildRescorer = { _, _ -> BigDecimal.ONE },
            historyRepository = repository
        )

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) delay(20.milliseconds)
        }
    }

    /**
     * Runs a search of the request as it stands and waits for its end — and for the verdict of the check it starts: the fake engine
     * answers "unavailable" for both modes that have one, so a request that is not prefiltered ends there. Waiting matters: a
     * "Verifying optimality…" spinner on screen is an endless animation, which a UI test would wait on forever.
     */
    private suspend fun BuildSearchModel.searchToTheEnd() {
        setDuration("1")
        search()
        awaitUntil { ui.phase == Phase.Done }
        if (ui.mode != precision && !ui.prefilteredRequest) awaitUntil { ui.proofState == ProofState.Unavailable }
    }

    private fun wantRow(
        model: BuildSearchModel,
        characteristic: Characteristic,
        value: String = "100",
    ) {
        model.addTarget(characteristic)
        model.updateTargetValue(characteristic.name, value)
    }

    private fun renderHero(
        ui: UiState,
        check: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLang provides Lang.EN) {
                MatchHero(ui = ui)
            }
        }
        check()
    }

    private fun ComposeUiTest.assertExplained() {
        onNodeWithText(Tr.NO_PROOF_TITLE.en).assertExists()
        onNodeWithText(Tr.NO_PROOF_BODY.en).assertExists()
        onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertDoesNotExist()
    }

    private fun ComposeUiTest.assertAdvisedToRaiseTheDuration() {
        onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertExists()
        onNodeWithTag(NO_PROOF_TAG).assertDoesNotExist()
        onNodeWithText(Tr.NO_PROOF_TITLE.en).assertDoesNotExist()
    }

    /** A request shape: the mode it is searched in and how its target rows are set up (on top of that mode's own rows). */
    private class Shape(
        val label: String,
        val mode: ScoreComputationMode,
        val rows: BuildSearchModel.() -> Unit,
    )

    private val severalElements =
        listOf(
            Shape("two specific masteries, most masteries", masteries) {
                toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_FIRE)
                toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_WATER)
            },
            Shape("the aggregate mastery row, most masteries", masteries) {
                toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY)
            },
            Shape("two resistance rows, most masteries", masteries) {
                wantRow(this, Characteristic.RESISTANCE_ELEMENTARY_FIRE)
                wantRow(this, Characteristic.RESISTANCE_ELEMENTARY_WATER)
            },
            // The GUI expands this single row into the four per-element ones for most masteries only; the engine counts all four either way.
            Shape("the aggregate resistance row, most masteries", masteries) {
                wantRow(this, Characteristic.RESISTANCE_ELEMENTARY)
            },
            Shape("two resistance rows, precision", precision) {
                wantRow(this, Characteristic.RESISTANCE_ELEMENTARY_FIRE)
                wantRow(this, Characteristic.RESISTANCE_ELEMENTARY_WATER)
            },
            Shape("the aggregate resistance row, precision", precision) {
                wantRow(this, Characteristic.RESISTANCE_ELEMENTARY)
            },
            Shape("two masteries, max damage", damage) {
                wantRow(this, Characteristic.MASTERY_ELEMENTARY_FIRE, "1")
                wantRow(this, Characteristic.MASTERY_ELEMENTARY_WATER, "1")
            }
        )

    private val singleElement =
        listOf(
            // The GUI's default request: distance mastery, with AP / MP / range / crit / HP and a lone air-resistance row.
            Shape("the default request, most masteries", masteries) {},
            Shape("one specific mastery, most masteries", masteries) {
                toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_FIRE)
            },
            Shape("a single resistance row, precision", precision) {
                updateTargetValue(Characteristic.RESISTANCE_ELEMENTARY_WIND.name, "100")
            },
            Shape("one mastery row, max damage", damage) {
                wantRow(this, Characteristic.MASTERY_ELEMENTARY_FIRE, "1")
            }
        )

    private suspend fun searched(shape: Shape): UiState {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model = newModel(scope)
            model.setMode(shape.mode)
            shape.rows(model)
            model.searchToTheEnd()
            delay(150.milliseconds)
            return model.ui
        } finally {
            scope.cancel()
        }
    }

    // ---- live results --------------------------------------------------------------------------------------------------------

    @Test
    fun `the result of a request on several elements explains its missing badge instead of suggesting a longer search`() {
        val results =
            severalElements.map { shape ->
                proofChecks.set(0)
                val ui = runBlocking { searched(shape) }
                assertTrue(ui.prefilteredRequest, "${shape.label}: the result knows its request cannot be proven")
                assertFalse(ui.optimal, "${shape.label}: no proof")
                assertEquals(0, proofChecks.get(), "${shape.label}: nothing to verify, so no check is started")
                assertEquals(ProofState.Idle, ui.proofState, "${shape.label}: no \"Verifying optimality…\" over the explanation")
                shape to ui
            }
        for ((shape, ui) in results) {
            renderHero(ui) {
                assertExplained()
                // The headline is unchanged: the optimum is still "not proven".
                onNodeWithText(Tr.BEST_FOUND.en).assertExists()
            }
            assertEquals(shape.mode, ui.mode, "${shape.label}: sanity")
        }
    }

    @Test
    fun `the result of a single-element request that ran out of time keeps the duration hint`() {
        for (shape in singleElement) {
            proofChecks.set(0)
            val ui = runBlocking { searched(shape) }
            assertFalse(ui.prefilteredRequest, "${shape.label}: one element can be proven, so it is not explained away")
            assertFalse(ui.optimal, "${shape.label}: sanity — the fake engine proved nothing")
            renderHero(ui) { assertAdvisedToRaiseTheDuration() }
        }
    }

    @Test
    fun `a single-element request still gets its post-search check`() {
        // The gate that spares a request on several elements its check must not spare the others.
        for (shape in singleElement.filter { it.mode != precision }) {
            proofChecks.set(0)
            val ui = runBlocking { searched(shape) }
            assertEquals(1, proofChecks.get(), "${shape.label}: the check ran")
            assertEquals(ProofState.Unavailable, ui.proofState, "${shape.label}: and its verdict was kept")
        }
    }

    @Test
    fun `a max-damage result that is only the quick first estimate keeps the duration hint, never an unsupported-shape message`(): Unit =
        runBlocking {
            // A search so short that the solver never produced a build of its own: what is shown is the greedy warm start, and the
            // engine answers "unavailable" at once because that build carries no solver objective. A longer search is what would
            // change it, so the panel must keep saying so — not call the request unsupported.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model = newModel(scope, finder = { flowOf(unproven(greedyWarmStart = true)) })
                model.setMode(damage)
                wantRow(model, Characteristic.MASTERY_ELEMENTARY_FIRE, "1")
                model.searchToTheEnd()
                assertEquals(ProofState.Unavailable, model.ui.proofState)
                assertFalse(model.ui.prefilteredRequest)
                renderHero(model.ui) {
                    assertAdvisedToRaiseTheDuration()
                    onNodeWithText(Tr.PROOF_UNAVAILABLE_FORCED.en).assertDoesNotExist()
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a search the user stopped keeps its stopped hint, whatever the request`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model =
                    newModel(scope, finder = {
                        flow {
                            emit(unproven())
                            awaitCancellation()
                        }
                    })
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_FIRE)
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_WATER)
                model.search()
                awaitUntil { model.ui.build != null }
                model.cancel()

                assertTrue(model.ui.searchStopped)
                assertTrue(model.ui.prefilteredRequest, "the request is still one no search can prove")
                // The stopped build is a best-so-far, not the result of a finished search: "very likely the best" would overstate it.
                renderHero(model.ui) {
                    onNodeWithText(Tr.SEARCH_STOPPED_HINT.en).assertExists()
                    onNodeWithTag(NO_PROOF_TAG).assertDoesNotExist()
                }
            } finally {
                scope.cancel()
            }
        }

    // ---- the result keeps what it says when the request is edited, or the mode switched ----------------------------------------

    @Test
    fun `editing the target rows after a search changes nothing about what its result says`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model = newModel(scope)

                // A single-element result, then a second element added to the rows without searching again.
                model.searchToTheEnd()
                assertFalse(model.ui.prefilteredRequest)
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_FIRE)
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_WATER)
                assertFalse(model.ui.prefilteredRequest, "the shown result was not searched with the second element")
                renderHero(model.ui) { assertAdvisedToRaiseTheDuration() }

                // The same rows, searched: now the result is of a request on several elements…
                model.searchToTheEnd()
                assertTrue(model.ui.prefilteredRequest)
                // …and dropping an element afterwards does not turn its explanation into a duration hint.
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_WATER)
                assertTrue(model.ui.prefilteredRequest, "the shown result was searched with both elements")
                renderHero(model.ui) { assertExplained() }

                // A new search of the single-element rows replaces it.
                model.searchToTheEnd()
                assertFalse(model.ui.prefilteredRequest)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `the explanation is parked and restored with the result when the mode is switched away and back`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model = newModel(scope)
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_FIRE)
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_WATER)
                model.searchToTheEnd()
                assertTrue(model.ui.prefilteredRequest)

                // Another mode has no result of its own yet, so it has nothing to explain…
                model.setMode(precision)
                assertEquals(null, model.ui.build)
                assertFalse(model.ui.prefilteredRequest)

                // …and the result found under most masteries comes back with its explanation.
                model.setMode(masteries)
                assertTrue(model.ui.build != null)
                assertTrue(model.ui.prefilteredRequest)
                renderHero(model.ui) { assertExplained() }
            } finally {
                scope.cancel()
            }
        }

    // ---- saved builds loaded back --------------------------------------------------------------------------------------------

    private fun savedBuild(
        id: String,
        mode: ScoreComputationMode,
        targets: List<TargetSnapshot>,
        optimal: Boolean,
    ) = HistoryEntry(
        id = id,
        name = id,
        createdAt = 1_000L,
        dataVersion = WakfuBestBuildFinderAlgorithm.dataVersion,
        request =
            RequestSnapshot(
                clazz = CharacterClass.CRA.name,
                level = 110,
                minLevel = 0,
                mode = mode.name,
                maxRarity = Rarity.EPIC,
                duration = "10",
                stopAtMatch = false,
                targets = targets,
                forcedItems = emptyList(),
                excludedItems = emptyList()
            ),
        result = ResultSnapshot(equipments = emptyList(), skills = emptyMap(), achieved = emptyMap(), match = 1.0, optimal = optimal)
    )

    private val fireAndWaterMastery =
        listOf(
            TargetSnapshot(Characteristic.MASTERY_ELEMENTARY_FIRE, "1"),
            TargetSnapshot(Characteristic.MASTERY_ELEMENTARY_WATER, "1"),
            TargetSnapshot(Characteristic.ACTION_POINT, "11")
        )

    private val allResistances =
        listOf(
            TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1"),
            TargetSnapshot(Characteristic.RESISTANCE_ELEMENTARY, "100")
        )

    private val distanceOnly =
        listOf(
            TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1"),
            TargetSnapshot(Characteristic.ACTION_POINT, "11")
        )

    /** A model whose library already holds [entries] (as saved by an earlier session). */
    private suspend fun modelWithSaved(
        scope: CoroutineScope,
        vararg entries: HistoryEntry,
    ): BuildSearchModel {
        val repository = HistoryRepository(baseDir = Files.createTempDirectory("wakfu-test-history"), ioDispatcher = Dispatchers.Unconfined)
        entries.forEach { repository.save(it) }
        val model = newModel(scope, repository)
        awaitUntil { model.ui.savedBuilds.size == entries.size }
        return model
    }

    @Test
    fun `a saved build of a request on several elements explains its missing badge once loaded, whatever its stored proof`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val saves =
                    listOf(
                        savedBuild("fire-water", masteries, fireAndWaterMastery, optimal = true),
                        savedBuild("all-resistances", masteries, allResistances, optimal = false),
                        savedBuild("all-resistances-precision", precision, allResistances, optimal = true)
                    )
                val model = modelWithSaved(scope, *saves.toTypedArray())
                for (save in saves) {
                    model.loadBuild(save.id)
                    assertEquals(Phase.Done, model.ui.phase, save.id)
                    assertTrue(model.ui.prefilteredRequest, "${save.id}: read from the request the save restored")
                    assertFalse(model.ui.optimal, "${save.id}: a stored proof of such a request is not restored")
                    renderHero(model.ui) {
                        assertExplained()
                        onNodeWithText(Tr.OPTIMAL_PROVEN.en).assertDoesNotExist()
                    }
                }
                assertEquals(0, proofChecks.get(), "loading starts no check")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a saved single-element build keeps the duration hint, or its badge, once loaded`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model =
                    modelWithSaved(
                        scope,
                        savedBuild("unproven", masteries, distanceOnly, optimal = false),
                        savedBuild("proven", masteries, distanceOnly, optimal = true)
                    )
                model.loadBuild("unproven")
                assertFalse(model.ui.prefilteredRequest)
                renderHero(model.ui) { assertAdvisedToRaiseTheDuration() }

                model.loadBuild("proven")
                assertFalse(model.ui.prefilteredRequest)
                assertTrue(model.ui.optimal)
                renderHero(model.ui) {
                    onNodeWithText(Tr.OPTIMAL_PROVEN.en).assertExists()
                    onNodeWithTag(NO_PROOF_TAG).assertDoesNotExist()
                    onNodeWithText(Tr.NOT_OPTIMAL_HINT.en).assertDoesNotExist()
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a loaded build says what its own request says, not what the build shown before it, or the rows before the load, said`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model =
                    modelWithSaved(
                        scope,
                        savedBuild("several", masteries, fireAndWaterMastery, optimal = false),
                        savedBuild("single", masteries, distanceOnly, optimal = false)
                    )

                // After a search of a request on several elements, a single-element save must not inherit its explanation…
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_FIRE)
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_WATER)
                model.searchToTheEnd()
                assertTrue(model.ui.prefilteredRequest)
                model.loadBuild("single")
                assertFalse(model.ui.prefilteredRequest)
                renderHero(model.ui) { assertAdvisedToRaiseTheDuration() }

                // …and after a single-element build (the rows now are the save's), a save of several elements gets its own.
                model.loadBuild("several")
                assertTrue(model.ui.prefilteredRequest)
                renderHero(model.ui) { assertExplained() }

                // Editing the rows of the loaded build does not change what it explains either.
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_WATER)
                assertTrue(model.ui.prefilteredRequest)
                renderHero(model.ui) { assertExplained() }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a new build starts with nothing to explain`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model = newModel(scope)
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_FIRE)
                model.toggleMaximizedMastery(Characteristic.MASTERY_ELEMENTARY_WATER)
                model.searchToTheEnd()
                assertTrue(model.ui.prefilteredRequest)

                model.newBuild()
                assertFalse(model.ui.prefilteredRequest)
            } finally {
                scope.cancel()
            }
        }
}
