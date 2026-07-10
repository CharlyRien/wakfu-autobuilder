package me.chosante.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locks [Sublimation.nameTier] — the generation tier read from the name's trailing roman numeral, which
 * the "max sublimation tier" filter caps on. This is the number a player sees ("Mesure III" = tier 3),
 * distinct from the internal shard-upgrade [Sublimation.maxTier] (every epic is maxTier 1 regardless).
 */
class SublimationNameTierTest {
    private fun sub(nameFr: String): Sublimation =
        Sublimation(
            stateId = 1,
            name = I18nText(fr = nameFr, en = nameFr, es = nameFr, pt = nameFr),
            rarity = SublimationRarity.EPIC,
            kind = SublimationKind.FLAT
        )

    @Test
    fun `a base name with no numeral is tier 1`() {
        assertEquals(1, sub("Mesure").nameTier)
        assertEquals(1, sub("Concentration Elémentaire").nameTier)
        assertEquals(1, sub("Maniement : Deux mains").nameTier)
    }

    @Test
    fun `an explicit trailing I is tier 1`() {
        assertEquals(1, sub("Poids Plume I").nameTier)
        assertEquals(1, sub("Influence paradoxale I").nameTier)
    }

    @Test
    fun `trailing II is tier 2 and III is tier 3`() {
        assertEquals(2, sub("Mesure II").nameTier)
        assertEquals(2, sub("Ravage II").nameTier)
        assertEquals(2, sub("Contrôle de l'espace II").nameTier)
        assertEquals(3, sub("Mesure III").nameTier)
    }

    @Test
    fun `a numeral only counts as a standalone trailing token`() {
        // "III" glued to a word or not at the end must not be read as a tier.
        assertEquals(1, sub("Anatomie").nameTier)
        assertEquals(1, sub("Volonté de fer").nameTier)
    }
}
