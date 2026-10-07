package me.chosante.ui.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.ComputeBudget
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm.MostMasteriesProof
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.i18n.Lang
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import kotlin.time.Duration.Companion.seconds

class BuildSearchModelSettingsTest {
    @TempDir lateinit var dir: Path
    private val result =
        SolverResult(
            individual = BuildCombination(emptyList(), CharacterSkills(110)),
            matchPercentage = BigDecimal("1000"),
            progressPercentage = 100,
            isOptimal = false,
            mostMasteriesObjective = 1000L,
            maxDamageObjective = 1000L
        )

    private suspend fun awaitUntil(predicate: () -> Boolean) =
        withTimeout(10.seconds) {
            while (!predicate()) delay(10)
        }

    @Test
    fun `launch restores each preset and reset preserves language library order tags and saved builds`(): Unit =
        runBlocking {
            val node = Preferences.userRoot().node("me/chosante/wakfu-autobuilder-test/settings-model-${UUID.randomUUID()}")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val repository = HistoryRepository(dir, Dispatchers.Unconfined)
                val entry =
                    HistoryEntry(
                        id = "keep",
                        name = "Keep me",
                        createdAt = 1L,
                        dataVersion = "test",
                        tags = listOf("keep-tag"),
                        folder = "keep-folder",
                        request = RequestSnapshot("CRA", 110, 0, "FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT", Rarity.EPIC, "1", false, emptyList(), emptyList(), emptyList()),
                        result = ResultSnapshot(emptyList(), emptyMap(), emptyMap(), 1000.0, false)
                    )
                repository.save(entry)
                val prefs = LibraryPreferences(node)
                prefs.saveLang(Lang.PT)
                prefs.saveSort(LibrarySort.NAME)
                prefs.saveGroupByClass(true)
                prefs.saveTags(listOf("keep-tag"))
                prefs.saveVerifyOptimality(false)
                prefs.saveHideChosen(true)
                val opened = mutableListOf<String>()
                for (preset in ProcessorUse.entries) {
                    val settings = ComputeSettings(preset, minOf(3, ComputeBudget.availableCores))
                    prefs.saveComputeSettings(settings)
                    val model =
                        BuildSearchModel(
                            scope,
                            libraryPreferences = prefs,
                            historyRepository = repository,
                            ioDispatcher = Dispatchers.Unconfined,
                            mainDispatcher = Dispatchers.Unconfined,
                            openBrowser = { opened += it },
                            backgroundProofCanceller = {}
                        )
                    awaitUntil { model.ui.savedBuilds.size == 1 }
                    assertThat(model.ui.computeSettings).isEqualTo(settings)
                    assertThat(model.ui.verifyOptimality).isFalse()
                    assertThat(model.ui.pickerHideChosen).isTrue()
                    model.requestResetSettings()
                    assertThat(model.ui.modal).isEqualTo(Modal.ConfirmResetSettings)
                    model.resetSettings()
                    assertThat(model.ui.computeSettings).isEqualTo(ComputeSettings())
                    assertThat(model.ui.verifyOptimality).isTrue()
                    assertThat(model.ui.pickerHideChosen).isFalse()
                    assertThat(model.ui.lang).isEqualTo(Lang.PT)
                    assertThat(model.ui.librarySort).isEqualTo(LibrarySort.NAME)
                    assertThat(model.ui.libraryGroupByClass).isTrue()
                    assertThat(model.ui.knownTags).contains("keep-tag")
                    assertThat(model.ui.savedBuilds).containsExactly(entry)
                    assertThat(repository.loadAll()).containsExactly(entry)
                    assertThat(LibraryPreferences(node).loadComputeSettings()).isEqualTo(ComputeSettings())
                    assertThat(LibraryPreferences(node).loadVerifyOptimality()).isTrue()
                    assertThat(LibraryPreferences(node).loadHideChosen()).isFalse()
                    assertThat(LibraryPreferences(node).loadLang()).isEqualTo(Lang.PT)
                    model.setProcessorUse(ProcessorUse.LOW)
                    model.setPickerHideChosen(true)
                    model.newBuild()
                    assertThat(model.ui.computeSettings.preset).isEqualTo(ProcessorUse.LOW)
                    assertThat(model.ui.pickerHideChosen).isTrue()
                    assertThat(model.ui.librarySort).isEqualTo(LibrarySort.NAME)
                    model.reportBug()
                    prefs.saveVerifyOptimality(false)
                    prefs.saveHideChosen(true)
                }
                assertThat(opened).hasSize(ProcessorUse.entries.size).containsOnly("https://github.com/CharlyRien/wakfu-autobuilder/issues")
            } finally {
                scope.cancel()
                node.removeNode()
            }
        }

    @Test
    fun `search keeps its snapshot and each proof captures the latest budget without mutating in flight`(): Unit =
        runBlocking {
            for (mode in listOf(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val searchStarted = CompletableDeferred<WakfuBestBuildParams>()
                val finishSearch = CompletableDeferred<Unit>()
                val proofStarted = CompletableDeferred<WakfuBestBuildParams>()
                val releaseProof = CountDownLatch(1)
                val cancelledWarmups = AtomicInteger()
                try {
                    val model =
                        BuildSearchModel(
                            scope,
                            buildFinder = { params ->
                                flow {
                                    searchStarted.complete(params)
                                    emit(result)
                                    finishSearch.await()
                                }
                            },
                            mmQualityProver = { params, _, _ ->
                                proofStarted.complete(params)
                                releaseProof.await(10, TimeUnit.SECONDS)
                                MostMasteriesProof.Unavailable
                            },
                            optimalityProver = { params, _, _, _ ->
                                proofStarted.complete(params)
                                releaseProof.await(10, TimeUnit.SECONDS)
                                MaxDamageSearch.MaxDamageProof.Unavailable
                            },
                            backgroundProofCanceller = { cancelledWarmups.incrementAndGet() },
                            libraryPreferences = LibraryPreferences(null),
                            historyRepository = HistoryRepository(dir, Dispatchers.Unconfined),
                            mainDispatcher = Dispatchers.Unconfined,
                            ioDispatcher = Dispatchers.Unconfined,
                            damageBreakdown = { _, _, _, _ -> emptyList() }
                        )
                    model.setMode(mode)
                    model.setProcessorUse(ProcessorUse.CUSTOM)
                    model.setCustomCores(1)
                    model.search()
                    val search = withTimeout(10.seconds) { searchStarted.await() }
                    assertThat(search.computeBudget.logicalCores).isEqualTo(1)
                    val next = minOf(2, ComputeBudget.availableCores)
                    model.setCustomCores(next)
                    assertThat(search.computeBudget.logicalCores).isEqualTo(1)
                    val cancelsBeforeEnd = cancelledWarmups.get()
                    finishSearch.complete(Unit)
                    val proof = withTimeout(10.seconds) { proofStarted.await() }
                    assertThat(proof.computeBudget.logicalCores).isEqualTo(next)
                    if (next != 1) assertThat(cancelledWarmups.get()).isGreaterThan(cancelsBeforeEnd)
                    model.setCustomCores(1)
                    assertThat(proof.computeBudget.logicalCores).isEqualTo(next)
                    releaseProof.countDown()
                } finally {
                    releaseProof.countDown()
                    scope.cancel()
                }
            }
        }
}
