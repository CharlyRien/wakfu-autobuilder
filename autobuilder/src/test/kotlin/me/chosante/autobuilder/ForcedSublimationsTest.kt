package me.chosante.autobuilder

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class ForcedSublimationsTest {
    @Test
    fun `parse chosen levels and preserve the max default`() {
        val (names, levels) = parseForcedSublimations(listOf("Neutralité III:2", "Carnage III"))
        assertThat(names).containsExactly("Neutralité III", "Carnage III")
        assertThat(levels).containsExactlyEntriesOf(mapOf("Neutralité III" to 2))
        assertThat(parseForcedSublimations(listOf("Neutrality III:2")).second).containsEntry("Neutralité III", 2)
    }

    @Test
    fun `reject invalid suffixes and unreachable levels clearly`() {
        for (suffix in listOf("x", "0", "5")) {
            assertThatIllegalArgumentException()
                .isThrownBy {
                    parseForcedSublimations(listOf("Neutralité III:$suffix"))
                }.withMessageContaining("level")
        }
        assertThatIllegalArgumentException().isThrownBy { parseForcedSublimations(listOf("Ravage secondaire II:3")) }.withMessageContaining("2, 4, 6")
    }
}
