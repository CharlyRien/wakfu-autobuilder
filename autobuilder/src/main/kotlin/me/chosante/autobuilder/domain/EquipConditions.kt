package me.chosante.autobuilder.domain

import me.chosante.autobuilder.genetic.wakfu.foldedToUsableStat
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.CriterionComparison
import me.chosante.common.Equipment
import me.chosante.common.ExclusiveGroup
import me.chosante.common.ItemStatGate
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind

// The item EQUIP conditions the engine ENFORCES (AGENTS.md §4 "Item equip conditions"), read from the item's own
// [Equipment.equipCriterion] (joined from `item-criteria.json` when the catalog loads). Every consumer — the pool
// filters, the CP-SAT model, the domination pre-filter, the greedy warm start and [BuildCombination.isValid] — goes
// through these few functions, so they agree on what a legal build is:
//  - REQUIRES ([requiredItemIds]): the item is only worn together with each of these items (a nation sword and its
//    ring — the game re-checks the whole equipped set, so it is a rule on the final build);
//  - FORBIDS ([equipConflict]): the symmetric closure of `not HasEquipmentId` — two items conflict when EITHER one
//    forbids the other (the Lieute rings only list their bans in their CRAFT criterion, yet the game refuses any two
//    of their triple together);
//  - CLASS-ONLY / NEVER ([isWearableBy]): static, filtered out of the pool before any search;
//  - STAT GATES ([statGates], [statGateViolations]): `GetCharac("RANGE") <= 3` and the like, read on the build's
//    OUT-OF-COMBAT sheet ([outOfCombatSheet]) — an item whose gate fails is inactive in game (it turns red), so a build
//    that breaks one is not a valid build. The CP-SAT twin is `StatBuilder.applyItemStatGates`.
// Player-state conditions are not enforced: they are outside the build, assumed met (see [me.chosante.common.ItemEquipCriterion]).

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

/** The `GetCharac` / `GetCharacMax` gates of [this]'s EQUIP criterion (empty when it has none). */
val Equipment.statGates: List<ItemStatGate>
    get() = equipCriterion?.statGates.orEmpty()

/** The usable characteristic [this] gate reads: AP / MP / WP for `GetCharacMax` too (see [outOfCombatSheet]). */
val ItemStatGate.sheetCharacteristic: Characteristic
    get() = characteristic.foldedToUsableStat()

/** Whether [this] gate holds on an out-of-combat sheet value of [actual]. */
fun ItemStatGate.holdsOn(actual: Int): Boolean = comparison.holds(actual, value)

/**
 * Whether [this] gate holds on EVERY value of [range] — a sound reach of the sheet stat — so no build can break it (the CP-SAT
 * model then adds nothing for it). An empty range holds vacuously.
 */
fun ItemStatGate.holdsOnEvery(range: LongRange): Boolean {
    if (range.isEmpty()) return true
    val v = value.toLong()
    return when (comparison) {
        CriterionComparison.LT -> range.last < v
        CriterionComparison.LE -> range.last <= v
        CriterionComparison.GT -> range.first > v
        CriterionComparison.GE -> range.first >= v
        CriterionComparison.EQ -> range.first == v && range.last == v
        CriterionComparison.NE -> v !in range
    }
}

/** One broken stat gate: [item] is inactive in game because its [gate] fails on the build's out-of-combat [actual] value. */
data class StatGateViolation(
    val item: Equipment,
    val gate: ItemStatGate,
    val actual: Int,
) {
    /** English one-liner, the item named in French (CLI, logs, [equipConditionViolation]-style messages). */
    fun describe(): String = "${item.name.fr} would be inactive in game: ${gate.characteristic} ${gate.comparison.symbol} ${gate.value}, the build has $actual"
}

/**
 * The build's OUT-OF-COMBAT sheet — the character-sheet totals the game checks an item's stat gate against, re-checked on the
 * whole equipped set: the character's base stats, every item's lines INCLUDING the gated item's own, the skills' FIXED lines,
 * the runes, the PERMANENT sublimation effects ([SublimationEffect.appliesBeforeCombat] — Visibilité's +1 range — never a
 * scenario-gated one: a scenario is a combat situation) and the selected passives' flat stats ([me.chosante.common.Passive.flatStats],
 * the extractor's permanent subset, which the game shows on the sheet). Start-of-combat and in-combat sublimation effects
 * (Abandon's range) do not count. AP / MP / WP fold their MAX_* lines in ([foldedToUsableStat]): out of combat a pool's current
 * value is its maximum, so a `GetCharacMax` gate ([ItemStatGate.max]) reads the same total as a `GetCharac` one. The skills'
 * PERCENT lines (% HP) are left out — no gated stat has one. Mirrors `StatBuilder.outOfCombatStat` term for term.
 *
 * [characterClass] sets the base stats (a Xelor starts with 12 WP); null reads a 6-WP class.
 */
fun outOfCombatSheet(
    build: BuildCombination,
    characterClass: CharacterClass?,
): Map<Characteristic, Int> {
    val level = build.characterSkills.level
    val sheet = HashMap<Characteristic, Int>()

    fun add(
        characteristic: Characteristic,
        value: Int,
    ) {
        if (value != 0) sheet.merge(characteristic.foldedToUsableStat(), value, Int::plus)
    }
    Character(characterClass ?: CharacterClass.UNKNOWN, level, level).baseCharacteristicValues.forEach { (c, v) -> add(c, v) }
    for (item in build.equipments) item.characteristics.forEach { (c, v) -> add(c, v) }
    build.characterSkills.allCharacteristicValues.fixedValues
        .forEach { (c, v) -> add(c, v) }
    for ((carrier, runes) in build.runes) runes.forEach { add(it.characteristic, it.valueOn(carrier.itemType, carrier.level)) }
    for (sub in build.sublimations.values.flatten()) {
        if (sub.kind == SublimationKind.COMBAT_CONDITIONAL || sub.kind == SublimationKind.CONVERSION) continue
        for (effect in sub.effects.filterIsInstance<SublimationEffect.StatEffect>()) {
            if (effect.appliesBeforeCombat && effect.scenarioGate == null) add(effect.characteristic, effect.magnitudeAtLevel(level))
        }
    }
    for (passive in build.passives) passive.flatStats.forEach { (c, v) -> add(c, v) }
    return sheet
}

/**
 * Every stat gate [build] breaks ([outOfCombatSheet]), item by item in build order — empty when every item it wears is active.
 * Reads each item's own [Equipment.equipCriterion]: a build read back from a save carries none, see
 * `WakfuBestBuildFinderAlgorithm.statGateViolations`, which joins the catalog's first.
 */
fun statGateViolations(
    build: BuildCombination,
    characterClass: CharacterClass?,
): List<StatGateViolation> {
    if (build.equipments.none { it.statGates.isNotEmpty() }) return emptyList()
    val sheet = outOfCombatSheet(build, characterClass)
    return build.equipments.flatMap { item ->
        item.statGates.mapNotNull { gate ->
            val actual = sheet[gate.sheetCharacteristic] ?: 0
            if (gate.holdsOn(actual)) null else StatGateViolation(item, gate, actual)
        }
    }
}
