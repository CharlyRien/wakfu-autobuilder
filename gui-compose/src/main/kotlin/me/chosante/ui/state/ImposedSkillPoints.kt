package me.chosante.ui.state

import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillBranch
import me.chosante.common.skills.SkillLine

/**
 * How the engine reads the points of an [ImposedSkillPoints]. Only [MINIMUM] exists (issue #284: at least that many points on
 * the line, the search may add more). A stricter rule (an exact value per line) would be a new constant here, handled by
 * [ImposedSkillPoints.forEngine] and offered by the editor; the stored request already carries the rule's name.
 */
enum class SkillPointsRule {
    MINIMUM,
}

/**
 * The skill points the player imposes before a search: [points] per skill line (never a 0 entry), read under [rule]. Always
 * kept within the level's limits by [withLine] / [fittedTo] — a line within its own cap, a branch within its pool — so the
 * request the engine receives passes `validateRequest`.
 */
data class ImposedSkillPoints(
    val rule: SkillPointsRule = SkillPointsRule.MINIMUM,
    val points: Map<SkillLine, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = points.isEmpty()

    operator fun get(line: SkillLine): Int = points[line] ?: 0

    /** The points imposed on the lines of [branch]. */
    fun branchTotal(branch: SkillBranch): Int = points.entries.sumOf { (line, value) -> if (line.branch == branch) value else 0 }

    /**
     * The most points [line] can take at [level] with the rest of its branch as imposed now: its own cap, and what its branch's
     * pool leaves once the branch's other lines are counted.
     */
    fun maxFor(
        line: SkillLine,
        level: Int,
    ): Int {
        val skills = CharacterSkills(level)
        val pool = line.branch.of(skills).maxPointsToAssign
        return minOf(line.maxPoints(skills), pool - (branchTotal(line.branch) - this[line])).coerceAtLeast(0)
    }

    /** These points with [line] set to [value], clamped into what [level] allows ([maxFor]); 0 removes the line. */
    fun withLine(
        line: SkillLine,
        value: Int,
        level: Int,
    ): ImposedSkillPoints {
        val clamped = value.coerceIn(0, maxFor(line, level))
        return copy(points = if (clamped == 0) points - line else points + (line to clamped))
    }

    /**
     * These points brought within [level]'s limits — after a level change: each line to its own cap, then each branch to its pool,
     * the line with the most points served first (so the excess comes off the smaller ones). Unchanged when they already fit.
     */
    fun fittedTo(level: Int): ImposedSkillPoints {
        val skills = CharacterSkills(level)
        val fitted = mutableMapOf<SkillLine, Int>()
        for (branch in SkillBranch.entries) {
            // The line the player put the most on keeps its points first (ties: skill-tree order).
            val lines = SkillLine.entries.filter { it.branch == branch }.sortedByDescending { this[it] }
            var room = branch.of(skills).maxPointsToAssign
            for (line in lines) {
                val value = minOf(this[line], line.maxPoints(skills), room).coerceAtLeast(0)
                if (value > 0) fitted[line] = value
                room -= value
            }
        }
        return if (fitted == points) this else copy(points = fitted)
    }

    /** What the engine receives (`WakfuBestBuildParams.forcedSkillPoints`): the per-line minimums under [SkillPointsRule.MINIMUM]. */
    fun forEngine(): Map<SkillLine, Int> =
        when (rule) {
            SkillPointsRule.MINIMUM -> points
        }

    /** The stored form ([me.chosante.common.history.RequestSnapshot.forcedSkillPoints]): line name → points. */
    fun toSnapshot(): Map<String, Int> = points.entries.sortedBy { it.key.ordinal }.associate { (line, value) -> line.name to value }

    companion object {
        /** Restores stored points for [level]: unknown line names and unknown rules are dropped, the rest fitted to the level. */
        fun fromSnapshot(
            stored: Map<String, Int>,
            rule: String,
            level: Int,
        ): ImposedSkillPoints {
            val restoredRule = SkillPointsRule.entries.firstOrNull { it.name == rule } ?: return ImposedSkillPoints()
            val points = stored.mapNotNull { (name, value) -> SkillLine.entries.firstOrNull { it.name == name }?.let { it to value } }.toMap()
            return ImposedSkillPoints(restoredRule, points).fittedTo(level)
        }
    }
}
