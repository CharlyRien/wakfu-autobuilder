package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.common.skills.SkillLine
import me.chosante.common.skills.feedsAny
import me.chosante.common.skills.withLinePoints

/**
 * Leaves UNPLACED the skill points that bring nothing to the request (issue #284: "8 points in block when I never asked for
 * block"). The solver has no tie-breaker between equal-score allocations, so a branch none of whose stats the request reads
 * comes back spread at random. After the search, each line is lowered — towards 0 — while the build stays exactly as good:
 *  - the request's re-scorer ([WakfuBestBuildFinderAlgorithm.rescore]) returns EXACTLY the same score (targets, floors and the
 *    max-damage rotation are all in it), so a proven-optimal build keeps its score and its badge;
 *  - the build stays valid ([BuildCombination.isValid]), breaks no item stat gate it did not already break, and every
 *    sublimation keeps its credited / not-credited state ([creditedSublimations]).
 *
 * Never touched: a line the player imposed ([WakfuBestBuildParams.forcedSkillPoints]) and a line feeding a stat of a target row
 * (the most-masteries overshoot tie-break puts points there on purpose — dodge above its 300 target is value the player asked
 * for). Deterministic: lines in [SkillLine] order, the smallest value found by trying 0 then a bisection, each result verified.
 */
internal object UnplacedSkillPoints {
    fun trim(
        params: WakfuBestBuildParams,
        build: BuildCombination,
    ): BuildCombination {
        val clazz = params.character.clazz
        // Rows of target 0 (floors, an unweighted maximized mastery) do not protect a line: the re-score guards them.
        val requested = params.targetStats.filter { it.target > 0 }.map { it.characteristic }
        val base = params.character.baseCharacteristicValues
        val wanted = params.targetStats.masteryElementsWanted.keys

        fun credited(b: BuildCombination) = creditedSublimations(b, base, params.scoreComputationMode, params.damageScenario, wanted)

        fun gates(b: BuildCombination) =
            WakfuBestBuildFinderAlgorithm
                .statGateViolations(b, clazz)
                .map { it.item.equipmentId to it.gate }
                .toSet()

        val candidateLines =
            SkillLine.entries.filter { line ->
                line !in params.forcedSkillPoints &&
                    line.of(build.characterSkills).pointsAssigned > 0 &&
                    !line.of(build.characterSkills).feedsAny(requested)
            }
        if (candidateLines.isEmpty()) return build

        val reference = WakfuBestBuildFinderAlgorithm.rescore(params, build)
        val referenceCredited = credited(build)
        val referenceGates = gates(build)
        val referenceValid = build.isValid(clazz)

        fun keeps(candidate: BuildCombination): Boolean =
            WakfuBestBuildFinderAlgorithm.rescore(params, candidate).compareTo(reference) == 0 &&
                (!referenceValid || candidate.isValid(clazz)) &&
                referenceGates.containsAll(gates(candidate)) &&
                credited(candidate) == referenceCredited

        var current = build
        for (line in candidateLines) {
            val points = line.of(current.characterSkills).pointsAssigned

            fun withValue(value: Int) = current.copy(characterSkills = current.characterSkills.withLinePoints(mapOf(line to value)))

            val zero = withValue(0)
            if (keeps(zero)) {
                current = zero
                continue
            }
            // Bisection for the smallest value that keeps the build (lo fails, hi keeps); the found value is verified by keeps().
            var lo = 0
            var hi = points
            var best: BuildCombination? = null
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                val candidate = withValue(mid)
                if (keeps(candidate)) {
                    hi = mid
                    best = candidate
                } else {
                    lo = mid
                }
            }
            if (best != null) current = best
        }
        return current
    }
}
