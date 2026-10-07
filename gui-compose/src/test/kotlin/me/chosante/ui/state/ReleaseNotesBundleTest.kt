package me.chosante.ui.state

import me.chosante.ui.i18n.Lang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The player-facing notes compiled from `changes/` (release-notes.json): parsed into New / Fixes / Faster sections in
 * the UI language, preferred over the technical CHANGELOG for every version that has them, and walked with the same
 * "every release since the last one seen" rule as before.
 */
class ReleaseNotesBundleTest {
    private val bundle =
        """
        {
          "versions": [
            {
              "version": "1.13.0",
              "notes": [
                {"id": "a-feature", "type": "feat", "scope": null, "text": {"en": "A feature", "fr": "Une fonctionnalité"}},
                {"id": "b-feature", "type": "feat", "scope": "gui", "text": {"en": "B feature", "fr": "Fonctionnalité B"}},
                {"id": "cli-fix", "type": "fix", "scope": "cli", "text": {"en": "--flag fixed", "fr": "--flag corrigé", "es": "--flag corregido"}},
                {"id": "speed", "type": "perf", "scope": null, "text": {"en": "Faster badge", "fr": "Badge plus rapide"}}
              ]
            },
            {
              "version": "1.12.0",
              "notes": [
                {"id": "only-a-fix", "type": "fix", "scope": null, "text": {"en": "A fix"}}
              ]
            }
          ]
        }
        """.trimIndent()

    private val changelog =
        """
        # Changelog

        ## [1.14.0](https://example.com) (2026-12-01)

        ### Bug Fixes

        * an internal-only fix, released without a player-facing note

        ## [1.13.0](https://example.com) (2026-11-01)

        ### Features

        * technical commit subject that the notes replace

        ## [1.11.0](https://example.com) (2026-10-02)

        ### Features

        * legacy entry

        ## [1.10.0](https://example.com) (2026-07-12)

        ### Features

        * older legacy entry
        """.trimIndent()

    @Test
    fun `notes are grouped into New, Fixes and Faster, in the bundle order`() {
        val release = parseReleaseNotesBundle(bundle).first()

        assertThat(release.version).isEqualTo("1.13.0")
        assertThat(release.sections.map { it.type }).containsExactly(ChangeType.FEAT, ChangeType.FIX, ChangeType.PERF)
        assertThat(release.sections[0].items.map { it.text(Lang.EN) }).containsExactly("A feature", "B feature")
        assertThat(
            release.sections[1]
                .items
                .single()
                .scope
        ).isEqualTo("cli")
        assertThat(release.sections[0].items.map { it.scope }).containsExactly(null, "gui")
    }

    @Test
    fun `a note reads in the UI language and falls back to English when untranslated`() {
        val notes = parseReleaseNotesBundle(bundle)
        val feature = notes[0].sections[0].items[0]

        assertThat(feature.text(Lang.FR)).isEqualTo("Une fonctionnalité")
        assertThat(feature.text(Lang.EN)).isEqualTo("A feature")
        assertThat(
            notes[1]
                .sections
                .single()
                .items
                .single()
                .text(Lang.FR)
        ).describedAs("no FR text in the bundle: English stands in")
            .isEqualTo("A fix")
    }

    @Test
    fun `a malformed bundle yields nothing instead of failing`() {
        assertThat(parseReleaseNotesBundle("{ not json")).isEmpty()
        assertThat(parseReleaseNotesBundle("""{"versions": [{"version": "1.0.0"}]}""")).isEmpty()
    }

    @Test
    fun `versions with notes replace their changelog, which only covers the older history`() {
        val history = releaseHistory(changelog = changelog, notesBundle = bundle)

        assertThat(history.map { it.version })
            .describedAs("1.14.0 has no notes but is newer than the first noted version: internal-only, left out")
            .containsExactly("1.13.0", "1.12.0", "1.11.0", "1.10.0")
        assertThat(history[0].sections.map { it.type })
            .describedAs("1.13.0 shows its notes, not its changelog")
            .containsExactly(ChangeType.FEAT, ChangeType.FIX, ChangeType.PERF)
        assertThat(history[2].sections.single().title).isEqualTo("Features")
        assertThat(history[2].sections.single().type).isNull()
    }

    @Test
    fun `without a bundle the changelog is the whole history, as before`() {
        assertThat(releaseHistory(changelog = changelog, notesBundle = null).map { it.version })
            .containsExactly("1.14.0", "1.13.0", "1.11.0", "1.10.0")
        assertThat(releaseHistory(changelog = null, notesBundle = null)).isEmpty()
    }

    @Test
    fun `a version jump walks localized and changelog releases alike`() {
        val history = releaseHistory(changelog = changelog, notesBundle = bundle)

        assertThat(releasesSince(history, currentVersion = "1.13.0", lastSeenVersion = "1.10.0").map { it.version })
            .containsExactly("1.13.0", "1.12.0", "1.11.0")
        assertThat(releasesSince(history, currentVersion = "1.13.0", lastSeenVersion = "1.12.0").map { it.version })
            .containsExactly("1.13.0")
    }

    @Test
    fun `versions compare semantically`() {
        assertThat(compareVersions("1.10.0", "1.9.1")).isPositive()
        assertThat(compareVersions("1.12.0", "1.12.0")).isZero()
        assertThat(compareVersions("1.13.0-dev", "1.13.0")).isNegative()
        assertThat(compareVersions("2.0.0", "1.99.99")).isPositive()
        assertThat(compareVersions("garbage", "1.0.0")).isNull()
        assertThat(listOf("1.9.1", "1.12.0", "1.10.0").sortedWith(versionOrder)).containsExactly("1.9.1", "1.10.0", "1.12.0")
    }

    @Test
    fun `the bundled notes carry 1_12_0 in English and French, ahead of the 1_11_0 changelog`() {
        val bundled = resource("/release-notes.json")
        val release = parseReleaseNotesBundle(bundled).single { it.version == "1.12.0" }
        val byType = release.sections.associate { section -> section.type to section.items }

        // containsAll: a dev build also files the unreleased notes under the version being built.
        assertThat(byType.getValue(ChangeType.FEAT).map { it.text(Lang.EN) }).containsAll(
            listOf(
                "Max Damage: a search that keeps the default mastery row can now be upgraded to the proven best build",
                "Max Damage searches with the default targets now get a \"proven within X%\" badge",
                "Most Masteries searches with a Range target or targets set to 0 now get a \"proven within X%\" badge",
                "The optimality check that keeps running after a search now explains itself, and can be stopped or turned off",
                "The Most Masteries \"proven within X%\" badge is ready as soon as the search ends",
                "Tighter \"proven within X%\" badges in Most Masteries"
            )
        )
        assertThat(byType.getValue(ChangeType.FIX)).hasSizeGreaterThanOrEqualTo(4)
        assertThat(byType.getValue(ChangeType.FIX).single { it.scope == "cli" }.text(Lang.FR))
            .isEqualTo("--wp règle la cible de points Wakfu (PW), et non plus les points de mouvement (PM)")
        assertThat(byType.getValue(ChangeType.PERF).map { it.text(Lang.FR) })
            .contains("En Max maîtrises, le badge « Optimal prouvé à X% près » est calculé environ 7 fois plus vite")
        release.sections.flatMap { it.items }.forEach { line ->
            assertThat(line.text(Lang.FR)).describedAs("FR of: ${line.text(Lang.EN)}").isNotEqualTo(line.text(Lang.EN))
        }

        val history = releaseHistory(changelog = resource("/CHANGELOG.md"), notesBundle = bundled)
        assertThat(history.first { it.version == "1.12.0" }.sections.map { it.type }).doesNotContainNull()
        assertThat(history.first { it.version == "1.11.0" }.sections.map { it.title })
            .describedAs("the history before the notes keeps its CHANGELOG rendering")
            .contains("Features", "Bug Fixes")
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.getResourceAsStream(path)) { "$path is missing from the test classpath" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
}
