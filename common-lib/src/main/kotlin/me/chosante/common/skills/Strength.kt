package me.chosante.common.skills

import me.chosante.common.Characteristic
import me.chosante.common.skills.StrengthCharacteristic.Hp
import me.chosante.common.skills.StrengthCharacteristic.MasteryDistance
import me.chosante.common.skills.StrengthCharacteristic.MasteryElementary
import me.chosante.common.skills.StrengthCharacteristic.MasteryMelee

data class Strength(
    override val maxPointsToAssign: Int,
    val masteryElementary: MasteryElementary = MasteryElementary(0),
    val masteryMelee: MasteryMelee = MasteryMelee(0),
    val masteryDistance: MasteryDistance = MasteryDistance(0),
    val hp: Hp = Hp(0),
) : Assignable<Strength> {
    init {
        check(pointsAssigned() in 0..maxPointsToAssign)
    }

    val allCharacteristicValues: CharacteristicValues
        get() = getAllCharacteristicValues(getCharacteristics())

    override fun getCharacteristics(): List<SkillCharacteristic> {
        return listOf(
            masteryElementary,
            masteryMelee,
            masteryDistance,
            hp
        )
    }

    override fun pointsAssigned() = getCharacteristics().sumOf { it.pointsAssigned }
}

sealed class StrengthCharacteristic(
    pointsAssigned: Int,
    name: String,
    maxPointsAssignable: Int,
    unitValue: Int,
    characteristic: Characteristic?,
    unitType: UnitType,
) : SkillCharacteristic(pointsAssigned, name, maxPointsAssignable, unitValue, characteristic, unitType) {

    class MasteryElementary(pointsAssigned: Int) :
        StrengthCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = Int.MAX_VALUE,
            unitValue = 5,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_ELEMENTARY,
            name = "Mastery Elementary"
        )

    class MasteryDistance(pointsAssigned: Int) :
        StrengthCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 40,
            unitValue = 8,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_DISTANCE,
            name = "Mastery Distance"
        )

    class MasteryMelee(pointsAssigned: Int) :
        StrengthCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 40,
            unitValue = 8,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_MELEE,
            name = "Mastery Melee"
        )

    class Hp(pointsAssigned: Int) :
        StrengthCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = Int.MAX_VALUE,
            unitValue = 20,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.HP,
            name = "HP"
        )
}
