package me.chosante.ui.state

import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues
import me.chosante.common.Character
import me.chosante.common.Characteristic.ACTION_POINT
import me.chosante.common.Characteristic.MASTERY_DISTANCE
import me.chosante.common.Equipment
import me.chosante.ui.i18n.Lang
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildSearchModelE2ETest {
    @Test
    fun `known forced build runs through search stats and zenith wiring`() =
        runBlocking {
            val openedLinks = mutableListOf<String>()
            val copiedLinks = mutableListOf<String>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val zenithUrl = "https://zenithwakfu.com/builder/e2e-test"
            val model =
                BuildSearchModel(
                    scope = scope,
                    buildFinder = { params -> WakfuBestBuildFinderAlgorithm.run(params.copy(populationSize = 250)) },
                    zenithBuilder = { zenithUrl },
                    openBrowser = { openedLinks += it },
                    copyToClipboard = { copiedLinks += it },
                    mainDispatcher = Dispatchers.Unconfined
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

                assertEquals(forcedEquipmentNames.size, model.ui.forcedItems.size)
                assertEquals("Solomonk", model.ui.excludedItems.single().matchName)
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

    private fun UiState.toTargetStats(): TargetStats =
        TargetStats(targets.map { TargetStat(it.characteristic, it.value.toIntOrNull() ?: 0) })

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
                "La seconde jumelle d'Azael",
                "Frogmourne",
                "Emblème du Pouvoir",
                "Peroucan"
            )
    }
}
