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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.state.ChangeType
import me.chosante.ui.state.ReleaseNoteLine
import me.chosante.ui.state.ReleaseNotes
import me.chosante.ui.state.ReleaseNotesSection
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WTypography

/**
 * Once-per-version release-notes pop-in, shown over the shell on the first launch after an update
 * (see [me.chosante.ui.state.WhatsNew] for the gating). When the user skipped releases (e.g. a
 * 1.7 → 1.10 jump), every unseen release is stacked newest-first with its own version header.
 * Player-facing notes (from `changes/`) show in the UI language under New / Fixes / Faster; the
 * older releases that predate them keep their English release-please CHANGELOG bullets, under the
 * translated release-please headings.
 */
@Composable
fun WhatsNewDialog(
    releases: List<ReleaseNotes>,
    onDismiss: () -> Unit,
) {
    val lang = LocalLang.current
    val title =
        if (releases.size == 1) "${tr(Tr.WHATS_NEW_TITLE)} ${releases.single().version}" else tr(Tr.WHATS_NEW_TITLE)
    Scrim(onDismiss = onDismiss) {
        ModalCard(title = title) {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                releases.forEachIndexed { releaseIndex, notes ->
                    if (releases.size > 1) {
                        if (releaseIndex > 0) {
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        Text(
                            text = notes.version,
                            style = WTypography.titleMedium.copy(color = WColor.accent, fontWeight = FontWeight.Bold)
                        )
                    }
                    notes.sections.forEachIndexed { sectionIndex, section ->
                        if (sectionIndex > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = sectionHeading(section),
                            style = WTypography.labelMedium.copy(color = WColor.muted, fontWeight = FontWeight.SemiBold)
                        )
                        section.items.forEach { item ->
                            val scope = scopeLabel(item)
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(
                                    text = "•",
                                    style = WTypography.bodyMedium.copy(color = WColor.accent)
                                )
                                Text(
                                    text =
                                        buildAnnotatedString {
                                            if (scope != null) {
                                                withStyle(SpanStyle(color = WColor.muted, fontWeight = FontWeight.SemiBold)) { append("$scope · ") }
                                            }
                                            append(item.text(lang))
                                        },
                                    style = WTypography.bodyMedium.copy(color = WColor.text, lineHeight = 19.sp),
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
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

/**
 * New / Fixes / Faster for player-facing notes; for a CHANGELOG section, its known release-please
 * heading translated, anything else as written.
 */
@Composable
private fun sectionHeading(section: ReleaseNotesSection): String =
    when (section.type) {
        ChangeType.FEAT -> tr(Tr.WHATS_NEW_NEW)
        ChangeType.FIX -> tr(Tr.WHATS_NEW_FIXED)
        ChangeType.PERF -> tr(Tr.WHATS_NEW_FASTER)
        null ->
            when (section.title) {
                "Features" -> tr(Tr.WHATS_NEW_FEATURES)
                "Bug Fixes" -> tr(Tr.WHATS_NEW_FIXES)
                "Performance Improvements" -> tr(Tr.WHATS_NEW_PERF)
                else -> section.title
            }
    }

/** A label for a note limited to another front-end (the CLI); none for the app's own (`gui`) or unscoped notes. */
@Composable
private fun scopeLabel(item: ReleaseNoteLine): String? =
    when (val scope = item.scope) {
        null, "gui" -> null
        "cli" -> tr(Tr.WHATS_NEW_SCOPE_CLI)
        else -> scope.uppercase()
    }
