package me.chosante.equipmentextractor

import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ExclusiveGroup
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.equipmentextractor.dataretriever.WakfuData
import me.chosante.equipmentextractor.dataretriever.dtos.Effect
import me.chosante.equipmentextractor.dataretriever.dtos.EffectData
import me.chosante.equipmentextractor.dataretriever.dtos.Item
import me.chosante.equipmentextractor.dataretriever.dtos.ItemProperty

val rarityIdToRarity =
    mapOf(
        0 to Rarity.COMMON,
        1 to Rarity.UNCOMMON,
        2 to Rarity.RARE,
        3 to Rarity.MYTHIC,
        4 to Rarity.LEGENDARY,
        5 to Rarity.RELIC,
        6 to Rarity.SOUVENIR,
        7 to Rarity.EPIC
    )

val faultyAction39ItemIds =
    listOf(
        28272,
        14152,
        29115,
        29116,
        29534,
        29630,
        29631,
        29882,
        29883,
        29884,
        29885,
        29886,
        29887,
        29922,
        29923,
        29972,
        29973,
        29978,
        29979,
        30189,
        30190,
        30194,
        30195,
        30266,
        30267,
        30268,
        30277,
        30278,
        30323,
        30324,
        30325
    )

fun extractDynamicResistanceOrMasteries(text: String): String {
    val startIndex = text.indexOf("?") + 1
    val endIndex = text.indexOf(":")
    return text
        .substring(startIndex, endIndex)
        .trim()
        .replace("Maîtrise", "Maîtrise élémentaire")
        .replace("Résistance", "Résistance élémentaire")
        .plus(" éléments aléatoires")
}

fun sanitizeAction2001(text: String): String = text.replace("{[~2]? en [#2]:}", " [#3]")

val edgeCaseCharacteristicDescription =
    mapOf(
        121 to "Armure donnée",
        120 to "Armure reçue"
    )

val mutableSet = mutableSetOf<I18nText>()

/** "Applies a state": a combat mechanic (procs, reactive buffs), never a sheet stat, so it is not modeled. */
private const val ACTION_APPLY_STATE = 304

/**
 * "REG : niveau des enfants fonction du niveau du caster/cible de cet effet": runs its CHILD effects (the item's
 * `subEffects`) at a percentage of the wearer's level, i.e. "X% of the level as <stat>". Decoded by [decodePercentOfLevel].
 */
private const val ACTION_PERCENT_OF_LEVEL = 999

/**
 * Equip actions with no description in actions.json that are known to grant no stat, so the extractor may skip them.
 * Any OTHER undescribed action fails the extraction: its effect would otherwise vanish without a trace.
 *  - 400 "NullEffect : Effet Vide": an empty placeholder on some mounts;
 *  - 1020 "REG : niveau des enfants fonction de la valeur de l'effet déclencheur": a proc whose children scale with the
 *    triggering hit (the level-0 ring Makabrano Zer deals damage with it), not a stat.
 */
private val STAT_FREE_UNDESCRIBED_ACTIONS = setOf(400, 1020)

/**
 * The CDN item properties that put an item in an "only one equipped at a time" group ([ExclusiveGroup]), by their
 * `itemProperties.json` NAME (ids 8 and 12 on the 1.93 data) — resolved by name so a renumbering cannot silently swap them.
 */
private val EXCLUSIVE_GROUP_PROPERTIES =
    mapOf(
        "EXCLUSIVE_EQUIPMENT_ITEM" to ExclusiveGroup.RELIC,
        "EXCLUSIVE_EQUIPMENT_ITEM_2" to ExclusiveGroup.EPIC
    )

/** The property id → exclusive group map of [itemProperties]; fails when the CDN no longer names both properties. */
internal fun exclusiveGroupPropertyIds(itemProperties: List<ItemProperty>): Map<Int, ExclusiveGroup> {
    val byName = itemProperties.associateBy { it.name }
    return EXCLUSIVE_GROUP_PROPERTIES.entries.associate { (name, group) ->
        val property =
            checkNotNull(byName[name]) {
                "itemProperties.json has no property $name: the epic / relic exclusivity groups can't be read (got ${itemProperties.map { it.name }})"
            }
        property.id to group
    }
}

/**
 * The "only one equipped at a time" group of the item [itemId] from its CDN [properties] ([groupByPropertyId]: see
 * [exclusiveGroupPropertyIds]); fails on an item in both groups, which the build budgets can't express.
 */
internal fun exclusiveGroupOf(
    itemId: Int,
    properties: List<Int>,
    groupByPropertyId: Map<Int, ExclusiveGroup>,
): ExclusiveGroup {
    val groups = properties.mapNotNull { groupByPropertyId[it] }.distinct()
    check(groups.size <= 1) { "Item $itemId is in several exclusivity groups $groups (properties $properties)" }
    return groups.singleOrNull() ?: ExclusiveGroup.NONE
}

fun extractData(wakfuData: WakfuData): List<Equipment> {
    val itemTypeIdToTypeName = wakfuData.itemTypes.associate { it.definition.id to it.title.fr.toItemType() }
    val exclusiveGroupByPropertyId = exclusiveGroupPropertyIds(wakfuData.itemProperties)
    val effectsByEffectId = wakfuData.effects.associateBy { it.definition.id }
    val jobsDict = wakfuData.jobs.associate { it.definition.id to it.title.fr }

    val equipments = mutableListOf<Equipment>()
    val equipmentsToNotExtract = listOf(24037, 24038, 24039, 24049, 24051, 24058, 24082)
    for (equipment in wakfuData.items) {
        if (equipment.definition.item.id in equipmentsToNotExtract) {
            continue
        }

        var level = equipment.definition.item.level
        val itemId = equipment.definition.item.id
        val itemGuiId = equipment.definition.item.graphicParameters.gfxId
        val name =
            equipment.title.let {
                I18nText(
                    fr = it.fr.replace("’", "'").replace("‘", "'"),
                    en = it.en.replace("’", "'").replace("‘", "'"),
                    pt = it.pt.replace("’", "'").replace("‘", "'"),
                    es = it.es.replace("’", "'").replace("‘", "'")
                )
            }
        val rarity = rarityIdToRarity.getValue(equipment.definition.item.baseParameters.rarity)
        // The item's "only one equipped at a time" group, written only where it is not its rarity's (Equipment.exclusiveGroup):
        // on the 1.93 data, the two COMMON items in the EPIC group (18691, 18693).
        val exclusiveGroup = exclusiveGroupOf(equipment.definition.item.id, equipment.definition.item.properties, exclusiveGroupByPropertyId)

        // Number of enchantment sockets ("châsses") the item can hold — drives rune socketing in the
        // solver (Equipment.maxShardSlots). Must be carried through here or every regenerated build
        // loses its runes (no icons in the GUI, no shards exported to Zenith).
        val maxShardSlots = equipment.definition.item.baseParameters.maximumShardSlotNumber

        val itemTypeId = equipment.definition.item.baseParameters.itemTypeId
        val itemType: ItemType = itemTypeIdToTypeName[itemTypeId] ?: continue

        // A Lucky Charm ("Porte-bonheur") sits in the PETS slot but, unlike a familier (raw level 0), carries a
        // real level requirement — flag it from the RAW level (before the force-level below) so the searcher
        // level-filters it like ordinary gear instead of treating it as a level-less companion. Familiers and
        // mounts leave this false and stay equippable at any character level.
        val levelRestricted = itemType == ItemType.PETS && level != 0

        if (itemType == ItemType.MOUNTS) {
            level = 50
        }

        // Familiers carry no level in the CDN (raw level 0); stamp them to 50 (Gélutins to 25) so the UI shows
        // a sensible level instead of "Lvl 0" — display-only, since familiers stay level-exempt (levelRestricted
        // is false). Lucky Charms ("Porte-bonheur") share the PETS slot but DO carry a real CDN level (12..35);
        // leave it untouched so they are level-filtered honestly.
        if (itemType == ItemType.PETS && level == 0) {
            level =
                if (equipment.title.fr.contains("Gélutin")) {
                    25
                } else {
                    50
                }
        }

        val bonus = mutableMapOf<Characteristic, Int>()
        val percentOfLevel = mutableMapOf<Characteristic, Int>()
        // Loop over each equipment bonus
        for (effect in equipment.definition.equipEffects) {
            val actionId = effect.effect.definition.actionId
            val params = effect.effect.definition.params
            val action = effectsByEffectId[actionId]

            // Applies a state: a combat mechanic, not a sheet stat, so it is not modeled. Among them the Dofus Pourpre's
            // state 9364 (→ 9365: +10% damage inflicted for one turn after being hit), deliberately left out: too
            // situational for a build search.
            if (actionId == ACTION_APPLY_STATE) {
                continue
            }

            // bug with these items (the json miss some property for the effect)
            if ((actionId == 39 && faultyAction39ItemIds.contains(itemId))) {
                continue
            }

            if (actionId == ACTION_PERCENT_OF_LEVEL) {
                decodePercentOfLevel(itemId, effect) { child ->
                    describedStat(child.actionId, child.params, level, effectsByEffectId, jobsDict)
                }.forEach { (characteristic, percent) -> percentOfLevel.merge(characteristic, percent, Int::plus) }
                continue
            }

            // An action without a description in actions.json has no stat line to read. Skipping one silently is how the
            // Dofus Pourpre lost its Elemental Mastery (its 999 above), so only the actions known to grant no stat may be.
            if (action?.description == null) {
                check(actionId in STAT_FREE_UNDESCRIBED_ACTIONS) {
                    "Item $itemId (${name.fr}): equip action $actionId has no description in actions.json, so its effect " +
                        "would be dropped. Decode it (like action $ACTION_PERCENT_OF_LEVEL) or, if it grants no stat, add it " +
                        "to STAT_FREE_UNDESCRIBED_ACTIONS."
                }
                continue
            }

            val (characteristic, value) = describedStat(actionId, params, level, effectsByEffectId, jobsDict) ?: continue
            bonus[characteristic] = value
        }

        val outputDict =
            Equipment(
                equipmentId = itemId,
                guiId = itemGuiId,
                level = level,
                name = name,
                rarity = rarity,
                itemType = itemType,
                characteristics = bonus,
                maxShardSlots = maxShardSlots,
                levelRestricted = levelRestricted,
                percentOfLevel = percentOfLevel,
                exclusiveGroupOverride = exclusiveGroup.takeIf { it != ExclusiveGroup.ofRarity(rarity) }
            )
        equipments.add(outputDict)
    }

    return equipments
}

/**
 * The stat line an equip effect grants, read from its action's French description in actions.json (`[#1]` = the value
 * `params[0] + params[1]·level`), or null when the action has no description or the line is a spell-level bonus ("Niv.
 * aux sorts"), which we don't model.
 */
private fun describedStat(
    actionId: Int,
    params: List<Double>,
    level: Int,
    effectsByEffectId: Map<Int, Effect>,
    jobsDict: Map<Int, String>,
): Pair<Characteristic, Int>? {
    val action = effectsByEffectId[actionId]
    val actionDescription = action?.description ?: return null
    mutableSet.add(actionDescription)
    var description =
        if ((actionId == 39 || actionId == 40) && params[4] != 0.0) {
            "[#1] " + edgeCaseCharacteristicDescription.getValue(params[4].toInt())
        } else {
            actionDescription.fr
        }

    // OLD effect, can be replaced by new 57 effect
    if (actionId == 42) {
        description = effectsByEffectId.getValue(57).description!!.fr
    }

    description = description.replace("[el1]", "Feu")
    description = description.replace("[el2]", "Eau")
    description = description.replace("[el3]", "Terre")
    description = description.replace("[el4]", "Air")
    description = description.replace("{[>1]?s:}", "s")
    description =
        description.replace("[#1]", ((params[1] * level + params[0]).toInt()).toString())

    if ("Niv. aux sorts" in description) {
        return null
    }

    if (actionId == 1069 || actionId == 1068) {
        description = extractDynamicResistanceOrMasteries(description)
    }

    if (actionId == 2001) {
        description = sanitizeAction2001(description)
    }

    for ((i, param) in params.withIndex()) {
        val baliseParam = "[#${i + 1}]"
        description = description.replace(baliseParam, param.toInt().toString())
    }

    if (actionId == 2001) {
        val jobId =
            Regex("\\d+")
                .findAll(description)
                .last()
                .value
                .toInt()
        val jobName = jobsDict.getValue(jobId)
        description = description.replace(jobId.toString(), jobName)
    }

    return description.toCharacteristic()
}

/**
 * Decodes an "X% of the level as <stat>" equip line ([ACTION_PERCENT_OF_LEVEL]) into `stat → X` pairs for
 * [Equipment.percentOfLevel]. The wrapper only carries X, its lone param > 1 (`params[14] = 100` on the Dofus Pourpre:
 * the same reading as the bdata-extractor's sublimation decoder); each CHILD effect ([statOf] decodes it like any equip
 * line) names the stat and must be worth exactly its own level (`0 + 1·level`: the Pourpre's 120 "Elemental Mastery"
 * `[0, 1]`), so the stat is X% of the wearer's level. Any other shape throws rather than being dropped or mis-scaled.
 */
internal fun decodePercentOfLevel(
    itemId: Int,
    effect: Item.Definition.Effect,
    statOf: (EffectData.EffectDefinition) -> Pair<Characteristic, Int>?,
): List<Pair<Characteristic, Int>> {
    val wrapper = effect.effect.definition
    val percents = wrapper.params.filter { it > 1.0 }
    check(percents.size == 1 && percents.single() % 1.0 == 0.0) {
        "Item $itemId: action ${wrapper.actionId} must carry exactly one whole percentage (param > 1), got ${wrapper.params}"
    }
    val percent = percents.single().toInt()
    val children = effect.subEffects.orEmpty()
    check(children.isNotEmpty()) { "Item $itemId: action ${wrapper.actionId} has no child effect naming its stat" }
    return children.map { child ->
        val definition = child.effect.definition
        check(child.subEffects.isNullOrEmpty() && definition.params.size >= 2 && definition.params[0] == 0.0 && definition.params[1] == 1.0) {
            "Item $itemId: the child of action ${wrapper.actionId} must be one stat worth its level (params [0, 1]), " +
                "got action ${definition.actionId} ${definition.params}"
        }
        val (characteristic, _) =
            checkNotNull(statOf(definition)) {
                "Item $itemId: the child action ${definition.actionId} of action ${wrapper.actionId} is not a stat line"
            }
        characteristic to percent
    }
}

private fun String.toCharacteristic(): Pair<Characteristic, Int> {
    val match = Regex("""(-?\d+)\s*%?\s*(.*)""").find(this)

    if (match != null) {
        val value = match.groupValues[1].toInt()
        val characteristic =
            when (val statName = match.groupValues[2].trim()) {
                "Quantité Récolte Herboriste" -> Characteristic.HERBALIST_HARVEST_QUANTITY_PERCENTAGE
                "PA max" -> Characteristic.MAX_ACTION_POINT
                "Quantité Récolte Mineur" -> Characteristic.MINER_HARVEST_QUANTITY_PERCENTAGE
                "Esquive" -> Characteristic.DODGE
                "Résistance élémentaire 3 éléments aléatoires" -> Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT
                "Contrôle" -> Characteristic.CONTROL
                "Maîtrise Dos" -> Characteristic.MASTERY_BACK
                "Maîtrise Distance" -> Characteristic.MASTERY_DISTANCE
                "Maîtrise Air" -> Characteristic.MASTERY_ELEMENTARY_WIND
                "Maîtrise élémentaire 1 éléments aléatoires" -> Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT
                "Résistance élémentaire 1 éléments aléatoires" -> Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT
                "Résistance Eau" -> Characteristic.RESISTANCE_ELEMENTARY_WATER
                "Tacle" -> Characteristic.LOCK
                "Maîtrise Feu" -> Characteristic.MASTERY_ELEMENTARY_FIRE
                "Maîtrise élémentaire 3 éléments aléatoires" -> Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT
                "PA" -> Characteristic.ACTION_POINT
                "Résistance Feu" -> Characteristic.RESISTANCE_ELEMENTARY_FIRE
                "Sagesse" -> Characteristic.WISDOM
                "Maîtrise Mêlée" -> Characteristic.MASTERY_MELEE
                "Résistance Air" -> Characteristic.RESISTANCE_ELEMENTARY_WIND
                "PW max", "Points de Wakfu max" -> Characteristic.MAX_WAKFU_POINTS
                "Résistance élémentaire 2 éléments aléatoires" -> Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT
                "Résistance Terre" -> Characteristic.RESISTANCE_ELEMENTARY_EARTH
                "PW", "Points de Wakfu" -> Characteristic.WAKFU_POINT
                "Prospection" -> Characteristic.PROSPECTION
                "Quantité Récolte Paysan" -> Characteristic.FARMER_HARVEST_QUANTITY_PERCENTAGE
                "Maîtrise Critique" -> Characteristic.MASTERY_CRITICAL
                "Résistance Critique" -> Characteristic.RESISTANCE_CRITICAL
                "Résistance Élémentaire", "Résistance élémentaire" -> Characteristic.RESISTANCE_ELEMENTARY
                "Initiative" -> Characteristic.INITIATIVE
                "Maîtrise Soin" -> Characteristic.MASTERY_HEALING
                "Portée" -> Characteristic.RANGE
                "Quantité Récolte Pêcheur" -> Characteristic.FISHERMAN_HARVEST_QUANTITY_PERCENTAGE
                "PV" -> Characteristic.HP
                "PM max" -> Characteristic.MAX_MOVEMENT_POINT
                "Maîtrise Berserk" -> Characteristic.MASTERY_BERSERK
                "Quantité Récolte Forestier" -> Characteristic.LUMBERJACK_HARVEST_QUANTITY_PERCENTAGE
                "Armure reçue" -> Characteristic.RECEIVED_ARMOR_PERCENTAGE
                "Coup critique" -> Characteristic.CRITICAL_HIT
                "Points de Vie" -> Characteristic.HP
                "Maîtrise Élémentaire" -> Characteristic.MASTERY_ELEMENTARY
                "Volonté" -> Characteristic.WILLPOWER
                "PM" -> Characteristic.MOVEMENT_POINT
                "Maîtrise Eau" -> Characteristic.MASTERY_ELEMENTARY_WATER
                "Parade" -> Characteristic.BLOCK_PERCENTAGE
                "Quantité Récolte Trappeur" -> Characteristic.TRAPPER_HARVEST_QUANTITY_PERCENTAGE
                "Armure donnée" -> Characteristic.GIVEN_ARMOR_PERCENTAGE
                "Maîtrise élémentaire 2 éléments aléatoires" -> Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT
                "Maîtrise Terre" -> Characteristic.MASTERY_ELEMENTARY_EARTH
                "Résistance Dos" -> Characteristic.RESISTANCE_BACK
                else -> throw IllegalArgumentException("Caractéristique inconnue : $statName")
            }
        return characteristic to value
    } else {
        throw IllegalArgumentException("Format de caractéristique invalide : $this")
    }
}

private fun String.toItemType(): ItemType? =
    when (this.replace("{[~1]?s:}", "").replace("{[~1]?x:}", "")) {
        "Marteau (Deux mains)", "Hache (Deux mains)", "Arc (Deux mains)", "Pelle (Deux mains)", "Epée (Deux mains)", "Bâton (Deux mains)" -> ItemType.TWO_HANDED_WEAPONS
        "Baguette (Une main)", "Aiguille (Une main)", "Bâton (Une main)", "Carte (Une main)", "Epée (Une main)" -> ItemType.ONE_HANDED_WEAPONS
        "Bouclier (Seconde main)", "Dague (Seconde main)" -> ItemType.OFF_HAND_WEAPONS
        "Plastron" -> ItemType.CHEST_PLATE
        "Epaulettes" -> ItemType.SHOULDER_PADS
        "Bottes" -> ItemType.BOOTS
        "Amulette" -> ItemType.AMULET
        "Emblème" -> ItemType.EMBLEM
        "Anneau" -> ItemType.RING
        "Cape" -> ItemType.CAPE
        "Casque" -> ItemType.HELMET
        "Familier" -> ItemType.PETS
        "Monture" -> ItemType.MOUNTS
        "Ceinture" -> ItemType.BELT
        // "Porte-bonheur" (Lucky Charm, Ankama item type 849, added in 1.92.x) equips in the single in-game PET
        // position, shared with familiers — so we model it as a PETS candidate competing for that slot. There
        // are only 6 (all level 12..35, Mythic) and the PETS pool bypasses the level filter (familiers have no
        // level requirement in Wakfu — see the grouping in WakfuBestBuildFinderAlgorithm), so a stronger
        // familier almost always dominates them; including them is the faithful in-game model and lets the
        // solver take one only on the rare request where it is Pareto-best. Caveat: the Zenith export keys off
        // ItemType.id, so a chosen Lucky Charm exports as a pet id that Zenith mis-slots/ignores — accepted,
        // the Zenith integration is already stale.
        "Porte-bonheur" -> ItemType.PETS
        "Costume", "Torche", "Outil", "Poing", "Arme 1 Main", "Arme 2 Mains", "Seconde Main", "WIP" -> null
        else -> throw IllegalStateException("unknown type: $this")
    }
