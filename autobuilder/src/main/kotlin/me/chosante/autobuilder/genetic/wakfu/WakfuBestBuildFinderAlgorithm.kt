package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import me.chosante.autobuilder.VERSION
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.GeneticAlgorithmResult
import me.chosante.common.Character
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import kotlin.time.Duration

object WakfuBestBuildFinderAlgorithm {
    private val logger = KotlinLogging.logger {}

    /**
     * The embedded Wakfu game-data version (e.g. `1.91.1.54`). Exposed publicly so callers outside
     * this module (the GUI's build-history persistence) can stamp saved builds with the exact data
     * set they were computed against — crucial for reproducibility across data bumps.
     */
    val dataVersion: String = VERSION

    val equipments =
        this.javaClass.classLoader.getResourceAsStream("equipments-v$VERSION.json")?.readAllBytes()!!.let {
            Json.decodeFromString<List<Equipment>>(String(it))
        }

    fun run(params: WakfuBestBuildParams): Flow<GeneticAlgorithmResult<BuildCombination>> {
        val equipmentsByItemType =
            groupAndFilterEquipments(
                excludedItems = params.excludedItems,
                forcedItems = params.forcedItems,
                maxRarity = params.maxRarity,
                excludedRarities = params.excludedRarities,
                character = params.character
            )

        return try {
            WakfuBuildSolver.optimize(params, equipmentsByItemType)
        } catch (exception: Exception) {
            // Surface the failure to the caller instead of killing the JVM: the CLI's runBlocking
            // turns it into a visible crash, while the GUI can catch it and show an error rather than
            // having the whole desktop app terminated by exitProcess.
            logger.error(exception) { "Exception occurred during the process of finding the best equipments." }
            throw exception
        }
    }

    private fun groupAndFilterEquipments(
        excludedItems: List<String>,
        forcedItems: List<String>,
        maxRarity: Rarity,
        excludedRarities: Set<Rarity>,
        character: Character,
    ): Map<ItemType, List<Equipment>> {
        val itemsExcluded = excludedItems.map { it.lowercase() }
        val itemsToForce = forcedItems.map { it.lowercase() }
        val eligibleEquipments =
            equipments
                .asSequence()
                .filter { equipment ->
                    equipment.rarity <= maxRarity && equipment.rarity !in excludedRarities
                }.filter { equipment ->
                    (equipment.level <= character.level && equipment.level >= character.minLevel) ||
                        (equipment.itemType == ItemType.PETS || equipment.itemType == ItemType.MOUNTS)
                }.filter { equipment -> equipment.name.fr.lowercase() !in itemsExcluded }
                .toList()
        val forcedWeaponTypes =
            eligibleEquipments
                .filter { it.name.fr.lowercase() in itemsToForce }
                .map { it.itemType }
                .toSet()
        val equipmentsByItemType =
            eligibleEquipments
                .groupBy { it.itemType }
                .mapValues { (_, value) ->
                    if (value.any { it.name.fr.lowercase() in itemsToForce }) {
                        value.filter { it.name.fr.lowercase() in itemsToForce || itemsToForce.isEmpty() }
                    } else {
                        value
                    }
                }.toMutableMap()
        if (ItemType.TWO_HANDED_WEAPONS in forcedWeaponTypes) {
            equipmentsByItemType.remove(ItemType.ONE_HANDED_WEAPONS)
            equipmentsByItemType.remove(ItemType.OFF_HAND_WEAPONS)
        } else if (ItemType.ONE_HANDED_WEAPONS in forcedWeaponTypes || ItemType.OFF_HAND_WEAPONS in forcedWeaponTypes) {
            equipmentsByItemType.remove(ItemType.TWO_HANDED_WEAPONS)
        }
        return equipmentsByItemType
    }
}

data class WakfuBestBuildParams(
    val character: Character,
    val targetStats: TargetStats,
    val searchDuration: Duration,
    val stopWhenBuildMatch: Boolean,
    val maxRarity: Rarity,
    /** Rarities the build may not use at all (independent of [maxRarity]); empty allows every rarity. */
    val excludedRarities: Set<Rarity> = emptySet(),
    val forcedItems: List<String>,
    val excludedItems: List<String>,
    val scoreComputationMode: ScoreComputationMode,
)
