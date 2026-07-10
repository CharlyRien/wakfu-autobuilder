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
 * `autobuilder-optimistic-modeling` decision): the rune is always at the **max level the carrier
 * item's level allows** (see [maxLevel]), and it **doubles** on its favoured equipment slots
 * ([doubleBonusPosition]) exactly as
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
    /**
     * Best (max) enchantment level reachable on an item of [itemLevel], 1..11, gated by
     * [RUNE_LEVEL_REQUIREMENTS]. The enchantment-level cap is a property of the **item's level** (an
     * item of level 50 caps at level 2, since level 3 requires an item of level >= 51) — *not* the
     * character's level. Socketing a rune above this cap is not possible in-game, so the engine and
     * the Zenith export both feed the carrier item's level here.
     */
    fun maxLevel(itemLevel: Int): Int = RUNE_LEVEL_REQUIREMENTS.count { it <= itemLevel }.coerceIn(1, RUNE_LEVEL_REQUIREMENTS.size)

    /**
     * Flat value this rune contributes when socketed on an item of [itemType] and [itemLevel]:
     * max-level base value (capped by [itemLevel] via [maxLevel]), doubled when [itemType]'s slot
     * raw id is one of the rune's favoured [doubleBonusPosition] slots.
     */
    fun valueOn(
        itemType: ItemType,
        itemLevel: Int,
    ): Int {
        val base = baseValueTable()[maxLevel(itemLevel) - 1]
        return if (isDoubledOn(itemType)) base * 2 else base
    }

    fun isDoubledOn(itemType: ItemType): Boolean = slotRawIds(itemType).any { it in doubleBonusPosition }

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
        // Minimum *item* level required for an enchantment of level 1..11 (Ankama's official table:
        // lvl 1 -> item 1, lvl 2 -> 36, lvl 3 -> 51, 4 -> 66, 5 -> 81, 6 -> 96, 7 -> 126, 8 -> 141,
        // 9 -> 171, 10 -> 186, 11 -> 216). The cap follows the item's level, not the character's.
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
