package me.chosante.autobuilder.domain

import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.RuneCatalogData
import me.chosante.common.RuneType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RuneLevelRequirementsTest {
    @Test
    fun `loaded thresholds preserve every carrier level cap for every rune`() {
        val oldThresholds = listOf(0, 36, 51, 66, 81, 96, 126, 141, 171, 186, 216)
        assertEquals(oldThresholds, RuneType.RUNE_LEVEL_REQUIREMENTS)
        // The game data (runes.json, decoded from the CDN) carries exactly the same thresholds.
        assertEquals(RuneType.RUNE_LEVEL_REQUIREMENTS, RuneCatalogData.embedded.levelRequirements)
        val runes = WakfuBestBuildFinderAlgorithm.runes
        assertEquals(15, runes.size)
        for (level in -1..250) {
            val expected = oldThresholds.count { it <= level }.coerceIn(1, 11)
            assertEquals(expected, RuneType.maxLevelForItemLevel(level))
            for (rune in runes) assertEquals(expected, rune.maxLevel(level), "rune ${rune.id}, carrier $level")
        }
    }
}
