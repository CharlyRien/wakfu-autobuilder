package me.chosante.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpellDamageTest {
    private val blazingArrow =
        Spell(
            id = 4769,
            clazz = CharacterClass.CRA,
            name = I18nText(fr = "Flèche Enflammée", en = "Blazing Arrow", es = "", pt = ""),
            element = SpellElement.FIRE,
            apCost = 2,
            rangeMin = 2,
            rangeMax = 5,
            baseDamage = 60,
            critDamage = 76,
            area = SpellArea.SINGLE_TARGET
        )

    // Level is irrelevant for blazingArrow: it has no damageScaling, so baseDamageAt() falls back to the
    // flat baseDamage at any level. These formula tests therefore pin characterLevel at max (245).
    private val maxLevel = 245

    @Test
    fun `no fire mastery and no crit yields the raw base hit`() {
        val result = SpellDamage.expectedDamage(blazingArrow, emptyMap(), maxLevel)!!
        assertEquals(60.0, result.expected, 1e-9)
        assertEquals(60.0, result.nonCrit, 1e-9)
    }

    @Test
    fun `fire mastery scales the hit and generic elemental mastery folds in`() {
        val stats =
            mapOf(
                Characteristic.MASTERY_ELEMENTARY_FIRE to 800,
                Characteristic.MASTERY_ELEMENTARY to 200
            )
        // base × (1 + (800+200)/100) = 60 × 11 = 660
        val result = SpellDamage.expectedDamage(blazingArrow, stats, maxLevel)!!
        assertEquals(660.0, result.nonCrit, 1e-9)
    }

    @Test
    fun `expected blends crit and non-crit by the usable crit rate`() {
        val stats =
            mapOf(
                Characteristic.MASTERY_ELEMENTARY_FIRE to 500,
                Characteristic.CRITICAL_HIT to 50,
                Characteristic.MASTERY_CRITICAL to 100
            )
        val r = SpellDamage.expectedDamage(blazingArrow, stats, maxLevel)!!
        // nonCrit = 60 × (1+5) = 360 ; crit = 76 × (1 + (500+100)/100) = 76 × 7 = 532 (critDamage already +25%)
        assertEquals(360.0, r.nonCrit, 1e-9)
        assertEquals(532.0, r.crit, 1e-9)
        assertEquals(0.5 * 360.0 + 0.5 * 532.0, r.expected, 1e-9)
    }

    @Test
    fun `damage inflicted and a boss weakness both raise the hit`() {
        val stats =
            mapOf(
                Characteristic.MASTERY_ELEMENTARY_FIRE to 100,
                Characteristic.DAMAGE_INFLICTED to 25
            )
        // 60 × (1+1) × (1+0.25) × (1 − (−50)/100) = 60 × 2 × 1.25 × 1.5 = 225
        val r = SpellDamage.expectedDamage(blazingArrow, stats, maxLevel, targetResistancePercent = -50)!!
        assertEquals(225.0, r.nonCrit, 1e-9)
    }

    @Test
    fun `a spell without a readable base hit returns null rather than a made-up number`() {
        val passive = blazingArrow.copy(element = null, baseDamage = null)
        assertNull(SpellDamage.expectedDamage(passive, emptyMap(), maxLevel))
        assertTrue(!passive.hasDamage)
    }

    @Test
    fun `resistance is capped so a huge resistance only reduces by the cap factor`() {
        val r = SpellDamage.expectedDamage(blazingArrow, emptyMap(), maxLevel, targetResistancePercent = 100_000)!!
        assertEquals(60.0 * (1.0 - 90 / 100.0), r.nonCrit, 1e-9) // 6.0
    }

    @Test
    fun `weakness beyond the floor is clamped at -100 percent (factor 2x)`() {
        val r = SpellDamage.expectedDamage(blazingArrow, emptyMap(), maxLevel, targetResistancePercent = -500)!!
        assertEquals(120.0, r.nonCrit, 1e-9) // 60 × 2.0
    }

    @Test
    fun `range, rear and berserk masteries fold in only when their flags are set`() {
        val stats =
            mapOf(
                Characteristic.MASTERY_DISTANCE to 100,
                Characteristic.MASTERY_BACK to 100,
                Characteristic.MASTERY_BERSERK to 100
            )
        assertEquals(60.0, SpellDamage.expectedDamage(blazingArrow, stats, maxLevel)!!.nonCrit, 1e-9) // flags off ⇒ ignored
        val withFlags =
            SpellDamage.expectedDamage(
                blazingArrow,
                stats,
                maxLevel,
                rangeBand = SpellDamage.RangeBand.DISTANCE,
                rearMastery = true,
                berserkMastery = true
            )!!
        assertEquals(240.0, withFlags.nonCrit, 1e-9) // 60 × (1 + 300/100)
    }

    @Test
    fun `a large damage-inflicted malus is floored at -50 percent`() {
        val r = SpellDamage.expectedDamage(blazingArrow, mapOf(Characteristic.DAMAGE_INFLICTED to -10_000), maxLevel)!!
        assertEquals(30.0, r.nonCrit, 1e-9) // 60 × (1 − 50/100)
    }

    @Test
    fun `the orientation multiplier scales every hit (a back hit also folds in rear mastery)`() {
        val stats = mapOf(Characteristic.MASTERY_BACK to 100)
        // Face (default 100%) ignores rear mastery: raw base hit.
        assertEquals(60.0, SpellDamage.expectedDamage(blazingArrow, stats, maxLevel)!!.nonCrit, 1e-9)
        // Side: ×1.10 positional, still no rear mastery.
        assertEquals(66.0, SpellDamage.expectedDamage(blazingArrow, stats, maxLevel, orientationMultiplierPercent = 110)!!.nonCrit, 1e-9)
        // Back: ×1.25 positional AND +100% rear mastery → 60 × 2 × 1.25 = 150.
        val back =
            SpellDamage.expectedDamage(blazingArrow, stats, maxLevel, rearMastery = true, orientationMultiplierPercent = 125)!!
        assertEquals(150.0, back.nonCrit, 1e-9)
    }

    @Test
    fun `critDamage falls back to the normal base when the spell exposes none`() {
        val noCritBase = blazingArrow.copy(critDamage = null)
        val r = SpellDamage.expectedDamage(noCritBase, mapOf(Characteristic.CRITICAL_HIT to 100), maxLevel)!!
        assertEquals(75.0, r.crit, 1e-9) // crit base falls back to 60 → 60 × 1.25
        assertEquals(r.crit, r.expected, 1e-9) // 100% crit ⇒ expected == crit
    }

    @Test
    fun `negative critical mastery is clamped to zero in the crit term`() {
        val stats = mapOf(Characteristic.CRITICAL_HIT to 100, Characteristic.MASTERY_CRITICAL to -500)
        val r = SpellDamage.expectedDamage(blazingArrow, stats, maxLevel)!!
        assertEquals(76.0, r.crit, 1e-9) // 76 × (1 + 0/100), negative crit mastery clamped to 0 (critDamage already +25%)
    }

    @Test
    fun `damageScaling scales the base hit to the caster level (anchored at levelCap)`() {
        // Blazing Arrow's real bdata formula: base hit floor(2 + 0.24·lvl), crit floor(2.5 + 0.30·lvl).
        val scaled =
            blazingArrow.copy(
                damageScaling = SpellDamageScaling(spellId = 4769, base = 2.0, inc = 0.24, critBase = 2.5, critInc = 0.30, levelCap = 245, matched = true)
            )
        // At max level the value equals the encyclopedia snapshot (no regression): floor(2 + 0.24·245) = 60.
        assertEquals(60, scaled.baseDamageAt(245))
        assertEquals(76, scaled.critDamageAt(245))
        assertEquals(60.0, SpellDamage.expectedDamage(scaled, emptyMap(), 245)!!.nonCrit, 1e-9)
        // At level 20 it is correctly much lower: floor(2 + 0.24·20) = 6 — the bug this fixes.
        assertEquals(6, scaled.baseDamageAt(20))
        assertEquals(6.0, SpellDamage.expectedDamage(scaled, emptyMap(), 20)!!.nonCrit, 1e-9)
        // Level is capped at levelCap, so beyond it the value plateaus (245 == 300).
        assertEquals(scaled.baseDamageAt(245), scaled.baseDamageAt(300))
    }
}
