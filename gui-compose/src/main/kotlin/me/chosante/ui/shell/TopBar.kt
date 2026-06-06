package me.chosante.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.CharacterClass
import me.chosante.ui.components.rememberClasspathBitmap
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.Phase
import me.chosante.ui.state.UiState
import me.chosante.ui.state.formatCompact
import me.chosante.ui.state.onlyDigits
import me.chosante.ui.state.requestedMasteryTotal
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WType
import me.chosante.ui.theme.WTypography

private fun CharacterClass.displayName(): String = name.lowercase().replaceFirstChar { it.titlecase() }

@Composable
fun TopBar(
    ui: UiState,
    onSearch: () -> Unit,
    onCancel: () -> Unit,
    onClassChange: (CharacterClass) -> Unit,
    onLevelChange: (String) -> Unit,
    onMinLevelChange: (String) -> Unit,
    onLangChange: (Lang) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().background(WColor.bg)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Brand()
            Spacer(modifier = Modifier.weight(1f))
            LangToggle(current = ui.lang, onSelect = onLangChange)
            Spacer(modifier = Modifier.width(14.dp))
            ClassDropdown(selected = ui.clazz, onSelect = onClassChange)
            Spacer(modifier = Modifier.width(10.dp))
            NumberControl(label = tr(Tr.LEVEL_SHORT), value = ui.level.toString(), onValueChange = onLevelChange)
            Spacer(modifier = Modifier.width(10.dp))
            NumberControl(label = tr(Tr.MIN_SHORT), value = ui.minLevel.toString(), onValueChange = onMinLevelChange)
            Spacer(modifier = Modifier.width(22.dp))
            TopMeter(label = tr(Tr.PROGRESS), value = "${ui.progress}%", fill = ui.progress / 100f, color = WColor.accent2)
            Spacer(modifier = Modifier.width(16.dp))
            if (ui.mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) {
                // Most-masteries: no exact-target "% match"; show the cumulated requested mastery.
                TopMeter(label = tr(Tr.MASTERY_SHORT), value = ui.requestedMasteryTotal().formatCompact(), fill = null, color = WColor.success)
            } else {
                TopMeter(label = tr(Tr.MATCH), value = "${ui.match.toInt()}%", fill = ui.match.toFloat() / 100f, color = WColor.success)
            }
            Spacer(modifier = Modifier.width(18.dp))
            SearchButton(
                searching = ui.phase == Phase.Searching,
                onClick = if (ui.phase == Phase.Searching) onCancel else onSearch
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
    }
}

@Composable
private fun Brand() {
    val wordmark = rememberClasspathBitmap("assets/branding/wordmark.png")
    if (wordmark != null) {
        Image(
            bitmap = wordmark,
            contentDescription = "Wakfu Auto-Builder",
            contentScale = ContentScale.Fit,
            // The wordmark is downscaled hard (large bitmap -> ~46dp tall); High filtering resamples
            // it smoothly instead of the default Low, which looked aliased on the fine lettering.
            filterQuality = FilterQuality.High,
            modifier = Modifier.height(46.dp).aspectRatio(wordmark.width.toFloat() / wordmark.height)
        )
    } else {
        Text(text = "Wakfu Auto-Builder", style = WTypography.headlineLarge)
    }
}

@Composable
private fun LangToggle(
    current: Lang,
    onSelect: (Lang) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.bg)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Lang.entries.forEach { lang ->
            val selected = lang == current
            Box(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) WColor.raised else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onSelect(lang) }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = lang.label,
                    style =
                        WTypography.labelMedium.copy(
                            color = if (selected) WColor.text else WColor.muted,
                            fontFamily = WType.mono
                        )
                )
            }
        }
    }
}

@Composable
private fun ClassDropdown(
    selected: CharacterClass,
    onSelect: (CharacterClass) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier =
                Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(WColor.raised)
                    .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = tr(Tr.CLASS), style = WTypography.labelMedium)
            Spacer(modifier = Modifier.width(9.dp))
            Text(text = selected.displayName(), style = WTypography.titleMedium)
            Spacer(modifier = Modifier.width(10.dp))
            Text(text = "▾", style = WTypography.labelSmall.copy(textAlign = TextAlign.Center, lineHeight = 10.sp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 420.dp),
            containerColor = WColor.surface,
            border = BorderStroke(1.dp, WColor.border)
        ) {
            CharacterClass.entries
                .filter { it != CharacterClass.UNKNOWN }
                .forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = item.displayName(),
                                style =
                                    WTypography.bodyMedium.copy(
                                        color = if (item == selected) WColor.accent else WColor.text
                                    )
                            )
                        },
                        onClick = {
                            onSelect(item)
                            expanded = false
                        }
                    )
                }
        }
    }
}

@Composable
private fun NumberControl(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(value) }
    // Keep the box in lockstep with the model's canonical (already-coerced) value. When the user types
    // something the model clamps or rejects — e.g. "999" -> 245, or a value that coerces to the one
    // already shown — snap back instead of leaving an out-of-range number on screen. Blank is left
    // untouched so the field can be cleared mid-edit.
    LaunchedEffect(value, draft) {
        if (draft.isNotBlank() && draft != value) {
            draft = value
        }
    }

    Row(
        modifier =
            Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(WColor.raised)
                .border(1.dp, WColor.border, RoundedCornerShape(9.dp))
                .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = WTypography.labelMedium)
        Spacer(modifier = Modifier.width(8.dp))
        BasicTextField(
            value = draft,
            onValueChange = { next ->
                draft = next.onlyDigits().take(3)
                if (draft.isNotBlank()) {
                    onValueChange(draft)
                }
            },
            singleLine = true,
            cursorBrush = SolidColor(WColor.accent),
            textStyle =
                WTypography.bodyMedium.copy(
                    fontFamily = WType.mono,
                    color = WColor.text,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                ),
            modifier =
                Modifier
                    .width(56.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(WColor.bg)
                    .border(1.dp, WColor.border, RoundedCornerShape(7.dp)),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    innerTextField()
                }
            }
        )
    }
}

@Composable
private fun TopMeter(
    label: String,
    value: String,
    fill: Float?,
    color: androidx.compose.ui.graphics.Color,
) {
    Column(modifier = Modifier.width(112.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = WTypography.labelSmall)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = value,
                style =
                    WTypography.labelMedium.copy(
                        fontFamily = WType.mono,
                        color = WColor.text
                    )
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (fill != null) {
            Box(
                modifier =
                    Modifier
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
        } else {
            // No meaningful 0-100 fill (e.g. a cumulated mastery total); keep the column height
            // aligned with the neighbouring meters by reserving the bar's space.
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SearchButton(
    searching: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .height(38.dp)
                .widthIn(min = 86.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (searching) WColor.raised else WColor.accent)
                .border(1.dp, if (searching) WColor.border else WColor.accentPress, RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (searching) tr(Tr.STOP) else tr(Tr.SEARCH),
            style =
                WTypography.labelLarge.copy(
                    color = if (searching) WColor.text else WColor.bg,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
        )
    }
}
