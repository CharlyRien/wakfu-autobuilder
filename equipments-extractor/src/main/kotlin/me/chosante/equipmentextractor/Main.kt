package me.chosante.equipmentextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.chosante.common.Equipment
import me.chosante.common.WakfuData
import me.chosante.common.findRepositoryRoot
import me.chosante.equipmentextractor.dataretriever.getWakfuRawData
import java.io.File

suspend fun main() {
    // Fetch the CDN game data for the version pinned in common-lib's WakfuData.VERSION — the single source of
    // truth shared by every extractor and app. This tool no longer probes the CDN for "latest" on its own, so
    // a stray run can't silently drift equipments.json onto a different version than the rest of the data set.
    // To move to a new Wakfu release, bump WakfuData.VERSION first (scripts/update-game-data.sh does this).
    val version = WakfuData.VERSION
    println("Using pinned Wakfu data version (common-lib WakfuData.VERSION): $version")
    val wakfuRawData = getWakfuRawData(version)
    val equipments = extractData(wakfuRawData)
    val repositoryRoot = findRepositoryRoot()
    val outputDirectory = File(repositoryRoot, "autobuilder/src/main/resources").apply { mkdirs() }

    File(outputDirectory, "equipments.json")
        .writeText(Json.encodeToString(ListSerializer(Equipment.serializer()), equipments))
    println("Wrote ${equipments.size} equipments -> equipments.json (data version $version)")
}
