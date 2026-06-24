package me.chosante.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.chosante.ui.theme.WColor

/** A 1.dp full-width divider line in the theme's hairline colour, shared by the result/request panels. */
@Composable
internal fun Hairline() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(WColor.hairline))
}
