package me.chosante.ui.components

import me.chosante.common.BestElementConcentration
import me.chosante.common.Characteristic
import me.chosante.common.PerStatStepSpec
import me.chosante.common.ScenarioGate
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationConversion
import me.chosante.common.SublimationEffect
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.label

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
    sub.effects.forEach { e ->
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
                    if (lang == Lang.FR) {
                        "$sign${e.percentOfLevel}% du niveau en ${e.characteristic.label(lang)}$gate"
                    } else {
                        "$sign${e.percentOfLevel}% of level as ${e.characteristic.label(lang)}$gate"
                    }
                }
            }
        )
    }
    sub.perStatStep?.let { parts.add(perStatStepText(it, lang)) }
    sub.bestElementConcentration?.let { parts.add(bestElementConcentrationText(it, lang)) }
    if (sub.zeroesElementalMastery) parts.add(if (lang == Lang.FR) "Maîtrises élémentaires mises à 0" else "Elemental masteries set to 0")
    return if (parts.isEmpty()) sub.rawText.orEmpty() else parts.joinToString("  |  ")
}

/** e.g. Elemental Concentration → "+20% Damage Inflicted, -30% Mastery of the 3 weakest elements". */
private fun bestElementConcentrationText(
    b: BestElementConcentration,
    lang: Lang,
): String =
    if (lang == Lang.FR) {
        "+${b.damageInflictedBonus}% Dommages infligés, -${b.masteryPenaltyPercent}% Maîtrise des 3 éléments les plus faibles"
    } else {
        "+${b.damageInflictedBonus}% Damage Inflicted, -${b.masteryPenaltyPercent}% Mastery of the 3 weakest elements"
    }

/** e.g. Featherweight → "+6% Damage Inflicted per MP above 4 (max 24)". */
private fun perStatStepText(
    s: PerStatStepSpec,
    lang: Lang,
): String {
    val unit = if (s.target in PERCENT_CHARACS) "%" else ""
    return if (lang == Lang.FR) {
        "+${s.perStep}$unit ${s.target.label(lang)} par ${s.source.label(lang)} au-dessus de ${s.threshold} (max ${s.cap})"
    } else {
        "+${s.perStep}$unit ${s.target.label(lang)} per ${s.source.label(lang)} above ${s.threshold} (max ${s.cap})"
    }
}

private fun gateText(
    gate: ScenarioGate?,
    lang: Lang,
): String {
    if (gate == null) return ""
    val tags = ArrayList<String>()
    if (gate.berserk == true) tags.add(if (lang == Lang.FR) "berserk" else "berserk")
    if (gate.ranged == true) tags.add(if (lang == Lang.FR) "à distance" else "ranged")
    gate.element?.let { el ->
        tags.add(
            when (el) {
                "FIRE" -> if (lang == Lang.FR) "feu" else "fire"
                "WATER" -> if (lang == Lang.FR) "eau" else "water"
                "EARTH" -> if (lang == Lang.FR) "terre" else "earth"
                "AIR" -> "air"
                else -> el.lowercase()
            }
        )
    }
    gate.minCharacterLevel?.let { tags.add(if (lang == Lang.FR) "niv $it+" else "lvl $it+") }
    return if (tags.isEmpty()) "" else " (" + tags.joinToString(" + ") + ")"
}

private fun conversionText(
    c: SublimationConversion,
    lang: Lang,
): String =
    if (lang == Lang.FR) {
        "Convertit ${c.percent}% de ${c.from.label(lang)} en ${c.to.label(lang)}"
    } else {
        "Convert ${c.percent}% of ${c.from.label(lang)} into ${c.to.label(lang)}"
    }

private fun conditionText(
    c: SublimationCondition,
    lang: Lang,
): String {
    val fr = lang == Lang.FR
    return when (c.type) {
        SublimationConditionType.AP_AT_MOST -> if (fr) "Si PA ≤ ${c.value}" else "If AP ≤ ${c.value}"
        SublimationConditionType.AP_AT_LEAST -> if (fr) "Si PA ≥ ${c.value}" else "If AP ≥ ${c.value}"
        SublimationConditionType.AP_EXACT -> if (fr) "Si PA = ${c.value}" else "If AP = ${c.value}"
        SublimationConditionType.AP_ODD -> if (fr) "Si PA impairs" else "If odd AP"
        SublimationConditionType.CRIT_AT_MOST -> if (fr) "Si Coup Critique ≤ ${c.value}%" else "If Critical Hit ≤ ${c.value}%"
        SublimationConditionType.CRIT_AT_LEAST -> if (fr) "Si Coup Critique ≥ ${c.value}%" else "If Critical Hit ≥ ${c.value}%"
        SublimationConditionType.BLOCK_AT_LEAST -> if (fr) "Si Parade ≥ ${c.value}%" else "If Block ≥ ${c.value}%"
        SublimationConditionType.RANGE_AT_MOST -> if (fr) "Si Portée ≤ ${c.value}" else "If Range ≤ ${c.value}"
        SublimationConditionType.RANGE_AT_LEAST -> if (fr) "Si Portée ≥ ${c.value}" else "If Range ≥ ${c.value}"
        SublimationConditionType.RANGE_EXACT -> if (fr) "Si Portée = ${c.value}" else "If Range = ${c.value}"
        SublimationConditionType.DODGE_LT_PCT_OF_LEVEL -> if (fr) "Si Esquive < ${c.value}% du niveau" else "If Dodge < ${c.value}% of level"
        SublimationConditionType.SECONDARY_MASTERIES_AT_MOST -> if (fr) "Si maîtrises secondaires ≤ ${c.value}" else "If secondary masteries ≤ ${c.value}"
        SublimationConditionType.CRITICAL_MASTERY_AT_MOST -> if (fr) "Si Maîtrise Critique ≤ ${c.value}" else "If Critical Mastery ≤ ${c.value}"
        SublimationConditionType.WEAPON_TYPE_EQUIPPED -> if (fr) "Si ${c.text} équipé" else "If ${c.text} equipped"
        SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED ->
            if (fr) "Si ni bouclier, ni dague, ni arme à deux mains équipé" else "If no shield, dagger or two-handed weapon equipped"
        SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_REAR ->
            if (fr) "Si la plus haute maîtrise élémentaire > maîtrise dos" else "If highest elemental mastery > rear mastery"
        SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_HEALING ->
            if (fr) "Si la plus haute maîtrise élémentaire > maîtrise soin" else "If highest elemental mastery > healing mastery"
        SublimationConditionType.OTHER -> if (fr) "Conditionnel" else "Conditional"
    }
}
