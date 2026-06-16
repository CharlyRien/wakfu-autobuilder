package me.chosante.autobuilder

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.options.splitPair
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.animation.progress.ThreadProgressTaskAnimator
import com.github.ajalt.mordant.animation.progress.animateOnThread
import com.github.ajalt.mordant.animation.progress.execute
import com.github.ajalt.mordant.rendering.BorderType.Companion.SQUARE_DOUBLE_SECTION_SEPARATOR
import com.github.ajalt.mordant.rendering.TextAlign
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextColors.Companion.rgb
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.table.Borders
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.progress.percentage
import com.github.ajalt.mordant.widgets.progress.progressBar
import com.github.ajalt.mordant.widgets.progress.progressBarContextLayout
import com.github.ajalt.mordant.widgets.progress.text
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import me.chosante.ZenithInputParameters
import me.chosante.autobuilder.domain.BossDisplay
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.SpellRotation
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.against
import me.chosante.autobuilder.domain.againstAllElements
import me.chosante.autobuilder.domain.resistancePercent
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildParams
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Characteristic.ACTION_POINT
import me.chosante.common.Characteristic.BLOCK_PERCENTAGE
import me.chosante.common.Characteristic.CONTROL
import me.chosante.common.Characteristic.CRITICAL_HIT
import me.chosante.common.Characteristic.DODGE
import me.chosante.common.Characteristic.GIVEN_ARMOR_PERCENTAGE
import me.chosante.common.Characteristic.HP
import me.chosante.common.Characteristic.INITIATIVE
import me.chosante.common.Characteristic.LOCK
import me.chosante.common.Characteristic.MASTERY_BACK
import me.chosante.common.Characteristic.MASTERY_BERSERK
import me.chosante.common.Characteristic.MASTERY_CRITICAL
import me.chosante.common.Characteristic.MASTERY_DISTANCE
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_EARTH
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_FIRE
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WATER
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WIND
import me.chosante.common.Characteristic.MASTERY_HEALING
import me.chosante.common.Characteristic.MASTERY_MELEE
import me.chosante.common.Characteristic.MOVEMENT_POINT
import me.chosante.common.Characteristic.PROSPECTION
import me.chosante.common.Characteristic.RANGE
import me.chosante.common.Characteristic.RECEIVED_ARMOR_PERCENTAGE
import me.chosante.common.Characteristic.RESISTANCE_BACK
import me.chosante.common.Characteristic.RESISTANCE_CRITICAL
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_EARTH
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_FIRE
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_WATER
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_WIND
import me.chosante.common.Characteristic.WILLPOWER
import me.chosante.common.Characteristic.WISDOM
import me.chosante.common.Equipment
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.skills.Assignable
import me.chosante.createZenithBuild
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
internal const val VERSION = "1.91.1.54"

fun main(args: Array<String>) = WakfuAutobuild().main(args)

private val additionalHelpOnStats =
    """note that, it is also possible to pass an optional weight with the following format 
        |-> x:y | x being the number wanted, y the weight of the statistic you want to put (default is 1)
    """.trimMargin()

private class WakfuAutobuild :
    CliktCommand(
        name = "Wakfu Autobuilder version: $VERSION"
    ) {
    override fun help(context: Context) =
        """This program helps Wakfu players easily find the best combination of equipment for their character 
    |at a given level by taking into account their desired stats and providing build suggestions
    |based on their input.
    |
    |Current Wakfu data version used: $VERSION
    |
    |Here's an example of usage in your terminal: 
    |./wakfu-autobuilder-cli.exe --level 110 --action-point 11 --movement-point 5 --mastery-distance 500 --hp 2000 --range 2 --cc 30 --class cra --create-zenith-build --duration 60
        """.trimMargin()

    private val maxLevelWanted: Int by option(
        "--max-level",
        "--max-niveau",
        help = "The max level of the character (no items selected will be above this level)"
    ).int()
        .default(245)
        .check("Level should be between 1 and 245") { it in 1..245 }

    private val minLevelWanted: Int by option(
        "--min-level",
        "--min-niveau",
        help = "The min level of the character (no items selected will be under this level)"
    ).int()
        .default(0)
        .check("Min level should be between 0 and 245") { it in 0..245 }

    private val characterClass: CharacterClass? by option(
        "--class",
        "--classe",
        help = """The class of the character, here are the possible classes: FECA
OSAMODAS
ENUTROF
SRAM
XELOR
ECAFLIP
ENIRIPSA
IOP
CRA
SADIDA
SACRIEUR
PANDAWA
ROUBLARD
ZOBAL
OUGINAK
STEAMER
ELIOTROPE
HUPPERMAGE"""
    ).convert {
        val clazz = CharacterClass.fromValue(it)
        if (clazz == CharacterClass.UNKNOWN) {
            fail("Unknown class, use --help to see all the classes possible")
        }

        clazz
    }

    private val searchDuration: Duration by option(
        "--duration",
        "--duree",
        help = "The number of seconds to search for the best set of equipment"
    ).convert { it.toLongOrNull()?.seconds ?: fail("'$it' should be a number") }
        .default(60.seconds)

    private val computationMode: ScoreComputationMode by option(
        "--computation-mode",
        "--mode-de-calcul",
        help = """The mode used for the algorithm, there are two for now:
                     PRECISION -> In this mode you can ask for every characteristic possible,
                     you have to enter a specific value for each characteristic wanted so the algorithm can try at all cost to follow or to surpass.
                   
                     MOST-MASTERIES -> In this mode, only the following characteristics can take a specific value: PA/PM/PO/CC
                     For the rest you only have to set the value "1" to the masteries you want to have for your build. 
                     The algorithm will then try to follow the constraints for the PA/PM/PO/CC while maximizing the masteries asked.
                """
    ).convert { ScoreComputationMode.from(it) ?: ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT }
        .default(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)

    private val maxRarity: Rarity by option(
        "--max-rarity",
        "--rarete-max",
        help = """Used to tell the algorithm to not take items into account that above this rarity, here the list of value possible in order: ${Rarity.entries}"""
    ).convert { Rarity.valueOf(it.uppercase()) }
        .default(Rarity.EPIC)

    private val forceItems: List<String> by option(
        "--items-a-force",
        "--forced-items",
        help =
            """
            Used to tell the algorithm to force specific items to be in the final build,
             the names have to be French for now, can be used like that:
              --forced-items 'Gelano','Amulette du Bouftou',...
            """.trimIndent()
    ).split(",").default(listOf())

    private val excludedItems: List<String> by option(
        "--items-a-exclure",
        "--excluded-items",
        help =
            """
            Used to tell the algorithm to exclude specific items to not be in the final build,
            the names have to be in french for now, can be used like that: --excluded-items 'Gelano','Amulette du Bouftou',...
            """.trimIndent()
    ).split(",").default(listOf())

    private val paWanted: TargetStat? by option(
        names = arrayOf("--ap", "--action-point", "--pa"),
        help = "Number of action points wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(ACTION_POINT)

    private val rangeWanted: TargetStat? by option(
        names = arrayOf("--range", "--portee", "--po"),
        help = "Number of range points wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(RANGE)

    private val criticalHitWanted: TargetStat? by option(
        names = arrayOf("--critical-hit", "--cc"),
        help = "Number of critical hit wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(CRITICAL_HIT)

    private val pmWanted: TargetStat? by option(
        names = arrayOf("--mp", "--movement-point", "--pm"),
        help = "Number of movement points wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MOVEMENT_POINT)

    private val pwWanted: TargetStat? by option(
        names = arrayOf("--wp", "--wakfu-point", "--pw"),
        help = "Number of wakfu points wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MOVEMENT_POINT)

    private val masteryElementWanted: TargetStat? by option(
        names = arrayOf("--mastery-elementary", "--maitrise-elementaire"),
        help = "Number of elementary mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_ELEMENTARY)

    private val masteryCriticalWanted: TargetStat? by option(
        names = arrayOf("--mastery-critical", "--maitrise-critique"),
        help = "Number of critical mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_CRITICAL)

    private val masteryWaterWanted: TargetStat? by option(
        names = arrayOf("--mastery-water", "--maitrise-eau"),
        help = "Number of water mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_ELEMENTARY_WATER)

    private val masteryFireWanted: TargetStat? by option(
        names = arrayOf("--mastery-fire", "--maitrise-feu"),
        help = "Number of fire mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_ELEMENTARY_FIRE)

    private val masteryWindWanted: TargetStat? by option(
        names = arrayOf("--mastery-wind", "--maitrise-air"),
        help = "Number of wind mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_ELEMENTARY_WIND)

    private val masteryEarthWanted: TargetStat? by option(
        names = arrayOf("--mastery-earth", "--maitrise-terre"),
        help = "Number of wind mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_ELEMENTARY_EARTH)

    private val masteryBackWanted: TargetStat? by option(
        names = arrayOf("--mastery-back", "--maitrise-dos"),
        help = "Number of back mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_BACK)

    private val masteryMeleeWanted: TargetStat? by option(
        names = arrayOf("--mastery-melee", "--maitrise-melee"),
        help = "Number of melee mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_MELEE)

    private val masteryBerserkWanted: TargetStat? by option(
        names = arrayOf("--mastery-berserk", "--maitrise-berserk"),
        help = "Number of berserk mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_BERSERK)

    private val masteryHealingWanted: TargetStat? by option(
        names = arrayOf("--mastery-healing", "--maitrise-soin"),
        help = "Number of healing mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_HEALING)

    private val resistanceCriticalWanted: TargetStat? by option(
        names = arrayOf("--resistance-critical", "--resistance-critique"),
        help = "Number of critical resistance wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_CRITICAL)

    private val resistanceBackWanted: TargetStat? by option(
        names = arrayOf("--resistance-back", "--resistance-dos"),
        help = "Number of back resistance wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_BACK)

    private val resistanceElementaryWanted: TargetStat? by option(
        names = arrayOf("--resistance-elementary", "--resistance-elementaire"),
        help = "Number of elementary resistance wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_ELEMENTARY)

    private val resistanceElementaryFireWanted: TargetStat? by option(
        names = arrayOf("--resistance-elementary-fire", "--resistance-elementaire-feu"),
        help = "Number of fire elementary resistance wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_ELEMENTARY_FIRE)

    private val resistanceElementaryWaterWanted: TargetStat? by option(
        names = arrayOf("--resistance-elementary-water", "--resistance-elementaire-eau"),
        help = "Number of water elementary resistance wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_ELEMENTARY_WATER)

    private val resistanceElementaryWindWanted: TargetStat? by option(
        names = arrayOf("--resistance-elementary-wind", "--resistance-elementaire-air"),
        help = "Number of wind elementary resistance wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_ELEMENTARY_WIND)

    private val resistanceElementaryEarthWanted: TargetStat? by option(
        names = arrayOf("--resistance-elementary-earth", "--resistance-elementaire-terre"),
        help = "Number of earth elementary resistance wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_ELEMENTARY_EARTH)

    private val controlWanted: TargetStat? by option(
        names = arrayOf("--control", "--controle"),
        help = "Number of control wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(CONTROL)

    private val wisdomWanted: TargetStat? by option(
        names = arrayOf("--wisdom", "--sagesse"),
        help = "Number of wisdom wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(WISDOM)

    private val dodgeWanted: TargetStat? by option(
        names = arrayOf("--dodge", "--esquive"),
        help = "Number of dodge wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(DODGE)

    private val lockWanted: TargetStat? by option(
        names = arrayOf("--lock", "--tacle"),
        help = "Number of lock wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(LOCK)

    private val prospectionWanted: TargetStat? by option(
        names = arrayOf("--prospection"),
        help = "Number of prospection wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(PROSPECTION)

    private val initiativeWanted: TargetStat? by option(
        names = arrayOf("--initiative"),
        help = "Number of initiative wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(INITIATIVE)

    private val willpowerWanted: TargetStat? by option(
        names = arrayOf("--willpower", "--volonte"),
        help = "Number of willpower wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(WILLPOWER)

    private val receivedArmorPercentageWanted: TargetStat? by option(
        names = arrayOf("--received-armor", "--armure-recue"),
        help = "Number of received armor percentage wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(RECEIVED_ARMOR_PERCENTAGE)

    private val masteryDistanceWanted: TargetStat? by option(
        names = arrayOf("--mastery-distance", "--maitrise-distance"),
        help = "Number of distance mastery wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(MASTERY_DISTANCE)

    private val hpWanted: TargetStat? by option(
        names = arrayOf("--hp", "--pdv", "--health-points", "--points-de-vie"),
        help = "Number of health points wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(HP)

    private val blockPercentageWanted: TargetStat? by option(
        names = arrayOf("--parade", "--block"),
        help = "percentage of parade wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(BLOCK_PERCENTAGE)

    private val armorGivenPercentageWanted: TargetStat? by option(
        names = arrayOf("--armor-given", "--armure-donnee"),
        help = "percentage of parade wanted. $additionalHelpOnStats"
    ).splitPair(delimiter = ":")
        .toTargetStat(GIVEN_ARMOR_PERCENTAGE)

    private val createZenithBuild: Boolean by option(
        "--create-zenith-build",
        help = "Use this flag to indicate that you want to create a zenith build for the equipment found"
    ).flag(default = false, defaultForHelp = "disabled")

    private val stopWhenBuildMatch: Boolean by option(
        "--stop-when-build-match",
        help = "Use this flag to indicate that you want to stop searching when you have 100% build match already found."
    ).flag(default = false, defaultForHelp = "disabled")

    private val noRunes: Boolean by option(
        "--no-runes",
        "--sans-chasses",
        help =
            "Use this flag to search without socketing runes. By default the solver fills each item's " +
                "sockets with the best runes for your requested stats (max-level runes + colour doubling)."
    ).flag(default = false, defaultForHelp = "runes enabled")

    // --- max-damage scenario (only used when --computation-mode max-damage) ---

    private val scenarioElement: SpellElement by option(
        "--scenario-element",
        help = "max-damage mode: the spell's element (fire/water/earth/air)."
    ).convert { SpellElement.valueOf(it.uppercase().let { value -> if (value == "WIND") "AIR" else value }) }
        .default(SpellElement.FIRE)

    private val scenarioRange: RangeBand by option(
        "--scenario-range",
        help = "max-damage mode: distance or melee (selects the secondary mastery)."
    ).convert { RangeBand.valueOf(it.uppercase()) }
        .default(RangeBand.DISTANCE)

    private val scenarioOrientation: Orientation by option(
        "--scenario-orientation",
        help = "max-damage mode: face / side / back (positional multiplier; back also adds rear mastery)."
    ).convert { Orientation.valueOf(it.uppercase()) }
        .default(Orientation.BACK)

    private val scenarioBerserk: Boolean by option(
        "--scenario-berserk",
        help = "max-damage mode: caster is at/below 50% HP (berserk mastery applies)."
    ).flag(default = false)

    private val scenarioHealing: Boolean by option(
        "--scenario-healing",
        help = "max-damage mode: the attack is a heal (healing mastery applies)."
    ).flag(default = false)

    private val scenarioCritCap: Int by option(
        "--crit-cap",
        help = "max-damage mode: maximum usable critical-hit rate in % (default 100)."
    ).int().default(100).check("Crit cap should be between 0 and 100") { it in 0..100 }

    private val scenarioEnemyResistance: Int by option(
        "--enemy-resistance",
        help = "max-damage mode: target's effective elemental resistance in % (0-90, default 0)."
    ).int().default(0).check("Resistance should be between 0 and 90") { it in 0..90 }

    private val scenarioBaseDamage: Int by option(
        "--base-damage",
        help = "max-damage mode: spell base hit used to scale the displayed expected-damage figure (default 100)."
    ).int().default(100)

    private val bossResistances: Map<SpellElement, Int>? by option(
        "--boss-resistances",
        help =
            "max-damage BOSS mode: the target's per-element resistance %, e.g. " +
                "'fire:0,water:-90,earth:50,air:50' (negative = weakness). When set, the solver optimizes " +
                "over ALL elements and picks the best one the class can actually play (jointly with gear)."
    ).convert { raw ->
        raw
            .split(",")
            .mapNotNull { token ->
                val (name, value) = token.split(":").map { it.trim() }.let { it.getOrNull(0) to it.getOrNull(1) }
                val element =
                    when (name?.lowercase()) {
                        "fire", "feu" -> SpellElement.FIRE
                        "water", "eau" -> SpellElement.WATER
                        "earth", "terre" -> SpellElement.EARTH
                        "air", "wind" -> SpellElement.AIR
                        else -> null
                    }
                val resistance = value?.toIntOrNull()
                when {
                    element == null || resistance == null -> null
                    // [-100, 90] = the engine's resistance range (+90% cap, −100% weakness floor); reject
                    // out-of-range input instead of silently clamping it (unlike the unbounded prior parse).
                    resistance !in -100..90 -> fail("--boss-resistances '$name' must be between -100 and 90, got $resistance")
                    else -> element to resistance
                }
            }.toMap()
            .ifEmpty { fail("Could not parse --boss-resistances; use e.g. 'fire:0,water:-90,earth:50,air:50'") }
    }

    // --- boss mode (auto-fills the max-damage scenario from a monster's resistances) ---

    private val boss: String? by option(
        "--boss",
        help =
            "Boss mode: target a monster by name (French, like --forced-items). Auto-fills the per-element " +
                "resistances from the bestiary and switches to max-damage mode (the objective auto-picks the " +
                "best playable element). Overrides --boss-resistances / --enemy-resistance."
    )

    // Validated eagerly (the check runs even without --boss) so a typo like --boss-element banana is
    // rejected up front; 'auto' (the default) means: let the boss-aware objective pick the element.
    private val bossElement: String by option(
        "--boss-element",
        help =
            "Boss mode: which element to attack with — 'auto' (default, let the objective pick the best vs " +
                "the boss) or one of fire/water/earth/air."
    ).default("auto")
        .check("--boss-element must be auto/fire/water/earth/air") {
            it.trim().lowercase() in setOf("auto", "fire", "water", "earth", "air", "wind")
        }

    private val bossDifficulty: Double by option(
        "--boss-difficulty",
        help =
            "Boss mode: dungeon difficulty as an HP multiplier (e.g. 2 = twice the HP). Only scales the " +
                "'turns to kill' figure — a boss's elemental resistances are constant across difficulty, so " +
                "the optimal build is unchanged. Default 1."
    ).double().default(1.0).check("Difficulty multiplier must be > 0") { it > 0.0 }

    override fun run() {
        // Contradictory level bounds (min above max) match no normal item — the engine's level
        // filter keeps items with min <= itemLevel <= max — so the solver would silently fall back
        // to the only level-agnostic slots (pets/mounts) and return a near-empty nonsense build.
        // Fail fast with a clear message instead of running the solver on impossible constraints.
        if (minLevelWanted > maxLevelWanted) {
            throw PrintMessage(
                message =
                    "The min level ($minLevelWanted) cannot be greater than the max level ($maxLevelWanted). " +
                        "Lower --min-level or raise --max-level.",
                printError = true
            )
        }
        // Boss mode: resolving a --boss switches to max-damage and fills the scenario's per-element
        // resistances from the bestiary, so the fused boss-aware objective picks the best playable element.
        val targetBoss = boss?.let { resolveBoss(it) }
        val mode = if (targetBoss != null) ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE else computationMode
        val character = Character(characterClass ?: CharacterClass.UNKNOWN, maxLevelWanted, minLevelWanted)
        val targetStats =
            TargetStats(
                listOfNotNull(
                    paWanted,
                    pmWanted,
                    hpWanted,
                    pwWanted,
                    criticalHitWanted,
                    rangeWanted,
                    masteryElementWanted,
                    masteryBackWanted,
                    controlWanted,
                    masteryEarthWanted,
                    masteryFireWanted,
                    masteryWaterWanted,
                    masteryWindWanted,
                    masteryMeleeWanted,
                    masteryBerserkWanted,
                    masteryHealingWanted,
                    masteryBackWanted,
                    masteryCriticalWanted,
                    masteryDistanceWanted,
                    resistanceCriticalWanted,
                    resistanceBackWanted,
                    resistanceElementaryWanted,
                    resistanceElementaryFireWanted,
                    resistanceElementaryWaterWanted,
                    resistanceElementaryEarthWanted,
                    resistanceElementaryWindWanted,
                    wisdomWanted,
                    lockWanted,
                    dodgeWanted,
                    prospectionWanted,
                    initiativeWanted,
                    willpowerWanted,
                    receivedArmorPercentageWanted,
                    blockPercentageWanted,
                    armorGivenPercentageWanted
                )
            )
        // Max-damage mode optimizes the attack scenario, so target stats are optional there (they only
        // act as hard AP/MP/range/… constraints); every other mode needs at least one target.
        if (targetStats.isEmpty() && mode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
            throw PrintMessage(
                message = "No input stats given, stopping the program... Use --help flag for more information",
                printError = true
            )
        }
        // Max-damage rotates the class's real spell kit, so an unset class (UNKNOWN) yields a build with an
        // empty, meaningless rotation — require --class up front rather than silently producing nonsense.
        if (mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE && characterClass == null) {
            throw PrintMessage(
                message = "Max-damage mode needs a class — pass --class so the engine knows which spells to cast.",
                printError = true
            )
        }

        val baseScenario =
            DamageScenario(
                element = scenarioElement,
                rangeBand = scenarioRange,
                orientation = scenarioOrientation,
                berserk = scenarioBerserk,
                healing = scenarioHealing,
                critCapPercent = scenarioCritCap,
                targetResistancePercent = scenarioEnemyResistance,
                baseDamage = scenarioBaseDamage
            )
        // --boss takes precedence over --boss-resistances / --enemy-resistance: a forced --boss-element
        // pins a single element (clearing any manual elementResistances), otherwise all four are filled
        // so the objective auto-picks the best one. With no boss, the manual --boss-resistances apply.
        val bossElementChoice = if (targetBoss != null) parseBossElement(bossElement) else null
        val damageScenario =
            when {
                targetBoss != null && bossElementChoice != null -> baseScenario.against(targetBoss, bossElementChoice)
                targetBoss != null -> baseScenario.againstAllElements(targetBoss)
                else -> baseScenario.copy(elementResistances = bossResistances)
            }

        runBlocking {
            val progressBar = progressBar(terminal)
            val bestCombination =
                WakfuBestBuildFinderAlgorithm
                    .run(
                        WakfuBestBuildParams(
                            character = character,
                            targetStats = targetStats,
                            searchDuration = searchDuration,
                            stopWhenBuildMatch = stopWhenBuildMatch,
                            maxRarity = maxRarity,
                            forcedItems = forceItems,
                            excludedItems = excludedItems,
                            scoreComputationMode = mode,
                            useRunes = !noRunes,
                            damageScenario = damageScenario
                        )
                    ).buffer(CONFLATED)
                    .onStart { progressBar.execute() }
                    .onEach {
                        progressBar.update {
                            context =
                                if (mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                                    "expected damage so far: ${it.matchPercentage}"
                                } else {
                                    "${it.matchPercentage}% match found so far"
                                }
                            completed = it.progressPercentage.toLong()
                        }
                        delay(1000L)
                    }.onCompletion {
                        progressBar.stop()
                    }.last()
                    .individual
                    .also {
                        terminal.println("Research result")
                        terminal.println("Equipment")
                        terminal.println(it.equipments.asASCIITable())
                        if (it.runes.isNotEmpty()) {
                            terminal.println("Runes")
                            terminal.println(it.runes.asRunesASCIITable())
                        }
                        terminal.println("Skills")
                        terminal.println("Intelligence")
                        terminal.println(it.characterSkills.intelligence.asASCIITable())
                        terminal.println("Strength")
                        terminal.println(it.characterSkills.strength.asASCIITable())
                        terminal.println("Luck")
                        terminal.println(it.characterSkills.luck.asASCIITable())
                        terminal.println("Agility")
                        terminal.println(it.characterSkills.agility.asASCIITable())
                        terminal.println("Major")
                        terminal.println(it.characterSkills.major.asASCIITable())
                    }

            if (mode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                terminal.println(spellRotationReport(bestCombination, character, damageScenario))
                if (targetBoss != null) {
                    terminal.println(bossSummary(targetBoss, bestCombination, character, damageScenario, bossDifficulty))
                }
            }

            if (createZenithBuild) {
                try {
                    val zenithInputParameters =
                        ZenithInputParameters(
                            character = character.copy(characterSkills = bestCombination.characterSkills),
                            equipments = bestCombination.equipments,
                            runes = bestCombination.runes
                        )
                    val link = zenithInputParameters.createZenithBuild()
                    terminal.println(TextStyles.bold("Zenith Build Link: $link"))
                } catch (exception: Exception) {
                    logger.error(exception) { "An error occurred during the creation of the Zenith build :(" }
                }
            }

            exitProcess(0)
        }
    }

    private fun List<Equipment>.asASCIITable() =
        table {
            borderType = SQUARE_DOUBLE_SECTION_SEPARATOR
            borderStyle = rgb("#4b25b9")
            align = TextAlign.RIGHT
            tableBorders = Borders.NONE
            header {
                style = TextColors.brightRed + TextStyles.bold
                row("Equipment name", "Equipment type", "Level", "Rarity") { cellBorders = Borders.BOTTOM }
            }
            body {
                style = TextColors.green
                column(0) {
                    align = TextAlign.LEFT
                    cellBorders = Borders.ALL
                    style = TextColors.brightBlue
                }
                column(3) {
                    cellBorders = Borders.LEFT_BOTTOM
                    style = TextColors.brightBlue
                }
                column(4) {
                    style = TextColors.brightBlue
                }
                rowStyles(TextStyle(), TextStyles.dim.style)
                cellBorders = Borders.TOP_BOTTOM
                this@asASCIITable.forEach {
                    row(it.name.en, it.itemType, it.level, it.rarity)
                }
            }
        }

    // Socketed runes per item. The enchantment level is capped by the carrier item's level
    // (Ankama's table), so it is derived per row from RuneType.maxLevel(item level).
    private fun Map<Equipment, List<RuneType>>.asRunesASCIITable() =
        table {
            borderType = SQUARE_DOUBLE_SECTION_SEPARATOR
            borderStyle = rgb("#4b25b9")
            align = TextAlign.RIGHT
            tableBorders = Borders.NONE
            header {
                style = TextColors.brightRed + TextStyles.bold
                row("Item", "Rune", "Count", "Enchant level") { cellBorders = Borders.BOTTOM }
            }
            body {
                style = TextColors.green
                column(0) {
                    align = TextAlign.LEFT
                    cellBorders = Borders.ALL
                    style = TextColors.brightBlue
                }
                rowStyles(TextStyle(), TextStyles.dim.style)
                cellBorders = Borders.TOP_BOTTOM
                this@asRunesASCIITable.forEach { (equipment, runes) ->
                    runes
                        .groupBy { it.characteristic }
                        .forEach { (characteristic, sameStat) ->
                            row(equipment.name.en, characteristic, sameStat.size, sameStat.first().maxLevel(equipment.level))
                        }
                }
            }
        }
}

/**
 * Human-readable "best spells for this build's AP" report appended to the max-damage CLI output.
 * Uses the build's real AP and the class's actual spells in the scenario element (see
 * [SpellRotationOptimizer]).
 */
private fun spellRotationReport(
    build: BuildCombination,
    character: Character,
    scenario: DamageScenario,
): String {
    // bestSequencedRotation is boss-aware (picks the best playable element vs the boss profile) AND
    // sequences resistance debuffs first when they raise the turn's total.
    val rotation: SpellRotation = SpellRotationOptimizer.bestSequencedRotation(build, character, character.clazz, scenario)
    val chosenElement = rotation.element?.name?.lowercase() ?: scenario.element.name.lowercase()
    val header = "Optimal spell rotation ($chosenElement, ${rotation.apBudget} AP)"
    if (rotation.isEmpty) {
        return "$header\n  No playable damage spells for ${character.clazz.name.lowercase()} in the " +
            "candidate element(s) — try another --scenario-element / --boss-resistances."
    }
    val debuffLines =
        rotation.debuffCasts.joinToString("") { cast ->
            "  ↳ debuff: ${cast.spell.name.en} (${cast.apCost} AP, −${cast.spell.targetResistanceReductionFlat} res)\n"
        } +
            // Show the post-all-debuffs resistance ONCE — not on every line (it's the cumulative final
            // value, not what each individual debuff reaches).
            if (rotation.debuffCasts.isNotEmpty() && rotation.effectiveResistancePercent != null) {
                "  → after debuffs, target resistance is ${rotation.effectiveResistancePercent}%\n"
            } else {
                ""
            }
    val lines =
        rotation.casts.joinToString("\n") { cast ->
            "  ${cast.count}× ${cast.spell.name.en} " +
                "(${cast.apCost} AP, ~${cast.expectedDamagePerCast.toLong()} dmg/cast) → ~${cast.totalExpectedDamage.toLong()}"
        }
    return "$header\n$debuffLines$lines\n" +
        "  Total: ~${rotation.totalExpectedDamage.toLong()} expected damage/turn " +
        "(${rotation.apUsed}/${rotation.apBudget} AP used)\n" +
        "  Note: per-turn cast limits aren't modeled yet — treat as an upper-bound suggestion."
}

/** Resolves the `--boss` query to a monster, or fails with a helpful message listing close names. */
private fun resolveBoss(query: String): Monster {
    WakfuBestBuildFinderAlgorithm.findMonster(query)?.let { return it }
    val suggestions =
        WakfuBestBuildFinderAlgorithm.monsters
            .filter {
                it.name.fr
                    .lowercase()
                    .contains(query.lowercase().take(3))
            }.sortedWith(compareByDescending<Monster> { it.rank }.thenByDescending { it.level })
            .take(8)
            .joinToString("\n") { "  - ${it.name.fr} (lvl ${it.level})" }
    throw PrintMessage(
        message =
            "No boss matching \"$query\" in the bestiary." +
                if (suggestions.isNotEmpty()) "\nDid you mean:\n$suggestions" else "",
        printError = true
    )
}

/** Parses `--boss-element`: 'auto' → null (let the boss-aware objective pick); else the named [SpellElement]. */
private fun parseBossElement(value: String): SpellElement? =
    when (val v = value.trim().lowercase()) {
        "auto" -> null
        "wind", "air" -> SpellElement.AIR
        "fire" -> SpellElement.FIRE
        "water" -> SpellElement.WATER
        "earth" -> SpellElement.EARTH
        else -> throw PrintMessage("Unknown --boss-element \"$v\" (use auto/fire/water/earth/air).", printError = true)
    }

/**
 * Human-readable boss report: HP, the four-element resistance profile (▶ marks the chosen element), and an
 * estimate of how many **turns** of the build's rotation it takes to kill the boss (scaled by difficulty).
 * Reuses the same boss-aware sequenced rotation the engine ranks builds by, so the element and per-turn
 * damage shown match what was optimized.
 */
private fun bossSummary(
    monster: Monster,
    build: BuildCombination,
    character: Character,
    scenario: DamageScenario,
    difficulty: Double,
): String {
    val rotation = SpellRotationOptimizer.bestSequencedRotation(build, character, character.clazz, scenario)
    // rotation.element is the common-lib enum; map it 1:1 onto the domain enum the scenario/profile use.
    val chosenElement = rotation.element?.let { SpellElement.valueOf(it.name) } ?: scenario.element
    val perTurn = rotation.totalExpectedDamage.toBigDecimal()
    val turns = BossDisplay.turnsToKill(monster, perTurn, difficulty)
    val effectiveHp = BossDisplay.effectiveHp(monster, difficulty)
    val hpLabel = if (difficulty != 1.0) "$effectiveHp HP (×$difficulty)" else "${monster.hp} HP"
    val profile =
        SpellElement.entries.joinToString("  ") {
            val marker = if (it == chosenElement) "▶" else " "
            "$marker${it.name.lowercase().replaceFirstChar(Char::uppercase)} ${monster.resistancePercent(it)}%"
        }
    val killLabel = if (turns <= 0) "no playable damage rotation" else "≈ $turns turn(s) to kill"
    return buildString {
        appendLine()
        appendLine("Boss: ${monster.name.fr} (lvl ${monster.level}, $hpLabel)")
        appendLine("Resistances: $profile")
        append("Best element: ${chosenElement.name.lowercase()} → ~${perTurn.toLong()} expected damage/turn, $killLabel")
    }
}

private fun progressBar(terminal: Terminal): ThreadProgressTaskAnimator<String> =
    progressBarContextLayout {
        text { context }
        progressBar()
        percentage()
    }.animateOnThread(terminal, "", total = 100L)

private fun Assignable<*>.asASCIITable() =
    table {
        borderType = SQUARE_DOUBLE_SECTION_SEPARATOR
        borderStyle = rgb("#4b25b9")
        align = TextAlign.RIGHT
        tableBorders = Borders.NONE
        body {
            style = TextColors.green
            column(0) {
                align = TextAlign.LEFT
                cellBorders = Borders.ALL
                style = TextColors.brightBlue
            }
            column(1) {
                cellBorders = Borders.LEFT_BOTTOM
                style = TextColors.brightBlue
            }
            rowStyles(TextStyle(), TextStyles.dim.style)
            cellBorders = Borders.TOP_BOTTOM
            this@asASCIITable.getCharacteristics().forEach {
                row(it.name, "${it.pointsAssigned}/${if (it.maxPointsAssignable == Int.MAX_VALUE) "∞" else it.maxPointsAssignable}")
            }
        }
    }

private fun NullableOption<Pair<String, String>, Pair<String, String>>.toTargetStat(characteristic: Characteristic): NullableOption<TargetStat, TargetStat> =
    convert { (value, weight) ->
        val valueInt = value.toIntOrNull() ?: fail("'$value' should be a number")

        TargetStat(
            characteristic = characteristic,
            target = valueInt,
            userDefinedWeight = weight.toIntOrNull() ?: 1
        )
    }
