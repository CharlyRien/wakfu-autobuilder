package me.chosante.bdataextractor

import me.chosante.common.I18nText
import java.io.File

/**
 * Localized strings decoded from the local client's i18n bundle (`contents/i18n/i18n_<lang>.jar` →
 * `texts_<lang>.properties`). Ankama keys every localizable string as `content.<namespace>.<id>`; the
 * monster pipeline uses namespace **7** (monster name) and **38** (family name), keyed by the relevant id.
 * Values are literal UTF-8 (no HTML entities, no `\u` escapes), so a plain `key=value` line split suffices —
 * we only strip a trailing CR so a CRLF bundle would not leak `\r` into names.
 *
 * This is the official-or-bdata name source that replaces the encyclopedia/MethodWakfu name scraping. Only
 * the requested [load] namespaces are retained in memory (the full bundle is multi-MB per language).
 */
class I18nBundle(
    private val byLang: Map<String, Map<String, String>>,
) {
    /** All four languages of `content.<namespace>.<id>`, or null if the FR entry is absent. */
    fun text(
        namespace: Int,
        id: Int,
    ): I18nText? {
        val key = "content.$namespace.$id"
        val fr = byLang.getValue("fr")[key] ?: return null
        return I18nText(
            fr = fr,
            en = byLang.getValue("en")[key] ?: fr,
            es = byLang.getValue("es")[key] ?: fr,
            pt = byLang.getValue("pt")[key] ?: fr
        )
    }

    companion object {
        val LANGS = listOf("fr", "en", "es", "pt")

        /**
         * Loads the four-language bundle, keeping only keys in the requested [namespaces] (e.g. 7 + 38 for
         * monsters) so we don't hold the entire game localization in memory. Each jar ships two property
         * files — `texts_<lang>.properties` (proper case) and `texts_<lang>_cleaned.properties` (lowercased) —
         * so we select the exact `texts_<lang>.properties` by name rather than the first `.properties` entry.
         */
        fun load(
            install: File,
            namespaces: Set<Int>,
        ): I18nBundle {
            val prefixes = namespaces.map { "content.$it." }
            return I18nBundle(
                LANGS.associateWith { lang ->
                    val jar = File(install, "contents/i18n/i18n_$lang.jar")
                    val bytes = readZipEntryBytes(jar) { it.substringAfterLast('/') == "texts_$lang.properties" }
                    buildMap {
                        for (raw in bytes.toString(Charsets.UTF_8).lineSequence()) {
                            val line = raw.removeSuffix("\r")
                            if (line.isEmpty() || line[0] == '#' || line[0] == '!') continue
                            if (prefixes.none { line.startsWith(it) }) continue
                            val eq = line.indexOf('=')
                            if (eq > 0) put(line.substring(0, eq), line.substring(eq + 1))
                        }
                    }
                }
            )
        }
    }
}
