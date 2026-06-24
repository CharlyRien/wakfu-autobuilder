package me.chosante.ui.spells

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.domain.BuildSpellDamage
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.autobuilder.domain.toSpellDamageRangeBand
import me.chosante.common.Character
import me.chosante.common.Characteristic
import me.chosante.common.Passive
import me.chosante.common.Spell
import me.chosante.common.SpellDamage
import me.chosante.common.SpellElement
import me.chosante.ui.components.InfoTip
import me.chosante.ui.components.PassiveIcon
import me.chosante.ui.components.SpellIcon
import me.chosante.ui.components.elementColor
import me.chosante.ui.components.elementLabel
import me.chosante.ui.components.localized
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.UiState
import me.chosante.ui.state.formatCompact
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

/**
 * The "Class spells & passives" tab of the result region: every damage spell of the current class with
 * the **expected damage the discovered build would deal** with it (via [BuildSpellDamage]), plus the
 * class's passive pool (with the build's selected passives flagged). Damage credits the build's elemental,
 * critical and **range-band (distance/melee) mastery** plus % damage inflicted, against a neutral
 * 0%-resistance target with no rear/berserk bonus — so a distance build is no longer under-shown by
 * dropping its biggest mastery. The range band comes from the current attack [scenario][UiState.scenario]
 * (shown as a small label); the "i" tooltip spells the rest out. Before any search runs (no
 * [UiState.build]) it falls back to each spell's build-independent base hit at the character level.
 */
@Composable
fun ClassSpellsPanel(
    ui: UiState,
    modifier: Modifier = Modifier,
) {
    val lang = LocalLang.current
    val build = ui.build
    val character =
        remember(ui.clazz, ui.level, ui.minLevel, build) {
            build?.let { Character(ui.clazz, ui.level, ui.minLevel).copy(characterSkills = it.characterSkills) }
        }
    val critRate = (ui.achieved[Characteristic.CRITICAL_HIT] ?: 0).coerceIn(0, 100)
    // Credit the build's own range band (the secondary mastery it plays with) so distance/melee builds
    // aren't understated; the headline stays a neutral FACE hit so spells remain comparable, with the
    // back/berserk scenarios shown as extra lines.
    val rangeBand = ui.scenario.rangeBand
    // Only offer the berserk variant when the build actually carries berserk mastery (otherwise it equals
    // the neutral hit and is just noise).
    val hasBerserk = (ui.achieved[Characteristic.MASTERY_BERSERK] ?: 0) > 0
    val entries =
        remember(ui.clazz, ui.level, ui.minLevel, build, ui.achieved, rangeBand) {
            val band = rangeBand.toSpellDamageRangeBand()
            SpellCatalog
                .damageSpells(ui.clazz)
                .map { spell ->
                    // Resolve the build's stats ONCE per spell; the face/back/berserk variants share them and
                    // differ only by the flags passed to SpellDamage, so this avoids 3× the stat resolution.
                    val stats = if (build != null && character != null) BuildSpellDamage.resolveStats(spell, build, character) else null

                    fun hit(
                        rear: Boolean,
                        berserk: Boolean,
                        orientation: Int,
                    ): SpellDamage.Result? =
                        if (stats != null && character != null) {
                            SpellDamage.expectedDamage(
                                spell = spell,
                                stats = stats,
                                characterLevel = character.level,
                                rangeBand = band,
                                rearMastery = rear,
                                berserkMastery = berserk,
                                orientationMultiplierPercent = orientation
                            )
                        } else {
                            null
                        }
                    SpellEntry(
                        spell = spell,
                        // Neutral reference: face hit, the build's range band, no rear/berserk.
                        damage = hit(rear = false, berserk = false, orientation = 100),
                        // Back hit: ×1.25 positional multiplier AND rear mastery (Wakfu couples them).
                        back = hit(rear = true, berserk = false, orientation = 125),
                        // Berserk: caster at/below 50% HP folds in berserk mastery (face).
                        berserk = if (hasBerserk) hit(rear = false, berserk = true, orientation = 100) else null,
                        baseHit = spell.baseDamageAt(ui.level)
                    )
                }.sortedByDescending { it.damage?.expected ?: it.baseHit?.toDouble() ?: 0.0 }
        }
    val scroll = rememberScrollState()
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(WColor.bg)
                .verticalScroll(scroll)
                .padding(WDimens.pad),
        verticalArrangement = Arrangement.spacedBy(WDimens.gap)
    ) {
        SectionTitle(title = tr(Tr.CLASS_SPELLS_TITLE), trailing = entries.size.toString())
        if (build != null) {
            // Transparency: say which secondary mastery the per-spell numbers credit (distance vs melee),
            // since the build's range band, not an apples-to-apples neutral, drives the figures.
            Text(
                text = tr(Tr.SPELL_DAMAGE_RANGE_NOTE).format(rangeBand.label(lang).lowercase()),
                style = WTypography.labelSmall.copy(color = WColor.faint)
            )
            // A hit is ranged OR melee, never both: if a build carries both masteries, only the scenario's
            // band counts here — flag it so the displayed damage isn't read as crediting the wasted one.
            val hasBothBands = (ui.achieved[Characteristic.MASTERY_DISTANCE] ?: 0) > 0 && (ui.achieved[Characteristic.MASTERY_MELEE] ?: 0) > 0
            if (hasBothBands) {
                Text(
                    text = tr(Tr.SPELL_DAMAGE_DIST_MELEE_HINT),
                    style = WTypography.labelSmall.copy(color = WColor.warning)
                )
            }
        }
        if (build == null) {
            Text(
                text = tr(Tr.CLASS_SPELLS_NO_BUILD),
                style = WTypography.bodySmall.copy(color = WColor.muted)
            )
        }
        if (entries.isEmpty()) {
            Text(
                text = tr(Tr.CLASS_SPELLS_EMPTY),
                style = WTypography.bodySmall.copy(color = WColor.muted)
            )
        }
        SpellGrid(entries = entries, critRate = critRate, hasBuild = build != null, lang = lang)
        PassivesSection(ui = ui, lang = lang)
    }
}

/**
 * A damage spell paired with its build-scoped expected hits: the neutral face [damage] (the headline and
 * the build-independent [baseHit] fallback), plus the [back] (rear hit, ×1.25 + rear mastery) and optional
 * [berserk] (≤50% HP) scenario variants shown as extra lines.
 */
private data class SpellEntry(
    val spell: Spell,
    val damage: SpellDamage.Result?,
    val back: SpellDamage.Result? = null,
    val berserk: SpellDamage.Result? = null,
    val baseHit: Int?,
)

@Composable
private fun SpellGrid(
    entries: List<SpellEntry>,
    critRate: Int,
    hasBuild: Boolean,
    lang: Lang,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns =
            when {
                maxWidth >= 920.dp -> 3
                maxWidth >= 560.dp -> 2
                else -> 1
            }
        Column(verticalArrangement = Arrangement.spacedBy(WDimens.gap)) {
            entries.chunked(columns).forEach { rowEntries ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WDimens.gap)
                ) {
                    rowEntries.forEach { entry ->
                        SpellCard(
                            entry = entry,
                            critRate = critRate,
                            hasBuild = hasBuild,
                            lang = lang,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(columns - rowEntries.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpellCard(
    entry: SpellEntry,
    critRate: Int,
    hasBuild: Boolean,
    lang: Lang,
    modifier: Modifier = Modifier,
) {
    val spell = entry.spell
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.hairline, RoundedCornerShape(WDimens.radius))
                .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SpellIcon(iconId = spell.iconId, element = spell.element, size = 40.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = spell.name.localized(lang),
                    style = WTypography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                ElementBadge(element = spell.element)
            }
        }
        SpellMetaRow(spell = spell)
        DamageBlock(entry = entry, critRate = critRate, hasBuild = hasBuild)
        spell.description?.localized(lang)?.takeIf { it.isNotBlank() }?.let { desc ->
            Text(
                text = desc,
                style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SpellMetaRow(spell: Spell) {
    val perTurn = tr(Tr.SPELL_PER_TURN_SUFFIX)
    val parts =
        buildList {
            spell.apCost?.let { add("$it AP") }
            spell.wpCost?.takeIf { it > 0 }?.let { add("$it WP") }
            val lo = spell.rangeMin
            val hi = spell.rangeMax
            if (lo != null || hi != null) {
                val low = lo ?: hi
                val high = hi ?: lo
                add(if (low == high) "$low" else "$low–$high")
            }
            spell.maxCastsThisTurn?.let { add("$it/$perTurn") }
        }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString("  ·  "),
        style = WTypography.labelSmall.copy(color = WColor.muted, fontFamily = WType.mono)
    )
}

@Composable
private fun DamageBlock(
    entry: SpellEntry,
    critRate: Int,
    hasBuild: Boolean,
) {
    val damage = entry.damage
    if (hasBuild && damage != null) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = damage.expected.toLong().formatCompact(),
                style = WTypography.headlineMedium.copy(color = WColor.accent, fontFamily = WType.mono)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = tr(Tr.SPELL_EXPECTED_HIT),
                style = WTypography.labelSmall.copy(color = WColor.muted),
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            InfoTip(text = tr(Tr.SPELL_EXPECTED_HIT_INFO), modifier = Modifier.padding(bottom = 2.dp))
        }
        CritBreakdown(damage = damage, critRate = critRate)
        ScenarioVariants(entry = entry)
    } else {
        val base = entry.baseHit ?: return
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = base.toLong().formatCompact(),
                style = WTypography.headlineMedium.copy(color = WColor.text, fontFamily = WType.mono)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = tr(Tr.SPELL_BASE_HIT),
                style = WTypography.labelSmall.copy(color = WColor.muted),
                modifier = Modifier.padding(bottom = 2.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            InfoTip(text = tr(Tr.SPELL_BASE_HIT_INFO), modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/**
 * Other-scenario expected hits below the neutral headline: the **back** hit (×1.25 positional + rear
 * mastery) and, when the build carries berserk mastery, the **berserk** hit (≤50% HP). Lets the player
 * read off how much a spell gains from positioning / low-HP play without switching the whole view.
 */
@Composable
private fun ScenarioVariants(entry: SpellEntry) {
    val lang = LocalLang.current
    val parts =
        buildList {
            entry.back?.let { add("${Orientation.BACK.label(lang).lowercase()} ${it.expected.toLong().formatCompact()}") }
            entry.berserk?.let { add("${tr(Tr.SPELL_VARIANT_BERSERK)} ${it.expected.toLong().formatCompact()}") }
        }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString("  ·  "),
        style = WTypography.labelSmall.copy(color = WColor.faint, fontFamily = WType.mono)
    )
}

/**
 * The non-crit / crit split, shown **only when the crit chance is strictly between 0% and 100%** —
 * exactly when both outcomes can happen. At 100% every cast crits (`expected == crit`); at 0% the
 * expected hit just is the non-crit value, so in both edge cases the single headline number says it all.
 */
@Composable
private fun CritBreakdown(
    damage: SpellDamage.Result,
    critRate: Int,
) {
    val text =
        when {
            critRate >= 100 -> tr(Tr.SPELL_ALWAYS_CRITS)
            critRate <= 0 -> return
            else ->
                "${tr(Tr.SPELL_NONCRIT)} ${damage.nonCrit.toLong().formatCompact()}  ·  " +
                    "${tr(Tr.SPELL_CRIT)} ${damage.crit.toLong().formatCompact()}  ·  " +
                    "$critRate% ${tr(Tr.SPELL_CRIT)}"
        }
    Text(
        text = text,
        style = WTypography.labelSmall.copy(color = WColor.faint, fontFamily = WType.mono)
    )
}

@Composable
private fun PassivesSection(
    ui: UiState,
    lang: Lang,
) {
    // The class's whole passive pool (so the "& passives" tab is never empty), with the ones the current
    // build selected flagged. Passives carry no spell damage, so this is a reference list, not a hit table.
    val passives = remember(ui.clazz) { PassiveCatalog.forClass(ui.clazz) }
    if (passives.isEmpty()) return
    val selectedIds =
        remember(ui.build) {
            ui.build
                ?.passives
                .orEmpty()
                .map { it.spellId }
                .toSet()
        }
    val trailing = if (selectedIds.isEmpty()) passives.size.toString() else "${selectedIds.size}/${passives.size}"
    SectionTitle(title = tr(Tr.CLASS_SPELLS_PASSIVES), trailing = trailing)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.hairline, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        passives.forEach { passive ->
            PassiveRow(passive = passive, selected = passive.spellId in selectedIds, lang = lang)
        }
    }
}

@Composable
private fun PassiveRow(
    passive: Passive,
    selected: Boolean,
    lang: Lang,
) {
    Row(verticalAlignment = Alignment.Top) {
        PassiveIcon(gfxId = passive.gfxId, size = 24.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = passive.name?.localized(lang) ?: passive.spellId.toString(),
                    style = WTypography.bodySmall.copy(color = WColor.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (selected) {
                    Box(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(WColor.success.copy(alpha = 0.18f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(text = tr(Tr.PASSIVE_IN_BUILD), style = WTypography.labelSmall.copy(color = WColor.success))
                    }
                }
            }
            passive.description?.localized(lang)?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    style = WTypography.labelSmall.copy(color = WColor.muted, lineHeight = 15.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        val flat = passive.flatStats.entries.joinToString("  ") { "+${it.value} ${it.key.label(lang)}" }
        if (flat.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = flat,
                style = WTypography.labelSmall.copy(color = WColor.accent2, fontFamily = WType.mono),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    trailing: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, style = WTypography.headlineMedium)
        Spacer(modifier = Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f).height(1.dp).background(WColor.hairline))
        if (trailing != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = trailing,
                style = WTypography.labelSmall.copy(color = WColor.muted, fontFamily = WType.mono)
            )
        }
    }
}

@Composable
private fun ElementBadge(element: SpellElement?) {
    element ?: return
    val color = element.elementColor()
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.16f))
                .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = element.elementLabel(),
            style = WTypography.labelSmall.copy(color = color, fontWeight = FontWeight.Medium)
        )
    }
}
