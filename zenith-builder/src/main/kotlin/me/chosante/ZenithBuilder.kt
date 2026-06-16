package me.chosante

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import me.chosante.common.Character
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import kotlin.time.Duration.Companion.seconds

internal const val BASE_API_URL = "https://api.zenithwakfu.com/builder/api"
internal val apiZenithWakfuHeaders =
    mapOf(
        "Host" to "api.zenithwakfu.com",
        "Origin" to "https://www.zenithwakfu.com",
        "Referer" to "https://www.zenithwakfu.com/",
        "X-Requested-With" to "XMLHttpRequest"
    )

data class ZenithInputParameters(
    val character: Character,
    val equipments: List<Equipment>,
    // Runes socketed per equipped item (from BuildCombination.runes); empty when runes are disabled.
    val runes: Map<Equipment, List<RuneType>> = emptyMap(),
    // Sublimations hosted per equipped item (from BuildCombination.sublimations); socketed like runes.
    val sublimations: Map<Equipment, List<Sublimation>> = emptyMap(),
)

suspend fun ZenithInputParameters.createZenithBuild() =
    supervisorScope {
        withTimeout(10.seconds) {
            with(this@createZenithBuild) {
                val zenithBuild = createBuild(character)
                val ringSideIds = listOf(23, 24).iterator()
                val everyEquipmentCalls =
                    equipments.map { equipment ->
                        async {
                            val sideValue =
                                when (equipment.itemType) {
                                    ItemType.RING -> ringSideIds.next()
                                    ItemType.TWO_HANDED_WEAPONS, ItemType.ONE_HANDED_WEAPONS -> 540
                                    ItemType.OFF_HAND_WEAPONS -> 520
                                    else -> equipment.itemType.id
                                }
                            addEquipment(equipment, zenithBuild.id, sideValue)
                            // Socket this item's runes once it exists on the build; the shard's `side`
                            // must match the item's, and `position` is the 0-based socket index.
                            runes[equipment].orEmpty().forEachIndexed { index, rune ->
                                addShard(
                                    buildId = zenithBuild.id,
                                    shardId = rune.id,
                                    position = index,
                                    side = sideValue,
                                    level = rune.maxLevel(equipment.level)
                                )
                            }
                            // Then this item's sublimations: socketed like runes but with level = null,
                            // placed after the runes so socket positions don't collide. (Epic/relic subs
                            // have no socket pattern — their position is best-effort; verify on the live builder.)
                            val runeCount = runes[equipment].orEmpty().size
                            sublimations[equipment].orEmpty().forEachIndexed { index, sub ->
                                addShard(
                                    buildId = zenithBuild.id,
                                    shardId = sub.zenithId,
                                    position = runeCount + index,
                                    side = sideValue,
                                    level = null
                                )
                            }
                        }
                    }

                val everySkillCalls =
                    character.characterSkills
                        .allCharacteristic
                        .filter { it.pointsAssigned > 0 }
                        .map {
                            async {
                                addSkill(it, zenithBuild.id)
                            }
                        }
                everySkillCalls.awaitAll()
                everyEquipmentCalls.awaitAll()
                zenithBuild.link
            }
        }
    }
