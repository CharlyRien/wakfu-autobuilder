package me.chosante.autobuilder

import me.chosante.autobuilder.domain.AchievementCatalog
import me.chosante.common.I18nText
import me.chosante.common.ItemEquipCriterion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmbeddedAchievementNamesDataTest {
    @Test
    fun `names contain exactly the referenced achievements with all four localizations`() {
        val criteria = requireNotNull(EmbeddedResources.decodeList<ItemEquipCriterion>("item-criteria.json"))
        val referenced =
            criteria
                .flatMap { it.playerState }
                .filter { it.function == "IsAchievementComplete" }
                .map { it.args.single().toInt() }
                .toSet()
        val names = requireNotNull(EmbeddedResources.decode<Map<Int, I18nText>>("achievement-names.json"))
        assertThat(names.keys).containsExactlyInAnyOrderElementsOf(referenced)
        assertThat(names.keys.toList()).isSorted()
        assertThat(AchievementCatalog.names).isEqualTo(names)
        assertThat(names.values).allSatisfy { assertThat(listOf(it.fr, it.en, it.es, it.pt)).allSatisfy { text -> assertThat(text).isNotBlank() } }
        assertThat(names.getValue(1509)).isEqualTo(I18nText("L'Effet Méryde", "The Meridian Effect", "El efecto santo", "O Efeito Meridiano"))
    }
}
