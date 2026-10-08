package me.chosante.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import me.chosante.common.Characteristic
import me.chosante.common.ScenarioGate
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.localized
import me.chosante.ui.i18n.tr
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

private val PERCENT_CHARACS =
    setOf(Characteristic.DAMAGE_INFLICTED, Characteristic.BLOCK_PERCENTAGE, Characteristic.CRITICAL_HIT)

/**
 * Stacking-at-a-glance badge, shared by the picker, the paperdoll cards and the stats panel:
 * `granted levels / stack cap` (Carnage III → "3/6", Carnage II → "2/6") — so an
 * autobuilder-vs-Zenith stacking discrepancy is visible on every surface. Uses the record's TIER
 * (what one socketed shard grants), NOT [Sublimation.maxCopies] (a solver-modeling clamp that
 * reads 1 for cumulable CONDITIONAL subs — displaying it misled the exact audit the badge is
 * for). Hidden when the stack cap leaves no headroom beyond one shard.
 */
@Composable
internal fun SublimationStackBadge(sub: Sublimation) {
    if (sub.maxStackLevel <= sub.maxTier) return
    Text(
        text = tr(Tr.SUBLIMATION_STACK_SHORT).format(sub.stackLevel ?: sub.maxTier, sub.maxStackLevel),
        style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
    )
}

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
                        fr = "$sign${e.percentOfLevel}% du niveau en ${e.characteristic.label(lang)}$gate",
                        en = "$sign${e.percentOfLevel}% of level as ${e.characteristic.label(lang)}$gate",
                        es = "$sign${e.percentOfLevel}% del nivel como ${e.characteristic.label(lang)}$gate",
                        pt = "$sign${e.percentOfLevel}% do nível como ${e.characteristic.label(lang)}$gate"
                    )
                }
            }
        )
    }
    sub.perStatStep?.let { parts.add(perStatStepText(it, lang)) }
    sub.bestElementConcentration?.let { parts.add(bestElementConcentrationText(it, lang)) }
    if (sub.zeroesElementalMastery) {
        parts.add(
            localized(
                lang,
                fr = "Maîtrises élémentaires mises à 0",
                en = "Elemental masteries set to 0",
                es = "Dominios elementales puestos a 0",
                pt = "Domínios elementares definidos como 0"
            )
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
        fr = "+${b.damageInflictedBonus}% Dommages infligés, -${b.masteryPenaltyPercent}% Maîtrise des 3 éléments les plus faibles",
        en = "+${b.damageInflictedBonus}% Damage Inflicted, -${b.masteryPenaltyPercent}% Mastery of the 3 weakest elements",
        es = "+${b.damageInflictedBonus}% Daños infligidos, -${b.masteryPenaltyPercent}% Dominio de los 3 elementos más débiles",
        pt = "+${b.damageInflictedBonus}% Danos infligidos, -${b.masteryPenaltyPercent}% Domínio dos 3 elementos mais fracos"
    )

/** e.g. Featherweight → "+6% Damage Inflicted per MP above 4 (max 24)". */
private fun perStatStepText(
    s: SublimationEffect.PerStatStep,
    lang: Lang,
): String {
    val unit = if (s.target in PERCENT_CHARACS) "%" else ""
    return localized(
        lang,
        fr = "+${s.perStep}$unit ${s.target.label(lang)} par ${s.source.label(lang)} au-dessus de ${s.threshold} (max ${s.cap})",
        en = "+${s.perStep}$unit ${s.target.label(lang)} per ${s.source.label(lang)} above ${s.threshold} (max ${s.cap})",
        es = "+${s.perStep}$unit ${s.target.label(lang)} por ${s.source.label(lang)} por encima de ${s.threshold} (máx. ${s.cap})",
        pt = "+${s.perStep}$unit ${s.target.label(lang)} por ${s.source.label(lang)} acima de ${s.threshold} (máx. ${s.cap})"
    )
}

private fun gateText(
    gate: ScenarioGate?,
    lang: Lang,
): String {
    if (gate == null) return ""
    val tags = ArrayList<String>()
    if (gate.berserk == true) tags.add(localized(lang, fr = "berserk", en = "berserk", es = "berserker", pt = "berserk"))
    if (gate.ranged == true) tags.add(localized(lang, fr = "à distance", en = "ranged", es = "a distancia", pt = "de distância"))
    gate.element?.let { el ->
        tags.add(
            when (el) {
                "FIRE" -> localized(lang, fr = "feu", en = "fire", es = "fuego", pt = "fogo")
                "WATER" -> localized(lang, fr = "eau", en = "water", es = "agua", pt = "água")
                "EARTH" -> localized(lang, fr = "terre", en = "earth", es = "tierra", pt = "terra")
                "AIR" -> localized(lang, fr = "air", en = "air", es = "aire", pt = "ar")
                else -> el.lowercase()
            }
        )
    }
    gate.minCharacterLevel?.let { tags.add(localized(lang, fr = "niv $it+", en = "lvl $it+", es = "niv $it+", pt = "nív. $it+")) }
    return if (tags.isEmpty()) "" else " (" + tags.joinToString(" + ") + ")"
}

private fun conversionText(
    c: SublimationEffect.Conversion,
    lang: Lang,
): String =
    localized(
        lang,
        fr = "Convertit ${c.percent}% de ${c.from.label(lang)} en ${c.to.label(lang)}",
        en = "Convert ${c.percent}% of ${c.from.label(lang)} into ${c.to.label(lang)}",
        es = "Convierte ${c.percent}% de ${c.from.label(lang)} en ${c.to.label(lang)}",
        pt = "Converte ${c.percent}% de ${c.from.label(lang)} em ${c.to.label(lang)}"
    )

private fun conditionText(
    c: SublimationCondition,
    lang: Lang,
): String =
    when (c.type) {
        SublimationConditionType.AP_AT_MOST -> localized(lang, fr = "Si PA ≤ ${c.value}", en = "If AP ≤ ${c.value}", es = "Si PA ≤ ${c.value}", pt = "Se PA ≤ ${c.value}")
        SublimationConditionType.AP_AT_LEAST -> localized(lang, fr = "Si PA ≥ ${c.value}", en = "If AP ≥ ${c.value}", es = "Si PA ≥ ${c.value}", pt = "Se PA ≥ ${c.value}")
        SublimationConditionType.AP_EXACT -> localized(lang, fr = "Si PA = ${c.value}", en = "If AP = ${c.value}", es = "Si PA = ${c.value}", pt = "Se PA = ${c.value}")
        SublimationConditionType.AP_ODD -> localized(lang, fr = "Si PA impairs", en = "If odd AP", es = "Si PA impares", pt = "Se PA ímpares")
        SublimationConditionType.CRIT_AT_MOST ->
            localized(
                lang,
                fr = "Si Coup Critique ≤ ${c.value}%",
                en = "If Critical Hit ≤ ${c.value}%",
                es = "Si Golpe Crítico ≤ ${c.value}%",
                pt = "Se Golpe crítico ≤ ${c.value}%"
            )

        SublimationConditionType.CRIT_AT_LEAST ->
            localized(
                lang,
                fr = "Si Coup Critique ≥ ${c.value}%",
                en = "If Critical Hit ≥ ${c.value}%",
                es = "Si Golpe Crítico ≥ ${c.value}%",
                pt = "Se Golpe crítico ≥ ${c.value}%"
            )

        SublimationConditionType.BLOCK_AT_LEAST ->
            localized(lang, fr = "Si Parade ≥ ${c.value}%", en = "If Block ≥ ${c.value}%", es = "Si Anticipación ≥ ${c.value}%", pt = "Se Parada ≥ ${c.value}%")

        SublimationConditionType.RANGE_AT_MOST ->
            localized(
                lang,
                fr = "Si Portée ≤ ${c.value}",
                en = "If Range ≤ ${c.value}",
                es = "Si Alcance ≤ ${c.value}",
                pt = "Se Alcance ≤ ${c.value}"
            )
        SublimationConditionType.RANGE_AT_LEAST ->
            localized(
                lang,
                fr = "Si Portée ≥ ${c.value}",
                en = "If Range ≥ ${c.value}",
                es = "Si Alcance ≥ ${c.value}",
                pt = "Se Alcance ≥ ${c.value}"
            )
        SublimationConditionType.RANGE_EXACT ->
            localized(
                lang,
                fr = "Si Portée = ${c.value}",
                en = "If Range = ${c.value}",
                es = "Si Alcance = ${c.value}",
                pt = "Se Alcance = ${c.value}"
            )
        SublimationConditionType.DODGE_LT_PCT_OF_LEVEL ->
            localized(
                lang,
                fr = "Si Esquive < ${c.value}% du niveau",
                en = "If Dodge < ${c.value}% of level",
                es = "Si Esquiva < ${c.value}% del nivel",
                pt = "Se Esquiva < ${c.value}% do nível"
            )

        SublimationConditionType.SECONDARY_MASTERIES_AT_MOST ->
            localized(
                lang,
                fr = "Si chaque maîtrise secondaire ≤ ${c.value}",
                en = "If each secondary mastery ≤ ${c.value}",
                es = "Si cada dominio secundario ≤ ${c.value}",
                pt = "Se cada domínio secundário ≤ ${c.value}"
            )

        SublimationConditionType.HEALING_MASTERY_AT_MOST ->
            localized(
                lang,
                fr = "Si Maîtrise Soin ≤ ${c.value}",
                en = "If Healing Mastery ≤ ${c.value}",
                es = "Si Dominio cura ≤ ${c.value}",
                pt = "Se Domínio de cura ≤ ${c.value}"
            )

        SublimationConditionType.CRITICAL_MASTERY_AT_MOST ->
            localized(
                lang,
                fr = "Si Maîtrise Critique ≤ ${c.value}",
                en = "If Critical Mastery ≤ ${c.value}",
                es = "Si Dominio crítico ≤ ${c.value}",
                pt = "Se Domínio de crítico ≤ ${c.value}"
            )

        SublimationConditionType.WEAPON_TYPE_EQUIPPED ->
            localized(lang, fr = "Si ${c.text} équipé", en = "If ${c.text} equipped", es = "Si ${c.text} equipado", pt = "Se ${c.text} equipado")

        SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED ->
            localized(
                lang,
                fr = "Si ni bouclier, ni dague, ni arme à deux mains équipé",
                en = "If no shield, dagger or two-handed weapon equipped",
                es = "Si no hay escudo, daga ni arma de dos manos equipada",
                pt = "Se nenhum escudo, adaga ou arma de duas mãos equipado"
            )

        SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_REAR ->
            localized(
                lang,
                fr = "Si la plus haute maîtrise élémentaire > maîtrise dos",
                en = "If highest elemental mastery > rear mastery",
                es = "Si el dominio elemental más alto > dominio de espalda",
                pt = "Se o maior domínio elementar > domínio de costas"
            )

        SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_HEALING ->
            localized(
                lang,
                fr = "Si la plus haute maîtrise élémentaire > maîtrise soin",
                en = "If highest elemental mastery > healing mastery",
                es = "Si el dominio elemental más alto > dominio de cura",
                pt = "Se o maior domínio elementar > domínio de cura"
            )

        SublimationConditionType.OTHER -> localized(lang, fr = "Conditionnel", en = "Conditional", es = "Condicional", pt = "Condicional")
    }
