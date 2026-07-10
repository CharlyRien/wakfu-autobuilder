package me.chosante.bdataextractor

import me.chosante.common.Characteristic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Verifies the first-party characteristic-id map ([CharacIdCatalog]) read from the obfuscated client enum. The
 * anchor ids are the action-913 *source* ids observed in real sublimations (Featherweight reads MP=3, Unraveling
 * CRITICAL_BONUS=52, Colossal BLOCK=87, Topology II DODGE=8) — cross-checked against the enum `<clinit>`.
 * Install-gated (reads the local client jar), so it is skipped in CI.
 */
class CharacIdCatalogTest {
    private val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")

    @Test
    fun `reads characteristic ids first-party from the client enum`() {
        assumeTrue(File(install, "lib/wakfu-client.jar").isFile, "no local Wakfu install")
        val catalog = CharacIdCatalog.load(install)

        assertEquals("MP", catalog.scriptNameFor(3))
        assertEquals("CRITICAL_BONUS", catalog.scriptNameFor(52))
        assertEquals("BLOCK", catalog.scriptNameFor(87))
        assertEquals("DODGE", catalog.scriptNameFor(8))

        assertEquals(Characteristic.MOVEMENT_POINT, catalog.characteristicFor(3))
        assertEquals(Characteristic.MASTERY_CRITICAL, catalog.characteristicFor(52))
        assertEquals(Characteristic.BLOCK_PERCENTAGE, catalog.characteristicFor(87))
        assertEquals(Characteristic.DODGE, catalog.characteristicFor(8))
    }
}
