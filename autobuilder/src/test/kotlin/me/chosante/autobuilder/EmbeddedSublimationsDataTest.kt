package me.chosante.autobuilder

import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.SublimationRarity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the embedded `sublimations.json` identity: a NORMAL sublimation's name and `zenithId` must be its
 * CREDITED tier's item (the one whose values the engine uses, `maxTier`). The extractor used to keep the first
 * CDN row of each family, which was often a lower tier — the GUI then showed "Ravage II" for a build valued at
 * Ravage III and the Zenith export socketed the tier-II shard. The extractor only WARNS on drift, so this test
 * is what fails if a future regeneration ships a lower tier's identity again. (The French name's numeral is the
 * proxy: name and zenithId come from the same CDN row.)
 */
class EmbeddedSublimationsDataTest {
    @Test
    fun `normal sublimations are named after their credited tier`() {
        val numerals = mapOf("I" to 1, "II" to 2, "III" to 3)
        val mismatches =
            WakfuBestBuildFinderAlgorithm.sublimations
                .filter { it.rarity == SublimationRarity.NORMAL }
                .mapNotNull { sub ->
                    val nameTier =
                        numerals[
                            sub.name.fr
                                .trim()
                                .substringAfterLast(' ')
                        ] ?: return@mapNotNull null
                    if (nameTier == sub.maxTier) null else "${sub.name.fr} (stateId ${sub.stateId}): name tier $nameTier, maxTier ${sub.maxTier}"
                }
        assertThat(mismatches)
            .describedAs("a NORMAL sublimation's displayed/exported identity must be its maxTier item")
            .isEmpty()
    }

    /**
     * Forced/excluded sublimations saved before the credited-tier rename ("Carnage II") must resolve to the
     * renamed record instead of being silently ignored; current names (any case) and unknown names pass
     * through untouched, and an old name that is now ANOTHER record's current name keeps its current meaning.
     */
    @Test
    fun `pre-rename sublimation names resolve to the current records`() {
        fun canonical(name: String) = WakfuBestBuildFinderAlgorithm.canonicalSublimationName(name)
        assertThat(canonical("Carnage II")).isEqualTo("Carnage III")
        assertThat(canonical("devastate ii")).describedAs("old English name, any case").isEqualTo("Ravage III")
        assertThat(canonical("Ravage III")).isEqualTo("Ravage III")
        assertThat(canonical("carnage iii")).describedAs("a current name is never rewritten").isEqualTo("carnage iii")
        assertThat(canonical("Resolute II")).describedAs("now another record's current English name").isEqualTo("Resolute II")
        assertThat(canonical("Pas une sublimation")).isEqualTo("Pas une sublimation")
        val targets = WakfuBestBuildFinderAlgorithm.sublimations.map { it.name.fr }.toSet()
        listOf("Carnage II", "Influence II", "Poids Plume I").forEach {
            assertThat(canonical(it)).describedAs("$it maps onto an existing record").isIn(targets)
        }
    }
}
