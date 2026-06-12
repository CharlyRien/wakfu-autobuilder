package me.chosante.ui.state

import me.chosante.common.CharacterClass
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.prefs.Preferences

class LibraryOrganizeTest {
    @Test
    fun `NEWEST and OLDEST order by createdAt`() {
        val builds = listOf(entry("a", clazz = "CRA", createdAt = 1_000), entry("b", clazz = "CRA", createdAt = 3_000), entry("c", clazz = "CRA", createdAt = 2_000))

        assertThat(organize(builds, sort = LibrarySort.NEWEST).single().builds.map { it.name })
            .containsExactly("b", "c", "a")
        assertThat(organize(builds, sort = LibrarySort.OLDEST).single().builds.map { it.name })
            .containsExactly("a", "c", "b")
    }

    @Test
    fun `NAME sorts case-insensitively and tie-breaks on createdAt desc`() {
        val builds =
            listOf(
                entry("banana", createdAt = 1),
                entry("Apple", createdAt = 1),
                entry("apple", createdAt = 5)
            )
        // "apple"/"Apple" tie on lowercase name → newer (createdAt 5) first.
        assertThat(organize(builds, sort = LibrarySort.NAME).single().builds.map { it.name })
            .containsExactly("apple", "Apple", "banana")
    }

    @Test
    fun `LEVEL sorts by request level desc, tie-break createdAt desc`() {
        val builds =
            listOf(
                entry("low", level = 50, createdAt = 9),
                entry("high-old", level = 200, createdAt = 1),
                entry("high-new", level = 200, createdAt = 7)
            )
        assertThat(organize(builds, sort = LibrarySort.LEVEL).single().builds.map { it.name })
            .containsExactly("high-new", "high-old", "low")
    }

    @Test
    fun `search matches name and class display name, case-insensitively`() {
        val builds =
            listOf(
                entry("My Distance Build", clazz = "CRA"),
                entry("Tank", clazz = "FECA")
            )
        assertThat(organize(builds, search = "distance").single().builds.map { it.name }).containsExactly("My Distance Build")
        // Class display name is "Feca"; matching by class works case-insensitively.
        assertThat(organize(builds, search = "fec").single().builds.map { it.name }).containsExactly("Tank")
        assertThat(organize(builds, search = "zzz").single().builds).isEmpty()
    }

    @Test
    fun `class filter matches via the restored enum, regardless of stored casing`() {
        val builds =
            listOf(
                entry("a", clazz = "cra"), // lowercase stored name still resolves to CRA
                entry("b", clazz = "IOP")
            )
        assertThat(organize(builds, classFilter = CharacterClass.CRA).single().builds.map { it.name }).containsExactly("a")
    }

    @Test
    fun `groupByClass keys on the enum, orders groups by label, preserves in-group sort`() {
        val builds =
            listOf(
                entry("iop1", clazz = "IOP", createdAt = 1),
                entry("cra1", clazz = "CRA", createdAt = 5),
                entry("cra2", clazz = "CRA", createdAt = 9),
                entry("iop2", clazz = "IOP", createdAt = 3)
            )
        val groups = organize(builds, sort = LibrarySort.NEWEST, groupByClass = true)
        // Groups ordered by label A–Z: Cra before Iop.
        assertThat(groups.map { it.clazz }).containsExactly(CharacterClass.CRA, CharacterClass.IOP)
        assertThat(groups[0].builds.map { it.name }).containsExactly("cra2", "cra1")
        assertThat(groups[1].builds.map { it.name }).containsExactly("iop2", "iop1")
    }

    @Test
    fun `empty input yields one empty ungrouped section`() {
        val groups = organize(emptyList())
        assertThat(groups).hasSize(1)
        assertThat(groups.single().clazz).isNull()
        assertThat(groups.single().builds).isEmpty()
    }

    @Test
    fun `classCounts lists only classes present, ordered by label`() {
        val builds = listOf(entry("a", clazz = "IOP"), entry("b", clazz = "CRA"), entry("c", clazz = "CRA"))
        assertThat(classCounts(builds)).containsExactly(CharacterClass.CRA to 2, CharacterClass.IOP to 1)
    }

    @Test
    fun `tag filter is OR and case-insensitive`() {
        val builds =
            listOf(
                entry("both", tags = listOf("PvP", "Solo")),
                entry("pvp-only", tags = listOf("pvp")),
                entry("solo-only", tags = listOf("Solo")),
                entry("none", tags = emptyList())
            )
        // Single tag selects every build carrying it (case-insensitively).
        assertThat(organize(builds, selectedTags = setOf("pvp")).single().builds.map { it.name })
            .containsExactlyInAnyOrder("both", "pvp-only")
        // Multiple tags = OR: any build carrying at least one of them.
        assertThat(organize(builds, selectedTags = setOf("pvp", "solo")).single().builds.map { it.name })
            .containsExactlyInAnyOrder("both", "pvp-only", "solo-only")
    }

    @Test
    fun `search matches a tag`() {
        val builds = listOf(entry("a", tags = listOf("Speedrun")), entry("b", tags = emptyList()))
        assertThat(organize(builds, search = "speed").single().builds.map { it.name }).containsExactly("a")
    }

    @Test
    fun `tagCounts aggregates case-insensitively keeping first casing, ordered by label`() {
        val builds =
            listOf(
                entry("a", tags = listOf("PvP", "Solo")),
                entry("b", tags = listOf("pvp"))
            )
        assertThat(tagCounts(builds)).containsExactly("PvP" to 2, "Solo" to 1)
    }

    // ── LibraryPreferences ──────────────────────────────────────────────────────────────────

    private val testNode: Preferences = Preferences.userRoot().node("me/chosante/wakfu-autobuilder-test")

    @AfterEach
    fun cleanup() {
        runCatching { testNode.removeNode() }
    }

    @Test
    fun `LibraryPreferences round-trips sort and group`() {
        val prefs = LibraryPreferences(testNode)
        prefs.saveSort(LibrarySort.LEVEL)
        prefs.saveGroupByClass(true)

        val reloaded = LibraryPreferences(testNode)
        assertThat(reloaded.loadSort()).isEqualTo(LibrarySort.LEVEL)
        assertThat(reloaded.loadGroupByClass()).isTrue()
    }

    @Test
    fun `LibraryPreferences falls back on an invalid stored sort`() {
        testNode.put("librarySort", "NOT_A_REAL_SORT")
        assertThat(LibraryPreferences(testNode).loadSort()).isEqualTo(LibrarySort.NEWEST)
    }

    @Test
    fun `LibraryPreferences round-trips the tag registry`() {
        LibraryPreferences(testNode).saveTags(listOf("PvP", "Solo", "Speedrun"))
        assertThat(LibraryPreferences(testNode).loadTags()).containsExactly("PvP", "Solo", "Speedrun")
    }

    @Test
    fun `LibraryPreferences loads an empty tag registry by default`() {
        assertThat(LibraryPreferences(testNode).loadTags()).isEmpty()
    }

    @Test
    fun `LibraryPreferences defaults when nothing stored`() {
        val prefs = LibraryPreferences(testNode)
        assertThat(prefs.loadSort()).isEqualTo(LibrarySort.NEWEST)
        assertThat(prefs.loadGroupByClass()).isFalse()
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `folder filter selects All, Unfiled, or a named folder`() {
        val builds =
            listOf(
                entry("filed-a", folder = "Pvp"),
                entry("filed-b", folder = "Solo"),
                entry("loose", folder = null)
            )
        assertThat(organize(builds, folder = LibraryFolderFilter.All).single().builds).hasSize(3)
        assertThat(organize(builds, folder = LibraryFolderFilter.Unfiled).single().builds.map { it.name }).containsExactly("loose")
        assertThat(organize(builds, folder = LibraryFolderFilter.Named("Pvp")).single().builds.map { it.name }).containsExactly("filed-a")
    }

    // ── tagInputSuggestions (the tag input's pure filter/create logic) ───────────────────────

    @Test
    fun `tagInputSuggestions filters known tags by query, excluding already-selected ones`() {
        val result = tagInputSuggestions(known = listOf("PvP", "Solo", "Speedrun"), selected = listOf("Solo"), rawQuery = "s")
        // "s" matches Solo & Speedrun, but Solo is already selected → only Speedrun is offered.
        assertThat(result.suggestions).containsExactly("Speedrun")
        assertThat(result.canCreate).isTrue() // "s" isn't an exact known/selected tag
    }

    @Test
    fun `tagInputSuggestions offers create only for a genuinely new name`() {
        val known = listOf("PvP")
        assertThat(tagInputSuggestions(known, selected = emptyList(), rawQuery = "  Healer ").canCreate).isTrue()
        // Exact (case-insensitive) match to a known tag → no create.
        assertThat(tagInputSuggestions(known, selected = emptyList(), rawQuery = "pvp").canCreate).isFalse()
        // Already selected → no create.
        assertThat(tagInputSuggestions(known, selected = listOf("Healer"), rawQuery = "healer").canCreate).isFalse()
        // Blank → no create.
        assertThat(tagInputSuggestions(known, selected = emptyList(), rawQuery = "   ").canCreate).isFalse()
    }

    @Test
    fun `tagInputSuggestions with a blank query lists every unselected tag`() {
        val result = tagInputSuggestions(known = listOf("PvP", "Solo"), selected = listOf("PvP"), rawQuery = "")
        assertThat(result.suggestions).containsExactly("Solo")
        assertThat(result.canCreate).isFalse()
    }

    @Test
    fun `folderCounts lists non-null folders ordered by name`() {
        val builds =
            listOf(
                entry("a", folder = "Zeta"),
                entry("b", folder = "alpha"),
                entry("c", folder = "alpha"),
                entry("d", folder = null)
            )
        assertThat(folderCounts(builds)).containsExactly("alpha" to 2, "Zeta" to 1)
    }

    private fun organize(
        builds: List<HistoryEntry>,
        search: String = "",
        sort: LibrarySort = LibrarySort.NEWEST,
        classFilter: CharacterClass? = null,
        groupByClass: Boolean = false,
        selectedTags: Set<String> = emptySet(),
        folder: LibraryFolderFilter = LibraryFolderFilter.All,
    ): List<LibraryGroup> = organizeLibrary(builds, search, sort, classFilter, groupByClass, selectedTags, folder)

    private fun entry(
        name: String,
        clazz: String = "CRA",
        level: Int = 110,
        createdAt: Long = 0L,
        tags: List<String> = emptyList(),
        folder: String? = null,
    ): HistoryEntry =
        HistoryEntry(
            id = name,
            name = name,
            createdAt = createdAt,
            dataVersion = "1.91.1.54",
            tags = tags,
            folder = folder,
            request =
                RequestSnapshot(
                    clazz = clazz,
                    level = level,
                    minLevel = 80,
                    mode = "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT",
                    maxRarity = Rarity.EPIC,
                    duration = "20",
                    stopAtMatch = false,
                    targets = emptyList(),
                    forcedItems = emptyList(),
                    excludedItems = emptyList()
                ),
            result =
                ResultSnapshot(
                    equipments = emptyList(),
                    skills = emptyMap(),
                    achieved = emptyMap(),
                    match = 90.0,
                    optimal = false
                )
        )
}
