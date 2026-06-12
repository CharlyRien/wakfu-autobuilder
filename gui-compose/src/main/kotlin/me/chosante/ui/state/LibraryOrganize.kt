package me.chosante.ui.state

import me.chosante.common.CharacterClass
import me.chosante.common.history.HistoryEntry
import me.chosante.ui.history.classDisplayName
import me.chosante.ui.history.restoredClass

// The library's filter + sort + group pipeline, kept as pure (non-Compose) functions so it can be
// unit-tested directly and the screen stays a thin renderer over its result.

/** One rendered section of the library. [clazz] is null for the single ungrouped section. */
data class LibraryGroup(
    val clazz: CharacterClass?,
    val builds: List<HistoryEntry>,
)

/** Display label for a class in the library (e.g. `Cra`), matching [HistoryEntry.classDisplayName]. */
fun CharacterClass.libraryLabel(): String = name.lowercase().replaceFirstChar { it.titlecase() }

/**
 * Filters, sorts and (optionally) groups [builds] for the library screen. Applied in order:
 * class filter → search → sort → group. The class filter keys on the restored enum (never the raw
 * stored string), so a build whose stored class name differs only in casing still matches. Search
 * matches the build name OR its class display name (case-insensitive substring).
 */
fun organizeLibrary(
    builds: List<HistoryEntry>,
    search: String,
    sort: LibrarySort,
    classFilter: CharacterClass?,
    groupByClass: Boolean,
    selectedTags: Set<String> = emptySet(),
    folder: LibraryFolderFilter = LibraryFolderFilter.All,
): List<LibraryGroup> {
    val query = search.trim()
    val filtered =
        builds
            .filter { entry ->
                when (folder) {
                    LibraryFolderFilter.All -> true
                    LibraryFolderFilter.Unfiled -> entry.folder == null
                    is LibraryFolderFilter.Named -> entry.folder == folder.name
                }
            }.filter { classFilter == null || it.restoredClass() == classFilter }
            // Tag filter is OR: with tags selected, keep builds carrying *any* of them. Empty = no filter.
            .filter { entry -> selectedTags.isEmpty() || entry.tags.any { it.lowercase() in selectedTags } }
            .filter { entry ->
                query.isBlank() ||
                    entry.name.contains(query, ignoreCase = true) ||
                    entry.classDisplayName().contains(query, ignoreCase = true) ||
                    entry.tags.any { it.contains(query, ignoreCase = true) }
            }
    val sorted = filtered.sortedWith(sort.comparator())
    if (!groupByClass) {
        return listOf(LibraryGroup(null, sorted))
    }
    return sorted
        .groupBy { it.restoredClass() }
        .map { (clazz, entries) -> LibraryGroup(clazz, entries) }
        .sortedBy { it.clazz!!.libraryLabel().lowercase() }
}

/** Comparator implementing each [LibrarySort] (see the plan §2 for the exact tie-breaks). */
private fun LibrarySort.comparator(): Comparator<HistoryEntry> =
    when (this) {
        LibrarySort.NEWEST -> compareByDescending { it.createdAt }
        LibrarySort.OLDEST -> compareBy { it.createdAt }
        LibrarySort.NAME -> compareBy<HistoryEntry> { it.name.lowercase() }.thenByDescending { it.createdAt }
        LibrarySort.LEVEL -> compareByDescending<HistoryEntry> { it.request.level }.thenByDescending { it.createdAt }
    }

/** Classes with ≥1 saved build and their counts, ordered by display label A–Z. */
fun classCounts(builds: List<HistoryEntry>): List<Pair<CharacterClass, Int>> =
    builds
        .groupingBy { it.restoredClass() }
        .eachCount()
        .toList()
        .sortedBy { it.first.libraryLabel().lowercase() }

/**
 * Distinct tags across [builds] with their counts, aggregated case-insensitively (first-seen casing
 * kept for display), ordered by display label A–Z. Drives the sidebar's tag filter section.
 */
fun tagCounts(builds: List<HistoryEntry>): List<Pair<String, Int>> {
    val displayByKey = LinkedHashMap<String, String>()
    val counts = LinkedHashMap<String, Int>()
    builds.forEach { entry ->
        entry.tags.forEach { tag ->
            val key = tag.lowercase()
            displayByKey.putIfAbsent(key, tag)
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    return counts.entries
        .map { (key, count) -> displayByKey.getValue(key) to count }
        .sortedBy { it.first.lowercase() }
}

/** Distinct non-null folders with their member counts, ordered by name A–Z (case-insensitive). */
fun folderCounts(builds: List<HistoryEntry>): List<Pair<String, Int>> =
    builds
        .mapNotNull { it.folder }
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedBy { it.first.lowercase() }

/** What the tag input offers for a query: matching known-but-unselected tags, and whether the typed
 * value can be created as a new tag. Pure so the (otherwise Compose-only) tag input is unit-testable. */
data class TagInputSuggestions(
    val suggestions: List<String>,
    val canCreate: Boolean,
)

fun tagInputSuggestions(
    known: List<String>,
    selected: List<String>,
    rawQuery: String,
): TagInputSuggestions {
    val query = rawQuery.trim()

    fun isSelected(tag: String) = selected.any { it.equals(tag, ignoreCase = true) }
    val suggestions = known.filter { !isSelected(it) && it.contains(query, ignoreCase = true) }
    val canCreate = query.isNotBlank() && known.none { it.equals(query, ignoreCase = true) } && !isSelected(query)
    return TagInputSuggestions(suggestions, canCreate)
}
