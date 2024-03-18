package me.chosante.common.skills

import me.chosante.common.Characteristic
import me.chosante.common.skills.IntelligenceCharacteristic.HealReceivedPercentage
import me.chosante.common.skills.IntelligenceCharacteristic.HpPercentage
import me.chosante.common.skills.IntelligenceCharacteristic.HpPercentageAsArmor
import me.chosante.common.skills.IntelligenceCharacteristic.Resistance
import me.chosante.common.skills.IntelligenceCharacteristic.Shield

data class Intelligence(
    override val maxPointsToAssign: Int,
    val hpPercentage: HpPercentage = HpPercentage(0),
    val resistance: Resistance = Resistance(
        0
    ),
    val shield: Shield = Shield(0),
    val healReceivedPercentage: HealReceivedPercentage = HealReceivedPercentage(
        0
    ),
    val hpPercentageAsArmor: HpPercentageAsArmor = HpPercentageAsArmor(
        0
    ),
) : Assignable<Intelligence> {

    init {
        check(pointsAssigned() in 0..maxPointsToAssign)
    }

    val allCharacteristicValues: CharacteristicValues
        get() = getAllCharacteristicValues(getCharacteristics())

    override fun getCharacteristics() = listOf(
        hpPercentage,
        resistance,
        shield,
        healReceivedPercentage,
        hpPercentageAsArmor
    )

    override fun pointsAssigned() =
        hpPercentage.pointsAssigned + resistance.pointsAssigned + shield.pointsAssigned + healReceivedPercentage.pointsAssigned + hpPercentageAsArmor.pointsAssigned
}

sealed class IntelligenceCharacteristic(
    pointsAssigned: Int,
    name: String,
    maxPointsAssignable: Int,
    unitValue: Int,
    characteristic: Characteristic?,
    unitType: UnitType,
) : SkillCharacteristic(pointsAssigned, name, maxPointsAssignable, unitValue, characteristic, unitType) {

    class HpPercentage(pointsAssigned: Int) :
        IntelligenceCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = Int.MAX_VALUE,
            unitValue = 4,
            unitType = UnitType.PERCENT,
            characteristic = Characteristic.HP,
            name = "% HP"
        )

    class Resistance(pointsAssigned: Int) :
        IntelligenceCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 10,
            unitValue = 10,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.RESISTANCE_ELEMENTARY,
            name = "Resistance Elementary"
        )

    class Shield(pointsAssigned: Int) :
        IntelligenceCharacteristic(
            pointsAssigned = pointsAssigned,
            unitValue = 1,
            maxPointsAssignable = 10,
            unitType = UnitType.FIXED,
            characteristic = null,
            name = "Shield"
        )

    class HealReceivedPercentage(pointsAssigned: Int) :
        IntelligenceCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 5,
            unitValue = 6,
            unitType = UnitType.PERCENT,
            characteristic = null,
            name = "% Heal Received"
        )

    class HpPercentageAsArmor(pointsAssigned: Int) :
        IntelligenceCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 10,
            unitValue = 4,
            unitType = UnitType.PERCENT,
            characteristic = null,
            name = "% HP as Armor"
        )
}
