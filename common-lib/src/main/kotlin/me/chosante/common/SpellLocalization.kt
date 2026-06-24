package me.chosante.common

import kotlinx.serialization.Serializable

/**
 * Per-spell localized name + description, decoded from the local client's i18n bundle (name = namespace 3,
 * description = namespace 4) by `bdata-extractor` into `spell-i18n.json`. Joined onto the encyclopedia-scraped
 * `spells.json` by [spellId] in `SpellCatalog`, so the GUI shows fully-translated spell text — the
 * encyclopedia scrape only ever carried English descriptions (all four language slots held the same English
 * string), whereas the client bundle has real fr/en/es/pt.
 */
@Serializable
data class SpellLocalization(
    val spellId: Int,
    val name: I18nText,
    val description: I18nText? = null,
)
