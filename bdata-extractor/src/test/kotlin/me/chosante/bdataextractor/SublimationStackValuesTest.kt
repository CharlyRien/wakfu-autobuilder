package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import me.chosante.common.Sublimation
import me.chosante.common.SublimationEffect
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

class SublimationStackValuesTest {
    @Test
    fun `family-level tables preserve top-tier values and decoded anchors`() {
        val subs =
            LENIENT_JSON.decodeFromString(
                ListSerializer(Sublimation.serializer()),
                File(findRepositoryRoot(), "autobuilder/src/main/resources/sublimations.json").readText()
            )

        fun values(id: Int) =
            subs
                .single { it.stateId == id }
                .effects
                .filterIsInstance<SublimationEffect.StatEffect>()
                .single()
                .valuesByLevel
        assertEquals(listOf(8, 16, 24, 32), values(6931))
        assertEquals(listOf(4, 8, 12, 16), values(8518))
        assertEquals(listOf(15, 30, 45, 60, 75, 90), values(5983))
        for (sub in subs) {
            for (effect in sub.effects.filterIsInstance<SublimationEffect.StatEffect>()) {
                assertEquals(sub.maxStackLevel, effect.valuesByLevel.size, sub.name.fr)
                val top =
                    when (effect) {
                        is SublimationEffect.Flat -> effect.value
                        is SublimationEffect.PercentOfLevel -> effect.percentOfLevel
                    }
                if (sub.maxTier <= sub.maxStackLevel) assertEquals(top, effect.valuesByLevel[sub.maxTier - 1], sub.name.fr)
            }
            if (sub.conversion != null || sub.perStatStep != null || sub.bestElementConcentration != null) assertFalse(sub.stackModelled, sub.name.fr)
        }
        assertEquals(setOf(2), subs.single { it.stateId == 6013 }.shardsByTier.keys)
        assertEquals(
            29001,
            subs
                .single { it.stateId == 6931 }
                .shardsByTier
                .getValue(1)
                .itemId
        )
        assertEquals(
            31645,
            subs
                .single { it.stateId == 8518 }
                .shardsByTier
                .getValue(1)
                .itemId
        )
    }
}
