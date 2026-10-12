package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpModel
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import me.chosante.autobuilder.domain.sheetCharacteristic
import me.chosante.autobuilder.domain.statGates
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ELEMENTARY_MASTERIES
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ELEMENTARY_RESISTANCES
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ItemType
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationKind

// RuneModelBuilder — the per-search rune CP-SAT modelling (single-type fold vs per-stat counts, socket caps,
// equipped-only gating) extracted from the WakfuBuildSolver object (B1 of docs/code-review-followups.md).

/**
 * Models runes as extra per-item allocatable stats. For each socketable equipped item and each
 * requested rune-coverable stat, an integer var counts how many runes of that stat sit in the
 * item's sockets; the per-item sum is capped at the item's socket count and forced to 0 when the
 * item is not equipped. The rune *value* per (stat, item slot, character level) is a constant
 * (best-achievable: max rune level + WakForge doubling), so runes plug straight into the stat term
 * loop in [StatBuilder.prePercentStat] and need no special-casing in the objective or scorer.
 */
internal fun CpModel.createRuneModel(
    params: WakfuBestBuildParams,
    allEquips: List<Equipment>,
    equipVars: Map<Equipment, IntVar>,
    runes: List<RuneType>,
    allowRuneFold: Boolean,
    // Stats a dangerous (≤/exact/parity) conditional sub reads (null = un-analyzable / forced). A modeled
    // rune feeding one of these makes most-masteries exact-fill unsound — see [fillSockets] below.
    subPinnedStats: Set<Characteristic>?,
    // Test seam: force the rune cap back to `≤` (no exact fill), for the exact-fill==≤ soundness lock.
    forceRuneLeq: Boolean,
    // What the max-damage model reads from a collapse-candidate rune ([maxDamageRuneReads]); read only by the
    // max-damage choice collapse.
    reads: MaxDamageRuneReads = MaxDamageRuneReads.NONE,
    // Test seams (the pruning-exactness lock): false keeps EVERY collapse candidate on every carrier (no Pareto
    // pruning), resp. posts no choice gate. Production keeps both on.
    choicePruning: Boolean = true,
    choiceGating: Boolean = true,
    // The rune types a single-type fill cannot represent exactly ([MaxDamageRuneReads.mixedStats]): a fold carrier
    // offering one of them beside another type gets per-type COUNTS instead of picks. Empty ⇒ the pure fold.
    mixedStats: Set<Characteristic> = emptySet(),
    // The mixed-rune HYBRID's fold stage ([WakfuBuildSolver.mixedRuneHybrid]): false keeps every carrier [mixedStats] would
    // make a count carrier on its single-type picks, and only RECORDS it ([RuneModel.foldInexactCarriers]) — the fast fold,
    // which knows it is not exact. (No count carrier then, so [aggregateCountClasses] has nothing to aggregate.)
    materializeCountCarriers: Boolean = true,
    // The hybrid's fold stage only (with [materializeCountCarriers] false): the reads its choice collapse prunes with — the
    // request's reads WITHOUT the item stat gates ([statGateRuneReads]), i.e. the ≤ v61 collapse. Every carrier whose kept
    // choices or choice gates differ from [reads]' is recorded in [RuneModel.foldInexactCarriers]: the stage is a search
    // heuristic whose builds are all real (every gate is still posted), never a proof. Null = prune with [reads].
    foldSearchReads: MaxDamageRuneReads? = null,
    // The SEARCH model: count carriers that share an item type, a socket count and their choices (same values) hold one
    // count per type for the whole class ([RuneCountClass], exact). Never in a model a certificate reads.
    aggregateCountClasses: Boolean = false,
): RuneModel {
    if (runes.isEmpty()) return RuneModel.EMPTY
    val runeById = runes.associateBy { it.id }
    val runeByCharacteristic = runes.associateBy { it.characteristic }

    // Global forced runes (CLI --forced-runes): "≥1 rune of this stat socketed somewhere".
    val forcedNames = params.forcedRunes.map { it.lowercase() }.toSet()
    val globalForcedRuneStats =
        runes
            .filter { it.name.fr.lowercase() in forcedNames || it.name.en.lowercase() in forcedNames }
            .map { it.characteristic }
            .toSet()

    // Per-item forced runes (GUI): pin a multiset of rune ids onto a specific carrier item. Keyed by
    // the item's French name (like forcedItems); resolve each id to its characteristic and count the
    // required runes per characteristic.
    val perItemForced: Map<String, Map<Characteristic, Int>> =
        params.forcedRunesByItem
            .mapKeys { (name, _) -> name.lowercase() }
            .mapValues { (_, ids) ->
                ids
                    .mapNotNull { runeById[it]?.characteristic }
                    .groupingBy { it }
                    .eachCount()
            }.filterValues { it.isNotEmpty() }
    val perItemForcedStats = perItemForced.values.flatMapTo(mutableSetOf()) { it.keys }

    val forcedRuneStats = globalForcedRuneStats + perItemForcedStats
    // Auto-fill runes only when enabled; forced runes are modeled regardless of that toggle.
    if (!params.useRunes && forcedRuneStats.isEmpty()) return RuneModel.EMPTY

    val runeStats =
        (if (params.useRunes) relevantRuneStats(params, runeByCharacteristic.keys) else emptySet()) + forcedRuneStats
    if (runeStats.isEmpty()) return RuneModel.EMPTY

    // Exact socket fill — pin `Σ runeCount = slots·selected` (instead of `≤`) so the proven optimum never
    // leaves a socket empty. It removes every never-optimal "underfill" assignment from the integer search
    // (a pure search-space cut that helps the proof close) AND fixes the "fewer than max runes" builds.
    //  - MAX-DAMAGE: the generic elemental-mastery rune is NOT a secondary mastery and only ever raises the
    //    damage objective, so it backfills any socket without tripping a secondary/AP/crit/range "at most" sub
    //    condition, and overshooting a required target is unpenalised (the score caps actual at target —
    //    coerceAtMost in FindMaxDamageScoring). So filling is always free. (Unchanged.)
    //  - MOST-MASTERIES: the objective is monotone non-decreasing in every modeled rune stat — masteries,
    //    shortfall-only required targets, the DI fold, the min-over-elements — so underfill is never optimal.
    //    The only risk is a dangerous (≤/exact/parity) conditional sub: forcing fill could push the stat it
    //    reads over the cap and flip the sub off. But the per-stat distribution is free (mixing preserved),
    //    so as long as ONE modeled rune stat is *not* read by a dangerous condition, every socket can be
    //    filled with that "safe filler" rune (HP, elemental, …) — monotone-beneficial and breaks no sub — so
    //    exact-fill keeps the optimum. It is unsound only when EVERY modeled rune stat is dangerous (a
    //    self-contradictory request: the sole rune-relevant stat is itself the capped one). [subPinnedStats]
    //    is the dangerous set (null ⇒ un-analyzable/forced ⇒ stay safe with `≤`). Locked sound by an
    //    adversarial soundness review + the exact-fill==≤ optimum test. The single-type FOLD below stays
    //    max-damage-only, so most-masteries keeps the integer-count model (intra-item rune MIXING preserved).
    val maxDamageFreeFill =
        params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE &&
            Characteristic.MASTERY_ELEMENTARY in runeStats
    val mostMasteriesExactFill =
        params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
            subPinnedStats != null &&
            runeStats.any { it !in subPinnedStats }
    val fillSockets = !forceRuneLeq && (maxDamageFreeFill || mostMasteriesExactFill)
    // Single-type-per-item rune FOLD (max-damage, no forced runes, no secondary-cap>0 sub in play —
    // [allowRuneFold]): because a rune's value is uniform across an item's sockets (doubling is per item
    // SLOT, not per socket — see RuneType.valueOn), filling an item entirely with its single best-value
    // type is ≥ any mix, so mixing types within ONE item is freedom the optimum never uses. Modelling each
    // item's choice as ONE boolean pick per type (Σ pick = selected) instead of an integer count 0..slots
    // collapses the rune search to binary decisions — far easier for CP-SAT to PROVE — with the SAME
    // reachable stat contributions (a pick contributes slots·coeff; see baseTermsFor + the 0..1 leaf seed).
    // The solver still chooses the type per item (build-dependent: mastery vs crit-mastery, elemental vs a
    // secondary for the secondary=0 subs) and items still differ — only the never-optimal intra-item mix is
    // dropped. Gated to where it is provably sound; forced runes / secondary-cap>0 subs keep the count model.
    // That premise holds only while the rune's every reader is LINEAR in its count (the damage objective). A
    // threshold read — a `secondary ≤ t` cap with a budget (an item's NEGATIVE line of that secondary, or t > 0), a
    // forced condition, a required row — can make a PART-fill optimal (3 rear runes absorbed by a −120-rear line, the
    // 4th socket elemental): such carriers keep per-type COUNTS ([mixedStats], CERTIFIER_VERSION 62).
    val singleTypePerItem = allowRuneFold && maxDamageFreeFill && forcedRuneStats.isEmpty()
    // The fold carriers modelled with per-type counts (0..slots each, Σ = slots·selected) instead of picks.
    val countCarriers = LinkedHashSet<Equipment>()
    // The carriers [mixedStats] would make count carriers, kept on picks ([materializeCountCarriers] false).
    val foldInexactCarriers = LinkedHashSet<Equipment>()
    val maxDamageMasteryRuneStats =
        if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
            buildSet {
                val scenario = params.damageScenario
                // Intentionally NOT scenarioMasteryStats(): runes route all elemental mastery through the
                // single GENERIC elemental-mastery rune (no per-element rune exists), so the specific
                // element mastery is deliberately omitted here.
                add(Characteristic.MASTERY_ELEMENTARY)
                add(scenario.rangeBand.masteryCharacteristic)
                if (scenario.orientation.grantsRearMastery) add(Characteristic.MASTERY_BACK)
                if (scenario.berserk) add(Characteristic.MASTERY_BERSERK)
                if (scenario.healing) add(Characteristic.MASTERY_HEALING)
            }
        } else {
            emptySet()
        }
    val maxDamageRuneChoiceCollapse =
        singleTypePerItem &&
            params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE &&
            runeStats.all { it == Characteristic.MASTERY_CRITICAL || it in maxDamageMasteryRuneStats }
    val runeTypeByVar = HashMap<IntVar, RuneType>()
    val coefficientByVar = HashMap<IntVar, Long>()
    val extraTerms = mutableMapOf<Characteristic, MutableList<Term>>()
    val suppressedBy = HashMap<Pair<Equipment, Characteristic>, IntVar>()
    // Insertion-ordered: the gates are posted in this order (a hash order of IntVar keys would vary between JVMs).
    val choiceGates = LinkedHashMap<IntVar, Set<Sublimation>>()
    // The count carriers' vars → their socket count (a count var's gate is `count ≤ slots·Σ subVar`).
    val countVarSlots = HashMap<IntVar, Long>()
    val runeVars = mutableMapOf<Equipment, Map<Characteristic, IntVar>>()

    // Count carriers awaiting their class ([aggregateCountClasses]): the carrier, its choices (type → rune, per-socket
    // value) and its choice gates (type → subs).
    data class PendingCount(
        val equip: Equipment,
        val choices: Map<Characteristic, Pair<RuneType, Long>>,
        val gates: Map<Characteristic, Set<Sublimation>>,
    )
    val pendingCounts = mutableListOf<PendingCount>()
    for (equip in allEquips) {
        val slots = equip.maxShardSlots
        if (slots <= 0) continue
        if (maxDamageRuneChoiceCollapse) {
            // Pure max-damage reads a rune of these types through the damage objective (the M-feeding masteries —
            // elemental / range / rear / berserk / healing — all enter the same M sum; critical mastery is K) and
            // through whatever else [reads] lists (a sub condition's stat sum, a conversion source, …). Offer every
            // candidate type, minus each one another candidate on THIS carrier beats on every read
            // ([MaxDamageRuneReads.paretoChoices]): without such a read that leaves the best M-feeding rune and,
            // when larger, the crit one — the original collapse; a capped secondary read also keeps the cheaper
            // choices (elemental, a smaller secondary, crit) a budget can need, and — the cap holding EACH secondary on
            // its own — every secondary type on its own budget (an equal rear and distance rune are two choices).
            val candidates =
                (maxDamageMasteryRuneStats + Characteristic.MASTERY_CRITICAL).mapNotNull { stat ->
                    runeByCharacteristic[stat]?.let { rune -> RuneChoice(stat, rune, rune.valueOn(equip.itemType, equip.level).toLong()) }
                }
            val exactKept = if (choicePruning) reads.paretoChoices(candidates) else candidates
            if (exactKept.isEmpty()) continue
            val exactGates = if (choiceGating) reads.choiceGates(exactKept) else emptyMap()
            // The hybrid's fold stage prunes with [foldSearchReads] (no item stat gate) instead: a carrier whose choices or
            // choice gates that changes is recorded as fold-inexact, so the stage's OPTIMAL / INFEASIBLE is never trusted.
            val searchReads = foldSearchReads?.takeIf { !materializeCountCarriers && choicePruning }
            val kept = searchReads?.paretoChoices(candidates) ?: exactKept
            val choiceGatesOfKept =
                if (searchReads == null) {
                    exactGates
                } else if (choiceGating) {
                    searchReads.choiceGates(kept)
                } else {
                    emptyMap()
                }
            val narrowed = kept.map { it.stat } != exactKept.map { it.stat } || choiceGatesOfKept != exactGates
            val choices = LinkedHashMap<Characteristic, Pair<RuneType, Long>>()
            kept.forEach { choices[it.stat] = it.rune to it.value }

            // A carrier whose choices include a type a single-type fill cannot represent exactly ([mixedStats]: a
            // threshold read with a budget) keeps per-type COUNTS over its kept types: the per-socket Pareto argument
            // and the gates hold rune by rune, so pruning stays exact; only the all-or-nothing fill goes.
            val inexact = exactKept.size >= 2 && exactKept.any { it.stat in mixedStats }
            if ((inexact || narrowed) && !materializeCountCarriers) foldInexactCarriers += equip
            val mixed = inexact && materializeCountCarriers
            if (mixed && aggregateCountClasses) {
                countCarriers += equip
                pendingCounts += PendingCount(equip, LinkedHashMap(choices), choiceGatesOfKept)
                continue
            }
            val perStat =
                if (mixed) {
                    countCarriers += equip
                    val vars = choices.keys.associateWith { stat -> newIntVar(0, slots.toLong(), "runeCount_${equip.equipmentId}_${stat.name}") }
                    val capExpr = LinearExpr.newBuilder()
                    vars.values.forEach { capExpr.addTerm(it, 1L) }
                    capExpr.addTerm(equipVars.getValue(equip), -slots.toLong())
                    addEquality(capExpr.build(), 0L)
                    vars.values.forEach { countVarSlots[it] = slots.toLong() }
                    vars
                } else if (choices.size == 1) {
                    // The single surviving choice is forced whenever the item is equipped: substitute the
                    // equipment variable directly and skip a redundant rune bool + equality.
                    mapOf(choices.keys.single() to equipVars.getValue(equip))
                } else if (choices.size == 2 && choices.containsKey(Characteristic.MASTERY_CRITICAL)) {
                    // The M-feeding survivor rides the equipment var; the crit choice is a swap bool.
                    val masteryStat = choices.keys.first { it != Characteristic.MASTERY_CRITICAL }
                    val masteryChoice = choices.getValue(masteryStat)
                    val critVar = newBoolVar("runePick_${equip.equipmentId}_${Characteristic.MASTERY_CRITICAL.name}")
                    addLessOrEqual(critVar, equipVars.getValue(equip))
                    // M is the default rune on the equipment var; choosing crit suppresses that default.
                    extraTerms
                        .getOrPut(masteryStat) { mutableListOf() }
                        .add(Term(critVar, -masteryChoice.second * slots.toLong()))
                    suppressedBy[equip to masteryStat] = critVar
                    mapOf(
                        masteryStat to equipVars.getValue(equip),
                        Characteristic.MASTERY_CRITICAL to critVar
                    )
                } else {
                    val vars = choices.keys.associateWith { stat -> newBoolVar("runePick_${equip.equipmentId}_${stat.name}") }
                    val pickExpr = LinearExpr.newBuilder()
                    vars.values.forEach { pickExpr.addTerm(it, 1L) }
                    pickExpr.addTerm(equipVars.getValue(equip), -1L)
                    addEquality(pickExpr.build(), 0L)
                    vars
                }
            for ((stat, choice) in choices) {
                val v = perStat.getValue(stat)
                runeTypeByVar[v] = choice.first
                coefficientByVar[v] = choice.second
            }
            // A choice kept only because a CHOOSABLE conditional sub reads it (the elemental rune on a
            // secondary-best carrier: only a secondary cap prefers it) is useless while none of those subs is
            // taken — gate it on them (posted once the sub model exists, see [RuneModel.choiceGates]).
            for ((stat, subs) in choiceGatesOfKept) {
                val v = perStat.getValue(stat)
                if (v != equipVars.getValue(equip)) choiceGates[v] = subs
            }
            runeVars[equip] = perStat
        } else if (singleTypePerItem && !(materializeCountCarriers && runeStats.size >= 2 && runeStats.any { it in mixedStats })) {
            if (runeStats.size >= 2 && runeStats.any { it in mixedStats }) foldInexactCarriers += equip
            // One boolean per type; exactly one type fills all the item's sockets when equipped, none otherwise.
            val perStat = runeStats.associateWith { stat -> newBoolVar("runePick_${equip.equipmentId}_${stat.name}") }
            val pickExpr = LinearExpr.newBuilder()
            perStat.values.forEach { pickExpr.addTerm(it, 1L) }
            pickExpr.addTerm(equipVars.getValue(equip), -1L)
            addEquality(pickExpr.build(), 0L)
            runeVars[equip] = perStat
        } else {
            // The count model — or, under the general fold, a carrier offering a [mixedStats] type (exact: every type
            // counted, `= slots·selected` like the fold's picks).
            if (singleTypePerItem) countCarriers += equip
            if (singleTypePerItem && aggregateCountClasses) {
                val choices = LinkedHashMap<Characteristic, Pair<RuneType, Long>>()
                for (stat in runeStats) {
                    val rune = runeByCharacteristic.getValue(stat)
                    choices[stat] = rune to rune.valueOn(equip.itemType, equip.level).toLong()
                }
                pendingCounts += PendingCount(equip, choices, emptyMap())
                continue
            }
            val perStat = runeStats.associateWith { stat -> newIntVar(0, slots.toLong(), "rune_${equip.equipmentId}_${stat.name}") }
            // Sockets only count when the item is equipped: Σ runeCount {= max-damage | ≤ other modes} slots·selected.
            val capExpr = LinearExpr.newBuilder()
            perStat.values.forEach { capExpr.addTerm(it, 1L) }
            capExpr.addTerm(equipVars.getValue(equip), -slots.toLong())
            if (fillSockets) addEquality(capExpr.build(), 0L) else addLessOrEqual(capExpr.build(), 0L)
            runeVars[equip] = perStat
        }
    }
    // The count classes ([RuneCountClass]): one count per type per class, `Σ_t m_t = slots · Σ_c x_c`. Grouped in
    // allEquips order (the first carrier names the class), so the model is JVM-independent.
    val countClasses = mutableListOf<RuneCountClass>()
    val classGroups =
        pendingCounts.groupBy { pc ->
            Triple(pc.equip.itemType, pc.equip.maxShardSlots, pc.choices.map { (stat, choice) -> Triple(stat, choice.first.id, choice.second) })
        }
    for ((key, members) in classGroups) {
        val slots = key.second
        val first = members.first()
        // The most of them one build wears: two rings, one of any other type ([addBuildValidityConstraints]; the same
        // limit [StatBuilder.reachableSumDomain] reads).
        val maxWorn = minOf(members.size, if (key.first == ItemType.RING) 2 else 1)
        val hi = slots.toLong() * maxWorn
        val vars =
            first.choices.keys.associateWith { stat ->
                newIntVar(0, hi, "runeClass_${first.equip.equipmentId}_${slots}_${stat.name}")
            }
        val capExpr = LinearExpr.newBuilder()
        vars.values.forEach { capExpr.addTerm(it, 1L) }
        members.forEach { capExpr.addTerm(equipVars.getValue(it.equip), -slots.toLong()) }
        addEquality(capExpr.build(), 0L)
        for ((stat, choice) in first.choices) {
            val v = vars.getValue(stat)
            runeTypeByVar[v] = choice.first
            coefficientByVar[v] = choice.second
        }
        // Same kept choices ⇒ same gates on every member ([MaxDamageRuneReads.choiceGates] reads the choices only).
        for ((stat, subs) in first.gates) vars[stat]?.let { choiceGates[it] = subs }
        countClasses +=
            RuneCountClass(
                carriers = members.map { it.equip },
                slots = slots,
                vars = vars,
                coefficients = first.choices.mapValues { it.value.second },
                runeTypes = first.choices.mapValues { it.value.first },
                maxWorn = maxWorn
            )
    }
    // Global forced runes must be socketed at least once across the build.
    for (stat in globalForcedRuneStats) {
        val countExpr = LinearExpr.newBuilder()
        var any = false
        for ((_, perStat) in runeVars) {
            perStat[stat]?.let {
                countExpr.addTerm(it, 1L)
                any = true
            }
        }
        if (any) addGreaterOrEqual(countExpr.build(), 1L)
    }
    // Per-item forced runes: for each named carrier, the rune-count var(s) for the equipped item
    // matching that name must reach the required count. We sum over every same-named candidate (only
    // one can be equipped, and a non-equipped item's rune vars are pinned to 0 by the socket cap), so
    // this both pins the runes onto that item AND forces one such item to be equipped.
    for ((name, byCharacteristic) in perItemForced) {
        val matching = allEquips.filter { it.name.fr.lowercase() == name && it.maxShardSlots > 0 }
        if (matching.isEmpty()) continue
        for ((stat, count) in byCharacteristic) {
            val countExpr = LinearExpr.newBuilder()
            var any = false
            for (equip in matching) {
                runeVars[equip]?.get(stat)?.let {
                    countExpr.addTerm(it, 1L)
                    any = true
                }
            }
            if (any) addGreaterOrEqual(countExpr.build(), count.toLong())
        }
    }
    return RuneModel(
        runeByCharacteristic,
        runeVars,
        singleTypePerItem,
        runeTypeByVar,
        coefficientByVar,
        extraTerms,
        suppressedBy,
        maxDamageChoiceCollapse = maxDamageRuneChoiceCollapse,
        choiceGates = choiceGates,
        countCarriers = countCarriers,
        countVarSlots = countVarSlots,
        foldInexactCarriers = foldInexactCarriers,
        countClasses = countClasses
    )
}

/** One candidate rune type of the max-damage choice collapse on a carrier: its type and per-socket value there. */
internal data class RuneChoice(
    val stat: Characteristic,
    val rune: RuneType,
    val value: Long,
)

/**
 * Everything the max-damage CP-SAT model READS from a rune of a choice-collapse candidate type (the scenario's M-feeding
 * masteries and critical mastery — [createRuneModel]). A rune enters the model only through [StatBuilder]'s
 * `baseTermsFor` of its own characteristic, so its readers are the readers of that characteristic:
 *  - the damage objective `Graw = (400 + c)·M + 5c·K` ([perHitDamageScore]): M sums every [damageStats] stat at weight
 *    1 ([scenarioMasteryStats]), K is critical mastery, the crit rate c is clamped to [0, 100];
 *  - each modelled sub's build-static stat condition ([subConditionSpec]: EACH of its stats against a threshold, read on
 *    the pre-combat / first-turn sheet — runes included), one [Bound] PER STAT. A CHOOSABLE sub only restricts the
 *    build once taken (`subVar ≤ holds`), so the build prefers the satisfying side ([Side.LOWER] for `≤`, [Side.HIGHER]
 *    for `≥`); a FORCED sub's effect is gated by its condition and may be a malus, so its stats must match exactly
 *    ([Side.EXACT]). The Neutralité family's `each secondary mastery ≤ 0` reads EACH of the six secondaries — crit
 *    mastery included — on its own, so it tells every two of them apart: a rear rune can be absorbed by a −430-rear
 *    item where an equal distance rune cannot, so equal-valued distance / rear / crit runes are DIFFERENT choices (a
 *    sum would have made them one). Critical Secret reads critical mastery alone (HIGHEST_ELEM_MASTERY_GT_* are not
 *    solver-modelled: such a sub applies unconditionally);
 *  - a CONVERSION reads its source's pre-sub stat. Crit mastery → an M-feeding stat at ≤ 100 % (Dénouement) only moves
 *    part of a crit rune into M — never more than its value, so a rune feeding M directly by at least as much still
 *    wins (the moved part is worth (400 + c) in M against 5c in K, and the rest stays in K); any other source is
 *    [opaque];
 *  - a per-stat-step ramp's source, a %-skill's stat, a required target row's stat, the per-element masteries a best-
 *    element concentration compares: [opaque] (never pruned, never pruning — none of them is a candidate today);
 *  - an item STAT GATE of the pool (`x_item ⇒ outOfCombatStat ⋚ value`, AGENTS.md §4 "Item equip conditions": the
 *    out-of-combat sheet counts runes) — distance mastery, lock and dodge in the data: [opaque] and a threshold
 *    ([mixedStats]). A gate on distance mastery reads the distance rune where the elemental one is free, so the
 *    elemental choice must not fall to it (the wearer of the gated item may need it) and a part-fill can meet the gate.
 */
internal class MaxDamageRuneReads(
    private val damageStats: Set<Characteristic>,
    private val bounds: List<Bound>,
    private val opaque: Set<Characteristic>,
    // The sources of a modelled conversion that is NOT opaque (crit mastery → an M-feeding stat at ≤ 100 %): a rune of
    // such a type is worth something to the objective even while a `≤ 0` cap holds its own stat at or below 0.
    private val convertedSources: Set<Characteristic> = emptySet(),
    // The opaque types whose reader is NOT linear in the rune count — a per-stat-step ramp's source, a best-element
    // concentration's masteries: a part-fill can cross one of their thresholds.
    private val thresholdReads: Set<Characteristic> = emptySet(),
    // The required target rows (stat → target): the shortfall penalty / the hard leg's `actual ≥ target` is a threshold.
    private val rowTargets: Map<Characteristic, Long> = emptyMap(),
) {
    internal enum class Side { LOWER, HIGHER, EXACT }

    /**
     * One stat of a modelled condition: `stat ⋚ threshold` (a multi-stat condition — the Neutralité family's six
     * secondaries — is one [Bound] per stat, each held on its own), the side the build prefers, and its CHOOSABLE sub
     * (null when forced). [comparison] / [threshold] are the condition's own (read by [mixedStats]).
     */
    internal data class Bound(
        val stat: Characteristic,
        val side: Side,
        val choosableSub: Sublimation?,
        val comparison: ConditionComparison = ConditionComparison.AT_MOST,
        val threshold: Long = 0L,
    )

    /**
     * The rune types a single-type fill can NOT represent exactly (CERTIFIER_VERSION 62): a carrier offering one of them
     * beside another type gets per-type counts ([createRuneModel]). The fold's premise — fill an item with its best type
     * — holds while every reader of a rune is linear in its count: the damage objective is (`Graw` is linear in M and
     * K at a fixed crit rate, convex through its `max(0, ·)` clamps, so a vertex — one type — maximises it over a
     * carrier's fills). A THRESHOLD read breaks it:
     *  - a CHOOSABLE `stat ≤ t` cap (the Neutralité family, Critical Secret) while it is taken leaves the stat's runes a
     *    BUDGET — `t` minus everything else on the read — that a part-fill can match where a whole item overshoots:
     *    rear 3 × 33 absorbed by an item's −120 rear line, the 4th socket elemental. The budget can be positive only if
     *    `t > 0` or some source can make the stat NEGATIVE ([negativeSources]: an item line, a sub effect — skills and
     *    the base never are); with neither, a taken cap holds every rune of the stat at 0 and the other types stay
     *    linear. The runes the budget buys must also be WORTH something: an M-feeding type always is; critical
     *    mastery while a `≤ t ≤ 0` cap holds it is clamped to 0 in `Graw` (`5c·max(0, K)`) unless a conversion moves it
     *    into M ([convertedSources]) or t > 0. While the cap is not taken it restricts nothing: the fill is linear;
     *  - a FORCED condition (its effect gated by the condition, possibly a malus) or a non-`≤` comparison: the build may
     *    want to cross the threshold from either side — always mixed;
     *  - [thresholdReads]: a ramp's steps, a per-element compare, an item stat gate's stat;
     *  - a required target row (`actual ≥ target` on the hard leg, the shortfall penalty on the soft one; a 0-target row
     *    is a floor) whose target is positive or whose stat a source can make negative — else every build meets it
     *    whatever its runes. Keyed per rune type: an "all resistances" row reads the four per-element resistances;
     *  - the survivability floor (HP and the four resistances), a non-crit conversion's source (its rounding step).
     * The set is exact for the candidate types of the collapse and for the general fold's types alike: a stat no
     * threshold reads is one more linear read.
     */
    fun mixedStats(negativeSources: Set<Characteristic>): Set<Characteristic> {
        val out = LinkedHashSet<Characteristic>(thresholdReads)
        for ((stat, target) in rowTargets) if (target > 0 || stat in negativeSources) out += stat
        for (b in bounds) {
            val s = b.stat
            if (b.choosableSub == null || b.comparison != ConditionComparison.AT_MOST) {
                out += s
                continue
            }
            val budget = b.threshold > 0 || s in negativeSources
            val worth = s in damageStats || b.threshold > 0 || s in convertedSources || s in opaque
            if (budget && worth) out += s
        }
        return out
    }

    /**
     * Whether choice [q] is at least as good as choice [p] ON THE SAME CARRIER (same socket count) for every read
     * except those of [ignored] (choosable subs assumed not taken): then swapping [p] for [q] in any build keeps every
     * condition of a taken sub satisfied and never lowers the objective. Objective: two M-feeding runes compare by
     * value; an M-feeding rune of at least the crit rune's value beats it at every crit rate (∂Graw/∂M = 400 + c ≥ 5c =
     * ∂Graw/∂K) — the dominance the original collapse relied on, tied to [perHitDamageScore]'s coefficients and to its
     * M ≥ 0 / K ≥ 0 clamps (re-verify both before changing either); a crit rune never beats an M-feeding one (c = 0).
     */
    fun dominates(
        q: RuneChoice,
        p: RuneChoice,
        ignored: Set<Sublimation> = emptySet(),
    ): Boolean {
        if (q.stat == p.stat || q.stat in opaque || p.stat in opaque) return false
        val objective = q.stat in damageStats && (p.stat in damageStats || p.stat == Characteristic.MASTERY_CRITICAL) && q.value >= p.value
        return objective &&
            bounds.all { b ->
                val sub = b.choosableSub
                (sub != null && sub in ignored) || b.holdsFor(q, p)
            }
    }

    private fun Bound.read(c: RuneChoice): Long = if (c.stat == stat) c.value else 0L

    private fun Bound.holdsFor(
        q: RuneChoice,
        p: RuneChoice,
    ): Boolean =
        when (side) {
            Side.LOWER -> read(q) <= read(p)
            Side.HIGHER -> read(q) >= read(p)
            Side.EXACT -> read(q) == read(p)
        }

    /**
     * The Pareto set of [candidates] (in their order): a choice is dropped iff another one dominates it ([dominates])
     * and is either strictly better or an exact tie listed EARLIER — one deterministic representative per tie (the
     * relation is a preorder, so every dropped choice has a kept dominator).
     */
    fun paretoChoices(
        candidates: List<RuneChoice>,
        ignored: Set<Sublimation> = emptySet(),
    ): List<RuneChoice> =
        candidates.filterIndexed { i, p ->
            candidates.withIndex().none { (j, q) ->
                j != i && dominates(q, p, ignored) && (j < i || !dominates(p, q, ignored))
            }
        }

    /**
     * For each [kept] choice that only a CHOOSABLE sub's condition keeps (it falls to a kept choice once every choosable
     * conditional sub is assumed not taken), those subs: while none of them is taken the choice is dominated by a choice
     * that is never gated, so `choice ≤ Σ subVar` removes no optimum. The dominator is taken from the Pareto set of
     * [kept] under that assumption (non-empty; every gated choice has a dominator there, transitivity).
     */
    fun choiceGates(kept: List<RuneChoice>): Map<Characteristic, Set<Sublimation>> {
        val choosable = bounds.mapNotNullTo(HashSet()) { it.choosableSub }
        if (choosable.isEmpty() || kept.size < 2) return emptyMap()
        val ungated = paretoChoices(kept, choosable)
        // Ordered (the candidates' order, then the bounds' — the modelled subs' — order): the gates and their sums are
        // posted as iterated, and the deterministic solve protocol needs a JVM-independent model.
        val gates = LinkedHashMap<Characteristic, Set<Sublimation>>()
        for (p in kept) {
            if (p in ungated) continue
            val d = ungated.firstOrNull { dominates(it, p, choosable) } ?: continue
            // The subs whose condition prefers p to d: d dominates p once all of them are untaken. Non-empty, since
            // both are kept (d does not dominate p on every read).
            val subs = bounds.mapNotNullTo(LinkedHashSet()) { b -> b.choosableSub?.takeIf { !b.holdsFor(d, p) } }
            if (subs.isNotEmpty()) gates[p.stat] = subs
        }
        return gates
    }

    companion object {
        /** No reader beyond the objective (most-masteries / precision never build the collapse). */
        val NONE = MaxDamageRuneReads(emptySet(), emptyList(), emptySet())
    }
}

/**
 * The max-damage model's reads of the choice-collapse candidate runes (see [MaxDamageRuneReads]), from the sublimations
 * the model actually builds ([modelledSublimations]) — not from the domination shape, which also gives up on requests
 * (forced items, …) that model no capping condition at all. [percentSkillStats] = the characteristics a %-skill scales.
 */
internal fun maxDamageRuneReads(
    params: WakfuBestBuildParams,
    sublimations: List<Sublimation>,
    percentSkillStats: Set<Characteristic>,
    // The rune keys the pool's item stat gates read ([statGateRuneReads]).
    gatedStats: Set<Characteristic> = emptySet(),
): MaxDamageRuneReads {
    if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return MaxDamageRuneReads.NONE
    val damageStats = scenarioMasteryStats(params.damageScenario).toSet()
    val bounds = mutableListOf<MaxDamageRuneReads.Bound>()
    val opaque = HashSet<Characteristic>(percentSkillStats)
    val converted = HashSet<Characteristic>()
    val thresholds = HashSet<Characteristic>()
    val rows = LinkedHashMap<Characteristic, Long>()
    params.targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() }.forEach { row ->
        // Keyed like the rune types ([relevantRuneStats]): an "all resistances" row (max-damage keeps it unsplit) reads
        // each per-element resistance rune.
        for (stat in runeKeysOf(row.characteristic)) {
            opaque += stat
            rows[stat] = maxOf(rows[stat] ?: Long.MIN_VALUE, row.target.toLong())
        }
    }
    // The survivability floor reads HP and the four resistances through `min(EHP, floor)` and a bucket table: a
    // threshold on each of them (see [StatBuilder]'s effective-HP proxy).
    val scenario = params.damageScenario
    if (scenario.survivabilityFloor && scenario.minEffectiveHp > 0) {
        thresholds += Characteristic.HP
        thresholds += ELEMENTARY_RESISTANCES
    }
    // An item stat gate reads its stat on the out-of-combat sheet, runes included, against a threshold (either side): it
    // never prunes nor is pruned (a choice reading less of it can be what lets the gated item be worn), and a part-fill can
    // be what meets it.
    opaque += gatedStats
    thresholds += gatedStats
    val (forced, choosable) = modelledSublimations(params, sublimations)
    for (sub in forced + choosable) {
        // A combat-conditional sub is never credited, so its condition is never reified (buildSublimationTerms).
        if (sub.kind == SublimationKind.COMBAT_CONDITIONAL) continue
        val spec = subConditionSpec(sub.condition, params.character.level)
        if (spec is SubConditionSpec.StatBound) {
            val isForced = sub in forced
            val side =
                when {
                    isForced -> MaxDamageRuneReads.Side.EXACT
                    spec.comparison == ConditionComparison.AT_MOST -> MaxDamageRuneReads.Side.LOWER
                    spec.comparison == ConditionComparison.AT_LEAST -> MaxDamageRuneReads.Side.HIGHER
                    else -> MaxDamageRuneReads.Side.EXACT
                }
            // EACH stat is its own bound (the Neutralité family holds every secondary mastery ≤ t separately, never
            // their sum): a choice dominates another only if it reads no more of ANY of them.
            for (stat in spec.stats.distinct()) {
                bounds += MaxDamageRuneReads.Bound(stat, side, if (isForced) null else sub, spec.comparison, spec.threshold.toLong())
            }
        }
        sub.conversion?.let { conversion ->
            val critIntoDamage =
                conversion.from == Characteristic.MASTERY_CRITICAL &&
                    conversion.to.foldedToUsableStat() in damageStats &&
                    conversion.percent in 0..100
            if (critIntoDamage) {
                converted += conversion.from
            } else {
                // Any other conversion moves `floor(pct · stat)`: a rounding step a part-fill can land on.
                opaque += conversion.from
                thresholds += conversion.from
            }
        }
        sub.perStatStep?.let {
            opaque += it.source
            thresholds += it.source
        }
        if (sub.bestElementConcentration != null) {
            opaque += ELEMENT_MASTERY_CHARACTERISTICS
            thresholds += ELEMENT_MASTERY_CHARACTERISTICS
        }
    }
    return MaxDamageRuneReads(damageStats, bounds, opaque, converted, thresholds, rows)
}

/**
 * The characteristics some source of this request can make NEGATIVE on the sheet a modelled condition reads — an item
 * of [allEquips] (already resolved to the wearer's level) or a modelled sub's stat effect (every timing and gate: a
 * superset). Skills, runes and the base never are. Read by [MaxDamageRuneReads.mixedStats]: a `≤ t ≤ 0` cap leaves a
 * stat's runes a budget only if one of these exists.
 */
internal fun negativeStatSources(
    allEquips: List<Equipment>,
    sublimations: List<Sublimation>,
    characterLevel: Int,
): Set<Characteristic> {
    val out = HashSet<Characteristic>()
    for (equip in allEquips) {
        for ((stat, value) in equip.characteristics) if (value < 0) out += negativeTargets(stat)
    }
    for (sub in sublimations) {
        for (effect in sub.effects.filterIsInstance<me.chosante.common.SublimationEffect.StatEffect>()) {
            // Any family level a stacked / forced-level sub can take (CERTIFIER_VERSION 61: its value is read per level).
            if (effect.magnitudeAtLevel(characterLevel) < 0 || effect.valuesByLevel.any { it < 0 }) out += negativeTargets(effect.characteristic)
        }
    }
    return out
}

/**
 * The per-element stats a negative line of [stat] can lower: an aggregate or random-element resistance (mastery) line
 * lands on the four elements (16 catalog items carry a negative "all resistances" line), any other line on its own
 * (MAX_* folded) stat.
 */
private fun negativeTargets(stat: Characteristic): Set<Characteristic> =
    when (stat) {
        Characteristic.RESISTANCE_ELEMENTARY,
        Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT,
        Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT,
        Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT,
        -> ELEMENTARY_RESISTANCES.toSet() + stat
        Characteristic.MASTERY_ELEMENTARY,
        Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT,
        Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT,
        Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT,
        -> ELEMENTARY_MASTERIES.toSet() + stat
        else -> setOf(stat.foldedToUsableStat())
    }

/**
 * The rune keys the item STAT GATES of [allEquips] read (`GetCharac("MASTERY_DISTANCE") <= 150`, lock / dodge bands, …;
 * read on the out-of-combat sheet, runes included — [StatBuilder.outOfCombatStat]). Every gate counts, whether or not it can
 * fail: a superset only keeps more choices and more count carriers, both exact. [allEquips] is the model's own pool (after the
 * prefilter and domination: an evicted gated item reads nothing).
 *
 * Narrowing it to the gates that can fail was measured and changes nothing (2026-10-12): every catalog gate on a rune stat
 * (distance mastery ≥ 400 / ≤ 500, lock ≥ 400 / ≥ 500 / ≤ 500 / ≤ 600, dodge ≥ 500 / ≤ 600) is posted by the model on the
 * level-200 / 245 pools that hold it (its reach crosses the value: [StatBuilder.applyItemStatGates]), and the gates that can
 * never fail (the 89 `critical hit > −10`) read no rune type. Both sides of the distance band are in those pools, so a side-aware
 * read would be the same as opaque. On requests with non-damage rune rows (the GUI default) the general fold offers every
 * type on every carrier and reads nothing here; the only model it grows is the free collapse (MD245F: 28,107 → 31,049 vars),
 * and the hybrid's fold stage prunes without it ([createRuneModel]'s `foldSearchReads`).
 */
internal fun statGateRuneReads(allEquips: List<Equipment>): Set<Characteristic> =
    allEquips.flatMapTo(LinkedHashSet()) { equip -> equip.statGates.flatMap { runeKeysOf(it.sheetCharacteristic) } }

/** The rune types a target row on [stat] reads — the same keys as [relevantRuneStats]. */
private fun runeKeysOf(stat: Characteristic): List<Characteristic> =
    when (stat) {
        Characteristic.RESISTANCE_ELEMENTARY -> ELEMENTARY_RESISTANCES.toList()
        Characteristic.MASTERY_ELEMENTARY, in ELEMENTARY_MASTERIES -> listOf(Characteristic.MASTERY_ELEMENTARY)
        else -> listOf(stat)
    }

/**
 * The rune-coverable stats worth modelling for this request: requested stats that have a rune.
 * Elemental masteries (specific or generic) all route to the single generic elemental-mastery rune
 * (there is no per-element mastery rune); the aggregate resistance request expands to the four
 * per-element resistance runes. Mirrors the elemental folding the scorers/solver already do. Also read by the
 * domination pre-filter's rune contract ([dominationShape]) with every rune-able characteristic.
 */
internal fun relevantRuneStats(
    params: WakfuBestBuildParams,
    runeCharacteristics: Set<Characteristic>,
): Set<Characteristic> {
    val result = mutableSetOf<Characteristic>()
    for (targetStat in params.targetStats) {
        when (val characteristic = targetStat.characteristic) {
            Characteristic.MASTERY_ELEMENTARY, in ELEMENTARY_MASTERIES -> result.add(Characteristic.MASTERY_ELEMENTARY)
            Characteristic.RESISTANCE_ELEMENTARY -> result.addAll(ELEMENTARY_RESISTANCES)
            else -> result.add(characteristic)
        }
    }
    // Max-damage mode socket-fills the masteries that drive the scenario's damage, even when they
    // are not in targetStats (which there only carry hard AP/MP/range/… constraints).
    if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
        val scenario = params.damageScenario
        // Intentionally NOT scenarioMasteryStats(): the rune-relevant set omits the specific element
        // mastery (generic-rune routing) and adds MASTERY_CRITICAL (a crit rune does exist).
        result.add(Characteristic.MASTERY_ELEMENTARY)
        result.add(scenario.rangeBand.masteryCharacteristic)
        result.add(Characteristic.MASTERY_CRITICAL)
        if (scenario.orientation.grantsRearMastery) result.add(Characteristic.MASTERY_BACK)
        if (scenario.berserk) result.add(Characteristic.MASTERY_BERSERK)
        if (scenario.healing) result.add(Characteristic.MASTERY_HEALING)
    }
    return result.intersect(runeCharacteristics)
}
