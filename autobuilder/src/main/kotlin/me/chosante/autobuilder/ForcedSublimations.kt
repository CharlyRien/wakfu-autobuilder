package me.chosante.autobuilder

import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Sublimation

/** Optional :level suffix; names without it retain the max-level default, including old renamed names. */
internal fun parseForcedSublimations(
    tokens: List<String>,
    catalog: List<Sublimation> = WakfuBestBuildFinderAlgorithm.sublimations,
): Pair<List<String>, Map<String, Int>> {
    val levels = linkedMapOf<String, Int>()
    val names =
        tokens
            .map { token ->
                val name = WakfuBestBuildFinderAlgorithm.canonicalSublimationName(token.substringBeforeLast(':', token).trim())
                if (':' in token) {
                    val level = token.substringAfterLast(':').trim().toIntOrNull()
                    require(level != null) { "Invalid sublimation level in '$token'; use 'Neutralité III:2'." }
                    val sub = catalog.firstOrNull { it.name.fr.equals(name, true) || it.name.en.equals(name, true) }
                    require(sub != null) { "Unknown sublimation '$name'." }
                    require(level in sub.reachableLevels()) { "${sub.name.fr}: level $level is unavailable; choose ${sub.reachableLevels().joinToString()}." }
                    val canonical = sub.name.fr
                    require(levels[canonical] == null || levels[canonical] == level) { "Conflicting forced levels for '$canonical'." }
                    levels[canonical] = level
                    canonical
                } else {
                    name
                }
            }.distinct()
    return names to levels
}
