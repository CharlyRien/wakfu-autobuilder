package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.chosante.autobuilder.VERSION
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.Character
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.GeneticAlgorithm
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.autobuilder.genetic.generateRandomPopulations
import me.chosante.autobuilder.genetic.tournamentSelection
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity

object WakfuBestBuildFinderAlgorithm {

    private val logger = KotlinLogging.logger {}

    suspend fun run(
        params: WakfuBestBuildParams,
    ): Flow<GeneticAlgorithmResult<BuildCombination>> {
        val equipments = getEquipments()

        val equipmentsByItemType = groupAndFilterEquipments(
            equipments = equipments,
            excludedItems = params.excludedItems,
            forcedItems = params.forcedItems,
            maxRarity = params.maxRarity,
            character = params.character
        )

        val targetStats = params.targetStats
        return try {
            val mutationProbability = 0.03
            val numberOfIndividualsInPopulation = 10000
            GeneticAlgorithm(
                population = generateRandomPopulations(
                    numberOfIndividual = numberOfIndividualsInPopulation,
                    equipmentsByItemType = equipmentsByItemType,
                    character = params.character,
                    targetStats = targetStats
                ),
                score = { combination ->
                    calculateSuccessPercentage(
                        targetStats = targetStats,
                        buildCombination = combination,
                        characterBaseCharacteristics = params.character.baseCharacteristicValues
                    )
                },
                select = ::tournamentSelection,
                cross = ::cross,
                mutate = { combination ->
                    mutateCombination(
                        individual = combination,
                        mutationProbability = mutationProbability,
                        equipmentsByItemType = equipmentsByItemType,
                        targetStats = targetStats
                    )
                }
            ).run(duration = params.searchDuration, stopWhenBuildMatch = params.stopWhenBuildMatch)
        } catch (exception: Exception) {
            logger.error(exception) { "Exception occurred during the process of finding the best equipments." }
            exitProcess(1)
        }
    }

    private fun groupAndFilterEquipments(
        equipments: List<Equipment>,
        excludedItems: List<String>,
        forcedItems: List<String>,
        maxRarity: Rarity,
        character: Character,
    ): Map<ItemType, List<Equipment>> {
        val itemsExcluded = excludedItems.map { it.lowercase() }
        val itemsToForce = forcedItems.map { it.lowercase() }
        val equipmentsByItemType = equipments
            .asSequence()
            .filter { equipment ->
                equipment.rarity <= maxRarity
            }
            .filter { equipment ->
                (equipment.level <= character.level && equipment.level >= maxOf(1, character.level - 50)) ||
                    (equipment.itemType == ItemType.PETS || equipment.itemType == ItemType.MOUNTS)
            }
            .filter { equipment -> equipment.name.lowercase() !in itemsExcluded }
            .groupBy { it.itemType }
            .mapValues { (_, value) ->
                if (value.any { it.name.lowercase() in itemsToForce }) {
                    value.filter { it.name.lowercase() in itemsToForce || itemsToForce.isEmpty() }
                } else {
                    value
                }
            }
        return equipmentsByItemType
    }

    private suspend fun getEquipments(): List<Equipment> {
        val equipments = withContext(Dispatchers.IO) {
            val equipmentsRaw =
                this.javaClass.classLoader.getResourceAsStream("equipments-v$VERSION.json")?.readAllBytes()!!
            Json.decodeFromString<List<Equipment>>(String(equipmentsRaw))
        }
        return equipments
    }
}

fun List<Equipment>.replaceRandomlyOneItemWithRarity(
    rarity: Rarity,
    replacementResearchZone: List<Equipment>,
    ringNames: List<String> = listOf(),
): List<Equipment> {
    val equipments = this.toMutableList()
    val equipmentToRemove = equipments.filter { it.rarity == rarity }.random()

    val replacementEquipment = findReplacementItem(
        equipmentTypeToReplace = equipmentToRemove.itemType,
        researchZone = replacementResearchZone,
        equipmentsToExclude = equipments,
        ringNamesToExclude = ringNames
    )

    if (replacementEquipment != null) {
        equipments.remove(equipmentToRemove)
        equipments.add(replacementEquipment)
    } else {
        val otherEquipmentToRemove = equipments.first { it.rarity == rarity && it != equipmentToRemove }
        findReplacementItem(
            equipmentTypeToReplace = otherEquipmentToRemove.itemType,
            researchZone = replacementResearchZone,
            equipmentsToExclude = equipments,
            ringNamesToExclude = ringNames
        )?.let {
            equipments.remove(otherEquipmentToRemove)
            equipments.add(it)
        } ?: equipments.remove(listOf(otherEquipmentToRemove, equipmentToRemove).random())
    }

    return equipments
}

private fun findReplacementItem(
    equipmentTypeToReplace: ItemType,
    researchZone: List<Equipment>,
    equipmentsToExclude: List<Equipment>,
    raritiesToExclude: List<Rarity> = listOf(Rarity.EPIC, Rarity.RELIC),
    ringNamesToExclude: List<String>,
): Equipment? {
    return researchZone
        .filter {
            it.itemType == equipmentTypeToReplace &&
                it !in equipmentsToExclude &&
                it.rarity !in raritiesToExclude &&
                (it.itemType != ItemType.RING || it.name !in ringNamesToExclude)
        }.randomOrNull()
}

data class WakfuBestBuildParams(
    val character: Character,
    val targetStats: TargetStats,
    val searchDuration: Duration,
    val stopWhenBuildMatch: Boolean,
    val maxRarity: Rarity,
    val forcedItems: List<String>,
    val excludedItems: List<String>,
)
