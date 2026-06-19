package me.chosante.ui.state

import me.chosante.ui.i18n.Lang
import java.util.prefs.Preferences

/**
 * Persists the *durable* UI options across launches — the library view options (sort order and
 * group-by-class) and the chosen UI language (the active search/class filters are deliberately
 * in-memory and reset each launch). Mirrors [WarmupTiming]'s Preferences pattern, but as an injectable
 * instance so tests can point it at a throwaway node. Every access is wrapped in `runCatching`: a prefs
 * failure must never break the UI.
 */
class LibraryPreferences(
    private val prefs: Preferences? =
        runCatching { Preferences.userRoot().node("me/chosante/wakfu-autobuilder") }.getOrNull(),
) {
    /** The UI language chosen last launch; defaults to English on first run or any read failure. */
    fun loadLang(): Lang = runCatching { Lang.valueOf(prefs?.get(KEY_LANG, "") ?: "") }.getOrDefault(Lang.EN)

    fun saveLang(lang: Lang) {
        runCatching { prefs?.put(KEY_LANG, lang.name) }
    }

    fun loadSort(): LibrarySort = runCatching { LibrarySort.valueOf(prefs?.get(KEY_SORT, "") ?: "") }.getOrDefault(LibrarySort.NEWEST)

    fun saveSort(sort: LibrarySort) {
        runCatching { prefs?.put(KEY_SORT, sort.name) }
    }

    fun loadGroupByClass(): Boolean = runCatching { prefs?.getBoolean(KEY_GROUP, false) }.getOrNull() ?: false

    fun saveGroupByClass(value: Boolean) {
        runCatching { prefs?.putBoolean(KEY_GROUP, value) }
    }

    /**
     * The persisted tag registry — tags exist as first-class, named entities independent of any build,
     * so removing a tag from its last build doesn't destroy it. Stored newline-joined (tag names are
     * single-line). Returns an empty list on any read failure or first launch.
     */
    fun loadTags(): List<String> =
        runCatching {
            prefs
                ?.get(KEY_TAGS, "")
                ?.split("\n")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
        }.getOrNull() ?: emptyList()

    fun saveTags(tags: List<String>) {
        runCatching { prefs?.put(KEY_TAGS, tags.joinToString("\n")) }
    }

    private companion object {
        const val KEY_LANG = "language"
        const val KEY_SORT = "librarySort"
        const val KEY_GROUP = "libraryGroupByClass"
        const val KEY_TAGS = "knownTags"
    }
}
