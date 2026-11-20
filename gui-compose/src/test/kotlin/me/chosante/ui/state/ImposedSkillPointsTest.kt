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
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillBranch
import me.chosante.common.skills.SkillLine
import me.chosante.ui.history.HistoryRepository
import me.chosante.ui.history.toRequestSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The request panel's imposed skill points (issue #284): limits, level changes, the engine request, save and reload. */
class ImposedSkillPointsTest {
    private val agility230 = CharacterSkills(230).agility.maxPointsToAssign

    @Test
    fun `a line is clamped to its own cap and to what its branch leaves`() {
        val p =
            ImposedSkillPoints()
                .withLine(SkillLine.LUCK_BLOCK, 99, 230)
                .withLine(SkillLine.AGILITY_DODGE, 50, 230)
                .withLine(SkillLine.AGILITY_LOCK, 50, 230)
                .withLine(SkillLine.MAJOR_ACTION_POINT, 3, 230)
        assertEquals(20, p[SkillLine.LUCK_BLOCK])
        assertEquals(50, p[SkillLine.AGILITY_DODGE])
        assertEquals(agility230 - 50, p[SkillLine.AGILITY_LOCK])
        assertEquals(1, p[SkillLine.MAJOR_ACTION_POINT])
        assertEquals(agility230, p.branchTotal(SkillBranch.AGILITY))
        assertEquals(0, p.maxFor(SkillLine.AGILITY_INITIATIVE, 230))
        // 0 removes the line: the request never carries a 0 entry.
        assertEquals(setOf(SkillLine.LUCK_BLOCK, SkillLine.AGILITY_DODGE, SkillLine.MAJOR_ACTION_POINT), p.withLine(SkillLine.AGILITY_LOCK, 0, 230).points.keys)
    }

    @Test
    fun `a lower level brings every branch back within its pool`() {
        val high =
            ImposedSkillPoints()
                .withLine(SkillLine.AGILITY_DODGE, 40, 230)
                .withLine(SkillLine.AGILITY_LOCK, 17, 230)
                .withLine(SkillLine.MAJOR_ACTION_POINT, 1, 230)
        val low = high.fittedTo(20)
        val skills20 = CharacterSkills(20)
        assertEquals(skills20.agility.maxPointsToAssign, low.branchTotal(SkillBranch.AGILITY))
        assertEquals(skills20.agility.maxPointsToAssign, low[SkillLine.AGILITY_DODGE]) // the first line keeps what fits
        assertEquals(0, low[SkillLine.AGILITY_LOCK])
        assertEquals(0, low[SkillLine.MAJOR_ACTION_POINT]) // no major point before level 25
        assertTrue(high.fittedTo(230) === high)
        // Every fitted request passes the engine's validation.
        for (level in listOf(1, 20, 25, 110, 230)) {
            val fitted = high.fittedTo(level)
            val problems =
                WakfuBestBuildFinderAlgorithm.validateRequest(
                    UiState(level = level, imposedSkillPoints = fitted).let { paramsOf(it) },
                    allEquipments = emptyList(),
                    allSublimations = emptyList()
                )
            assertEquals(emptyList<Any>(), problems, "level $level")
        }
    }

    @Test
    fun `the stored form round-trips and drops what it cannot read`() {
        val p = ImposedSkillPoints().withLine(SkillLine.AGILITY_DODGE, 57, 230).withLine(SkillLine.LUCK_CRITICAL_HIT, 20, 230)
        assertEquals(p, ImposedSkillPoints.fromSnapshot(p.toSnapshot(), 230))
        assertEquals(p, ImposedSkillPoints.fromSnapshot(p.toSnapshot() + ("NOT_A_LINE" to 3), 230))
        assertEquals(mapOf(SkillLine.AGILITY_DODGE to 57, SkillLine.LUCK_CRITICAL_HIT to 20), p.forEngine())
    }

    private fun paramsOf(state: UiState): WakfuBestBuildParams =
        WakfuBestBuildParams(
            character = me.chosante.common.Character(state.clazz, state.level, state.minLevel),
            targetStats =
                me.chosante.autobuilder.domain
                    .TargetStats(emptyList()),
            searchDuration = 1.seconds,
            stopWhenBuildMatch = false,
            maxRarity = me.chosante.common.Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = state.mode,
            forcedSkillPoints = state.imposedSkillPoints.forEngine()
        )

    @Test
    fun `the search receives the imposed points, a save keeps them and a reload brings them back`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var received: WakfuBestBuildParams? = null
            val model =
                BuildSearchModel(
                    scope = scope,
                    buildFinder = { params ->
                        received = params
                        flowOf(
                            SolverResult(
                                individual = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(params.character.level)),
                                matchPercentage = BigDecimal("100"),
                                progressPercentage = 100
                            )
                        )
                    },
                    optimalityProver = { _, _, _, _ -> MaxDamageSearch.MaxDamageProof.Unavailable },
                    zenithBuilder = { "" },
                    mainDispatcher = Dispatchers.Unconfined,
                    ioDispatcher = Dispatchers.Unconfined,
                    libraryPreferences = LibraryPreferences(null),
                    backgroundProofCanceller = {},
                    historyRepository = HistoryRepository(baseDir = Files.createTempDirectory("wakfu-test-history"), ioDispatcher = Dispatchers.Unconfined)
                )
            try {
                model.setLevel("230")
                model.setImposedSkillPoints(SkillLine.AGILITY_DODGE, 57)
                model.setImposedSkillPoints(SkillLine.LUCK_BLOCK, 500)
                assertEquals(20, model.ui.imposedSkillPoints[SkillLine.LUCK_BLOCK])
                model.setDuration("1")
                model.search()
                withTimeout(25.seconds) { while (model.ui.phase != Phase.Done) delay(20.milliseconds) }
                val expected = mapOf(SkillLine.AGILITY_DODGE to 57, SkillLine.LUCK_BLOCK to 20)
                assertEquals(expected, received?.forcedSkillPoints)

                model.setImposedSkillPoints(SkillLine.LUCK_BLOCK, 0)

                model.saveBuild("Dodge build", null, asNew = true)
                withTimeout(25.seconds) { while (model.ui.savedBuilds.none { it.name == "Dodge build" }) delay(20.milliseconds) }
                val saved = model.ui.savedBuilds.single()
                assertEquals(mapOf("AGILITY_DODGE" to 57), saved.request.forcedSkillPoints)

                model.resetImposedSkillPoints()
                assertTrue(model.ui.imposedSkillPoints.isEmpty)
                model.loadBuild(saved.id)
                assertEquals(mapOf(SkillLine.AGILITY_DODGE to 57), model.ui.imposedSkillPoints.points)

                // The remembered workspace restores them too.
                assertEquals(model.ui.imposedSkillPoints, UiState().withRememberedRequest(model.ui.toRequestSnapshot()).imposedSkillPoints)

                // An old save (no imposed points stored) loads with none.
                val old = saved.copy(request = saved.request.copy(forcedSkillPoints = emptyMap()))
                assertTrue(UiState().withRememberedRequest(old.request).imposedSkillPoints.isEmpty)

                // A lower level brings them back within the new pools.
                model.setLevel("20")
                assertEquals(CharacterSkills(20).agility.maxPointsToAssign, model.ui.imposedSkillPoints[SkillLine.AGILITY_DODGE])
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `the skill tree padlocks impose a line at the shown value, and the whole allocation`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val shown =
                CharacterSkills(230).apply {
                    agility.dodge.setPointAssigned(57)
                    luck.criticalHit.setPointAssigned(20)
                    major.actionPoint.setPointAssigned(1)
                }
            val model =
                BuildSearchModel(
                    scope = scope,
                    buildFinder = {
                        flowOf(
                            SolverResult(
                                individual = BuildCombination(equipments = emptyList(), characterSkills = shown),
                                matchPercentage = BigDecimal("100"),
                                progressPercentage = 100
                            )
                        )
                    },
                    optimalityProver = { _, _, _, _ -> MaxDamageSearch.MaxDamageProof.Unavailable },
                    zenithBuilder = { "" },
                    mainDispatcher = Dispatchers.Unconfined,
                    ioDispatcher = Dispatchers.Unconfined,
                    libraryPreferences = LibraryPreferences(null),
                    backgroundProofCanceller = {},
                    historyRepository = HistoryRepository(baseDir = Files.createTempDirectory("wakfu-test-history"), ioDispatcher = Dispatchers.Unconfined)
                )
            try {
                model.setLevel("230")
                model.setDuration("1")
                model.search()
                withTimeout(25.seconds) { while (model.ui.phase != Phase.Done) delay(20.milliseconds) }

                model.toggleSkillLock(SkillLine.AGILITY_DODGE)
                assertEquals(mapOf(SkillLine.AGILITY_DODGE to 57), model.ui.imposedSkillPoints.points)
                model.toggleSkillLock(SkillLine.LUCK_BLOCK) // nothing spent there: nothing to keep
                assertEquals(mapOf(SkillLine.AGILITY_DODGE to 57), model.ui.imposedSkillPoints.points)
                model.toggleSkillLock(SkillLine.AGILITY_DODGE)
                assertTrue(model.ui.imposedSkillPoints.isEmpty)

                model.toggleImposeShownAllocation()
                assertEquals(mapOf(SkillLine.AGILITY_DODGE to 57, SkillLine.LUCK_CRITICAL_HIT to 20, SkillLine.MAJOR_ACTION_POINT to 1), model.ui.imposedSkillPoints.points)
                model.toggleImposeShownAllocation()
                assertTrue(model.ui.imposedSkillPoints.isEmpty, "a second click frees the whole allocation")
            } finally {
                scope.cancel()
            }
        }
}
