package me.chosante.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the default-window-size clamp. The window must never be created larger than the usable
 * screen, otherwise its title bar / maximise button land off-screen and it can't be moved or
 * maximised — the symptom seen on Windows laptops running >100 % display scaling.
 */
class WindowSizeTest {
    @Test
    fun `roomy screen keeps the desired size`() {
        val fitted = clampToUsable(DpSize(1440.dp, 900.dp), usableWidthPx = 1920, usableHeightPx = 1040)

        assertThat(fitted).isEqualTo(DpSize(1440.dp, 900.dp))
    }

    @Test
    fun `small or scaled screen shrinks the window so the controls stay reachable`() {
        // 1080p at 150 % scaling exposes roughly 1280x680 logical px of usable space.
        val fitted = clampToUsable(DpSize(1440.dp, 900.dp), usableWidthPx = 1280, usableHeightPx = 680)

        assertThat(fitted.width.value).isLessThanOrEqualTo(1280f)
        assertThat(fitted.height.value).isLessThanOrEqualTo(680f)
        // Leaves a margin rather than filling the screen edge-to-edge.
        assertThat(fitted.width.value).isLessThan(1280f)
        assertThat(fitted.height.value).isLessThan(680f)
    }
}
