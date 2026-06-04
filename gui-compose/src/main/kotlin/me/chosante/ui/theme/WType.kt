package me.chosante.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

object WType {
    val ui =
        FontFamily(
            Font("fonts/Manrope-Regular.ttf", FontWeight.Normal),
            Font("fonts/Manrope-Medium.ttf", FontWeight.Medium),
            Font("fonts/Manrope-SemiBold.ttf", FontWeight.SemiBold),
            Font("fonts/Manrope-Bold.ttf", FontWeight.Bold)
        )

    val display = ui

    val mono =
        FontFamily(
            Font("fonts/IBMPlexMono-Regular.ttf", FontWeight.Normal),
            Font("fonts/IBMPlexMono-Medium.ttf", FontWeight.Medium),
            Font("fonts/IBMPlexMono-SemiBold.ttf", FontWeight.SemiBold),
            Font("fonts/IBMPlexMono-Bold.ttf", FontWeight.Bold)
        )
}

val WTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = WType.display,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = 0.sp,
        color = WColor.text
    ),
    headlineLarge = TextStyle(
        fontFamily = WType.display,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        letterSpacing = 0.sp,
        color = WColor.text
    ),
    headlineMedium = TextStyle(
        fontFamily = WType.display,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        letterSpacing = 0.sp,
        color = WColor.text
    ),
    headlineSmall = TextStyle(
        fontFamily = WType.display,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = WColor.text
    ),
    titleMedium = TextStyle(
        fontFamily = WType.ui,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        color = WColor.text
    ),
    bodyLarge = TextStyle(
        fontFamily = WType.ui,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        color = WColor.text
    ),
    bodyMedium = TextStyle(
        fontFamily = WType.ui,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        color = WColor.text
    ),
    bodySmall = TextStyle(
        fontFamily = WType.ui,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = WColor.muted
    ),
    labelLarge = TextStyle(
        fontFamily = WType.display,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 0.sp,
        color = WColor.text
    ),
    labelMedium = TextStyle(
        fontFamily = WType.ui,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = WColor.muted
    ),
    labelSmall = TextStyle(
        fontFamily = WType.ui,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        color = WColor.faint
    )
)
