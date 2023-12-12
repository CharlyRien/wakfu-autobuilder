package me.chosante.autobuilder.domain

import me.chosante.autobuilder.domain.skills.CharacterSkills
import me.chosante.common.Characteristic
import me.chosante.common.Characteristic.ACTION_POINT
import me.chosante.common.Characteristic.CONTROL
import me.chosante.common.Characteristic.CRITICAL_HIT
import me.chosante.common.Characteristic.HP
import me.chosante.common.Characteristic.MOVEMENT_POINT
import me.chosante.common.Characteristic.WAKFU_POINT

enum class CharacterClass {
    FECA,
    OSAMODAS,
    ENUTROF,
    SRAM,
    XELOR,
    ECAFLIP,
    ENIRIPSA,
    IOP,
    CRA,
    SADIDA,
    SACRIEUR,
    PANDAWA,
    ROUBLARD,
    ZOBAL,
    OUGINAK,
    STEAMER,
    ELIOTROPE,
    HUPPERMAGE,

    UNKNOWN,

    ;

    companion object {
        fun fromValue(characterName: String): CharacterClass {
            return entries.find { it.name == characterName.uppercase() } ?: UNKNOWN
        }
    }
}

data class Character(
    val clazz: CharacterClass,
    val level: Int,
    val minLevel: Int,
    val characterSkills: CharacterSkills = CharacterSkills(level),
) {
    private val baseAP = CharacterStat(
        characteristic = ACTION_POINT,
        value = 6
    )
    private val baseMP = CharacterStat(
        characteristic = MOVEMENT_POINT,
        value = 3
    )
    private val baseWP = CharacterStat(
        characteristic = WAKFU_POINT,
        value = if (clazz == CharacterClass.XELOR) 12 else 6
    )
    private val baseHP = CharacterStat(
        characteristic = HP,
        value = 50 + (level * 10)
    )

    private val baseCriticalHit = CharacterStat(
        characteristic = CRITICAL_HIT,
        value = 3
    )

    private val baseControl = CharacterStat(
        characteristic = CONTROL,
        value = 1
    )

    val baseCharacteristicValues = setOf(
        baseAP,
        baseMP,
        baseWP,
        baseHP,
        baseCriticalHit,
        baseControl
    ).associate { it.characteristic to it.value }

    data class CharacterStat(
        val characteristic: Characteristic,
        val value: Int,
    )
}
