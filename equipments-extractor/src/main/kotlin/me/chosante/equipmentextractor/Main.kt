package me.chosante.equipmentextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.chosante.common.Equipment
import me.chosante.common.findRepositoryRoot
import me.chosante.equipmentextractor.dataretriever.getWakfuRawData
import me.chosante.equipmentextractor.dataretriever.wakfuAPILatestVersion
import java.io.File

suspend fun main() {
    val latestWakfuVersion = wakfuAPILatestVersion()
    // The CDN's latest version is the single source of truth for "what version is current". The update
    // script (scripts/update-game-data.sh) greps this line to sync WakfuData.VERSION; keep it parseable.
    println("Latest Wakfu data version (Ankama CDN): $latestWakfuVersion")
    val wakfuRawData = getWakfuRawData(latestWakfuVersion)
    val equipments = extractData(wakfuRawData)
    val repositoryRoot = findRepositoryRoot()
    val outputDirectory = File(repositoryRoot, "autobuilder/src/main/resources").apply { mkdirs() }

    File(outputDirectory, "equipments.json")
        .writeText(Json.encodeToString(ListSerializer(Equipment.serializer()), equipments))
    println("Wrote ${equipments.size} equipments -> equipments.json (data version $latestWakfuVersion)")
}
