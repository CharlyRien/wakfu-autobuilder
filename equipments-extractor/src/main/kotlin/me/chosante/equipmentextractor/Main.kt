package me.chosante.equipmentextractor

import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.chosante.common.Equipment
import me.chosante.equipmentextractor.dataretriever.getWakfuRawData
import me.chosante.equipmentextractor.dataretriever.wakfuAPILatestVersion

suspend fun main() {
    val latestWakfuVersion = wakfuAPILatestVersion()
    val wakfuRawData = getWakfuRawData(latestWakfuVersion)
    val equipments = extractData(wakfuRawData)
    File("autobuilder/src/main/resources", "equipments-v$latestWakfuVersion.json")
        .writeText(Json.encodeToString(ListSerializer(Equipment.serializer()), equipments))
}
