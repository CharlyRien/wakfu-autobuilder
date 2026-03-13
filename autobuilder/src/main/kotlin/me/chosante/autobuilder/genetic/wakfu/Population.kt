package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.assignRandomPoints
import kotlin.random.Random

internal fun generateRandomPopulations(
    numberOfIndividual: Int = 10000,
    equipmentsByItemType: Map<ItemType, List<Equipment>>,
    character: Character,
    targetStats: TargetStats,
    random: Random = Random.Default,
): Collection<BuildCombination> =
    generateSequence {
        getRandomCombination(character, equipmentsByItemType, targetStats, random)
    }.take(numberOfIndividual)
        .toList()

private fun getRandomCombination(
    character: Character,
    equipmentsByItemType: Map<ItemType, List<Equipment>>,
    targetStats: TargetStats,
    random: Random,
): BuildCombination {
    var buildCombination: BuildCombination

    do {
        buildCombination =
            BuildCombination(
                buildList {
                    val ring1 = equipmentsByItemType[ItemType.RING]?.random(random)
                    ring1?.let { add(it) }
                    var ring2 = equipmentsByItemType[ItemType.RING]?.random(random)
                    var count = 0
                    while (ring2?.name == ring1?.name || count < 10) {
                        count++
                        ring2 = equipmentsByItemType[ItemType.RING]?.random(random)
                    }
                    ring2?.let { add(it) }
                    equipmentsByItemType[ItemType.HELMET]?.random(random)?.let { add(it) }
                    equipmentsByItemType[ItemType.AMULET]?.random(random)?.let { add(it) }
                    equipmentsByItemType[ItemType.EMBLEM]?.random(random)?.let { add(it) }
                    equipmentsByItemType[ItemType.SHOULDER_PADS]?.random(random)?.let { add(it) }
                    equipmentsByItemType[ItemType.BOOTS]?.random(random)?.let { add(it) }
                    equipmentsByItemType[ItemType.CHEST_PLATE]?.random(random)?.let { add(it) }
                    equipmentsByItemType[ItemType.CAPE]?.random(random)?.let { add(it) }
                    equipmentsByItemType[ItemType.PETS]?.random(random)?.let { add(it) }
                    equipmentsByItemType[ItemType.MOUNTS]?.random(random)?.let { add(it) }
                    equipmentsByItemType[ItemType.BELT]?.random(random)?.let { add(it) }

                    if (random.nextBoolean()) {
                        equipmentsByItemType[ItemType.ONE_HANDED_WEAPONS]?.random(random)?.let { add(it) }
                        equipmentsByItemType[ItemType.OFF_HAND_WEAPONS]?.random(random)?.let { add(it) }
                    } else {
                        equipmentsByItemType[ItemType.TWO_HANDED_WEAPONS]?.random(random)?.let { add(it) }
                    }
                },
                CharacterSkills(character.level).let {
                    val targetCharacteristics = targetStats.map { targetStat -> targetStat.characteristic }
                    val intelligenceRandomized =
                        it.intelligence.assignRandomPoints(it.intelligence.maxPointsToAssign, targetCharacteristics, random)
                    val strengthRandomized =
                        it.strength.assignRandomPoints(it.strength.maxPointsToAssign, targetCharacteristics, random)
                    val agilityRandomized =
                        it.agility.assignRandomPoints(it.agility.maxPointsToAssign, targetCharacteristics, random)
                    val luckRandomized =
                        it.luck.assignRandomPoints(it.luck.maxPointsToAssign, targetCharacteristics, random)
                    val majorRandomized =
                        it.major.assignRandomPoints(it.major.maxPointsToAssign, targetCharacteristics, random)
                    it.copy(
                        intelligence = intelligenceRandomized,
                        strength = strengthRandomized,
                        agility = agilityRandomized,
                        luck = luckRandomized,
                        major = majorRandomized
                    )
                }
            )
    } while (!buildCombination.isValid())

    return buildCombination
}
