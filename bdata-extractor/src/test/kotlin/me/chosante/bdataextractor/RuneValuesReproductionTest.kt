package me.chosante.bdataextractor

import me.chosante.common.RuneValues
import me.chosante.common.WakfuData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class RuneValuesReproductionTest {
    @Test
    fun `official formulas level bands and rounding reproduce every rune value`() {
        val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")
        assumeTrue(File(install, "lib/wakfu-client.jar").isFile, "no local Wakfu install")
        val actual = buildRuneValues(install, ItemsCatalog.fetchItemsJson(WakfuData.VERSION), ActionCatalog.fetch(WakfuData.VERSION))
        assertEquals(RuneValues.embedded, actual)
    }
}
