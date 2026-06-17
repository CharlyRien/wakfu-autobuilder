package me.chosante.ui.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.domain.BossDisplay
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.Characteristic
import me.chosante.common.SpellElement
import me.chosante.common.skills.Assignable
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.ui.components.CharacteristicIcon
import me.chosante.ui.components.PassiveIcon
import me.chosante.ui.components.StatGlyphIcon
import me.chosante.ui.components.VerticalScrollHints
import me.chosante.ui.components.iconResourcePath
import me.chosante.ui.components.rememberClasspathBitmap
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.label
import me.chosante.ui.i18n.skillLabel
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.TargetRow
import me.chosante.ui.state.UiState
import me.chosante.ui.state.ZenithState
import me.chosante.ui.state.formatCompact
import me.chosante.ui.state.isEngineInternalStat
import me.chosante.ui.state.isExact
import me.chosante.ui.state.requestedMasteryTotal
import me.chosante.ui.state.statCatalog
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

@Composable
fun StatsPanel(
    ui: UiState,
    onOpenZenith: () -> Unit,
    onCopyZenith: () -> Unit,
    onSaveBuild: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    me.chosante.ui.testing
        .ScreenshotAutoScrollToBottom(scroll, ui.build != null)
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(WDimens.gap),
            verticalArrangement = Arrangement.spacedBy(WDimens.gap)
        ) {
            MatchHero(ui)
            if (ui.phase == Phase.Idle && ui.build == null) {
                // No build yet: the ActionsCard (which normally carries the error banner) isn't shown,
                // so surface a pre-search error — e.g. an invalid min/max level range — here instead.
                ui.error?.let { ErrorBanner(error = it) }
                EmptyHint()
            } else {
                ActionsCard(
                    ui = ui,
                    onOpenZenith = onOpenZenith,
                    onCopyZenith = onCopyZenith,
                    onSaveBuild = onSaveBuild,
                    onExport = onExport
                )
                if (ui.mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                    SpellRotationCard(ui)
                }
                MasterySummary(ui)
                DesiredVsAchieved(ui)
                BuildSheet(ui)
                SublimationsResult(ui)
                PassivesResult(ui)
                SkillTree(ui.build?.characterSkills ?: CharacterSkills(ui.level))
            }
        }
        VerticalScrollHints(scroll)
    }
}

@Composable
private fun MatchHero(ui: UiState) {
    // Most-masteries maximizes mastery and max-damage maximizes expected damage, so a "% match" is
    // meaningless for both — show the headline number (no %, no progress bar) instead. Only precision
    // mode keeps the % match + meter.
    val masteryMode = ui.mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
    val damageMode = ui.mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
    val headlineNumberMode = masteryMode || damageMode
    ResultCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        ) {
            if (headlineNumberMode) {
                Text(
                    text = if (damageMode) ui.match.toInt().formatCompact() else ui.requestedMasteryTotal().formatCompact(),
                    style =
                        WTypography.displayLarge.copy(
                            fontSize = 46.sp,
                            lineHeight = 46.sp,
                            color = WColor.text,
                            fontFamily = WType.display,
                            textAlign = TextAlign.Center
                        )
                )
                Text(
                    text = tr(if (damageMode) Tr.EXPECTED_DAMAGE else Tr.BUILD_MASTERY),
                    style = WTypography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (!damageMode) {
                    Text(
                        text = tr(Tr.BUILD_MASTERY_HINT),
                        style = WTypography.labelSmall.copy(color = WColor.faint, textAlign = TextAlign.Center),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = ui.match.toInt().toString(),
                        style =
                            WTypography.displayLarge.copy(
                                fontSize = 46.sp,
                                lineHeight = 46.sp,
                                color = if (ui.match.toInt() == 100) WColor.success else WColor.text,
                                fontFamily = WType.display,
                                textAlign = TextAlign.Center
                            )
                    )
                    Text(
                        text = "%",
                        style =
                            WTypography.headlineMedium.copy(
                                color = WColor.muted,
                                lineHeight = 24.sp
                            )
                    )
                }
                Text(
                    text = tr(Tr.BUILD_MATCH),
                    style = WTypography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            // Only judge optimality once the search has actually FINISHED — during Searching the streamed
            // best-so-far is naturally "not proven optimal" yet, so showing it then is premature/misleading.
            if (ui.phase == Phase.Done && ui.build != null) {
                Text(
                    text = tr(if (ui.optimal) Tr.OPTIMAL_PROVEN else Tr.BEST_FOUND),
                    style = WTypography.labelSmall.copy(color = if (ui.optimal) WColor.success else WColor.warning),
                    modifier = Modifier.padding(top = 3.dp)
                )
                if (!ui.optimal) {
                    Text(
                        text = tr(Tr.NOT_OPTIMAL_HINT),
                        style = WTypography.labelSmall.copy(color = WColor.faint, textAlign = TextAlign.Center),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (!headlineNumberMode) {
                Meter(
                    fill = ui.match.toFloat() / 100f,
                    color = if (ui.match.toInt() == 100) WColor.success else WColor.warning,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        }
    }
}

@Composable
private fun SpellRotationCard(ui: UiState) {
    val rotation = ui.spellRotation ?: return
    val lang = LocalLang.current
    ResultCard(
        title = tr(Tr.SPELL_ROTATION),
        trailing =
            if (rotation.isEmpty) {
                null
            } else {
                rotation.totalExpectedDamage
                    .toLong()
                    .toInt()
                    .formatCompact()
            }
    ) {
        if (rotation.isEmpty) {
            Text(
                text = tr(Tr.SPELL_ROTATION_EMPTY),
                style = WTypography.bodySmall.copy(color = WColor.muted)
            )
            return@ResultCard
        }
        Text(
            text = tr(Tr.SPELL_ROTATION_SUB),
            style = WTypography.labelSmall.copy(color = WColor.faint),
            modifier = Modifier.padding(bottom = 6.dp)
        )
        rotation.debuffCasts.forEach { cast ->
            Text(
                text =
                    "↳ ${cast.spell.name.let { if (lang == Lang.FR) it.fr else it.en }} " +
                        "(${cast.apCost} AP, −${cast.spell.targetResistanceReductionFlat} res)",
                style = WTypography.labelSmall.copy(color = WColor.accent2),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        // The post-all-debuffs resistance, shown ONCE — not on every debuff line (it's the cumulative
        // final value, not what each individual debuff reaches).
        if (rotation.debuffCasts.isNotEmpty() && rotation.effectiveResistancePercent != null) {
            Text(
                text = "→ ${rotation.effectiveResistancePercent}% res after debuffs",
                style = WTypography.labelSmall.copy(color = WColor.accent2),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        // Mono: a flat list of casts. Bi-element (element == null): group the casts under a colored
        // per-element sub-header so the two-element split reads clearly (each cast carries its own element).
        if (rotation.element == null && rotation.casts.any { it.spell.element != null }) {
            rotation.casts.groupBy { it.spell.element }.forEach { (element, casts) ->
                Text(
                    text = element.elementLabel(),
                    style = WTypography.labelSmall.copy(color = element.elementColor(), fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
                casts.forEachIndexed { index, cast ->
                    if (index > 0) Hairline()
                    SpellCastRow(cast, lang)
                }
            }
        } else {
            rotation.casts.forEachIndexed { index, cast ->
                if (index > 0) Hairline()
                SpellCastRow(cast, lang)
            }
        }
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tr(Tr.SPELL_ROTATION_PER_TURN),
                style = WTypography.labelMedium.copy(color = WColor.muted),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${rotation.totalExpectedDamage.toLong().formatCompact()}  (${rotation.apUsed}/${rotation.apBudget} AP)",
                style = WTypography.bodyMedium.copy(fontFamily = WType.mono)
            )
        }
        // Turns-to-kill against the targeted boss (its HP scaled by the difficulty multiplier; display only).
        ui.selectedBoss?.takeIf { rotation.totalExpectedDamage > 0 }?.let { boss ->
            val turns =
                BossDisplay.turnsToKill(
                    monster = boss,
                    expectedDamagePerTurn = rotation.totalExpectedDamage.toBigDecimal(),
                    hpMultiplier = (ui.bossDifficulty.toIntOrNull() ?: 1).coerceAtLeast(1).toDouble()
                )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${tr(Tr.TURNS_TO_KILL)} · ${boss.name.fr.ifBlank { boss.name.en }}",
                    style = WTypography.labelMedium.copy(color = WColor.muted),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$turns",
                    style = WTypography.bodyMedium.copy(fontFamily = WType.mono, color = WColor.accent)
                )
            }
        }
        Text(
            text = tr(Tr.SPELL_ROTATION_NOTE),
            style = WTypography.labelSmall.copy(color = WColor.faint),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** One spell-cast line in the rotation card: `N× name … AP … ~damage`. Shared by the mono and bi-element layouts. */
@Composable
private fun SpellCastRow(
    cast: me.chosante.autobuilder.domain.SpellCast,
    lang: Lang,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${cast.count}×",
            style = WTypography.bodyMedium.copy(fontFamily = WType.mono, color = WColor.accent)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (lang == Lang.FR) cast.spell.name.fr else cast.spell.name.en,
            style = WTypography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${cast.apCost} AP",
            style = WTypography.labelSmall.copy(color = WColor.muted, fontFamily = WType.mono)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "~${cast.totalExpectedDamage.toLong().formatCompact()}",
            style = WTypography.bodyMedium.copy(fontFamily = WType.mono, color = WColor.text)
        )
    }
}

/** Display name for a spell element's rotation sub-header (null = unknown). */
private fun SpellElement?.elementLabel(): String =
    when (this) {
        SpellElement.FIRE -> "Fire"
        SpellElement.WATER -> "Water"
        SpellElement.EARTH -> "Earth"
        SpellElement.AIR -> "Air"
        null -> "—"
    }

/** Accent color for a spell element's rotation sub-header. */
private fun SpellElement?.elementColor(): Color =
    when (this) {
        SpellElement.FIRE -> WColor.fire
        SpellElement.WATER -> WColor.water
        SpellElement.EARTH -> WColor.earth
        SpellElement.AIR -> WColor.air
        null -> WColor.muted
    }

@Composable
private fun DesiredVsAchieved(ui: UiState) {
    ResultCard(title = tr(Tr.DESIRED_VS_ACHIEVED)) {
        groupedTargets(ui.targets).forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) {
                Hairline()
            }
            Text(
                text = tr(group.title),
                style = WTypography.labelMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(top = if (groupIndex == 0) 0.dp else 10.dp, bottom = 2.dp)
            )
            group.targets.forEachIndexed { index, target ->
                if (index > 0) Hairline()
                StatRow(
                    target = target,
                    achieved = ui.achieved[target.characteristic] ?: 0,
                    mode = ui.mode
                )
            }
        }
    }
}

@Composable
private fun MasterySummary(ui: UiState) {
    val elementalMasteries =
        listOf(
            Characteristic.MASTERY_ELEMENTARY_WATER,
            Characteristic.MASTERY_ELEMENTARY_FIRE,
            Characteristic.MASTERY_ELEMENTARY_EARTH,
            Characteristic.MASTERY_ELEMENTARY_WIND
        )
    val specializedMasteries =
        listOf(
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_MELEE,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_HEALING
        )
    val requested = ui.targets.map { it.characteristic }.toSet()

    // Requested masteries are what the headline score is built from; everything else the build carries
    // (via gear/runes) is real but incidental — shown in its own muted group so the lists don't look
    // like they should sum to the headline. Requested ones always show (even at 0); incidental ones
    // only when non-zero.
    fun valuesFor(
        masteries: List<Characteristic>,
        requestedOnes: Boolean,
    ) = masteries
        .filter { (it in requested) == requestedOnes && (requestedOnes || (ui.achieved[it] ?: 0) != 0) }
        .map { it to (ui.achieved[it] ?: 0) }

    val requestedElementals = valuesFor(elementalMasteries, requestedOnes = true)
    val requestedSpecialized = valuesFor(specializedMasteries, requestedOnes = true)
    val incidental = valuesFor(elementalMasteries + specializedMasteries, requestedOnes = false)

    // The engine-faithful number: requested specialized summed + the weakest *requested* element.
    val requestedMastery = ui.requestedMasteryTotal()

    ResultCard(
        title = tr(Tr.MASTERY_SUMMARY),
        trailing = requestedMastery.formatCompact()
    ) {
        SummaryMetric(label = tr(Tr.BUILD_MASTERY), value = requestedMastery)
        Text(
            text = tr(Tr.BUILD_MASTERY_HINT),
            style = WTypography.labelSmall.copy(color = WColor.faint),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        if (requestedElementals.isNotEmpty()) {
            Hairline()
            MasteryGroup(title = tr(Tr.MASTERY_ELEMENTALS), values = requestedElementals)
        }
        if (requestedSpecialized.isNotEmpty()) {
            Hairline()
            MasteryGroup(title = tr(Tr.MASTERY_SPECIALIZED), values = requestedSpecialized)
        }
        if (incidental.isNotEmpty()) {
            Hairline()
            MasteryGroup(title = tr(Tr.MASTERY_INCIDENTAL), values = incidental, muted = true)
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = WTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value.formatCompact(),
            style = WTypography.headlineMedium.copy(fontFamily = WType.mono, color = WColor.text)
        )
    }
}

/**
 * The full "rest of the build" sheet: every resulting characteristic the build actually carries that
 * is **not** one of your requested targets (those are in Desired vs Achieved) and isn't already in the
 * mastery summary. Surfaces the incidental stats — and especially any negatives the gear introduced on
 * things you didn't ask for — so you can check the build doesn't quietly break something.
 */
@Composable
private fun BuildSheet(ui: UiState) {
    val requested = ui.targets.map { it.characteristic }.toSet()
    val shownMasteries =
        setOf(
            Characteristic.MASTERY_ELEMENTARY_WATER,
            Characteristic.MASTERY_ELEMENTARY_FIRE,
            Characteristic.MASTERY_ELEMENTARY_EARTH,
            Characteristic.MASTERY_ELEMENTARY_WIND,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_MELEE,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_HEALING
        ).filter { it in requested || (ui.achieved[it] ?: 0) != 0 }.toSet()
    val rows =
        ui.achieved
            .filterValues { it != 0 }
            .filterKeys { it !in requested && it !in shownMasteries && !it.isEngineInternalStat() }
            .entries
            .sortedBy { it.key.ordinal }
    ResultCard(title = tr(Tr.BUILD_SHEET_TITLE)) {
        if (rows.isEmpty()) {
            Text(
                text = tr(Tr.BUILD_SHEET_EMPTY),
                style = WTypography.bodyMedium.copy(color = WColor.muted),
                modifier = Modifier.padding(vertical = 6.dp)
            )
        } else {
            rows.forEachIndexed { index, (characteristic, value) ->
                if (index > 0) Hairline()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CharacteristicIcon(characteristic = characteristic, size = 16.dp)
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(
                        text = characteristic.label(LocalLang.current),
                        style = WTypography.bodyMedium.copy(color = if (value < 0) WColor.danger else WColor.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (value > 0) "+${value.formatCompact()}" else value.formatCompact(),
                        style =
                            WTypography.bodyMedium.copy(
                                fontFamily = WType.mono,
                                fontWeight = FontWeight.SemiBold,
                                color = if (value < 0) WColor.danger else WColor.muted
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun MasteryGroup(
    title: String,
    values: List<Pair<Characteristic, Int>>,
    muted: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = WTypography.labelSmall.copy(color = WColor.muted))
        values.forEach { (characteristic, value) ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CharacteristicIcon(characteristic = characteristic, size = 15.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = characteristic.label(LocalLang.current),
                    style = WTypography.bodySmall.copy(color = if (muted) WColor.faint else WColor.text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = value.formatCompact(),
                    style = WTypography.bodySmall.copy(fontFamily = WType.mono, color = WColor.muted)
                )
            }
        }
    }
}

internal data class TargetGroup(
    val title: Tr,
    val targets: List<TargetRow>,
)

internal fun groupedTargets(targets: List<TargetRow>): List<TargetGroup> {
    // Display targets in the catalog's canonical order (mirroring the request panel on the left)
    // rather than the user's insertion order — otherwise removing and re-adding a constraint would
    // shuffle it to the bottom of its group. Sort once; the per-group filters preserve that order.
    val ordered = targets.sortedBy { statCatalogOrder[it.characteristic] ?: Int.MAX_VALUE }
    val groups =
        listOf(
            TargetGroup(Tr.STAT_GROUP_CORE, ordered.filter { it.characteristic in coreTargetStats }),
            TargetGroup(Tr.MASTERY_ELEMENTALS, ordered.filter { it.characteristic in elementalTargetMasteries }),
            TargetGroup(Tr.MASTERY_SPECIALIZED, ordered.filter { it.characteristic in specializedTargetMasteries }),
            TargetGroup(Tr.STAT_GROUP_RESISTANCES, ordered.filter { it.characteristic.name.startsWith("RESISTANCE") }),
            TargetGroup(
                Tr.STAT_GROUP_SECONDARY,
                ordered.filter {
                    it.characteristic !in coreTargetStats &&
                        it.characteristic !in allTargetMasteries &&
                        !it.characteristic.name.startsWith("RESISTANCE")
                }
            )
        )
    return groups.filter { it.targets.isNotEmpty() }
}

/** Canonical display rank per characteristic, from the shared [statCatalog] order. */
private val statCatalogOrder: Map<Characteristic, Int> =
    statCatalog.withIndex().associate { (index, def) -> def.characteristic to index }

private val coreTargetStats =
    setOf(
        Characteristic.ACTION_POINT,
        Characteristic.MOVEMENT_POINT,
        Characteristic.RANGE,
        Characteristic.WAKFU_POINT,
        Characteristic.CRITICAL_HIT,
        Characteristic.HP
    )

private val elementalTargetMasteries =
    setOf(
        Characteristic.MASTERY_ELEMENTARY,
        Characteristic.MASTERY_ELEMENTARY_WATER,
        Characteristic.MASTERY_ELEMENTARY_FIRE,
        Characteristic.MASTERY_ELEMENTARY_EARTH,
        Characteristic.MASTERY_ELEMENTARY_WIND
    )

private val specializedTargetMasteries =
    setOf(
        Characteristic.MASTERY_DISTANCE,
        Characteristic.MASTERY_MELEE,
        Characteristic.MASTERY_CRITICAL,
        Characteristic.MASTERY_BACK,
        Characteristic.MASTERY_BERSERK,
        Characteristic.MASTERY_HEALING
    )

private val allTargetMasteries = elementalTargetMasteries + specializedTargetMasteries

@Composable
private fun StatRow(
    target: TargetRow,
    achieved: Int,
    mode: ScoreComputationMode,
) {
    val targetValue = target.value.toIntOrNull() ?: 0
    val exact = target.isExact(mode)
    val status =
        when {
            exact && achieved >= targetValue -> StatStatus.Ok
            exact -> StatStatus.Miss
            targetValue > 0 && achieved >= targetValue -> StatStatus.Ok
            targetValue > 0 -> StatStatus.Miss
            else -> StatStatus.Over
        }
    val progress = if (targetValue > 0) (achieved.toFloat() / targetValue).coerceIn(0f, 1f) else 1f
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatGlyph(characteristic = target.characteristic, label = target.glyph, color = target.color)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = target.characteristic.label(LocalLang.current),
                    style = WTypography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = tr(if (exact) Tr.TAG_EXACT else Tr.TAG_MAXIMIZE),
                    style = WTypography.labelSmall
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = achieved.formatCompact(),
                    style =
                        WTypography.bodyMedium.copy(
                            fontFamily = WType.mono,
                            fontWeight = FontWeight.SemiBold,
                            color = if (status == StatStatus.Miss) WColor.warning else WColor.text
                        )
                )
                if (targetValue > 0) {
                    Text(text = " / ", style = WTypography.bodySmall.copy(color = WColor.faint))
                    Text(
                        text = targetValue.formatCompact(),
                        style = WTypography.bodySmall.copy(fontFamily = WType.mono, color = WColor.muted)
                    )
                }
            }
            Text(
                text = status.icon,
                style =
                    WTypography.bodyMedium.copy(
                        color = status.color,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    ),
                modifier = Modifier.width(18.dp)
            )
        }
        if (targetValue > 0) {
            Meter(
                fill = progress,
                color = status.color,
                modifier = Modifier.padding(top = 8.dp).height(3.dp)
            )
        }
    }
}

@Composable
private fun SkillTree(skills: CharacterSkills) {
    ResultCard(
        title = tr(Tr.SKILL_ALLOCATION),
        trailing = tr(Tr.BRANCHES_COUNT)
    ) {
        skillBranches(skills).forEachIndexed { index, branch ->
            if (index > 0) Hairline()
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(branch.color)
                    )
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(
                        text = tr(branch.labelKey),
                        style = WTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${branch.points}/${branch.max}",
                        style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.muted)
                    )
                }
                Column(
                    modifier = Modifier.padding(start = 17.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    branch.lines.forEach { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                val bitmap = line.iconPath?.let { rememberClasspathBitmap(it) }
                                if (bitmap != null) {
                                    // Light tile behind the icon: several skill-line icons are dark,
                                    // near-monochrome line-art (shield/armor/heal, lock, control, …)
                                    // that would otherwise be invisible on the dark theme. See #127.
                                    Box(
                                        modifier =
                                            Modifier
                                                .padding(top = 1.dp)
                                                .size(20.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(WColor.iconTile),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = skillLabel(line.name, LocalLang.current),
                                    style =
                                        WTypography.bodySmall.copy(
                                            color = if (line.points > 0) WColor.text else WColor.muted,
                                            lineHeight = 15.sp
                                        ),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                text = "${line.points}/${line.maxText}",
                                style =
                                    WTypography.bodySmall.copy(
                                        fontFamily = WType.mono,
                                        color = if (line.points > 0) WColor.muted else WColor.faint
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsCard(
    ui: UiState,
    onOpenZenith: () -> Unit,
    onCopyZenith: () -> Unit,
    onSaveBuild: () -> Unit,
    onExport: () -> Unit,
) {
    ResultCard {
        if (ui.error != null) {
            ErrorBanner(error = ui.error)
            Spacer(modifier = Modifier.height(10.dp))
        }
        ActionButton(
            text = if (ui.activeBuildId != null) tr(Tr.UPDATE_BUILD) else tr(Tr.SAVE_BUILD),
            color = WColor.accent,
            contentColor = WColor.bg,
            enabled = ui.phase == Phase.Done && ui.build != null,
            onClick = onSaveBuild
        )
        Spacer(modifier = Modifier.height(9.dp))
        ActionButton(
            text = if (ui.zenith == ZenithState.Loading) tr(Tr.OPENING) else tr(Tr.OPEN_IN_ZENITH),
            color = WColor.accent2,
            contentColor = Color(0xFF072824),
            enabled = ui.phase == Phase.Done && ui.zenith != ZenithState.Loading,
            onClick = onOpenZenith
        )
        Spacer(modifier = Modifier.height(9.dp))
        ActionButton(
            text = tr(Tr.COPY_BUILD_LINK),
            color = Color.Transparent,
            contentColor = WColor.text,
            enabled = ui.phase == Phase.Done && ui.zenith != ZenithState.Loading,
            borderColor = WColor.border,
            onClick = onCopyZenith
        )
        Spacer(modifier = Modifier.height(9.dp))
        // Copies the whole build (request + result) as JSON so testers can hand it back without a
        // screenshot; re-imported from the library's Import button. Independent of the Zenith link.
        ActionButton(
            text = tr(Tr.EXPORT_BUILD),
            color = Color.Transparent,
            contentColor = WColor.accent2,
            enabled = ui.phase == Phase.Done && ui.build != null,
            borderColor = WColor.border,
            onClick = onExport
        )
        ui.zenithUrl?.let { link ->
            Text(
                text = link,
                style = WTypography.labelSmall.copy(fontFamily = WType.mono, color = WColor.accent2),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        ui.toast?.let { toast ->
            Text(
                text = toast,
                style = WTypography.labelSmall.copy(color = WColor.success),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    ResultCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = tr(Tr.NO_BUILD_YET),
                style = WTypography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = tr(Tr.NO_BUILD_HINT),
                style =
                    WTypography.bodyMedium.copy(
                        color = WColor.muted,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center
                    ),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    color: Color,
    contentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    borderColor: Color = color,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .clip(RoundedCornerShape(10.dp))
                .background(color)
                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style =
                WTypography.labelLarge.copy(
                    color = contentColor,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
        )
    }
}

@Composable
private fun ErrorBanner(error: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(WColor.danger.copy(alpha = 0.1f))
                .border(1.dp, WColor.danger.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = "!", style = WTypography.bodyMedium.copy(color = WColor.danger))
        Text(
            text = error,
            style = WTypography.bodySmall.copy(color = WColor.text),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ResultCard(
    title: String? = null,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WDimens.radius))
                .background(WColor.surface)
                .border(1.dp, WColor.hairline, RoundedCornerShape(WDimens.radius))
                .padding(WDimens.pad)
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, style = WTypography.labelMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f).height(1.dp).background(WColor.hairline))
                if (trailing != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = trailing, style = WTypography.labelSmall.copy(fontFamily = WType.mono))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
private fun SublimationsResult(ui: UiState) {
    val subs =
        ui.build
            ?.sublimations
            ?.values
            ?.flatten()
            .orEmpty()
    if (subs.isEmpty()) return
    ResultCard(title = tr(Tr.CHOSEN_SUBLIMATIONS), trailing = subs.size.toString()) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            subs.forEach { sub ->
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = sub.name.let { if (ui.lang == me.chosante.ui.i18n.Lang.FR) it.fr else it.en }, style = WTypography.labelMedium.copy(color = WColor.text))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = sub.rarity.name, style = WTypography.labelSmall.copy(color = WColor.muted, fontFamily = WType.mono))
                    }
                    sub.rawText?.takeIf { it.isNotBlank() }?.let {
                        Text(text = it, style = WTypography.labelSmall.copy(color = WColor.muted))
                    }
                }
            }
        }
    }
}

/** The selected passive loadout, each as an icon + name (+ flat stats), with the in-game text on hover. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PassivesResult(ui: UiState) {
    val passives = ui.build?.passives.orEmpty()
    if (passives.isEmpty()) return
    ResultCard(title = tr(Tr.CHOSEN_PASSIVES), trailing = passives.size.toString()) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            passives.forEach { passive ->
                androidx.compose.foundation.TooltipArea(
                    delayMillis = 300,
                    tooltip = {
                        passive.description?.takeIf { it.isNotBlank() }?.let { desc ->
                            Box(
                                modifier =
                                    Modifier
                                        .widthIn(max = 340.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(WColor.raised)
                                        .border(1.dp, WColor.border, RoundedCornerShape(7.dp))
                                        .padding(horizontal = 9.dp, vertical = 6.dp)
                            ) {
                                Text(text = desc, style = WTypography.labelSmall.copy(color = WColor.text))
                            }
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PassiveIcon(gfxId = passive.gfxId, size = 24.dp)
                        Text(
                            text = passive.name ?: passive.spellId.toString(),
                            style = WTypography.labelMedium.copy(color = WColor.text),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        val flat = passive.flatStats.entries.joinToString("  ") { "+${it.value} ${it.key.name}" }
                        if (flat.isNotBlank()) {
                            Text(
                                text = flat,
                                style = WTypography.labelSmall.copy(color = WColor.accent2, fontFamily = WType.mono),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatGlyph(
    characteristic: Characteristic,
    label: String,
    color: Color,
) {
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                // Light tile so dark line-art stat symbols stay legible on the dark theme (see WColor.iconTile).
                .background(WColor.iconTile)
                .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center
    ) {
        StatGlyphIcon(characteristic = characteristic, glyph = label, color = color, iconSize = 18.dp)
    }
}

@Composable
private fun Meter(
    fill: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(WColor.raised)
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(fill.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(color)
        )
    }
}

@Composable
private fun Hairline() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
}

private enum class StatStatus(
    val icon: String,
    val color: Color,
) {
    Ok("✓", WColor.success),
    Miss("▾", WColor.warning),
    Over("↑", WColor.accent2),
}

private data class SkillBranch(
    val labelKey: Tr,
    val color: Color,
    val points: Int,
    val max: Int,
    val lines: List<SkillLine>,
)

private data class SkillLine(
    val name: String,
    val points: Int,
    val maxText: String,
    val iconPath: String?,
)

private fun skillBranches(skills: CharacterSkills): List<SkillBranch> =
    listOf(
        branch(Tr.BRANCH_INTELLIGENCE, WColor.earth, skills.intelligence),
        branch(Tr.BRANCH_STRENGTH, WColor.fire, skills.strength),
        branch(Tr.BRANCH_AGILITY, WColor.accent2, skills.agility),
        branch(Tr.BRANCH_LUCK, Color(0xFFE8C24A), skills.luck),
        branch(Tr.BRANCH_MAJOR, WColor.accent, skills.major)
    )

private fun branch(
    labelKey: Tr,
    color: Color,
    assignable: Assignable<*>,
): SkillBranch =
    SkillBranch(
        labelKey = labelKey,
        color = color,
        points = assignable.pointsAssigned(),
        max = assignable.maxPointsToAssign,
        lines = assignable.getCharacteristics().map { it.toSkillLine() }
    )

private fun SkillCharacteristic.toSkillLine(): SkillLine =
    SkillLine(
        name = name,
        points = pointsAssigned,
        maxText = if (maxPointsAssignable == Int.MAX_VALUE) "∞" else maxPointsAssignable.toString(),
        iconPath = iconResourcePath()
    )
