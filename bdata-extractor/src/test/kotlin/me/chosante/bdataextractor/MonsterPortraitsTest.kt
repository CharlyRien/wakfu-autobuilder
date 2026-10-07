package me.chosante.bdataextractor

import me.chosante.common.I18nText
import me.chosante.common.Monster
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class MonsterPortraitsTest {
    @TempDir
    lateinit var destination: File

    private fun boss(
        id: Int,
        gfx: Int? = id,
        rank: Int = 1,
    ) = Monster(
        id,
        I18nText("boss", "boss", "boss", "boss"),
        1,
        100,
        rank = rank,
        gfx = gfx,
        fireResistance = 0,
        waterResistance = 0,
        earthResistance = 0,
        airResistance = 0
    )

    private fun png(size: Int = 200): ByteArray =
        ByteArrayOutputStream().use {
            ImageIO.write(BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB), "png", it)
            it.toByteArray()
        }

    @Test
    fun `fetches each boss sprite once tolerates missing portraits and prunes unused assets`() {
        File(destination, "1.png").writeBytes(png())
        File(destination, "3.png").writeText("old unavailable image")
        File(destination, "99.png").writeText("unused regular-monster image")
        File(destination, "README.md").writeText("keep non-PNG files")
        val monsters = listOf(boss(1), boss(2), boss(3), boss(4), boss(5, 1), boss(6, null), boss(99, rank = 0))
        val calls = mutableListOf<Int>()

        fun fetch(gfx: Int): PortraitResponse {
            calls.add(gfx)
            return when (gfx) {
                1, 2 -> PortraitResponse(200, png())
                3 -> PortraitResponse(403)
                4 -> PortraitResponse(404)
                else -> error("Unexpected sprite $gfx")
            }
        }
        assertEquals(PortraitReport(2, 1, 3, 1, 1), fetchMonsterPortraits(monsters, destination, ::fetch))
        assertEquals(listOf(1, 2, 3, 4), calls)
        assertFalse(File(destination, "3.png").exists())
        assertFalse(File(destination, "99.png").exists())
        assertTrue(File(destination, "README.md").exists())
        assertEquals(PortraitReport(2, 0, 3, 0, 0), fetchMonsterPortraits(monsters, destination, ::fetch))
    }

    @Test
    fun `server errors and malformed or wrong-size portraits do not mutate existing files`() {
        val prior = File(destination, "99.png").apply { writeText("prior") }
        for (response in listOf(PortraitResponse(500), PortraitResponse(200, byteArrayOf(1)), PortraitResponse(200, png(115)))) {
            assertThrows(Exception::class.java) {
                fetchMonsterPortraits(listOf(boss(1), boss(2)), destination) { if (it == 1) PortraitResponse(200, png()) else response }
            }
            assertEquals("prior", prior.readText())
            assertFalse(File(destination, "1.png").exists())
        }
        assertThrows(IllegalArgumentException::class.java) { fetchMonsterPortraits(emptyList(), destination) { error("not called") } }
        assertTrue(prior.exists())
    }
}
