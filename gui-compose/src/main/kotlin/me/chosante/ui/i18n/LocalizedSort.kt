package me.chosante.ui.i18n

import java.text.Collator
import java.util.Locale

fun localizedCollator(lang: Lang): Collator =
    Collator
        .getInstance(
            when (lang) {
                Lang.FR -> Locale.FRENCH
                Lang.EN -> Locale.ENGLISH
            }
        ).apply {
            strength = Collator.PRIMARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
        }

fun <T> Iterable<T>.sortedByLocalized(
    lang: Lang,
    selector: (T) -> String,
): List<T> {
    val collator = localizedCollator(lang)
    return sortedWith { left, right -> collator.compare(selector(left).trim(), selector(right).trim()) }
}
