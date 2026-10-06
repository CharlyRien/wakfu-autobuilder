package me.chosante.ui.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.history.historyJson
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.Tr
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class BuildSearchModelLibraryErrorsTest {
    @Test
    fun `each failed library write shows a localized retry message without a raw file path`(
        @TempDir dir: Path,
    ): Unit =
        runTest {
            for (lang in listOf(Lang.EN, Lang.FR)) {
                for (operation in listOf("save", "import", "duplicate")) {
                    val base = dir.resolve("$lang-$operation")
                    val dispatcher = StandardTestDispatcher(testScheduler)
                    val model =
                        BuildSearchModel(
                            scope = backgroundScope,
                            buildFinder = { flowOf(SolverResult(BuildCombination(emptyList(), CharacterSkills(110)), BigDecimal(100), 100, isOptimal = true)) },
                            mainDispatcher = dispatcher,
                            ioDispatcher = dispatcher,
                            backgroundDispatcher = dispatcher,
                            historyRepository = HistoryRepository(base, dispatcher),
                            libraryPreferences = LibraryPreferences(null),
                            backgroundProofCanceller = {}
                        )
                    model.setLang(lang)
                    model.search()
                    runCurrent()
                    model.saveBuild("Source", null, asNew = true)
                    runCurrent()
                    val source = model.ui.savedBuilds.single()
                    // A file where the history directory belongs reliably fails writes on any OS, even as root.
                    Files.move(base.resolve("history"), base.resolve("old-history"))
                    Files.writeString(base.resolve("history"), "blocked")
                    val expected =
                        when (operation) {
                            "save" -> {
                                model.saveBuild("Retry", null, asNew = true)
                                Tr.SAVE_BUILD_FAILED
                            }
                            "import" -> {
                                model.importBuild(historyJson.encodeToString(source))
                                Tr.IMPORT_BUILD_FAILED
                            }
                            else -> {
                                model.duplicateBuild(source.id)
                                Tr.DUPLICATE_BUILD_FAILED
                            }
                        }
                    runCurrent()
                    assertThat(model.ui.error?.message).isEqualTo(expected.value(lang)).doesNotContain(base.toString())
                    model.importBuild("not a build")
                    assertThat(model.ui.toast).isEqualTo(Tr.IMPORT_INVALID.value(lang))
                }
            }
        }
}
