package me.chosante.spellextractor

import me.chosante.common.SpellArea
import me.chosante.common.SpellElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Parses real, saved encyclopedia pages (no network) so the regexes stay pinned to the live HTML
 * structure. Fixtures captured June 2026 from the Cra class.
 */
class SpellScraperTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing fixture $name" }
            .readBytes()
            .decodeToString()

    @Test
    fun `parses the Cra spell listing into stubs with names and icons`() {
        val stubs = SpellScraper.parseClassListing(fixture("cra-listing.html"))
        assertTrue(stubs.size >= 38, "expected the full Cra deck, got ${stubs.size}")

        val blazing = stubs.first { it.id == 4769 }
        assertEquals("Blazing Arrow", blazing.name)
        assertTrue(blazing.isElementary)
        assertNotNull(blazing.iconId)
    }

    @Test
    fun `parses a single-target fire spell page`() {
        val d = SpellScraper.parseSpellPage(fixture("blazing-arrow.html"))
        assertEquals("Blazing Arrow", d.name)
        assertEquals(SpellElement.FIRE, d.element)
        assertEquals(2, d.apCost)
        assertEquals(2, d.rangeMin)
        assertEquals(5, d.rangeMax)
        assertEquals(60, d.baseDamage)
        assertEquals(76, d.critDamage)
        assertEquals(SpellArea.SINGLE_TARGET, d.area)
    }

    @Test
    fun `parses an area air spell and detects the area of effect`() {
        val d = SpellScraper.parseSpellPage(fixture("storm-arrow.html"))
        assertEquals("Storm Arrow", d.name)
        assertEquals(SpellElement.AIR, d.element)
        assertEquals(3, d.apCost)
        assertEquals(3, d.rangeMin)
        assertEquals(6, d.rangeMax)
        assertEquals(SpellArea.AREA, d.area)
        assertNotNull(d.baseDamage)
    }
}
