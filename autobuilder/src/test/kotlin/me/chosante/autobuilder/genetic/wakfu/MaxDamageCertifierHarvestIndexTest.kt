package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.Sublimation
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.time.Duration.Companion.seconds

class MaxDamageCertifierHarvestIndexTest {
    @Test
    fun `indexed AP and crit coordinates are byte-identical to the reference scans`() {
        val random = Random(0x48_41_52_56)
        repeat(5_000) { iteration ->
            val cellCount = random.nextInt(1, 22)
            val apConst = random.nextInt(-3, 12).toLong()
            val stateAp = random.nextInt(-12, 25)
            val apPlus = randomBudget(random)
            val apMinus = randomBudget(random)
            val minSubAp = -apMinus.sum().toInt()
            val maxSubAp = apPlus.sum().toInt()
            val expectedAp =
                referenceApCoordinates(
                    stateAp,
                    cellCount,
                    apConst,
                    minSubAp,
                    maxSubAp,
                    apPlus,
                    apMinus
                )
            val indexedAp =
                indexedFastHarvestApCoordinates(
                    stateAp,
                    cellCount,
                    apConst,
                    minSubAp,
                    maxSubAp,
                    apPlus,
                    apMinus
                )
            assertContentEquals(expectedAp, indexedAp, "AP coordinate mismatch at iteration $iteration")

            val cLow = random.nextInt(0, 105)
            val cHigh = random.nextInt(cLow, 121)
            val critConst = random.nextInt(0, 16).toLong()
            val critOff = random.nextInt(0, 21)
            val stateCrit = random.nextInt(-critOff, 131)
            val critBudget = randomBudget(random, maxValue = 30)
            val forcedStartCrit = random.nextInt(0, 16).toLong()
            val maxSubCrit = critBudget.sum() + forcedStartCrit
            val csWorldCrit = random.nextInt(0, 21).toLong()
            val expectedCrit =
                referenceCritCoordinates(
                    stateCrit,
                    cLow,
                    cHigh,
                    critConst,
                    critOff,
                    maxSubCrit,
                    csWorldCrit,
                    forcedStartCrit,
                    critBudget
                )
            val indexedCrit =
                indexedFastHarvestCritCoordinates(
                    stateCrit,
                    cLow,
                    cHigh,
                    critConst,
                    critOff,
                    maxSubCrit,
                    csWorldCrit,
                    forcedStartCrit,
                    critBudget
                )
            assertContentEquals(expectedCrit, indexedCrit, "crit coordinate mismatch at iteration $iteration")
        }
    }

    @Test
    fun `indexed FAST harvest produces a byte-identical ledger`() {
        val level = 50
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(emptyList()),
                searchDuration = 2.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                useRunes = false,
                useSublimations = true,
                damageScenario =
                    DamageScenario(
                        element = SpellElement.FIRE,
                        rangeBand = RangeBand.DISTANCE,
                        orientation = Orientation.FACE
                    )
            )
        val pool =
            listOf(
                equipment(1, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 400, Characteristic.CRITICAL_HIT to -8), 4),
                equipment(2, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300, Characteristic.CRITICAL_HIT to 12), 4),
                equipment(3, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 250, Characteristic.ACTION_POINT to 2), 4),
                equipment(4, ItemType.AMULET, mapOf(Characteristic.DAMAGE_INFLICTED to 35, Characteristic.ACTION_POINT to -1), 4),
                equipment(5, ItemType.BELT, mapOf(Characteristic.MASTERY_DISTANCE to 300, Characteristic.CRITICAL_HIT to 18), 4),
                equipment(6, ItemType.RING, mapOf(Characteristic.MASTERY_CRITICAL to 220), 4),
                equipment(7, ItemType.RING, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 180), 4)
            ).groupBy { it.itemType }
        val sublimations =
            listOf(
                flatSub(10_001, Characteristic.ACTION_POINT, 1),
                flatSub(10_002, Characteristic.ACTION_POINT, -1),
                flatSub(10_003, Characteristic.CRITICAL_HIT, 15, permanent = true),
                flatSub(10_004, Characteristic.CRITICAL_HIT, 9),
                flatSub(10_005, Characteristic.DAMAGE_INFLICTED, 20)
            )

        val previous = CertifierTuning.indexedFastHarvestEnabled
        CertifierTuning.indexedFastHarvestCoordinatesForTest.set(0L)
        try {
            CertifierTuning.indexedFastHarvestEnabled = false
            val reference =
                WakfuBuildSolver.certifierFastLedgerForTest(
                    params,
                    pool,
                    sublimations = sublimations,
                    applyDomination = false,
                    threads = 1
                )
            CertifierTuning.indexedFastHarvestEnabled = true
            val indexed =
                WakfuBuildSolver.certifierFastLedgerForTest(
                    params,
                    pool,
                    sublimations = sublimations,
                    applyDomination = false,
                    threads = 1
                )

            assertThat(indexed)
                .describedAs("the indexed harvest must leave every FAST certificate cell byte-identical")
                .isEqualTo(reference)
            assertThat(CertifierTuning.indexedFastHarvestCoordinatesForTest.get())
                .describedAs("the optimized seam must actually visit indexed harvest coordinates")
                .isGreaterThan(0L)
        } finally {
            CertifierTuning.indexedFastHarvestEnabled = previous
        }
    }

    private fun referenceApCoordinates(
        stateAp: Int,
        cellCount: Int,
        apConst: Long,
        minSubAp: Int,
        maxSubAp: Int,
        apPlus: List<Long>,
        apMinus: List<Long>,
    ): IntArray =
        buildList {
            for (cell in 0 until cellCount) {
                val apHigh = cell - apConst
                if (stateAp < apHigh - maxSubAp || stateAp > apHigh - minSubAp) continue
                val gap = apHigh - stateAp
                val charge = if (gap >= 0L) referenceCover(gap, apPlus) else referenceCover(-gap, apMinus)
                if (charge == Int.MAX_VALUE) continue
                add(cell)
                add(charge)
            }
        }.toIntArray()

    private fun referenceCritCoordinates(
        stateCrit: Int,
        cLow: Int,
        cHigh: Int,
        critConst: Long,
        critOff: Int,
        maxSubCrit: Long,
        csWorldCrit: Long,
        forcedStartCrit: Long,
        critBudget: List<Long>,
    ): IntArray =
        buildList {
            for (c in cLow..cHigh) {
                val critHigh = (c - critConst).toInt()
                val critLow = if (c == 0) -critOff else critHigh - maxSubCrit.toInt()
                if (stateCrit < critLow || stateCrit > critHigh) continue
                val charge =
                    if (c == 0) {
                        0
                    } else {
                        referenceCover(c - critConst - stateCrit - csWorldCrit - forcedStartCrit, critBudget)
                    }
                if (charge == Int.MAX_VALUE) continue
                add(c)
                add(charge)
            }
        }.toIntArray()

    private fun referenceCover(
        gap: Long,
        sortedDesc: List<Long>,
    ): Int {
        if (gap <= 0L) return 0
        var covered = 0L
        for ((index, value) in sortedDesc.withIndex()) {
            covered += value
            if (covered >= gap) return index + 1
        }
        return Int.MAX_VALUE
    }

    private fun randomBudget(
        random: Random,
        maxValue: Int = 4,
    ): List<Long> =
        List(random.nextInt(0, 6)) { random.nextInt(1, maxValue + 1).toLong() }
            .sortedDescending()

    private fun equipment(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
        maxShardSlots: Int,
    ): Equipment =
        Equipment(
            equipmentId = id,
            guiId = id,
            level = 50,
            name = I18nText("item$id", "item$id", "item$id", "item$id"),
            rarity = Rarity.COMMON,
            itemType = type,
            characteristics = stats,
            maxShardSlots = maxShardSlots
        )

    private fun flatSub(
        id: Int,
        characteristic: Characteristic,
        value: Int,
        permanent: Boolean = false,
    ): Sublimation =
        Sublimation(
            stateId = id,
            name = I18nText("sub$id", "sub$id", "sub$id", "sub$id"),
            rarity = SublimationRarity.NORMAL,
            slotColorPattern = listOf(1, 2, 3),
            kind = SublimationKind.FLAT,
            solverChoosable = true,
            effects = listOf(SublimationEffect.Flat(characteristic, value, appliesBeforeCombat = permanent))
        )
}
