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

    @Test
    fun `the post-search optimality check is ON by default and its choice round-trips across instances`() {
        val node = Preferences.userRoot().node("me/chosante/wakfu-autobuilder-test/${javaClass.simpleName}-verify")
        try {
            node.clear()
            assertThat(LibraryPreferences(node).loadVerifyOptimality())
                .describedAs("first run: the check is ON")
                .isTrue()

            LibraryPreferences(node).saveVerifyOptimality(false)
            assertThat(LibraryPreferences(node).loadVerifyOptimality())
                .describedAs("a fresh instance reads the saved OFF — i.e. it survives a relaunch")
                .isFalse()

            LibraryPreferences(node).saveVerifyOptimality(true)
            assertThat(LibraryPreferences(node).loadVerifyOptimality()).isTrue()
        } finally {
            runCatching { node.removeNode() }
        }
    }

    @Test
    fun `a null preferences node never throws and keeps the optimality check ON`() {
        val prefs = LibraryPreferences(null)
        prefs.saveVerifyOptimality(false) // no-op, must not throw
        assertThat(prefs.loadVerifyOptimality()).isTrue()
    }

    @Test
    fun `hide chosen defaults off and survives fresh preference instances`() {
        val node = Preferences.userRoot().node("me/chosante/wakfu-autobuilder-test/${javaClass.simpleName}-picker")
        try {
            node.clear()
            assertThat(LibraryPreferences(node).loadHideChosen()).isFalse()
            LibraryPreferences(node).saveHideChosen(true)
            node.flush()
            assertThat(LibraryPreferences(node).loadHideChosen()).isTrue()
            LibraryPreferences(node).saveHideChosen(false)
            assertThat(LibraryPreferences(node).loadHideChosen()).isFalse()
            LibraryPreferences(null).saveHideChosen(true)
            assertThat(LibraryPreferences(null).loadHideChosen()).isFalse()
        } finally {
            runCatching { node.removeNode() }
        }
    }
}
