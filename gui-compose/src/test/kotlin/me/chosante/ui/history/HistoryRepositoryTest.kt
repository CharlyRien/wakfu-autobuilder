package me.chosante.ui.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.ItemRef
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.common.history.TargetSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

// NB: each @Test uses a *block body* (not `= runBlocking { … }`). An expression body returns the
// lambda's value, giving the test a non-Unit return type that JUnit 5 silently skips — block bodies
// return Unit and are discovered reliably.
class HistoryRepositoryTest {
    @Test
    fun `save then loadAll round-trips an entry`(
        @TempDir tempDir: Path,
    ) {
        runBlocking {
            val repo = HistoryRepository(baseDir = tempDir, ioDispatcher = Dispatchers.Unconfined)
            val entry = sampleEntry(id = "11111111-1111-1111-1111-111111111111", name = "Cra 110 · Distance")

            repo.save(entry)
            val loaded = repo.loadAll()

            assertThat(loaded).hasSize(1)
            assertThat(loaded.single()).isEqualTo(entry)
        }
    }

    @Test
    fun `loadAll returns newest first`(
        @TempDir tempDir: Path,
    ) {
        runBlocking {
            val repo = HistoryRepository(baseDir = tempDir, ioDispatcher = Dispatchers.Unconfined)
            val older = sampleEntry(id = "a", name = "older").copy(createdAt = 1_000)
            val newer = sampleEntry(id = "b", name = "newer").copy(createdAt = 2_000)

            repo.save(older)
            repo.save(newer)

            assertThat(repo.loadAll().map { it.name }).containsExactly("newer", "older")
        }
    }

    @Test
    fun `save overwrites the same id`(
        @TempDir tempDir: Path,
    ) {
        runBlocking {
            val repo = HistoryRepository(baseDir = tempDir, ioDispatcher = Dispatchers.Unconfined)
            repo.save(sampleEntry(id = "x", name = "first"))
            repo.save(sampleEntry(id = "x", name = "second"))

            val loaded = repo.loadAll()
            assertThat(loaded).hasSize(1)
            assertThat(loaded.single().name).isEqualTo("second")
        }
    }

    @Test
    fun `delete removes the entry`(
        @TempDir tempDir: Path,
    ) {
        runBlocking {
            val repo = HistoryRepository(baseDir = tempDir, ioDispatcher = Dispatchers.Unconfined)
            repo.save(sampleEntry(id = "x", name = "doomed"))
            repo.delete("x")

            assertThat(repo.loadAll()).isEmpty()
        }
    }

    @Test
    fun `loadAll skips a corrupt file instead of failing`(
        @TempDir tempDir: Path,
    ) {
        runBlocking {
            val repo = HistoryRepository(baseDir = tempDir, ioDispatcher = Dispatchers.Unconfined)
            repo.save(sampleEntry(id = "good", name = "good"))
            repo
                .directory()
                .resolve("garbage.json")
                .toFile()
                .writeText("{ not valid json")

            val loaded = repo.loadAll()
            assertThat(loaded.map { it.name }).containsExactly("good")
        }
    }

    @Test
    fun `appDataDir resolves per OS`() {
        val home = "/home/tester"
        val mac = appDataDir(osName = "Mac OS X", env = { null }, userHome = home)
        assertThat(mac.toString()).isEqualTo("/home/tester/Library/Application Support/WakfuAutobuilder")

        val linux = appDataDir(osName = "Linux", env = { null }, userHome = home)
        assertThat(linux.toString()).isEqualTo("/home/tester/.local/share/wakfu-autobuilder")

        val linuxXdg = appDataDir(osName = "Linux", env = { if (it == "XDG_DATA_HOME") "/custom/data" else null }, userHome = home)
        assertThat(linuxXdg.toString()).isEqualTo("/custom/data/wakfu-autobuilder")
    }

    private fun sampleEntry(
        id: String,
        name: String,
    ): HistoryEntry =
        HistoryEntry(
            id = id,
            name = name,
            createdAt = 1_700_000_000_000,
            note = "a note",
            dataVersion = "1.91.1.54",
            request =
                RequestSnapshot(
                    clazz = "CRA",
                    level = 110,
                    minLevel = 80,
                    mode = "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT",
                    solver = "OR_TOOLS",
                    maxRarity = Rarity.EPIC,
                    duration = "20",
                    stopAtMatch = false,
                    targets =
                        listOf(
                            TargetSnapshot(Characteristic.ACTION_POINT, "11"),
                            TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1")
                        ),
                    forcedItems = listOf(ItemRef("Gelano", Rarity.RELIC, "Gelano")),
                    excludedItems = emptyList()
                ),
            result =
                ResultSnapshot(
                    equipments =
                        listOf(
                            Equipment(
                                equipmentId = 1234,
                                guiId = 5678,
                                level = 110,
                                name = I18nText(fr = "Cape de test", en = "Test Cape", es = "", pt = ""),
                                rarity = Rarity.MYTHIC,
                                itemType = ItemType.CAPE,
                                characteristics = mapOf(Characteristic.MASTERY_DISTANCE to 80)
                            )
                        ),
                    skills = mapOf("Distance Mastery" to 20, "Lock" to 5),
                    achieved = mapOf(Characteristic.ACTION_POINT to 11, Characteristic.MASTERY_DISTANCE to 1280),
                    match = 98.0,
                    optimal = true
                ),
            zenithUrl = "https://zenithwakfu.com/builder/abc123"
        )
}
