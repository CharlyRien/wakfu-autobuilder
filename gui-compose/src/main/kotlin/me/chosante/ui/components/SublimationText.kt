package me.chosante.ui.components

import me.chosante.common.Characteristic
import me.chosante.common.ScenarioGate
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.localized

private val PERCENT_CHARACS =
    setOf(Characteristic.DAMAGE_INFLICTED, Characteristic.BLOCK_PERCENTAGE, Characteristic.CRITICAL_HIT)

/**
 * Localized effect text for a sublimation, synthesized from its **structured** [Sublimation.condition] /
 * [Sublimation.effects] / [Sublimation.conversion] using the GUI's already-translated [Characteristic]
 * labels. The in-game sublimation tooltip is rendered client-side (no static localized string exists), and
 * the baked [Sublimation.rawText] is English-only — so we rebuild the text per language here, mirroring the
 * extractor's English `synthesizeRawText`. Falls back to the English [Sublimation.rawText] for the
 * combat-conditional subs that carry no structured effects.
 */
internal fun sublimationEffectText(
    sub: Sublimation,
    lang: Lang,
): String {
    val parts = ArrayList<String>()
    sub.condition?.let { parts.add(conditionText(it, lang)) }
    sub.conversion?.let { parts.add(conversionText(it, lang)) }
    sub.effects.filterIsInstance<SublimationEffect.StatEffect>().forEach { e ->
        val gate = gateText(e.scenarioGate, lang)
        parts.add(
            when (e) {
                is SublimationEffect.Flat -> {
                    val unit = if (e.characteristic in PERCENT_CHARACS) "%" else ""
                    val sign = if (e.value >= 0) "+" else ""
                    "$sign${e.value}$unit ${e.characteristic.label(lang)}$gate"
                }
                is SublimationEffect.PercentOfLevel -> {
                    val sign = if (e.percentOfLevel >= 0) "+" else ""
                    localized(
                        lang,
                        "$sign${e.percentOfLevel}% du niveau en ${e.characteristic.label(lang)}$gate",
                        "$sign${e.percentOfLevel}% of level as ${e.characteristic.label(lang)}$gate",
                        "$sign${e.percentOfLevel}% del nivel como ${e.characteristic.label(lang)}$gate"
                    )
                }
            }
        )
    }
    sub.perStatStep?.let { parts.add(perStatStepText(it, lang)) }
    sub.bestElementConcentration?.let { parts.add(bestElementConcentrationText(it, lang)) }
    if (sub.zeroesElementalMastery) {
        parts.add(
            localized(lang, "Maîtrises élémentaires mises à 0", "Elemental masteries set to 0", "Maestrías elementales puestas a 0")
        )
    }
    return if (parts.isEmpty()) sub.rawText.orEmpty() else parts.joinToString("  |  ")
}

/** e.g. Elemental Concentration → "+20% Damage Inflicted, -30% Mastery of the 3 weakest elements". */
private fun bestElementConcentrationText(
    b: SublimationEffect.BestElementConcentration,
    lang: Lang,
): String =
    localized(
        lang,
        "+${b.damageInflictedBonus}% Dommages infligés, -${b.masteryPenaltyPercent}% Maîtrise des 3 éléments les plus faibles",
        "+${b.damageInflictedBonus}% Damage Inflicted, -${b.masteryPenaltyPercent}% Mastery of the 3 weakest elements",
        "+${b.damageInflictedBonus}% Daño Infligido, -${b.masteryPenaltyPercent}% Maestría de los 3 elementos más débiles"
    )

/** e.g. Featherweight → "+6% Damage Inflicted per MP above 4 (max 24)". */
private fun perStatStepText(
    s: SublimationEffect.PerStatStep,
    lang: Lang,
): String {
    val unit = if (s.target in PERCENT_CHARACS) "%" else ""
    return localized(
        lang,
        "+${s.perStep}$unit ${s.target.label(lang)} par ${s.source.label(lang)} au-dessus de ${s.threshold} (max ${s.cap})",
        "+${s.perStep}$unit ${s.target.label(lang)} per ${s.source.label(lang)} above ${s.threshold} (max ${s.cap})",
        "+${s.perStep}$unit ${s.target.label(lang)} por ${s.source.label(lang)} por encima de ${s.threshold} (máx. ${s.cap})"
    )
}

private fun gateText(
    gate: ScenarioGate?,
    lang: Lang,
): String {
    if (gate == null) return ""
    val tags = ArrayList<String>()
    if (gate.berserk == true) tags.add(localized(lang, "berserk", "berserk", "berserker"))
    if (gate.ranged == true) tags.add(localized(lang, "à distance", "ranged", "a distancia"))
    gate.element?.let { el ->
        tags.add(
            when (el) {
                "FIRE" -> localized(lang, "feu", "fire", "fuego")
                "WATER" -> localized(lang, "eau", "water", "agua")
                "EARTH" -> localized(lang, "terre", "earth", "tierra")
                "AIR" -> localized(lang, "air", "air", "aire")
                else -> el.lowercase()
            }
        )
    }
    gate.minCharacterLevel?.let { tags.add(localized(lang, "niv $it+", "lvl $it+", "niv $it+")) }
    return if (tags.isEmpty()) "" else " (" + tags.joinToString(" + ") + ")"
}

private fun conversionText(
    c: SublimationEffect.Conversion,
    lang: Lang,
): String =
    localized(
        lang,
        "Convertit ${c.percent}% de ${c.from.label(lang)} en ${c.to.label(lang)}",
        "Convert ${c.percent}% of ${c.from.label(lang)} into ${c.to.label(lang)}",
        "Convierte ${c.percent}% de ${c.from.label(lang)} en ${c.to.label(lang)}"
    )

private fun conditionText(
    c: SublimationCondition,
    lang: Lang,
): String =
    when (c.type) {
        SublimationConditionType.AP_AT_MOST -> localized(lang, "Si PA ≤ ${c.value}", "If AP ≤ ${c.value}", "Si PA ≤ ${c.value}")
        SublimationConditionType.AP_AT_LEAST -> localized(lang, "Si PA ≥ ${c.value}", "If AP ≥ ${c.value}", "Si PA ≥ ${c.value}")
        SublimationConditionType.AP_EXACT -> localized(lang, "Si PA = ${c.value}", "If AP = ${c.value}", "Si PA = ${c.value}")
        SublimationConditionType.AP_ODD -> localized(lang, "Si PA impairs", "If odd AP", "Si PA impares")
        SublimationConditionType.CRIT_AT_MOST ->
            localized(lang, "Si Coup Critique ≤ ${c.value}%", "If Critical Hit ≤ ${c.value}%", "Si Golpe Crítico ≤ ${c.value}%")
        SublimationConditionType.CRIT_AT_LEAST ->
            localized(lang, "Si Coup Critique ≥ ${c.value}%", "If Critical Hit ≥ ${c.value}%", "Si Golpe Crítico ≥ ${c.value}%")
        SublimationConditionType.BLOCK_AT_LEAST ->
            localized(lang, "Si Parade ≥ ${c.value}%", "If Block ≥ ${c.value}%", "Si Bloqueo ≥ ${c.value}%")
        SublimationConditionType.RANGE_AT_MOST -> localized(lang, "Si Portée ≤ ${c.value}", "If Range ≤ ${c.value}", "Si Alcance ≤ ${c.value}")
        SublimationConditionType.RANGE_AT_LEAST -> localized(lang, "Si Portée ≥ ${c.value}", "If Range ≥ ${c.value}", "Si Alcance ≥ ${c.value}")
        SublimationConditionType.RANGE_EXACT -> localized(lang, "Si Portée = ${c.value}", "If Range = ${c.value}", "Si Alcance = ${c.value}")
        SublimationConditionType.DODGE_LT_PCT_OF_LEVEL ->
            localized(
                lang,
                "Si Esquive < ${c.value}% du niveau",
                "If Dodge < ${c.value}% of level",
                "Si Esquiva < ${c.value}% del nivel"
            )
        SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ->
            localized(
                lang,
                "Si maîtrises secondaires ≤ ${c.value}",
                "If secondary masteries ≤ ${c.value}",
                "Si maestrías secundarias ≤ ${c.value}"
            )
        SublimationConditionType.CRITICAL_MASTERY_AT_MOST ->
            localized(
                lang,
                "Si Maîtrise Critique ≤ ${c.value}",
                "If Critical Mastery ≤ ${c.value}",
                "Si Maestría Crítica ≤ ${c.value}"
            )
        SublimationConditionType.WEAPON_TYPE_EQUIPPED ->
            localized(lang, "Si ${c.text} équipé", "If ${c.text} equipped", "Si ${c.text} equipado")
        SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED ->
            localized(
                lang,
                "Si ni bouclier, ni dague, ni arme à deux mains équipé",
                "If no shield, dagger or two-handed weapon equipped",
                "Si no hay escudo, daga ni arma a dos manos equipada"
            )
        SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_REAR ->
            localized(
                lang,
                "Si la plus haute maîtrise élémentaire > maîtrise dos",
                "If highest elemental mastery > rear mastery",
                "Si la maestría elemental más alta > maestría de espalda"
            )
        SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_HEALING ->
            localized(
                lang,
                "Si la plus haute maîtrise élémentaire > maîtrise soin",
                "If highest elemental mastery > healing mastery",
                "Si la maestría elemental más alta > maestría de curación"
            )
        SublimationConditionType.OTHER -> localized(lang, "Conditionnel", "Conditional", "Condicional")
    }
