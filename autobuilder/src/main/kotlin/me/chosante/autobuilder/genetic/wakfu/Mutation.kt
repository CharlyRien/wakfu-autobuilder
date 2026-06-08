package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.Agility
import me.chosante.common.skills.Intelligence
import me.chosante.common.skills.Luck
import me.chosante.common.skills.Major
import me.chosante.common.skills.Strength
import me.chosante.common.skills.assignRandomPoints
import kotlin.random.Random

fun mutateCombination(
    individual: BuildCombination,
    mutationProbability: Double,
    equipmentsByItemType: Map<ItemType, List<Equipment>>,
    targetStats: TargetStats,
    random: Random = Random.Default,
): BuildCombination {
    var newEquipments = individual.equipments.toMutableList()
    var ringNames = newEquipments.filter { it.itemType == ItemType.RING }.map { it.name.fr }

    for (i in newEquipments.indices) {
        val currentEquipment = newEquipments[i]
        if (random.nextDouble() <= mutationProbability) {
            val randomEquipment =
                equipmentsByItemType[currentEquipment.itemType]
                    ?.randomByOrNull(random) {
                        it != currentEquipment &&
                            (currentEquipment.itemType != ItemType.RING || it.name.fr !in ringNames)
                    } ?: break

            when (randomEquipment.rarity) {
                Rarity.EPIC -> {
                    val currentEpicEquipment = newEquipments.firstOrNull { it.rarity == Rarity.EPIC }
                    if (currentEpicEquipment == null) {
                        newEquipments[i] = randomEquipment
                    } else {
                        newEquipments =
                            newEquipments
                                .replaceRandomlyOneItemWithRarity(
                                    rarity = Rarity.EPIC,
                                    replacementResearchZone = equipmentsByItemType[currentEpicEquipment.itemType] ?: listOf(),
                                    ringNames = ringNames,
                                    random = random
                                ).toMutableList()
                    }
                }

                Rarity.RELIC -> {
                    val currentRelicEquipment = newEquipments.firstOrNull { it.rarity == Rarity.RELIC }
                    if (currentRelicEquipment == null) {
                        newEquipments[i] = randomEquipment
                    } else {
                        newEquipments =
                            newEquipments
                                .replaceRandomlyOneItemWithRarity(
                                    rarity = Rarity.RELIC,
                                    replacementResearchZone = equipmentsByItemType[currentRelicEquipment.itemType] ?: listOf(),
                                    ringNames = ringNames,
                                    random = random
                                ).toMutableList()
                    }
                }

                else -> newEquipments[i] = randomEquipment
            }
            ringNames = newEquipments.filter { it.itemType == ItemType.RING }.map { it.name.fr }
        }
    }
    val characterSkills =
        with(individual.characterSkills) {
            val targetCharacteristics = targetStats.map { it.characteristic }
            val newIntelligence =
                if (random.nextDouble() <= mutationProbability) {
                    val maxPointsToAssign = intelligence.maxPointsToAssign
                    Intelligence(maxPointsToAssign).assignRandomPoints(maxPointsToAssign, targetCharacteristics, random)
                } else {
                    intelligence
                }
            val newStrength =
                if (random.nextDouble() <= mutationProbability) {
                    val maxPointsToAssign = strength.maxPointsToAssign
                    Strength(maxPointsToAssign).assignRandomPoints(maxPointsToAssign, targetCharacteristics, random)
                } else {
                    strength
                }
            val newLuck =
                if (random.nextDouble() <= mutationProbability) {
                    val maxPointsToAssign = luck.maxPointsToAssign
                    Luck(maxPointsToAssign).assignRandomPoints(maxPointsToAssign, targetCharacteristics, random)
                } else {
                    luck
                }
            val newAgility =
                if (random.nextDouble() <= mutationProbability) {
                    val maxPointsToAssign = agility.maxPointsToAssign
                    Agility(maxPointsToAssign).assignRandomPoints(maxPointsToAssign, targetCharacteristics, random)
                } else {
                    agility
                }
            val newMajor =
                if (random.nextDouble() <= mutationProbability) {
                    val maxPointsToAssign = major.maxPointsToAssign
                    Major(maxPointsToAssign).assignRandomPoints(maxPointsToAssign, targetCharacteristics, random)
                } else {
                    major
                }
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

private fun <E> List<E>.randomByOrNull(
    random: Random,
    predicate: (E) -> Boolean,
): E? {
    var randomElement: E? = null
    repeat(10) {
        val randomIndex = random.nextInt(size)
        val element = elementAt(randomIndex)
        if (predicate(element)) {
            randomElement = element
            return@repeat
        }
    }
    return randomElement
}
