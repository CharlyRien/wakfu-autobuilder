package me.chosante.common

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the [SublimationEffectLenientSerializer] back-compat contract. Builds saved BEFORE [SublimationEffect]
 * became a sealed hierarchy stored each effect as a discriminator-less `{ "characteristic": …, "value": … }`.
 * Those must still decode (as [SublimationEffect.Flat]) so the build-history / clipboard loader does not silently
 * drop the whole saved build — while new data keeps the `type` discriminator, so the strict `sublimations.json`
 * decoder is unaffected and the file round-trips unchanged.
 */
class SublimationSerializationTest {
    // Mirrors the build-history codec (gui-compose `historyJson`): lenient, defaults encoded.
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `a legacy effect saved without a type discriminator decodes as a flat effect`() {
        // The exact on-disk shape a build saved before the sealed hierarchy produced: a bare
        // { characteristic, value }, no "type" and no appliesBeforeCombat.
        val legacy =
            """{"stateId":5073,"name":{"fr":"x","en":"x","es":"x","pt":"x"},"rarity":"EPIC","kind":"FLAT",""" +
                """"effects":[{"characteristic":"DAMAGE_INFLICTED","value":15}]}"""

        val sub = json.decodeFromString(Sublimation.serializer(), legacy)

        assertEquals(listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 15)), sub.effects)
    }

    @Test
    fun `a pre-B4 save folds top-level conversion, perStatStep and bestElementConcentration into effects`() {
        // Before B4 the three structured bonuses were top-level Sublimation fields; the SublimationLegacyFieldsSerializer
        // must fold each into effects (with its type discriminator) so a pre-B4 saved build keeps them instead of
        // dropping them under ignoreUnknownKeys.
        val legacy =
            """{"stateId":5077,"name":{"fr":"x","en":"x","es":"x","pt":"x"},"rarity":"EPIC","kind":"CONVERSION",""" +
                """"conversion":{"from":"MASTERY_CRITICAL","to":"MASTERY_ELEMENTARY","percent":100},""" +
                """"perStatStep":{"source":"MOVEMENT_POINT","threshold":4,"perStep":6,"cap":24,"target":"DAMAGE_INFLICTED"},""" +
                """"bestElementConcentration":{"damageInflictedBonus":20,"masteryPenaltyPercent":30}}"""

        val sub = json.decodeFromString(SublimationLegacyFieldsSerializer, legacy)

        assertEquals(
            SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, 100),
            sub.conversion
        )
        assertEquals(
            SublimationEffect.PerStatStep(Characteristic.MOVEMENT_POINT, 4, 6, 24, Characteristic.DAMAGE_INFLICTED),
            sub.perStatStep
        )
        assertEquals(SublimationEffect.BestElementConcentration(20, 30), sub.bestElementConcentration)
    }

    @Test
    fun `the legacy folding serializer is a no-op for a modern sub that already stores effects`() {
        val modern =
            """{"stateId":5077,"name":{"fr":"x","en":"x","es":"x","pt":"x"},"rarity":"EPIC","kind":"CONVERSION",""" +
                """"effects":[{"type":"conversion","from":"MASTERY_CRITICAL","to":"MASTERY_ELEMENTARY","percent":100}]}"""

        val viaLegacy = json.decodeFromString(SublimationLegacyFieldsSerializer, modern)
        val viaDefault = json.decodeFromString(Sublimation.serializer(), modern)

        assertEquals(viaDefault, viaLegacy)
        assertEquals(
            SublimationEffect.Conversion(Characteristic.MASTERY_CRITICAL, Characteristic.MASTERY_ELEMENTARY, 100),
            viaLegacy.conversion
        )
    }

    @Test
    fun `flat and percent-of-level effects round-trip and keep the type discriminator on encode`() {
        val sub =
            Sublimation(
                stateId = 1,
                name = I18nText("x", "x", "x", "x"),
                rarity = SublimationRarity.NORMAL,
                kind = SublimationKind.FLAT,
                effects =
                    listOf(
                        SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 20),
                        SublimationEffect.PercentOfLevel(Characteristic.MASTERY_ELEMENTARY, percentOfLevel = 4)
                    )
            )

        val encoded = json.encodeToString(Sublimation.serializer(), sub)
        // The discriminator MUST still be written, so the strict sublimations.json decoder keeps working.
        assertTrue(encoded.contains("\"type\":\"flat\""), "flat discriminator must be written: $encoded")
        assertTrue(encoded.contains("\"type\":\"percentOfLevel\""), "percentOfLevel discriminator must be written: $encoded")

        assertEquals(sub, json.decodeFromString(Sublimation.serializer(), encoded))
    }
}
