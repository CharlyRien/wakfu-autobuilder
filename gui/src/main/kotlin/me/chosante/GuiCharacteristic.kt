package me.chosante

import me.chosante.common.Characteristic

enum class GuiCharacteristic(val characteristic: Characteristic, val guiName: String) {
    MASTERY_ELEMENTARY(Characteristic.MASTERY_ELEMENTARY, "Mastery Elementary"),
    MASTERY_ELEMENTARY_WATER(Characteristic.MASTERY_ELEMENTARY_WATER, "Mastery Elementary Water"),
    MASTERY_ELEMENTARY_WIND(Characteristic.MASTERY_ELEMENTARY_WIND, "Mastery Elementary Wind"),
    MASTERY_ELEMENTARY_FIRE(Characteristic.MASTERY_ELEMENTARY_FIRE, "Mastery Elementary Fire"),
    MASTERY_ELEMENTARY_EARTH(Characteristic.MASTERY_ELEMENTARY_EARTH, "Mastery Elementary Earth"),
    MASTERY_DISTANCE(Characteristic.MASTERY_DISTANCE, "Mastery Distance"),
    MASTERY_CRITICAL(Characteristic.MASTERY_CRITICAL, "Mastery Critical"),
    MASTERY_BACK(Characteristic.MASTERY_BACK, "Mastery Back"),
    MASTERY_MELEE(Characteristic.MASTERY_MELEE, "Mastery Melee"),
    MASTERY_BERSERK(Characteristic.MASTERY_BERSERK, "Mastery Berserk"),
    MASTERY_HEALING(Characteristic.MASTERY_HEALING, "Mastery Healing"),
    RESISTANCE_CRITICAL(Characteristic.RESISTANCE_CRITICAL, "Resistance Critical"),
    RESISTANCE_BACK(Characteristic.RESISTANCE_BACK, "Resistance Back"),
    RESISTANCE_ELEMENTARY(Characteristic.RESISTANCE_ELEMENTARY, "Resistance Elementary"),
    RESISTANCE_ELEMENTARY_EARTH(Characteristic.RESISTANCE_ELEMENTARY_EARTH, "Resistance Elementary Earth"),
    RESISTANCE_ELEMENTARY_FIRE(Characteristic.RESISTANCE_ELEMENTARY_FIRE, "Resistance Elementary Fire"),
    RESISTANCE_ELEMENTARY_WATER(Characteristic.RESISTANCE_ELEMENTARY_WATER, "Resistance Elementary Water"),
    RESISTANCE_ELEMENTARY_WIND(Characteristic.RESISTANCE_ELEMENTARY_WIND, "Resistance Elementary Wind"),
    HP(Characteristic.HP, "HP"),
    CRITICAL_HIT(Characteristic.CRITICAL_HIT, "Critical Hit %"),
    WAKFU_POINT(Characteristic.WAKFU_POINT, "Wakfu Point"),
    ACTION_POINT(Characteristic.ACTION_POINT, "Action Point"),
    RANGE(Characteristic.RANGE, "Range"),
    MOVEMENT_POINT(Characteristic.MOVEMENT_POINT, "Movement Point"),
    CONTROL(Characteristic.CONTROL, "Control"),
    DODGE(Characteristic.DODGE, "Dodge"),
    LOCK(Characteristic.LOCK, "Lock"),
    INITIATIVE(Characteristic.INITIATIVE, "Initiative"),
    BLOCK_PERCENTAGE(Characteristic.BLOCK_PERCENTAGE, "Block %"),
    WILLPOWER(Characteristic.WILLPOWER, "Willpower"),
    ;

    companion object {
        fun from(characteristic: Characteristic) = entries.firstOrNull { it.characteristic == characteristic }
    }
}
