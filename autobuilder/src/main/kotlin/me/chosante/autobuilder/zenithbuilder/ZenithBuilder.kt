package me.chosante.autobuilder.zenithbuilder

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.Character

internal const val baseAPIUrl = "https://api.zenithwakfu.com/builder/api"
val apiZenithWakfuHeaders = mapOf(
    "Host" to "api.zenithwakfu.com",
    "Origin" to "https://www.zenithwakfu.com",
    "Referer" to "https://www.zenithwakfu.com/",
    "X-Requested-With" to "XMLHttpRequest"
)

suspend fun BuildCombination.createZenithBuild(character: Character) = supervisorScope {
    val zenithBuild = createBuild(character)
    val buildCombination = this@createZenithBuild
    val everyEquipmentCalls = buildCombination.equipments.map {
        async {
            addEquipment(it, character.level, zenithBuild.id)
        }
    }
    val everySkillCalls = buildCombination
        .characterSkills
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
