package me.chosante.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.state.ChangeType
import me.chosante.ui.state.ReleaseNoteLine
import me.chosante.ui.state.ReleaseNotes
import me.chosante.ui.state.ReleaseNotesSection
import org.junit.jupiter.api.Test

/**
 * The What's new dialog speaks the UI language for releases with player-facing notes (New / Fixes / Faster, the
 * note's own translation, a command-line label for CLI-only notes) and keeps the English CHANGELOG bullets, under
 * translated release-please headings, for the older releases.
 */
@OptIn(ExperimentalTestApi::class)
class WhatsNewDialogUiTest {
    private fun line(
        en: String,
        fr: String? = null,
        scope: String? = null,
    ) = ReleaseNoteLine(listOfNotNull("en" to en, fr?.let { "fr" to it }).toMap(), scope)

    private val releases =
        listOf(
            ReleaseNotes(
                "1.13.0",
                listOf(
                    ReleaseNotesSection("feat", listOf(line("Builds load faster", "Les builds chargent plus vite")), ChangeType.FEAT),
                    ReleaseNotesSection("fix", listOf(line("--wp sets WP", "--wp règle les PW", scope = "cli")), ChangeType.FIX),
                    ReleaseNotesSection("perf", listOf(line("Quicker badge", "Badge plus rapide à calculer")), ChangeType.PERF)
                )
            ),
            // An older release, rendered from the English CHANGELOG (untyped section).
            ReleaseNotes("1.11.0", listOf(ReleaseNotesSection("Features", listOf(line("legacy changelog entry")))))
        )

    private fun androidx.compose.ui.test.ComposeUiTest.dialog(lang: Lang) =
        setContent {
            CompositionLocalProvider(LocalLang provides lang) {
                WhatsNewDialog(releases = releases, onDismiss = {})
            }
        }

    @Test
    fun `in French the notes and their headings are French, the older changelog stays English`() =
        runComposeUiTest {
            dialog(Lang.FR)
            onNodeWithText("Nouveau").assertExists()
            onNodeWithText("Corrections").assertExists()
            onNodeWithText("Plus rapide").assertExists()
            onNodeWithText("Les builds chargent plus vite").assertExists()
            onNodeWithText("Ligne de commande · --wp règle les PW").assertExists()
            onNodeWithText("Badge plus rapide à calculer").assertExists()
            onNodeWithText("Builds load faster").assertDoesNotExist()
            onNodeWithText("Fonctionnalités").assertExists()
            onNodeWithText("legacy changelog entry").assertExists()
        }

    @Test
    fun `in English the same release reads in English`() =
        runComposeUiTest {
            dialog(Lang.EN)
            onNodeWithText("New").assertExists()
            onNodeWithText("Fixes").assertExists()
            onNodeWithText("Faster").assertExists()
            onNodeWithText("Builds load faster").assertExists()
            onNodeWithText("Command line · --wp sets WP").assertExists()
            onNodeWithText("Les builds chargent plus vite").assertDoesNotExist()
            onNodeWithText("Features").assertExists()
        }
}
