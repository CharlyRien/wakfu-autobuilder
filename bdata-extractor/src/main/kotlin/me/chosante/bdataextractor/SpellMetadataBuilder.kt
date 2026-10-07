package me.chosante.bdataextractor

import me.chosante.common.Spell
import me.chosante.common.SpellResourceCost
import kotlin.math.floor

/** Merge only reviewed client fields. Missing client ids retain the encyclopedia record; no invented zeros.
 * Damage, element, category, area, LOS, icon and descriptions stay exactly on their existing source.
 * FR corrections are id-reviewed misaligned/obsolete listing names, documented in the comparison report.
 */
internal val REVIEWED_FRENCH_SPELL_NAMES = setOf(4720, 6845, 7064, 5030, 5041, 5043, 5044, 5045, 5047, 7211, 4604, 6942, 7084)

fun buildSpellMetadata(
    spells: Table,
    i18n: I18nBundle,
    encyclopedia: List<Spell>,
    resourceName: (Int) -> String?,
): List<Spell> {
    val byId = spells.records.associateBy { it["id"] as Int }
    return encyclopedia.map { spell ->
        val record = byId[spell.id] ?: return@map spell
        val level = record.getValue("max_level") as Int

        fun value(
            base: String,
            inc: String,
        ): Int {
            val v = (record.getValue(base) as Number).toDouble() + (record.getValue(inc) as Number).toDouble() * level
            check(v.isFinite()) { "Non-finite spell ${spell.id} $base" }
            return floor(v).toInt()
        }
        val names = i18n.requireText(3, spell.id)
        // English names currently reproduce exactly. Future differences need review, never automatic replacement.
        check(names.en == spell.name.en) { "Spell ${spell.id} English name changed: ${spell.name.en} → ${names.en}; review before adoption" }
        val resourceCosts =
            (record.getValue("base_cast_parameters") as Map<*, *>)
                .entries
                .mapNotNull { (id, raw) ->
                    val characteristicId = id as Int
                    val scriptName = resourceName(characteristicId) ?: error("Unknown spell resource $characteristicId")
                    val params = raw as Map<*, *>
                    val delta = floor((params["base"] as Number).toDouble() + (params["increment"] as Number).toDouble() * level).toInt()
                    // Conventions differ by resource. HUPPERMAGE_RESOURCE is a signed delta; SP is a positive
                    // expenditure like AP/WP (the client cast validator compares it with current Stasis Points).
                    val cost =
                        when (scriptName) {
                            "HUPPERMAGE_RESOURCE" -> -delta
                            "SP" -> delta
                            else -> error("Unreviewed cost convention for spell resource $scriptName ($characteristicId)")
                        }
                    if (cost > 0) SpellResourceCost(characteristicId, scriptName, cost) else null
                }.sortedBy { it.characteristicId }
        spell.copy(
            name = spell.name.copy(fr = if (spell.id in REVIEWED_FRENCH_SPELL_NAMES) names.fr else spell.name.fr, es = names.es, pt = names.pt),
            apCost = value("pa_base", "pa_inc"),
            mpCost = value("pm_base", "pm_inc"),
            wpCost = value("pw_base", "pw_inc"),
            resourceCosts = resourceCosts,
            rangeMin = value("range_min_base", "range_min_level_increment"),
            rangeMax = value("range_max_base", "range_max_inc"),
            source = "ankama-encyclopedia+ankama-client",
            missingFields = spell.missingFields.filterNot { it in setOf("apCost", "rangeMin", "rangeMax", "range") }
        )
    }
}
