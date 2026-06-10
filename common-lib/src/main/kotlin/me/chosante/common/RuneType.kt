package me.chosante.common

import kotlinx.serialization.Serializable

/**
 * Socket / rune colour. The numeric [code] matches Ankama's `shardsParameters.color`
 * (and a sublimation's `slotColorPattern`): 1 = red, 2 = green, 3 = blue. White sockets (0) are a
 * wildcard that matches any colour and are not a rune property, so they are not represented here.
 */
@Serializable
enum class RuneColor(
    val code: Int,
) {
    RED(1),
    GREEN(2),
    BLUE(3),
    ;

    companion object {
        fun fromCode(code: Int): RuneColor = entries.first { it.code == code }
    }
}

/**
 * A Wakfu rune ("éclat"): one stat you can socket into an item. There are 15 modeled runes (one per
 * supported [Characteristic]); Ankama's two single-target / area mastery runes (actionId 400) are not
 * modeled here because they have no [Characteristic] equivalent in the current engine.
 *
 * Values follow the **best-achievable / BiS** model (see docs/ENCHANTMENTS_PLAN.md and the
 * `autobuilder-optimistic-modeling` decision): the rune is always at the **max level** the character
 * can equip, and it **doubles** on its favoured equipment slots ([doubleBonusPosition]) exactly as
 * WakForge models it — which is also the optimum a committed player can actually reach (socket colours
 * are re-rollable, but a rune can only double on the slots whose native colour matches it).
 *
 * The per-level value tables are transcribed verbatim from WakForge's `useConstants.js`
 * (docs/ENCHANTMENTS_PLAN.md §6), because Ankama's transform from the raw `shardLevelingCurve` to the
 * displayed stat is undocumented and irregular.
 */
@Serializable
data class RuneType(
    val id: Int,
    val name: I18nText,
    val color: RuneColor,
    val characteristic: Characteristic,
    val doubleBonusPosition: List<Int>,
    val gfxId: Int,
) {
    /** Best (max) rune level usable at [characterLevel], 1..11, gated by [RUNE_LEVEL_REQUIREMENTS]. */
    fun maxLevel(characterLevel: Int): Int = RUNE_LEVEL_REQUIREMENTS.count { it <= characterLevel }.coerceIn(1, RUNE_LEVEL_REQUIREMENTS.size)

    /**
     * Flat value this rune contributes when socketed on an item of [itemType] for a character of
     * [characterLevel]: max-level base value, doubled when [itemType]'s slot raw id is one of the
     * rune's favoured [doubleBonusPosition] slots.
     */
    fun valueOn(
        itemType: ItemType,
        characterLevel: Int,
    ): Int {
        val base = baseValueTable()[maxLevel(characterLevel) - 1]
        val doubled = slotRawIds(itemType).any { it in doubleBonusPosition }
        return if (doubled) base * 2 else base
    }

    private fun baseValueTable(): List<Int> =
        when (characteristic) {
            Characteristic.MASTERY_ELEMENTARY -> RUNE_ELEMENTAL_MASTERY_LEVEL_VALUES
            Characteristic.MASTERY_MELEE,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_HEALING,
            -> RUNE_MASTERY_LEVEL_VALUES

            Characteristic.RESISTANCE_ELEMENTARY_FIRE,
            Characteristic.RESISTANCE_ELEMENTARY_WATER,
            Characteristic.RESISTANCE_ELEMENTARY_EARTH,
            Characteristic.RESISTANCE_ELEMENTARY_WIND,
            -> RUNE_RESISTANCE_LEVEL_VALUES

            Characteristic.LOCK, Characteristic.DODGE -> RUNE_DODGE_LOCK_LEVEL_VALUES
            Characteristic.INITIATIVE -> RUNE_INITIATIVE_LEVEL_VALUES
            Characteristic.HP -> RUNE_HEALTH_LEVEL_VALUES
            else -> error("No rune value table for characteristic $characteristic (rune $id)")
        }

    companion object {
        // Character level required to equip a rune of level 1..11. Transcribed from WakForge.
        val RUNE_LEVEL_REQUIREMENTS = listOf(0, 36, 51, 66, 81, 96, 126, 141, 171, 186, 216)

        // Per-rune-level value tables (index by level-1). WakForge's resistance table carries a 12th
        // entry (30) that its own `[level-1]` indexing never reaches (max level 11 -> 27); we keep the
        // 11 reachable values so doubled max resistance is 27*2 = 54, matching the game.
        val RUNE_MASTERY_LEVEL_VALUES = listOf(1, 3, 4, 6, 7, 10, 15, 19, 24, 30, 33)
        val RUNE_ELEMENTAL_MASTERY_LEVEL_VALUES = listOf(1, 2, 3, 4, 5, 7, 10, 13, 16, 20, 22)
        val RUNE_RESISTANCE_LEVEL_VALUES = listOf(2, 5, 7, 10, 12, 15, 17, 20, 22, 25, 27)
        val RUNE_DODGE_LOCK_LEVEL_VALUES = listOf(3, 6, 9, 12, 15, 21, 30, 39, 48, 60, 66)
        val RUNE_INITIATIVE_LEVEL_VALUES = listOf(2, 4, 6, 8, 10, 14, 20, 26, 32, 40, 44)
        val RUNE_HEALTH_LEVEL_VALUES = listOf(4, 8, 12, 16, 20, 28, 40, 52, 64, 80, 88)

        /**
         * Equipment-slot raw ids used by rune doubling (WakForge `ITEM_SLOT_DATA`). A rune doubles when
         * placed on a slot whose raw id is in its [doubleBonusPosition]. Rings expose both ring raw ids
         * (7, 8); socketless slots (off-hand, emblem, pet, mount) return empty and never double.
         */
        fun slotRawIds(itemType: ItemType): List<Int> =
            when (itemType) {
                ItemType.HELMET -> listOf(0)
                ItemType.CHEST_PLATE -> listOf(5)
                ItemType.SHOULDER_PADS -> listOf(3)
                ItemType.BOOTS -> listOf(12)
                ItemType.AMULET -> listOf(4)
                ItemType.CAPE -> listOf(13)
                ItemType.BELT -> listOf(10)
                ItemType.ONE_HANDED_WEAPONS, ItemType.TWO_HANDED_WEAPONS -> listOf(15)
                ItemType.RING -> listOf(7, 8)
                ItemType.OFF_HAND_WEAPONS, ItemType.EMBLEM, ItemType.PETS, ItemType.MOUNTS -> emptyList()
            }
    }
}
