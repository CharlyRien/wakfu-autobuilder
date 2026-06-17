package me.chosante.autobuilder.domain

import me.chosante.autobuilder.EmbeddedResources
import me.chosante.autobuilder.VERSION
import me.chosante.common.CharacterClass
import me.chosante.common.Passive

/**
 * The embedded class-passive dataset (`spell-passives-v<VERSION>.json`, produced by `bdata-extractor`),
 * loaded the same lazy way as the equipments / spells so merely referencing the object never triggers the
 * parse on the UI thread. Decoded leniently — the runtime [Passive] model reads only the fields it needs.
 *
 * A player slots a limited number of passives ([slotsForLevel]); the solver may auto-pick from the
 * [choosable] (fully-declarative, flat-stat) ones, and the user may force any of [forClass] into the build.
 */
object PassiveCatalog {
    val passives: List<Passive> by lazy {
        EmbeddedResources.decodeList<Passive>("spell-passives-v$VERSION.json", EmbeddedResources.lenientJson).orEmpty()
    }

    private val byClass: Map<CharacterClass, List<Passive>> by lazy { passives.groupBy { it.characterClass } }

    /** Every passive of [clazz] (the player's full passive pool to pick a loadout from). */
    fun forClass(clazz: CharacterClass): List<Passive> = byClass[clazz] ?: emptyList()

    /** The passives of [clazz] the solver may auto-pick — fully declarative with a flat stat bonus. */
    fun choosable(clazz: CharacterClass): List<Passive> = forClass(clazz).filter { it.solverChoosable }

    /** Resolve a forced passive by (case-insensitive) French name within [clazz]; null if no match. */
    fun findByName(
        clazz: CharacterClass,
        name: String,
    ): Passive? = forClass(clazz).firstOrNull { it.name?.equals(name, ignoreCase = true) == true }

    /**
     * Passive slots unlocked at [level]. Wakfu grants a new slot at levels 10 / 30 / 50 / 100 / 150 / 200,
     * so a character has between 0 (below 10) and [MAX_SLOTS] (= 6, at 200) passive slots.
     */
    fun slotsForLevel(level: Int): Int = SLOT_UNLOCK_LEVELS.count { level >= it }

    /** Character levels at which a passive slot unlocks (ascending). */
    val SLOT_UNLOCK_LEVELS = intArrayOf(10, 30, 50, 100, 150, 200)
    const val MAX_SLOTS = 6
}
