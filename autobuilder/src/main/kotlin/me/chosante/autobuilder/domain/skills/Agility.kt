package me.chosante.autobuilder.domain.skills

import me.chosante.common.Characteristic
import me.chosante.autobuilder.domain.skills.AgilityCharacteristic.Dodge
import me.chosante.autobuilder.domain.skills.AgilityCharacteristic.DodgeAndLock
import me.chosante.autobuilder.domain.skills.AgilityCharacteristic.Initiative
import me.chosante.autobuilder.domain.skills.AgilityCharacteristic.Lock
import me.chosante.autobuilder.domain.skills.AgilityCharacteristic.Willpower

data class Agility(
    override val maxPointsToAssign: Int,
    val lock: Lock = Lock(0),
    val dodge: Dodge = Dodge(0),
    val initiative: Initiative = Initiative(0),
    val dodgeAndLock: SkillCharacteristic.PairedCharacteristic = SkillCharacteristic.PairedCharacteristic(
        name = "Lock and Dodge",
        maxPointsAssignable = Int.MAX_VALUE,
        first = DodgeAndLock.Dodge(0),
        second = DodgeAndLock.Lock(0)
    ),
    val willpower: Willpower = Willpower(0)
) : Assignable<Agility> {
    init {
        val sumOfAssignedPoints =
            lock.pointsAssigned + dodge.pointsAssigned + initiative.pointsAssigned + dodgeAndLock.first.pointsAssigned + willpower.pointsAssigned
        check(sumOfAssignedPoints in 0..maxPointsToAssign)
    }

    val allCharacteristicValues: CharacteristicValues
        get() = getAllCharacteristicValues(getCharacteristics())

    override fun getCharacteristics(): List<SkillCharacteristic> {
        return listOf(
            lock,
            dodge,
            initiative,
            dodgeAndLock,
            willpower
        )
    }

    override fun pointsAssigned(): Int {
        return listOf(
            lock,
            dodge,
            initiative,
            dodgeAndLock,
            willpower
        ).sumOf { it.pointsAssigned }
    }

}

sealed class AgilityCharacteristic(
    name: String,
    pointsAssigned: Int,
    maxPointsAssignable: Int,
    unitValue: Int,
    characteristic: Characteristic?,
    unitType: UnitType
) : SkillCharacteristic(pointsAssigned, name, maxPointsAssignable, unitValue, characteristic, unitType) {

    class Lock(pointsAssigned: Int) :
        AgilityCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = Int.MAX_VALUE,
            unitValue = 6,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.LOCK,
            name = "Lock"
        )

    class Dodge(pointsAssigned: Int) :
        AgilityCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = Int.MAX_VALUE,
            unitValue = 6,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.DODGE,
            name = "Dodge"
        )

    class Initiative(pointsAssigned: Int) :
        AgilityCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 20,
            unitValue = 4,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.INITIATIVE,
            name = "Initiative"
        )

    class DodgeAndLock {
        class Dodge(pointsAssigned: Int) :
            AgilityCharacteristic(
                pointsAssigned = pointsAssigned,
                maxPointsAssignable = Int.MAX_VALUE,
                unitValue = 4,
                unitType = UnitType.FIXED,
                characteristic = Characteristic.DODGE,
                name = ""
            )

        class Lock(pointsAssigned: Int) : AgilityCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = Int.MAX_VALUE,
            unitValue = 4,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.LOCK,
            name = ""
        )
    }

    class Willpower(pointsAssigned: Int) :
        AgilityCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 20,
            unitValue = 1,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.WILLPOWER,
            name = "Willpower"
        )

}