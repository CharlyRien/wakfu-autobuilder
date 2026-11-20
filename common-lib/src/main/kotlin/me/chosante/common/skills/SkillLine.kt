package me.chosante.common.skills

/** The five skill branches of [CharacterSkills], each with its own point pool. */
enum class SkillBranch(
    private val select: (CharacterSkills) -> Assignable<*>,
) {
    INTELLIGENCE({ it.intelligence }),
    STRENGTH({ it.strength }),
    AGILITY({ it.agility }),
    LUCK({ it.luck }),
    MAJOR({ it.major }),
    ;

    /** This branch of [skills]. */
    fun of(skills: CharacterSkills): Assignable<*> = select(skills)
}

/**
 * A stable identifier for each line of the skill tree, independent of the [SkillCharacteristic] instances (which have
 * identity semantics, and whose [SkillCharacteristic.name] is not unique: "Resistance Elementary" exists in both the
 * Intelligence and the Major branch). Used to carry player-imposed skill points in a search request
 * (`WakfuBestBuildParams.forcedSkillPoints`), on the CLI ([cliId]) and in saved requests ([name]).
 */
enum class SkillLine(
    val branch: SkillBranch,
    private val select: (CharacterSkills) -> SkillCharacteristic,
) {
    INTELLIGENCE_HP_PERCENTAGE(SkillBranch.INTELLIGENCE, { it.intelligence.hpPercentage }),
    INTELLIGENCE_RESISTANCE(SkillBranch.INTELLIGENCE, { it.intelligence.resistance }),
    INTELLIGENCE_SHIELD(SkillBranch.INTELLIGENCE, { it.intelligence.shield }),
    INTELLIGENCE_HEAL_RECEIVED(SkillBranch.INTELLIGENCE, { it.intelligence.healReceivedPercentage }),
    INTELLIGENCE_HP_AS_ARMOR(SkillBranch.INTELLIGENCE, { it.intelligence.hpPercentageAsArmor }),

    STRENGTH_MASTERY_ELEMENTARY(SkillBranch.STRENGTH, { it.strength.masteryElementary }),
    STRENGTH_MASTERY_MELEE(SkillBranch.STRENGTH, { it.strength.masteryMelee }),
    STRENGTH_MASTERY_DISTANCE(SkillBranch.STRENGTH, { it.strength.masteryDistance }),
    STRENGTH_HP(SkillBranch.STRENGTH, { it.strength.hp }),

    AGILITY_LOCK(SkillBranch.AGILITY, { it.agility.lock }),
    AGILITY_DODGE(SkillBranch.AGILITY, { it.agility.dodge }),
    AGILITY_INITIATIVE(SkillBranch.AGILITY, { it.agility.initiative }),
    AGILITY_DODGE_AND_LOCK(SkillBranch.AGILITY, { it.agility.dodgeAndLock }),
    AGILITY_WILLPOWER(SkillBranch.AGILITY, { it.agility.willpower }),

    LUCK_BLOCK(SkillBranch.LUCK, { it.luck.block }),
    LUCK_CRITICAL_HIT(SkillBranch.LUCK, { it.luck.criticalHit }),
    LUCK_MASTERY_BACK(SkillBranch.LUCK, { it.luck.masteryBack }),
    LUCK_MASTERY_BERSERK(SkillBranch.LUCK, { it.luck.masteryBerserk }),
    LUCK_MASTERY_CRITICAL(SkillBranch.LUCK, { it.luck.masteryCritical }),
    LUCK_MASTERY_HEALING(SkillBranch.LUCK, { it.luck.masteryHealing }),
    LUCK_RESISTANCE_CRITICAL(SkillBranch.LUCK, { it.luck.resistanceCritical }),
    LUCK_RESISTANCE_BACK(SkillBranch.LUCK, { it.luck.resistanceBack }),

    MAJOR_ACTION_POINT(SkillBranch.MAJOR, { it.major.actionPoint }),
    MAJOR_MOVEMENT_POINT(SkillBranch.MAJOR, { it.major.movementPointAndMasteryElementary }),
    MAJOR_RANGE(SkillBranch.MAJOR, { it.major.rangeAndMasteryElementary }),
    MAJOR_WAKFU_POINTS(SkillBranch.MAJOR, { it.major.wakfuPoints }),
    MAJOR_CONTROL(SkillBranch.MAJOR, { it.major.controlAndMasteryElementary }),
    MAJOR_DAMAGE_INFLICTED(SkillBranch.MAJOR, { it.major.damageInflicted }),
    MAJOR_RESISTANCE(SkillBranch.MAJOR, { it.major.resistance }),
    ;

    /** This line's [SkillCharacteristic] in [skills] (the instance the solver keys its variables on). */
    fun of(skills: CharacterSkills): SkillCharacteristic = select(skills)

    /** The most points this line can hold for a character with [skills]: its own cap, bounded by its branch's pool. */
    fun maxPoints(skills: CharacterSkills): Int = minOf(of(skills).maxPointsAssignable, branch.of(skills).maxPointsToAssign)

    /** The CLI spelling: `agility-dodge`, `luck-critical-hit`, … */
    val cliId: String get() = name.lowercase().replace('_', '-')

    companion object {
        /** Parses a [cliId] or an enum [name] (case-insensitive, `-`, `.` and `_` interchangeable); null when unknown. */
        fun parse(raw: String): SkillLine? {
            val normalized =
                raw
                    .trim()
                    .uppercase()
                    .replace('-', '_')
                    .replace('.', '_')
            return entries.firstOrNull { it.name == normalized }
        }
    }
}

/** A copy of this allocation with [changes] applied (line → points); every other line keeps its points. */
fun CharacterSkills.withLinePoints(changes: Map<SkillLine, Int>): CharacterSkills {
    val copy = CharacterSkills(level)
    for (line in SkillLine.entries) line.of(copy).setPointAssigned(changes[line] ?: line.of(this).pointsAssigned)
    return copy
}
