package me.chosante.autobuilder.domain.skills

import me.chosante.autobuilder.domain.skills.MajorCharacteristic.ActionPoint
import me.chosante.autobuilder.domain.skills.MajorCharacteristic.Control
import me.chosante.autobuilder.domain.skills.MajorCharacteristic.DamageInflicted
import me.chosante.autobuilder.domain.skills.MajorCharacteristic.MasteryElementaryWithControl
import me.chosante.autobuilder.domain.skills.MajorCharacteristic.MasteryElementaryWithMovementPoint
import me.chosante.autobuilder.domain.skills.MajorCharacteristic.MasteryElementaryWithRange
import me.chosante.autobuilder.domain.skills.MajorCharacteristic.MovementPoint
import me.chosante.autobuilder.domain.skills.MajorCharacteristic.Range
import me.chosante.autobuilder.domain.skills.MajorCharacteristic.Resistance
import me.chosante.autobuilder.domain.skills.MajorCharacteristic.WakfuPoints
import me.chosante.common.Characteristic

data class Major(
    override val maxPointsToAssign: Int,
    val actionPoint: ActionPoint = ActionPoint(0),
    val movementPointAndMasteryElementary: SkillCharacteristic.PairedCharacteristic = SkillCharacteristic.PairedCharacteristic(
        name = "Movement Point and damage",
        maxPointsAssignable = 1,
        first = MovementPoint(0),
        second = MasteryElementaryWithMovementPoint(0)
    ),
    val rangeAndMasteryElementary: SkillCharacteristic.PairedCharacteristic = SkillCharacteristic.PairedCharacteristic(
        name = "Range and damage",
        maxPointsAssignable = 1,
        first = Range(0),
        second = MasteryElementaryWithRange(0)
    ),
    val wakfuPoints: WakfuPoints = WakfuPoints(0),
    val controlAndMasteryElementary: SkillCharacteristic.PairedCharacteristic = SkillCharacteristic.PairedCharacteristic(
        name = "Control and damage",
        maxPointsAssignable = 1,
        first = Control(0),
        second = MasteryElementaryWithControl(0)
    ),
    val damageInflicted: DamageInflicted = DamageInflicted(0),
    val resistance: Resistance = Resistance(0),
) : Assignable<Major> {

    val allCharacteristicValues: CharacteristicValues
        get() = getAllCharacteristicValues(getCharacteristics())

    override fun getCharacteristics() = listOf(
        actionPoint,
        movementPointAndMasteryElementary,
        rangeAndMasteryElementary,
        wakfuPoints,
        controlAndMasteryElementary,
        damageInflicted,
        resistance
    )

    override fun pointsAssigned() =
        listOf(
            actionPoint,
            movementPointAndMasteryElementary,
            rangeAndMasteryElementary,
            controlAndMasteryElementary,
            wakfuPoints,
            damageInflicted,
            resistance
        ).sumOf { it.pointsAssigned }
}

sealed class MajorCharacteristic(
    name: String,
    pointsAssigned: Int,
    maxPointsAssignable: Int,
    unitValue: Int,
    characteristic: Characteristic?,
    unitType: UnitType,
) : SkillCharacteristic(pointsAssigned, name, maxPointsAssignable, unitValue, characteristic, unitType) {

    class ActionPoint(pointsAssigned: Int) :
        MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 1,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.ACTION_POINT,
            name = "Action Point"
        )

    class MovementPoint(pointsAssigned: Int) :
        MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 1,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MOVEMENT_POINT,
            name = ""
        )

    class MasteryElementaryWithMovementPoint(pointsAssigned: Int) :
        MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 20,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_ELEMENTARY,
            name = ""
        )

    class Range(pointsAssigned: Int) :
        MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 1,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.RANGE,
            name = ""
        )

    class MasteryElementaryWithRange(pointsAssigned: Int) :
        MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 40,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_ELEMENTARY,
            name = ""
        )

    class WakfuPoints(pointsAssigned: Int) :
        MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 2,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.WAKFU_POINT,
            name = "Wakfu Points"
        )

    class Control(pointsAssigned: Int) :
        MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 2,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.CONTROL,
            name = ""
        )

    class MasteryElementaryWithControl(pointsAssigned: Int) :
        MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 40,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_ELEMENTARY,
            name = ""
        )

    class DamageInflicted(pointsAssigned: Int) :
        MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 10,
            unitType = UnitType.PERCENT,
            characteristic = Characteristic.MASTERY_ELEMENTARY,
            name = "% Inflicted Damage"
        )

    class Resistance(pointsAssigned: Int) :
        MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 50,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.RESISTANCE_ELEMENTARY,
            name = "Resistance Elementary"
        )
}
