package me.chosante.ui.history

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.StaleEngine
import me.chosante.ui.state.UiState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * When a saved build is obsolete, and why: (A) it was saved with other game data than the app ships, (B) it was computed by an
 * older engine (a lower `ENGINE_RESULTS_VERSION`, or none recorded) — both reasons can hold at once, and a current build has none.
 */
class ObsolescenceTest {
    private val current = "1.93.1.62"
    private val engine = 3

    private fun of(
        data: String,
        engineVersion: Int?,
    ) = obsolescenceOf(data, engineVersion, currentDataVersion = current, currentEngineVersion = engine)

    @Test
    fun `an older game-data version gives reason A only`() {
        val obsolescence = of("1.92.1.58", engine)!!

        assertThat(obsolescence.dataUpdated).isTrue()
        assertThat(obsolescence.savedDataVersion).isEqualTo("1.92.1.58")
        assertThat(obsolescence.engineImproved).isFalse()
        assertThat(obsolescence.reasons(Lang.EN))
            .containsExactly(
                "Game data updated since this build was saved (1.92 → 1.93): new items, sublimations or runes may give a better build. Re-run the search."
            )
        assertThat(obsolescence.reasons(Lang.FR))
            .containsExactly(
                "Données du jeu mises à jour depuis (1.92 → 1.93) : de nouveaux objets, sublimations ou runes peuvent donner un meilleur build. Relance la recherche."
            )
    }

    @Test
    fun `a lower engine version gives reason B only`() {
        val obsolescence = of(current, engine - 1)!!

        assertThat(obsolescence.dataUpdated).isFalse()
        assertThat(obsolescence.engineImproved).isTrue()
        assertThat(obsolescence.reasons(Lang.EN)).containsExactly(Tr.OBSOLETE_ENGINE_REASON.en)
    }

    @Test
    fun `a save that never recorded the engine version counts as an older engine`() {
        assertThat(of(current, null)?.engineImproved).isTrue()
    }

    @Test
    fun `both reasons together are both given, game data first`() {
        val obsolescence = of("1.92.1.58", null)!!

        assertThat(obsolescence.dataUpdated).isTrue()
        assertThat(obsolescence.engineImproved).isTrue()
        assertThat(obsolescence.reasons(Lang.EN)).hasSize(2)
        assertThat(obsolescence.reasons(Lang.EN)[1]).isEqualTo(Tr.OBSOLETE_ENGINE_REASON.en)
    }

    @Test
    fun `a build saved with the current data and engine is not obsolete`() {
        assertThat(of(current, engine)).isNull()
        assertThat(of(current, engine + 1)).describedAs("a newer app's save is not flagged by an older app").isNull()
    }

    @Test
    fun `a save from NEWER game data gets a neutral sentence, not the suggestion that new items may do better`() {
        val obsolescence = of("1.94.1.70", engine)!!

        assertThat(obsolescence.savedWithNewerData).isTrue()
        assertThat(obsolescence.reasons(Lang.EN)).containsExactly("Saved with other game data (1.94).")
        assertThat(obsolescence.reasons(Lang.FR)).containsExactly("Enregistré avec d'autres données du jeu (1.94).")
        assertThat(of("1.92.1.58", engine)!!.savedWithNewerData).isFalse()
    }

    @Test
    fun `versions are ordered numerically, not as text`() {
        assertThat(compareVersions("1.100.0.1", "1.93.1.62")).isPositive()
        assertThat(compareVersions("1.93.1.9", "1.93.1.62")).isNegative()
        assertThat(compareVersions("1.93", "1.93.0.0")).isZero()
    }

    @Test
    fun `a version change reads major-minor when that tells them apart, in full otherwise`() {
        assertThat(versionChange("1.92.1.58", "1.93.1.62")).isEqualTo("1.92 → 1.93")
        assertThat(versionChange("1.93.1.58", "1.93.2.62")).isEqualTo("1.93.1.58 → 1.93.2.62")
    }

    @Test
    fun `a loaded build reads its obsolescence from the stamps it carries`() {
        val build = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))

        assertThat(UiState(build = build).obsolescence(current)).isNull()
        assertThat(UiState(build = build, staleDataVersion = "1.92.1.58").obsolescence(current)?.engineImproved).isFalse()
        assertThat(UiState(build = build, staleEngine = StaleEngine(null)).obsolescence(current)?.dataUpdated).isFalse()
        assertThat(UiState(build = build, staleDataVersion = "1.92.1.58", staleEngine = StaleEngine(1)).obsolescence(current)?.reasons(Lang.EN))
            .hasSize(2)
        assertThat(UiState(staleDataVersion = "1.92.1.58").obsolescence(current)).describedAs("no build, no badge").isNull()
    }
}
