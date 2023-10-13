package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.chosante.autobuilder.VERSION
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.Character
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.GeneticAlgorithm
import me.chosante.autobuilder.genetic.generateRandomPopulations
import me.chosante.autobuilder.genetic.tournamentSelection
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity

object WakfuBestBuildFinderAlgorithm {

    private val logger = KotlinLogging.logger {}

    suspend fun run(
        params: WakfuBestBuildParams,
    ): BuildCombination {
        val equipments = withContext(Dispatchers.IO) {
            val equipmentsRaw =
                this.javaClass.classLoader.getResourceAsStream("equipments-v$VERSION.json")?.readAllBytes()!!
            Json.decodeFromString<List<Equipment>>(String(equipmentsRaw))
        }

        val targetStats = params.targetStats
        val equipmentsByItemType = equipments
            .asSequence()
            .filter { equipment ->
                equipment.rarity <= params.maxRarity
            }
            .filter { equipment ->
                (equipment.level <= params.character.level && equipment.level >= maxOf(1, params.character.level - 50)) ||
                    (equipment.itemType == ItemType.PETS || equipment.itemType == ItemType.MOUNTS)
            }
            .filter { equipment -> equipment.name !in params.excludeItems }
            .groupBy { it.itemType }
            .mapValues { (_, value) ->
                if (value.any { it.name in params.forcedItems }) {
                    value.filter { it.name in params.forcedItems || params.forcedItems.isEmpty() }
                } else {
                    value
                }
            }

        return try {
            val mutationProbability = 0.02
            val numberOfIndividualsInPopulation = 5000
            val isLowLevel = params.character.level <= 35
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
                        targetStats = targetStats,
                        isLowLevel = isLowLevel
                    )
                }
            ).run(duration = params.searchDuration, stopWhenBuildMatch = params.stopWhenBuildMatch)
        } catch (exception: Exception) {
            logger.error(exception) { "Exception occurred during the process of finding the best equipments." }
            exitProcess(1)
        }
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
    val excludeItems: List<String>,
)
