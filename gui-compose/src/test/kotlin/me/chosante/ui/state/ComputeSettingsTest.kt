package me.chosante.ui.state

import me.chosante.autobuilder.genetic.wakfu.ComputeBudget
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.prefs.Preferences

class ComputeSettingsTest {
    @Test
    fun `presets map correctly even on odd and single core hosts`() {
        for (cores in listOf(1, 2, 3, 5, 10, 16)) {
            assertThat(ComputeSettings(ProcessorUse.MAXIMUM).cores(cores)).isEqualTo(cores)
            assertThat(ComputeSettings(ProcessorUse.BALANCED).cores(cores)).isEqualTo((cores + 1) / 2)
            assertThat(ComputeSettings(ProcessorUse.LOW).cores(cores)).isEqualTo(minOf(2, cores))
            assertThat(ComputeSettings(ProcessorUse.CUSTOM, -10).cores(cores)).isEqualTo(1)
            assertThat(ComputeSettings(ProcessorUse.CUSTOM, 100).cores(cores)).isEqualTo(cores)
            for (n in 1..cores) assertThat(ComputeSettings(ProcessorUse.CUSTOM, n).cores(cores)).isEqualTo(n)
        }
        assertThat(ComputeSettings().budget()).isEqualTo(ComputeBudget())
    }

    @Test
    fun `every preset and custom count survives fresh preferences`() {
        val node = Preferences.userRoot().node("me/chosante/wakfu-autobuilder-test/settings-${UUID.randomUUID()}")
        try {
            assertThat(LibraryPreferences(node).loadComputeSettings()).isEqualTo(ComputeSettings())
            for (preset in ProcessorUse.entries) {
                val settings = ComputeSettings(preset, minOf(3, ComputeBudget.availableCores))
                LibraryPreferences(node).saveComputeSettings(settings)
                node.flush()
                assertThat(LibraryPreferences(node).loadComputeSettings()).isEqualTo(settings)
            }
            node.put("processorUse", "corrupt")
            node.putInt("processorCustomCores", -7)
            assertThat(LibraryPreferences(node).loadComputeSettings()).isEqualTo(ComputeSettings(ProcessorUse.MAXIMUM, 1))
            node.putInt("processorCustomCores", Int.MAX_VALUE)
            assertThat(LibraryPreferences(node).loadComputeSettings().customCores).isEqualTo(ComputeBudget.availableCores)
            LibraryPreferences(null).saveComputeSettings(ComputeSettings(ProcessorUse.LOW))
            assertThat(LibraryPreferences(null).loadComputeSettings()).isEqualTo(ComputeSettings())
        } finally {
            node.removeNode()
        }
    }
}
