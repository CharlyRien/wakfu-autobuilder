package me.chosante.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SublimationStackLevelTest {
    private fun sub(
        tiers: Set<Int> = setOf(1, 2, 3),
        cap: Int = 4,
    ) = Sublimation(
        6931,
        name = I18nText("Neutralité III", "Neutrality III", "", ""),
        rarity = SublimationRarity.NORMAL,
        maxStackLevel = cap,
        maxTier = tiers.max(),
        cumulable = true,
        kind = SublimationKind.STATIC_CONDITIONAL,
        condition = SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, 0),
        shardsByTier = tiers.associateWith { SublimationShard(29000 + it, I18nText("Tier $it", "Tier $it", "", "")) },
        effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 24, valuesByLevel = (1..cap).map { it * 8 }))
    )

    @Test
    fun `conditional partial families use the cap marginal and correct socket identities`() {
        val sub = sub()
        val effect = sub.effects.single() as SublimationEffect.StatEffect
        assertEquals(2, sub.maxCopies)
        assertEquals(listOf(24, 8), (1..2).map { sub.marginalMagnitude(effect, it, 65) })
        val shards = sub.socketedShards(2, 65)
        assertEquals(listOf(29003, 29001), shards.map { it.zenithId })
        assertEquals(listOf(4, 4), shards.map { it.stackLevel })
        assertEquals(32, shards.sumOf { (it.effects.single() as SublimationEffect.Flat).value })
        assertEquals(listOf(sub.condition, sub.condition), shards.map { it.condition })
    }

    @Test
    fun `synthetic partial families resolve the same total as the solver`() {
        val sub = sub().copy(shardsByTier = emptyMap())
        assertEquals(32, sub.socketedShards(2, 65).sumOf { (it.effects.single() as SublimationEffect.Flat).value })
    }

    @Test
    fun `reachable sums use the fewest actual shards without overshoot`() {
        val onlyTwo = sub(setOf(2), 6)
        assertEquals(listOf(2, 4, 6), onlyTwo.reachableLevels())
        assertEquals(listOf(2, 2), onlyTwo.shardPlan(4))
        assertEquals(listOf(2, 2), sub(setOf(2, 3)).shardPlan(4))
        assertEquals(listOf(3, 1), sub().shardPlan(4))
        val onlyThree = sub(setOf(3))
        assertEquals(listOf(3), onlyThree.reachableLevels())
        assertEquals(listOf(3), onlyThree.automaticStackLevels)
        assertEquals(1, onlyThree.maxCopies)
        assertThrows(IllegalArgumentException::class.java) { onlyThree.shardPlan(4) }
        assertEquals(listOf(3), onlyThree.socketedShards(1, 65).map { it.socketTier })
        val twoOrThree = sub(setOf(2, 3))
        assertEquals(listOf(2, 3, 4), twoOrThree.reachableLevels())
        assertEquals(listOf(3, 4), twoOrThree.automaticStackLevels)
        assertEquals(listOf(2, 2), twoOrThree.socketedShards(2, 65).map { it.socketTier })
        assertEquals(listOf(24, 8), twoOrThree.certificateUnits(65).map { (it.effects.single() as SublimationEffect.Flat).value })
        assertEquals(32, twoOrThree.socketedShards(2, 65).sumOf { (it.effects.single() as SublimationEffect.Flat).value })
        val forcedFive = sub(setOf(1, 3), 5)
        assertEquals(listOf(3, 1, 1), forcedFive.shardPlan(5))
        val effect = forcedFive.effects.single() as SublimationEffect.StatEffect
        assertEquals(listOf(24, 8, 8), (1..3).map { forcedFive.marginalMagnitude(effect, it, 65, 5) })
        assertEquals(2, sub().atTierLimit(2)!!.maxCopies)
        assertEquals(4, sub().atTierLimit(1)!!.maxCopies)
    }

    @Test
    fun `round the whole percent family before differencing and retain nonzero bases`() {
        val family = sub(cap = 6).copy(effects = listOf(SublimationEffect.PercentOfLevel(Characteristic.MASTERY_ELEMENTARY, 45, valuesByLevel = listOf(15, 30, 45, 60, 75, 90))))
        val effect = family.effects.single() as SublimationEffect.StatEffect
        assertEquals(1, (1..2).sumOf { family.marginalMagnitude(effect, it, 2) })
        val base = sub().copy(effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 11, valuesByLevel = listOf(7, 9, 11, 13))))
        assertEquals(listOf(11, 2), base.certificateUnits(65).map { (it.effects.single() as SublimationEffect.Flat).value })
    }
}
