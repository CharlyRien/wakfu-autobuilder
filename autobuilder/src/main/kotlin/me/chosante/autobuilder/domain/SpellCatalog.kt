package me.chosante.autobuilder.domain

import me.chosante.autobuilder.EmbeddedResources
import me.chosante.common.CharacterClass
import me.chosante.common.Spell
import me.chosante.common.SpellCastLimit
import me.chosante.common.SpellDamageScaling
import me.chosante.common.SpellElement
import me.chosante.common.SpellLocalization

/**
 * The embedded class-spell dataset (`spells.json`, produced by the `spells-extractor` module from the
 * Ankama encyclopedia), loaded the same lazy way as equipments / runes so merely referencing the object
 * never triggers the multi-hundred-KB parse on the UI thread. Two bdata-sourced side tables are joined by
 * spell id at load: the cast limits (`spell-cast-limits.json`) and the **per-level damage formula**
 * (`spell-damage.json`) — the latter lets `SpellDamage` scale a hit to the caster's level instead of using
 * the encyclopedia's max-level snapshot.
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
        // Join the per-level damage formula (bdata, produced by bdata-extractor) so SpellDamage scales the
        // hit to the caster's level instead of using the encyclopedia's max-level snapshot. A spell with no
        // record keeps its flat baseDamage (the old behaviour) — safe by construction.
        val damageScalingBySpellId =
            EmbeddedResources
                .decodeList<SpellDamageScaling>("spell-damage.json")
                .orEmpty()
                .associateBy { it.spellId }
        // Join the per-spell localized name + description (bdata i18n, all four languages) so the GUI shows
        // translated spell text; the encyclopedia scrape only carried English descriptions. A spell with no
        // record keeps its encyclopedia text — safe by construction.
        val localizationBySpellId =
            EmbeddedResources
                .decodeList<SpellLocalization>("spell-i18n.json")
                .orEmpty()
                .associateBy { it.spellId }
        base.map { spell ->
            val limit = castLimitsBySpellId[spell.id]
            val scaling = damageScalingBySpellId[spell.id]
            val localization = localizationBySpellId[spell.id]
            if (limit == null && scaling == null && localization == null) return@map spell
            spell.copy(
                name = localization?.name ?: spell.name,
                description = localization?.description ?: spell.description,
                maxCastPerTurn = limit?.maxCastPerTurn,
                maxCastPerTarget = limit?.maxCastPerTarget,
                cooldown = limit?.cooldown,
                wpCost = limit?.wpCost,
                damageScaling = scaling
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
