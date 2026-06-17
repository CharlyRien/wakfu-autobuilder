package me.chosante.autobuilder.domain

import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Passive
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills

data class BuildCombination(
    val equipments: List<Equipment>,
    val characterSkills: CharacterSkills,
    // Runes socketed per equipped item (best-achievable model). Empty when runes are disabled.
    val runes: Map<Equipment, List<RuneType>> = emptyMap(),
    // Sublimations chosen/forced, keyed by their CARRIER item (epic → the epic item, relic → the relic
    // item, normal → its assigned ≥3-socket item). Mirrors [runes] so the GUI can show each item's
    // sublimation + rune colour pattern. Flatten with `.values.flatten()` for the effect set.
    val sublimations: Map<Equipment, List<Sublimation>> = emptyMap(),
    // The player's selected passive loadout (≤ the level's passive slots). Carried for display + Zenith
    // export; their fully-declarative flat stats are also folded into the solve (see PassiveCatalog).
    val passives: List<Passive> = emptyList(),
) {
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

        val rings = equipments.filter { it.itemType == ItemType.RING }
        if (rings.size > 1 && rings.distinctBy { it.name }.count() == 1) {
            return false
        }

        if (numberOfTwoHandsWeapon + numberOfOneHandsWeapon > 1) {
            return false
        }

        if (numberOfTwoHandsWeapon + numberOfSecondHandsWeapon > 1) {
            return false
        }

        return hasLegalSublimations()
    }

    /**
     * Sublimation legality, mirroring the solver constraints: at most 10 sublimations, at most 1 epic and
     * 1 relic, and each one on a valid carrier item — epic on an epic item, relic on a relic item, a normal
     * sub on a ≥3-socket item with at most one normal sub per item, and `runes + 3·normalSubs ≤ sockets` so
     * a sub's three sockets aren't double-booked with runes.
     */
    private fun hasLegalSublimations(): Boolean {
        val allSubs = sublimations.values.flatten()
        if (allSubs.size > 10) return false
        if (allSubs.count { it.rarity == SublimationRarity.EPIC } > 1) return false
        if (allSubs.count { it.rarity == SublimationRarity.RELIC } > 1) return false
        for ((carrier, hosted) in sublimations) {
            val normalSubCount = hosted.count { it.rarity == SublimationRarity.NORMAL }
            if (normalSubCount > 1) return false
            if (normalSubCount > 0 && carrier.maxShardSlots < 3) return false
            if (hosted.any { it.rarity == SublimationRarity.EPIC } && carrier.rarity != Rarity.EPIC) return false
            if (hosted.any { it.rarity == SublimationRarity.RELIC } && carrier.rarity != Rarity.RELIC) return false
            if ((runes[carrier]?.size ?: 0) + 3 * normalSubCount > carrier.maxShardSlots) return false
        }
        return true
    }
}
