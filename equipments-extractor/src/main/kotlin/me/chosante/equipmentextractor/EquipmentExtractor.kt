package me.chosante.equipmentextractor

import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.equipmentextractor.dataretriever.WakfuData

val rarityIdToRarity = mapOf(
    0 to Rarity.COMMON,
    1 to Rarity.UNCOMMON,
    2 to Rarity.RARE,
    3 to Rarity.MYTHIC,
    4 to Rarity.LEGENDARY,
    5 to Rarity.RELIC,
    6 to Rarity.SOUVENIR,
    7 to Rarity.EPIC
)

val faultyAction39ItemIds = listOf(
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
    return text.substring(startIndex, endIndex)
        .trim()
        .replace("Maîtrise", "Maîtrise élémentaire")
        .replace("Résistance", "Résistance élémentaire")
        .plus(" éléments aléatoires")
}

fun sanitizeAction2001(text: String): String {
    return text.replace("{[~2]? en [#2]:}", " [#3]")
}

val edgeCaseCharacteristicDescription = mapOf(
    121 to "Armure donnée",
    120 to "Armure reçue"
)

fun extractData(wakfuData: WakfuData): List<Equipment> {
    val itemTypeIdToTypeName = wakfuData.itemTypes.associate { it.definition.id to it.title.fr.toItemType() }
    val effectsByEffectId = wakfuData.effects.associateBy { it.definition.id }
    val jobsDict = wakfuData.jobs.associate { it.definition.id to it.title.fr }

    val equipments = mutableListOf<Equipment>()
    val equipmentsToNotExtract = listOf(24037, 24038, 24039, 24049, 24051, 24058, 24082)
    for (equipment in wakfuData.items) {
        if (equipment.definition.item.id in equipmentsToNotExtract)
            continue

        var level = equipment.definition.item.level
        val itemId = equipment.definition.item.id
        val name = equipment.title.fr.replace("’", "'")
        val rarity = rarityIdToRarity.getValue(equipment.definition.item.baseParameters.rarity)

        val itemTypeId = equipment.definition.item.baseParameters.itemTypeId
        val itemType: ItemType = itemTypeIdToTypeName[itemTypeId] ?: continue

        if (itemType == ItemType.MOUNTS) {
            level = 50
        }

        if (itemType == ItemType.PETS) {
            level = if(equipment.title.fr.contains("Gélutin")) {
                25
            } else {
                50
            }
        }

        val bonus = mutableMapOf<Characteristic, Int>()
        // Loop over each equipment bonus
        for (effect in equipment.definition.equipEffects) {
            val actionId = effect.effect.definition.actionId
            val params = effect.effect.definition.params
            val action = effectsByEffectId[actionId]

            // Applies a state, I don’t know if it’s useful, nor how to materialize it.
            if (actionId == 304) {
                continue
            }

            // bug with these items (the json miss some property for the effect)
            if ((actionId == 39 && faultyAction39ItemIds.contains(itemId))) {
                continue
            }

            if (action?.description != null) {

                var description = if ((actionId == 39 || actionId == 40) && params[4] != 0.0) {
                    "[#1] " + edgeCaseCharacteristicDescription.getValue(params[4].toInt())
                } else {
                    action.description.fr
                }

                // OLD effect, can be replaced by new 57 effect
                if(actionId == 42) {
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
                    continue
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
                    val jobId = Regex("\\d+").findAll(description).last().value.toInt()
                    val jobName = jobsDict.getValue(jobId)
                    description = description.replace(jobId.toString(), jobName)
                }

                val (characteristic, value) = description.toCharacteristic()
                bonus[characteristic] = value
            }
        }

        val outputDict = Equipment(
            equipmentId = itemId,
            level = level,
            name = name,
            rarity = rarity,
            itemType = itemType,
            characteristics = bonus
        )
        equipments.add(outputDict)
    }
    return equipments
}

private fun String.toCharacteristic(): Pair<Characteristic, Int> {
    val match = Regex("""(-?\d+)\s*%?\s*(.*)""").find(this)

    if (match != null) {
        val value = match.groupValues[1].toInt()
        val characteristic = when (val statName = match.groupValues[2].trim()) {
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

private fun String.toItemType(): ItemType? = when (this) {
    "Marteau (Deux mains)", "Hache (Deux mains)", "Arc (Deux mains)", "Pelle (Deux mains)", "Epée (Deux mains)", "Bâton (Deux mains)" -> ItemType.TWO_HANDED_WEAPONS
    "Baguette (Une main)", "Aiguille (Une main)", "Bâton (Une main)", "Cartes (Une main)", "Epée (Une main)" -> ItemType.ONE_HANDED_WEAPONS
    "Bouclier (Seconde main)", "Dague (Seconde main)" -> ItemType.OFF_HAND_WEAPONS
    "Plastron" -> ItemType.CHEST_PLATE
    "Epaulettes" -> ItemType.SHOULDER_PADS
    "Bottes" -> ItemType.BOOTS
    "Amulette" -> ItemType.AMULET
    "Emblème" -> ItemType.EMBLEM
    "Anneau" -> ItemType.RING
    "Cape" -> ItemType.CAPE
    "Casque" -> ItemType.HELMET
    "Familiers" -> ItemType.PETS
    "Montures" -> ItemType.MOUNTS
    "Ceinture" -> ItemType.BELT
    "Costumes", "Torches", "Outils", "Poing", "Armes 1 Main", "Armes 2 Mains", "Seconde Main" -> null
    else -> throw IllegalStateException("unknown type: $this")
}
