package me.chosante

import com.github.kittinunf.fuel.core.awaitUnit
import com.github.kittinunf.fuel.core.extensions.jsonBody
import com.github.kittinunf.fuel.httpPost
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.chosante.common.skills.AgilityCharacteristic
import me.chosante.common.skills.IntelligenceCharacteristic
import me.chosante.common.skills.LuckCharacteristic
import me.chosante.common.skills.MajorCharacteristic
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.common.skills.StrengthCharacteristic

private const val urlUpdateSkill = "$baseAPIUrl/aptitude/update"

internal suspend fun addSkill(
    skillCharacteristic: SkillCharacteristic,
    buildId: Long,
) {
    val jsonPayload =
        buildJsonObject {
            put("id_build", buildId)
            put("id_aptitude", skillCharacteristic.toZenithWakfuAptitudeId())
            put("aptitude_value", skillCharacteristic.pointsAssigned)
        }
    urlUpdateSkill
        .httpPost()
        .header(apiZenithWakfuHeaders)
        .jsonBody(Json.encodeToString<JsonObject>(jsonPayload))
        .awaitUnit()
}

private fun SkillCharacteristic.toZenithWakfuAptitudeId(): Int =
    when (this) {
        is IntelligenceCharacteristic.HpPercentage -> 1
        is IntelligenceCharacteristic.Resistance -> 2
        is IntelligenceCharacteristic.Shield -> 3
        is IntelligenceCharacteristic.HealReceivedPercentage -> 4
        is IntelligenceCharacteristic.HpPercentageAsArmor -> 5
        is StrengthCharacteristic.MasteryElementary -> 6
        is StrengthCharacteristic.MasteryMelee -> 9
        is StrengthCharacteristic.MasteryDistance -> 10
        is StrengthCharacteristic.Hp -> 11
        is AgilityCharacteristic.Lock -> 12
        is AgilityCharacteristic.Dodge -> 13
        is AgilityCharacteristic.Initiative -> 14
        is AgilityCharacteristic.DodgeAndLock -> 15
        is AgilityCharacteristic.Willpower -> 16
        is LuckCharacteristic.CriticalHit -> 17
        is LuckCharacteristic.Block -> 18
        is LuckCharacteristic.MasteryCritical -> 19
        is LuckCharacteristic.MasteryBack -> 20
        is LuckCharacteristic.MasteryBerserk -> 21
        is LuckCharacteristic.MasteryHealing -> 22
        is LuckCharacteristic.ResistanceBack -> 23
        is LuckCharacteristic.ResistanceCritical -> 24
        is MajorCharacteristic.ActionPoint -> 25
        is MajorCharacteristic.MovementPointWithMasteryElementary -> 26
        is MajorCharacteristic.RangeWithMasteryElementary -> 27
        is MajorCharacteristic.WakfuPoints -> 28
        is MajorCharacteristic.ControlWithMasteryElementary -> 29
        is MajorCharacteristic.DamageInflicted -> 30
        is MajorCharacteristic.Resistance -> 31
        else -> throw IllegalStateException("Unknown characteristic: $this")
    }
