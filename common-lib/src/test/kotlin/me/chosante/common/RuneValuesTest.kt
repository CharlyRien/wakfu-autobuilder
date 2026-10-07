package me.chosante.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuneValuesTest {
    @Test
    fun `official values preserve all fifteen runes at eleven levels normal and doubled`() {
        val families =
            listOf(
                setOf(Characteristic.MASTERY_ELEMENTARY) to listOf(1, 2, 3, 4, 5, 7, 10, 13, 16, 20, 22),
                setOf(
                    Characteristic.MASTERY_MELEE,
                    Characteristic.MASTERY_DISTANCE,
                    Characteristic.MASTERY_BERSERK,
                    Characteristic.MASTERY_CRITICAL,
                    Characteristic.MASTERY_BACK,
                    Characteristic.MASTERY_HEALING
                ) to listOf(1, 3, 4, 6, 7, 10, 15, 19, 24, 30, 33),
                setOf(
                    Characteristic.RESISTANCE_ELEMENTARY_FIRE,
                    Characteristic.RESISTANCE_ELEMENTARY_WATER,
                    Characteristic.RESISTANCE_ELEMENTARY_EARTH,
                    Characteristic.RESISTANCE_ELEMENTARY_WIND
                ) to listOf(2, 5, 7, 10, 12, 15, 17, 20, 22, 25, 27),
                setOf(Characteristic.LOCK, Characteristic.DODGE) to listOf(3, 6, 9, 12, 15, 21, 30, 39, 48, 60, 66),
                setOf(Characteristic.INITIATIVE) to listOf(2, 4, 6, 8, 10, 14, 20, 26, 32, 40, 44),
                setOf(Characteristic.HP) to listOf(4, 8, 12, 16, 20, 28, 40, 52, 64, 80, 88)
            )
        assertEquals(RuneType.VALUED_CHARACTERISTICS, families.flatMap { it.first }.toSet())
        for ((stats, table) in families) {
            for (stat in stats) {
                assertEquals(table, RuneValues.embedded.byCharacteristic.getValue(stat), stat.name)
                val rune = RuneType(1, I18nText("", "", "", ""), RuneColor.RED, stat, listOf(0), 0)
                for ((index, itemLevel) in RuneType.RUNE_LEVEL_REQUIREMENTS.withIndex()) {
                    assertEquals(table[index], rune.valueOn(ItemType.CHEST_PLATE, itemLevel), "$stat level ${index + 1}")
                    assertEquals(2 * table[index], rune.valueOn(ItemType.HELMET, itemLevel), "$stat level ${index + 1} doubled")
                }
            }
        }
    }
}
