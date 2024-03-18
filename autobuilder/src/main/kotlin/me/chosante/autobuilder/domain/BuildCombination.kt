package me.chosante.autobuilder.domain

import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills

data class BuildCombination(val equipments: List<Equipment>, val characterSkills: CharacterSkills) {
    fun isValid(): Boolean {
        val numberOfEquipmentByType = equipments.groupingBy { it.itemType }.eachCount()
        if (numberOfEquipmentByType.any { (key, count) ->
            count > 1 && key != ItemType.RING || count > 2
        }
        ) {
            return false
        }

        val numberOfEquipmentByRarity = equipments.groupingBy { it.rarity }.eachCount()
        val numberOfEpicItems = numberOfEquipmentByRarity[Rarity.EPIC] ?: 0
        val numberOfRelicItems = numberOfEquipmentByRarity[Rarity.RELIC] ?: 0
        if (numberOfRelicItems >= 2) {
            return false
        }

        if (numberOfEpicItems >= 2) {
            return false
        }
        val numberOfTwoHandsWeapon = numberOfEquipmentByType[ItemType.TWO_HANDED_WEAPONS] ?: 0
        val numberOfOneHandsWeapon = numberOfEquipmentByType[ItemType.ONE_HANDED_WEAPONS] ?: 0
        val numberOfSecondHandsWeapon = numberOfEquipmentByType[ItemType.OFF_HAND_WEAPONS] ?: 0

        // same ring
        if (equipments.filter { it.itemType == ItemType.RING }.distinctBy { it.name }.count() == 1) {
            return false
        }

        if (numberOfTwoHandsWeapon + numberOfOneHandsWeapon > 1) {
            return false
        }

        return numberOfTwoHandsWeapon + numberOfSecondHandsWeapon <= 1
    }
}
