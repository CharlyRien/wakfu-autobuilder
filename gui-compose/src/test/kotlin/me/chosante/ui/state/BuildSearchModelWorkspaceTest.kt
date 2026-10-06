package me.chosante.ui.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.history.toRequestSnapshot
import me.chosante.ui.i18n.Tr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The model's side of the remembered workspace: it puts the remembered request back when warm-up ends, drops what the game data
 * no longer knows (with a toast), falls back to the defaults when there is nothing usable, and writes every later change of the
 * request — never a result.
 */
class BuildSearchModelWorkspaceTest {
    private val foundBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))

    /** Knows the item "Gelano" and nothing else. */
    private val catalog =
        WorkspaceCatalog(itemNames = setOf("Gelano"), sublimationNames = emptySet(), runeIds = emptySet(), passiveExists = { _, _ -> false })

    private fun newModel(
        scope: CoroutineScope,
        dir: Path,
        store: WorkspaceStore = WorkspaceStore(baseDir = dir, ioDispatcher = Dispatchers.IO),
        debounce: kotlin.time.Duration = 20.milliseconds,
        readWait: kotlin.time.Duration = 2.seconds,
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = { flowOf(SolverResult(individual = foundBuild, matchPercentage = BigDecimal("100"), progressPercentage = 100, isOptimal = true)) },
            zenithBuilder = { "" },
            copyToClipboard = {},
            readClipboard = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
            historyRepository = HistoryRepository(baseDir = dir.resolve("library"), ioDispatcher = Dispatchers.Unconfined),
            workspaceStore = store,
            workspaceSaveDebounce = debounce,
            workspaceCatalog = { catalog },
            workspaceReadWait = readWait
        ).also { it.windowShown.complete(Unit) }

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(60.seconds) {
            while (!predicate()) {
                delay(20.milliseconds)
            }
        }
    }

    private fun withScope(block: suspend (CoroutineScope) -> Unit) =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                block(scope)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `with no remembered workspace the app starts from the defaults`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val model = newModel(scope, dir)
        awaitUntil { model.isReady }

        assertThat(model.ui.toRequestSnapshot(keepBossInAnyMode = true)).isEqualTo(UiState().toRequestSnapshot(keepBossInAnyMode = true))
        assertThat(dir.resolve("workspace.json").exists()).describedAs("nothing changed, nothing written").isFalse()
    }

    @Test
    fun `a corrupt remembered workspace falls back to the defaults, and the next change replaces it`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val file = dir.resolve("workspace.json")
        file.writeText("{\"formatVersion\": 1, \"request\": {")
        val model = newModel(scope, dir)
        awaitUntil { model.isReady }

        assertThat(model.ui.level).isEqualTo(UiState().level)
        assertThat(model.ui.toast).isNull()

        model.setLevel("150")
        awaitUntil { WorkspaceStore(baseDir = dir).read()?.request?.level == 150 }
    }

    @Test
    fun `the request edited in one session comes back in the next, without what the game data no longer knows`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val first = newModel(scope, dir)
        awaitUntil { first.isReady }
        first.setClass(CharacterClass.FECA)
        first.setLevel("180")
        first.toggleRarity(Rarity.COMMON)
        first.setDuration("30")
        first.forceItem(item("Gelano"))
        first.excludeItem(item("Removed by the update"))
        awaitUntil {
            WorkspaceStore(baseDir = dir)
                .read()
                ?.request
                ?.excludedItems
                ?.size == 1
        }

        val second = newModel(scope, dir)
        awaitUntil { second.isReady }
        awaitUntil { second.ui.toast != null }

        assertThat(second.ui.clazz).isEqualTo(CharacterClass.FECA)
        assertThat(second.ui.level).isEqualTo(180)
        assertThat(second.ui.excludedRarities).containsExactly(Rarity.COMMON)
        assertThat(second.ui.duration).isEqualTo("30")
        assertThat(second.ui.forcedItems.map { it.matchName }).containsExactly("Gelano")
        assertThat(second.ui.excludedItems).describedAs("an item the game data no longer has").isEmpty()
        assertThat(second.ui.toast).isEqualTo(Tr.TOAST_WORKSPACE_ENTRIES_REMOVED.value(second.ui.lang).format(1))
        // The cleaned request is what gets remembered from now on.
        awaitUntil {
            WorkspaceStore(baseDir = dir)
                .read()
                ?.request
                ?.excludedItems
                ?.isEmpty() == true
        }
    }

    @Test
    fun `a result is never remembered, only the request it was searched with`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val first = newModel(scope, dir)
        awaitUntil { first.isReady }
        first.setDuration("1")
        first.search()
        awaitUntil { first.ui.phase == Phase.Done && first.ui.build != null }
        first.flushWorkspace()

        val written = dir.resolve("workspace.json").readText()
        assertThat(written).doesNotContain("\"result\"", "\"equipments\"", "\"phase\"")

        val second = newModel(scope, dir)
        awaitUntil { second.isReady }
        assertThat(second.ui.duration).isEqualTo("1")
        assertThat(second.ui.build).isNull()
        assertThat(second.ui.phase).isEqualTo(Phase.Idle)
    }

    @Test
    fun `a blank field and a typed 0 come back as they were, no row for the blank and a floor for the 0`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val first = newModel(scope, dir)
        awaitUntil { first.isReady }
        first.addTarget(Characteristic.RESISTANCE_ELEMENTARY_FIRE)
        first.addTarget(Characteristic.RESISTANCE_ELEMENTARY_WIND)
        val fire = first.ui.targets.single { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY_FIRE }
        val air = first.ui.targets.single { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY_WIND }
        first.updateTargetValue(fire.id, "")
        first.updateTargetValue(air.id, "0")
        first.flushWorkspace()

        val second = newModel(scope, dir)
        awaitUntil { second.isReady }

        val rows = second.ui.targets.associate { it.characteristic to it.value }
        assertThat(rows[Characteristic.RESISTANCE_ELEMENTARY_FIRE]).isEqualTo("")
        assertThat(rows[Characteristic.RESISTANCE_ELEMENTARY_WIND]).isEqualTo("0")
        val stats = with(second) { second.ui.toTargetStats() }
        assertThat(stats.map { it.characteristic }).describedAs("a blank field asks for nothing").doesNotContain(Characteristic.RESISTANCE_ELEMENTARY_FIRE)
        assertThat(stats.resistanceFloorElements).describedAs("a typed 0 is a floor").contains(Characteristic.RESISTANCE_ELEMENTARY_WIND)
    }

    @Test
    fun `a flush writes the last edit at once, without waiting for the debounce`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val model = newModel(scope, dir, debounce = 10.minutes)
        awaitUntil { model.isReady }
        model.setLevel("199")
        assertThat(WorkspaceStore(baseDir = dir).read()?.request?.level).describedAs("still debounced").isNotEqualTo(199)

        model.flushWorkspace()

        assertThat(WorkspaceStore(baseDir = dir).read()?.request?.level).isEqualTo(199)
    }

    /** A store whose read only returns once [gate] completes: a disk slower than the reveal's wait. */
    private class SlowStore(
        dir: Path,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : WorkspaceStore(baseDir = dir, ioDispatcher = Dispatchers.IO) {
        override suspend fun load(): me.chosante.common.workspace.WorkspaceSnapshot? {
            gate.await()
            return super.load()
        }
    }

    private fun rememberLevel(
        dir: Path,
        level: Int,
    ) = WorkspaceStore(baseDir = dir).saveBlocking(
        UiState(level = level).toWorkspaceSnapshot("v")
    )

    @Test
    fun `a read slower than the reveal is applied when it lands, and nothing is written before`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        rememberLevel(dir, 177)
        val store = SlowStore(dir)
        val model = newModel(scope, dir, store = store, readWait = 50.milliseconds)
        awaitUntil { model.isReady }
        assertThat(model.ui.level).describedAs("the defaults show meanwhile").isEqualTo(UiState().level)

        store.gate.complete(Unit)

        awaitUntil { model.ui.level == 177 }
        assertThat(WorkspaceStore(baseDir = dir).read()?.request?.level).isEqualTo(177)
    }

    @Test
    fun `an edit made while a slow read runs never overwrites the file before the read lands, and then wins`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        rememberLevel(dir, 177)
        val store = SlowStore(dir)
        val model = newModel(scope, dir, store = store, readWait = 50.milliseconds)
        awaitUntil { model.isReady }

        model.setLevel("150")
        delay(300.milliseconds) // well past the debounce
        assertThat(WorkspaceStore(baseDir = dir).read()?.request?.level).describedAs("the remembered request is not overwritten yet").isEqualTo(177)

        store.gate.complete(Unit)

        awaitUntil { WorkspaceStore(baseDir = dir).read()?.request?.level == 150 }
        assertThat(model.ui.level).describedAs("the user's edit wins over the late read").isEqualTo(150)
    }

    private fun item(frenchName: String) =
        me.chosante.common.Equipment(
            equipmentId = frenchName.hashCode(),
            guiId = 0,
            level = 100,
            name = me.chosante.common.I18nText(frenchName, frenchName, frenchName, frenchName),
            rarity = Rarity.MYTHIC,
            itemType = me.chosante.common.ItemType.RING,
            characteristics = emptyMap()
        )
}
