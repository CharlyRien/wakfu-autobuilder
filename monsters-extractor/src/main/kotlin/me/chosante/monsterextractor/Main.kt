package me.chosante.monsterextractor

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.chosante.common.Monster
import java.io.File

/**
 * Builds `autobuilder/src/main/resources/monsters.json` from the MethodWakfu bestiary
 * (primary, flat resistances), cross-referenced against the Fandom `MonsterCard` template for
 * provenance and used as a **fallback** to recover monsters MethodWakfu cannot serve (its detail
 * endpoint HTTP 500s on some endgame bosses).
 *
 * Run with: `./gradlew :monsters-extractor:run`.
 */
suspend fun main() {
    println("Fetching MethodWakfu bestiary…")
    val fetch = MethodWakfuBestiary.fetchAll()
    println("Fetched ${fetch.monsters.size} monsters (${fetch.failed.size} unreachable on MethodWakfu).")

    val fandomTitles =
        runCatching { FandomCrossReference.monsterTitles() }
            .onFailure { println("Fandom unavailable (${it.message}); keeping MethodWakfu only.") }
            .getOrDefault(emptyMap())
    if (fandomTitles.isNotEmpty()) println("Fandom MonsterCard pages: ${fandomTitles.size}.")

    // Provenance: tag MethodWakfu monsters that Fandom also documents.
    val tagged =
        fetch.monsters.map { monster ->
            if (FandomCrossReference.normalize(monster.name.en) in fandomTitles) {
                monster.copy(source = "methodwakfu+fandom")
            } else {
                monster
            }
        }

    // Fallback: try to recover the unreachable monsters from Fandom (level-matched, flat resistances).
    val recovered =
        if (fandomTitles.isEmpty()) {
            emptyList()
        } else {
            fetch.failed.mapNotNull { failure ->
                runCatching { FandomCrossReference.recover(failure, fandomTitles) }.getOrNull()
            }
        }
    if (recovered.isNotEmpty()) println("Recovered ${recovered.size} monster(s) from Fandom.")
    val recoveredIds = recovered.map { it.id }.toSet()
    val stillMissing = fetch.failed.filter { it.id !in recoveredIds }

    val all =
        (tagged + recovered)
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Monster> { it.rank }.thenByDescending { it.level }.thenBy { it.name.en })

    val outputDirectory = File(findRepositoryRoot(), "autobuilder/src/main/resources").apply { mkdirs() }
    val outputFile = File(outputDirectory, "monsters.json")
    outputFile.writeText(Json.encodeToString(ListSerializer(Monster.serializer()), all))

    printReport(all, recovered, stillMissing, fandomTitles, outputFile)
}

private fun printReport(
    monsters: List<Monster>,
    recovered: List<Monster>,
    stillMissing: List<MethodWakfuBestiary.FailedMonster>,
    fandomTitles: Map<String, String>,
    outputFile: File,
) {
    val bosses = monsters.count { it.isBoss }
    val bySource = monsters.groupingBy { it.source }.eachCount().toSortedMap()
    val fandomOnly = fandomTitles.keys - monsters.map { FandomCrossReference.normalize(it.name.en) }.toSet()
    println()
    println("=== monsters-extractor report ===")
    println("Wrote ${monsters.size} monsters → ${outputFile.path}")
    println("  bosses (rank ≥ 1): $bosses   normal: ${monsters.size - bosses}")
    println("  rank breakdown: ${monsters.groupingBy { it.rank }.eachCount().toSortedMap()}")
    println("  source: $bySource")
    if (recovered.isNotEmpty()) {
        println("  recovered from Fandom: ${recovered.size}")
        recovered.forEach { println("    + [r${it.rank} lvl${it.level}] ${it.name.fr}") }
    }
    if (fandomTitles.isNotEmpty()) println("  Fandom-only (not in dataset): ${fandomOnly.size}")
    if (stillMissing.isNotEmpty()) {
        // MethodWakfu's detail endpoint HTTP 500s on these (a server-side "Unknown item" bug while
        // resolving their drops) and no other source has them at a matching level — listed, never faked.
        val missingBosses = stillMissing.count { it.isBoss }
        println("  STILL MISSING (no source): ${stillMissing.size} (bosses: $missingBosses)")
        println("    rank breakdown: ${stillMissing.groupingBy { it.rank }.eachCount().toSortedMap()}")
        stillMissing
            .sortedWith(compareByDescending<MethodWakfuBestiary.FailedMonster> { it.rank }.thenByDescending { it.level })
            .forEach { println("    - [r${it.rank} lvl${it.level}] ${it.name.fr} (id ${it.id})") }
    }
}

private fun findRepositoryRoot(): File =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() }
        ?: error("Unable to locate repository root from ${System.getProperty("user.dir")}")
