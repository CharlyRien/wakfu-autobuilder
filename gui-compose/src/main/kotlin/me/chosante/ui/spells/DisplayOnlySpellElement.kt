package me.chosante.ui.spells

import me.chosante.common.Spell
import me.chosante.ui.i18n.Tr

/** Presentation only. Never extend the engine's four-element SpellElement/mastery domains for this. */
internal enum class DisplayOnlySpellElement(
    val label: Tr,
    val iconPath: String,
) {
    LIGHT(Tr.ELEMENT_LIGHT, "assets/elements/light.png"),
    STASIS(Tr.ELEMENT_STASIS, "assets/elements/stasis.png"),
}

internal fun Spell.displayOnlyElement(): DisplayOnlySpellElement? =
    if (element != null) null else DisplayOnlySpellElement.entries.firstOrNull { "element(${it.name})" in missingFields }
