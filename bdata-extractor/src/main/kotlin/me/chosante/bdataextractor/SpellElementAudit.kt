package me.chosante.bdataextractor

import me.chosante.common.Spell

/** Diagnostic only: a spell branch is not a damage element. Never joined onto the engine catalog.
 * Action ids 2–5/917/1083 are the client's named damage-action registrations (and CDN 1083's [el6]).
 * Descendants are included, but states/cast-spell scripts are deliberately not guessed.
 */
internal fun spellDamageElementAudit(
    spells: Table,
    effects: Table,
    encyclopedia: List<Spell>,
): List<String> {
    val actions = mapOf(1 to "PHYSICAL", 2 to "FIRE", 3 to "EARTH", 4 to "WATER", 5 to "AIR", 917 to "STASIS", 1083 to "LIGHT")
    val bySpell = spells.records.associateBy { it["id"] as Int }
    val byEffect = effects.records.associateBy { it["effect_id"] as Int }
    val children = effects.records.groupBy { it["parent_id"] as Int }
    return encyclopedia.filter { it.baseDamage != null }.map { spell ->
        val found = sortedSetOf<String>()
        val seen = hashSetOf<Int>()

        fun visit(id: Int) {
            if (!seen.add(id)) return
            val effect = byEffect[id] ?: return
            actions[effect["action_id"]]?.let(found::add)
            children[id].orEmpty().forEach { visit(it["effect_id"] as Int) }
        }
        val record = bySpell[spell.id]
        (record?.get("effect_ids") as? List<*>)?.forEach { visit(it as Int) }
        val expected =
            spell.element?.name ?: spell.missingFields
                .firstOrNull { it.startsWith("element(") }
                ?.removePrefix("element(")
                ?.removeSuffix(")")
        "${spell.id},${expected.orEmpty()},${found.joinToString("|")},${found.toList() == listOf(expected)}"
    }
}
