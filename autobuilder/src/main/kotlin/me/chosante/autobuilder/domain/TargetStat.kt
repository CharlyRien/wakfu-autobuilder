package me.chosante.autobuilder.domain

import me.chosante.common.Characteristic

class TargetStat(
    val characteristic: Characteristic,
    val target: Int,
    val userDefinedWeight: Int = 1
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TargetStat

        if (characteristic != other.characteristic) return false
        if (target != other.target) return false
        if (userDefinedWeight != other.userDefinedWeight) return false
        return true
    }

    override fun hashCode(): Int {
        var result = characteristic.hashCode()
        result = 31 * result + target
        result = 31 * result + userDefinedWeight
        return result
    }


}