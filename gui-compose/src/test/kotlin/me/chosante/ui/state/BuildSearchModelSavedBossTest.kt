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
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.domain.against
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.autobuilder.genetic.wakfu.MaxDamageSearch
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.I18nText
import me.chosante.common.Monster
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.history.HistoryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A saved max-damage build remembers the boss it was searched against: the library card can name it, and loading the build
 * brings the boss back (it used to come back as a manual scenario, so the rotation was scored against 0 % resistance and
 * disagreed with the damage saved next to it).
 */
class BuildSearchModelSavedBossTest {
    private val foundBuild = BuildCombination(equipments = emptyList(), characterSkills = CharacterSkills(110))

    private val boss =
        Monster(
            id = 4242,
            name = I18nText("Magik Riktus Dominant", "Dominant Magik Riktus", "Magik Riktus Dominante", "Magik Riktus Dominante"),
            level = 105,
            hp = 12_345,
            fireResistance = 60,
            waterResistance = 10,
            earthResistance = 30,
            airResistance = 0
        )

    private fun newModel(scope: CoroutineScope): BuildSearchModel =
        BuildSearchModel(
            scope = scope,
            buildFinder = {
                flowOf(
                    SolverResult(individual = foundBuild, matchPercentage = BigDecimal("5000"), progressPercentage = 100, isOptimal = false)
                )
            },
            optimalityProver = { _, _, _, _ -> MaxDamageSearch.MaxDamageProof.Unavailable },
            zenithBuilder = { "" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            libraryPreferences = LibraryPreferences(null),
            backgroundProofCanceller = {},
            historyRepository =
                HistoryRepository(
                    baseDir = Files.createTempDirectory("wakfu-test-history"),
                    ioDispatcher = Dispatchers.Unconfined
                )
        )

    private suspend fun awaitUntil(predicate: () -> Boolean) {
        withTimeout(25.seconds) {
            while (!predicate()) {
                delay(20.milliseconds)
            }
        }
    }

    private suspend fun BuildSearchModel.searchAndSave(name: String): String {
        setDuration("1")
        search()
        awaitUntil { ui.phase == Phase.Done }
        saveBuild(name, null, asNew = true)
        awaitUntil { ui.savedBuilds.any { it.name == name } }
        return ui.savedBuilds.first { it.name == name }.id
    }

    @Test
    fun `loading a saved boss build brings the boss back and scores the rotation against it`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope)
            try {
                model.pickBoss(boss)
                model.setBossElement(SpellElement.FIRE)
                model.setBossDifficulty("3")
                val id = model.searchAndSave("Boss damage")
                val saved = model.ui.savedBuilds.single()
                assertEquals(boss, saved.request.boss?.monster)

                // The workspace forgets the boss (as after a New build / another load), then the saved build is loaded.
                model.clearBoss()
                model.setBossDifficulty("1")
                model.loadBuild(id)

                assertEquals(boss, model.ui.selectedBoss)
                assertEquals(SpellElement.FIRE, model.ui.bossElement)
                assertEquals("3", model.ui.bossDifficulty)

                // The rotation is the one against the boss' fire resistance, not the manual scenario's 0 %.
                val character = Character(CharacterClass.CRA, model.ui.level, model.ui.minLevel, foundBuild.characterSkills)
                val againstBoss = SpellRotationOptimizer.bestSequencedRotation(foundBuild, character, character.clazz, model.ui.scenario.against(boss, SpellElement.FIRE))
                val manual = SpellRotationOptimizer.bestSequencedRotation(foundBuild, character, character.clazz, model.ui.scenario)
                assertNotEquals(manual.totalExpectedDamage, againstBoss.totalExpectedDamage, "the boss' resistance must change the damage")
                assertEquals(againstBoss.totalExpectedDamage, model.ui.spellRotation?.totalExpectedDamage)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `loading a build saved without a boss clears the boss left selected`(): Unit =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val model = newModel(scope)
            try {
                val id = model.searchAndSave("Masteries build")
                assertNull(
                    model.ui.savedBuilds
                        .single()
                        .request.boss
                )

                model.pickBoss(boss)
                model.setBossElement(SpellElement.WATER)
                model.setBossDifficulty("5")
                model.loadBuild(id)

                assertNull(model.ui.selectedBoss, "the loaded request replaces the whole workspace, boss included")
                assertNull(model.ui.bossElement)
                assertEquals("1", model.ui.bossDifficulty)
            } finally {
                scope.cancel()
            }
        }
}
