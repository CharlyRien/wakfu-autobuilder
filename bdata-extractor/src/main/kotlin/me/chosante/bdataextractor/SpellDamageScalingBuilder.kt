package me.chosante.bdataextractor

import me.chosante.common.Spell
import me.chosante.common.SpellDamageScaling
import me.chosante.common.SpellElement
import kotlin.math.floor

/*
 * Builds `spell-damage.json` — the per-level damage FORMULA for each damage spell — by joining bdata to the
 * encyclopedia. The encyclopedia only publishes a spell's hit at one level (the max, 245); bdata's damage
 * effects carry the `[base, inc]` params, so `floor(base + inc·level)` gives the hit at ANY caster level.
 * That fixes the engine displaying max-level damage for a low-level character.
 *
 * The catch is *which* of a spell's many damage effects (combo tiers, conditional variants, `% of level`
 * scalers) is the base hit. We resolve it by ANCHORING on the encyclopedia: the bdata damage effect whose
 * `floor(base + inc·levelCap)` equals the encyclopedia's value is, by construction, the right one — so the
 * encyclopedia *disambiguates* the selection instead of being a competing source. When no effect reproduces
 * the value (DoT / Ecaflip-random / weapon-% spells), a linear approximation through the known max-level
 * value is stored (`matched = false`): exact at `levelCap`, graceful below it, never worse than today.
 */

/** Spell-damage `action_id`s (neutral/fire/earth/water/air/light); element-specific ones aren't in actions.json. */
private val DAMAGE_ACTIONS = setOf(1, 2, 3, 4, 5, 1083)

/** Element → its damage action, to break ties when several effects reproduce the anchor value at levelCap. */
private val ELEMENT_TO_DAMAGE_ACTION: Map<SpellElement, Int> =
    mapOf(SpellElement.FIRE to 2, SpellElement.EARTH to 3, SpellElement.WATER to 4, SpellElement.AIR to 5)

/** Wakfu's max character level — the level the encyclopedia renders by default and the scaling cap. */
private const val MAX_CHARACTER_LEVEL = 245

/** A decoded damage effect's per-level params, with its action (for element tie-breaking). */
private class DmgEffect(
    val action: Int,
    val base: Double,
    val inc: Double,
)

fun buildSpellDamageScalings(
    spells: Table,
    effects: Table,
    encyclopediaSpells: List<Spell>,
): List<SpellDamageScaling> {
    @Suppress("UNCHECKED_CAST")
    fun intList(v: Any?): List<Int> = (v as List<Any?>).map { it as Int }

    val spellById = spells.records.associateBy { it["id"] as Int }
    val byEffectId = effects.records.associateBy { it["effect_id"] as Int }
    val childrenOf = HashMap<Int, MutableList<Int>>()
    for (r in effects.records) childrenOf.getOrPut(r["parent_id"] as Int) { mutableListOf() }.add(r["effect_id"] as Int)

    fun subtree(effectIds: List<Int>): List<Int> {
        val seen = HashSet<Int>()
        val out = ArrayList<Int>()

        fun visit(id: Int) {
            if (id in seen || id !in byEffectId) return
            seen.add(id)
            out.add(id)
            childrenOf[id]?.forEach(::visit)
        }
        effectIds.forEach(::visit)
        return out
    }

    return encyclopediaSpells
        .filter { it.baseDamage != null }
        .sortedBy { it.id }
        .map { spell ->
            val rec = spellById[spell.id]
            val levelCap = (rec?.get("max_level") as? Int)?.coerceIn(1, MAX_CHARACTER_LEVEL) ?: MAX_CHARACTER_LEVEL
            val targetAction = spell.element?.let { ELEMENT_TO_DAMAGE_ACTION[it] }

            val normal = ArrayList<DmgEffect>()
            val critical = ArrayList<DmgEffect>()
            if (rec != null) {
                for (eid in subtree(intList(rec["effect_ids"]))) {
                    val e = byEffectId.getValue(eid)
                    val a = e["action_id"] as Int
                    if (a !in DAMAGE_ACTIONS) continue

                    @Suppress("UNCHECKED_CAST")
                    val p = (e["params"] as List<Any?>).map { (it as Float).toDouble() }
                    if (p.size < 2) continue
                    val bucket = if ((e["critical_state"] as String).trim() == "CRITICAL") critical else normal
                    bucket.add(DmgEffect(a, p[0], p[1]))
                }
            }

            // The effect whose floor(base + inc·levelCap) == the encyclopedia value is the base hit. Several
            // can tie (combo tiers flooring to the same number at max level); prefer the element's action so
            // the slope below max level is the real one.
            fun anchorMatch(
                candidates: List<DmgEffect>,
                target: Int,
            ): DmgEffect? {
                val matches = candidates.filter { floor(it.base + it.inc * levelCap + Spell.DAMAGE_FLOOR_EPSILON).toInt() == target }
                return matches.firstOrNull { it.action == targetAction } ?: matches.firstOrNull()
            }

            val normalMatch = anchorMatch(normal, spell.baseDamage!!)
            val critTarget = spell.critDamage ?: spell.baseDamage!!
            val critMatch = anchorMatch(critical, critTarget)

            // Anchor normal and crit INDEPENDENTLY on their encyclopedia values: an exact bdata slope when an
            // effect reproduces the value, else a linear approximation through it (`0 + value/levelCap·lvl`),
            // which is exact at levelCap. Either way `*DamageAt(levelCap)` == the encyclopedia value (no
            // max-level regression). `matched` reflects the NORMAL hit (the primary).
            SpellDamageScaling(
                spellId = spell.id,
                base = normalMatch?.base ?: 0.0,
                inc = normalMatch?.inc ?: (spell.baseDamage!!.toDouble() / levelCap),
                critBase = critMatch?.base ?: 0.0,
                critInc = critMatch?.inc ?: (critTarget.toDouble() / levelCap),
                levelCap = levelCap,
                matched = normalMatch != null
            )
        }
}
