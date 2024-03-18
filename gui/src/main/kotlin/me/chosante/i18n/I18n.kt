package me.chosante.i18n

import generated.I18nKey
import java.text.MessageFormat
import java.util.*
import java.util.prefs.Preferences
import me.chosante.WakfuAutobuilderGUI
import me.chosante.common.I18nText

object I18n {
    private val fallbackOnEnglishWhenLocaleNotFound = object : ResourceBundle.Control() {
        override fun getFallbackLocale(baseName: String?, locale: Locale?) = Locale.ENGLISH
    }

    private val fallbackBundle = ResourceBundle.getBundle("i18n", Locale.ENGLISH)

    private val bundle = Preferences.userNodeForPackage(WakfuAutobuilderGUI::class.java).let {
        val languageTag = it.get("preferredLocale", Locale.getDefault().toLanguageTag())

        ResourceBundle.getBundle("i18n", Locale.forLanguageTag(languageTag), fallbackOnEnglishWhenLocaleNotFound)
    }

    internal fun I18nText.usePreferredLanguageIfFoundOrEnglish(): String {
        return when (bundle.locale) {
            Locale.FRANCE -> fr
            Locale.ENGLISH -> en
            Locale.forLanguageTag("es") -> es
            Locale.forLanguageTag("pt") -> pt
            else -> en
        }
    }

    fun valueOf(i18nKey: I18nKey, vararg parameters: String): String {
        val resourceBundle = if (bundle.containsKey(i18nKey.key)) bundle else fallbackBundle
        return MessageFormat.format(resourceBundle.getString(i18nKey.key), *parameters)
    }
}
