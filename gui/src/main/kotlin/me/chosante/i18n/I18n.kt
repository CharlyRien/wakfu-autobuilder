package me.chosante.i18n

import generated.I18nKey
import java.text.MessageFormat
import java.util.*
import java.util.prefs.Preferences
import me.chosante.WakfuAutobuilderGUI

object I18n {
    private val fallbackOnEnglishWhenLocaleNotFound = object : ResourceBundle.Control() {
        override fun getFallbackLocale(baseName: String?, locale: Locale?) = Locale.ENGLISH
    }
    private val bundle = Preferences.userNodeForPackage(WakfuAutobuilderGUI::class.java).let {
        val languageTag = it.get("preferredLocale", Locale.FRANCE.toLanguageTag())

        ResourceBundle.getBundle("i18n", Locale.forLanguageTag(languageTag), fallbackOnEnglishWhenLocaleNotFound)
    }

    fun valueOf(i18nKey: I18nKey, vararg parameters: String): String {
        return MessageFormat.format(bundle.getString(i18nKey.key), *parameters)
    }
}
