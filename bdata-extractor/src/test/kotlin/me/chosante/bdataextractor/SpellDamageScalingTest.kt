package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import me.chosante.common.Spell
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Validates the bdata-derived per-level damage formula (`buildSpellDamageScalings`). The contract:
 *  1. **No regression at max level** — every spell's `baseDamageAt(levelCap)` / `critDamageAt(levelCap)`
 *     reproduces the encyclopedia value exactly (the formula is anchored on it). This is the hard gate.
 *  2. Coverage — how many spells get the EXACT bdata slope (`matched`) vs a linear approximation, reported
 *     for visibility. Install-gated; skipped in CI.
 */
class SpellDamageScalingTest {
    private val install = File(System.getenv("WAKFU_INSTALL") ?: "/Applications/Ankama/Wakfu")

    @Test
    fun `damage scalings reproduce the encyclopedia at max level and report coverage`() {
        assumeTrue(File(install, "contents/bdata/${Tables.SPELL}.jar").isFile, "no local Wakfu install")
        val spells = loadTable(install, Tables.SPELL, Tables.SPELL_SCHEMA)
        val effects = loadTable(install, Tables.STATIC_EFFECT, Tables.STATIC_EFFECT_SCHEMA)
        val encyclopedia =
            LENIENT_JSON.decodeFromString(
                ListSerializer(Spell.serializer()),
                File(findRepositoryRoot(), "autobuilder/src/main/resources/spells.json").readText()
            )
        val encById = encyclopedia.associateBy { it.id }

        val scalings = buildSpellDamageScalings(spells, effects, encyclopedia)
        val matched = scalings.count { it.matched }
        println("spell damage scalings: ${scalings.size} (exact bdata match=$matched, linear-approx=${scalings.size - matched})")
        println("  damage spells in encyclopedia: ${encyclopedia.count { it.baseDamage != null }}")

        // (1) No regression at max level: applying the formula at levelCap reproduces the encyclopedia value.
        var baseRegressions = 0
        var critRegressions = 0
        val samples = ArrayList<String>()
        for (s in scalings) {
            val enc = encById.getValue(s.spellId)
            val spell = enc.copy(damageScaling = s)
            if (spell.baseDamageAt(s.levelCap) != enc.baseDamage) {
                baseRegressions++
                if (samples.size < 10) samples.add("${s.spellId} ${enc.name.en}: base@cap=${spell.baseDamageAt(s.levelCap)} vs enc ${enc.baseDamage}")
            }
            if (enc.critDamage != null && spell.critDamageAt(s.levelCap) != enc.critDamage) critRegressions++
        }
        if (samples.isNotEmpty()) {
            println("max-level base regressions:")
            samples.forEach { println("  $it") }
        }

        // Spot-check level scaling actually drops for a low-level caster (the bug being fixed).
        encById[4769]?.let { blazing ->
            val s = scalings.first { it.spellId == 4769 }
            val spell = blazing.copy(damageScaling = s)
            println("Blazing Arrow: lvl${s.levelCap}=${spell.baseDamageAt(s.levelCap)}  lvl20=${spell.baseDamageAt(20)}  lvl1=${spell.baseDamageAt(1)}")
        }

        assertEquals(0, baseRegressions, "spells whose formula does NOT reproduce the encyclopedia base at levelCap")
        assertEquals(0, critRegressions, "spells whose formula does NOT reproduce the encyclopedia crit at levelCap")
    }
}
