package me.chosante.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.WakfuSolver
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
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

class BuildSearchModelE2ETest {
    @Test
    fun `max mastery targets are selected as binary objectives`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model =
            BuildSearchModel(
                scope = scope,
                buildFinder = { emptyFlow() },
                zenithBuilder = { "" },
                mainDispatcher = Dispatchers.Unconfined
            )

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
        val model =
            BuildSearchModel(
                scope = scope,
                buildFinder = { emptyFlow() },
                zenithBuilder = { "" },
                mainDispatcher = Dispatchers.Unconfined
            )

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
                BuildSearchModel(
                    scope = scope,
                    buildFinder = {
                        flowOf(
                            GeneticAlgorithmResult(
                                individual = fakeBuild,
                                matchPercentage = BigDecimal("100"),
                                progressPercentage = 100,
                                isOptimal = true
                            )
                        )
                    },
                    zenithBuilder = { zenithUrl },
                    openBrowser = { openedLinks += it },
                    copyToClipboard = { copiedLinks += it },
                    mainDispatcher = Dispatchers.Unconfined
                )

            try {
                model.setDuration("1")
                model.setMinLevel("0")
                model.setSolver(WakfuSolver.GENETIC_ALGORITHM)
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

    private fun UiState.toTargetStats(): TargetStats = TargetStats(targets.map { TargetStat(it.characteristic, it.value.toIntOrNull() ?: 0) })

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) {
                delay(50)
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
