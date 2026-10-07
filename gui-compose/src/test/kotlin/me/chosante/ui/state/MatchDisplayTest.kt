package me.chosante.ui.state

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Precision's "% match" as displayed. Once a build meets every target the engine keeps scoring how far it overshoots them
 * (a raw 248.5 for a realistic request, 20 330 with the seeded "Distance mastery 1" row), which is not a percentage a player can
 * read: the display caps it at 100 and says the targets are met, while the raw value stays available for ordering builds.
 */
class MatchDisplayTest {
    @Test
    fun `below 100 the match shows its whole percent`() {
        assertThat(BigDecimal("0").displayedMatchPercent()).isEqualTo(0)
        assertThat(BigDecimal("87.9").displayedMatchPercent()).isEqualTo(87)
        assertThat(BigDecimal("99.99").displayedMatchPercent()).isEqualTo(99)
    }

    @Test
    fun `once every target is met the match never reads above 100`() {
        for (raw in listOf("100", "100.0", "100.4", "248.5", "20330", "1E+15")) {
            assertThat(BigDecimal(raw).displayedMatchPercent()).describedAs(raw).isEqualTo(100)
            assertThat(BigDecimal(raw).meetsAllTargets()).describedAs(raw).isTrue()
        }
    }

    @Test
    fun `just below 100 the targets are not met`() {
        assertThat(BigDecimal("99.99").meetsAllTargets()).isFalse()
        assertThat(BigDecimal("0").meetsAllTargets()).isFalse()
    }

    @Test
    fun `a saved match is read the same way`() {
        assertThat(87.9.displayedMatchPercent()).isEqualTo(87)
        assertThat(100.0.displayedMatchPercent()).isEqualTo(100)
        assertThat(20_330.0.displayedMatchPercent()).isEqualTo(100)
        assertThat(Double.NaN.displayedMatchPercent()).isEqualTo(0)
        assertThat(99.9.meetsAllTargets()).isFalse()
        assertThat(248.5.meetsAllTargets()).isTrue()
    }

    @Test
    fun `the display cap does not touch the raw score the state keeps`() {
        val ui = UiState(match = BigDecimal("248.5"))

        assertThat(ui.match).isEqualByComparingTo(BigDecimal("248.5"))
        assertThat(ui.match.displayedMatchPercent()).isEqualTo(100)
    }
}
