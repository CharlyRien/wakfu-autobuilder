package me.chosante.bdataextractor

import me.chosante.common.EquipmentPositions
import me.chosante.common.WakfuData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class EquipmentPositionsReproductionTest {
    @Test
    fun `client enum and CDN occupied positions reproduce the committed catalog`() {
        val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")
        assumeTrue(File(install, "lib/wakfu-client.jar").isFile, "no local Wakfu install")
        assertEquals(EquipmentPositions.embedded, buildEquipmentPositions(install, fetchEquipmentTypes(WakfuData.VERSION)))
    }
}
