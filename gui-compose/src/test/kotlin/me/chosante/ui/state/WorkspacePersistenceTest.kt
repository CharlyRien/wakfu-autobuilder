package me.chosante.ui.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.workspace.WorkspaceSnapshot
import me.chosante.ui.history.toRequestSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The remembered workspace ([WorkspaceStore] + [toWorkspaceSnapshot] / [withRememberedRequest] / [withoutUnknownEntries]): every
 * request field survives a relaunch, a file this app cannot use reads as "nothing remembered", and names the game data no longer
 * knows are dropped and counted.
 */
class WorkspacePersistenceTest {
    private val boss =
        Monster(
            id = 4242,
            name = I18nText("Boss fr", "Boss en", "Boss es", "Boss pt"),
            level = 230,
            hp = 1_000_000,
            rank = 2,
            fireResistance = 40,
            waterResistance = 20,
            earthResistance = 10,
            airResistance = 0
        )

    private fun row(
        characteristic: Characteristic,
        value: String,
        weight: Int = 1,
    ): TargetRow = statDefFor(characteristic)!!.toRow(value).copy(weight = weight)

    /** A request where EVERY remembered field differs from the defaults, so a field the round trip loses cannot go unnoticed. */
    private val edited =
        UiState(
            clazz = CharacterClass.SRAM,
            level = 215,
            minLevel = 200,
            mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            scenario =
                DamageScenario(
                    element = SpellElement.WATER,
                    rangeBand = RangeBand.MELEE,
                    orientation = Orientation.SIDE,
                    berserk = true,
                    healing = true,
                    critCapPercent = 80,
                    targetResistancePercent = 15,
                    baseDamage = 140,
                    elementResistances = mapOf(SpellElement.FIRE to 40, SpellElement.WATER to 20),
                    survivabilityFloor = true,
                    minEffectiveHp = 12_000
                ),
            selectedBoss = boss,
            bossElement = SpellElement.EARTH,
            bossDifficulty = "3",
            targets =
                listOf(
                    row(Characteristic.ACTION_POINT, "12", weight = 4),
                    row(Characteristic.MOVEMENT_POINT, "5", weight = 2),
                    row(Characteristic.MASTERY_MELEE, "1")
                ),
            maxRarity = Rarity.LEGENDARY,
            excludedRarities = setOf(Rarity.COMMON, Rarity.UNCOMMON),
            duration = "45",
            stopAtMatch = true,
            forcedItems = listOf(ItemChip("Forced en", Rarity.MYTHIC, "Forcé fr")),
            excludedItems = listOf(ItemChip("Excluded en", Rarity.RELIC, "Exclu fr")),
            useSublimations = false,
            maxSublimationTier = 2,
            forcedSublimations = listOf("Sub forcée"),
            excludedSublimations = listOf("Sub exclue"),
            forcedPassives = listOf("Passif un", "Passif deux"),
            forcedRunesByItem = mapOf("Forcé fr" to listOf(1, 1, 2))
        )

    /** The request fields of [state], compared as a whole (the same projection the store writes). */
    private fun requestOf(state: UiState) = state.toRequestSnapshot(keepBossInAnyMode = true)

    @Test
    fun `every request field round-trips through the file`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val store = WorkspaceStore(baseDir = dir, ioDispatcher = Dispatchers.Unconfined)
            store.save(edited.toWorkspaceSnapshot(dataVersion = "1.93.1.62"))

            val read = store.load()
            assertThat(read).isNotNull
            assertThat(read!!.dataVersion).isEqualTo("1.93.1.62")
            val restored = UiState().withRememberedRequest(read.request)

            assertThat(requestOf(restored)).isEqualTo(requestOf(edited))
            // Field by field too, on the live types the panels read (a mapping bug can hide behind a symmetric snapshot).
            assertThat(restored.clazz).isEqualTo(edited.clazz)
            assertThat(restored.level).isEqualTo(edited.level)
            assertThat(restored.minLevel).isEqualTo(edited.minLevel)
            assertThat(restored.mode).isEqualTo(edited.mode)
            assertThat(restored.scenario).isEqualTo(edited.scenario)
            assertThat(restored.selectedBoss).isEqualTo(boss)
            assertThat(restored.bossElement).isEqualTo(SpellElement.EARTH)
            assertThat(restored.bossDifficulty).isEqualTo("3")
            assertThat(restored.targets).isEqualTo(edited.targets)
            assertThat(restored.maxRarity).isEqualTo(edited.maxRarity)
            assertThat(restored.excludedRarities).isEqualTo(edited.excludedRarities)
            assertThat(restored.duration).isEqualTo(edited.duration)
            assertThat(restored.stopAtMatch).isTrue()
            assertThat(restored.forcedItems).isEqualTo(edited.forcedItems)
            assertThat(restored.excludedItems).isEqualTo(edited.excludedItems)
            assertThat(restored.useSublimations).isFalse()
            assertThat(restored.maxSublimationTier).isEqualTo(2)
            assertThat(restored.forcedSublimations).isEqualTo(edited.forcedSublimations)
            assertThat(restored.excludedSublimations).isEqualTo(edited.excludedSublimations)
            assertThat(restored.forcedPassives).isEqualTo(edited.forcedPassives)
            assertThat(restored.forcedRunesByItem).isEqualTo(edited.forcedRunesByItem)
        }

    @Test
    fun `a boss stays remembered outside max-damage, as the workspace keeps it selected across a mode change`() {
        val inMasteries = edited.copy(mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)

        val restored = UiState().withRememberedRequest(inMasteries.toWorkspaceSnapshot("v").request)

        assertThat(restored.selectedBoss).isEqualTo(boss)
        assertThat(inMasteries.toRequestSnapshot().boss).describedAs("a SAVED build still records the boss only in max-damage").isNull()
    }

    @Test
    fun `no file reads as nothing remembered`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            assertThat(WorkspaceStore(baseDir = dir, ioDispatcher = Dispatchers.Unconfined).load()).isNull()
        }

    @Test
    fun `a corrupt, truncated or foreign file reads as nothing remembered, without throwing`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val store = WorkspaceStore(baseDir = dir, ioDispatcher = Dispatchers.Unconfined)
            val valid = WorkspaceStore.encode(edited.toWorkspaceSnapshot("v"))

            for (content in listOf("{not json", valid.take(valid.length / 2), "", "[1, 2, 3]", """{"formatVersion": 1}""")) {
                store.file().writeText(content)
                assertThat(store.load()).describedAs("content: %s", content.take(40)).isNull()
            }
        }

    @Test
    fun `a file of another format version reads as nothing remembered`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val store = WorkspaceStore(baseDir = dir, ioDispatcher = Dispatchers.Unconfined)
            store.save(edited.toWorkspaceSnapshot("v").copy(formatVersion = WorkspaceSnapshot.CURRENT_FORMAT_VERSION + 1))

            assertThat(store.load()).isNull()
        }

    @Test
    fun `a write replaces the file whole and leaves no temp file behind`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val store = WorkspaceStore(baseDir = dir, ioDispatcher = Dispatchers.Unconfined)
            store.save(UiState().toWorkspaceSnapshot("v"))
            store.save(edited.toWorkspaceSnapshot("v"))

            assertThat(dir.listDirectoryEntries().map { it.fileName.toString() }).containsExactly("workspace.json")
            assertThat(store.file().readText()).contains("Forcé fr")
            assertThat(Files.size(store.file())).isPositive()
        }

    @Test
    fun `out-of-range numbers from a hand-edited file are clamped, and an all-excluded rarity set is dropped`() {
        val request =
            edited
                .toWorkspaceSnapshot("v")
                .request
                .copy(level = 999, minLevel = -4, duration = "12x34", excludedRarities = Rarity.entries.toSet())

        val restored = UiState().withRememberedRequest(request)

        assertThat(restored.level).isEqualTo(245)
        assertThat(restored.minLevel).isZero()
        assertThat(restored.duration).isEqualTo("123")
        assertThat(restored.excludedRarities).isEmpty()
    }

    @Test
    fun `items, sublimations, passives and runes the game data no longer knows are dropped and counted`() {
        val catalog =
            WorkspaceCatalog(
                itemNames = setOf("Forcé fr"),
                sublimationNames = setOf("sub forcée", "nouveau nom"),
                runeIds = setOf(1),
                passiveExists = { clazz, name -> clazz == CharacterClass.SRAM && name == "Passif un" },
                canonicalSublimation = { if (it == "Ancien nom") "Nouveau nom" else it }
            )
        val state = edited.copy(excludedSublimations = listOf("Sub exclue", "Ancien nom"))

        val (cleaned, dropped) = state.withoutUnknownEntries(catalog)

        assertThat(cleaned.forcedItems).isEqualTo(edited.forcedItems)
        assertThat(cleaned.excludedItems).describedAs("an item gone from the data").isEmpty()
        assertThat(cleaned.forcedSublimations).containsExactly("Sub forcée")
        assertThat(cleaned.excludedSublimations).describedAs("a renamed sublimation follows its new name").containsExactly("Nouveau nom")
        assertThat(cleaned.forcedPassives).containsExactly("Passif un")
        assertThat(cleaned.forcedRunesByItem).isEqualTo(mapOf("Forcé fr" to listOf(1, 1)))
        // excluded item + "Sub exclue" + "Passif deux" + rune 2.
        assertThat(dropped).isEqualTo(4)
    }

    @Test
    fun `the pins of an item that is gone go with it`() {
        val catalog = WorkspaceCatalog(itemNames = emptySet(), sublimationNames = emptySet(), runeIds = setOf(1, 2), passiveExists = { _, _ -> true })
        val state = UiState(forcedRunesByItem = mapOf("Disparu" to listOf(1, 2)))

        val (cleaned, dropped) = state.withoutUnknownEntries(catalog)

        assertThat(cleaned.forcedRunesByItem).isEmpty()
        assertThat(dropped).isEqualTo(1)
    }

    @Test
    fun `a request the game data fully knows is left as it is`() {
        val catalog = WorkspaceCatalog(itemNames = emptySet(), sublimationNames = emptySet(), runeIds = emptySet(), passiveExists = { _, _ -> false })

        val (cleaned, dropped) = UiState().withoutUnknownEntries(catalog)

        assertThat(cleaned).isEqualTo(UiState())
        assertThat(dropped).isZero()
    }
}
