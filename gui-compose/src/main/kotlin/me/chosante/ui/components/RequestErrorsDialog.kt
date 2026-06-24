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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.chosante.autobuilder.genetic.wakfu.RequestValidationProblem
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.LocalLang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.i18n.tr
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WDimens
import me.chosante.ui.theme.WTypography

/**
 * Pre-search validation pop-up: lists EVERY problem with the current request (a non-equippable forced item,
 * more than one epic/relic forced sublimation, contradictory level bounds) so the user fixes them before
 * searching — instead of a single error buried in the results-panel banner. Driven by
 * [me.chosante.ui.state.UiState.requestErrors]; dismissed via [me.chosante.ui.state.BuildSearchModel.dismissRequestErrors].
 */
@Composable
fun RequestErrorsDialog(
    problems: List<RequestValidationProblem>,
    onDismiss: () -> Unit,
) {
    val lang = LocalLang.current
    Scrim(onDismiss = onDismiss) {
        ModalCard(title = tr(Tr.REQUEST_ERRORS_TITLE)) {
            Text(
                text = tr(Tr.REQUEST_ERRORS_INTRO),
                style = WTypography.bodyMedium.copy(color = WColor.muted)
            )
            Spacer(modifier = Modifier.height(WDimens.gap))
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                problems.forEach { problem ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(
                            text = "•",
                            style = WTypography.bodyMedium.copy(color = WColor.danger)
                        )
                        Text(
                            text = problem.localizedMessage(lang),
                            style = WTypography.bodyMedium.copy(color = WColor.text, lineHeight = 19.sp),
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(WDimens.gap))
            DialogButton(
                text = tr(Tr.REQUEST_ERRORS_DISMISS),
                filled = true,
                color = WColor.accent,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Localized one-line message for a single request problem; named items/sublimations are interpolated. */
internal fun RequestValidationProblem.localizedMessage(lang: Lang): String =
    when (this) {
        is RequestValidationProblem.LevelRangeInvalid -> Tr.LEVEL_RANGE_INVALID.value(lang)
        is RequestValidationProblem.ForcedItemNotEquippable -> {
            val name = if (lang == Lang.FR) item.name.fr else item.name.en
            "$name ${Tr.FORCED_ITEM_NOT_EQUIPPABLE.value(lang)}"
        }
        is RequestValidationProblem.ForcedSublimationRarityExceeded -> {
            val names = sublimations.joinToString { if (lang == Lang.FR) it.fr else it.en }
            "${Tr.FORCED_SUBLIMATION_RARITY_INVALID.value(lang)} $names"
        }
    }
