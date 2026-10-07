package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import me.chosante.common.Spell
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class SpellElementAuditTest {
    @Test
    fun `light arrow belongs to water branch but its damage is light`() {
        val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")
        assumeTrue(File(install, "contents/bdata/66.jar").isFile, "no local Wakfu install")
        val spells = loadTable(install, Tables.SPELL, Tables.SPELL_SCHEMA)
        val effects = loadTable(install, Tables.STATIC_EFFECT, Tables.STATIC_EFFECT_SCHEMA)
        val encyclopedia = LENIENT_JSON.decodeFromString(ListSerializer(Spell.serializer()), File(findRepositoryRoot(), "autobuilder/src/main/resources/spells.json").readText())
        assertEquals(2, spells.records.single { it["id"] == 5594 }["spell_branch"])
        val rows = spellDamageElementAudit(spells, effects, encyclopedia)
        assertEquals("5594,LIGHT,LIGHT,true", rows.single { it.startsWith("5594,") })
        assertEquals(286, rows.size)
        assertEquals(250, rows.count { it.endsWith(",true") })
    }
}
