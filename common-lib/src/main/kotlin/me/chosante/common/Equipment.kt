package me.chosante.common

import kotlinx.serialization.Serializable

@Serializable
enum class Rarity {
    COMMON,
    UNCOMMON,
    RARE,
    MYTHIC,
    LEGENDARY,
    RELIC,
    SOUVENIR,
    EPIC,
}

@Serializable
enum class ItemType(
    val id: Int,
) {
    AMULET(120),
    EMBLEM(646),
    SHOULDER_PADS(138),
    RING(103),
    BOOTS(119),
    ONE_HANDED_WEAPONS(518),
    CHEST_PLATE(136),
    CAPE(132),
    OFF_HAND_WEAPONS(112),
    HELMET(134),
    PETS(582),
    TWO_HANDED_WEAPONS(519),
    MOUNTS(611),
    BELT(133),
}

@Serializable
data class Equipment(
    val equipmentId: Int,
    val guiId: Int,
    val level: Int,
    val name: I18nText,
    val rarity: Rarity,
    val itemType: ItemType,
    val characteristics: Map<Characteristic, Int>,
    // Number of enchantment sockets ("châsses") on this item, 0..4. Defaults to 0 so equipments
    // resources generated before runes were modeled still deserialize (they simply carry no sockets).
    val maxShardSlots: Int = 0,
)

@Serializable
data class I18nText(
    val fr: String,
    val en: String,
    val es: String,
    val pt: String,
)

@Serializable
enum class Characteristic {
    MASTERY_ELEMENTARY,
    MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT,
    MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT,
    MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT,
    MASTERY_ELEMENTARY_WATER,
    MASTERY_ELEMENTARY_WIND,
    MASTERY_ELEMENTARY_FIRE,
    MASTERY_ELEMENTARY_EARTH,
    MASTERY_DISTANCE,
    MASTERY_CRITICAL,
    MASTERY_BACK,
    MASTERY_MELEE,
    MASTERY_BERSERK,
    MASTERY_HEALING,

    // "% Dommages infligés": a flat percentage damage multiplier, distinct from mastery. It is its
    // own multiplicative factor in the Wakfu damage formula, so it is only read by the max-damage
    // scoring mode. Sources: the Major "% Inflicted Damage" aptitude and (later) sublimations.
    DAMAGE_INFLICTED,
    RESISTANCE_CRITICAL,
    RESISTANCE_BACK,
    RESISTANCE_ELEMENTARY,
    RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT,
    RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT,
    RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT,
    RESISTANCE_ELEMENTARY_EARTH,
    RESISTANCE_ELEMENTARY_FIRE,
    RESISTANCE_ELEMENTARY_WATER,
    RESISTANCE_ELEMENTARY_WIND,
    HP,
    CRITICAL_HIT,
    WAKFU_POINT,
    MAX_WAKFU_POINTS,
    ACTION_POINT,
    MAX_ACTION_POINT,
    RANGE,
    MOVEMENT_POINT,
    MAX_MOVEMENT_POINT,
    CONTROL,
    WISDOM,
    DODGE,
    LOCK,
    PROSPECTION,
    INITIATIVE,
    WILLPOWER,
    BLOCK_PERCENTAGE,
    GIVEN_ARMOR_PERCENTAGE,
    RECEIVED_ARMOR_PERCENTAGE,
    HERBALIST_HARVEST_QUANTITY_PERCENTAGE,
    LUMBERJACK_HARVEST_QUANTITY_PERCENTAGE,
    TRAPPER_HARVEST_QUANTITY_PERCENTAGE,
    MINER_HARVEST_QUANTITY_PERCENTAGE,
    FARMER_HARVEST_QUANTITY_PERCENTAGE,
    FISHERMAN_HARVEST_QUANTITY_PERCENTAGE,
}
