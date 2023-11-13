package me.chosante.autobuilder.domain.skills

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random.Default.nextInt
import me.chosante.common.Characteristic

class CharacterSkills(
    val level: Int,
) {
    var strength: Strength
        private set
    var agility: Agility
        private set
    var luck: Luck
        private set
    var major: Major
        private set
    var intelligence: Intelligence
        private set

    init {
        val points = max(1, level - 1)
        val basePoints = points / 4
        val extraPoints = points % 4
        intelligence = Intelligence(basePoints + if (extraPoints >= 1) 1 else 0)
        strength = Strength(basePoints + if (extraPoints >= 2) 1 else 0)
        agility = Agility(basePoints + if (extraPoints >= 3) 1 else 0)
        luck = Luck(basePoints)
        major = Major(
            maxPointsToAssign = when {
                level >= 175 -> 4
                level >= 125 -> 3
                level >= 75 -> 2
                level >= 25 -> 1
                else -> 0
            }
        )
    }

    val allCharacteristic
        get() = major.getCharacteristics() + intelligence.getCharacteristics() + strength.getCharacteristics() + agility.getCharacteristics() + luck.getCharacteristics()

    val allCharacteristicValues: CharacteristicValues
        get() = major.allCharacteristicValues + intelligence.allCharacteristicValues + strength.allCharacteristicValues + agility.allCharacteristicValues +
            luck.allCharacteristicValues

    fun copy(
        strength: Strength = this.strength,
        agility: Agility = this.agility,
        luck: Luck = this.luck,
        major: Major = this.major,
        intelligence: Intelligence = this.intelligence,
    ): CharacterSkills {
        return CharacterSkills(
            level = this.level
        ).apply {
            this.strength = strength
            this.agility = agility
            this.luck = luck
            this.major = major
            this.intelligence = intelligence
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CharacterSkills

        if (level != other.level) return false
        if (strength != other.strength) return false
        if (agility != other.agility) return false
        if (luck != other.luck) return false
        if (major != other.major) return false
        if (intelligence != other.intelligence) return false

        return true
    }

    override fun hashCode(): Int {
        var result = level
        result = 31 * result + strength.hashCode()
        result = 31 * result + agility.hashCode()
        result = 31 * result + luck.hashCode()
        result = 31 * result + major.hashCode()
        result = 31 * result + intelligence.hashCode()
        return result
    }
}

enum class UnitType {
    FIXED, PERCENT
}

data class CharacteristicValues(
    val fixedValues: Map<Characteristic, Int>,
    val percentValues: Map<Characteristic, Int>,
) {
    operator fun plus(characteristicValues: CharacteristicValues): CharacteristicValues {
        val newFixedValues = fixedValues.toMutableMap()
        characteristicValues.fixedValues.forEach { (key, value) ->
            newFixedValues.merge(key, value) { oldValue, newValue -> oldValue + newValue }
        }

        val newPercentValues = percentValues.toMutableMap()
        characteristicValues.percentValues.forEach { (key, value) ->
            newPercentValues.merge(key, value) { oldValue, newValue -> oldValue + newValue }
        }

        return CharacteristicValues(
            fixedValues = newFixedValues,
            percentValues = newPercentValues
        )
    }
}

sealed class SkillCharacteristic(
    pointsAssigned: Int,
    val name: String,
    val maxPointsAssignable: Int,
    private val unitValue: Int,
    val characteristic: Characteristic?,
    val unitType: UnitType,
) {
    init {
        check(pointsAssigned in 0..maxPointsAssignable) {
            "Impossible to have more points assigned ($pointsAssigned)" +
                " than the max allowed ($maxPointsAssignable) to the characteristic: $this"
        }
    }

    open var pointsAssigned: Int = pointsAssigned
        protected set(value) {
            check(value in 0..maxPointsAssignable) {
                "Impossible to have more points assigned ($value)" +
                    " than the max allowed ($maxPointsAssignable) to the characteristic: $this"
            }
            field = value
        }

    open fun setPointAssigned(pointsToAssign: Int) {
        pointsAssigned = pointsToAssign
    }

    open class PairedCharacteristic(
        name: String,
        val first: SkillCharacteristic,
        val second: SkillCharacteristic,
        maxPointsAssignable: Int,
    ) : SkillCharacteristic(
        pointsAssigned = 0,
        maxPointsAssignable = maxPointsAssignable,
        unitValue = 0,
        characteristic = null,
        unitType = first.unitType,
        name = name
    ) {
        override fun setPointAssigned(pointsToAssign: Int) {
            pointsAssigned = pointsToAssign
            first.setPointAssigned(pointsToAssign)
            second.setPointAssigned(pointsToAssign)
        }
    }

    val value
        get() = unitValue * pointsAssigned
}

fun getAllCharacteristicValues(characteristics: List<SkillCharacteristic>): CharacteristicValues {
    val fixedCharacteristics = mutableMapOf<Characteristic, Int>()
    val percentCharacteristics = mutableMapOf<Characteristic, Int>()

    for (characteristic in characteristics) {
        addCharacteristicValue(characteristic, fixedCharacteristics, percentCharacteristics)
    }

    return CharacteristicValues(fixedCharacteristics, percentCharacteristics)
}

fun addCharacteristicValue(
    characteristic: SkillCharacteristic,
    fixedCharacteristics: MutableMap<Characteristic, Int>,
    percentCharacteristics: MutableMap<Characteristic, Int>,
) {
    if (characteristic is SkillCharacteristic.PairedCharacteristic) {
        addCharacteristicValue(characteristic.first, fixedCharacteristics, percentCharacteristics)
        addCharacteristicValue(characteristic.second, fixedCharacteristics, percentCharacteristics)
    }

    if (characteristic.characteristic != null) {
        when (characteristic.unitType) {
            UnitType.FIXED -> fixedCharacteristics[characteristic.characteristic] =
                fixedCharacteristics.getOrPut(characteristic.characteristic) { 0 } + characteristic.value

            UnitType.PERCENT -> percentCharacteristics[characteristic.characteristic] =
                percentCharacteristics.getOrPut(characteristic.characteristic) { 0 } + characteristic.value
        }
    }
}

interface Assignable<T> {
    val maxPointsToAssign: Int
    fun getCharacteristics(): List<SkillCharacteristic>
    fun pointsAssigned(): Int
}

fun <T : Assignable<T>> T.assignRandomPoints(pointsToAssign: Int, targetCharacteristics: List<Characteristic>): T {
    check(pointsToAssign in 0..maxPointsToAssign)
    val skillCharacteristics = filterOnlyWantedTargetCharacteristics(targetCharacteristics)
    this.getCharacteristics().forEach { it.setPointAssigned(0) }
    if (skillCharacteristics.isEmpty()) {
        return this
    }
    if (skillCharacteristics.size == 1) {
        val characteristic = skillCharacteristics.first()
        characteristic.setPointAssigned(min(pointsToAssign, characteristic.maxPointsAssignable))
        return this
    }
    var remainingPoints = pointsToAssign
    while (remainingPoints > 0) {
        if (skillCharacteristics.all { it.pointsAssigned == it.maxPointsAssignable }) {
            return this
        }
        skillCharacteristics
            .random()
            .let { characteristic ->
                val assignedPoints = assignPointsToCharacteristic(characteristic, remainingPoints)
                remainingPoints -= assignedPoints
            }
    }
    return this
}

private fun <T : Assignable<T>> T.filterOnlyWantedTargetCharacteristics(targetCharacteristics: List<Characteristic>): List<SkillCharacteristic> {
    return getCharacteristics().filter { skillCharacteristic ->
        when {
            skillCharacteristic is SkillCharacteristic.PairedCharacteristic ->
                skillCharacteristic.first.characteristic in targetCharacteristics ||
                    skillCharacteristic.second.characteristic in targetCharacteristics

            skillCharacteristic.characteristic == Characteristic.RESISTANCE_ELEMENTARY -> targetCharacteristics.any {
                it in resistanceElementaryCharacteristics
            }

            skillCharacteristic.characteristic == Characteristic.MASTERY_ELEMENTARY -> targetCharacteristics.any {
                it in masteryElementaryCharacteristics
            }

            else -> skillCharacteristic.characteristic in targetCharacteristics
        }
    }
}

private fun <T : Assignable<T>> T.assignPointsToCharacteristic(
    characteristic: SkillCharacteristic,
    points: Int,
): Int {
    val maxCharacteristicPointsAssignable =
        min(characteristic.maxPointsAssignable - characteristic.pointsAssigned, points)
    val maxCategoryPointsAssignable = maxPointsToAssign - pointsAssigned()
    return if (maxCharacteristicPointsAssignable > 0 && maxCategoryPointsAssignable > 0) {
        val maxPointsAssignable = min(maxCharacteristicPointsAssignable, maxCategoryPointsAssignable)
        val randomPointsToAssign = nextInt(1, maxPointsAssignable + 1)
        characteristic.setPointAssigned(characteristic.pointsAssigned + randomPointsToAssign)
        randomPointsToAssign
    } else {
        0
    }
}

private val masteryElementaryCharacteristics = listOf(
    Characteristic.MASTERY_ELEMENTARY_EARTH,
    Characteristic.MASTERY_ELEMENTARY_WATER,
    Characteristic.MASTERY_ELEMENTARY_WIND,
    Characteristic.MASTERY_ELEMENTARY_FIRE,
    Characteristic.MASTERY_ELEMENTARY
)

private val resistanceElementaryCharacteristics = listOf(
    Characteristic.RESISTANCE_ELEMENTARY_EARTH,
    Characteristic.RESISTANCE_ELEMENTARY_WATER,
    Characteristic.RESISTANCE_ELEMENTARY_WIND,
    Characteristic.RESISTANCE_ELEMENTARY_FIRE,
    Characteristic.RESISTANCE_ELEMENTARY
)
