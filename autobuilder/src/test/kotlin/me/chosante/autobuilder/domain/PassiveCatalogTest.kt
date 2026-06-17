package me.chosante.autobuilder.domain

import me.chosante.common.CharacterClass
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PassiveCatalogTest {
    @Test
    fun `loads the embedded passive dataset for the player classes`() {
        assertThat(PassiveCatalog.passives).describedAs("the baked passive resource decodes").isNotEmpty
        CharacterClass.entries
            .filter { it != CharacterClass.UNKNOWN }
            .forEach { clazz ->
                assertThat(PassiveCatalog.forClass(clazz))
                    .describedAs("$clazz should own some passives")
                    .isNotEmpty
            }
    }

    @Test
    fun `every passive carries a usable icon gfx and resolves its class`() {
        assertThat(PassiveCatalog.passives).allSatisfy { passive ->
            assertThat(passive.gfxId).describedAs("${passive.name}: a gfx id for the icon").isPositive
            assertThat(passive.characterClass).isNotEqualTo(CharacterClass.UNKNOWN)
        }
    }

    @Test
    fun `solver-choosable passives are a non-empty subset that actually grants flat stats`() {
        // FECA actually owns solver-choosable passives (e.g. "Ligne" → +1 RANGE); CRA has none, so asserting
        // on CRA would pass vacuously and prove nothing about the filtering.
        val choosable = PassiveCatalog.choosable(CharacterClass.FECA)
        assertThat(choosable).describedAs("FECA has fully-declarative flat-stat passives").isNotEmpty
        assertThat(choosable).isSubsetOf(PassiveCatalog.forClass(CharacterClass.FECA))
        assertThat(choosable).allSatisfy { passive ->
            assertThat(passive.flatStats).isNotEmpty
            assertThat(passive.fullyDeclarative).isTrue()
        }
    }

    @Test
    fun `passive slots unlock at 10-30-50-100-150-200, capped at six`() {
        assertThat(PassiveCatalog.slotsForLevel(9)).isZero
        assertThat(PassiveCatalog.slotsForLevel(10)).isEqualTo(1)
        assertThat(PassiveCatalog.slotsForLevel(30)).isEqualTo(2)
        assertThat(PassiveCatalog.slotsForLevel(50)).isEqualTo(3)
        assertThat(PassiveCatalog.slotsForLevel(100)).isEqualTo(4)
        assertThat(PassiveCatalog.slotsForLevel(150)).isEqualTo(5)
        assertThat(PassiveCatalog.slotsForLevel(200)).isEqualTo(6)
        assertThat(PassiveCatalog.slotsForLevel(245)).isEqualTo(PassiveCatalog.MAX_SLOTS)
    }
}
