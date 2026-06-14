package me.chosante.spellextractor

import me.chosante.common.CharacterClass
import me.chosante.common.SpellArea
import me.chosante.common.SpellCategory
import me.chosante.common.SpellElement

/** Ankama encyclopedia coordinates for one class: its numeric id and url slug, mapped to our enum. */
data class ClassRef(
    val clazz: CharacterClass,
    val encyclopediaId: Int,
    val slug: String,
) {
    /** Encyclopedia path for this class's spell list in [locale] (slug is localized via redirect). */
    fun classPath(locale: Locale): String = "/${locale.code}/mmorpg/${locale.encyclopedia}/classes/$encyclopediaId-$slug"

    companion object {
        /** The 18 playable classes and their encyclopedia ids (slugs are the English canonical ones). */
        val ALL =
            listOf(
                ClassRef(CharacterClass.FECA, 1, "feca"),
                ClassRef(CharacterClass.OSAMODAS, 2, "osamodas"),
                ClassRef(CharacterClass.ENUTROF, 3, "enutrof"),
                ClassRef(CharacterClass.SRAM, 4, "sram"),
                ClassRef(CharacterClass.XELOR, 5, "xelor"),
                ClassRef(CharacterClass.ECAFLIP, 6, "ecaflip"),
                ClassRef(CharacterClass.ENIRIPSA, 7, "eniripsa"),
                ClassRef(CharacterClass.IOP, 8, "iop"),
                ClassRef(CharacterClass.CRA, 9, "cra"),
                ClassRef(CharacterClass.SADIDA, 10, "sadida"),
                ClassRef(CharacterClass.SACRIEUR, 11, "sacrier"),
                ClassRef(CharacterClass.PANDAWA, 12, "pandawa"),
                ClassRef(CharacterClass.ROUBLARD, 13, "rogue"),
                ClassRef(CharacterClass.ZOBAL, 14, "masqueraider"),
                ClassRef(CharacterClass.OUGINAK, 15, "ouginak"),
                ClassRef(CharacterClass.STEAMER, 16, "foggernaut"),
                ClassRef(CharacterClass.ELIOTROPE, 18, "eliotrope"),
                ClassRef(CharacterClass.HUPPERMAGE, 19, "huppermage")
            )
    }
}

/** Encyclopedia locales: the url language code and the localized "encyclopedia" path segment. */
enum class Locale(
    val code: String,
    val encyclopedia: String,
) {
    EN("en", "encyclopedia"),
    FR("fr", "encyclopedie"),
}

/** A spell as listed on a class page — enough to crawl and name it before fetching its detail page. */
data class SpellStub(
    val id: Int,
    val slug: String,
    val name: String,
    val iconId: Int?,
    val isElementary: Boolean,
) {
    fun detailPath(ref: ClassRef): String = "/en/mmorpg/encyclopedia/classes/${ref.encyclopediaId}-${ref.slug}/$id-$slug"
}

/** The mechanics parsed off a single spell's detail page. Any field may be null (never invented). */
data class SpellDetail(
    val name: String?,
    val element: SpellElement?,
    val apCost: Int?,
    val wpCost: Int?,
    val rangeMin: Int?,
    val rangeMax: Int?,
    val baseDamage: Int?,
    val critDamage: Int?,
    val area: SpellArea?,
    val requiresLineOfSight: Boolean?,
    val category: SpellCategory,
    val description: String?,
)

/**
 * Pure HTML → data parsing for the encyclopedia (regex-based; no external HTML library, mirroring the
 * project's lean dependency set). Stateless and unit-testable against saved pages.
 */
object SpellScraper {
    private val DOTALL = setOf(RegexOption.DOT_MATCHES_ALL)

    private val LIST_ROW =
        Regex(
            """<a[^>]+href="/en/mmorpg/encyclopedia/classes/\d+-[^/"]+/(\d+)-([^"]+)"[^>]*class="([^"]*)"[^>]*title="([^"]*)"[^>]*>\s*<img[^>]+src="[^"]*spell/(\d+)\.png"""",
            DOTALL
        )

    private val SPELL_NAME = Regex("""ak-spell-name[^>]*>\s*([^<]+)""")
    private val INFOS = Regex("""ak-spell-details-infos"?>(.*?)ak-spell-details-effects-container""", DOTALL)
    private val DESC = Regex("""ak-spell-description[^>]*>(.*?)</div>""", DOTALL)
    private val NORMAL_EFFECT =
        Regex("""spell-normal-effect.*?(?=spell-critical-effect|ak-spell-details-illu|$)""", DOTALL)
    private val CRIT_EFFECT = Regex("""spell-critical-effect.*?(?=ak-spell-details-illu|$)""", DOTALL)
    private val ELEMENT_PICTO = Regex("""element/(FIRE|WATER|EARTH|AIR)\.png""")
    private val DAMAGE = Regex("""Damage:\s*(\d+)""")
    private val AP_RANGE = Regex("""^(\d+)\s+(\d+)\s*-\s*(\d+)""")
    private val AP_RANGE_SINGLE = Regex("""^(\d+)\s+(\d+)\b""")
    private val TAGS = Regex("""<[^>]+>""")
    private val WS = Regex("""\s+""")

    private fun text(html: String): String = WS.replace(TAGS.replace(html, " "), " ").trim()

    /** Parses the `(id, name, icon, isElementary)` stubs from a class listing page. */
    fun parseClassListing(html: String): List<SpellStub> =
        LIST_ROW
            .findAll(html)
            .map { m ->
                SpellStub(
                    id = m.groupValues[1].toInt(),
                    slug = m.groupValues[2],
                    name = m.groupValues[4].trim(),
                    iconId = m.groupValues[5].toIntOrNull(),
                    isElementary = m.groupValues[3].contains("ak-elementary-spell")
                )
            }.distinctBy { it.id }
            .toList()

    /** Parses a single spell's detail page. */
    fun parseSpellPage(html: String): SpellDetail {
        val name =
            SPELL_NAME
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.trim()

        val infosText =
            INFOS.find(html)?.groupValues?.get(1)?.let { text(it) }?.let { raw ->
                if (name != null && raw.startsWith(name)) raw.removePrefix(name).trim() else raw
            } ?: ""
        val apRange = AP_RANGE.find(infosText) ?: AP_RANGE_SINGLE.find(infosText)
        val apCost = apRange?.groupValues?.get(1)?.toIntOrNull()
        val rangeMin = apRange?.groupValues?.get(2)?.toIntOrNull()
        val rangeMax = apRange?.groupValues?.getOrNull(3)?.toIntOrNull() ?: rangeMin

        val description =
            DESC
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.let { text(it) }
                ?.takeIf { it.isNotBlank() }

        val normalBlock = NORMAL_EFFECT.find(html)?.value ?: ""
        val critBlock = CRIT_EFFECT.find(html)?.value ?: ""
        val element =
            ELEMENT_PICTO
                .find(normalBlock)
                ?.groupValues
                ?.get(1)
                ?.let { runCatching { SpellElement.valueOf(it) }.getOrNull() }
        val baseDamage =
            DAMAGE
                .find(normalBlock)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        val critDamage =
            DAMAGE
                .find(critBlock)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()

        val descLower = (description ?: "").lowercase()
        val isDamage = element != null && baseDamage != null
        val area =
            when {
                !isDamage -> null
                AREA_KEYWORDS.any { it in descLower } -> SpellArea.AREA
                else -> SpellArea.SINGLE_TARGET
            }
        val requiresLineOfSight = if (description == null) null else "line of sight" !in descLower

        val category = if (apCost != null) SpellCategory.ACTIVE else SpellCategory.PASSIVE

        return SpellDetail(
            name = name,
            element = element,
            apCost = apCost,
            wpCost = null,
            rangeMin = rangeMin,
            rangeMax = rangeMax,
            baseDamage = baseDamage,
            critDamage = critDamage,
            area = area,
            requiresLineOfSight = requiresLineOfSight,
            category = category,
            description = description
        )
    }

    private val AREA_KEYWORDS =
        listOf("area of effect", "cone", "in a line", "in a cross", "cells around", "around the", "all enemies")
}
