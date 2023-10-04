package me.chosante.equipmentextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.chosante.common.Equipment
import me.chosante.equipmentextractor.dataretriever.getWakfuRawData
import me.chosante.equipmentextractor.dataretriever.wakfuAPILatestVersion
import java.io.File

suspend fun main() {
    val latestWakfuVersion = wakfuAPILatestVersion()
    val wakfuRawData = getWakfuRawData(latestWakfuVersion)
    val equipments = extractData(wakfuRawData)
    File("autobuilder/src/main/resources","equipments-v$latestWakfuVersion.json")
        .writeText(Json.encodeToString(ListSerializer(Equipment.serializer()), equipments))
}