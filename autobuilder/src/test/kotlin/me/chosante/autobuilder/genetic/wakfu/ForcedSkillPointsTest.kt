package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillBranch
import me.chosante.common.skills.SkillLine
import me.chosante.common.skills.withLinePoints
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Player-imposed skill points (issue #284): `WakfuBestBuildParams.forcedSkillPoints` is an EXACT value per skill line. The
 * issue's case — "all my agility points in dodge, nothing in block" — is locked in every mode: the player fills the Agility
 * branch with dodge and the Luck branch with critical hit + critical mastery, and the result keeps exactly that. And without
 * imposed points, the points that bring nothing to the request are left unplaced ([UnplacedSkillPoints]): no more block.
 */
class ForcedSkillPointsTest {
    private val level = 230
    private val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, greedyWarmStart = false)

    private fun item(
        id: Int,
        slot: ItemType,
        stats: Map<Characteristic, Int>,
    ) = Equipment(
        equipmentId = id,
        guiId = 1,
        level = level,
        name = I18nText("item$id", "item$id", "item$id", "item$id"),
        rarity = Rarity.LEGENDARY,
        itemType = slot,
        characteristics = stats,
        maxShardSlots = 0
    )

    // A dodge item and a mastery item per slot: the dodge row can be met by gear, by skill points, or both.
    private val pool: Map<ItemType, List<Equipment>> =
        listOf(ItemType.AMULET, ItemType.BELT, ItemType.BOOTS, ItemType.HELMET)
            .withIndex()
            .associate { (i, slot) ->
                slot to
                    listOf(
                        item(100 + i, slot, mapOf(Characteristic.DODGE to 120, Characteristic.MASTERY_DISTANCE to 40)),
                        item(200 + i, slot, mapOf(Characteristic.MASTERY_DISTANCE to 150, Characteristic.MASTERY_ELEMENTARY to 100))
                    )
            }

    private val skills = CharacterSkills(level)
    private val agilityPool = skills.agility.maxPointsToAssign
    private val luckPool = skills.luck.maxPointsToAssign

    /** The issue's allocation: every agility point in dodge, luck in critical hit + critical mastery (so none in block). */
    private val issueAllocation =
        mapOf(
            SkillLine.AGILITY_DODGE to agilityPool,
            SkillLine.LUCK_CRITICAL_HIT to 20,
            SkillLine.LUCK_MASTERY_CRITICAL to luckPool - 20
        )

    private fun params(
        mode: ScoreComputationMode,
        forced: Map<SkillLine, Int> = issueAllocation,
    ): WakfuBestBuildParams {
        val targets =
            when (mode) {
                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                    listOf(TargetStat(Characteristic.DODGE, 300), TargetStat(Characteristic.MASTERY_DISTANCE, 500))
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                    listOf(TargetStat(Characteristic.DODGE, 300), TargetStat(Characteristic.MASTERY_DISTANCE, 1))
                ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE ->
                    listOf(TargetStat(Characteristic.DODGE, 300))
            }
        return WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, level, 200),
            targetStats = TargetStats(targets),
            searchDuration = 10.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = mode,
            useRunes = false,
            useSublimations = false,
            forcedSkillPoints = forced
        )
    }

    private fun solve(p: WakfuBestBuildParams): BuildCombination =
        runBlocking {
            WakfuBuildSolver
                .optimize(p, pool, tuning = tuning)
                .toList()
                .last()
                .individual
        }

    private fun assertIssueAllocationKept(build: BuildCombination) {
        val s = build.characterSkills
        assertThat(s.agility.dodge.pointsAssigned).isEqualTo(agilityPool)
        assertThat(s.agility.pointsAssigned()).isEqualTo(agilityPool)
        assertThat(s.luck.block.pointsAssigned).describedAs("no point in block").isZero()
        assertThat(s.luck.criticalHit.pointsAssigned).isEqualTo(20)
        assertThat(s.luck.masteryCritical.pointsAssigned).isEqualTo(luckPool - 20)
    }

    @Test
    fun `every skill line is identified exactly once and maps to its own branch`() {
        for (lvl in listOf(1, 25, 75, 125, 175, 230, 245)) {
            val s = CharacterSkills(lvl)
            val lines = SkillLine.entries.map { it.of(s) }
            val distinct = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { addAll(lines) }
            assertThat(distinct).hasSize(SkillLine.entries.size)
            assertThat(lines).hasSameSizeAs(s.allCharacteristic)
            assertThat(s.allCharacteristic.all { it in distinct }).isTrue()
            for (line in SkillLine.entries) {
                assertThat(
                    line.branch
                        .of(s)
                        .getCharacteristics()
                        .any { it === line.of(s) }
                ).describedAs(line.name).isTrue()
            }
        }
        assertThat(SkillLine.parse("agility-dodge")).isEqualTo(SkillLine.AGILITY_DODGE)
        assertThat(SkillLine.parse("LUCK_CRITICAL_HIT")).isEqualTo(SkillLine.LUCK_CRITICAL_HIT)
        assertThat(SkillLine.parse("luck.block")).isEqualTo(SkillLine.LUCK_BLOCK)
        assertThat(SkillLine.parse("dodge")).isNull()
    }

    @Test
    fun `the issue allocation is kept by the solver in every mode`() {
        for (mode in ScoreComputationMode.entries) {
            val p = params(mode)
            assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(p, allEquipments = pool.values.flatten(), allSublimations = emptyList())).isEmpty()
            val build = solve(p)
            assertIssueAllocationKept(build)
        }
    }

    @Test
    fun `an imposed value is exact, 0 included, and the rest of the branch stays free`() {
        // Most-masteries' overshoot tie-break would put every agility point in dodge: imposed at 10, dodge stays at 10 and the
        // other agility lines take the rest; block imposed at 3 stays 3; dodge-and-lock imposed at 0 stays empty.
        for (mode in ScoreComputationMode.entries) {
            val forced = mapOf(SkillLine.AGILITY_DODGE to 10, SkillLine.LUCK_BLOCK to 3, SkillLine.AGILITY_DODGE_AND_LOCK to 0)
            val s = solve(params(mode, forced)).characterSkills
            assertThat(s.agility.dodge.pointsAssigned).describedAs("$mode").isEqualTo(10)
            assertThat(s.luck.block.pointsAssigned).describedAs("$mode").isEqualTo(3)
            assertThat(s.agility.dodgeAndLock.pointsAssigned).describedAs("$mode").isZero()
            assertThat(s.agility.pointsAssigned()).isLessThanOrEqualTo(agilityPool)
        }
    }

    @Test
    fun `nothing is placed in a branch the request does not read, in every mode, with the same score`() {
        for (mode in ScoreComputationMode.entries) {
            val p = params(mode, emptyMap())
            val result = runBlocking { WakfuBuildSolver.optimize(p, pool, tuning = tuning).toList().last() }
            val trimmed = UnplacedSkillPoints.trim(p, result.individual)
            val s = trimmed.characterSkills
            assertThat(s.luck.block.pointsAssigned).describedAs("$mode: no point in block").isZero()
            assertThat(s.intelligence.pointsAssigned()).describedAs("$mode: nothing in intelligence").isZero()
            if (mode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                // Max-damage reads critical hit / critical mastery from the Luck branch; the two other modes read nothing there.
                assertThat(s.luck.pointsAssigned()).describedAs("$mode: nothing in luck").isZero()
            }
            // The requested dodge keeps its points (a target row's line is never trimmed).
            assertThat(s.agility.dodge.pointsAssigned + s.agility.dodgeAndLock.pointsAssigned)
                .isEqualTo(result.individual.characterSkills.agility.dodge.pointsAssigned + result.individual.characterSkills.agility.dodgeAndLock.pointsAssigned)
            assertThat(
                WakfuBestBuildFinderAlgorithm.rescore(p, trimmed)
            ).describedAs("$mode: same score").isEqualByComparingTo(WakfuBestBuildFinderAlgorithm.rescore(p, result.individual))
            assertThat(trimmed.isValid(CharacterClass.CRA)).isTrue()
            assertThat(trimmed.equipments).isEqualTo(result.individual.equipments)
        }
    }

    @Test
    fun `the pass never touches an imposed line, even a useless one`() {
        for (mode in ScoreComputationMode.entries) {
            val p = params(mode, mapOf(SkillLine.LUCK_BLOCK to 7, SkillLine.INTELLIGENCE_SHIELD to 4))
            val trimmed = UnplacedSkillPoints.trim(p, solve(p))
            assertThat(trimmed.characterSkills.luck.block.pointsAssigned).describedAs("$mode").isEqualTo(7)
            assertThat(trimmed.characterSkills.intelligence.shield.pointsAssigned).describedAs("$mode").isEqualTo(4)
        }
    }

    @Test
    fun `the pass keeps the points that change the score`() {
        // Max-damage reads critical mastery: whatever the solver put on a scoring line comes back, only useless lines are emptied.
        val p = params(ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE, emptyMap())
        val build = solve(p)
        val withBlock = build.copy(characterSkills = build.characterSkills.withLinePoints(mapOf(SkillLine.LUCK_BLOCK to 0)))
        val trimmed = UnplacedSkillPoints.trim(p, withBlock)
        for (line in SkillLine.entries) {
            val before = line.of(withBlock.characterSkills).pointsAssigned
            val after = line.of(trimmed.characterSkills).pointsAssigned
            assertThat(after).describedAs(line.name).isLessThanOrEqualTo(before)
            if (after < before) {
                // A line the pass lowered must not change the score on its own either.
                val only = withBlock.copy(characterSkills = withBlock.characterSkills.withLinePoints(mapOf(line to after)))
                assertThat(WakfuBestBuildFinderAlgorithm.rescore(p, only)).describedAs(line.name).isEqualByComparingTo(WakfuBestBuildFinderAlgorithm.rescore(p, withBlock))
            }
        }
        assertThat(WakfuBestBuildFinderAlgorithm.rescore(p, trimmed)).isEqualByComparingTo(WakfuBestBuildFinderAlgorithm.rescore(p, build))
        assertThat(trimmed.characterSkills.luck.masteryCritical.pointsAssigned + trimmed.characterSkills.luck.criticalHit.pointsAssigned)
            .describedAs("max-damage keeps its critical lines")
            .isEqualTo(build.characterSkills.luck.masteryCritical.pointsAssigned + build.characterSkills.luck.criticalHit.pointsAssigned)
    }

    @Test
    fun `the issue request on the real data puts nothing in block`() {
        // Level 230 Cra, most-masteries, distance + fire masteries, AP / MP / range and dodge 300 (the issue's reproduction, which
        // returned 20 points in block before the pass).
        val p =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 200),
                targetStats =
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.ACTION_POINT, 12),
                            TargetStat(Characteristic.MOVEMENT_POINT, 5),
                            TargetStat(Characteristic.RANGE, 2),
                            TargetStat(Characteristic.MASTERY_DISTANCE, 1),
                            TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1),
                            TargetStat(Characteristic.DODGE, 300)
                        )
                    ),
                searchDuration = 15.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
            )
        val build = runBlocking { WakfuBestBuildFinderAlgorithm.run(p).toList().last() }.individual
        assertThat(build.characterSkills.luck.block.pointsAssigned).isZero()
        // Every point left in a line no target row reads is there for the score: emptying the line lowers it (berserk mastery
        // can stay, for instance, when Ravage converts it into elemental mastery).
        val score = WakfuBestBuildFinderAlgorithm.rescore(p, build)
        for (line in SkillLine.entries.filter { it.branch == me.chosante.common.skills.SkillBranch.LUCK || it.branch == me.chosante.common.skills.SkillBranch.INTELLIGENCE }) {
            if (line.of(build.characterSkills).pointsAssigned == 0) continue
            val emptied = build.copy(characterSkills = build.characterSkills.withLinePoints(mapOf(line to 0)))
            assertThat(WakfuBestBuildFinderAlgorithm.rescore(p, emptied)).describedAs(line.name).isLessThan(score)
        }
    }

    @Test
    fun `imposed points never raise the score - the free optimum is an upper bound`() {
        for (mode in ScoreComputationMode.entries) {
            val free = runBlocking { WakfuBuildSolver.optimize(params(mode, emptyMap()), pool, tuning = tuning).toList().last() }
            val forced = runBlocking { WakfuBuildSolver.optimize(params(mode), pool, tuning = tuning).toList().last() }
            assertThat(free.isOptimal).describedAs("$mode free optimum proven").isTrue()
            val forcedScore = WakfuBestBuildFinderAlgorithm.rescore(params(mode), forced.individual)
            val freeScore = WakfuBestBuildFinderAlgorithm.rescore(params(mode), free.individual)
            assertThat(forcedScore).describedAs("$mode").isLessThanOrEqualTo(freeScore)
        }
    }

    @Test
    fun `the greedy warm start honours the imposed points`() {
        for (mode in listOf(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)) {
            val p = params(mode)
            val greedy =
                if (mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                    MostMasteriesWarmStart.greedyMaxDamageBuild(p, pool.values.flatten())
                } else {
                    MostMasteriesWarmStart.greedyBuild(p, pool.values.flatten())
                }
            assertIssueAllocationKept(requireNotNull(greedy) { "$mode greedy" })
        }
        // A partial request: the imposed line is exact, the branch's other points still follow the requested stats.
        val partial = params(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, mapOf(SkillLine.LUCK_BLOCK to 5, SkillLine.AGILITY_DODGE to 20))
        val s = requireNotNull(MostMasteriesWarmStart.greedyBuild(partial, pool.values.flatten())).characterSkills
        assertThat(s.luck.block.pointsAssigned).isEqualTo(5)
        assertThat(s.luck.pointsAssigned()).isEqualTo(5) // nothing requested in luck: only the imposed line
        assertThat(s.agility.dodge.pointsAssigned).isEqualTo(20)
        assertThat(s.agility.pointsAssigned()).isEqualTo(agilityPool) // dodge-and-lock, also requested, takes the rest
    }

    @Test
    fun `validation rejects a line over its cap and a branch over its pool`() {
        fun problems(forced: Map<SkillLine, Int>) =
            WakfuBestBuildFinderAlgorithm.validateRequest(
                params(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, forced),
                allEquipments = pool.values.flatten(),
                allSublimations = emptyList()
            )

        assertThat(problems(mapOf(SkillLine.MAJOR_ACTION_POINT to 2)))
            .containsExactly(RequestValidationProblem.ForcedSkillPointsOutOfRange(SkillLine.MAJOR_ACTION_POINT, 2, 1))
        assertThat(problems(mapOf(SkillLine.LUCK_BLOCK to 21)))
            .containsExactly(RequestValidationProblem.ForcedSkillPointsOutOfRange(SkillLine.LUCK_BLOCK, 21, 20))
        assertThat(problems(mapOf(SkillLine.AGILITY_DODGE to -1)))
            .containsExactly(RequestValidationProblem.ForcedSkillPointsOutOfRange(SkillLine.AGILITY_DODGE, -1, agilityPool))
        assertThat(problems(mapOf(SkillLine.AGILITY_DODGE to 40, SkillLine.AGILITY_LOCK to 40)))
            .containsExactly(RequestValidationProblem.ForcedSkillPointsExceedBranch(SkillBranch.AGILITY, 80, agilityPool))
        // Major points unlock with the level: none at level 20.
        val low = params(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, mapOf(SkillLine.MAJOR_ACTION_POINT to 1))
        assertThat(
            WakfuBestBuildFinderAlgorithm.validateRequest(
                low.copy(character = Character(CharacterClass.CRA, 20, 1)),
                allEquipments = pool.values.flatten(),
                allSublimations = emptyList()
            )
        ).contains(RequestValidationProblem.ForcedSkillPointsOutOfRange(SkillLine.MAJOR_ACTION_POINT, 1, 0))
        assertThat(problems(issueAllocation)).isEmpty()
    }

    @Test
    fun `run refuses an invalid allocation before searching`() {
        val invalid = params(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, mapOf(SkillLine.LUCK_BLOCK to 99))
        val error = assertThrows<InvalidRequestException> { WakfuBestBuildFinderAlgorithm.run(invalid) }
        assertThat(error.problems).containsExactly(RequestValidationProblem.ForcedSkillPointsOutOfRange(SkillLine.LUCK_BLOCK, 99, 20))
        assertThat(error.message).contains("luck-block")
    }
}
