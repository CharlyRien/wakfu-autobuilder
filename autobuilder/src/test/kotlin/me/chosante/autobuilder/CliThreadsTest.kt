package me.chosante.autobuilder

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.parsers.CommandLineParser
import me.chosante.autobuilder.genetic.wakfu.ComputeBudget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CliThreadsTest {
    private fun budgetOf(vararg argv: String): ComputeBudget {
        val command = WakfuAutobuild()
        var budget: ComputeBudget? = null
        CommandLineParser.parseAndRun(command, argv.toList()) { budget = command.computeBudget }
        return requireNotNull(budget)
    }

    @Test
    fun `threads parses into the same budget used by searches and proofs`() {
        assertThat(budgetOf().logicalCores).isEqualTo(ComputeBudget.availableCores)
        for (n in 1..ComputeBudget.availableCores) {
            assertThat(budgetOf("--threads", n.toString())).isEqualTo(ComputeBudget(n))
        }
    }

    @Test
    fun `threads rejects invalid values before the command runs`() {
        for (value in listOf("0", "-1", "many", (ComputeBudget.availableCores + 1).toString())) {
            assertThrows<BadParameterValue> { budgetOf("--threads", value) }
        }
        assertThat(WakfuAutobuild().getFormattedHelp()).contains("--threads", "optimality proofs", "all cores")
    }
}
