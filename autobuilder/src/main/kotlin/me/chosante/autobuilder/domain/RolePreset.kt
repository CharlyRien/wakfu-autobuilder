package me.chosante.autobuilder.domain

/**
 * Role / positioning presets for max-damage mode (Lot 5).
 *
 * The raw [DamageScenario] defaults (orientation = BACK, rangeBand = DISTANCE) silently fold rear +
 * distance mastery into build *selection*: a player who never hits from behind, or who plays melee, still
 * gets a build optimized as if they did. Presets make that explicit — each one is a small, documented
 * transform of a base scenario that sets only the positioning fields ([DamageScenario.orientation] /
 * [DamageScenario.rangeBand]) and, for the tank role, opts the survivability soft-floor in.
 *
 * A preset never invents target values: it leaves [DamageScenario.element] / [DamageScenario.baseDamage] /
 * [DamageScenario.critCapPercent] / [DamageScenario.berserk] / resistances / [DamageScenario.minEffectiveHp]
 * untouched, so it composes with the rest of the scenario (and, in the CLI, *wins* over the individual
 * `--scenario-*` flags for the fields it does set).
 */
enum class RolePreset(
    /** Positioning the role assumes; null leaves the base scenario's orientation as-is. */
    private val orientation: Orientation?,
    /** Distance band the role plays; null leaves the base scenario's rangeBand as-is. */
    private val rangeBand: RangeBand?,
    /** Whether the role turns the survivability soft-floor on (never turns an already-on floor off). */
    private val survivabilityFloor: Boolean,
) {
    /** Ranged DPS hitting from behind — the raw default, made explicit. */
    DISTANCE_DPS(orientation = Orientation.BACK, rangeBand = RangeBand.DISTANCE, survivabilityFloor = false),

    /** Melee DPS: switches the secondary mastery to melee but keeps the rear-hit assumption. */
    MELEE_DPS(orientation = Orientation.BACK, rangeBand = RangeBand.MELEE, survivabilityFloor = false),

    /**
     * Tank: optimizes damage from the FRONT (no rear-mastery freebie) and turns the survivability
     * soft-floor on, so the optimum is a build that can hold a line rather than a glass cannon. The EHP
     * floor value comes from [DamageScenario.minEffectiveHp] (defaulted by the CLI/GUI when unset).
     */
    TANK(orientation = Orientation.FACE, rangeBand = RangeBand.DISTANCE, survivabilityFloor = true),
    ;

    /** Returns [scenario] with this preset's positioning + survivability fields applied (berserk preserved). */
    fun apply(scenario: DamageScenario): DamageScenario =
        scenario.copy(
            orientation = orientation ?: scenario.orientation,
            rangeBand = rangeBand ?: scenario.rangeBand,
            survivabilityFloor = survivabilityFloor || scenario.survivabilityFloor
        )
}
