package me.chosante.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.FindMostMasteriesFromInputScoring
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A row of target 0 on a required stat is a FLOOR ("never below 0") since the zero-target-rows change: a saved build whose air
 * resistance is negative under the default "air resistance 0" row was scored as if the row did not exist. Loaded, it is
 * re-scored under the current rules (#232): the score is halved, so the stored "proven optimal" flag — a proof for the old
 * rules — is not restored. A save that keeps every floor reads exactly as saved, flag included — unless an older version
 * searched its request on the pre-filtered pool, which counted a resistance row of target 0 as a wanted element.
 */
class BuildSearchModelFloorReloadTest {
    private fun item(
        id: Int,
        stats: Map<Characteristic, Int>,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 110,
        name = I18nText("fl$id", "fl$id", "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = ItemType.HELMET,
        characteristics = stats,
        maxShardSlots = 0
    )

    private val skills = CharacterSkills(110)
    private val targets = listOf(TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1"), TargetSnapshot(Characteristic.RESISTANCE_ELEMENTARY_WIND, "0"))

    private fun oldSave(
        build: BuildCombination,
        match: BigDecimal,
        targets: List<TargetSnapshot> = this.targets,
    ) = HistoryEntry(
        id = "old-floor-save",
        name = "Old floor save",
        createdAt = 1_000L,
        dataVersion = WakfuBestBuildFinderAlgorithm.dataVersion,
        request =
            RequestSnapshot(
                clazz = CharacterClass.CRA.name,
                level = 110,
                minLevel = 0,
                mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT.name,
                maxRarity = Rarity.EPIC,
                duration = "10",
                stopAtMatch = false,
                targets = targets,
                forcedItems = emptyList(),
                excludedItems = emptyList()
            ),
        result =
            ResultSnapshot(
                equipments = build.equipments,
                skills = build.characterSkills.toFlatMap(),
                achieved = emptyMap(),
                match = match.toDouble(),
                optimal = true
            )
    )

    private suspend fun loaded(
        scope: CoroutineScope,
        entry: HistoryEntry,
    ): BuildSearchModel {
        val repository = HistoryRepository(baseDir = Files.createTempDirectory("wakfu-test-history"), ioDispatcher = Dispatchers.Unconfined)
        repository.save(entry)
        val model =
            BuildSearchModel(
                scope = scope,
                buildFinder = { flowOf(SolverResult(individual = BuildCombination(emptyList(), skills), matchPercentage = BigDecimal.ONE, progressPercentage = 100)) },
                optimalityProver = { _, _, _, _ -> MaxDamageSearch.MaxDamageProof.Unavailable },
                zenithBuilder = { "" },
                mainDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
                libraryPreferences = LibraryPreferences(null),
                backgroundProofCanceller = {},
                buildRescorer = { params, build -> WakfuBestBuildFinderAlgorithm.rescore(params, build) },
                historyRepository = repository
            )
        withTimeout(25.seconds) {
            while (model.ui.savedBuilds.none { it.id == entry.id }) delay(20.milliseconds)
        }
        model.loadBuild(entry.id)
        return model
    }

    @Test
    fun `a saved build that breaks a floor is re-scored halved and loses its proof flag`(): Unit =
        runBlocking {
            // Distance 100 with −20 air resistance: the old rules ignored the "air resistance 0" row and scored it 100.
            val build = BuildCombination(listOf(item(970_001, mapOf(Characteristic.MASTERY_DISTANCE to 100, Characteristic.RESISTANCE_ELEMENTARY_WIND to -20))), skills)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model = loaded(scope, oldSave(build, BigDecimal(100)))
                assertThat(model.ui.match).isEqualByComparingTo(BigDecimal(50))
                assertFalse(model.ui.optimal, "a proof for the old rules is not restored for a score that moved")
                assertEquals(-20, model.ui.achieved[Characteristic.RESISTANCE_ELEMENTARY_WIND], "the stats column shows the floor's value")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a saved build that keeps every floor reads exactly as saved, proof flag included`(): Unit =
        runBlocking {
            val build = BuildCombination(listOf(item(970_002, mapOf(Characteristic.MASTERY_DISTANCE to 100, Characteristic.RESISTANCE_ELEMENTARY_WIND to 5))), skills)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model = loaded(scope, oldSave(build, BigDecimal(100)))
                assertThat(model.ui.match).isEqualByComparingTo(BigDecimal(100))
                assertTrue(model.ui.optimal, "the current rules agree with the save: its proof flag holds")
                assertEquals(5, model.ui.achieved[Characteristic.RESISTANCE_ELEMENTARY_WIND])
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `an old save of fire resistance 100 beside the default air 0 loses its proof flag - its search ran on the pre-filtered pool`(): Unit =
        runBlocking {
            // The review's repro: before rows of target 0 became floors, "air resistance 0" was a WANTED element, so "fire resistance
            // 100" beside it made the request multi-element and searched it on the pre-filtered pool — and 1.13 stamped CP-SAT's
            // OPTIMAL over that reduced pool as "proven optimal". A request on one element now, it re-scores exactly as saved: only
            // the old reading can tell its stored proof does not cover the whole catalog.
            val build =
                BuildCombination(
                    listOf(
                        item(
                            980_001,
                            mapOf(
                                Characteristic.MASTERY_DISTANCE to 100,
                                Characteristic.RESISTANCE_ELEMENTARY_FIRE to 120,
                                Characteristic.RESISTANCE_ELEMENTARY_WIND to 5
                            )
                        )
                    ),
                    skills
                )
            val rows =
                listOf(
                    TargetStat(Characteristic.MASTERY_DISTANCE, 1),
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 100),
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WIND, 0)
                )
            val stored = FindMostMasteriesFromInputScoring.computeScore(TargetStats(rows), build, Character(CharacterClass.CRA, 110, 0, skills).baseCharacteristicValues)
            val saved = rows.map { TargetSnapshot(it.characteristic, it.target.toString()) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val model = loaded(scope, oldSave(build, stored, saved))
                assertFalse(model.ui.prefilteredRequest, "a request on one element now")
                assertThat(model.ui.match).isEqualByComparingTo(stored)
                assertFalse(model.ui.optimal, "a proof over the old pre-filtered pool is not restored")
            } finally {
                scope.cancel()
            }
        }
}
