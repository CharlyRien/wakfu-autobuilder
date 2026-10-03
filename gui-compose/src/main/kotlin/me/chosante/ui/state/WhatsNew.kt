package me.chosante.ui.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.chosante.ui.i18n.Lang
import java.util.prefs.Preferences

/** Release notes of a single version. */
data class ReleaseNotes(
    val version: String,
    val sections: List<ReleaseNotesSection>,
)

/**
 * One group of a release's notes. A release with player-facing notes has typed New / Fixes / Faster sections
 * ([type] set, heading localized by the dialog); an older release (≤ 1.11, before those notes existed) keeps its
 * release-please CHANGELOG groups, whose raw `### Features`-style [title] the dialog translates when it knows it.
 */
data class ReleaseNotesSection(
    val title: String,
    val items: List<ReleaseNoteLine>,
    val type: ChangeType? = null,
)

/** The kind of a player-facing note (its `type`), in display order. */
enum class ChangeType(
    val key: String,
) {
    FEAT("feat"),
    FIX("fix"),
    PERF("perf"),
}

/**
 * One bullet. [translations] maps a language code ("en", "fr", "es"…) to the text; English is always present and
 * stands in for a language the note has no translation for (and is the only one a CHANGELOG line has). [scope] marks a
 * change limited to one front-end ("cli", "gui").
 */
data class ReleaseNoteLine(
    val translations: Map<String, String>,
    val scope: String? = null,
) {
    fun text(lang: Lang): String = translations[lang.name.lowercase()] ?: translations["en"] ?: translations.values.first()
}

/**
 * Once-per-version "What's new" gate. Two resources are embedded at build time together with the app version
 * (see `gui-compose/build.gradle.kts`): `release-notes.json`, the player-facing notes compiled from `changes/`
 * (EN + FR, shown in the UI language), and the release-please `CHANGELOG.md`, the English fallback for the releases
 * that predate those notes. The dialog shows on the first launch whose version differs from the last one seen, then
 * never again until the next release. Every resource is optional: without notes the dialog simply never shows.
 */
object WhatsNew {
    private const val KEY = "lastSeenChangelogVersion"

    /**
     * Ceiling on the number of releases stacked into one dialog. Also the safety net when the
     * last-seen version can't be compared (corrupted pref): rather than greeting the user with the
     * full history, the dialog shows at most this many.
     */
    internal const val MAX_VERSIONS_SHOWN = 10

    private val prefs: Preferences? = runCatching { Preferences.userRoot().node("me/chosante/wakfu-autobuilder") }.getOrNull()

    /** App version baked in by the build, or null when the resource is missing (unit tests, IDE). */
    val appVersion: String? by lazy {
        runCatching { resourceText("/app-version.txt")?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
    }

    /** Every release with something to show, newest first (see [releaseHistory]). */
    private val history: List<ReleaseNotes> by lazy {
        releaseHistory(
            changelog = runCatching { resourceText("/CHANGELOG.md") }.getOrNull(),
            notesBundle = runCatching { resourceText("/release-notes.json") }.getOrNull()
        )
    }

    /** Notes of the running version, or null when it has none. */
    val releaseNotes: ReleaseNotes? by lazy {
        val version = appVersion ?: return@lazy null
        history.firstOrNull { it.version == version }
    }

    /**
     * Every release the user hasn't seen yet, newest first — so a 1.7 → 1.10 jumper reads the 1.8
     * and 1.9 notes too, not just the running version's. Empty when up to date, on a fresh install,
     * or when there are no notes.
     */
    fun unseenReleaseNotes(): List<ReleaseNotes> {
        val version = appVersion ?: return emptyList()
        val lastSeen = runCatching { prefs?.get(KEY, null) }.getOrNull() ?: return emptyList()
        if (lastSeen == version) return emptyList()
        return releasesSince(history, currentVersion = version, lastSeenVersion = lastSeen, maxVersions = MAX_VERSIONS_SHOWN)
    }

    /**
     * True when there are unseen release notes. A machine with no recorded version is a fresh
     * install, not an update — it records the baseline silently instead of greeting a new user
     * with a changelog.
     */
    fun shouldShow(): Boolean {
        val version = appVersion ?: return false
        val lastSeen = runCatching { prefs?.get(KEY, null) }.getOrNull()
        if (lastSeen == null) {
            markSeen()
            return false
        }
        return lastSeen != version && unseenReleaseNotes().isNotEmpty()
    }

    fun markSeen() {
        appVersion?.let { version -> runCatching { prefs?.put(KEY, version) } }
    }

    private fun resourceText(path: String): String? =
        WhatsNew::class.java
            .getResourceAsStream(path)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
}

/**
 * Every release with notes to show, newest first. A version that has player-facing notes ([notesBundle], compiled from
 * `changes/` by the `generateReleaseNotes` Gradle task) shows them; the English release-please [changelog] covers only
 * the history BEFORE the first such version (≤ 1.11). A later version without notes — an internal-only release — has
 * nothing for players and is left out rather than falling back to its technical changelog.
 */
internal fun releaseHistory(
    changelog: String?,
    notesBundle: String?,
): List<ReleaseNotes> {
    val localized = notesBundle?.let(::parseReleaseNotesBundle).orEmpty()
    val firstLocalized = localized.map { it.version }.minWithOrNull(versionOrder)
    val legacy =
        changelog
            ?.let(::parseChangelog)
            .orEmpty()
            .filter { firstLocalized == null || versionOrder.compare(it.version, firstLocalized) < 0 }
    return (localized + legacy).sortedWith(compareByDescending(versionOrder) { it.version })
}

/**
 * The releases a user updating from [lastSeenVersion] hasn't seen, newest first: from [currentVersion] — or the newest
 * release not newer than it, for a dev build ahead of its notes — down to, and excluding, [lastSeenVersion] or anything
 * older, capped at [maxVersions]. The cap is the only stop when [lastSeenVersion] isn't a version (corrupted pref).
 */
internal fun releasesSince(
    history: List<ReleaseNotes>,
    currentVersion: String,
    lastSeenVersion: String,
    maxVersions: Int = WhatsNew.MAX_VERSIONS_SHOWN,
): List<ReleaseNotes> {
    val start =
        history
            .indexOfFirst { it.version == currentVersion || (compareVersions(it.version, currentVersion) ?: 1) <= 0 }
            .coerceAtLeast(0)
    return history
        .drop(start)
        .takeWhile { it.version != lastSeenVersion && (compareVersions(it.version, lastSeenVersion) ?: 1) > 0 }
        .take(maxVersions)
}

private val semanticVersion = Regex("""(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""")

/**
 * Semantic-version comparison: numeric parts compare as numbers ("1.10.0" > "1.9.1") and a pre-release sorts below its
 * release ("1.13.0-dev" < "1.13.0"). Null when either side isn't a version.
 */
internal fun compareVersions(
    a: String,
    b: String,
): Int? {
    val left = semanticVersion.matchEntire(a)?.groupValues ?: return null
    val right = semanticVersion.matchEntire(b)?.groupValues ?: return null
    for (part in 1..3) {
        val byPart = left[part].toInt().compareTo(right[part].toInt())
        if (byPart != 0) return byPart
    }
    return when {
        left[4] == right[4] -> 0
        left[4].isEmpty() -> 1
        right[4].isEmpty() -> -1
        else -> left[4].compareTo(right[4])
    }
}

/** Total order for sorting: [compareVersions], with a plain text comparison for anything that isn't a version. */
internal val versionOrder: Comparator<String> = Comparator { a, b -> compareVersions(a, b) ?: a.compareTo(b) }

/**
 * Parses `release-notes.json`, written by the `generateReleaseNotes` Gradle task from the `changes/` notes:
 * `{"versions": [{"version": "1.12.0", "notes": [{"id", "type", "scope", "text": {"en": …, "fr": …}}]}]}`, already
 * ordered. Each version's notes are grouped into New / Fixes / Faster sections. A malformed bundle yields nothing, so
 * the dialog falls back to the CHANGELOG instead of failing.
 */
internal fun parseReleaseNotesBundle(json: String): List<ReleaseNotes> =
    runCatching {
        Json.parseToJsonElement(json).jsonObject.getValue("versions").jsonArray.mapNotNull { element ->
            val release = element.jsonObject
            val notes = release.getValue("notes").jsonArray.map { it.jsonObject }
            val sections =
                ChangeType.entries.mapNotNull { type ->
                    val items =
                        notes
                            .filter { it["type"]?.jsonPrimitive?.content == type.key }
                            .map { note ->
                                ReleaseNoteLine(
                                    translations = note.getValue("text").jsonObject.mapValues { it.value.jsonPrimitive.content },
                                    scope = note["scope"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                                )
                            }
                    if (items.isEmpty()) null else ReleaseNotesSection(title = type.key, items = items, type = type)
                }
            if (sections.isEmpty()) null else ReleaseNotes(release.getValue("version").jsonPrimitive.content, sections)
        }
    }.getOrDefault(emptyList())

private val releaseHeading = Regex("""^##\s+\[?v?(\d[^\]\s]*)]?.*""")
private val markdownLink = Regex("""\[([^\]]*)]\(([^)]*)\)""")
private val trailingCommitRef = Regex("""\s*\([0-9a-f]{7,40}\)\s*$""")

/**
 * Every release of a release-please CHANGELOG that has bullets, in file order (newest first). Release headings look
 * like `## [1.2.0](compare-url) (2026-07-01)` — or `## 1.2.0 (2026-07-01)` for a first release — with
 * `### Features` / `### Bug Fixes` subsections of `* bullet` lines.
 */
internal fun parseChangelog(changelog: String): List<ReleaseNotes> {
    val lines = changelog.lines()
    val headings =
        lines
            .withIndex()
            .mapNotNull { (index, line) ->
                releaseHeading
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.let { index to it }
            }
    return headings.mapIndexedNotNull { headingIndex, (lineIndex, version) ->
        val bodyEnd = headings.getOrNull(headingIndex + 1)?.first ?: lines.size
        val sections = parseSections(lines.subList(lineIndex + 1, bodyEnd))
        if (sections.isEmpty()) null else ReleaseNotes(version, sections)
    }
}

/** The `### title` / `* bullet` groups of one release's body lines (see [parseChangelog]). */
private fun parseSections(body: List<String>): List<ReleaseNotesSection> {
    val sections = mutableListOf<ReleaseNotesSection>()
    var title = ""
    var items = mutableListOf<String>()

    fun flush() {
        if (items.isNotEmpty()) sections += ReleaseNotesSection(title, items.map { ReleaseNoteLine(mapOf("en" to it)) })
        items = mutableListOf()
    }

    for (line in body) {
        val trimmed = line.trim()
        when {
            trimmed.startsWith("### ") -> {
                flush()
                title = trimmed.removePrefix("### ").trim()
            }

            trimmed.startsWith("* ") || trimmed.startsWith("- ") -> items += cleanBullet(trimmed.drop(2))

            // Wrapped bullet: an indented continuation line is folded into the previous item.
            line.startsWith("  ") && trimmed.isNotEmpty() && items.isNotEmpty() ->
                items[items.lastIndex] = items.last() + " " + cleanBullet(trimmed)
        }
    }
    flush()
    return sections
}

/** `**gui:** stuff ([#140](url)) ([abc1234](url))` → `gui: stuff (#140)`. */
private fun cleanBullet(raw: String): String {
    var text = markdownLink.replace(raw) { it.groupValues[1] }.replace("**", "")
    while (true) {
        val stripped = trailingCommitRef.replace(text, "")
        if (stripped == text) break
        text = stripped
    }
    return text.trim()
}
