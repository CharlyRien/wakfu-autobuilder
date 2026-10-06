package me.chosante.autobuilder.domain

import me.chosante.common.CharacterClass
import me.chosante.common.Equipment
import me.chosante.common.ExclusiveGroup

// The item EQUIP conditions the engine ENFORCES (AGENTS.md §4 "Item equip conditions"), read from the item's own
// [Equipment.equipCriterion] (joined from `item-criteria.json` when the catalog loads). Every consumer — the pool
// filters, the CP-SAT model, the domination pre-filter, the greedy warm start and [BuildCombination.isValid] — goes
// through these few functions, so they agree on what a legal build is:
//  - REQUIRES ([requiredItemIds]): the item is only worn together with each of these items (a nation sword and its
//    ring — the game re-checks the whole equipped set, so it is a rule on the final build);
//  - FORBIDS ([equipConflict]): the symmetric closure of `not HasEquipmentId` — two items conflict when EITHER one
//    forbids the other (the Lieute rings only list their bans in their CRAFT criterion, yet the game refuses any two
//    of their triple together);
//  - CLASS-ONLY / NEVER ([isWearableBy]): static, filtered out of the pool before any search.
// Stat gates and player-state conditions are not enforced (see [me.chosante.common.ItemEquipCriterion]).

/** The ids of the items [this] can only be worn with (its EQUIP criterion's `HasEquipmentId`). */
val Equipment.requiredItemIds: List<Int>
    get() = equipCriterion?.requiresItems.orEmpty()

/** The ids [this] names in a `not HasEquipmentId` — one direction of [equipConflict]. */
val Equipment.forbiddenItemIds: List<Int>
    get() = equipCriterion?.forbidsItems.orEmpty()

/** Whether a character of [clazz] may wear [this] at all: never a `False` item, and a class item only by its class. */
fun Equipment.isWearableBy(clazz: CharacterClass): Boolean {
    val criterion = equipCriterion ?: return true
    return !criterion.never && (criterion.classes.isEmpty() || clazz in criterion.classes)
}

/** Whether [a] and [b] can't be worn together: either one forbids the other. */
fun equipConflict(
    a: Equipment,
    b: Equipment,
): Boolean = b.equipmentId in a.forbiddenItemIds || a.equipmentId in b.forbiddenItemIds

/**
 * The first EQUIP-condition violation of the set [equipments] (null when it is legal), [clazz] checked when given: an
 * item a character of that class can't wear (or no character can), an item without one of its required items, or two
 * conflicting items. Human-readable — it names the items by French name.
 */
fun equipConditionViolation(
    equipments: Collection<Equipment>,
    clazz: CharacterClass? = null,
): String? {
    val ids = equipments.mapTo(HashSet()) { it.equipmentId }
    for (item in equipments) {
        val criterion = item.equipCriterion ?: continue
        if (criterion.never) return "${item.name.fr} can never be equipped"
        if (clazz != null && !item.isWearableBy(clazz)) return "${item.name.fr} is for ${criterion.classes} only, not $clazz"
        criterion.requiresItems.firstOrNull { it !in ids }?.let { return "${item.name.fr} needs item $it" }
    }
    val list = equipments.toList()
    for (i in list.indices) {
        for (j in i + 1 until list.size) {
            if (equipConflict(list[i], list[j])) return "${list[i].name.fr} and ${list[j].name.fr} can't be worn together"
        }
    }
    return null
}

/** [ids] plus the ids of the [catalog] items they require, to a fixpoint (a key's own keys). */
fun requirementClosure(
    ids: Set<Int>,
    catalog: Collection<Equipment>,
): Set<Int> {
    val byId = catalog.associateBy { it.equipmentId }
    val closed = LinkedHashSet(ids)
    var frontier: Collection<Int> = ids
    while (frontier.isNotEmpty()) {
        frontier = frontier.flatMap { byId[it]?.requiredItemIds.orEmpty() }.filter { closed.add(it) }
    }
    return closed
}

/**
 * Drops from [pool] every item one of whose required items is not in it (any slot), to a fixpoint — a requirement
 * that can never be met makes the item unwearable for this request (its key ring is above the rarity cap, out of the
 * level band, excluded by name or crowded out of a slot by forced items). A slot left empty is dropped, like a slot
 * the pool never had. [pool] itself is returned when nothing goes (the common case), so identity-keyed memos still hit.
 */
fun <K> withRequirementsMet(pool: Map<K, List<Equipment>>): Map<K, List<Equipment>> {
    var current = pool
    while (true) {
        val ids = current.values.flatMapTo(HashSet()) { items -> items.map { it.equipmentId } }
        if (current.values.all { items -> items.all { item -> item.requiredItemIds.all { it in ids } } }) return current
        current =
            current
                .mapValues { (_, items) -> items.filter { item -> item.requiredItemIds.all { it in ids } } }
                .filterValues { it.isNotEmpty() }
    }
}

/**
 * The first "only one equipped at a time" rule [equipments] break (null when none): two items of the EPIC exclusivity
 * group — every EPIC item and two COMMON ones, 18691 / 18693 — or two of the RELIC group ([Equipment.exclusiveGroup], the
 * CDN item properties 12 and 8). Human-readable — it names the items by French name.
 */
fun exclusiveGroupViolation(equipments: Collection<Equipment>): String? {
    for (group in listOf(ExclusiveGroup.EPIC, ExclusiveGroup.RELIC)) {
        val members = equipments.filter { it.exclusiveGroup == group }
        if (members.size > 1) return "${members.joinToString(" and ") { it.name.fr }} are all in the $group exclusivity group (one at a time)"
    }
    return null
}

/**
 * The key the certificates pair rings on, per ring of [rings] (by equipment id): a bound offers two rings together only when
 * their keys differ. The game refuses two rings of one (French) name and two rings either of which FORBIDS the other
 * ([equipConflict]). These conflicts form a graph; where a connected component of it is a CLIQUE — every two of its rings
 * conflict — one key for the whole component is EXACTLY the game's rule (on the 1.93 data: every name class, the five
 * different-name FORBIDS triples, Issé Sceau's three rarities). A component that is not a clique keeps the plain name keys:
 * its FORBIDS pairs are then offered — the relaxation the certificates used before, sound and looser. A FORBIDS partner that is
 * not one of [rings] is ignored (also a relaxation). So a key never separates two rings the game lets a build wear together.
 */
fun ringPairingKeys(rings: Collection<Equipment>): Map<Int, String> {
    val list = rings.distinctBy { it.equipmentId }
    val byId = list.associateBy { it.equipmentId }
    val nameKey = list.associate { it.equipmentId to it.name.fr.lowercase() }
    val parent = HashMap<Int, Int>().apply { list.forEach { put(it.equipmentId, it.equipmentId) } }

    fun find(id: Int): Int {
        var root = id
        while (parent.getValue(root) != root) root = parent.getValue(root)
        return root
    }

    fun union(
        a: Int,
        b: Int,
    ) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
    }
    list.groupBy { nameKey.getValue(it.equipmentId) }.values.forEach { same -> same.zipWithNext { a, b -> union(a.equipmentId, b.equipmentId) } }
    for (ring in list) {
        for (other in ring.forbiddenItemIds) if (other in byId) union(ring.equipmentId, other)
    }
    val keys = HashMap<Int, String>()
    for (members in list.groupBy { find(it.equipmentId) }.values) {
        val names = members.map { nameKey.getValue(it.equipmentId) }.distinct()
        val clique = members.all { a -> members.all { b -> a === b || nameKey[a.equipmentId] == nameKey[b.equipmentId] || equipConflict(a, b) } }
        val componentKey = if (names.size > 1 && clique) "forbids#${members.minOf { it.equipmentId }}" else null
        for (m in members) keys[m.equipmentId] = componentKey ?: nameKey.getValue(m.equipmentId)
    }
    return keys
}
