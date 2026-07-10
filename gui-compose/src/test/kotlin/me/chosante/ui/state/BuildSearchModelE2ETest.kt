package me.chosante.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.RequestValidationProblem
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Character
import me.chosante.common.Characteristic.ACTION_POINT
import me.chosante.common.Characteristic.MASTERY_CRITICAL
import me.chosante.common.Characteristic.MASTERY_DISTANCE
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_EARTH
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_FIRE
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WATER
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WIND
import me.chosante.common.Equipment
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.common.history.TargetSnapshot
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.i18n.Lang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BuildSearchModelE2ETest {
    @Test
    fun `max mastery targets are selected as binary objectives`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = newModel(scope)

        try {
            assertEquals(
                "1",
                model.ui.targets
                    .single { it.characteristic == MASTERY_DISTANCE }
                    .value
            )

            model.toggleMaximizedMastery(MASTERY_DISTANCE)
            assertTrue(model.ui.targets.none { it.characteristic == MASTERY_DISTANCE })

            model.toggleMaximizedMastery(MASTERY_CRITICAL)
            val criticalTarget = model.ui.targets.single { it.characteristic == MASTERY_CRITICAL }
            assertEquals("1", criticalTarget.value)

            model.updateTargetValue(criticalTarget.id, "500")
            model.setMode(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT)
            assertEquals(
                "500",
                model.ui.targets
                    .single { it.characteristic == MASTERY_CRITICAL }
                    .value
            )

            model.setMode(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)
            assertEquals(
                "1",
                model.ui.targets
                    .single { it.characteristic == MASTERY_CRITICAL }
                    .value
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `all-elements and specific elemental masteries are mutually exclusive`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = newModel(scope)

        fun elementalMasteries() =
            model.ui.targets
                .map { it.characteristic }
                .filter {
                    it == MASTERY_ELEMENTARY ||
                        it in setOf(MASTERY_ELEMENTARY_FIRE, MASTERY_ELEMENTARY_EARTH, MASTERY_ELEMENTARY_WATER, MASTERY_ELEMENTARY_WIND)
                }.toSet()

        try {
            // No elemental mastery is selected by default.
            assertTrue(elementalMasteries().isEmpty())

            model.toggleMaximizedMastery(MASTERY_ELEMENTARY_FIRE)
            model.toggleMaximizedMastery(MASTERY_ELEMENTARY_EARTH)
            assertEquals(setOf(MASTERY_ELEMENTARY_FIRE, MASTERY_ELEMENTARY_EARTH), elementalMasteries())

            // Selecting "all elements" clears the specific elements.
            model.toggleMaximizedMastery(MASTERY_ELEMENTARY)
            assertEquals(setOf(MASTERY_ELEMENTARY), elementalMasteries())

            // Selecting a specific element clears "all elements".
            model.toggleMaximizedMastery(MASTERY_ELEMENTARY_WATER)
            assertEquals(setOf(MASTERY_ELEMENTARY_WATER), elementalMasteries())

            // A non-elemental mastery is untouched by the exclusivity rule.
            model.toggleMaximizedMastery(MASTERY_CRITICAL)
            model.toggleMaximizedMastery(MASTERY_ELEMENTARY)
            assertTrue(model.ui.targets.any { it.characteristic == MASTERY_CRITICAL })
            assertEquals(setOf(MASTERY_ELEMENTARY), elementalMasteries())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `known forced build runs through search stats and zenith wiring`() =
        runBlocking {
            val openedLinks = mutableListOf<String>()
            val copiedLinks = mutableListOf<String>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val zenithUrl = "https://zenithwakfu.com/builder/e2e-test"
            val fakeBuild =
                BuildCombination(
                    equipments = forcedEquipmentNames.map(::equipmentByFrenchName),
                    characterSkills = CharacterSkills(110)
                )
            val model =
                newModel(
                    scope = scope,
                    buildFinder = {
                        flowOf(
                            SolverResult(
                                individual = fakeBuild,
                                matchPercentage = BigDecimal("100"),
                                progressPercentage = 100,
                                isOptimal = true
                            )
                        )
                    },
                    zenithBuilder = { zenithUrl },
                    openBrowser = { openedLinks += it },
                    copyToClipboard = { copiedLinks += it }
                )

            try {
                model.setDuration("1")
                model.setMinLevel("0")
                model.setLang(Lang.FR)
                forcedEquipmentNames
                    .map(::equipmentByFrenchName)
                    .forEach { equipment ->
                        model.openModal(Modal.ItemPicker(PickerMode.Forced))
                        model.pickItem(equipment)
                    }

                val excluded = equipmentByFrenchName("Solomonk")
                model.openModal(Modal.ItemPicker(PickerMode.Excluded))
                model.pickItem(excluded)
                model.closeModal()

                assertEquals(forcedEquipmentNames.size, model.ui.forcedItems.size)
                assertEquals(
                    "Solomonk",
                    model.ui.excludedItems
                        .single()
                        .matchName
                )
                assertNull(model.ui.modal)

                model.search()
                awaitUntil { model.ui.phase == Phase.Done || model.ui.error != null }

                assertNull(model.ui.error)
                assertEquals(Phase.Done, model.ui.phase)
                assertTrue(model.ui.progress >= 100)
                assertTrue(model.ui.match > BigDecimal.ZERO)

                val build = requireNotNull(model.ui.build)
                assertTrue(build.isValid())
                forcedEquipmentNames.forEach { forcedName ->
                    assertTrue(
                        build.equipments.any { it.name.fr.equals(forcedName, ignoreCase = true) },
                        "Expected forced item '$forcedName' in build: ${build.equipments.map { it.name.fr }}"
                    )
                }
                assertTrue(build.equipments.none { it.name.fr == "Solomonk" })

                val expectedAchieved =
                    computeCharacteristicsValues(
                        buildCombination = build,
                        characterBaseCharacteristics = Character(model.ui.clazz, model.ui.level, model.ui.minLevel).baseCharacteristicValues,
                        masteryElementsWanted = model.ui.toTargetStats().masteryElementsWanted,
                        resistanceElementsWanted = model.ui.toTargetStats().resistanceElementsWanted
                    )
                assertEquals(expectedAchieved, model.ui.achieved)
                assertNotNull(model.ui.achieved[ACTION_POINT])
                assertNotNull(model.ui.achieved[MASTERY_DISTANCE])

                model.openZenithBuild()
                awaitUntil { openedLinks.isNotEmpty() || model.ui.error != null }
                assertNull(model.ui.error)
                assertEquals(ZenithState.Ready, model.ui.zenith)
                assertEquals(zenithUrl, model.ui.zenithUrl)
                assertEquals(listOf(zenithUrl), openedLinks)

                model.copyZenithLink()
                awaitUntil { copiedLinks.isNotEmpty() || model.ui.error != null }
                assertNull(model.ui.error)
                assertEquals(listOf(zenithUrl), copiedLinks)
                assertEquals("Lien Zenith copié", model.ui.toast)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `target priority is clamped to 1-5 and flows into the search params`() =
        runBlocking {
            var capturedApWeight: Int? = null
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model =
                newModel(
                    scope = scope,
                    buildFinder = { params ->
                        capturedApWeight = params.targetStats.firstOrNull { it.characteristic == ACTION_POINT }?.userDefinedWeight
                        emptyFlow()
                    }
                )

            try {
                val apId =
                    model.ui.targets
                        .single { it.characteristic == ACTION_POINT }
                        .id
                // Default priority is the neutral 1.
                assertEquals(
                    1,
                    model.ui.targets
                        .single { it.characteristic == ACTION_POINT }
                        .weight
                )

                // Out-of-range values are clamped to 1..5.
                model.updateTargetWeight(apId, 50)
                assertEquals(
                    5,
                    model.ui.targets
                        .single { it.characteristic == ACTION_POINT }
                        .weight
                )
                model.updateTargetWeight(apId, 0)
                assertEquals(
                    1,
                    model.ui.targets
                        .single { it.characteristic == ACTION_POINT }
                        .weight
                )

                // An in-range priority reaches the engine as TargetStat.userDefinedWeight.
                model.updateTargetWeight(apId, 4)
                model.search()
                awaitUntil { capturedApWeight != null }
                assertEquals(4, capturedApWeight)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `paperdoll force and exclude of the same item are mutually exclusive`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = newModel(scope)

        try {
            val item = equipmentByFrenchName("Solomonk")

            // Forcing an item lists it as forced, and not as excluded.
            model.forceItem(item)
            assertEquals(listOf("Solomonk"), model.ui.forcedItems.map { it.matchName })
            assertTrue(model.ui.excludedItems.isEmpty())

            // Excluding that same item must drop it from forced (else the engine's exclude filter
            // wins, the item is invisible, yet it lingers in the forced list).
            model.excludeItem(item)
            assertTrue(model.ui.forcedItems.isEmpty(), "Excluding a forced item must drop it from forced")
            assertEquals(listOf("Solomonk"), model.ui.excludedItems.map { it.matchName })

            // ...and forcing it again moves it back, clearing the exclusion.
            model.forceItem(item)
            assertEquals(listOf("Solomonk"), model.ui.forcedItems.map { it.matchName })
            assertTrue(model.ui.excludedItems.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `item picker enforces the same force-exclude exclusivity`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = newModel(scope)

        try {
            val item = equipmentByFrenchName("Solomonk")

            model.openModal(Modal.ItemPicker(PickerMode.Forced))
            model.pickItem(item)
            assertEquals(listOf("Solomonk"), model.ui.forcedItems.map { it.matchName })
            assertEquals(Modal.ItemPicker(PickerMode.Forced), model.ui.modal)

            model.openModal(Modal.ItemPicker(PickerMode.Excluded))
            model.pickItem(item)
            assertTrue(model.ui.forcedItems.isEmpty())
            assertEquals(listOf("Solomonk"), model.ui.excludedItems.map { it.matchName })
            assertEquals(Modal.ItemPicker(PickerMode.Excluded), model.ui.modal)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a min level above the character level is rejected before the solver runs`() {
        var solverInvocations = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model =
            newModel(
                scope = scope,
                buildFinder = {
                    solverInvocations++
                    emptyFlow()
                }
            )

        try {
            // Default character level is 110; push the min above it to make the range impossible.
            model.setMinLevel("120")
            assertTrue(model.ui.minLevel > model.ui.level)

            model.search()

            // The solver is never invoked; the impossible level range is reported as a request problem in the
            // errors pop-up (UiState.requestErrors), not the results-panel banner, instead of the engine
            // silently returning a near-empty pets/mounts-only build.
            assertEquals(0, solverInvocations)
            assertEquals(1, model.ui.requestErrors.size)
            assertTrue(model.ui.requestErrors.single() is RequestValidationProblem.LevelRangeInvalid)
            assertEquals(Phase.Idle, model.ui.phase)
            assertNull(model.ui.build)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `duplicate saves an independent copy with a unique name`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val repo = HistoryRepository(baseDir = tempDir, ioDispatcher = Dispatchers.Unconfined)
        repo.save(historyEntry(id = "orig", name = "Cra 110 · Distance"))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var nextId = 0
        val model =
            BuildSearchModel(
                scope = scope,
                buildFinder = { emptyFlow() },
                zenithBuilder = { "" },
                mainDispatcher = Dispatchers.Unconfined,
                historyRepository = repo,
                libraryPreferences = LibraryPreferences(null),
                idGenerator = { "copy-${nextId++}" },
                clock = { 9_000L }
            )

        try {
            awaitUntil { model.ui.savedBuilds.any { it.id == "orig" } }

            // First duplicate → "(copy)": a fresh id, a faithful copy of the request + result.
            model.duplicateBuild("orig")
            awaitUntil { model.ui.savedBuilds.size == 2 }
            val original = model.ui.savedBuilds.single { it.id == "orig" }
            val firstCopy = model.ui.savedBuilds.single { it.id != "orig" }
            assertEquals("copy-0", firstCopy.id)
            assertEquals("Cra 110 · Distance (copy)", firstCopy.name)
            assertEquals(9_000L, firstCopy.createdAt)
            assertEquals(original.request, firstCopy.request)
            assertEquals(original.result, firstCopy.result)
            assertEquals("Build duplicated", model.ui.toast)
            // The new copy is flagged for the library's highlight/scroll feedback.
            assertEquals(firstCopy.id, model.ui.lastDuplicatedBuildId)
            // The source is left untouched.
            assertEquals("Cra 110 · Distance", original.name)

            // Duplicating the same source again must not collide → "(copy 2)".
            model.duplicateBuild("orig")
            awaitUntil { model.ui.savedBuilds.size == 3 }
            val names =
                model.ui.savedBuilds
                    .map { it.name }
                    .toSet()
            assertTrue(
                names.contains("Cra 110 · Distance (copy 2)"),
                "Expected a uniquely-suffixed second copy, got $names"
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `loading a build clears a stale optimality proof badge`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val repo = HistoryRepository(baseDir = tempDir, ioDispatcher = Dispatchers.Unconfined)
        repo.save(historyEntry(id = "saved", name = "Saved build"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fakeBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))
        val model =
            BuildSearchModel(
                scope = scope,
                buildFinder = {
                    flowOf(
                        SolverResult(
                            individual = fakeBuild,
                            matchPercentage = BigDecimal("1000"),
                            progressPercentage = 100,
                            isOptimal = false,
                            maxDamageObjective = 5_000L
                        )
                    )
                },
                // Prove instantly so proofState reaches ProvenOptimal without a real (minutes-long) solve.
                optimalityProver = { _, _, _ -> MaxDamageSearch.MaxDamageProof.ProvenOptimal },
                zenithBuilder = { "" },
                mainDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
                libraryPreferences = LibraryPreferences(null),
                historyRepository = repo
            )

        try {
            awaitUntil { model.ui.savedBuilds.any { it.id == "saved" } }
            model.setMode(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)
            model.setDuration("1")
            model.search()
            // The certificate proof resolves to ProvenOptimal after the max-damage search completes.
            awaitUntil { model.ui.proofState == ProofState.ProvenOptimal }

            // Loading a DIFFERENT build must clear the stale proof — otherwise StatsPanel would paint a green
            // "Proven optimal" on a build the certificate never saw (P4.4 wrong-badge bug).
            model.loadBuild("saved")
            assertEquals(Phase.Done, model.ui.phase)
            assertEquals(ProofState.Idle, model.ui.proofState)
        } finally {
            scope.cancel()
        }
    }

    private fun historyEntry(
        id: String,
        name: String,
    ): HistoryEntry =
        HistoryEntry(
            id = id,
            name = name,
            createdAt = 1_000L,
            dataVersion = "test",
            request =
                RequestSnapshot(
                    clazz = "CRA",
                    level = 110,
                    minLevel = 80,
                    mode = "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT",
                    maxRarity = Rarity.EPIC,
                    duration = "20",
                    stopAtMatch = false,
                    targets = listOf(TargetSnapshot(MASTERY_DISTANCE, "1")),
                    forcedItems = emptyList(),
                    excludedItems = emptyList()
                ),
            result =
                ResultSnapshot(
                    equipments = emptyList(),
                    skills = emptyMap(),
                    achieved = mapOf(MASTERY_DISTANCE to 1280),
                    match = 100.0,
                    optimal = true
                ),
            zenithUrl = "https://zenithwakfu.com/builder/orig"
        )

    /**
     * Constructs a [BuildSearchModel] wired for deterministic tests: both the IO and main
     * dispatchers are [Dispatchers.Unconfined] so the init savedBuilds load runs synchronously
     * inside the constructor, before the test touches [BuildSearchModel.ui]. A temp directory
     * isolates tests from the developer's real saved-build library.
     */
    private fun newModel(
        scope: CoroutineScope,
        buildFinder: (WakfuBestBuildParams) -> Flow<SolverResult<BuildCombination>> = { emptyFlow() },
        zenithBuilder: suspend (me.chosante.ZenithInputParameters) -> String = { "" },
        openBrowser: (String) -> Unit = {},
        copyToClipboard: (String) -> Unit = {},
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = buildFinder,
            zenithBuilder = zenithBuilder,
            openBrowser = openBrowser,
            copyToClipboard = copyToClipboard,
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
            historyRepository =
                HistoryRepository(
                    baseDir = Files.createTempDirectory("wakfu-test-history"),
                    ioDispatcher = Dispatchers.Unconfined
                )
        )

    private fun UiState.toTargetStats(): TargetStats = TargetStats(targets.map { TargetStat(it.characteristic, it.value.toIntOrNull() ?: 0) })

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) {
                delay(50.milliseconds)
            }
        }
    }

    private fun equipmentByFrenchName(name: String): Equipment =
        WakfuBestBuildFinderAlgorithm.equipments
            .filter { it.name.fr.equals(name, ignoreCase = true) }
            .maxByOrNull { it.rarity.ordinal }
            ?: error("Missing equipment '$name' in embedded Wakfu data")

    private companion object {
        val forcedEquipmentNames =
            listOf(
                "Casque Hazieff",
                "Amulette du Corbeau Blanc",
                "Combinaison Lardante",
                "Halo de Magmog",
                "Gelano",
                "Bottes Massetard",
                "Coulée de Magmog",
                "Ottopaulettes",
                "Ceinture du Corbeau Blanc",
                "Monture Godron",
                "Emblème du Pouvoir",
                "Peroucan"
            )
    }
}
