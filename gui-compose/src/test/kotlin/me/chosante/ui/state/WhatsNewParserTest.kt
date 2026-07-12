package me.chosante.ui.state

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

    @Test
    fun `extracts the requested version with cleaned bullets`() {
        val notes = parseReleaseNotes(changelog, "1.2.0")

        assertThat(notes).isNotNull
        assertThat(notes!!.version).isEqualTo("1.2.0")
        assertThat(notes.sections.map { it.title }).containsExactly("Features", "Bug Fixes")
        assertThat(notes.sections[0].items)
            .containsExactly(
                // Markdown links resolve to their text, bold markers and trailing commit hashes
                // are stripped, wrapped bullets are folded back into one line.
                "gui: add a what's new dialog (#140)",
                "engine: speed up warm-up on a wrapped continuation line"
            )
        assertThat(notes.sections[1].items).containsExactly("fix the priority bar drag")
    }

    @Test
    fun `stops at the next release and parses unlinked headings`() {
        val notes = parseReleaseNotes(changelog, "1.1.0")

        assertThat(notes).isNotNull
        assertThat(notes!!.sections).hasSize(1)
        assertThat(notes.sections[0].items).containsExactly("older release entry")
    }

    @Test
    fun `returns null for an unknown version`() {
        assertThat(parseReleaseNotes(changelog, "9.9.9")).isNull()
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

        val notes = parseReleaseNotesSince(jump, currentVersion = "1.10.0", lastSeenVersion = "1.7.0")

        assertThat(notes.map { it.version })
            .describedAs("newest first, stops BEFORE the last seen version")
            .containsExactly("1.10.0", "1.9.1", "1.9.0")
        assertThat(
            notes
                .first()
                .sections
                .single()
                .items
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

        assertThat(parseReleaseNotesSince(withEmptyRelease, "1.3.0", lastSeenVersion = "1.3.0")).isEmpty()
        assertThat(parseReleaseNotesSince(withEmptyRelease, "1.3.0", lastSeenVersion = "1.2.0").map { it.version })
            .describedAs("the bullet-less 1.2.5 is skipped, 1.2.0 (seen) excluded")
            .containsExactly("1.3.0")
    }

    @Test
    fun `an unknown last-seen version is capped instead of dumping the full history`() {
        val many =
            (20 downTo 1).joinToString("\n\n") { minor ->
                "## [1.$minor.0](https://example.com) (2026-01-01)\n\n### Features\n\n* entry $minor"
            }

        val notes = parseReleaseNotesSince(many, currentVersion = "1.20.0", lastSeenVersion = "0.0.1", maxVersions = 3)
        assertThat(notes.map { it.version }).containsExactly("1.20.0", "1.19.0", "1.18.0")
    }

    @Test
    fun `a dev build newer than the changelog starts at the newest release`() {
        val notes = parseReleaseNotesSince(changelog, currentVersion = "1.3.0-dev", lastSeenVersion = "1.1.0")
        assertThat(notes.map { it.version }).containsExactly("1.2.0")
    }

    @Test
    fun `returns null when the section has no bullets`() {
        val empty =
            """
            # Changelog

            ## [1.3.0](https://example.com) (2026-08-01)

            ## [1.2.0](https://example.com) (2026-07-01)

            ### Features

            * something
            """.trimIndent()
        assertThat(parseReleaseNotes(empty, "1.3.0")).isNull()
    }
}
