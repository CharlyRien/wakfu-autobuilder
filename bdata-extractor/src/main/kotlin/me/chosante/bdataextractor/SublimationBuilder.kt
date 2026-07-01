package me.chosante.bdataextractor

import me.chosante.common.BestElementConcentration
import me.chosante.common.Characteristic
import me.chosante.common.PerStatStepSpec
import me.chosante.common.ScenarioGate
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationConversion
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import kotlin.math.floor

/*
 * Builds the `sublimations.json` engine resource entirely from **first-party** sources — no WakForge /
 * noredlace / hand-curated inputs:
 *  - **metadata** (identity, name, rarity, slot colours, Zenith id) from the CDN `items.json` ([ItemsCatalog]);
 *  - **effects / condition / max level** decoded from the local client's bdata State (67) → StaticEffect (68)
 *    tables, resolved through the same [ActionCatalog] the passives use, plus a small sublimation-local action
 *    overlay ([SUB_ACTION_OVERRIDE]) for the value actions Ankama ships with no `actions.json` description.
 *
 * **Best-achievable model:** a chosen sublimation contributes its top value `floor(base + inc·maxTier)` (with
 * the action's sign), where `maxTier` (1..3) is the real best-achievable level carried by the sublimation ITEM
 * ([SublimationMeta.maxTier]) — NOT the State table's theoretical `max_level` (often 6). Verified against
 * in-game tooltips (Heavy Armor 5·2 = 10% at tier 2, Influence 3·3 = 9 at tier 3). This replaces the old
 * `maxLevel-1`-based value, which over-stated high-`maxLevel` subs and under-stated low-`maxLevel` ones.
 * A sublimation is [SublimationKind.solverChoosable] only
 * when every one of its effects is a cleanly-modelable static stat under a recognized build-static condition —
 * the conservative rule that keeps the solver-choosable set to the audited minimum (see
 * AGENTS.md §5): any trigger/proc, applied-state, unmapped/non-flat action, or
 * unrecognized criterion atom drops the whole sublimation to forced-input-only.
 *
 * A sublimation **is** a State (table 67) keyed by its `stateId`; its `effect_ids` strict subtree (effect_ids
 * + their `parent_id` descendants) holds the StaticEffect records. Build-static **conditions** ("at the start
 * of combat, if …") and per-scenario **gates** (berserk / ranged / min level) live in those effects'
 * `effect_criterion` scripts ([parseCriterion]); a condition holds for the whole sublimation, while a gate
 * attaches to the specific effect it (or an ancestor group) guards.
 */

/**
 * Value actions resolved sublimation-locally instead of via [ActionCatalog]. Either Ankama ships them with a
 * `null` `actions.json` description (so the catalog can't map them — the "declarative siblings" of the 330
 * group executor), or the `[#charac]` token discards a distinction the catalog needs (56/57 are "max AP/MP",
 * but the token is plain `AP`/`MP`). Kept here, NOT in the shared catalog, so `spell-passives.json` is
 * unaffected. Pair = (characteristic, sign).
 */
private val SUB_ACTION_OVERRIDE: Map<Int, Pair<Characteristic, Int>> =
    mapOf(
        126 to (Characteristic.DAMAGE_INFLICTED to 1), // % damage inflicted (gain)
        129 to (Characteristic.DAMAGE_INFLICTED to -1), // % damage inflicted (loss)
        1093 to (Characteristic.DAMAGE_INFLICTED to 1), // % damage inflicted, conditionally applied (on-crit / berserk)
        178 to (Characteristic.WILLPOWER to -1), // willpower (loss); 177 (gain) is described, the catalog maps it
        56 to (Characteristic.MAX_ACTION_POINT to -1), // "-N PA max"
        57 to (Characteristic.MAX_MOVEMENT_POINT to -1) // "-N PM max"
    )

private const val ACTION_APPLY_STATE = 304 // applies another state — a combat mechanic, never solver-clean

/**
 * Actions for clean PERMANENT build stats Ankama ships that our [Characteristic] model has no entry for — so we
 * can't credit them, but they are NOT combat scripts, so they must NOT force the whole sublimation to
 * forced-input-only. Recognized here and **skipped** (contribute nothing, don't dirty).
 *  - 1095 = "% heals performed" (the heal analogue of % damage inflicted, action 126) — verified against the
 *    in-game Inflexibility II tooltip ("+20% Damage inflicted, +20% Heals performed").
 */
private val UNMODELED_STAT_ACTIONS = setOf(1095)

// "X% of level as <stat>" — a meta-action whose CHILD effect(s) name the stat(s) (lock/dodge/elemental mastery
// …). The % is the lone param > 1; the magnitude is resolved against the character's level at solve time
// ([SublimationEffect.PercentOfLevel]). (Was mis-modeled as a flat LOCK regardless of the real child stat.)
private const val ACTION_LEVEL_PERCENT = 999

// Ankama's action-913 = a "derived stat" formula (a linear ramp / conversion of one build stat into another).
// Its param block is not a plain stat, so it normally dirties the sub; only the specific linear-ramp shape is
// decoded (see [decodePerStatStepRamp] → [PerStatStepSpec]). Every other 913 (conversions / best-element) stays
// forced-only.
private const val ACTION_DERIVED_RAMP = 913

// Per-element (+ generic) elemental-mastery LOSS actions — the "Perte Maîtrise <element>" mirror of the gains
// 122/123/124/125 (only 130 generic + 132 fire are described in `actions.json`; 133/134/135 = earth/water/air
// loss are undescribed). Used to recognize the "best element" concentration penalty branches ([decodeBest…]).
private val ELEM_LOSS_ACTIONS = setOf(130, 132, 133, 134, 135)

// The "+% damage inflicted" gain (its loss twin is 129). The unconditional signature of the best-element
// concentration sub (Elemental Concentration): a +126 sitting outside the per-element penalty fan-out.
private const val ACTION_DAMAGE_INFLICTED_GAIN = 126

// The "set elemental mastery to 0" actions (Chaos: "elemental masteries are set to 0"), params [0, 0] and absent
// from `actions.json` (no catalog description). Five of them (the four elements + the generic mastery). Recognized
// here to set the sublimation's `zeroesElementalMastery` flag instead of dirtying it — the solver zeroes the
// scenario element's mastery when the sub is picked (max-damage only). See [Sublimation.zeroesElementalMastery].
private val ELEM_ZERO_ACTIONS = setOf(560, 561, 562, 563, 566)

fun buildSublimations(
    states: Table,
    effects: Table,
    actions: ActionCatalog,
    meta: List<SublimationMeta>,
    characIds: CharacIdCatalog,
): List<Sublimation> {
    @Suppress("UNCHECKED_CAST")
    fun intList(v: Any?): List<Int> = (v as List<Any?>).map { it as Int }

    val stateById = states.records.associateBy { it["id"] as Int }
    val byEffectId = effects.records.associateBy { it["effect_id"] as Int }
    val timingFilter = subTimingFilter()
    val childrenOf = HashMap<Int, MutableList<Int>>()
    for (r in effects.records) childrenOf.getOrPut(r["parent_id"] as Int) { mutableListOf() }.add(r["effect_id"] as Int)

    fun strictSubtree(effectIds: List<Int>): List<Int> {
        val seen = HashSet<Int>()
        val out = ArrayList<Int>()
        val stack = ArrayDeque(effectIds)
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            if (x in seen || x !in byEffectId) continue
            seen.add(x)
            out.add(x)
            childrenOf[x]?.let { stack.addAll(it) }
        }
        return out
    }

    // First-party decode of a "per point of <source> above <threshold>, +<target> capped at <cap>" ramp
    // (Featherweight) from Ankama's action-913 formula — or null if [subtree] isn't that exact shape. Heavily
    // GUARDED: the source id resolves via [CharacIdCatalog], slope>0 & intercept<0 (a ramp rising above a positive
    // threshold), an integer threshold, a positive cap. Every other 913 sub (conversions / best-element, which
    // have several 913 branches or a zero intercept) fails a guard → stays forced-only. Returns the spec plus the
    // effect ids it consumes (the 913 + its child action-126) so the caller skips them in the effect loop below.
    // ⚠️ Positional (the 913 layout shifts between formula types); pinned by a reproduction test so drift fails loudly.
    fun decodePerStatStepRamp(
        subtree: List<Int>,
        subtreeSet: Set<Int>,
        maxTier: Int,
    ): Pair<PerStatStepSpec, Set<Int>>? {
        val rampId = subtree.singleOrNull { byEffectId.getValue(it)["action_id"] == ACTION_DERIVED_RAMP } ?: return null

        @Suppress("UNCHECKED_CAST")
        val params = (byEffectId.getValue(rampId)["params"] as List<Any?>).map { (it as Float).toDouble() }
        if (params.size <= 19) return null
        val source = characIds.characteristicFor(params[12].toInt()) ?: return null
        val interceptPerTier = params[17]
        val slopePerTier = params[19]
        if (slopePerTier <= 0.0 || interceptPerTier >= 0.0) return null

        // Child action-126 carry the target stat (DAMAGE_INFLICTED, via the overlay) and the cap value.
        val capChildren = childrenOf[rampId].orEmpty().filter { it in subtreeSet && byEffectId.getValue(it)["action_id"] == 126 }
        val target = SUB_ACTION_OVERRIDE[126]?.first ?: return null
        val cap =
            capChildren.maxOfOrNull { cid ->
                @Suppress("UNCHECKED_CAST")
                val p = (byEffectId.getValue(cid)["params"] as List<Any?>).map { (it as Float).toDouble() }
                floor(p.getOrElse(0) { 0.0 } + p.getOrElse(1) { 0.0 } * maxTier).toInt()
            } ?: return null
        if (cap <= 0) return null

        val perStep = floor(slopePerTier * maxTier).toInt()
        // Threshold is tier-independent (slope & intercept both scale by maxTier): -intercept/slope. Require exact.
        val thresholdExact = -interceptPerTier / slopePerTier
        val threshold = thresholdExact.toInt()
        if (perStep <= 0 || threshold < 0 || threshold.toDouble() != thresholdExact) return null

        return PerStatStepSpec(source, threshold, perStep, cap, target) to (setOf(rampId) + capChildren)
    }

    // First-party decode of a "convert 100% of <source> into <target>" 913 sub (Unraveling: crit mastery →
    // elemental mastery, gated by its CRIT_AT_LEAST condition), reusing the model's dormant [SublimationConversion]
    // path. SCOPED to conversions FROM crit mastery (`source == MASTERY_CRITICAL`) so it can't mis-match the
    // best-element 913s (several 913 branches) or a Block→Crit conversion whose "Block > Crit" gate the model can't
    // evaluate — those stay forced-only. Full (100%) conversion, the only shape seen; pinned by a reproduction test.
    // Returns the conversion plus the effect ids it consumes (the 913 + its children) so the caller skips them below.
    fun decodeConversion913(
        subtree: List<Int>,
        subtreeSet: Set<Int>,
    ): Pair<SublimationConversion, Set<Int>>? {
        val convId = subtree.singleOrNull { byEffectId.getValue(it)["action_id"] == ACTION_DERIVED_RAMP } ?: return null

        @Suppress("UNCHECKED_CAST")
        val params = (byEffectId.getValue(convId)["params"] as List<Any?>).map { (it as Float).toDouble() }
        if (params.size <= 12) return null
        val source = characIds.characteristicFor(params[12].toInt()) ?: return null
        if (source != Characteristic.MASTERY_CRITICAL) return null

        // The target is the single child action resolving to a build stat other than the source.
        fun statOf(childId: Int): Characteristic? {
            val a = byEffectId.getValue(childId)["action_id"] as Int
            return SUB_ACTION_OVERRIDE[a]?.first ?: (actions.kind(a) as? ActionKind.Stat)?.characteristic
        }
        val children = childrenOf[convId].orEmpty().filter { it in subtreeSet }
        val target =
            children
                .mapNotNull { statOf(it) }
                .filter { it != source }
                .distinct()
                .singleOrNull() ?: return null

        return SublimationConversion(from = source, to = target, percent = 100) to (setOf(convId) + children)
    }

    // First-party decode of the "best element" concentration sub (Elemental Concentration: +20% Damage Inflicted,
    // −30% mastery on the three weakest elements) → a [BestElementConcentration] (see that type for the sound
    // damage-mode model). Signature: an UNCONDITIONAL +DI (action 126, not under an element gate, not in a dead
    // `False` branch) together with a four-way "my <element> ≥ every other element" fan-out ([CritAtom.ElementGate]
    // covering ALL FOUR elements) whose branches carry per-element mastery LOSSES. A single-element gate set is a
    // per-element-damage sub (Brûlure/…) handled by [familyElement], not this. Guarded so the penalty is strong
    // enough that a non-strongest damage element makes the sub strictly worse ((100−p)·(100+d) < 100²) — the
    // property the sound model relies on. Returns the spec + every effect id it consumes (the +DI + the whole
    // element-gated fan-out) so the caller skips them (no dirty, no bogus flats). The per-branch 913 general-mastery
    // redistribution is deliberately dropped: the tooltip-faithful net is "−30% on the weak elements", which the
    // strongest-guard makes moot for the scored element. ⚠️ Pinned by a reproduction test.
    fun decodeBestElementConcentration(
        subtree: List<Int>,
        subtreeSet: Set<Int>,
        maxTier: Int,
    ): Pair<BestElementConcentration, Set<Int>>? {
        fun atomsOf(eid: Int): List<CritAtom> = parseCriterion(byEffectId.getValue(eid)["effect_criterion"] as String)

        // Self + ancestor groups within the subtree (stops at the state root / a self-parent).
        fun chain(startId: Int): Sequence<Int> =
            generateSequence(startId) { cur ->
                (byEffectId.getValue(cur)["parent_id"] as Int).takeIf { it != cur && it in subtreeSet }
            }

        fun underElementGate(eid: Int): Boolean = chain(eid).any { anc -> atomsOf(anc).any { it is CritAtom.ElementGate } }

        fun isDead(eid: Int): Boolean =
            chain(eid).any { anc ->
                atomsOf(anc).any { it is CritAtom.FalseGate || it is CritAtom.TargetGate || it is CritAtom.AntiStackGate }
            }

        // The best-element fan-out must cover ALL FOUR elements (a single-element set is a per-element-damage sub).
        val gatedElements =
            subtree
                .asSequence()
                .filterNot { isDead(it) }
                .flatMap { atomsOf(it).asSequence() }
                .filterIsInstance<CritAtom.ElementGate>()
                .map { it.element }
                .toSet()
        if (gatedElements != setOf("FIRE", "WATER", "EARTH", "AIR")) return null

        fun value(eid: Int): Int {
            @Suppress("UNCHECKED_CAST")
            val p = (byEffectId.getValue(eid)["params"] as List<Any?>).map { (it as Float).toDouble() }
            return floor(p.getOrElse(0) { 0.0 } + p.getOrElse(1) { 0.0 } * maxTier).toInt()
        }

        // The unconditional +DI bonus (action 126, outside the fan-out, live).
        val diEffects = subtree.filter { byEffectId.getValue(it)["action_id"] == ACTION_DAMAGE_INFLICTED_GAIN && !isDead(it) && !underElementGate(it) }
        val d = diEffects.singleOrNull()?.let { value(it) } ?: return null
        if (d <= 0) return null

        // The per-element penalty percent from a live, element-gated per-element mastery LOSS (30 for EC).
        val p =
            subtree
                .filter { !isDead(it) && underElementGate(it) && byEffectId.getValue(it)["action_id"] in ELEM_LOSS_ACTIONS }
                .mapNotNull { value(it).takeIf { v -> v > 0 } }
                .maxOrNull() ?: return null

        // Soundness: a non-strongest damage element must make the sub strictly worse — (1−p/100)(1+d/100) < 1.
        if ((100 - p).toLong() * (100 + d) >= 100L * 100) return null

        val consumed = diEffects.toHashSet() + subtree.filter { underElementGate(it) }
        return BestElementConcentration(damageInflictedBonus = d, masteryPenaltyPercent = p) to consumed
    }

    return meta.sortedBy { it.stateId }.map { m ->
        val state = stateById[m.stateId]
        // `maxLevel` (State table 67 `max_level`) is the theoretical formula range, NOT the value cap — kept only
        // for the display field + timing diagnostics.
        val maxLevel = (state?.get("max_level") as? Int)?.coerceAtLeast(1) ?: 1
        // The value formula is `floor(base + inc·maxTier)`, evaluated at the sublimation's real best-achievable
        // level `maxTier` (1..3, carried by the item — see [SublimationMeta.maxTier]). This is the community
        // formula `floor(param + paramInc·level)` at `level = maxTier`, verified against in-game tooltips
        // (Heavy Armor 5·2 = 10% dmg at tier 2; Swiftness 30−10·2 = −10% at tier 2; Influence 3·3 = 9 at tier 3).
        // NOTE: do NOT clamp this by `maxLevel-1` — WakForge's per-level tables show a CONSTANT (level-1) value
        // for low-`maxLevel` subs, which is a WakForge display bug, not the real scaling; trusting it over-clamps.
        val maxTier = m.maxTier.coerceAtLeast(1)
        val subtree = state?.let { strictSubtree(intList(it["effect_ids"])) } ?: emptyList()
        val subtreeSet = subtree.toHashSet()

        // Structured decodes → a dedicated field ([perStatStep] / [conversion] / [bestElementConcentration]) instead
        // of the effect list; the effects they consume are skipped in the loop below so they neither dirty the sub
        // nor emit bogus flats. A sub is at most one of these: the 913 ramp (Featherweight), the crit-mastery
        // conversion (Unraveling), or the best-element concentration fan-out (Elemental Concentration). null else.
        val perStatStepDecode = decodePerStatStepRamp(subtree, subtreeSet, maxTier)
        val conversionDecode = if (perStatStepDecode == null) decodeConversion913(subtree, subtreeSet) else null
        // The best-element concentration sub (Elemental Concentration) is a distinct shape (an unconditional +DI plus
        // a four-element penalty fan-out, not a single 913), decoded into its own [bestElementConcentration] field.
        val bestElementDecode =
            if (perStatStepDecode == null && conversionDecode == null) {
                decodeBestElementConcentration(subtree, subtreeSet, maxTier)
            } else {
                null
            }
        val perStatStep = perStatStepDecode?.first
        val conversion = conversionDecode?.first
        val bestElementConcentration = bestElementDecode?.first
        // Effect ids consumed by a dedicated decoder (perStatStep / conversion / best-element) — skipped in the
        // effect loop below so they neither dirty the sub nor emit bogus flats.
        val decodedHandled =
            (perStatStepDecode?.second ?: emptySet()) +
                (conversionDecode?.second ?: emptySet()) +
                (bestElementDecode?.second ?: emptySet())

        // Env-gated timing diagnostic (`WAKFU_SUB_TIMING_DEBUG=1` or `=6026,6013,…`) — dumps every effect
        // record's raw timing fields so the permanent (before-combat) / start-of-combat split can be settled
        // from the data. Greppable per stateId: `grep 'subTiming state=6026'`. No effect on the artifact.
        if (timingFilter != null && (timingFilter.isEmpty() || m.stateId in timingFilter)) {
            val nm = m.name.en.ifBlank { m.name.fr }
            if (subtree.isEmpty()) {
                println("subTiming state=${m.stateId} name=\"$nm\" maxLevel=$maxLevel (no decoded effects; stateRowPresent=${state != null})")
            } else {
                subtree.forEach { eid -> dumpSubTimingEffect(m.stateId, nm, byEffectId.getValue(eid)) }
            }
        }

        // Ancestor-chain (own + parent groups, within the subtree) scenario gates for a given effect.
        fun gatesFor(startId: Int): ScenarioGate? {
            var berserk = false
            var ranged = false
            var minLevel: Int? = null
            var orientation: String? = null
            var cur = startId
            val guard = HashSet<Int>()
            while (cur in subtreeSet && guard.add(cur)) {
                val e = byEffectId.getValue(cur)
                for (atom in parseCriterion(e["effect_criterion"] as String)) {
                    if (atom is CritAtom.Gate) {
                        berserk = berserk || atom.berserk
                        ranged = ranged || atom.ranged
                        atom.minLevel?.let { minLevel = it }
                        atom.orientation?.let { orientation = it }
                    }
                }
                val pid = e["parent_id"] as Int
                if (pid == cur) break
                cur = pid
            }
            return if (berserk || ranged || minLevel != null || orientation != null) {
                ScenarioGate(berserk = berserk.takeIf { it }, ranged = ranged.takeIf { it }, minCharacterLevel = minLevel, orientation = orientation)
            } else {
                null
            }
        }

        // True if this effect — or any ancestor group within the subtree — carries a `triggers_*` list. A
        // trigger means the effect fires on an event (applied at start of combat / in-combat), so it is NOT a
        // permanent out-of-combat stat. Structural groups carry the start-of-combat application trigger (e.g.
        // 1028), which `combatSignal` deliberately ignores for KIND classification — but it is exactly what
        // distinguishes a sheet-permanent stat (Influence II: crit directly under the state, no trigger) from a
        // start-of-combat one (Secondary Devastation II: crit under a 330 group whose `triggers_*` holds 1028).
        fun hasTriggeredAncestor(startId: Int): Boolean {
            var cur = startId
            val guard = HashSet<Int>()
            while (cur in subtreeSet && guard.add(cur)) {
                val e = byEffectId.getValue(cur)
                if (TRIGGER_KEYS.any { (e[it] as List<*>).isNotEmpty() }) return true
                val pid = e["parent_id"] as Int
                if (pid == cur) break
                cur = pid
            }
            return false
        }

        // True if this effect — or any ancestor group — sits under a "dead" criterion branch: an enemy/target-state
        // gate ([CritAtom.TargetGate], e.g. "the target has Armor") or a literal `False` ([CritAtom.FalseGate], a
        // `… and False` branch that never holds). Such an effect is situational / unreachable, so it is DROPPED
        // (skipped below) rather than credited or dirtying the sub.
        fun droppedByDeadBranch(startId: Int): Boolean {
            var cur = startId
            val guard = HashSet<Int>()
            while (cur in subtreeSet && guard.add(cur)) {
                val e = byEffectId.getValue(cur)
                if (parseCriterion(e["effect_criterion"] as String).any {
                        it is CritAtom.TargetGate || it is CritAtom.FalseGate || it is CritAtom.AntiStackGate
                    }
                ) {
                    return true
                }
                val pid = e["parent_id"] as Int
                if (pid == cur) break
                cur = pid
            }
            return false
        }

        // Effects whose parent is a "% of level" ([ACTION_LEVEL_PERCENT]) meta-action: they only NAME the stat
        // their parent scales, so the parent emits them as [SublimationEffect.PercentOfLevel] — skip their own
        // (placeholder) flat decode here to avoid double-counting.
        val percentOfLevelChildren =
            subtree
                .filter { (byEffectId.getValue(it)["parent_id"] as Int).let { p -> p in byEffectId && byEffectId.getValue(p)["action_id"] == ACTION_LEVEL_PERCENT } }
                .toHashSet()

        var dirty = state == null || subtree.isEmpty()
        var combatSignal = false
        // Set by the "elemental masteries → 0" actions (Chaos); modeled specially by the solver (see
        // [Sublimation.zeroesElementalMastery]) rather than as a flat effect, so they don't dirty the sub.
        var zeroesElementalMastery = false
        val conditions = LinkedHashSet<SublimationCondition>()
        val resolved = ArrayList<SublimationEffect>()
        // Tracks each value action+params seen, so the same one in multiple parallel groups (e.g. "-50% damage"
        // twice) is COLLAPSED to a single contribution instead of summed (the magnitude is the per-branch value,
        // not their sum) — and still drops the sub to forced-only (the branching isn't safely solver-modelable).
        val valueSignatures = HashSet<String>()

        // Per-element-damage subs (Brûlure/Gel/Tellurisme/Ventilation): their value branches are guarded by
        // element-matchup criteria naming the sub's own element ([CritAtom.ElementGate]). When EVERY matchup names
        // the SAME element, the whole sub is "+X% <element> damage": its DAMAGE_INFLICTED effect is tagged with an
        // element scenario gate ([add] below), and the otherwise-dirtying duplicate matchup branches are EXPECTED
        // (one per matchup, same value, only one applies) so they no longer force the sub to forced-only.
        // `singleOrNull` is the guard against the *multi*-element "play your best element" subs (Anatomy /
        // Elemental Concentration / Abnegation) — those carry a matchup branch for ALL four elements, so the set
        // has size 4 and stays `null` (no element tag, left forced-only as before).
        val familyElement: String? =
            subtree
                .asSequence()
                .flatMap { parseCriterion(byEffectId.getValue(it)["effect_criterion"] as String).asSequence() }
                .filterIsInstance<CritAtom.ElementGate>()
                .map { it.element }
                .toSet()
                .singleOrNull()

        // Resolve a value action to its (characteristic, sign) via the sublimation-local overlay then the catalog.
        fun resolveStatAction(a: Int): Pair<Characteristic, Int>? = SUB_ACTION_OVERRIDE[a] ?: (actions.kind(a) as? ActionKind.Stat)?.let { it.characteristic to it.sign }

        for (eid in subtree) {
            // Dead-branch (enemy-state / `False`) and %-of-level-child effects are handled out of band
            // (dropped / by their parent) — and a dead branch's own criterion is never harvested as a condition.
            if (droppedByDeadBranch(eid) || eid in percentOfLevelChildren || eid in decodedHandled) continue
            val e = byEffectId.getValue(eid)
            val a = e["action_id"] as Int

            @Suppress("UNCHECKED_CAST")
            val params = (e["params"] as List<Any?>).map { (it as Float).toDouble() }
            val atoms = parseCriterion(e["effect_criterion"] as String)
            if (atoms.any { it is CritAtom.Unknown }) dirty = true
            atoms.filterIsInstance<CritAtom.Cond>().forEach { conditions.add(it.condition) }
            // Triggers/scripts on a STRUCTURAL group are its execution machinery (a conditional group fires its
            // children via `triggers_not_related_to_executions`), NOT a combat proc — only flag them on a value
            // effect, where they mean "this stat procs on an in-combat event".
            val structural = a in STRUCTURAL_ACTIONS
            if (!structural && TRIGGER_KEYS.any { (e[it] as List<*>).isNotEmpty() }) {
                dirty = true
                combatSignal = true
            }
            if (!structural && (e["script_file_id"] as Int) != 0) dirty = true
            // A value effect with a finite positive duration is a TEMPORARY combat buff (e.g. "+30 resistance
            // for 2 turns"), not a permanent build stat. Permanent stats are dur=-1; the instant %-damage / %-of-
            // level modifiers are dur=0. Only dur>=1 is temporary.
            if (!structural && (e["duration_base"] as Int) >= 1) {
                dirty = true
                combatSignal = true
            }
            // The same value action+params in multiple parallel groups (e.g. Burn's three identical "+12% damage"
            // branches, one per element-proc condition) must NOT be summed — in game only one applies. Collapse to
            // one: emit the FIRST occurrence, drop the rest (the `when` below skips a `duplicateValueBranch`), and
            // still mark the sub forced-only (the branching isn't safely modelable for the solver).
            val duplicateValueBranch = !structural && a != ACTION_APPLY_STATE && !valueSignatures.add("$a:$params")
            // Duplicate value branches normally mark the sub forced-only (ambiguous branching). For a per-element
            // damage sub they are the expected one-per-matchup encoding of a single "+X% <element> damage" bonus,
            // so they collapse to one effect (the first wins via the `when` below) WITHOUT dirtying.
            if (duplicateValueBranch && familyElement == null) dirty = true

            // Permanent (character-sheet) stat iff non-temporary (`dur < 1` — both the −1 permanents AND the
            // dur-0 instant "% of level" / %-modifiers, which also show on the sheet; only `dur >= 1` is a
            // temporary in-combat buff), not bound to end-of-turn, and nothing in its ancestor chain triggers it
            // (a trigger ⇒ applied at combat start, not permanent). The final `condition == null && !combatSignal`
            // gate (applied in the merge below) keeps conditional / combat-conditional subs out of "permanent":
            // their effects apply only at / during combat, after any condition is read.
            val permanentHere =
                (e["duration_base"] as Int) < 1 &&
                    !(e["ends_at_end_of_turn"] as Boolean) &&
                    !hasTriggeredAncestor(eid)

            fun base() = params.getOrNull(0) ?: 0.0

            fun inc() = params.getOrNull(1) ?: 0.0

            fun add(
                charac: Characteristic,
                sign: Int,
                value: Int = floor((base() + inc() * maxTier) * sign).toInt(),
            ) {
                if (value == 0) return
                // A per-element-damage sub gates its DI on the sub's element ([familyElement]): the in-game effect
                // is simply "+X% <element> damage", credited per-element in max-damage mode.
                val gate =
                    gatesFor(eid).let { g ->
                        if (familyElement != null && charac == Characteristic.DAMAGE_INFLICTED) {
                            (g ?: ScenarioGate()).copy(element = familyElement)
                        } else {
                            g
                        }
                    }
                resolved.add(SublimationEffect.Flat(charac, value, gate, permanentHere))
            }

            when {
                duplicateValueBranch -> Unit // identical (action,params) branch already counted once — collapse
                a in STRUCTURAL_ACTIONS -> Unit // structural group; criteria already harvested above
                a == ACTION_APPLY_STATE -> {
                    dirty = true
                    combatSignal = true
                }
                a == ACTION_LEVEL_PERCENT -> {
                    // "X% of level as <stat>": the per-tier % is the lone param > 1; the stat(s) are this
                    // effect's child action(s) (lock 173 / dodge 175 / elemental mastery 120 / …). The
                    // best-achievable % scales with the tier exactly like a flat value (Expert 25·3 = 75%,
                    // Interception 50·3 = 150%, Brawling 50·2 = 100%), so multiply by `maxTier`. Emit one
                    // PercentOfLevel per child stat (resolved against the character's level at solve time);
                    // an unresolvable child → forced-only.
                    val pct = params.filter { it > 1.0 }.maxOrNull()?.let { floor(it).toInt() } ?: 0
                    val scaledPct = pct * maxTier
                    val statChildren = childrenOf[eid].orEmpty().filter { it in subtreeSet }
                    if (pct == 0 || statChildren.isEmpty()) {
                        dirty = true
                    } else {
                        for (childId in statChildren) {
                            val stat = resolveStatAction(byEffectId.getValue(childId)["action_id"] as Int)
                            if (stat == null) {
                                dirty = true
                            } else if (scaledPct * stat.second != 0) {
                                resolved.add(
                                    SublimationEffect.PercentOfLevel(stat.first, scaledPct * stat.second, gatesFor(childId), permanentHere)
                                )
                            }
                        }
                    }
                }
                a in UNMODELED_STAT_ACTIONS -> Unit // recognized clean stat with no Characteristic — skip, don't dirty
                a in ELEM_ZERO_ACTIONS -> zeroesElementalMastery = true // "elem mastery → 0" (Chaos) — flag, don't dirty
                else -> {
                    val override = SUB_ACTION_OVERRIDE[a]
                    if (override != null) {
                        add(override.first, override.second)
                    } else {
                        when (val k = actions.kind(a)) {
                            is ActionKind.Stat -> add(k.characteristic, k.sign)
                            // Random-element / dynamic / spell damage / heal aren't flat build stats; an action
                            // absent from actions.json is a combat script. Any of these = not solver-clean.
                            else -> dirty = true
                        }
                    }
                }
            }
        }

        val condition =
            when (conditions.size) {
                0 -> null
                1 -> conditions.first()
                else -> {
                    dirty = true
                    null
                } // conflicting conditions -> can't model
            }
        // Coalesce contributions per (characteristic, gate) and drop any whose NET is zero — this drives
        // `hasEffect` / `kind` and the cancellation EXACTLY as before timing was tracked, so a sub whose stats
        // fully cancel (e.g. +1/−1 WP across permanent + start-of-combat parts) stays effect-less →
        // COMBAT_CONDITIONAL. Within each surviving key, split into permanent vs start-of-combat (permanence
        // gated by `condition == null && !combatSignal`, holding the invariant **permanent ⟹ kind == FLAT**) so
        // the pre-combat condition logic sees only the permanent portion; an all-permanent key (the common case,
        // e.g. a 999 lock + its companion increment) collapses back to a single entry. Flat and percent-of-level
        // effects coalesce in separate buckets (their magnitudes don't mix). Canonical order = stable.
        val isPermanent = { e: SublimationEffect -> e.appliesBeforeCombat && condition == null && !combatSignal }
        val flatMerged =
            resolved
                .filterIsInstance<SublimationEffect.Flat>()
                .groupBy { it.characteristic to it.scenarioGate }
                .filter { (_, list) -> list.sumOf { it.value } != 0 }
                .flatMap { (key, list) ->
                    list
                        .groupBy { isPermanent(it) }
                        .map { (perm, part) -> SublimationEffect.Flat(key.first, part.sumOf { it.value }, key.second, perm) }
                        .filter { it.value != 0 }
                }
        val percentMerged =
            resolved
                .filterIsInstance<SublimationEffect.PercentOfLevel>()
                .groupBy { it.characteristic to it.scenarioGate }
                .filter { (_, list) -> list.sumOf { it.percentOfLevel } != 0 }
                .flatMap { (key, list) ->
                    list
                        .groupBy { isPermanent(it) }
                        .map { (perm, part) -> SublimationEffect.PercentOfLevel(key.first, part.sumOf { it.percentOfLevel }, key.second, perm) }
                        .filter { it.percentOfLevel != 0 }
                }
        val merged: List<SublimationEffect> =
            (flatMerged + percentMerged).sortedWith(
                compareBy(
                    { it.characteristic.ordinal },
                    { it is SublimationEffect.PercentOfLevel },
                    { (it as? SublimationEffect.Flat)?.value ?: (it as SublimationEffect.PercentOfLevel).percentOfLevel },
                    { it.appliesBeforeCombat }
                )
            )

        val hasEffect = merged.isNotEmpty()
        // Featherweight contributes only via [perStatStep] (a build-stat-driven ramp), Unraveling only via
        // [conversion] (a stat→stat move), and Elemental Concentration only via [bestElementConcentration] (its +DI
        // and penalty are consumed off the effect list) — no flat effects, but all are modelable contributions, so
        // they count here alongside `hasEffect`.
        val hasContribution = hasEffect || perStatStep != null || conversion != null || bestElementConcentration != null
        // A sub granting several secondary masteries at once (the "Devastate" all-masteries buff) is safe to offer:
        // both the solver objective and the re-scorer credit only the masteries applicable to the mode. Max-damage
        // sums exactly the scenario's element + range-band + back/berserk/healing masteries (distance XOR melee,
        // never both — WakfuBuildSolver.computeDamagePreMasteryTerms / FindMaxDamageScoring.expectedDamage); most-
        // masteries counts only the REQUESTED masteries. So multiple secondary masteries never sum into over-value.
        // Chaos's "elemental masteries → 0" ([zeroesElementalMastery]) IS decoded first-party (kept for display + a
        // possible future fast model), but the sub deliberately stays FORCED-INPUT-ONLY. Modeling the zeroing forces
        // the solver to explore both an elemental-on AND an elemental-zeroed damage regime for every build — roughly
        // 5× the max-damage proof time, GLOBALLY (measured on a level-110 full-pool proof: ~99s → ~490s, whether the
        // zeroing is a fast pre-mastery correction or the slow per-var fallback — the domain widening is inherent to
        // the sub's semantics, not the implementation). It only ever wins for a deliberately secondary-mastery-stacked,
        // elemental-light build (`secondary × 1.2 > elemental`), which is not worth that cost on every max-damage
        // request. Users can still `forcedSublimations` it. (See the reverted solver model in git history if revisiting.)
        val solverChoosable = !dirty && hasContribution && !zeroesElementalMastery
        val kind =
            when {
                combatSignal -> SublimationKind.COMBAT_CONDITIONAL
                // A conversion must be CONVERSION-kind for the solver's dedicated path to fire; its condition (if
                // any, e.g. Unraveling's CRIT_AT_LEAST) is still applied via appliesVar, so it takes precedence.
                conversion != null -> SublimationKind.CONVERSION
                condition != null -> SublimationKind.STATIC_CONDITIONAL
                hasContribution -> SublimationKind.FLAT
                else -> SublimationKind.COMBAT_CONDITIONAL
            }

        Sublimation(
            stateId = m.stateId,
            zenithId = m.zenithId,
            name = m.name,
            rarity = m.rarity,
            slotColorPattern = m.slotColorPattern,
            maxLevel = maxLevel,
            kind = kind,
            solverChoosable = solverChoosable,
            condition = condition,
            effects = merged,
            conversion = conversion,
            perStatStep = perStatStep,
            bestElementConcentration = bestElementConcentration,
            zeroesElementalMastery = zeroesElementalMastery,
            // Synthesized English (the client renders sub tooltips dynamically — there is no stored string).
            // Null when nothing is reconstructable: ~75 forced-only subs are pure combat scripts / state-appliers
            // whose effect text isn't in the declarative tables; the GUI degrades to name + rarity for those.
            rawText = synthesizeRawText(condition, merged, perStatStep, conversion, bestElementConcentration, zeroesElementalMastery).ifBlank { null }
        )
    }
}

// ----- timing diagnostics (env-gated; `WAKFU_SUB_TIMING_DEBUG`) -----

/**
 * Parses the `WAKFU_SUB_TIMING_DEBUG` env var. `null` = diagnostics off (unset/blank); an **empty set** =
 * dump every sublimation (`1` / `true` / `all`); otherwise the explicit comma-separated stateIds to dump
 * (e.g. `6026,6013,7115`). Used to settle which sub effects are permanent (out-of-combat, on the character
 * sheet) vs applied at start of combat — the timing that decides whether a sub's crit feeds a CRIT_AT_MOST
 * condition. The extractor needs a local Wakfu install, so this runs maintainer-locally, never in CI.
 */
private fun subTimingFilter(): Set<Int>? {
    val raw = System.getenv("WAKFU_SUB_TIMING_DEBUG")?.trim().orEmpty()
    if (raw.isEmpty()) return null
    if (raw.equals("1", true) || raw.equals("true", true) || raw.equals("all", true)) return emptySet()
    return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
}

/** Dumps one StaticEffect (68) record's raw timing fields as a single greppable `subTiming` line. */
private fun dumpSubTimingEffect(
    stateId: Int,
    name: String,
    e: Map<String, Any?>,
) {
    val triggers =
        TRIGGER_KEYS
            .map { it.removePrefix("triggers_") to (e[it] as List<*>) }
            .filter { it.second.isNotEmpty() }
            .joinToString(", ") { "${it.first}=${it.second}" }
            .ifEmpty { "none" }

    @Suppress("UNCHECKED_CAST")
    val params = (e["params"] as List<Any?>).map { (it as Float) }
    val crit = (e["effect_criterion"] as String).replace('\n', ' ').trim()
    println(
        "subTiming state=$stateId name=\"$name\" " +
            "effect=${e["effect_id"]} action=${e["action_id"]} parent=${e["parent_id"]} " +
            "dur_base=${e["duration_base"]} dur_inc=${e["duration_inc"]} " +
            "ends_at_end_of_turn=${e["ends_at_end_of_turn"]} dur_full_turns=${e["is_duration_in_full_turns"]} " +
            "dur_in_caster_turn=${e["duration_in_caster_turn"]} " +
            "apply_delay_base=${e["apply_delay_base"]} apply_delay_inc=${e["apply_delay_increment"]} " +
            "is_fight_effect=${e["is_fight_effect"]} is_in_turn_in_fight=${e["is_in_turn_in_fight"]} " +
            "store_on_self=${e["store_on_self"]} max_execution=${e["max_execution"]} " +
            "trigger_listener_type=${e["trigger_listener_type"]} script_file_id=${e["script_file_id"]} " +
            "triggers=[$triggers] crit=\"$crit\" params=$params"
    )
}

// ----- criterion-script parsing -----

/** One parsed clause of an `effect_criterion` script. */
private sealed interface CritAtom {
    /** A build-static start-of-combat condition (holds for the whole sublimation). */
    data class Cond(
        val condition: SublimationCondition,
    ) : CritAtom

    /** A per-scenario gate (attaches to the guarded effect). */
    data class Gate(
        val berserk: Boolean = false,
        val ranged: Boolean = false,
        val minLevel: Int? = null,
        /** Attack [me.chosante.autobuilder.domain.DamageScenario] orientation name (`BACK`/`FRONT`/`SIDE`). */
        val orientation: String? = null,
    ) : CritAtom

    /**
     * The element-matchup guard of a per-element-damage sub (Brûlure/Gel/Tellurisme/Ventilation). Their "+X% <E>
     * damage" bonus is implemented as branches comparing the sub's element [element] against the others — "my
     * `DMG_<E>` is highest" or "the target's `RES_<E>` is lowest". The player-facing effect is simply "+X% <E>
     * damage", so the guarded value becomes an **element scenario gate** ([ScenarioGate.element]) rather than an
     * opaque [Unknown] (which would force the sub to forced-only). Honoured only in max-damage mode.
     */
    data class ElementGate(
        val element: String,
    ) : CritAtom

    /** Recognized but irrelevant to the static model (anti-stack guards, on-crit triggers, `True`, …). */
    data object Ignore : CritAtom

    /**
     * An **enemy/target-state** gate (e.g. "the target has Armor", `GetCharac("ARMOR", …)`). The guarded effect
     * depends on who you fight, so it is **dropped** from the build-static model (never credited) — but, unlike
     * [Unknown], it does NOT dirty the rest of the sub, so a sub's clean siblings can still be modeled (e.g.
     * Light Weapons Expert keeps its mastery while its "+% damage vs armored target" half is dropped).
     */
    data object TargetGate : CritAtom

    /**
     * A criterion clause that is the literal `False` — a dead `… and False` branch Ankama leaves in the data. It
     * can NEVER hold, so the effect it guards never applies: **dropped** from the model (never credited) WITHOUT
     * dirtying the sub — exactly like [TargetGate] — so a sub's real siblings still model and its phantom
     * `False`-gated effects don't pollute the decoded value (e.g. Inflexibility II's spurious per-element masteries).
     */
    data object FalseGate : CritAtom

    /**
     * The REMOVE half of a "while <condition>" anti-double-apply pair: a `HasEffectWithSpecificId(marker)` branch
     * whose `421` state-ref undoes the bonus once the condition stops. We model the APPLIED (start-of-combat)
     * state, so this branch is **dropped** (not credited, doesn't dirty) — the matching APPLY branch
     * (`not HasEffectWithSpecificId …`) carries the real value (e.g. Carnage / Berserk Critical / Vital).
     */
    data object AntiStackGate : CritAtom

    /** Unrecognized — forces the sublimation to forced-only (conservative). */
    data object Unknown : CritAtom
}

private val RE_AP_ODD = Regex("""GetCharac\("AP",\s*"\w+"\)\s*%\s*2\)?\s*==\s*1""")
private val RE_DODGE_LT_LEVEL = Regex("""GetCharac\("DODGE",\s*"\w+"\)\s*<\s*GetLevel""")
private val RE_MIN_LEVEL = Regex("""GetLevel\("\w+"\)\s*>=\s*(\d+)""")
private val RE_BERSERK = Regex("""GetCharacInPct\("HP",\s*"\w+"\)\s*<\s*50""")
private val RE_TOTAL_HP_PCT = Regex("""GetTotalHpInPercent\("\w+"\)\s*(>=|>|<=|<)\s*(\d+)""")
private val RE_CMP = Regex("""GetCharac(?:Max)?\("(\w+)",\s*"\w+"\)\s*(<=|>=|==)\s*(-?\d+)""")

/**
 * Critical **Mastery** cap (Critical Secret: "+crit when Critical Mastery ≤ 0"): the **1-argument**
 * `GetCharac("CRITICAL_BONUS") <= N` form (no caster arg). `CRITICAL_BONUS` is also one of the six
 * [SECONDARY_MASTERY_CRITERION_TOKENS], but that criterion uses the 2-argument `GetCharac("CRITICAL_BONUS",
 * "caster")` form picked up by [RE_CMP]; the arity disambiguates, so this is matched **before** [RE_CMP].
 */
private val RE_CRIT_MASTERY_AT_MOST = Regex("""GetCharac(?:Max)?\("CRITICAL_BONUS"\)\s*<=\s*(-?\d+)""")

/**
 * The mirror 1-argument `GetCharac("CRITICAL_BONUS") >= N` — a benign "you have crit mastery to spend/convert"
 * guard (Unraveling gates its crit-mastery→elemental conversion on it). It is redundant with the effect it
 * protects (converting ~0 crit mastery is a no-op), so it's IGNORED rather than dirtying the sub.
 */
private val RE_CRIT_MASTERY_AT_LEAST = Regex("""GetCharac(?:Max)?\("CRITICAL_BONUS"\)\s*>=\s*(-?\d+)""")

/**
 * "(highest elemental mastery + %damage) > rear / healing mastery" — the self-referential build-static gate of
 * Anatomy (`… > BACKSTAB_BONUS`) and Abnegation (`… > HEAL_IN_PERCENT`), an `or`-chain over the four elements of
 * `(GetCharac("DMG_<E>_PERCENT") + GetCharac("DMG_IN_PERCENT")) > GetCharac("<secondary>")`. It holds for any real
 * damage build (elemental mastery dwarfs the rear/healing secondary), and — like the `GetTotalHpInPercent >= 90`
 * full-HP gate — is modeled as **optimistically satisfied → [CritAtom.Ignore]** rather than dirtying the sub. This
 * keeps the sub MONOTONE (no reified condition on the conflicted rear mastery, which would force max-damage
 * domination off), so Anatomy's real effects (−20% DI / +40% rear DI) decode cleanly and it becomes choosable.
 */
private val RE_ELEM_DMG_EXCEEDS_SECONDARY =
    Regex("""GetCharac\("DMG_(?:FIRE|WATER|EARTH|AIR)_PERCENT",\s*"\w+"\)\s*\+\s*GetCharac\("DMG_IN_PERCENT",\s*"\w+"\)\)\s*>\s*GetCharac\("(?:BACKSTAB_BONUS|HEAL_IN_PERCENT)"""")

/**
 * Element-matchup clause of a per-element-damage sub (Brûlure/Gel/Tellurisme/Ventilation): a comparison of the
 * sub's element's own damage / the target's resistance against another element — `GetCharac("DMG_<E>_PERCENT")
 * <op> GetCharac("DMG_…")` or `GetCharac("RES_<E>_PERCENT","target") <op> GetCharac("RES_…","target")`. The
 * **subject** element `<E>` is the left-hand side; it is the sub's damage element. Two `GetCharac` operands (no
 * numeric RHS), so [RE_CMP] never matches it. See [CritAtom.ElementGate].
 */
private val RE_ELEMENT_MATCHUP =
    Regex("""GetCharac\("(?:DMG|RES)_(FIRE|WATER|EARTH|AIR)_PERCENT"(?:,\s*"\w+")?\)\s*(?:<=|<|>=|>)\s*GetCharac\(""")
private val SECONDARY_MASTERY_CRITERION_TOKENS =
    setOf("MELEE_DMG", "RANGED_DMG", "BERSERK_DMG", "CRITICAL_BONUS", "BACKSTAB_BONUS", "HEAL_IN_PERCENT")

private val RE_NOT_HAS_WEAPON_TYPE = Regex("""not\s+HasWeaponType\(([0-9,\s]+)\)""")

/**
 * The CDN weapon-type ids forbidden by the in-game *light-weapons* criterion: the six two-handed subtypes
 * (axe 101 / shovel 111 / hammer 114 / bow 117 / sword 223 / staff 253, plus the legacy 510) + dagger 112 +
 * shield 189 — i.e. **every off-hand and two-handed weapon**. A `not HasWeaponType(exactly these)` therefore
 * holds iff the build equips no off-hand and no two-handed weapon ([SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED]);
 * any other id set is left [CritAtom.Unknown] (conservative — we only model this one verified mapping).
 */
private val LIGHT_WEAPON_FORBIDDEN_TYPES = setOf(101, 111, 114, 117, 223, 253, 510, 112, 189)

/**
 * Parses an `effect_criterion` script into [CritAtom]s. The script language for sublimations is a small,
 * finite vocabulary (`GetCharac`/`GetCharacMax`/`GetCharacInPct`/`GetLevel` comparisons, parity, state
 * guards). An empty/`True` criterion is unconditional; any clause we don't recognize yields [CritAtom.Unknown]
 * so the conservative classifier drops the sublimation from the solver-choosable set.
 */
private fun parseCriterion(criterion: String): List<CritAtom> {
    val crit = criterion.trim()
    if (crit.isEmpty() || crit.equals("True", ignoreCase = true)) return listOf(CritAtom.Ignore)
    return crit
        .replace('\n', ' ')
        .split(Regex("""\s+and\s+"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { atom -> classifyAtom(atom) }
}

private fun classifyAtom(atom: String): CritAtom {
    // A literal `False` clause (a dead `… and False` branch) never holds — drop the effect it guards, don't dirty.
    if (atom.equals("False", ignoreCase = true)) return CritAtom.FalseGate
    // Gates first (scenario-specific guards).
    if (atom.contains("IsTriggeredByMeleeEffect")) {
        return if (atom.startsWith("not ")) CritAtom.Gate(ranged = true) else CritAtom.Unknown
    }
    // Backstab orientation: `IsBackstabbed("target")` = attacking from the rear (BACK), `not IsBackstabbed(…)` =
    // front/side. Maps to a [me.chosante.common.ScenarioGate] orientation so a directional-damage sub (e.g.
    // Destruction II: +9% rear / −6% front) is modeled per max-damage scenario instead of merging into the net
    // +3%. The negation maps to FRONT only (the SIDE penalty is optimistically dropped — best-achievable model).
    if (atom.contains("IsBackstabbed")) {
        return CritAtom.Gate(orientation = if (atom.startsWith("not ")) "FRONT" else "BACK")
    }
    // Anti-double-apply marker of a "while <condition>" sub: the `not …` form guards the APPLY branch (keep it,
    // the marker is irrelevant → Ignore); the positive form guards the REMOVE branch (drop it → [CritAtom.AntiStackGate]).
    if (atom.contains("HasEffectWithSpecificId")) {
        return if (atom.startsWith("not ")) CritAtom.Ignore else CritAtom.AntiStackGate
    }
    // HP-percent conditions: `>= 90` (high HP) holds at the start of combat (full HP), so it is optimistically
    // always-true → Ignore (Carnage: "+45% of level mastery when HP > 90%"). `< 50` is the berserk range → a
    // berserk scenario gate, credited only in a max-damage berserk scenario (Berserk Critical: "+15% crit when HP < 50%").
    RE_TOTAL_HP_PCT.find(atom)?.let { m ->
        val op = m.groupValues[1]
        val n = m.groupValues[2].toInt()
        if ((op == ">=" || op == ">") && n >= 90) return CritAtom.Ignore
        if ((op == "<" || op == "<=") && n <= 50) return CritAtom.Gate(berserk = true)
        return CritAtom.Unknown
    }
    if (RE_BERSERK.containsMatchIn(atom)) return CritAtom.Gate(berserk = true)
    RE_MIN_LEVEL.find(atom)?.let { return CritAtom.Gate(minLevel = it.groupValues[1].toInt()) }

    // "No shield / dagger / two-handed weapon equipped" — the light-weapons gate (Light Weapons Expert).
    RE_NOT_HAS_WEAPON_TYPE.find(atom)?.let { m ->
        val ids =
            m.groupValues[1]
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
        return if (ids == LIGHT_WEAPON_FORBIDDEN_TYPES) {
            CritAtom.Cond(SublimationCondition(SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED))
        } else {
            CritAtom.Unknown
        }
    }

    // Build-static conditions.
    if (RE_AP_ODD.containsMatchIn(atom)) return CritAtom.Cond(SublimationCondition(SublimationConditionType.AP_ODD))
    if (RE_DODGE_LT_LEVEL.containsMatchIn(atom)) {
        return CritAtom.Cond(SublimationCondition(SublimationConditionType.DODGE_LT_PCT_OF_LEVEL, value = 100))
    }
    // Critical *Mastery* cap (Critical Secret) — matched before [RE_CMP] so the 1-arg `CRITICAL_BONUS` token reads
    // as MASTERY_CRITICAL, not the secondary-masteries criterion (which carries the same token in its 2-arg form).
    RE_CRIT_MASTERY_AT_MOST.find(atom)?.let { m ->
        return CritAtom.Cond(
            SublimationCondition(SublimationConditionType.CRITICAL_MASTERY_AT_MOST, value = m.groupValues[1].toInt())
        )
    }
    // Benign "you have crit mastery to convert" guard (Unraveling) — ignored, see [RE_CRIT_MASTERY_AT_LEAST].
    if (RE_CRIT_MASTERY_AT_LEAST.containsMatchIn(atom)) return CritAtom.Ignore
    // "(highest elem mastery + %damage) > rear/heal mastery" (Anatomy/Abnegation) — optimistically satisfied, see
    // [RE_ELEM_DMG_EXCEEDS_SECONDARY]. Checked before the element-matchup / RE_CMP rules (its `+`/secondary-RHS shape
    // matches neither, but keep it first so the whole `or`-chain reads as one Ignore atom).
    if (RE_ELEM_DMG_EXCEEDS_SECONDARY.containsMatchIn(atom)) return CritAtom.Ignore
    // Per-element damage matchup (Brûlure/Gel/Tellurisme/Ventilation): "+X% <E> damage", read as an element gate.
    RE_ELEMENT_MATCHUP.find(atom)?.let { m -> return CritAtom.ElementGate(m.groupValues[1]) }
    RE_CMP.find(atom)?.let { m ->
        val charac = m.groupValues[1]
        val op = m.groupValues[2]
        val n = m.groupValues[3].toInt()
        // ARMOR is a combat shield (the *target's* state), never a build stat, so an `…("ARMOR", …)` comparison
        // is an enemy-state gate: drop the guarded effect rather than dirtying the whole sub.
        if (charac == "ARMOR") return CritAtom.TargetGate
        // A "you have crit mastery to convert/spend" guard (Unraveling's `CRITICAL_BONUS >= N`, 2-arg form) — benign
        // (converting/spending ~0 crit mastery is a no-op), so ignore it rather than dirtying the sub.
        if (charac == "CRITICAL_BONUS" && (op == ">=" || op == ">")) return CritAtom.Ignore
        val type =
            when {
                charac == "AP" && op == "<=" -> SublimationConditionType.AP_AT_MOST
                charac == "AP" && op == ">=" -> SublimationConditionType.AP_AT_LEAST
                charac == "FEROCITY" && op == "<=" -> SublimationConditionType.CRIT_AT_MOST
                charac == "FEROCITY" && op == ">=" -> SublimationConditionType.CRIT_AT_LEAST
                charac == "BLOCK" && op == ">=" -> SublimationConditionType.BLOCK_AT_LEAST
                charac == "RANGE" && op == "<=" -> SublimationConditionType.RANGE_AT_MOST
                charac == "RANGE" && op == ">=" -> SublimationConditionType.RANGE_AT_LEAST
                charac == "RANGE" && op == "==" -> SublimationConditionType.RANGE_EXACT
                charac in SECONDARY_MASTERY_CRITERION_TOKENS && op == "<=" -> SublimationConditionType.SECONDARY_MASTERIES_AT_MOST
                else -> null
            }
        return type?.let { CritAtom.Cond(SublimationCondition(it, value = n)) } ?: CritAtom.Unknown
    }

    // Recognized-but-irrelevant guards.
    if (atom.startsWith("not HasState") ||
        atom.contains("IsTriggeringEffectCritical") ||
        atom.startsWith("HasState")
    ) {
        return CritAtom.Ignore
    }
    return CritAtom.Unknown
}

// ----- English rawText synthesis (display + searchable; the dynamic client tooltip has no static string) -----

private val PERCENT_CHARACS =
    setOf(Characteristic.DAMAGE_INFLICTED, Characteristic.BLOCK_PERCENTAGE, Characteristic.CRITICAL_HIT)

private val CHARAC_LABEL: Map<Characteristic, String> =
    mapOf(
        Characteristic.DAMAGE_INFLICTED to "Damage Inflicted",
        Characteristic.WILLPOWER to "Force of Will",
        Characteristic.RESISTANCE_CRITICAL to "Critical Resistance",
        Characteristic.RESISTANCE_BACK to "Rear Resistance",
        Characteristic.RESISTANCE_ELEMENTARY to "Elemental Resistance",
        Characteristic.CRITICAL_HIT to "Critical Hit",
        Characteristic.BLOCK_PERCENTAGE to "Block",
        Characteristic.ACTION_POINT to "AP",
        Characteristic.MOVEMENT_POINT to "MP",
        Characteristic.WAKFU_POINT to "WP",
        Characteristic.MAX_ACTION_POINT to "max AP",
        Characteristic.MAX_MOVEMENT_POINT to "max MP",
        Characteristic.RANGE to "Range",
        Characteristic.LOCK to "Lock",
        Characteristic.DODGE to "Dodge",
        Characteristic.CONTROL to "Control"
    )

internal fun synthesizeRawText(
    condition: SublimationCondition?,
    effects: List<SublimationEffect>,
    perStatStep: PerStatStepSpec? = null,
    conversion: SublimationConversion? = null,
    bestElementConcentration: BestElementConcentration? = null,
    zeroesElementalMastery: Boolean = false,
): String {
    val parts = ArrayList<String>()
    condition?.let { parts.add(conditionText(it)) }
    effects.forEach { parts.add(effectText(it)) }
    perStatStep?.let { parts.add(perStatStepText(it)) }
    conversion?.let { parts.add(conversionText(it)) }
    bestElementConcentration?.let { parts.add(bestElementConcentrationText(it)) }
    if (zeroesElementalMastery) parts.add("Elemental masteries set to 0")
    return parts.joinToString(" | ")
}

/** e.g. Elemental Concentration → "+20% Damage Inflicted | -30% Mastery of the 3 weakest elements". */
private fun bestElementConcentrationText(b: BestElementConcentration): String =
    "+${b.damageInflictedBonus}% Damage Inflicted | -${b.masteryPenaltyPercent}% Mastery of the 3 weakest elements"

/** e.g. Unraveling → "Convert 100% of critical mastery into elemental mastery". */
private fun conversionText(c: SublimationConversion): String {
    fun label(ch: Characteristic) = CHARAC_LABEL[ch] ?: ch.name.lowercase().replace('_', ' ')
    return "Convert ${c.percent}% of ${label(c.from)} into ${label(c.to)}"
}

/** e.g. Featherweight → "+6% Damage Inflicted per MP above 4 (max 24)". */
private fun perStatStepText(s: PerStatStepSpec): String {
    fun label(c: Characteristic) = CHARAC_LABEL[c] ?: c.name.lowercase().replace('_', ' ')
    val unit = if (s.target in PERCENT_CHARACS) "%" else ""
    return "+${s.perStep}$unit ${label(s.target)} per ${label(s.source)} above ${s.threshold} (max ${s.cap})"
}

private fun effectText(e: SublimationEffect): String {
    val label =
        CHARAC_LABEL[e.characteristic] ?: e.characteristic.name
            .lowercase()
            .replace('_', ' ')
    val gate = gateText(e.scenarioGate)
    return when (e) {
        is SublimationEffect.Flat -> {
            val unit = if (e.characteristic in PERCENT_CHARACS) "%" else ""
            val sign = if (e.value >= 0) "+" else ""
            "$sign${e.value}$unit $label$gate"
        }
        is SublimationEffect.PercentOfLevel -> {
            val sign = if (e.percentOfLevel >= 0) "+" else ""
            "$sign${e.percentOfLevel}% of level as $label$gate"
        }
    }
}

private fun gateText(g: ScenarioGate?): String {
    if (g == null) return ""
    val tags = ArrayList<String>()
    if (g.berserk == true) tags.add("berserk")
    if (g.ranged == true) tags.add("ranged")
    g.orientation?.let { tags.add(if (it == "BACK") "rear" else it.lowercase()) }
    g.element?.let { tags.add(it.lowercase()) }
    g.minCharacterLevel?.let { tags.add("lvl $it+") }
    return if (tags.isEmpty()) "" else " (" + tags.joinToString(" + ") + ")"
}

private fun conditionText(c: SublimationCondition): String =
    when (c.type) {
        SublimationConditionType.AP_AT_MOST -> "If AP ≤ ${c.value}"
        SublimationConditionType.AP_AT_LEAST -> "If AP ≥ ${c.value}"
        SublimationConditionType.AP_EXACT -> "If AP = ${c.value}"
        SublimationConditionType.AP_ODD -> "If odd AP"
        SublimationConditionType.CRIT_AT_MOST -> "If Critical Hit ≤ ${c.value}%"
        SublimationConditionType.CRIT_AT_LEAST -> "If Critical Hit ≥ ${c.value}%"
        SublimationConditionType.BLOCK_AT_LEAST -> "If Block ≥ ${c.value}%"
        SublimationConditionType.RANGE_AT_MOST -> "If Range ≤ ${c.value}"
        SublimationConditionType.RANGE_AT_LEAST -> "If Range ≥ ${c.value}"
        SublimationConditionType.RANGE_EXACT -> "If Range = ${c.value}"
        SublimationConditionType.DODGE_LT_PCT_OF_LEVEL -> "If Dodge < ${c.value}% of level"
        SublimationConditionType.SECONDARY_MASTERIES_AT_MOST -> "If secondary masteries ≤ ${c.value}"
        SublimationConditionType.CRITICAL_MASTERY_AT_MOST -> "If Critical Mastery ≤ ${c.value}"
        SublimationConditionType.WEAPON_TYPE_EQUIPPED -> "If ${c.text} equipped"
        SublimationConditionType.NO_OFFHAND_OR_TWO_HANDED -> "If no shield, dagger or two-handed weapon equipped"
        SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_REAR -> "If highest elemental mastery > rear mastery"
        SublimationConditionType.HIGHEST_ELEM_MASTERY_GT_HEALING -> "If highest elemental mastery > healing mastery"
        SublimationConditionType.OTHER -> "Conditional"
    }
