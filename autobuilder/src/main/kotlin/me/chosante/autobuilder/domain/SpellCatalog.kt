package me.chosante.autobuilder.domain

import me.chosante.autobuilder.EmbeddedResources
import me.chosante.common.CharacterClass
import me.chosante.common.Spell
import me.chosante.common.SpellCastLimit
import me.chosante.common.SpellElement

/**
 * The embedded class-spell dataset (`spells-v<VERSION>.json`, produced by the `spells-extractor`
 * module from the Ankama encyclopedia), loaded the same lazy way as equipments / runes so merely
 * referencing the object never triggers the multi-hundred-KB parse on the UI thread.
 *
 * Beyond exposing the spells, it answers the question that motivated the dataset
 * (`docs/SPELLS_AND_COMBO_RESEARCH.md`): **which elements a class can actually play** — derived from
 * the real damage spells the class owns, not a hand-curated table. This is the gate the max-damage /
 * boss auto-element search should consult so it never recommends an element the class has no spells in
 * (the "tell a Cra to play Water" bug).
 */
object SpellCatalog {
    val spells: List<Spell> by lazy {
        val base = EmbeddedResources.decodeList<Spell>("spells.json") ?: emptyList()
        // Join the baked per-spell cast limits (produced by bdata-extractor, see
        // docs/SPELL_CAST_LIMITS_EXTRACTION.md) onto each spell by id. The binary id space == the
        // encyclopedia id space, so the join is direct; a spell with no matching record keeps its null
        // (unbounded) limits, which the rotation optimizer treats as "no cap" — safe by construction.
        val castLimitsBySpellId =
            EmbeddedResources
                .decodeList<SpellCastLimit>("spell-cast-limits.json")
                .orEmpty()
                .associateBy { it.spellId }
        base.map { spell ->
            val limit = castLimitsBySpellId[spell.id] ?: return@map spell
            spell.copy(
                maxCastPerTurn = limit.maxCastPerTurn,
                maxCastPerTarget = limit.maxCastPerTarget,
                cooldown = limit.cooldown,
                wpCost = limit.wpCost
            )
        }
    }

    private val byClass: Map<CharacterClass, List<Spell>> by lazy { spells.groupBy { it.clazz } }

    /** Every spell of [clazz] (active + passive, damage + utility). */
    fun forClass(clazz: CharacterClass): List<Spell> = byClass[clazz] ?: emptyList()

    /** The damage spells of [clazz] (those with a readable element + base hit). */
    fun damageSpells(clazz: CharacterClass): List<Spell> = forClass(clazz).filter { it.hasDamage }

    /**
     * The elements [clazz] can actually deal damage in, derived from its real spell kit. Empty only for
     * classes with no readable damage spells (e.g. the dataset couldn't be loaded). Use this to gate the
     * boss / max-damage auto-element search to *playable* elements.
     */
    fun playableElements(clazz: CharacterClass): Set<SpellElement> = damageSpells(clazz).mapNotNull { it.element }.toSortedSet()
}
