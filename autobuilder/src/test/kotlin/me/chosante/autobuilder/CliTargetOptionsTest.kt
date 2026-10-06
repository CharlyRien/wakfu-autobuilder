package me.chosante.autobuilder

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.parsers.CommandLineParser
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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

    private val statOptions =
        setOf(
            "--action-point",
            "--movement-point",
            "--wakfu-point",
            "--range",
            "--critical-hit",
            "--mastery-elementary",
            "--mastery-critical",
            "--mastery-water",
            "--mastery-fire",
            "--mastery-wind",
            "--mastery-earth",
            "--mastery-back",
            "--mastery-melee",
            "--mastery-berserk",
            "--mastery-healing",
            "--mastery-distance",
            "--resistance-critical",
            "--resistance-back",
            "--resistance-elementary",
            "--resistance-elementary-fire",
            "--resistance-elementary-water",
            "--resistance-elementary-wind",
            "--resistance-elementary-earth",
            "--control",
            "--wisdom",
            "--dodge",
            "--lock",
            "--prospection",
            "--initiative",
            "--willpower",
            "--received-armor",
            "--hp",
            "--block",
            "--armor-given"
        )

    private fun statAliases(): List<Set<String>> =
        WakfuAutobuild()
            .registeredOptions()
            .filter { option -> option.names.any { it in statOptions } }
            .map { it.names }
            .also { assertThat(it).hasSize(statOptions.size) }

    @Test
    fun `every stat option and alias rejects negative targets before the command runs, including weighted targets`() {
        for (names in statAliases()) {
            for (alias in names) {
                for (value in listOf("-1", "-1:3", Int.MIN_VALUE.toString())) {
                    val command = WakfuAutobuild()
                    var started = false
                    val error =
                        assertThrows<BadParameterValue> {
                            CommandLineParser.parseAndRun(command, listOf(alias, value)) { started = true }
                        }
                    assertThat(started).describedAs("$alias $value must not start a search").isFalse()
                    val message = command.getFormattedHelp(error)
                    assertThat(message).describedAs("$alias $value names the option").containsAnyOf(*names.toTypedArray())
                    assertThat(message).contains(value.substringBefore(":"), "0")
                }
            }
        }
    }

    @Test
    fun `zero floors and positive weighted targets still parse for every stat alias`() {
        for (names in statAliases()) {
            for (alias in names) {
                assertThat(targetsOf(alias, "0").single().second).describedAs(alias).isZero()
                val command = WakfuAutobuild()
                var targets: TargetStats? = null
                CommandLineParser.parseAndRun(command, listOf(alias, "5:3")) { targets = command.requestedTargetStats() }
                val target = requireNotNull(targets).single()
                assertThat(target.target).isEqualTo(5)
                assertThat(target.userDefinedWeight).isEqualTo(3)
            }
        }
    }
}
