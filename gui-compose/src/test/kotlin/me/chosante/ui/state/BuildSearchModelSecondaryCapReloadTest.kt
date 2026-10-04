package me.chosante.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.FindMostMasteriesFromInputScoring
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.common.history.TargetSnapshot
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.history.toFlatMap
import me.chosante.ui.history.toSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A saved build is re-scored when it is loaded, not shown with the numbers of the rules it was found under: its stats grid, its score
 * and, for max-damage, its rotation are all recomputed. So a build saved while the Neutralité family was read as the SUM of the
 * secondary masteries — distance +100 offset by rear −100 — comes back with Neutralité III inactive: each secondary mastery must be
 * ≤ 0 on its own, as in the game. A proof flag stored with a score that moved no longer holds, so it is not restored.
 */
class BuildSearchModelSecondaryCapReloadTest {
    private val neutraliteIII = WakfuBestBuildFinderAlgorithm.sublimations.single { it.stateId == 6931 }

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 110,
        name = I18nText("sc$id", "sc$id", "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = stats,
        maxShardSlots = 4
    )

    private val helmet = item(950_001, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600, Characteristic.MASTERY_DISTANCE to 100))
    private val rearCape = item(950_002, ItemType.CAPE, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600, Characteristic.MASTERY_BACK to -100))
    private val skills = CharacterSkills(110)
    private val savedBuild =
        BuildCombination(equipments = listOf(helmet, rearCape), characterSkills = skills, sublimations = mapOf(helmet to listOf(neutraliteIII)))

    private fun newModel(
        scope: CoroutineScope,
        repository: HistoryRepository = HistoryRepository(baseDir = Files.createTempDirectory("wakfu-test-history"), ioDispatcher = Dispatchers.Unconfined),
        buildFinder: (WakfuBestBuildParams) -> Flow<SolverResult<BuildCombination>> = {
            flowOf(SolverResult(individual = savedBuild, matchPercentage = BigDecimal("5000"), progressPercentage = 100, isOptimal = false))
        },
        buildRescorer: (WakfuBestBuildParams, BuildCombination) -> BigDecimal = { params, build -> WakfuBestBuildFinderAlgorithm.rescore(params, build) },
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = buildFinder,
            optimalityProver = { _, _, _, _ -> MaxDamageSearch.MaxDamageProof.Unavailable },
            zenithBuilder = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
            backgroundProofCanceller = {},
            buildRescorer = buildRescorer,
            historyRepository = repository
        )

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) delay(20.milliseconds)
        }
    }

    @Test
    fun `a loaded build whose secondary masteries only cancel out across stats gets no Neutralite bonus`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope)
            try {
                model.setMode(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)
                model.setScenario(DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE))
                model.setDuration("1")
                model.search()
                awaitUntil { model.ui.phase == Phase.Done }
                model.saveBuild("Neutralité offset", null, asNew = true)
                awaitUntil { model.ui.savedBuilds.any { it.name == "Neutralité offset" } }
                val id =
                    model.ui.savedBuilds
                        .single()
                        .id
                model.loadBuild(id)

                val character = Character(CharacterClass.CRA, model.ui.level, model.ui.minLevel, skills)
                val scenario = model.ui.scenario
                val loaded = requireNotNull(model.ui.spellRotation).totalExpectedDamage
                // Distance +100 stays positive however much rear the cape takes away: Neutralité III's +24 % DI is inactive,
                // so the loaded rotation is exactly the build's rotation without the sub.
                val withoutSub = SpellRotationOptimizer.bestSequencedRotation(savedBuild.copy(sublimations = emptyMap()), character, character.clazz, scenario)
                assertEquals(withoutSub.totalExpectedDamage, loaded)
                // Control: the same build with the distance offset WITHIN distance (−100 distance on the cape) does carry it.
                val distanceCape = rearCape.copy(characteristics = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600, Characteristic.MASTERY_DISTANCE to -100))
                val offsetWithin = savedBuild.copy(equipments = listOf(helmet, distanceCape))
                val active = SpellRotationOptimizer.bestSequencedRotation(offsetWithin, character, character.clazz, scenario).totalExpectedDamage
                val inactive =
                    SpellRotationOptimizer
                        .bestSequencedRotation(offsetWithin.copy(sublimations = emptyMap()), character, character.clazz, scenario)
                        .totalExpectedDamage
                assertTrue(active > inactive, "Neutralité III must be credited when distance nets to 0 within its own stat ($active vs $inactive)")
            } finally {
                scope.cancel()
            }
        }

    // ---- a save made under the old rules: its stored numbers are the old rules' ---------------------------------------------

    private val mostMasteries = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
    private val maxDamage = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
    private val base = Character(CharacterClass.CRA, 110, 0, skills).baseCharacteristicValues
    private val fireAndDistance = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1), TargetStat(Characteristic.MASTERY_DISTANCE, 1)))
    private val faceScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)

    /** [savedBuild] without its sublimation: what it is worth when Neutralité III does not fire (an inactive sub adds nothing). */
    private val buildWithoutSub = savedBuild.copy(sublimations = emptyMap())

    /**
     * [savedBuild] as an engine that read the Neutralité family on the SUM of the secondary masteries scored it: distance +100 and
     * rear −100 net to 0, so Neutralité III counted — its +24 % DI is credited whatever the individual masteries.
     */
    private val creditedBuild = savedBuild.copy(sublimations = mapOf(helmet to listOf(neutraliteIII.copy(condition = null))))

    private fun gridOf(
        build: BuildCombination,
        mode: ScoreComputationMode,
        targetStats: TargetStats,
    ): Map<Characteristic, Int> =
        computeCharacteristicsValues(
            buildCombination = build,
            characterBaseCharacteristics = base,
            masteryElementsWanted = targetStats.masteryElementsWanted,
            resistanceElementsWanted = targetStats.resistanceElementsWanted,
            scoreComputationMode = mode,
            masteryElementsToMinimize = targetStats.masteryElementsToMinimize.takeIf { mode == mostMasteries }
        )

    private fun rotationDamage(build: BuildCombination): Double {
        val character = Character(CharacterClass.CRA, 110, 0, skills)
        return SpellRotationOptimizer.bestSequencedRotation(build, character, character.clazz, faceScenario).totalExpectedDamage
    }

    /** A library entry as an older version of the app wrote it: [build] with the [achieved] grid and [match] score of the old rules. */
    private fun oldSave(
        mode: ScoreComputationMode,
        targets: List<TargetSnapshot>,
        build: BuildCombination,
        achieved: Map<Characteristic, Int>,
        match: BigDecimal,
        optimal: Boolean,
    ) = HistoryEntry(
        id = "old-save",
        name = "Old save",
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
                excludedItems = emptyList(),
                scenario = faceScenario.toSnapshot()
            ),
        result =
            ResultSnapshot(
                equipments = build.equipments,
                skills = build.characterSkills.toFlatMap(),
                achieved = achieved,
                match = match.toDouble(),
                optimal = optimal,
                sublimations = build.sublimations.entries.associate { (carrier, subs) -> carrier.equipmentId to subs }
            )
    )

    /** A model whose library already holds [entry] (written before the app was updated) and which has loaded it. */
    private suspend fun modelWithLoaded(
        scope: CoroutineScope,
        entry: HistoryEntry,
        buildRescorer: (WakfuBestBuildParams, BuildCombination) -> BigDecimal = { params, build -> WakfuBestBuildFinderAlgorithm.rescore(params, build) },
    ): BuildSearchModel {
        val repository = HistoryRepository(baseDir = Files.createTempDirectory("wakfu-test-history"), ioDispatcher = Dispatchers.Unconfined)
        repository.save(entry)
        val model = newModel(scope, repository, buildRescorer = buildRescorer)
        awaitUntil { model.ui.savedBuilds.any { it.id == entry.id } }
        model.loadBuild(entry.id)
        return model
    }

    @Test
    fun `a saved most-masteries build is re-scored on load, so the Neutralite credit of the old rules and its proof flag are gone`(): Unit =
        runBlocking {
            val staleGrid = gridOf(creditedBuild, mostMasteries, fireAndDistance)
            val staleMatch = FindMostMasteriesFromInputScoring.computeScore(fireAndDistance, creditedBuild, base)
            assertEquals(24, staleGrid[Characteristic.DAMAGE_INFLICTED], "the old rules credited Neutralité III's +24 % DI")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val targets = listOf(TargetSnapshot(Characteristic.MASTERY_ELEMENTARY_FIRE, "1"), TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1"))
                val model = modelWithLoaded(scope, oldSave(mostMasteries, targets, savedBuild, staleGrid, staleMatch, optimal = true))

                val expectedMatch = FindMostMasteriesFromInputScoring.computeScore(fireAndDistance, buildWithoutSub, base)
                assertTrue(expectedMatch < staleMatch, "the credit was worth something ($expectedMatch vs $staleMatch)")
                // The stats column and the score both read the current rules: exactly the build's numbers without the bonus.
                assertEquals(gridOf(buildWithoutSub, mostMasteries, fireAndDistance), model.ui.achieved)
                assertEquals(0, model.ui.achieved[Characteristic.DAMAGE_INFLICTED] ?: 0)
                assertThat(model.ui.match).isEqualByComparingTo(expectedMatch)
                // The stored "proven optimal" was a proof for the old rules, whose score this build no longer has.
                assertFalse(model.ui.optimal, "a proof for the old rules is not restored for a score that moved")
                assertEquals(ProofState.Idle, model.ui.proofState)
                // It is the same build, still carrying its sublimation: only what it is worth changed.
                assertEquals(
                    listOf(helmet.equipmentId, rearCape.equipmentId),
                    model.ui.build
                        ?.equipments
                        ?.map { it.equipmentId }
                )
                assertEquals(
                    listOf(neutraliteIII.stateId),
                    model.ui.build
                        ?.sublimations
                        ?.values
                        ?.flatten()
                        ?.map { it.stateId }
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a saved build whose numbers still hold is shown exactly as saved, proof flag included`(): Unit =
        runBlocking {
            // Distance +100 − 100 WITHIN distance: Neutralité III fires under the old rules and the current ones alike.
            val distanceCape = rearCape.copy(characteristics = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600, Characteristic.MASTERY_DISTANCE to -100))
            val firing = savedBuild.copy(equipments = listOf(helmet, distanceCape))
            val grid = gridOf(firing, mostMasteries, fireAndDistance)
            val score = FindMostMasteriesFromInputScoring.computeScore(fireAndDistance, firing, base)
            assertEquals(24, grid[Characteristic.DAMAGE_INFLICTED], "control: the credit is really earned here")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val targets = listOf(TargetSnapshot(Characteristic.MASTERY_ELEMENTARY_FIRE, "1"), TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1"))
                val model = modelWithLoaded(scope, oldSave(mostMasteries, targets, firing, grid, score, optimal = true))

                assertEquals(grid, model.ui.achieved)
                assertThat(model.ui.match).isEqualByComparingTo(score)
                assertTrue(model.ui.optimal, "a save the current rules agree with keeps its proof flag")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a saved max-damage build agrees with itself on load - its headline, its stats and its rotation all lose the old credit`(): Unit =
        runBlocking {
            val staleGrid = gridOf(creditedBuild, maxDamage, TargetStats(emptyList()))
            val staleMatch = rotationDamage(creditedBuild).toBigDecimal().setScale(4, RoundingMode.FLOOR)
            assertEquals(24, staleGrid[Characteristic.DAMAGE_INFLICTED], "the old rules credited Neutralité III's +24 % DI")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model = modelWithLoaded(scope, oldSave(maxDamage, emptyList(), savedBuild, staleGrid, staleMatch, optimal = true))

                val rotation = requireNotNull(model.ui.spellRotation).totalExpectedDamage
                assertEquals(rotationDamage(buildWithoutSub), rotation, "the rotation card has no credit")
                // The headline reads the very rotation the card shows (the damage it was searched for), the stats column has no credit.
                assertThat(model.ui.match).isEqualByComparingTo(rotation.toBigDecimal().setScale(4, RoundingMode.FLOOR))
                assertTrue(model.ui.match < staleMatch, "the credit was worth damage (${model.ui.match} vs $staleMatch)")
                assertEquals(0, model.ui.achieved[Characteristic.DAMAGE_INFLICTED] ?: 0)
                assertFalse(model.ui.optimal, "a proof for the old rules is not restored for a score that moved")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `in every mode a build reads the same reloaded as when the search found it`(): Unit =
        runBlocking {
            for (mode in ScoreComputationMode.entries) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    // The engine's own score of the build under the request it is given, as a real search streams it, proven optimal.
                    val model =
                        newModel(
                            scope,
                            buildFinder = { params ->
                                flowOf(SolverResult(savedBuild, WakfuBestBuildFinderAlgorithm.rescore(params, savedBuild), progressPercentage = 100, isOptimal = true))
                            }
                        )
                    model.setMode(mode)
                    model.setScenario(faceScenario)
                    model.setDuration("1")
                    model.search()
                    awaitUntil { model.ui.phase == Phase.Done }
                    val found = model.ui
                    model.saveBuild("Round trip", null, asNew = true)
                    awaitUntil { model.ui.savedBuilds.isNotEmpty() }
                    model.loadBuild(
                        model.ui.savedBuilds
                            .single()
                            .id
                    )
                    val reloaded = model.ui

                    assertEquals(found.achieved, reloaded.achieved, "$mode: the stats grid")
                    assertThat(reloaded.match).describedAs("$mode: the score").isEqualByComparingTo(found.match)
                    assertEquals(found.spellRotation?.totalExpectedDamage, reloaded.spellRotation?.totalExpectedDamage, "$mode: the rotation")
                    assertTrue(reloaded.optimal, "$mode: nothing moved, so the proof flag of the search holds")
                } finally {
                    scope.cancel()
                }
            }
        }

    @Test
    fun `a score too long for a Double still keeps the proof flag of a save the rules agree with`(): Unit =
        runBlocking {
            // 17 significant digits, the shape of a quotient with float noise. A save keeps its score as a Double and BigDecimal → Double →
            // BigDecimal does not give such a score back, so comparing the BigDecimals would call an unchanged score changed.
            val score = BigDecimal("1488.1234567890123")
            assertNotEquals(0, score.compareTo(score.toDouble().toBigDecimal()), "precondition: the naive BigDecimal comparison fails for this score")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val targets = listOf(TargetSnapshot(Characteristic.MASTERY_ELEMENTARY_FIRE, "1"), TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1"))
                val grid = gridOf(savedBuild, mostMasteries, fireAndDistance)
                val unchanged = modelWithLoaded(scope, oldSave(mostMasteries, targets, savedBuild, grid, score, optimal = true)) { _, _ -> score }
                assertTrue(unchanged.ui.optimal, "the score did not move, so the proof flag holds")
                assertThat(unchanged.ui.match).isEqualByComparingTo(score)

                // A score that really moved still drops it.
                val moved = modelWithLoaded(scope, oldSave(mostMasteries, targets, savedBuild, grid, score, optimal = true)) { _, _ -> score.add(BigDecimal.ONE) }
                assertFalse(moved.ui.optimal, "a score that moved loses the proof flag")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a saved build the scorer cannot read keeps its stored numbers`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                // A precision save with no target rows (an odd import): its % match is 0 / 0 and cannot be re-computed.
                val stored = mapOf(Characteristic.DAMAGE_INFLICTED to 24)
                val entry = oldSave(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, emptyList(), savedBuild, stored, BigDecimal("77.5"), optimal = true)
                val model = modelWithLoaded(scope, entry)

                assertEquals(Phase.Done, model.ui.phase)
                assertEquals(stored, model.ui.achieved)
                assertThat(model.ui.match).isEqualByComparingTo(BigDecimal("77.5"))
                assertTrue(model.ui.optimal)
            } finally {
                scope.cancel()
            }
        }
}
