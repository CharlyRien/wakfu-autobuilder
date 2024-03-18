package me.chosante

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.chosante.common.Character
import me.chosante.common.Equipment
import me.chosante.common.ItemType

internal const val baseAPIUrl = "https://api.zenithwakfu.com/builder/api"
internal val apiZenithWakfuHeaders = mapOf(
    "Host" to "api.zenithwakfu.com",
    "Origin" to "https://www.zenithwakfu.com",
    "Referer" to "https://www.zenithwakfu.com/",
    "X-Requested-With" to "XMLHttpRequest"
)

data class ZenithInputParameters(val character: Character, val equipments: List<Equipment>)

suspend fun ZenithInputParameters.createZenithBuild() = withContext(Dispatchers.IO + SupervisorJob()) {
    withTimeout(10.seconds) {
        with(this@createZenithBuild) {
            val zenithBuild = createBuild(character)
            val ringSideIds = listOf(23, 24).iterator()
            val everyEquipmentCalls = equipments.map {
                async {
                    val sideValue = when (it.itemType) {
                        ItemType.RING -> ringSideIds.next()
                        ItemType.TWO_HANDED_WEAPONS, ItemType.ONE_HANDED_WEAPONS -> 540
                        ItemType.OFF_HAND_WEAPONS -> 520
                        else -> it.itemType.id
                    }
                    addEquipment(it, zenithBuild.id, sideValue)
                }
            }

            val everySkillCalls = character.characterSkills
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
