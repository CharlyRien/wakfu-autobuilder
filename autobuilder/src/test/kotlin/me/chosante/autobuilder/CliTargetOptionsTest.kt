package me.chosante.autobuilder

import com.github.ajalt.clikt.parsers.CommandLineParser
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The CLI's stat options → the [TargetStats] the engine receives. Parsed only: Clikt's `parseAndRun` hook
 * replaces [WakfuAutobuild.run], so no search starts.
 */
class CliTargetOptionsTest {
    private fun targetsOf(vararg argv: String): List<Pair<Characteristic, Int>> {
        val command = WakfuAutobuild()
        var targets: TargetStats? = null
        CommandLineParser.parseAndRun(command, argv.toList()) { targets = command.requestedTargetStats() }
        return requireNotNull(targets).map { it.characteristic to it.target }
    }

    @Test
    fun `every wakfu-point alias builds a WAKFU_POINT target`() {
        for (alias in listOf("--wp", "--wakfu-point", "--pw")) {
            assertThat(targetsOf(alias, "6")).describedAs(alias).containsExactly(Characteristic.WAKFU_POINT to 6)
        }
    }

    @Test
    fun `wakfu points and movement points stay two distinct targets`() {
        // Regression: --wp built a SECOND movement-point target, so `--mp 5 --wp 6` asked for MP 5 AND MP 6 and no WP.
        assertThat(targetsOf("--mp", "5", "--wp", "6"))
            .containsExactlyInAnyOrder(Characteristic.MOVEMENT_POINT to 5, Characteristic.WAKFU_POINT to 6)
    }
}
