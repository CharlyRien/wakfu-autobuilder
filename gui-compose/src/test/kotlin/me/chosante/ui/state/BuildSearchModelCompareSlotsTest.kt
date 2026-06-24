package me.chosante.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import me.chosante.ui.history.HistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Locks the compare-slot logic behind the N-build compare view: [BuildSearchModel.addCompareSlot] caps at
 * four, [BuildSearchModel.setCompareSlot] is index-addressed, and [BuildSearchModel.clearCompareSlot]
 * removes an extra column but only empties (never drops below) the two base columns. Unconfined dispatchers
 * make the model's init run synchronously; the build finder is stubbed so OR-Tools never starts.
 */
class BuildSearchModelCompareSlotsTest {
    @Test
    fun `slots add up to four then cap, and clearing removes extras but keeps two`(
        @TempDir tempDir: Path,
    ) = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val model =
            BuildSearchModel(
                scope = scope,
                buildFinder = { emptyFlow() },
                zenithBuilder = { "" },
                mainDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
                libraryPreferences = LibraryPreferences(null),
                historyRepository = HistoryRepository(baseDir = tempDir, ioDispatcher = Dispatchers.Unconfined)
            )
        try {
            // startCompare seeds the first column + one empty slot.
            model.startCompare("a")
            assertThat(model.ui.compareSlots).containsExactly("a", null)

            model.addCompareSlot()
            model.addCompareSlot()
            assertThat(model.ui.compareSlots).hasSize(4)
            model.addCompareSlot() // capped at four
            assertThat(model.ui.compareSlots).hasSize(4)

            model.setCompareSlot(1, "b")
            assertThat(model.ui.compareSlots[1]).isEqualTo("b")
            model.setCompareSlot(9, "z") // out of range → ignored, no growth
            assertThat(model.ui.compareSlots).hasSize(4)

            // Above the floor of two, clearing removes the whole column…
            model.clearCompareSlot(3)
            assertThat(model.ui.compareSlots).hasSize(3)
            model.clearCompareSlot(2)
            assertThat(model.ui.compareSlots).hasSize(2)

            // …at the floor, clearing empties the slot instead of dropping below two columns.
            model.clearCompareSlot(0)
            assertThat(model.ui.compareSlots).hasSize(2)
            assertThat(model.ui.compareSlots[0]).isNull()
            assertThat(model.ui.compareSlots[1]).isEqualTo("b")
        } finally {
            scope.cancel()
        }
    }
}
