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
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.genetic.SolverResult
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
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A saved max-damage build is re-scored when it is loaded (its rotation is recomputed), so a build saved while the
 * Neutralité family was read as the SUM of the secondary masteries — distance +100 offset by rear −100 — comes back with
 * Neutralité III inactive: each secondary mastery must be ≤ 0 on its own, as in the game.
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

    private fun newModel(scope: CoroutineScope): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = {
                flowOf(SolverResult(individual = savedBuild, matchPercentage = BigDecimal("5000"), progressPercentage = 100, isOptimal = false))
            },
            optimalityProver = { _, _, _, _ -> MaxDamageSearch.MaxDamageProof.Unavailable },
            zenithBuilder = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
            backgroundProofCanceller = {},
            historyRepository = HistoryRepository(baseDir = Files.createTempDirectory("wakfu-test-history"), ioDispatcher = Dispatchers.Unconfined)
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
}
