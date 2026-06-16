package me.chosante.bdataextractor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.chosante.common.Characteristic
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Semantics of an effect's `action_id`, resolved from the CDN `actions.json` (the same file
 * `equipments-extractor` uses). Only stat/damage/heal actions are relevant here.
 */
sealed interface ActionKind {
    /** A flat-stat gain/loss mapped to a build [Characteristic], with [sign] (+1 gain, −1 loss). */
    data class Stat(
        val characteristic: Characteristic,
        val sign: Int,
    ) : ActionKind

    /** Elemental mastery (1068) / resistance (1069) over N elements selected by `params[2]`. */
    data class RandomElement(
        val mastery: Boolean,
    ) : ActionKind

    /**
     * Gain (39) / Perte (40): the affected characteristic is NOT in the action label but selected at
     * runtime by `params[4]` (an Ankama characteristic id, e.g. 120=given armor, 121=received armor).
     * [sign] is +1 for Gain, −1 for Perte; the characteristic is resolved per-effect via [CHARAC_ID].
     */
    data class DynamicStat(
        val sign: Int,
    ) : ActionKind

    data object Damage : ActionKind

    data object Heal : ActionKind
}

/**
 * Maps each Ankama `action_id` to its meaning, parsed from `actions.json`. Each action's description
 * carries a `[#charac CODE]` token naming the affected characteristic (e.g. `[#charac DMG_FIRE_PERCENT]`);
 * those codes map 1:1 onto the project [Characteristic] enum. The "Gain"/"Boost" vs "Perte"/"Deboost"
 * prefix of the action's `effect` label gives the sign.
 */
class ActionCatalog(
    private val byId: Map<Int, ActionKind>,
    /** Every `action_id` present in `actions.json` — an action absent here is a combat script (opaque). */
    val allActionIds: Set<Int>,
) {
    fun kind(actionId: Int): ActionKind? = byId[actionId]

    companion object {
        private val CHARAC_CODE: Map<String, Characteristic> =
            mapOf(
                "HP" to Characteristic.HP,
                "AP" to Characteristic.ACTION_POINT,
                "MP" to Characteristic.MOVEMENT_POINT,
                "WP" to Characteristic.WAKFU_POINT,
                "HEAL_IN_PERCENT" to Characteristic.MASTERY_HEALING,
                "RES_IN_PERCENT" to Characteristic.RESISTANCE_ELEMENTARY,
                "RES_FIRE_PERCENT" to Characteristic.RESISTANCE_ELEMENTARY_FIRE,
                "RES_WATER_PERCENT" to Characteristic.RESISTANCE_ELEMENTARY_WATER,
                "RES_EARTH_PERCENT" to Characteristic.RESISTANCE_ELEMENTARY_EARTH,
                "RES_AIR_PERCENT" to Characteristic.RESISTANCE_ELEMENTARY_WIND,
                "RES_BACKSTAB" to Characteristic.RESISTANCE_BACK,
                "DMG_IN_PERCENT" to Characteristic.MASTERY_ELEMENTARY,
                "DMG_FIRE_PERCENT" to Characteristic.MASTERY_ELEMENTARY_FIRE,
                "DMG_WATER_PERCENT" to Characteristic.MASTERY_ELEMENTARY_WATER,
                "DMG_EARTH_PERCENT" to Characteristic.MASTERY_ELEMENTARY_EARTH,
                "DMG_AIR_PERCENT" to Characteristic.MASTERY_ELEMENTARY_WIND,
                "CRITICAL_BONUS" to Characteristic.MASTERY_CRITICAL,
                "FEROCITY" to Characteristic.CRITICAL_HIT,
                "RANGE" to Characteristic.RANGE,
                "PROSPECTION" to Characteristic.PROSPECTION,
                "WISDOM" to Characteristic.WISDOM,
                "INIT" to Characteristic.INITIATIVE,
                "TACKLE" to Characteristic.LOCK,
                "DODGE" to Characteristic.DODGE,
                "WILLPOWER" to Characteristic.WILLPOWER,
                "BACKSTAB_BONUS" to Characteristic.MASTERY_BACK,
                "BLOCK" to Characteristic.BLOCK_PERCENTAGE,
                "CRITICAL_RES" to Characteristic.RESISTANCE_CRITICAL,
                "MELEE_DMG" to Characteristic.MASTERY_MELEE,
                "RANGED_DMG" to Characteristic.MASTERY_DISTANCE,
                "BERSERK_DMG" to Characteristic.MASTERY_BERSERK,
                "CONTROL" to Characteristic.CONTROL
            )
        private val CHARAC_TOKEN = Regex("""\[#charac (\w+)]""")

        /**
         * Ankama characteristic id (the `params[4]` selector of actions 39/40) → project [Characteristic].
         * Only ids that map to a build-relevant characteristic are listed; param-selected class resources
         * (e.g. 101 indirect damage, 117 Fury, 123 Stasis) have no [Characteristic] and resolve to null.
         * (120/121 mirror equipments-extractor's `edgeCaseCharacteristicDescription`.)
         */
        val CHARAC_ID: Map<Int, Characteristic> =
            mapOf(
                120 to Characteristic.GIVEN_ARMOR_PERCENTAGE,
                121 to Characteristic.RECEIVED_ARMOR_PERCENTAGE
            )

        fun fetch(version: String): ActionCatalog {
            val url = "https://wakfu.cdn.ankama.com/gamedata/$version/actions.json"
            val body =
                HttpClient.newHttpClient().use { client ->
                    val resp =
                        client.send(
                            HttpRequest.newBuilder(URI.create(url)).GET().build(),
                            HttpResponse.BodyHandlers.ofString()
                        )
                    require(resp.statusCode() == 200) { "GET $url -> HTTP ${resp.statusCode()}" }
                    resp.body()
                }
            return parse(body)
        }

        private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

        fun parse(json: String): ActionCatalog {
            val actions = LENIENT_JSON.decodeFromString<List<ActionDto>>(json)
            val map = HashMap<Int, ActionKind>()
            for (a in actions) {
                val id = a.definition.id
                val label = a.definition.effect ?: ""
                val sign = if (label.startsWith("Perte") || label.startsWith("Deboost")) -1 else 1
                when {
                    id == 1068 -> map[id] = ActionKind.RandomElement(mastery = true)
                    id == 1069 -> map[id] = ActionKind.RandomElement(mastery = false)
                    id == 39 || id == 40 -> map[id] = ActionKind.DynamicStat(sign)
                    label.startsWith("Dommage") -> map[id] = ActionKind.Damage
                    label.startsWith("Soin") -> map[id] = ActionKind.Heal
                    else -> {
                        val code = CHARAC_TOKEN.find(a.description?.fr ?: "")?.groupValues?.get(1)
                        val charac = code?.let { CHARAC_CODE[it] }
                        if (charac != null) {
                            map[id] = ActionKind.Stat(charac, sign)
                        }
                    }
                }
            }
            return ActionCatalog(map, actions.map { it.definition.id }.toSet())
        }
    }

    @Serializable
    private data class ActionDto(
        val definition: Def,
        val description: Desc? = null,
    )

    @Serializable
    private data class Def(
        val id: Int,
        val effect: String? = null,
    )

    @Serializable
    private data class Desc(
        val fr: String? = null,
    )
}
