package me.chosante.common.skills

import me.chosante.common.Characteristic
import me.chosante.common.skills.LuckCharacteristic.Block
import me.chosante.common.skills.LuckCharacteristic.CriticalHit
import me.chosante.common.skills.LuckCharacteristic.MasteryBack
import me.chosante.common.skills.LuckCharacteristic.MasteryBerserk
import me.chosante.common.skills.LuckCharacteristic.MasteryCritical
import me.chosante.common.skills.LuckCharacteristic.MasteryHealing
import me.chosante.common.skills.LuckCharacteristic.ResistanceBack
import me.chosante.common.skills.LuckCharacteristic.ResistanceCritical

data class Luck(
    override val maxPointsToAssign: Int,
    val criticalHit: CriticalHit = CriticalHit(0),
    val block: Block = Block(0),
    val masteryCritical: MasteryCritical = MasteryCritical(0),
    val masteryBack: MasteryBack = MasteryBack(0),
    val masteryBerserk: MasteryBerserk = MasteryBerserk(0),
    val masteryHealing: MasteryHealing = MasteryHealing(0),
    val resistanceBack: ResistanceBack = ResistanceBack(0),
    val resistanceCritical: ResistanceCritical = ResistanceCritical(0),
) : Assignable<Luck> {

    val allCharacteristicValues: CharacteristicValues
        get() = getAllCharacteristicValues(getCharacteristics())

    override fun getCharacteristics(): List<SkillCharacteristic> {
        return listOf(
            block,
            criticalHit,
            masteryBack,
            masteryBerserk,
            masteryCritical,
            masteryHealing,
            resistanceCritical,
            resistanceBack
        )
    }

    override fun pointsAssigned() = getCharacteristics().sumOf { it.pointsAssigned }
}

sealed class LuckCharacteristic(
    pointsAssigned: Int,
    name: String,
    maxPointsAssignable: Int,
    unitValue: Int,
    characteristic: Characteristic?,
    unitType: UnitType,
) : SkillCharacteristic(pointsAssigned, name, maxPointsAssignable, unitValue, characteristic, unitType) {
    class CriticalHit(pointsAssigned: Int) :
        LuckCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 20,
            unitValue = 1,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.CRITICAL_HIT,
            name = "% Critical Hit"
        )

    class Block(pointsAssigned: Int) :
        LuckCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 20,
            unitValue = 1,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.BLOCK_PERCENTAGE,
            name = "% Block"
        )

    class MasteryCritical(pointsAssigned: Int) :
        LuckCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = Int.MAX_VALUE,
            unitValue = 4,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_CRITICAL,
            name = "Mastery Critical"
        )

    class MasteryBack(pointsAssigned: Int) :
        LuckCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = Int.MAX_VALUE,
            unitValue = 6,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_BACK,
            name = "Mastery Back"
        )

    class MasteryBerserk(pointsAssigned: Int) :
        LuckCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = Int.MAX_VALUE,
            unitValue = 8,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_BERSERK,
            name = "Mastery Berserk"
        )

    class MasteryHealing(pointsAssigned: Int) :
        LuckCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = Int.MAX_VALUE,
            unitValue = 6,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.MASTERY_HEALING,
            name = "Mastery Healing"
        )

    class ResistanceBack(pointsAssigned: Int) :
        LuckCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 20,
            unitValue = 4,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.RESISTANCE_BACK,
            name = "Resistance Back"
        )

    class ResistanceCritical(pointsAssigned: Int) :
        LuckCharacteristic(
            pointsAssigned = pointsAssigned,
            maxPointsAssignable = 20,
            unitValue = 4,
            unitType = UnitType.FIXED,
            characteristic = Characteristic.RESISTANCE_CRITICAL,
            name = "Resistance Critical"
        )
}
