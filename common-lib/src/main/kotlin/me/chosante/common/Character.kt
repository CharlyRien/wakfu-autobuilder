package me.chosante.common

import me.chosante.common.Characteristic.ACTION_POINT
import me.chosante.common.Characteristic.CONTROL
import me.chosante.common.Characteristic.CRITICAL_HIT
import me.chosante.common.Characteristic.HP
import me.chosante.common.Characteristic.MOVEMENT_POINT
import me.chosante.common.Characteristic.WAKFU_POINT
import me.chosante.common.skills.CharacterSkills

/**
 * The 18 Wakfu classes (+ [UNKNOWN]). [breedId] is Ankama's numeric "breed" id — the key used to
 * resolve class artwork (icon / T-pose illustration / background) from the community asset set, and
 * the same id the encyclopedia/Zenith use. There is no breed 17; [UNKNOWN] has no art (0).
 */
enum class CharacterClass(
    val breedId: Int,
) {
    FECA(1),
    OSAMODAS(2),
    ENUTROF(3),
    SRAM(4),
    XELOR(5),
    ECAFLIP(6),
    ENIRIPSA(7),
    IOP(8),
    CRA(9),
    SADIDA(10),
    SACRIEUR(11),
    PANDAWA(12),
    ROUBLARD(13),
    ZOBAL(14),
    OUGINAK(15),
    STEAMER(16),
    ELIOTROPE(18),
    HUPPERMAGE(19),

    UNKNOWN(0),

    ;

    companion object {
        fun fromValue(characterName: String): CharacterClass = entries.find { it.name == characterName.uppercase() } ?: UNKNOWN
    }
}

data class Character(
    val clazz: CharacterClass,
    val level: Int,
    val minLevel: Int,
    val characterSkills: CharacterSkills = CharacterSkills(level),
) {
    private val baseAP =
        CharacterStat(
            characteristic = ACTION_POINT,
            value = 6
        )
    private val baseMP =
        CharacterStat(
            characteristic = MOVEMENT_POINT,
            value = 3
        )
    private val baseWP =
        CharacterStat(
            characteristic = WAKFU_POINT,
            value = if (clazz == CharacterClass.XELOR) 12 else 6
        )
    private val baseHP =
        CharacterStat(
            characteristic = HP,
            value = 50 + (level * 10)
        )

    private val baseCriticalHit =
        CharacterStat(
            characteristic = CRITICAL_HIT,
            value = 3
        )

    private val baseControl =
        CharacterStat(
            characteristic = CONTROL,
            value = 1
        )

    val baseCharacteristicValues =
        setOf(
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
