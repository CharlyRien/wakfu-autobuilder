package me.chosante.autobuilder

import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CliMatchDisplayTest {
    @Test
    fun `the precision progress line never reads above 100 percent`() {
        for (raw in listOf("100", "100.4", "248.5", "20330", "1E+15")) {
            assertThat(searchProgressLine(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, BigDecimal(raw)))
                .isEqualTo("100% match found so far")
        }
    }

    @Test
    fun `precision uses the GUI's whole percent below the cap`() {
        assertThat(searchProgressLine(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, BigDecimal("87.9")))
            .isEqualTo("87% match found so far")
        assertThat(searchProgressLine(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, BigDecimal("-2")))
            .isEqualTo("0% match found so far")
    }

    @Test
    fun `damage and mastery progress keep their score`() {
        val score = BigDecimal("248.5")
        assertThat(searchProgressLine(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE, score)).isEqualTo("expected damage so far: 248.5")
        assertThat(searchProgressLine(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, score)).isEqualTo("248.5% match found so far")
    }
}
