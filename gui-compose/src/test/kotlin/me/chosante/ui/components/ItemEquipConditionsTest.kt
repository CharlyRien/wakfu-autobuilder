package me.chosante.ui.components

import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.Characteristic
import me.chosante.common.CriterionComparison
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.ItemStatGate
import me.chosante.ui.i18n.Lang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ItemEquipConditionsTest {
    private val catalog by lazy { WakfuBestBuildFinderAlgorithm.equipments.associateBy { it.equipmentId } }

    private fun lines(
        id: Int,
        lang: Lang,
    ) = formatItemEquipConditions(requireNotNull(catalog.getValue(id).equipCriterion), catalog, lang)

    private fun assertLines(
        id: Int,
        en: String,
        fr: String,
        unenforced: Boolean = false,
    ) {
        assertThat(lines(id, Lang.EN)).containsExactly(ItemConditionLine(en, unenforced))
        assertThat(lines(id, Lang.FR)).containsExactly(ItemConditionLine(fr, unenforced))
    }

    @Test
    fun `Brakmar sword names its nation ring in the current language`() {
        assertLines(26497, "Needs Brakmar Ring", "Nécessite Anneau de Brâkmar")
    }

    @Test
    fun `the excluding ring triple lists both other rings`() {
        assertLines(24392, "Can't be worn with Zlug, Laroproc", "Incompatible avec La Bastos, Le Cabot")
    }

    @Test
    fun `class emblems reuse the localized class labels`() {
        assertLines(15258, "Cra only", "Réservé à la classe Crâ")
    }

    @Test
    fun `a never equippable item is explicit`() {
        assertLines(32988, "Cannot be equipped", "Non équipable")
    }

    @Test
    fun `stat gates use characteristic labels and mathematical operators and read as enforced rules`() {
        assertLines(26295, "Range ≤ 3", "Portée ≤ 3")
        assertLines(4224, "Critical Hit > -10", "Coup Critique > -10")
        assertLines(26296, "Max AP ≥ 13", "PA max ≥ 13")
        assertLines(18691, "Max AP ≤ 11", "PA max ≤ 11")
    }

    @Test
    fun `each player-state function is localized and marked assumed met`() {
        assertLines(19545, "Militia rank ≥ 2 (assumed met)", "Rang de milice ≥ 2 (supposé rempli)", true)
        assertLines(14422, "Achievement #1509 completed (assumed met)", "Succès n°1509 accompli (supposé rempli)", true)
        assertLines(9963, "Stasis gauge > 40 (assumed met)", "Jauge de Stasis > 40 (supposé rempli)", true)
        assertLines(9964, "Wakfu gauge > 35 (assumed met)", "Jauge de Wakfu > 35 (supposé rempli)", true)
        assertLines(9615, "Crime score ≥ 500 (assumed met)", "Score de crime ≥ 500 (supposé rempli)", true)
    }

    @Test
    fun `the generic unique ring rule has no display line`() {
        for (lang in Lang.entries) assertThat(lines(2022, lang)).isEmpty()
    }

    @Test
    fun `reverse-only bans are displayed once`() {
        val ring = catalog.getValue(24392)
        val criterion = requireNotNull(ring.equipCriterion).copy(forbidsItems = emptyList())
        val reverseOnly = catalog + (ring.equipmentId to ring.copy(equipCriterion = criterion))
        assertThat(formatItemEquipConditions(criterion, reverseOnly, Lang.EN))
            .containsExactly(ItemConditionLine("Can't be worn with Zlug, Laroproc"))
    }

    @Test
    fun `every embedded criterion can be formatted in both languages`() {
        for (equipment in catalog.values) {
            val criterion = equipment.equipCriterion ?: continue
            for (lang in Lang.entries) {
                val formatted = formatItemEquipConditions(criterion, catalog, lang)
                assertThat(formatted).allSatisfy { assertThat(it.text).isNotBlank() }
                assertThat(formatted.count { it.unenforced }).isEqualTo(criterion.playerState.size)
            }
        }
    }

    @Test
    fun `missing catalog references keep an explicit localized id`() {
        val criterion = ItemEquipCriterion(1, raw = "", requiresItems = listOf(999999))
        assertThat(formatItemEquipConditions(criterion, emptyMap(), Lang.EN)).containsExactly(ItemConditionLine("Needs Item #999999"))
        assertThat(formatItemEquipConditions(criterion, emptyMap(), Lang.FR)).containsExactly(ItemConditionLine("Nécessite Objet n°999999"))
    }

    @Test
    fun `strict less-than comparison stays strict`() {
        val criterion = ItemEquipCriterion(1, raw = "", statGates = listOf(ItemStatGate(Characteristic.RANGE, comparison = CriterionComparison.LT, value = 3)))
        assertThat(formatItemEquipConditions(criterion, emptyMap(), Lang.EN)).containsExactly(ItemConditionLine("Range < 3"))
    }
}
