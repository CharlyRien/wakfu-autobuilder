package me.chosante.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class BuildSearchModelLibraryTest {
    @Test
    fun `editBuild persists name, note and tags and updates the active name`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val id = saveFreshBuild(model)

            model.editBuild(id, "Renamed", "my note", listOf("PvP", "pvp", "Solo"), folder = null)
            awaitUntil {
                model.ui.savedBuilds
                    .firstOrNull { it.id == id }
                    ?.tags
                    ?.isNotEmpty() == true
            }

            val saved = model.ui.savedBuilds.single { it.id == id }
            assertThat(saved.name).isEqualTo("Renamed")
            assertThat(saved.note).isEqualTo("my note")
            assertThat(saved.tags).containsExactly("PvP", "Solo") // case-insensitive dedupe
            assertThat(model.ui.activeBuildName).isEqualTo("Renamed")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `editBuild into another build's name is rejected with a toast and no write`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val idA = saveFreshBuild(model, name = "Alpha")
            model.clearActiveBuild()
            val idB = saveFreshBuild(model, name = "Beta")

            model.editBuild(idA, "Beta", null, emptyList(), folder = null)

            assertThat(model.ui.toast).isNotNull()
            assertThat(
                model.ui.savedBuilds
                    .single { it.id == idA }
                    .name
            ).isEqualTo("Alpha")
            assertThat(idB).isNotEqualTo(idA)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resaving a loaded build preserves its tags`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val id = saveFreshBuild(model)
            model.editBuild(id, "Tagged", null, listOf("PvP"), folder = null)
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .tags == listOf("PvP")
            }

            model.loadBuild(id)
            // "Update build": overwrite the same entry from the workspace (asNew = false).
            model.saveBuild(name = "Tagged", note = null, asNew = false)
            awaitUntil {
                model.ui.savedBuilds
                    .firstOrNull { it.id == id }
                    ?.name == "Tagged"
            }

            assertThat(
                model.ui.savedBuilds
                    .single { it.id == id }
                    .tags
            ).containsExactly("PvP")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `editBuild creates, then clears, a folder`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val id = saveFreshBuild(model)

            model.editBuild(id, "Build", null, emptyList(), folder = "  Mes builds  ")
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .folder == "Mes builds"
            }

            model.editBuild(id, "Build", null, emptyList(), folder = "   ")
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .folder == null
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `renameFolder rewrites every member`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val a = saveFreshBuild(model, name = "A")
            model.clearActiveBuild()
            val b = saveFreshBuild(model, name = "B")
            model.editBuild(a, "A", null, emptyList(), folder = "Old")
            model.editBuild(b, "B", null, emptyList(), folder = "Old")
            awaitUntil { model.ui.savedBuilds.count { it.folder == "Old" } == 2 }

            model.renameFolder("Old", "New")
            awaitUntil { model.ui.savedBuilds.count { it.folder == "New" } == 2 }
            assertThat(model.ui.savedBuilds.none { it.folder == "Old" }).isTrue()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `renameFolder into an existing name merges with the canonical casing`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val a = saveFreshBuild(model, name = "A")
            model.clearActiveBuild()
            val b = saveFreshBuild(model, name = "B")
            model.editBuild(a, "A", null, emptyList(), folder = "Pvp")
            model.editBuild(b, "B", null, emptyList(), folder = "Solo")
            awaitUntil { model.ui.savedBuilds.any { it.folder == "Pvp" } && model.ui.savedBuilds.any { it.folder == "Solo" } }

            // Rename "Solo" → "PVP": merges into the existing "Pvp" folder (its canonical casing wins).
            model.renameFolder("Solo", "PVP")
            awaitUntil { model.ui.savedBuilds.count { it.folder == "Pvp" } == 2 }
            assertThat(model.ui.savedBuilds.none { it.folder == "Solo" || it.folder == "PVP" }).isTrue()
            assertThat(model.ui.toast).isEqualTo("Folders merged")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deleteFolder unfiles its members and resets a pointing filter`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val id = saveFreshBuild(model)
            model.editBuild(id, "Build", null, emptyList(), folder = "Trash")
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .folder == "Trash"
            }
            model.setLibraryFolderFilter(LibraryFolderFilter.Named("Trash"))

            model.deleteFolder("Trash")
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .folder == null
            }
            assertThat(model.ui.libraryFolder).isEqualTo(LibraryFolderFilter.All)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `resaving a loaded build preserves its folder`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val id = saveFreshBuild(model)
            model.editBuild(id, "Filed", null, emptyList(), folder = "Keep")
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .folder == "Keep"
            }

            model.loadBuild(id)
            model.saveBuild(name = "Filed", note = null, asNew = false)
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .name == "Filed"
            }

            assertThat(
                model.ui.savedBuilds
                    .single { it.id == id }
                    .folder
            ).isEqualTo("Keep")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deleting the last build of the filtered folder resets the filter to All`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val id = saveFreshBuild(model)
            model.editBuild(id, "Only", null, emptyList(), folder = "Lonely")
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .folder == "Lonely"
            }
            model.setLibraryFolderFilter(LibraryFolderFilter.Named("Lonely"))

            model.deleteBuild(id)
            awaitUntil { model.ui.savedBuilds.none { it.id == id } }
            assertThat(model.ui.libraryFolder).isEqualTo(LibraryFolderFilter.All)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a tag survives being removed from every build (it stays in the registry)`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val id = saveFreshBuild(model)
            model.editBuild(id, "Build", null, listOf("Keep"), folder = null)
            awaitUntil { model.ui.knownTags.contains("Keep") }

            // Remove it from the only build that had it.
            model.editBuild(id, "Build", null, emptyList(), folder = null)
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .tags
                    .isEmpty()
            }

            // The tag is still known (assignable again) — it wasn't implicitly destroyed.
            assertThat(model.ui.knownTags).contains("Keep")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `createTag adds a standalone tag with no build assignment`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            saveFreshBuild(model)

            model.createTag("  Solo  ")
            assertThat(model.ui.knownTags).contains("Solo")
            assertThat(model.ui.savedBuilds.flatMap { it.tags }).doesNotContain("Solo")

            // Duplicates (case-insensitive) are ignored.
            model.createTag("solo")
            assertThat(model.ui.knownTags.count { it.equals("solo", ignoreCase = true) }).isEqualTo(1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `renameTag rewrites the tag on every build that carries it`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val a = saveFreshBuild(model, name = "A")
            model.clearActiveBuild()
            val b = saveFreshBuild(model, name = "B")
            model.editBuild(a, "A", null, listOf("Old"), folder = null)
            model.editBuild(b, "B", null, listOf("Old", "Keep"), folder = null)
            awaitUntil { model.ui.savedBuilds.count { it.tags.contains("Old") } == 2 }

            model.renameTag("Old", "New")
            awaitUntil { model.ui.savedBuilds.count { it.tags.contains("New") } == 2 }
            assertThat(model.ui.savedBuilds.none { it.tags.contains("Old") }).isTrue()
            // Unrelated tags are untouched.
            assertThat(
                model.ui.savedBuilds
                    .single { it.id == b }
                    .tags
            ).containsExactlyInAnyOrder("New", "Keep")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `renameTag into an existing tag merges with its canonical casing`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val a = saveFreshBuild(model, name = "A")
            model.clearActiveBuild()
            val b = saveFreshBuild(model, name = "B")
            model.editBuild(a, "A", null, listOf("Pvp"), folder = null)
            model.editBuild(b, "B", null, listOf("Solo"), folder = null)
            awaitUntil { model.ui.savedBuilds.any { it.tags.contains("Pvp") } && model.ui.savedBuilds.any { it.tags.contains("Solo") } }

            // "Solo" → "PVP" merges into the existing "Pvp" tag (its casing wins).
            model.renameTag("Solo", "PVP")
            awaitUntil { model.ui.savedBuilds.count { it.tags.contains("Pvp") } == 2 }
            assertThat(model.ui.savedBuilds.none { it.tags.any { t -> t == "Solo" || t == "PVP" } }).isTrue()
            assertThat(model.ui.toast).isEqualTo("Tags merged")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deleteTag removes the tag from every build and clears a pointing filter`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val id = saveFreshBuild(model)
            model.editBuild(id, "Build", null, listOf("Gone", "Keep"), folder = null)
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .tags
                    .contains("Gone")
            }
            model.toggleLibraryTag("Gone")
            assertThat(model.ui.librarySelectedTags).contains("gone")

            model.deleteTag("Gone")
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .tags == listOf("Keep")
            }
            assertThat(model.ui.librarySelectedTags).doesNotContain("gone")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a selected standalone (zero-build) tag filter survives an unrelated edit`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val id = saveFreshBuild(model)
            model.createTag("Wip") // standalone, on zero builds
            model.toggleLibraryTag("Wip")
            assertThat(model.ui.librarySelectedTags).contains("wip")

            // Editing an unrelated build triggers a reload + filter coercion; the registry tag must stay.
            model.editBuild(id, "Build", null, listOf("Other"), folder = null)
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == id }
                    .tags == listOf("Other")
            }

            assertThat(model.ui.librarySelectedTags).contains("wip")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `assigning a folder adopts the casing of an existing one (no case-variant duplicate)`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val model = model(scope, tempDir)
        try {
            val a = saveFreshBuild(model, name = "A")
            model.clearActiveBuild()
            val b = saveFreshBuild(model, name = "B")
            model.editBuild(a, "A", null, emptyList(), folder = "PvP")
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == a }
                    .folder == "PvP"
            }

            // Assign a case-variant of the existing folder → canonicalized to "PvP", not a new "pvp".
            model.editBuild(b, "B", null, emptyList(), folder = "pvp")
            awaitUntil {
                model.ui.savedBuilds
                    .single { it.id == b }
                    .folder != null
            }

            assertThat(
                model.ui.savedBuilds
                    .single { it.id == b }
                    .folder
            ).isEqualTo("PvP")
            assertThat(
                model.ui.savedBuilds
                    .mapNotNull { it.folder }
                    .toSet()
            ).containsExactly("PvP")
        } finally {
            scope.cancel()
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private fun model(
        scope: CoroutineScope,
        tempDir: Path,
    ): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = {
                flowOf(
                    GeneticAlgorithmResult(
                        individual = fakeBuild(),
                        matchPercentage = BigDecimal("100"),
                        progressPercentage = 100,
                        isOptimal = true
                    )
                )
            },
            zenithBuilder = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            historyRepository = HistoryRepository(baseDir = tempDir, ioDispatcher = Dispatchers.Unconfined),
            libraryPreferences = LibraryPreferences(null)
        )

    /** Runs a search to populate a workspace build, then saves it as a new library entry. Returns its id. */
    private suspend fun saveFreshBuild(
        model: BuildSearchModel,
        name: String = "Build",
    ): String {
        model.setDuration("1")
        model.search()
        awaitUntil { model.ui.phase == Phase.Done }
        model.saveBuild(name = name, note = null, asNew = true)
        awaitUntil { model.ui.savedBuilds.any { it.name == name } }
        return model.ui.savedBuilds
            .first { it.name == name }
            .id
    }

    private fun fakeBuild(): BuildCombination =
        BuildCombination(
            equipments =
                listOf(
                    Equipment(
                        equipmentId = 1,
                        guiId = 1,
                        level = 110,
                        name = I18nText(fr = "Cape", en = "Cape", es = "", pt = ""),
                        rarity = Rarity.LEGENDARY,
                        itemType = ItemType.CAPE,
                        characteristics = mapOf(Characteristic.MASTERY_DISTANCE to 60)
                    )
                ),
            characterSkills = CharacterSkills(110)
        )

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) {
                delay(25)
            }
        }
    }
}
