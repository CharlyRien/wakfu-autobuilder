package me.chosante.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.ReleaseNotes
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WTypography

/**
 * Once-per-version release-notes pop-in, shown over the shell on the first launch after an update
 * (see [me.chosante.ui.state.WhatsNew] for the gating). Bullets come from the release-please
 * CHANGELOG, so they are English-only; section titles are the few known release-please headings
 * and get translated.
 */
@Composable
fun WhatsNewDialog(
    notes: ReleaseNotes,
    onDismiss: () -> Unit,
) {
    Scrim(onDismiss = onDismiss) {
        ModalCard(title = "${tr(Tr.WHATS_NEW_TITLE)} ${notes.version}") {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                notes.sections.forEachIndexed { sectionIndex, section ->
                    if (sectionIndex > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = sectionTitle(section.title),
                        style = WTypography.labelMedium.copy(color = WColor.muted, fontWeight = FontWeight.SemiBold)
                    )
                    section.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                text = "•",
                                style = WTypography.bodyMedium.copy(color = WColor.accent)
                            )
                            Text(
                                text = item,
                                style = WTypography.bodyMedium.copy(color = WColor.text, lineHeight = 19.sp),
                                modifier = Modifier.padding(top = 1.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(WDimens.gap))
            DialogButton(
                text = tr(Tr.WHATS_NEW_GOT_IT),
                filled = true,
                color = WColor.accent,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Translates the known release-please section headings; anything else shows as written. */
@Composable
private fun sectionTitle(raw: String): String =
    when (raw) {
        "Features" -> tr(Tr.WHATS_NEW_FEATURES)
        "Bug Fixes" -> tr(Tr.WHATS_NEW_FIXES)
        "Performance Improvements" -> tr(Tr.WHATS_NEW_PERF)
        else -> raw
    }
