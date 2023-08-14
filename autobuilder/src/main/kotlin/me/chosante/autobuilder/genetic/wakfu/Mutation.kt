package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.skills.Agility
import me.chosante.autobuilder.domain.skills.Intelligence
import me.chosante.autobuilder.domain.skills.Luck
import me.chosante.autobuilder.domain.skills.Major
import me.chosante.autobuilder.domain.skills.Strength
import me.chosante.autobuilder.domain.skills.assignRandomPoints
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import kotlin.random.Random.Default.nextInt

fun mutateCombination(
    individual: BuildCombination,
    mutationProbability: Double,
    equipmentsByItemType: Map<ItemType, List<Equipment>>,
    targetStats: TargetStats,
    isLowLevel: Boolean
): BuildCombination {
    var newEquipments = individual.equipments.toMutableList()
    var ringNames = newEquipments.filter { it.itemType == ItemType.RING }.map { it.name }

    for (i in newEquipments.indices) {
        val currentEquipment = newEquipments[i]
        if (Math.random() <= mutationProbability) {
            val randomEquipment =
                equipmentsByItemType[currentEquipment.itemType]
                    ?.randomByOrNull {
                        it != currentEquipment
                                && (currentEquipment.itemType != ItemType.RING || it.name !in ringNames)
                    } ?: break

            when (randomEquipment.rarity) {
                Rarity.EPIC -> {
                    val currentEpicEquipment = newEquipments.firstOrNull { it.rarity == Rarity.EPIC }
                    if (currentEpicEquipment == null) {
                        newEquipments[i] = randomEquipment
                    } else {
                        newEquipments = newEquipments.replaceRandomlyOneItemWithRarity(
                            rarity = Rarity.EPIC,
                            replacementResearchZone = equipmentsByItemType[currentEpicEquipment.itemType] ?: listOf(),
                            ringNames = ringNames
                        ).toMutableList()
                    }
                }

                Rarity.RELIC -> {
                    val currentRelicEquipment = newEquipments.firstOrNull { it.rarity == Rarity.RELIC }
                    if (currentRelicEquipment == null) {
                        newEquipments[i] = randomEquipment
                    } else {
                        newEquipments = newEquipments.replaceRandomlyOneItemWithRarity(
                            rarity = Rarity.RELIC,
                            replacementResearchZone = equipmentsByItemType[currentRelicEquipment.itemType] ?: listOf(),
                            ringNames = ringNames
                        ).toMutableList()
                    }
                }

                else -> newEquipments[i] = randomEquipment
            }
            ringNames = newEquipments.filter { it.itemType == ItemType.RING }.map { it.name }
        }
    }
    val characterSkills = with(individual.characterSkills) {
        val targetCharacteristics = targetStats.map { it.characteristic }
        val newIntelligence = if (Math.random() <= mutationProbability) {
            val maxPointsToAssign = intelligence.maxPointsToAssign
            Intelligence(maxPointsToAssign).assignRandomPoints(maxPointsToAssign, targetCharacteristics)
        } else intelligence
        val newStrength = if (Math.random() <= mutationProbability) {
            val maxPointsToAssign = strength.maxPointsToAssign
            Strength(maxPointsToAssign).assignRandomPoints(maxPointsToAssign, targetCharacteristics)
        } else strength
        val newLuck = if (Math.random() <= mutationProbability) {
            val maxPointsToAssign = luck.maxPointsToAssign
            Luck(maxPointsToAssign).assignRandomPoints(maxPointsToAssign, targetCharacteristics)
        } else luck
        val newAgility = if (Math.random() <= mutationProbability) {
            val maxPointsToAssign = agility.maxPointsToAssign
            Agility(maxPointsToAssign).assignRandomPoints(maxPointsToAssign, targetCharacteristics)
        } else agility
        val newMajor = if (Math.random() <= mutationProbability) {
            val maxPointsToAssign = major.maxPointsToAssign
            Major(maxPointsToAssign).assignRandomPoints(maxPointsToAssign, targetCharacteristics)
        } else major
        copy(
            intelligence = newIntelligence,
            strength = newStrength,
            luck = newLuck,
            agility = newAgility,
            major = newMajor
        )
    }


    return BuildCombination(newEquipments, characterSkills)
}

private fun <E> List<E>.randomByOrNull(predicate: (E) -> Boolean): E? {
    var randomElement: E? = null
    repeat(10) {
        val randomIndex = nextInt(size)
        val element = elementAt(randomIndex)
        if (predicate(element)) {
            randomElement = element
            return@repeat
        }
    }
    return randomElement
}
