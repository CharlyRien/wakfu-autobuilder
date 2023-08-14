package me.chosante.autobuilder.domain.skills

import me.chosante.common.Characteristic
import me.chosante.autobuilder.domain.skills.StrengthCharacteristic.Hp
import me.chosante.autobuilder.domain.skills.StrengthCharacteristic.MasteryDistance
import me.chosante.autobuilder.domain.skills.StrengthCharacteristic.MasteryElementary
import me.chosante.autobuilder.domain.skills.StrengthCharacteristic.MasteryMelee
import me.chosante.autobuilder.domain.skills.StrengthCharacteristic.MasterySingleTarget
import me.chosante.autobuilder.domain.skills.StrengthCharacteristic.MasteryZone

data class Strength(
    override val maxPointsToAssign: Int,
    val masteryElementary: MasteryElementary = MasteryElementary(0),
    val masterySingleTarget: MasterySingleTarget = MasterySingleTarget(0),
    val masteryZone: MasteryZone = MasteryZone(0),
    val masteryMelee: MasteryMelee = MasteryMelee(0),
    val masteryDistance: MasteryDistance = MasteryDistance(0),
    val hp: Hp = Hp(0)
) : Assignable<Strength> {
    init {
        check(pointsAssigned() in 0..maxPointsToAssign)
    }

    val allCharacteristicValues: CharacteristicValues
        get() = getAllCharacteristicValues(getCharacteristics())

    override fun getCharacteristics(): List<SkillCharacteristic> {
        return listOf(
            masteryElementary,
            masteryZone,
            masteryMelee,
            masteryDistance,
            masterySingleTarget,
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
    unitType: UnitType
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

    class MasterySingleTarget(pointsAssigned: Int) :
        StrengthCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 20,
            unitValue = 8,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_SINGLE_TARGET,
            name = "Mastery Single Target"
        )

    class MasteryDistance(pointsAssigned: Int) :
        StrengthCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 20,
            unitValue = 8,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_DISTANCE,
            name = "Mastery Distance"
        )

    class MasteryZone(pointsAssigned: Int) :
        StrengthCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 20,
            unitValue = 8,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_ZONE,
            name = "Mastery Zone"
        )

    class MasteryMelee(pointsAssigned: Int) :
        StrengthCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 20,
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