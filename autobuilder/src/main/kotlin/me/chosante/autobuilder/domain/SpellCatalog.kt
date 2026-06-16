package me.chosante.autobuilder.domain

import kotlinx.serialization.json.Json
import me.chosante.autobuilder.VERSION
import me.chosante.common.CharacterClass
import me.chosante.common.Spell
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
        this.javaClass.classLoader
            .getResourceAsStream("spells-v$VERSION.json")
            ?.readAllBytes()
            ?.let { Json.decodeFromString<List<Spell>>(String(it)) }
            ?: emptyList()
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
