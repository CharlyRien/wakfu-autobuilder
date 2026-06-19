package me.chosante.bdataextractor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Install-gated end-to-end check of the monster pipeline (bytecode-derive schema → decode → i18n names →
 * build). Skipped in CI (no game install); the full decode is also re-validated at extraction time by
 * [verifyAndWriteMonsters] against the committed oracle. Confirms: the Monster (42) schema auto-derived from
 * the client bytecode decodes the whole table (size-guard), names resolve from i18n, `gfx` is decoded from
 * the record, and a few known monsters carry the exact expected combat data.
 */
class MonsterDecodeTest {
    private val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")

    @Test
    fun `monster table auto-derives, decodes, and builds with i18n names, gfx and exact combat data`() {
        assumeTrue(File(install, "contents/bdata/${Tables.MONSTER}.jar").isFile, "no local Wakfu install")

        val schema = SchemaGenerator.load(install).monsterSchema(install)
        // loadTable's per-record size-guard throws on any drift, so reaching here proves the derived schema fits.
        val records = loadTable(install, Tables.MONSTER, schema).records
        assertTrue(records.size > 2000, "expected the full bestiary (~2800), got ${records.size}")

        val monsters = buildMonsters(records, I18nBundle.load(install, namespaces = setOf(7, 38)), ranks = emptyMap())
        assertTrue(monsters.size > 2000, "expected most monsters to have a localized name, got ${monsters.size}")
        assertTrue(monsters.all { it.name.fr.isNotBlank() }, "every built monster must have a FR name")

        val byId = monsters.associateBy { it.id }
        val aguabrial = byId.getValue(5165)
        assertEquals("Aguabrial", aguabrial.name.fr)
        assertEquals(200, aguabrial.level)
        assertEquals(170000, aguabrial.hp)
        assertEquals(listOf(500, 800, 500, 500), listOf(aguabrial.fireResistance, aguabrial.waterResistance, aguabrial.earthResistance, aguabrial.airResistance))
        assertEquals(137201723, aguabrial.gfx, "gfx should be decoded from bdata")

        val denver = byId.getValue(5627)
        assertEquals(31499, denver.hp)
        assertEquals(listOf(850, 825, 770, 825), listOf(denver.fireResistance, denver.waterResistance, denver.earthResistance, denver.airResistance))
        assertEquals(1114205627, denver.gfx, "gfx should be decoded from bdata")
    }
}
