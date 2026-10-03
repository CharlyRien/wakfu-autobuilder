package me.chosante.ui.state

import me.chosante.ui.i18n.Lang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WhatsNewParserTest {
    private val changelog =
        """
        # Changelog

        ## [1.2.0](https://github.com/CharlyRien/wakfu-autobuilder/compare/1.1.0...1.2.0) (2026-07-01)


        ### Features

        * **gui:** add a what's new dialog ([#140](https://github.com/CharlyRien/wakfu-autobuilder/issues/140)) ([abc1234](https://github.com/CharlyRien/wakfu-autobuilder/commit/abc1234))
        * **engine:** speed up warm-up on a wrapped
          continuation line ([def5678](https://github.com/CharlyRien/wakfu-autobuilder/commit/def5678))

        ### Bug Fixes

        * fix the priority bar drag ([0a1b2c3](https://github.com/CharlyRien/wakfu-autobuilder/commit/0a1b2c3))

        ## 1.1.0 (2026-06-09)

        ### Features

        * older release entry ([1234567](https://github.com/CharlyRien/wakfu-autobuilder/commit/1234567))
        """.trimIndent()

    private fun ReleaseNotesSection.lines() = items.map { it.text(Lang.EN) }

    @Test
    fun `extracts the requested version with cleaned bullets`() {
        val notes = parseChangelog(changelog).single { it.version == "1.2.0" }

        assertThat(notes.sections.map { it.title }).containsExactly("Features", "Bug Fixes")
        assertThat(notes.sections.map { it.type }).describedAs("CHANGELOG sections are untyped").containsOnlyNulls()
        assertThat(notes.sections[0].lines())
            .containsExactly(
                // Markdown links resolve to their text, bold markers and trailing commit hashes
                // are stripped, wrapped bullets are folded back into one line.
                "gui: add a what's new dialog (#140)",
                "engine: speed up warm-up on a wrapped continuation line"
            )
        assertThat(notes.sections[1].lines()).containsExactly("fix the priority bar drag")
        assertThat(
            notes.sections[1]
                .items
                .single()
                .text(Lang.FR)
        ).describedAs("the CHANGELOG is English-only, so every language falls back to it")
            .isEqualTo("fix the priority bar drag")
    }

    @Test
    fun `stops at the next release and parses unlinked headings`() {
        val notes = parseChangelog(changelog).single { it.version == "1.1.0" }

        assertThat(notes.sections).hasSize(1)
        assertThat(notes.sections[0].lines()).containsExactly("older release entry")
    }

    @Test
    fun `lists every release newest first`() {
        assertThat(parseChangelog(changelog).map { it.version }).containsExactly("1.2.0", "1.1.0")
    }

    @Test
    fun `a version jump collects every release down to the last seen one, exclusive`() {
        val jump =
            """
            # Changelog

            ## [1.10.0](https://example.com) (2026-07-12)

            ### Bug Fixes

            * newest fix

            ## [1.9.1](https://example.com) (2026-07-11)

            ### Features

            * middle feature

            ## [1.9.0](https://example.com) (2026-07-10)

            ### Features

            * older feature

            ## [1.7.0](https://example.com) (2026-06-01)

            ### Features

            * already seen
            """.trimIndent()

        val notes = releasesSince(parseChangelog(jump), currentVersion = "1.10.0", lastSeenVersion = "1.7.0")

        assertThat(notes.map { it.version })
            .describedAs("newest first, stops BEFORE the last seen version")
            .containsExactly("1.10.0", "1.9.1", "1.9.0")
        assertThat(
            notes
                .first()
                .sections
                .single()
                .lines()
        ).containsExactly("newest fix")
    }

    @Test
    fun `an up-to-date user gets nothing and a bullet-less release is skipped`() {
        val withEmptyRelease =
            """
            ## [1.3.0](https://example.com) (2026-08-01)

            ### Features

            * new stuff

            ## [1.2.5](https://example.com) (2026-07-20)

            ## [1.2.0](https://example.com) (2026-07-01)

            ### Features

            * old stuff
            """.trimIndent()

        val history = parseChangelog(withEmptyRelease)
        assertThat(releasesSince(history, "1.3.0", lastSeenVersion = "1.3.0")).isEmpty()
        assertThat(releasesSince(history, "1.3.0", lastSeenVersion = "1.2.0").map { it.version })
            .describedAs("the bullet-less 1.2.5 is skipped, 1.2.0 (seen) excluded")
            .containsExactly("1.3.0")
        assertThat(releasesSince(history, "1.3.0", lastSeenVersion = "1.2.5").map { it.version })
            .describedAs("a bullet-less last-seen release still stops the walk: 1.2.0 is older than it")
            .containsExactly("1.3.0")
    }

    @Test
    fun `an unknown last-seen version is capped instead of dumping the full history`() {
        val many =
            parseChangelog(
                (20 downTo 1).joinToString("\n\n") { minor ->
                    "## [1.$minor.0](https://example.com) (2026-01-01)\n\n### Features\n\n* entry $minor"
                }
            )

        for (lastSeen in listOf("0.0.1", "not-a-version")) {
            val notes = releasesSince(many, currentVersion = "1.20.0", lastSeenVersion = lastSeen, maxVersions = 3)
            assertThat(notes.map { it.version }).describedAs(lastSeen).containsExactly("1.20.0", "1.19.0", "1.18.0")
        }
    }

    @Test
    fun `a dev build newer than the changelog starts at the newest release`() {
        val notes = releasesSince(parseChangelog(changelog), currentVersion = "1.3.0-dev", lastSeenVersion = "1.1.0")
        assertThat(notes.map { it.version }).containsExactly("1.2.0")
    }

    @Test
    fun `a downgrade shows nothing rather than releases the user already had`() {
        assertThat(releasesSince(parseChangelog(changelog), currentVersion = "1.1.0", lastSeenVersion = "1.2.0")).isEmpty()
    }

    @Test
    fun `a release without bullets is left out`() {
        val empty =
            """
            # Changelog

            ## [1.3.0](https://example.com) (2026-08-01)

            ## [1.2.0](https://example.com) (2026-07-01)

            ### Features

            * something
            """.trimIndent()
        assertThat(parseChangelog(empty).map { it.version }).containsExactly("1.2.0")
    }
}
