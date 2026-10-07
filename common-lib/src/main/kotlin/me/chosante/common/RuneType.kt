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
 * ([doubleBonusPosition]) as the official client computes it — the optimum a committed player can reach (socket colours
 * are re-rollable, but a rune can only double on the slots whose native colour matches it).
 *
 * Per-level values come from CDN shard equip-effect formulas and the client StaticEffect level bands,
 * generated into [RuneValues]. The client floors each normal value before doubling; the extractor guards
 * that bytecode shape. `shardLevelingCurve` describes XP costs, not displayed stats.
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
    fun maxLevel(itemLevel: Int): Int = maxLevelForItemLevel(itemLevel)

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

    private fun baseValueTable(): List<Int> = RuneValues.embedded.byCharacteristic[characteristic] ?: error("No rune value table for characteristic $characteristic (rune $id)")

    companion object {
        // Minimum *item* level for an enchantment of level 1..11. The game data carries the same list
        // (CDN items.json shardsParameters.shardLevelRequirement, decoded into runes.json `levelRequirements`):
        // [RuneCatalogData.embedded] checks the two match on load, so a data drift fails loudly instead of silently
        // changing caps. Kept as a constant here so common-lib's consumers (zenith-builder) never need autobuilder's
        // resources. The cap follows the item's level, not the character's.
        val RUNE_LEVEL_REQUIREMENTS = listOf(0, 36, 51, 66, 81, 96, 126, 141, 171, 186, 216)

        /**
         * The enchantment-level cap (1..11) of an item of [itemLevel] — what [maxLevel] returns for every rune. Every
         * value table increases with it, so an item with a higher cap carries a better rune of each stat.
         */
        fun maxLevelForItemLevel(itemLevel: Int): Int = RUNE_LEVEL_REQUIREMENTS.count { it <= itemLevel }.coerceIn(1, RUNE_LEVEL_REQUIREMENTS.size)

        /** Every characteristic a rune can carry: exactly the ones [baseValueTable] has a value table for. */
        val VALUED_CHARACTERISTICS: Set<Characteristic> =
            setOf(
                Characteristic.MASTERY_ELEMENTARY,
                Characteristic.MASTERY_MELEE,
                Characteristic.MASTERY_DISTANCE,
                Characteristic.MASTERY_BERSERK,
                Characteristic.MASTERY_CRITICAL,
                Characteristic.MASTERY_BACK,
                Characteristic.MASTERY_HEALING,
                Characteristic.RESISTANCE_ELEMENTARY_FIRE,
                Characteristic.RESISTANCE_ELEMENTARY_WATER,
                Characteristic.RESISTANCE_ELEMENTARY_EARTH,
                Characteristic.RESISTANCE_ELEMENTARY_WIND,
                Characteristic.LOCK,
                Characteristic.DODGE,
                Characteristic.INITIATIVE,
                Characteristic.HP
            )

        // Compatibility accessors for consumers of the former tables; every value now comes from the official artifact.
        val RUNE_MASTERY_LEVEL_VALUES get() = RuneValues.embedded.byCharacteristic.getValue(Characteristic.MASTERY_MELEE)
        val RUNE_ELEMENTAL_MASTERY_LEVEL_VALUES get() = RuneValues.embedded.byCharacteristic.getValue(Characteristic.MASTERY_ELEMENTARY)
        val RUNE_RESISTANCE_LEVEL_VALUES get() = RuneValues.embedded.byCharacteristic.getValue(Characteristic.RESISTANCE_ELEMENTARY_FIRE)
        val RUNE_DODGE_LOCK_LEVEL_VALUES get() = RuneValues.embedded.byCharacteristic.getValue(Characteristic.DODGE)
        val RUNE_INITIATIVE_LEVEL_VALUES get() = RuneValues.embedded.byCharacteristic.getValue(Characteristic.INITIATIVE)
        val RUNE_HEALTH_LEVEL_VALUES get() = RuneValues.embedded.byCharacteristic.getValue(Characteristic.HP)

        /**
         * Client raw position ids, joined to CDN occupied positions in [EquipmentPositions].
         * The socketless domain slots retain their empty rune-placement domain.
         */
        fun slotRawIds(itemType: ItemType): List<Int> =
            when (itemType) {
                ItemType.OFF_HAND_WEAPONS, ItemType.EMBLEM, ItemType.PETS, ItemType.MOUNTS -> emptyList()
                else -> EquipmentPositions.embedded.rawIds(itemType)
            }
    }
}
