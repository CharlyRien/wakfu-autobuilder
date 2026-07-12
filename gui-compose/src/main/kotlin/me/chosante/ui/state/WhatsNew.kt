package me.chosante.ui.state

import java.util.prefs.Preferences

/** Release notes of a single version, extracted from the embedded release-please CHANGELOG. */
data class ReleaseNotes(
    val version: String,
    val sections: List<ReleaseNotesSection>,
)

/** One `### Features` / `### Bug Fixes` group of a release, with its cleaned-up bullet lines. */
data class ReleaseNotesSection(
    val title: String,
    val items: List<String>,
)

/**
 * Once-per-version "What's new" gate. The release-please-maintained `CHANGELOG.md` is embedded as
 * a classpath resource at build time together with the app version (see
 * `gui-compose/build.gradle.kts`); the dialog shows on the first launch whose version differs from
 * the last one seen, then never again until the next release. Both resources are optional: until
 * the first release PR merges there is no changelog, and the dialog simply never shows.
 */
object WhatsNew {
    private const val KEY = "lastSeenChangelogVersion"

    /**
     * Ceiling on the number of releases stacked into one dialog. Also the safety net when the
     * last-seen version has no heading in the embedded changelog (corrupted pref, truncated file):
     * rather than greeting the user with the full history, the dialog shows at most this many.
     */
    internal const val MAX_VERSIONS_SHOWN = 10

    private val prefs: Preferences? = runCatching { Preferences.userRoot().node("me/chosante/wakfu-autobuilder") }.getOrNull()

    /** App version baked in by the build, or null when the resource is missing (unit tests, IDE). */
    val appVersion: String? by lazy {
        runCatching { resourceText("/app-version.txt")?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
    }

    /** Notes of the running version, or null when the changelog or its section doesn't exist. */
    val releaseNotes: ReleaseNotes? by lazy {
        val version = appVersion ?: return@lazy null
        runCatching { resourceText("/CHANGELOG.md") }.getOrNull()?.let { parseReleaseNotes(it, version) }
    }

    /**
     * Every release the user hasn't seen yet, newest first — so a 1.7 → 1.10 jumper reads the 1.8
     * and 1.9 notes too, not just the running version's. Empty when up to date, on a fresh install,
     * or when the changelog is missing.
     */
    fun unseenReleaseNotes(): List<ReleaseNotes> {
        val version = appVersion ?: return emptyList()
        val lastSeen = runCatching { prefs?.get(KEY, null) }.getOrNull() ?: return emptyList()
        if (lastSeen == version) return emptyList()
        val changelog = runCatching { resourceText("/CHANGELOG.md") }.getOrNull() ?: return emptyList()
        return parseReleaseNotesSince(changelog, currentVersion = version, lastSeenVersion = lastSeen, maxVersions = MAX_VERSIONS_SHOWN)
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
            ?.bufferedReader()
            ?.use { it.readText() }
}

private val releaseHeading = Regex("""^##\s+\[?v?(\d[^\]\s]*)]?.*""")
private val markdownLink = Regex("""\[([^\]]*)]\(([^)]*)\)""")
private val trailingCommitRef = Regex("""\s*\([0-9a-f]{7,40}\)\s*$""")

/**
 * Extracts the section of a release-please CHANGELOG for [version]. Release headings look like
 * `## [1.2.0](compare-url) (2026-07-01)` — or `## 1.2.0 (2026-07-01)` for a first release — with
 * `### Features` / `### Bug Fixes` subsections of `* bullet` lines. Returns null when the version
 * has no section or the section has no bullets.
 */
internal fun parseReleaseNotes(
    changelog: String,
    version: String,
): ReleaseNotes? {
    val lines = changelog.lines()
    val start = lines.indexOfFirst { releaseHeading.find(it)?.groupValues?.get(1) == version }
    if (start == -1) return null
    val body = lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
    val sections = parseSections(body)
    return if (sections.isEmpty()) null else ReleaseNotes(version, sections)
}

/**
 * Every release section from [currentVersion] down to — and excluding — [lastSeenVersion], newest
 * first, capped at [maxVersions]. Releases without bullets are skipped. When [currentVersion] has
 * no heading (a dev build newer than the embedded changelog), the walk starts at the newest release
 * instead; when [lastSeenVersion] has no heading, [maxVersions] is the only stop.
 */
internal fun parseReleaseNotesSince(
    changelog: String,
    currentVersion: String,
    lastSeenVersion: String,
    maxVersions: Int = WhatsNew.MAX_VERSIONS_SHOWN,
): List<ReleaseNotes> {
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
    if (headings.isEmpty()) return emptyList()

    val startAt = headings.indexOfFirst { (_, version) -> version == currentVersion }.coerceAtLeast(0)
    val notes = mutableListOf<ReleaseNotes>()
    for (headingIndex in startAt until headings.size) {
        val (lineIndex, version) = headings[headingIndex]
        if (version == lastSeenVersion || notes.size >= maxVersions) break
        val bodyEnd = headings.getOrNull(headingIndex + 1)?.first ?: lines.size
        val sections = parseSections(lines.subList(lineIndex + 1, bodyEnd))
        if (sections.isNotEmpty()) notes += ReleaseNotes(version, sections)
    }
    return notes
}

/** The `### title` / `* bullet` groups of one release's body lines (see [parseReleaseNotes]). */
private fun parseSections(body: List<String>): List<ReleaseNotesSection> {
    val sections = mutableListOf<ReleaseNotesSection>()
    var title = ""
    var items = mutableListOf<String>()

    fun flush() {
        if (items.isNotEmpty()) sections += ReleaseNotesSection(title, items.toList())
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
