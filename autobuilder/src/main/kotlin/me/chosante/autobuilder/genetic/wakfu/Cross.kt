package me.chosante.autobuilder.genetic.wakfu

import kotlin.random.Random
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.ItemType
import me.chosante.common.Rarity

fun cross(parents: Pair<BuildCombination, BuildCombination>): BuildCombination {
    val (parent1, parent2) = parents
    var crossEquipments =
        buildList {
            for (itemType in ItemType.entries) {
                val equipmentParent1 = parent1.equipments.filter { it.itemType == itemType }
                val equipmentParent2 = parent2.equipments.filter { it.itemType == itemType }

                when {
                    equipmentParent1.isEmpty() && equipmentParent2.isEmpty() -> continue
                    equipmentParent1.isEmpty() -> addAll(equipmentParent2)
                    equipmentParent2.isEmpty() -> addAll(equipmentParent1)
                    else -> addAll(if (Random.nextBoolean()) equipmentParent1 else equipmentParent2)
                }
            }
        }
    val countItemsByItemType = crossEquipments.groupingBy { it.itemType }.eachCount()
    val offHandWeaponsCount = countItemsByItemType[ItemType.OFF_HAND_WEAPONS] ?: 0
    val twoHandWeaponsCount = countItemsByItemType[ItemType.TWO_HANDED_WEAPONS] ?: 0
    val oneHandedWeaponsCount = countItemsByItemType[ItemType.ONE_HANDED_WEAPONS] ?: 0
    if (offHandWeaponsCount + twoHandWeaponsCount > 1 ||
        oneHandedWeaponsCount + twoHandWeaponsCount > 1
    ) {
        crossEquipments =
            buildList {
                addAll(crossEquipments)
                val randomBoolean = Random.nextBoolean()
                removeIf {
                    when {
                        randomBoolean -> it.itemType == ItemType.OFF_HAND_WEAPONS || it.itemType == ItemType.ONE_HANDED_WEAPONS
                        else -> it.itemType == ItemType.TWO_HANDED_WEAPONS
                    }
                }
            }
    }
    if (crossEquipments.count { it.rarity == Rarity.RELIC } > 1) {
        crossEquipments =
            crossEquipments.replaceRandomlyOneItemWithRarity(
                rarity = Rarity.RELIC,
                replacementResearchZone = parent1.equipments + parent2.equipments
            )
    }
    if (crossEquipments.count { it.rarity == Rarity.EPIC } > 1) {
        crossEquipments =
            crossEquipments.replaceRandomlyOneItemWithRarity(
                rarity = Rarity.EPIC,
                replacementResearchZone = parent1.equipments + parent2.equipments
            )
    }

    return BuildCombination(
        crossEquipments,
        if (Random.nextBoolean()) parent1.characterSkills else parent2.characterSkills
    )
}
