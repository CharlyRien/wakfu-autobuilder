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
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Characteristic
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.common.history.TargetSnapshot
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.history.obsolescence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The model's side of the "obsolete build" badge: a loaded build carries the engine stamp it was saved with (and keeps it when
 * saved as it is), a new search clears it, "Re-run the search" restores the request and starts it — and the library is re-scored
 * under the current rules off the UI thread, once per build and rules (cached).
 */
class BuildSearchModelObsoleteBuildTest {
    private val data = "1.93.1.62"
    private val engine = 5
    private val foundBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))
    private val searches = AtomicInteger()
    private val rescores = AtomicInteger()

    private fun saved(
        id: String,
        engineVersion: Int?,
        dataVersion: String = data,
        targets: List<TargetSnapshot> = listOf(TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1")),
    ) = HistoryEntry(
        id = id,
        name = "Build $id",
        createdAt = 1_000L,
        dataVersion = dataVersion,
        engineResultsVersion = engineVersion,
        request =
            RequestSnapshot(
                clazz = "CRA",
                level = 110,
                minLevel = 0,
                mode = "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT",
                maxRarity = Rarity.EPIC,
                duration = "1",
                stopAtMatch = false,
                targets = targets,
                forcedItems = emptyList(),
                excludedItems = emptyList()
            ),
        result =
            ResultSnapshot(
                equipments = emptyList(),
                skills = emptyMap(),
                achieved = emptyMap(),
                match = 1_000.0,
                optimal = true
            )
    )

    private suspend fun newModel(
        scope: CoroutineScope,
        dir: Path,
        vararg library: HistoryEntry,
        rescoreDelayMs: Long = 0,
        realRescorer: Boolean = false,
    ): BuildSearchModel {
        val repository = HistoryRepository(baseDir = dir, ioDispatcher = Dispatchers.Unconfined)
        library.forEach { repository.save(it) }
        return BuildSearchModel(
            scope = scope,
            buildFinder = {
                searches.incrementAndGet()
                flowOf(SolverResult(individual = foundBuild, matchPercentage = BigDecimal("100"), progressPercentage = 100, isOptimal = true))
            },
            zenithBuilder = { "" },
            copyToClipboard = {},
            readClipboard = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
            dataVersion = data,
            engineResultsVersion = engine,
            historyRepository = repository,
            buildRescorer = { params, build ->
                rescores.incrementAndGet()
                if (rescoreDelayMs > 0) Thread.sleep(rescoreDelayMs)
                if (realRescorer) {
                    me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
                        .rescore(params, build)
                } else {
                    BigDecimal("1234")
                }
            }
        ).also { model -> awaitUntil { model.ui.savedBuilds.size == library.size } }
    }

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) {
                delay(10.milliseconds)
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
    fun `a build loaded from an older engine is flagged, and saving it as it is keeps its stamp`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val model = newModel(scope, dir, saved("old", engineVersion = engine - 1))

        model.loadBuild("old")

        assertThat(model.ui.staleEngine).isEqualTo(StaleEngine(engine - 1))
        assertThat(model.ui.obsolescence(data)?.engineImproved).isTrue()

        model.saveBuild("Build old", null, asNew = false)
        awaitUntil {
            model.ui.savedBuilds
                .single()
                .createdAt != 1_000L
        }
        assertThat(
            model.ui.savedBuilds
                .single()
                .engineResultsVersion
        ).describedAs("numbers from an older engine are not relabelled as current")
            .isEqualTo(engine - 1)
    }

    @Test
    fun `a build saved before the engine version existed is flagged too`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val model = newModel(scope, dir, saved("legacy", engineVersion = null))

        model.loadBuild("legacy")

        assertThat(model.ui.staleEngine).isEqualTo(StaleEngine(null))
    }

    @Test
    fun `a current build is not flagged, and a new search stamps the current engine`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val model = newModel(scope, dir, saved("fresh", engineVersion = engine))

        model.loadBuild("fresh")
        assertThat(model.ui.staleEngine).isNull()
        assertThat(model.ui.obsolescence(data)).isNull()

        model.confirmReSearch()
        awaitUntil { model.ui.phase == Phase.Done }
        model.saveBuild("New", null, asNew = true)
        awaitUntil { model.ui.savedBuilds.size == 2 }

        assertThat(
            model.ui.savedBuilds
                .first { it.name == "New" }
                .engineResultsVersion
        ).isEqualTo(engine)
    }

    @Test
    fun `re-running an obsolete build restores its request and starts the search`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val model = newModel(scope, dir, saved("old", engineVersion = null, dataVersion = "1.92.1.58"))
        val before = searches.get()

        model.rerunSearch("old")
        awaitUntil { model.ui.phase == Phase.Done && searches.get() > before }

        assertThat(model.ui.activeBuildId).describedAs("saving the new result updates the build").isEqualTo("old")
        assertThat(model.ui.searchLocked).isFalse()
        assertThat(model.ui.staleDataVersion).isNull()
        assertThat(model.ui.staleEngine).isNull()
        assertThat(model.ui.targets.map { it.characteristic }).containsExactly(Characteristic.MASTERY_DISTANCE)
    }

    @Test
    fun `opening the library re-scores every build off the UI thread, once, and the cards read the current numbers`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val model = newModel(scope, dir, saved("a", engineVersion = engine), saved("b", engineVersion = null))
        assertThat(model.ui.libraryRescores).describedAs("nothing is re-scored before the library opens").isEmpty()

        model.goToScreen(Screen.Library)
        awaitUntil { model.ui.libraryRescores.size == 2 }

        val shown = model.ui.shownEntry(model.ui.savedBuilds.first { it.id == "a" })
        assertThat(shown.result.match).isEqualTo(1234.0)
        assertThat(shown.result.optimal).describedAs("a moved score loses its stored proof, as on load").isFalse()
        assertThat(rescores.get()).isEqualTo(2)

        // Closing and reopening the library (or the compare view) recomputes nothing: same entries, same rules.
        model.goToScreen(Screen.Builder)
        model.goToScreen(Screen.Library)
        model.startCompare("a")
        delay(200.milliseconds)
        assertThat(rescores.get()).isEqualTo(2)
    }

    @Test
    fun `a build changed since its re-score is re-scored again, and shown as stored until then`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val model = newModel(scope, dir, saved("a", engineVersion = engine))
        model.goToScreen(Screen.Library)
        awaitUntil { model.ui.libraryRescores.size == 1 }

        // Overwrite the build in place (same id, another result): the old re-score no longer applies to it.
        val changed =
            model.ui.savedBuilds
                .single()
                .let { it.copy(result = it.result.copy(match = 2_000.0)) }
        assertThat(
            model.ui
                .copy(savedBuilds = listOf(changed))
                .shownEntry(changed)
                .result.match
        ).describedAs("a re-score made for another version of the entry is ignored")
            .isEqualTo(2_000.0)

        model.loadBuild("a")
        model.saveBuild("Build a", null, asNew = false)
        awaitUntil {
            model.ui.savedBuilds
                .single()
                .createdAt != 1_000L
        }
        model.goToScreen(Screen.Library)
        awaitUntil {
            model.ui.libraryRescores["a"]?.stored ==
                model.ui.savedBuilds
                    .single()
                    .result
        }
    }

    @Test
    fun `leaving the library stops its re-scoring, and so does a search`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        val library = (1..30).map { saved("b$it", engineVersion = engine) }.toTypedArray()
        val model = newModel(scope, dir, *library, rescoreDelayMs = 40)

        model.goToScreen(Screen.Library)
        awaitUntil { rescores.get() >= 2 }
        model.goToScreen(Screen.Builder)
        val atLeave = rescores.get()
        delay(500.milliseconds)
        assertThat(rescores.get()).describedAs("at most the build in flight finishes").isLessThanOrEqualTo(atLeave + 1)

        model.goToScreen(Screen.Library)
        awaitUntil { rescores.get() > atLeave + 1 }
        model.search()
        val atSearch = rescores.get()
        delay(500.milliseconds)
        assertThat(rescores.get()).isLessThanOrEqualTo(atSearch + 1)
    }

    @Test
    fun `a card and a reload show the same numbers on the real rescorer, blank field or typed 0`(
        @TempDir dir: Path,
    ) = withScope { scope ->
        fun request(air: String) =
            listOf(
                TargetSnapshot(Characteristic.MASTERY_DISTANCE, "1"),
                TargetSnapshot(Characteristic.RESISTANCE_ELEMENTARY_FIRE, "100"),
                TargetSnapshot(Characteristic.RESISTANCE_ELEMENTARY_WIND, air)
            )
        val model =
            newModel(
                scope,
                dir,
                saved("blank", engineVersion = engine, targets = request("")),
                saved("zero", engineVersion = engine, targets = request("0")),
                realRescorer = true
            )
        model.goToScreen(Screen.Library)
        awaitUntil { model.ui.libraryRescores.size == 2 }
        val cards = listOf("blank", "zero").associateWith { id -> model.ui.shownEntry(model.ui.savedBuilds.first { it.id == id }).result }

        for ((id, card) in cards) {
            assertThat(card.optimal).describedAs("%s: an older version searched this request on the pre-filtered pool", id).isFalse()
            model.loadBuild(id)
            assertThat(model.ui.optimal).describedAs("%s: the reload agrees with the card", id).isEqualTo(card.optimal)
            assertThat(model.ui.match.toDouble()).describedAs("%s: score", id).isEqualTo(card.match)
            assertThat(model.ui.achieved).describedAs("%s: stats", id).isEqualTo(card.achieved)
        }
    }
}
