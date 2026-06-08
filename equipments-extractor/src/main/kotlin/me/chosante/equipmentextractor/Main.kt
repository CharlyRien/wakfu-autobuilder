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
    val repositoryRoot = findRepositoryRoot()
    val outputDirectory = File(repositoryRoot, "autobuilder/src/main/resources").apply { mkdirs() }

    File(outputDirectory, "equipments-v$latestWakfuVersion.json")
        .writeText(Json.encodeToString(ListSerializer(Equipment.serializer()), equipments))
}

private fun findRepositoryRoot(): File =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() }
        ?: error("Unable to locate repository root from ${System.getProperty("user.dir")}")
