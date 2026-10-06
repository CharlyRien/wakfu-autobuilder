package me.chosante.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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

/**
 * The game's "only one item of this group equipped at a time" rule — what the "at most one epic and one relic item" budget
 * really is. Ankama's CDN data carries it as two item PROPERTIES (`items.json` → `properties`, named in
 * `itemProperties.json`): 12 `EXCLUSIVE_EQUIPMENT_ITEM_2` ("[Relique2]") for [EPIC] and 8 `EXCLUSIVE_EQUIPMENT_ITEM`
 * ("[Relique]") for [RELIC]. On the 1.93 data property 8 is on exactly the 99 RELIC items, and property 12 on all 115 EPIC
 * items plus two COMMON ones (18691 Piquants du Guerrier Trool anciens, 18693 Sain Turastil ancienne): the game refuses
 * either of those two next to an epic item, or next to each other. An item reads its group through
 * [Equipment.exclusiveGroup].
 *
 * The group decides the BUDGET only. Which item can host an epic / relic SUBLIMATION stays its [Rarity]: the exclusivity
 * properties say nothing about a sublimation socket — the CDN names that with properties of its own (19 `EPIC_GEMMABLE` / 20
 * `RELIC_GEMMABLE`, "adds an epic / relic gem slot to an item", carried only by the four nation rings and five swords, which
 * are EPIC / RELIC already) — and neither COMMON item of the EPIC group carries one.
 */
@Serializable
enum class ExclusiveGroup {
    /** In no group: any number of such items may be worn together. */
    NONE,

    /** Property 12 `EXCLUSIVE_EQUIPMENT_ITEM_2`: every EPIC item, and two COMMON ones. */
    EPIC,

    /** Property 8 `EXCLUSIVE_EQUIPMENT_ITEM`: every RELIC item. */
    RELIC,
    ;

    companion object {
        /** The group an item of [rarity] is in when its data says nothing else: EPIC and RELIC items in theirs, others in none. */
        fun ofRarity(rarity: Rarity): ExclusiveGroup =
            when (rarity) {
                Rarity.EPIC -> EPIC
                Rarity.RELIC -> RELIC
                else -> NONE
            }
    }
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
    // Only consulted for the companion slots (PETS/MOUNTS): when true, this slot item carries a real
    // character-level requirement and must be level-filtered like ordinary gear. Lucky Charms ("Porte-bonheur")
    // sit in the PETS slot yet — unlike level-less familiers and mounts — have a real level, so they set this.
    // Ordinary equipment is level-filtered regardless, so the flag is irrelevant (and stays false) for it.
    // Defaults false so equipments resources generated before this flag existed still deserialize.
    val levelRestricted: Boolean = false,
    // Ankama's "X% of the level as <stat>" equip lines (action 999 wrapping the stat's own effect), e.g. the Dofus
    // Pourpre's "100% of level as Elemental Mastery": stat → percent. The magnitude depends on the WEARER's level, so it
    // can't live in [characteristics]; [atLevel] folds it in when a search pool is built for a character, before any
    // stat reader (solver, scorers, certificates, domination, GUI) sees the item. Empty for every other item, and the
    // default, so resources and saved builds written before it existed still deserialize.
    val percentOfLevel: Map<Characteristic, Int> = emptyMap(),
    // The item's EQUIP criterion (a nation sword needs its ring, a class emblem is for its class, some rings exclude each
    // other — see [ItemEquipCriterion]), decoded from the client by `bdata-extractor` into `item-criteria.json` and joined
    // by id when the engine loads its catalog. Null for an item without one — and for every item built outside the
    // catalog (tests), so a synthetic pool never inherits a real item's conditions by id. @Transient: it is never read
    // from equipments.json (a CDN artifact) nor written into a saved build.
    @Transient
    val equipCriterion: ItemEquipCriterion? = null,
    // The item's "only one equipped at a time" group when it is NOT its rarity's ([ExclusiveGroup], read through
    // [exclusiveGroup]): null = the rarity's group. `equipments-extractor` reads it from the CDN item properties and writes it
    // only for such an exception (on the 1.93 data, the two COMMON items in the EPIC group), so a synthetic item (tests), a
    // copy with another rarity, and a build saved before the field existed all follow their rarity.
    @SerialName("exclusiveGroup")
    val exclusiveGroupOverride: ExclusiveGroup? = null,
) {
    /**
     * The "only one equipped at a time" group this item is in ([ExclusiveGroup]): a build wears at most one [ExclusiveGroup.EPIC]
     * and one [ExclusiveGroup.RELIC] item. Its [rarity]'s group unless the data says otherwise ([exclusiveGroupOverride]).
     */
    val exclusiveGroup: ExclusiveGroup
        get() = exclusiveGroupOverride ?: ExclusiveGroup.ofRarity(rarity)

    /**
     * This item as worn by a level-[characterLevel] character: every [percentOfLevel] line resolved into
     * [characteristics] (`floor(percent · level / 100)`, [percentOfLevelMagnitude]) and cleared. Clearing makes the copy
     * final: resolving it again, at any level, returns it unchanged, so a resolved item can flow through any number of
     * pools or saved builds without being counted twice. An item without such a line is returned as is (same instance).
     */
    fun atLevel(characterLevel: Int): Equipment {
        if (percentOfLevel.isEmpty()) return this
        val resolved = LinkedHashMap(characteristics)
        for ((characteristic, percent) in percentOfLevel) {
            resolved.merge(characteristic, percentOfLevelMagnitude(percent, characterLevel), Int::plus)
        }
        return copy(characteristics = resolved, percentOfLevel = emptyMap())
    }
}

/**
 * Ankama's "X% of the level as <stat>": `floor(percent · level / 100)` for a level-[level] character (every shipped
 * percent is positive, where Kotlin's truncating `/` is the floor). Shared by the level-scaled item lines
 * ([Equipment.percentOfLevel]) and sublimations ([SublimationEffect.PercentOfLevel]). At 100% (the Dofus Pourpre) it is
 * the level itself, whatever the rounding.
 */
fun percentOfLevelMagnitude(
    percent: Int,
    level: Int,
): Int = (percent * level) / 100

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
