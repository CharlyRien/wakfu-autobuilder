package me.chosante.ui.components

import me.chosante.common.Monster
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.localizedCollator

/** The boss's name as the player reads it: in the language of the app (the other language if that one is missing). */
internal fun Monster.displayName(lang: Lang): String = name.localized(lang)

/**
 * The boss's family ("Monks", "Moines"…) in the language of the app, or null when it has none — or when it only repeats the
 * boss's own name (16 bosses are their own family: "Excarnus" / "Excarnus"), which would read as noise under the name.
 */
internal fun Monster.displayFamily(lang: Lang): String? =
    family
        ?.localized(lang)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals(displayName(lang).trim(), ignoreCase = true) }

/**
 * The bosses the picker offers, in the order it lists them: boss-tier monsters only (rank ≥ 1 — bosses, golems, "Dominant"
 * variants; the ~2 600 regular creatures of the bestiary are hidden), sorted by the name AS DISPLAYED in [lang].
 *
 * Several bosses can share a name when a dungeon boss exists at more than one level ("Cire Momore" at levels 58, 73 and 233: three
 * distinct monsters with their own life and resistances, not duplicates). They are listed together, lowest level first, and
 * the picker row shows each one's level.
 */
internal fun bossRoster(
    monsters: List<Monster>,
    lang: Lang,
): List<Monster> {
    val collator = localizedCollator(lang)
    return monsters
        .filter { it.isBoss }
        .sortedWith(
            Comparator<Monster> { left, right -> collator.compare(left.displayName(lang).trim(), right.displayName(lang).trim()) }
                .thenBy { it.level }
                .thenBy { it.id }
        )
}

/** Whether what the player typed matches the boss's name, in either language (so "Bus" finds a boss in an FR app too). */
internal fun Monster.matchesQuery(query: String): Boolean {
    val needle = query.trim()
    return needle.isEmpty() ||
        name.fr.contains(needle, ignoreCase = true) ||
        name.en.contains(needle, ignoreCase = true) ||
        name.es.contains(needle, ignoreCase = true) ||
        name.pt.contains(needle, ignoreCase = true)
}
