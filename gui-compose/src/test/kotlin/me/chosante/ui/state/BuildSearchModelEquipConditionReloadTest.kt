package me.chosante.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ExclusiveGroup
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A saved build the game refuses — an item EQUIP condition it breaks — is no proven optimum when it is loaded, whatever
 * flag was stored with it. A save made before the conditions were enforced can wear Épée de Brâkmar without the Anneau de
 * Brâkmar the game requires with it (the v56 level-245 "proven optimum" did). A save never carries the items' conditions
 * (`Equipment.equipCriterion` is not saved), so the check reads them from the catalog by item id.
 */
class BuildSearchModelEquipConditionReloadTest {
    private val catalog = WakfuBestBuildFinderAlgorithm.equipments.associateBy { it.equipmentId }
    private val brakmarSword = catalog.getValue(26497)
    private val brakmarRing = catalog.getValue(26578)

    // Any other ring of the catalog the sword's ring could pair with.
    private val otherRing: Equipment =
        WakfuBestBuildFinderAlgorithm.equipments.first {
            it.itemType == brakmarRing.itemType && it.equipCriterion?.constrainsBuild != true && it.level in 150..200 && it.rarity == Rarity.LEGENDARY
        }

    private val skills = CharacterSkills(200)
    private val storedScore = BigDecimal("1234")

    private fun save(build: BuildCombination) =
        HistoryEntry(
            id = "old-save",
            name = "Old save",
            createdAt = 1_000L,
            dataVersion = WakfuBestBuildFinderAlgorithm.dataVersion,
            request =
                RequestSnapshot(
                    clazz = CharacterClass.CRA.name,
                    level = 200,
                    minLevel = 0,
                    mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT.name,
                    maxRarity = Rarity.EPIC,
                    duration = "10",
                    stopAtMatch = false,
                    targets = listOf(TargetSnapshot(Characteristic.MASTERY_ELEMENTARY_FIRE, "1")),
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE).toSnapshot()
                ),
            result =
                ResultSnapshot(
                    equipments = build.equipments,
                    skills = build.characterSkills.toFlatMap(),
                    achieved = emptyMap(),
                    match = storedScore.toDouble(),
                    optimal = true
                )
        )

    /** A model that loaded [entry] from its library; the re-score is pinned to the stored score, so only the conditions can move the flag. */
    private suspend fun loaded(
        scope: CoroutineScope,
        entry: HistoryEntry,
    ): BuildSearchModel {
        val repository = HistoryRepository(baseDir = Files.createTempDirectory("wakfu-test-history"), ioDispatcher = Dispatchers.Unconfined)
        repository.save(entry)
        val model =
            BuildSearchModel(
                scope = scope,
                buildFinder = { emptyFlow() },
                optimalityProver = { _, _, _, _ -> MaxDamageSearch.MaxDamageProof.Unavailable },
                zenithBuilder = { "" },
                mainDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
                libraryPreferences = LibraryPreferences(null),
                backgroundProofCanceller = {},
                buildRescorer = { _, _ -> storedScore },
                historyRepository = repository
            )
        withTimeout(25.seconds) {
            while (model.ui.savedBuilds.none { it.id == entry.id }) delay(20.milliseconds)
        }
        model.loadBuild(entry.id)
        return model
    }

    @Test
    fun `a saved build wearing a nation sword without its ring loses its proven-optimal flag on load`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val illegal = BuildCombination(equipments = listOf(brakmarSword, otherRing), characterSkills = skills)
                val model = loaded(scope, save(illegal))
                // The save itself carries no condition: the reloaded items come back without one.
                assertNull(
                    model.ui.build
                        ?.equipments
                        ?.single { it.equipmentId == brakmarSword.equipmentId }
                        ?.equipCriterion
                )
                assertFalse(model.ui.optimal, "the game refuses the sword without its ring: no proof stands for that build")
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `the same save wearing the sword with its ring keeps its proven-optimal flag`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val legal = BuildCombination(equipments = listOf(brakmarSword, brakmarRing, otherRing), characterSkills = skills)
                val model = loaded(scope, save(legal))
                assertTrue(model.ui.optimal, "a legal save whose score still holds keeps its proof flag")
            } finally {
                scope.cancel()
            }
        }

    /**
     * 18691 Piquants du Guerrier Trool anciens is COMMON, yet in the game's EPIC exclusivity group: a save made before the groups
     * were read can wear it beside an epic item. Its saved item has no group (the field did not exist), so it follows its rarity —
     * the check reads the group from the catalog by id, like the conditions.
     */
    @Test
    fun `a saved build wearing 18691 beside an epic item loses its proven-optimal flag on load`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val trool = catalog.getValue(18691).copy(exclusiveGroupOverride = null)
                assertEquals(ExclusiveGroup.NONE, trool.exclusiveGroup)
                val epicAmulet = WakfuBestBuildFinderAlgorithm.equipments.first { it.rarity == Rarity.EPIC && it.itemType == ItemType.AMULET }
                val model = loaded(scope, save(BuildCombination(equipments = listOf(trool, epicAmulet, otherRing), characterSkills = skills)))
                assertFalse(model.ui.optimal, "the game refuses 18691 beside an epic item: no proof stands for that build")
                val alone = loaded(scope, save(BuildCombination(equipments = listOf(trool, otherRing), characterSkills = skills)))
                assertTrue(alone.ui.optimal, "18691 without an epic item is legal: the stored proof flag stays")
            } finally {
                scope.cancel()
            }
        }
}
