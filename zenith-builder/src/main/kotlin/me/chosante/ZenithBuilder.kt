package me.chosante

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import me.chosante.common.Character
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationRarity
import kotlin.time.Duration.Companion.seconds

internal const val BASE_API_URL = "https://api.zenithwakfu.com/builder/api"
internal val apiZenithWakfuHeaders =
    mapOf(
        "Host" to "api.zenithwakfu.com",
        "Origin" to "https://www.zenithwakfu.com",
        "Referer" to "https://www.zenithwakfu.com/",
        "X-Requested-With" to "XMLHttpRequest"
    )

data class ZenithInputParameters(
    val character: Character,
    val equipments: List<Equipment>,
    // Runes socketed per equipped item (from BuildCombination.runes); empty when runes are disabled.
    val runes: Map<Equipment, List<RuneType>> = emptyMap(),
    // Sublimations hosted per equipped item (from BuildCombination.sublimations); socketed like runes.
    val sublimations: Map<Equipment, List<Sublimation>> = emptyMap(),
)

/**
 * One planned `/shard/add` call: a rune (with a [level], [whiten] = true) or a sublimation
 * ([level] = null — Zenith ignores the parameter for subs; the shard id's intrinsic level is the
 * socketed level, so the id must already be the tier-resolved family member).
 */
internal data class PlannedShard(
    val shardId: Int,
    val position: Int,
    val side: Int,
    val level: Int?,
    val whiten: Boolean = false,
)

/**
 * The Zenith `side` of each equipped item, paired in list order. The two RINGs take the dedicated ring
 * sides (23 / 24), weapons their fixed slot ids, everything else its [ItemType.id].
 *
 * Computed SEQUENTIALLY, up front — the ring-side iterator used to be advanced from inside the per-item
 * `async` blocks, so the two rings raced on a non-thread-safe iterator and could both claim side 23 (or
 * blow up on `next()`). Returns a list, not a map: two equipped items are distinct objects and must each
 * keep their own side even if they ever shared an `equipmentId`.
 */
internal fun sideAssignments(equipments: List<Equipment>): List<Pair<Equipment, Int>> {
    val ringSideIds = listOf(23, 24).iterator()
    return equipments.map { equipment ->
        val side =
            when (equipment.itemType) {
                ItemType.RING -> ringSideIds.next()
                ItemType.TWO_HANDED_WEAPONS, ItemType.ONE_HANDED_WEAPONS -> 540
                ItemType.OFF_HAND_WEAPONS -> 520
                else -> equipment.itemType.id
            }
        equipment to side
    }
}

/**
 * The shards to socket on [equipment]: its [runes] first (positions `0..n-1`, each at the level its
 * carrier's item level allows and flagged for whitening — see [whitenShard]), then its [subs]
 * (positions `n..`, `level = null`). Positions never collide because the subs start after the runes.
 *
 * A CUMULABLE sublimation stacked across several carriers appears once in EACH carrier's [subs] list, so it
 * yields one call per carrier — same `id_shard`, different `side`. That is exactly how the game sockets it
 * (one copy per ≥3-socket item), and nothing here dedupes by shard id.
 *
 * [subShardId] maps a sublimation to the Zenith shard id actually socketed — the tier-resolved family
 * member (see [fetchSublimationLevelIds]); the default keeps the raw `zenithId` (family root, level 1).
 *
 * EPIC/RELIC subs live in the build's dedicated character slots on Zenith, not on an item: the UI posts
 * them with `side = 0, position = 0`, so the export does too (the server routes them by the shard's
 * own epic/relic flag).
 */
internal fun plannedShards(
    equipment: Equipment,
    side: Int,
    runes: List<RuneType>,
    subs: List<Sublimation>,
    subShardId: (Sublimation) -> Int = { it.zenithId },
): List<PlannedShard> =
    runes.mapIndexed { index, rune -> PlannedShard(rune.id, index, side, rune.maxLevel(equipment.level), whiten = true) } +
        subs.mapIndexed { index, sub ->
            if (sub.rarity == SublimationRarity.NORMAL) {
                PlannedShard(subShardId(sub), runes.size + index, side, null)
            } else {
                PlannedShard(subShardId(sub), 0, 0, null)
            }
        }

/**
 * The tier-resolver for [plannedShards]: the family member of [Sublimation.zenithId] whose Zenith shard
 * level equals the sub's credited [Sublimation.maxTier]. Falls back to the raw id when the catalog is
 * unreachable or the family has no such member (then Zenith shows the tier-I shard, level 1).
 */
private fun subShardIdResolver(levelIdsByRoot: Map<Int, Map<Int, Int>>): (Sublimation) -> Int = { sub -> levelIdsByRoot[sub.zenithId]?.get(sub.maxTier) ?: sub.zenithId }

suspend fun ZenithInputParameters.createZenithBuild() =
    supervisorScope {
        withTimeout(10.seconds) {
            with(this@createZenithBuild) {
                val zenithBuild = createBuild(character)
                val subShardId =
                    if (sublimations.values.any { it.isNotEmpty() }) {
                        subShardIdResolver(runCatching { fetchSublimationLevelIds() }.getOrDefault(emptyMap()))
                    } else {
                        { sub: Sublimation -> sub.zenithId }
                    }
                val everyEquipmentCalls =
                    sideAssignments(equipments).map { (equipment, sideValue) ->
                        async {
                            addEquipment(equipment, zenithBuild.id, sideValue)
                            // Socket this item's runes then its sublimations, once the item exists on the
                            // build; the shard's `side` must match the item's.
                            plannedShards(equipment, sideValue, runes[equipment].orEmpty(), sublimations[equipment].orEmpty(), subShardId)
                                .forEach { shard ->
                                    addShard(
                                        buildId = zenithBuild.id,
                                        shardId = shard.shardId,
                                        position = shard.position,
                                        side = shard.side,
                                        level = shard.level
                                    )
                                    // Whitening is a separate transform on the already-socketed shard.
                                    if (shard.whiten) {
                                        whitenShard(
                                            buildId = zenithBuild.id,
                                            shardId = shard.shardId,
                                            position = shard.position,
                                            side = shard.side
                                        )
                                    }
                                }
                        }
                    }

                val everySkillCalls =
                    character.characterSkills
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
