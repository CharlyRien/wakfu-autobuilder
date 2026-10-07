package me.chosante.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The text colors stay readable on the three surfaces the UI is built from. The tertiary `faint` color (hints, units, the 10 sp
 * labels under values) was #5F656F, which measured 2.45–3.0:1 on the cards — under the 4.5:1 WCAG asks of small text.
 */
class ThemeContrastTest {
    /** WCAG 2.x contrast ratio between two opaque colors. */
    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val lighter = maxOf(a.luminance(), b.luminance()).toDouble()
        val darker = minOf(a.luminance(), b.luminance()).toDouble()
        return (lighter + 0.05) / (darker + 0.05)
    }

    private val surfaces = mapOf("bg" to WColor.bg, "surface" to WColor.surface, "raised" to WColor.raised)

    @Test
    fun `tertiary text reaches 4_5 to 1 on every surface`() {
        for ((name, surface) in surfaces) {
            assertThat(contrast(WColor.faint, surface)).describedAs("faint on $name").isGreaterThanOrEqualTo(4.5)
        }
    }

    @Test
    fun `secondary and primary text do too`() {
        for ((name, surface) in surfaces) {
            assertThat(contrast(WColor.muted, surface)).describedAs("muted on $name").isGreaterThanOrEqualTo(4.5)
            assertThat(contrast(WColor.text, surface)).describedAs("text on $name").isGreaterThanOrEqualTo(4.5)
        }
    }

    @Test
    fun `the hierarchy survives the lift - primary is brighter than secondary, secondary at least as bright as tertiary`() {
        assertThat(WColor.text.luminance()).isGreaterThan(WColor.muted.luminance())
        assertThat(WColor.muted.luminance()).isGreaterThanOrEqualTo(WColor.faint.luminance())
    }
}
