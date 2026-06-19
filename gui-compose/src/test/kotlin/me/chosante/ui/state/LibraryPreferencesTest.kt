package me.chosante.ui.state

import me.chosante.ui.i18n.Lang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.prefs.Preferences

class LibraryPreferencesTest {
    @Test
    fun `language defaults to English and round-trips across instances`() {
        val node = Preferences.userRoot().node("me/chosante/wakfu-autobuilder-test/${javaClass.simpleName}")
        try {
            node.clear()
            assertThat(LibraryPreferences(node).loadLang())
                .describedAs("first run defaults to English")
                .isEqualTo(Lang.EN)

            LibraryPreferences(node).saveLang(Lang.FR)

            assertThat(LibraryPreferences(node).loadLang())
                .describedAs("the saved language is read back by a fresh instance — i.e. it survives a relaunch")
                .isEqualTo(Lang.FR)
        } finally {
            runCatching { node.removeNode() }
        }
    }

    @Test
    fun `a null preferences node never throws and falls back to English`() {
        val prefs = LibraryPreferences(null)
        prefs.saveLang(Lang.FR) // no-op, must not throw
        assertThat(prefs.loadLang()).isEqualTo(Lang.EN)
    }
}
