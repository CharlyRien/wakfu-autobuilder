package me.chosante.ui.theme

import androidx.compose.ui.graphics.Color

object WColor {
    val bg = Color(0xFF17191E)
    val surface = Color(0xFF1E2128)
    val raised = Color(0xFF262A32)
    val border = Color(0xFF2C313A)
    val hairline = Color(0xFF24282F)
    val text = Color(0xFFE7E5E0)
    val muted = Color(0xFF969BA5)

    /**
     * Tertiary text (hints, units, placeholders, the 10 sp labels under values). The design mock-up's #5F656F measured
     * 2.45–3.0:1 on the cards — under the 4.5:1 that small text needs — so it is lifted to #8F939A: 5.2:1 on `surface`,
     * 4.7:1 on `raised`, and still a step below [muted] (which stays for secondary text).
     */
    val faint = Color(0xFF8F939A)
    val accent = Color(0xFFD98A45)
    val accentPress = Color(0xFFC2783A)
    val accent2 = Color(0xFF45B8A6)
    val success = Color(0xFF5BA86A)
    val warning = Color(0xFFD98C4D)
    val danger = Color(0xFFD9655C)
    val water = Color(0xFF5AA3DE)
    val fire = Color(0xFFD9655C)
    val earth = Color(0xFF7FAE5C)
    val air = Color(0xFFA6B6C2)

    /**
     * Light neutral backdrop for the small HUD icons that are dark, near-monochrome line-art (skill
     * tree: shield/armor/heal, lock, control, …). On the dark theme those icons would otherwise melt
     * into the background; a light tile behind every skill icon guarantees contrast regardless of the
     * individual asset's colours.
     */
    val iconTile = Color(0xFFDDDBD5)
}
