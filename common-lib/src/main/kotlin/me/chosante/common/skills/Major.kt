package me.chosante.common.skills

import me.chosante.common.Characteristic
import me.chosante.common.skills.MajorCharacteristic.ActionPoint
import me.chosante.common.skills.MajorCharacteristic.ControlWithMasteryElementary
import me.chosante.common.skills.MajorCharacteristic.DamageInflicted
import me.chosante.common.skills.MajorCharacteristic.MovementPointWithMasteryElementary
import me.chosante.common.skills.MajorCharacteristic.RangeWithMasteryElementary
import me.chosante.common.skills.MajorCharacteristic.Resistance
import me.chosante.common.skills.MajorCharacteristic.WakfuPoints

data class Major(
    override val maxPointsToAssign: Int,
    val actionPoint: ActionPoint = ActionPoint(0),
    val movementPointAndMasteryElementary: MovementPointWithMasteryElementary = MovementPointWithMasteryElementary(0),
    val rangeAndMasteryElementary: RangeWithMasteryElementary = RangeWithMasteryElementary(0),
    val wakfuPoints: WakfuPoints = WakfuPoints(0),
    val controlAndMasteryElementary: ControlWithMasteryElementary = ControlWithMasteryElementary(0),
    val damageInflicted: DamageInflicted = DamageInflicted(0),
    val resistance: Resistance = Resistance(0),
) : Assignable<Major> {
    val allCharacteristicValues: CharacteristicValues
        get() = getAllCharacteristicValues(getCharacteristics())

    override fun getCharacteristics() =
        listOf(
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
    class ActionPoint(
        pointsAssigned: Int,
    ) : MajorCharacteristic(
        pointsAssigned = pointsAssigned,
        maxPointsAssignable = 1,
        unitValue = 1,
        unitType = UnitType.FIXED,
        characteristic = Characteristic.ACTION_POINT,
        name = "Action Point"
    )

    class MovementPointWithMasteryElementary(
        pointsAssigned: Int,
    ) : PairedCharacteristic(
        name = "Movement Point and damage",
        first = MovementPoint(pointsAssigned),
        second = MasteryElementary(pointsAssigned),
        maxPointsAssignable = 1
    ) {
        private class MovementPoint(
            pointsAssigned: Int,
        ) : MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 1,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MOVEMENT_POINT,
            name = ""
        )

        private class MasteryElementary(
            pointsAssigned: Int,
        ) : MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 20,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_ELEMENTARY,
            name = ""
        )
    }

    class RangeWithMasteryElementary(
        pointsAssigned: Int,
    ) : PairedCharacteristic(
        name = "Range and damage",
        maxPointsAssignable = 1,
        first = Range(pointsAssigned),
        second = MasteryElementary(pointsAssigned)
    ) {
        private class Range(
            pointsAssigned: Int,
        ) : MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 1,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.RANGE,
            name = ""
        )

        private class MasteryElementary(
            pointsAssigned: Int,
        ) : MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 40,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_ELEMENTARY,
            name = ""
        )
    }

    class WakfuPoints(
        pointsAssigned: Int,
    ) : MajorCharacteristic(
        pointsAssigned = pointsAssigned,
        maxPointsAssignable = 1,
        unitValue = 2,
        unitType = UnitType.FIXED,
        characteristic = Characteristic.WAKFU_POINT,
        name = "Wakfu Points"
    )

    class ControlWithMasteryElementary(
        pointsAssigned: Int,
    ) : PairedCharacteristic(
        name = "Control and damage",
        maxPointsAssignable = 1,
        first = Control(pointsAssigned),
        second = MasteryElementary(pointsAssigned)
    ) {
        private class Control(
            pointsAssigned: Int,
        ) : MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 2,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.CONTROL,
            name = ""
        )

        private class MasteryElementary(
            pointsAssigned: Int,
        ) : MajorCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 1,
            unitValue = 40,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_ELEMENTARY,
            name = ""
        )
    }

    class DamageInflicted(
        pointsAssigned: Int,
    ) : MajorCharacteristic(
        pointsAssigned = pointsAssigned,
        maxPointsAssignable = 1,
        unitValue = 10,
        unitType = UnitType.PERCENT,
        characteristic = Characteristic.MASTERY_ELEMENTARY,
        name = "% Inflicted Damage"
    )

    class Resistance(
        pointsAssigned: Int,
    ) : MajorCharacteristic(
        pointsAssigned = pointsAssigned,
        maxPointsAssignable = 1,
        unitValue = 50,
        unitType = UnitType.FIXED,
        characteristic = Characteristic.RESISTANCE_ELEMENTARY,
        name = "Resistance Elementary"
    )
}
