package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import me.chosante.autobuilder.VERSION
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.common.Character
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import kotlin.system.exitProcess
import kotlin.time.Duration

object WakfuBestBuildFinderAlgorithm {
    private val logger = KotlinLogging.logger {}

    val equipments =
        this.javaClass.classLoader.getResourceAsStream("equipments-v$VERSION.json")?.readAllBytes()!!.let {
            Json.decodeFromString<List<Equipment>>(String(it))
        }

    fun run(params: WakfuBestBuildParams): Flow<GeneticAlgorithmResult<BuildCombination>> {
        val equipmentsByItemType =
            groupAndFilterEquipments(
                excludedItems = params.excludedItems,
                forcedItems = params.forcedItems,
                maxRarity = params.maxRarity,
                character = params.character
            )

        return try {
            WakfuBuildSolver.optimize(params, equipmentsByItemType)
        } catch (exception: Exception) {
            logger.error(exception) { "Exception occurred during the process of finding the best equipments." }
            exitProcess(1)
        }
    }

    private fun groupAndFilterEquipments(
        excludedItems: List<String>,
        forcedItems: List<String>,
        maxRarity: Rarity,
        character: Character,
    ): Map<ItemType, List<Equipment>> {
        val itemsExcluded = excludedItems.map { it.lowercase() }
        val itemsToForce = forcedItems.map { it.lowercase() }
        val eligibleEquipments =
            equipments
                .asSequence()
                .filter { equipment ->
                    equipment.rarity <= maxRarity
                }.filter { equipment ->
                    (equipment.level <= character.level && equipment.level >= character.minLevel) ||
                        (equipment.itemType == ItemType.PETS || equipment.itemType == ItemType.MOUNTS)
                }.filter { equipment -> equipment.name.fr.lowercase() !in itemsExcluded }
                .toList()
        val forcedWeaponTypes =
            eligibleEquipments
                .filter { it.name.fr.lowercase() in itemsToForce }
                .map { it.itemType }
                .toSet()
        val equipmentsByItemType =
            eligibleEquipments
                .groupBy { it.itemType }
                .mapValues { (_, value) ->
                    if (value.any { it.name.fr.lowercase() in itemsToForce }) {
                        value.filter { it.name.fr.lowercase() in itemsToForce || itemsToForce.isEmpty() }
                    } else {
                        value
                    }
                }.toMutableMap()
        if (ItemType.TWO_HANDED_WEAPONS in forcedWeaponTypes) {
            equipmentsByItemType.remove(ItemType.ONE_HANDED_WEAPONS)
            equipmentsByItemType.remove(ItemType.OFF_HAND_WEAPONS)
        } else if (ItemType.ONE_HANDED_WEAPONS in forcedWeaponTypes || ItemType.OFF_HAND_WEAPONS in forcedWeaponTypes) {
            equipmentsByItemType.remove(ItemType.TWO_HANDED_WEAPONS)
        }
        return equipmentsByItemType
    }
}

internal fun List<Equipment>.replaceRandomlyOneItemWithRarity(
    rarity: Rarity,
    replacementResearchZone: List<Equipment>,
    ringNames: List<String> = listOf(),
    random: kotlin.random.Random = kotlin.random.Random.Default,
): List<Equipment> {
    val equipments = this.toMutableList()
    val equipmentToRemove = equipments.filter { it.rarity == rarity }.random(random)

    val replacementEquipment =
        findReplacementItem(
            equipmentTypeToReplace = equipmentToRemove.itemType,
            researchZone = replacementResearchZone,
            equipmentsToExclude = equipments,
            ringNamesToExclude = ringNames,
            random = random
        )

    if (replacementEquipment == null) {
        val otherEquipmentToRemove = equipments.firstOrNull { it.rarity == rarity && it != equipmentToRemove }
        if (otherEquipmentToRemove == null) {
            return this
        }
        findReplacementItem(
            equipmentTypeToReplace = otherEquipmentToRemove.itemType,
            researchZone = replacementResearchZone,
            equipmentsToExclude = equipments,
            ringNamesToExclude = ringNames,
            random = random
        )?.let {
            equipments.remove(otherEquipmentToRemove)
            equipments.add(it)
        } ?: equipments.remove(listOf(otherEquipmentToRemove, equipmentToRemove).random(random))
    }

    equipments.remove(equipmentToRemove)
    replacementEquipment?.let { equipments.add(it) }
    return equipments
}

private fun findReplacementItem(
    equipmentTypeToReplace: ItemType,
    researchZone: List<Equipment>,
    equipmentsToExclude: List<Equipment>,
    raritiesToExclude: List<Rarity> = listOf(Rarity.EPIC, Rarity.RELIC),
    ringNamesToExclude: List<String>,
    random: kotlin.random.Random = kotlin.random.Random.Default,
): Equipment? =
    researchZone
        .filter {
            it.itemType == equipmentTypeToReplace &&
                it !in equipmentsToExclude &&
                it.rarity !in raritiesToExclude &&
                (it.itemType != ItemType.RING || it.name.fr !in ringNamesToExclude)
        }.randomOrNull(random)

data class WakfuBestBuildParams(
    val character: Character,
    val targetStats: TargetStats,
    val searchDuration: Duration,
    val stopWhenBuildMatch: Boolean,
    val maxRarity: Rarity,
    val forcedItems: List<String>,
    val excludedItems: List<String>,
    val scoreComputationMode: ScoreComputationMode,
    val populationSize: Int = 20000,
)
