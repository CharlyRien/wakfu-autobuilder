package me.chosante.autobuilder.domain

import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.autobuilder.genetic.wakfu.isRequiredMostMasteriesTarget
import me.chosante.common.Characteristic
import java.math.RoundingMode

class TargetStats(
    targetStats: List<TargetStat>,
) : HashSet<TargetStat>(targetStats) {
    private val targetStatToWeight = targetStats.associateWeights(100)
    val totalExpectedScore = sumOf { it.target * targetStatToWeight.getValue(it) }
    val expectedScoreByCharacteristic: Map<TargetStat, Double> =
        associateWith { it.target * targetStatToWeight.getValue(it) }

    fun weight(targetStat: TargetStat): Double = targetStatToWeight.getValue(targetStat)

    val masteryElementsWanted =
        targetStats
            .firstOrNull { it.characteristic == Characteristic.MASTERY_ELEMENTARY }
            ?.let {
                mapOf(
                    Characteristic.MASTERY_ELEMENTARY_EARTH to it.target,
                    Characteristic.MASTERY_ELEMENTARY_WIND to it.target,
                    Characteristic.MASTERY_ELEMENTARY_WATER to it.target,
                    Characteristic.MASTERY_ELEMENTARY_FIRE to it.target
                )
            } ?: filter {
            it.characteristic in
                listOf(
                    Characteristic.MASTERY_ELEMENTARY_EARTH,
                    Characteristic.MASTERY_ELEMENTARY_WIND,
                    Characteristic.MASTERY_ELEMENTARY_WATER,
                    Characteristic.MASTERY_ELEMENTARY_FIRE
                )
        }.associate { it.characteristic to it.target }

    /**
     * The resistance elements this request WANTS — those its random-element resistance rolls land on, read through the family's
     * fold: all four at the aggregate's target when "all resistances" asks for a value, else each element whose own row does. A
     * row of target 0 wants nothing: "air resistance 0" (a default row of the GUI) only keeps air at 0 or more
     * ([resistanceFloorElements]), so it never turns a request on one element into one on several ([needsItemPrefilter]).
     */
    val resistanceElementsWanted =
        targetStats
            .firstOrNull { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY && it.target != 0 }
            ?.let {
                mapOf(
                    Characteristic.RESISTANCE_ELEMENTARY_EARTH to it.target,
                    Characteristic.RESISTANCE_ELEMENTARY_WIND to it.target,
                    Characteristic.RESISTANCE_ELEMENTARY_WATER to it.target,
                    Characteristic.RESISTANCE_ELEMENTARY_FIRE to it.target
                )
            } ?: filter {
            it.target != 0 &&
                it.characteristic in
                listOf(
                    Characteristic.RESISTANCE_ELEMENTARY_EARTH,
                    Characteristic.RESISTANCE_ELEMENTARY_WIND,
                    Characteristic.RESISTANCE_ELEMENTARY_WATER,
                    Characteristic.RESISTANCE_ELEMENTARY_FIRE
                )
        }.associate { it.characteristic to it.target }

    /**
     * The FLOORS of this request: a row of target 0 on a REQUIRED stat ([isRequiredMostMasteriesTarget] — dodge, lock, a
     * resistance… never a maximized mastery) means "never below 0", in every mode — a hard `actual ≥ 0` on the most-masteries
     * and max-damage hard legs, the whole objective halved (once, however many floors are below 0) on their soft legs and in
     * precision. This list holds the floors outside the elemental resistances ([resistanceFloorElements]), in enum order. A
     * row of target 0 on a stat another row already targets with a non-zero value is left to that row (no floor): the GUI and
     * the CLI give each stat one row, so only a hand-built request can carry both.
     */
    val floorCharacteristics: List<Characteristic> =
        filter { row ->
            row.target == 0 &&
                row.characteristic.isRequiredMostMasteriesTarget() &&
                row.characteristic !in RESISTANCE_FAMILY &&
                none { it.characteristic == row.characteristic && it.target != 0 }
        }.map { it.characteristic }
            .distinct()
            .sortedBy { it.ordinal }

    /**
     * The elemental resistances kept at 0 or more without being WANTED ([resistanceElementsWanted]): those a row of target 0
     * names — its element, or all four for "all resistances 0" — that no row with a non-zero target wants. Each is read as the
     * game reads it: its own lines, the "+all elements" ones (and percent skills) and the random-element rolls the player puts
     * there. Such a request places the family's rolls over the wanted and the floored elements together — one joint fold, each
     * roll on as many of them as it can reach (a negative one on as few as it must, the elements no row names taking the rest) —
     * in the solver's model and in the scorers alike, so the two always agree on whether such a floor holds. A floor never makes
     * a request multi-element ([needsItemPrefilter] reads the wanted elements only). In the solver's canonical element order
     * (water, fire, earth, air).
     */
    val resistanceFloorElements: List<Characteristic> =
        run {
            val named = HashSet<Characteristic>()
            for (row in this) {
                if (row.target != 0) continue
                when (row.characteristic) {
                    Characteristic.RESISTANCE_ELEMENTARY -> named.addAll(ELEMENTAL_RESISTANCES)
                    in ELEMENTAL_RESISTANCES -> named.add(row.characteristic)
                    else -> Unit
                }
            }
            ELEMENTAL_RESISTANCES.filter { it in named && it !in resistanceElementsWanted }
        }

    /** Whether this request has a floor ([floorCharacteristics], [resistanceFloorElements]). */
    val hasFloors: Boolean
        get() = floorCharacteristics.isNotEmpty() || resistanceFloorElements.isNotEmpty()

    /**
     * Precision only: the masteries a row of target 0 names (distance, or one element — never "elemental mastery", whose row
     * of target 0 weighs nothing) that no row with a non-zero target covers (its own, or for an element "elemental mastery"'s).
     * Precision halves its score while one of them reads below 0, like a floor. A mastery is not a required stat, so its row
     * keeps the meaning it has always had: an element stays WANTED ([masteryElementsWanted], read on its FOLD, random rolls
     * included), and the other modes give such a row no floor (most-masteries maximizes a requested mastery whatever its
     * target; max-damage ignores mastery rows). In enum order.
     */
    val zeroMasteries: List<Characteristic> =
        run {
            val aggregateCovers = any { it.characteristic == Characteristic.MASTERY_ELEMENTARY && it.target != 0 }
            filter { row ->
                row.target == 0 &&
                    row.characteristic.isMaximizableMastery() &&
                    row.characteristic != Characteristic.MASTERY_ELEMENTARY &&
                    !(aggregateCovers && row.characteristic in ELEMENTAL_MASTERIES) &&
                    none { it.characteristic == row.characteristic && it.target != 0 }
            }.map { it.characteristic }
                .distinct()
                .sortedBy { it.ordinal }
        }

    /**
     * Whether one elemental family of this request wants more than one element: several per-element rows of it, or its
     * aggregate row ("all resistances", "elemental mastery") — of a resistance, only rows with a target count
     * ([resistanceElementsWanted]). Such a request is searched on a heuristically pre-filtered item pool, so no search of it
     * is ever PROVEN optimal (`WakfuBuildSolver.needsItemPrefilter` reads this), and a proof an older version stored for one
     * cannot be trusted either.
     */
    val needsItemPrefilter: Boolean
        get() = masteryElementsWanted.size > 1 || resistanceElementsWanted.size > 1

    /**
     * Whether a version before rows of target 0 became floors searched this request on the pre-filtered pool ([needsItemPrefilter]
     * under the old reading): it counted a resistance row of target 0 as a WANTED element, so "air resistance 0" beside "fire
     * resistance 100" — or "all resistances 0" — made the request multi-element. A build saved from such a search may carry a
     * "proven optimal" flag proven over that reduced pool (1.13 stamped CP-SAT's OPTIMAL there; the guard came in 1.14.0), which a
     * reload must not restore, though the request now searches the whole catalog.
     */
    val legacyNeedsItemPrefilter: Boolean
        get() =
            needsItemPrefilter ||
                any { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY } ||
                filter { it.characteristic in ELEMENTAL_RESISTANCES }.map { it.characteristic }.distinct().size > 1

    /**
     * Elements the "most-masteries" objective takes the *minimum* elemental mastery over. Specific
     * elements win: if the user asked for any of fire/earth/water/air, those define the set, so a
     * co-requested [Characteristic.MASTERY_ELEMENTARY] ("all elements") only lifts them via generic
     * gear instead of forcing the solver to also balance the elements they never asked for. The
     * aggregate expands to all four only when no specific element was requested. Always a subset of
     * [masteryElementsWanted]'s keys (which stays the full fold set), and mirrored by both the scorer
     * and the OR-Tools objective so the two engines optimise the same thing.
     */
    val masteryElementsToMinimize: List<Characteristic> =
        elementsToMinimizeOver(ELEMENTAL_MASTERIES, Characteristic.MASTERY_ELEMENTARY)

    private fun elementsToMinimizeOver(
        elements: List<Characteristic>,
        aggregate: Characteristic,
    ): List<Characteristic> {
        val requestedSpecifics = elements.filter { element -> any { it.characteristic == element } }
        return when {
            requestedSpecifics.isNotEmpty() -> requestedSpecifics
            any { it.characteristic == aggregate } -> elements
            else -> emptyList()
        }
    }

    companion object {
        private val ELEMENTAL_MASTERIES =
            listOf(
                Characteristic.MASTERY_ELEMENTARY_WATER,
                Characteristic.MASTERY_ELEMENTARY_FIRE,
                Characteristic.MASTERY_ELEMENTARY_EARTH,
                Characteristic.MASTERY_ELEMENTARY_WIND
            )

        private val ELEMENTAL_RESISTANCES =
            listOf(
                Characteristic.RESISTANCE_ELEMENTARY_WATER,
                Characteristic.RESISTANCE_ELEMENTARY_FIRE,
                Characteristic.RESISTANCE_ELEMENTARY_EARTH,
                Characteristic.RESISTANCE_ELEMENTARY_WIND
            )

        private val RESISTANCE_FAMILY = ELEMENTAL_RESISTANCES.toSet() + Characteristic.RESISTANCE_ELEMENTARY
    }
}

fun List<TargetStat>.associateWeights(normalizeValue: Int): Map<TargetStat, Double> {
    return associateWith {
        if (it.target == 0) {
            return@associateWith 0.0
        }

        val normalizedWeight = normalizeValue.toBigDecimal().setScale(2, RoundingMode.HALF_UP) / it.target.toBigDecimal().setScale(2, RoundingMode.HALF_UP)
        normalizedWeight.toDouble() * it.userDefinedWeight
    }
}
