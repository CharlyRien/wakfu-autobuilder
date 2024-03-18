package me.chosante.autobuilder

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.NullableOption
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.options.splitPair
import com.github.ajalt.clikt.parameters.types.int
import de.vandermeer.asciitable.AsciiTable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import me.chosante.ZenithInputParameters
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
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
import me.chosante.common.Rarity
import me.chosante.common.skills.Assignable
import me.chosante.createZenithBuild
import me.tongfei.progressbar.ConsoleProgressBarConsumer
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle

private val logger = KotlinLogging.logger {}
internal const val VERSION = "1.83.1.21"

fun main(args: Array<String>) = WakfuAutobuild().main(args)

private val additionalHelpOnStats =
    """note that, it is also possible to pass an optional weight with the following format 
        |-> x:y | x being the number wanted, y the weight of the statistic you want to put (default is 1)
    """.trimMargin()

private class WakfuAutobuild :
    CliktCommand(
        help = """This program helps Wakfu players easily find the best combination of equipment for their character 
            |at a given level by taking into account their desired stats and providing build suggestions
            |based on their input.
            |
            |Current Wakfu data version used: $VERSION
            |
            |Here's an example of usage in your terminal: 
            |./wakfu-autobuilder-cli.exe --level 110 --action-point 11 --movement-point 5 --mastery-distance 500 --hp 2000 --range 2 --cc 30 --class cra --create-zenith-build --duration 60
        """.trimMargin(),
        name = "Wakfu Autobuilder version: $VERSION"
    ) {

    private val maxLevelWanted: Int by option(
        "--max-level",
        "--max-niveau",
        help = "The max level of the character (no items selected will be above this level)"
    )
        .int()
        .default(230)
        .check("Level should be between 1 and 230") { it in 1..230 }

    private val minLevelWanted: Int by option(
        "--min-level",
        "--min-niveau",
        help = "The min level of the character (no items selected will be under this level)"
    )
        .int()
        .default(1)
        .check("Level should be between 1 and 230") { it in 1..230 }

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
        help = "Used to tell the algorithm to not take items into account that above this rarity, " +
            "here the list of value possible in order: ${Rarity.entries}"
    ).convert { Rarity.valueOf(it.uppercase()) }
        .default(Rarity.EPIC)

    private val forceItems: List<String> by option(
        "--items-a-force",
        "--forced-items",
        help = "Used to tell the algorithm to force specific items to be in the final build," +
            " the names have to be French for now, can be used like that: --forced-items 'Gelano','Amulette du Bouftou',..."
    ).split(",").default(listOf())

    private val excludedItems: List<String> by option(
        "--items-a-exclure",
        "--excluded-items",
        help = "Used to tell the algorithm to exclude specific items to not be in the final build," +
            " the names have to be in french for now, can be used like that: --excluded-items 'Gelano','Amulette du Bouftou',..."
    ).split(",").default(listOf())

    private val paWanted: TargetStat? by option(
        names = arrayOf("--ap", "--action-point", "--pa"),
        help = "Number of action points wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(ACTION_POINT)

    private val rangeWanted: TargetStat? by option(
        names = arrayOf("--range", "--portee", "--po"),
        help = "Number of range points wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(RANGE)

    private val criticalHitWanted: TargetStat? by option(
        names = arrayOf("--critical-hit", "--cc"),
        help = "Number of critical hit wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(CRITICAL_HIT)

    private val pmWanted: TargetStat? by option(
        names = arrayOf("--mp", "--movement-point", "--pm"),
        help = "Number of movement points wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MOVEMENT_POINT)

    private val pwWanted: TargetStat? by option(
        names = arrayOf("--wp", "--wakfu-point", "--pw"),
        help = "Number of wakfu points wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MOVEMENT_POINT)

    private val masteryElementWanted: TargetStat? by option(
        names = arrayOf("--mastery-elementary", "--maitrise-elementaire"),
        help = "Number of elementary mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_ELEMENTARY)

    private val masteryCriticalWanted: TargetStat? by option(
        names = arrayOf("--mastery-critical", "--maitrise-critique"),
        help = "Number of critical mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_CRITICAL)

    private val masteryWaterWanted: TargetStat? by option(
        names = arrayOf("--mastery-water", "--maitrise-eau"),
        help = "Number of water mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_ELEMENTARY_WATER)

    private val masteryFireWanted: TargetStat? by option(
        names = arrayOf("--mastery-fire", "--maitrise-feu"),
        help = "Number of fire mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_ELEMENTARY_FIRE)

    private val masteryWindWanted: TargetStat? by option(
        names = arrayOf("--mastery-wind", "--maitrise-air"),
        help = "Number of wind mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_ELEMENTARY_WIND)

    private val masteryEarthWanted: TargetStat? by option(
        names = arrayOf("--mastery-earth", "--maitrise-terre"),
        help = "Number of wind mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_ELEMENTARY_EARTH)

    private val masteryBackWanted: TargetStat? by option(
        names = arrayOf("--mastery-back", "--maitrise-dos"),
        help = "Number of back mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_BACK)

    private val masteryMeleeWanted: TargetStat? by option(
        names = arrayOf("--mastery-melee", "--maitrise-melee"),
        help = "Number of melee mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_MELEE)

    private val masteryBerserkWanted: TargetStat? by option(
        names = arrayOf("--mastery-berserk", "--maitrise-berserk"),
        help = "Number of berserk mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_BERSERK)

    private val masteryHealingWanted: TargetStat? by option(
        names = arrayOf("--mastery-healing", "--maitrise-soin"),
        help = "Number of healing mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_HEALING)

    private val resistanceCriticalWanted: TargetStat? by option(
        names = arrayOf("--resistance-critical", "--resistance-critique"),
        help = "Number of critical resistance wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_CRITICAL)

    private val resistanceBackWanted: TargetStat? by option(
        names = arrayOf("--resistance-back", "--resistance-dos"),
        help = "Number of back resistance wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_BACK)

    private val resistanceElementaryWanted: TargetStat? by option(
        names = arrayOf("--resistance-elementary", "--resistance-elementaire"),
        help = "Number of elementary resistance wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_ELEMENTARY)

    private val resistanceElementaryFireWanted: TargetStat? by option(
        names = arrayOf("--resistance-elementary-fire", "--resistance-elementaire-feu"),
        help = "Number of fire elementary resistance wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_ELEMENTARY_FIRE)

    private val resistanceElementaryWaterWanted: TargetStat? by option(
        names = arrayOf("--resistance-elementary-water", "--resistance-elementaire-eau"),
        help = "Number of water elementary resistance wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_ELEMENTARY_WATER)

    private val resistanceElementaryWindWanted: TargetStat? by option(
        names = arrayOf("--resistance-elementary-wind", "--resistance-elementaire-air"),
        help = "Number of wind elementary resistance wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_ELEMENTARY_WIND)

    private val resistanceElementaryEarthWanted: TargetStat? by option(
        names = arrayOf("--resistance-elementary-earth", "--resistance-elementaire-terre"),
        help = "Number of earth elementary resistance wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(RESISTANCE_ELEMENTARY_EARTH)

    private val controlWanted: TargetStat? by option(
        names = arrayOf("--control", "--controle"),
        help = "Number of control wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(CONTROL)

    private val wisdomWanted: TargetStat? by option(
        names = arrayOf("--wisdom", "--sagesse"),
        help = "Number of wisdom wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(WISDOM)

    private val dodgeWanted: TargetStat? by option(
        names = arrayOf("--dodge", "--esquive"),
        help = "Number of dodge wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(DODGE)

    private val lockWanted: TargetStat? by option(
        names = arrayOf("--lock", "--tacle"),
        help = "Number of lock wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(LOCK)

    private val prospectionWanted: TargetStat? by option(
        names = arrayOf("--prospection"),
        help = "Number of prospection wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(PROSPECTION)

    private val initiativeWanted: TargetStat? by option(
        names = arrayOf("--initiative"),
        help = "Number of initiative wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(INITIATIVE)

    private val willpowerWanted: TargetStat? by option(
        names = arrayOf("--willpower", "--volonte"),
        help = "Number of willpower wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(WILLPOWER)

    private val receivedArmorPercentageWanted: TargetStat? by option(
        names = arrayOf("--received-armor", "--armure-recue"),
        help = "Number of received armor percentage wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(RECEIVED_ARMOR_PERCENTAGE)

    private val masteryDistanceWanted: TargetStat? by option(
        names = arrayOf("--mastery-distance", "--maitrise-distance"),
        help = "Number of distance mastery wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(MASTERY_DISTANCE)

    private val hpWanted: TargetStat? by option(
        names = arrayOf("--hp", "--pdv", "--health-points", "--points-de-vie"),
        help = "Number of health points wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(HP)

    private val blockPercentageWanted: TargetStat? by option(
        names = arrayOf("--parade", "--block"),
        help = "percentage of parade wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(BLOCK_PERCENTAGE)

    private val armorGivenPercentageWanted: TargetStat? by option(
        names = arrayOf("--armor-given", "--armure-donnee"),
        help = "percentage of parade wanted. $additionalHelpOnStats"
    )
        .splitPair(delimiter = ":")
        .toTargetStat(GIVEN_ARMOR_PERCENTAGE)

    private val createZenithBuild: Boolean by option(
        "--create-zenith-build",
        help = "Use this flag to indicate that you want to create a zenith build for the equipment found"
    ).flag(default = false, defaultForHelp = "disabled")

    private val stopWhenBuildMatch: Boolean by option(
        "--stop-when-build-match",
        help = "Use this flag to indicate that you want to stop searching when you have 100% build match already found."
    ).flag(default = false, defaultForHelp = "disabled")

    override fun run() {
        val character = Character(characterClass ?: CharacterClass.UNKNOWN, maxLevelWanted, minLevelWanted)
        val targetStats = TargetStats(
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
        if (targetStats.isEmpty()) {
            throw PrintMessage(
                message = "No input stats given, stopping the program... Use --help flag for more information",
                printError = true
            )
        }

        runBlocking {
            val bestCombination = progressBar().use { progressBar ->
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
                            scoreComputationMode = computationMode
                        )
                    )
                    .buffer(CONFLATED)
                    .onEach {
                        progressBar.setExtraMessage("${it.matchPercentage}% match found so far")
                        progressBar.stepTo(it.progressPercentage.toLong())
                        delay(1000L)
                    }
                    .onCompletion {
                        progressBar.stepTo(progressBar.max)
                    }
                    .last()
                    .individual
                    .also {
                        println(
                            """Research result
                        |Given vs. Expected characteristics
                        |
                        |Equipment
                        |${it.equipments.asASCIITable()}
                        |
                        |Skills
                        |
                        |Intelligence
                        |${it.characterSkills.intelligence.asASCIITable()}
                        |
                        |Strength
                        |${it.characterSkills.strength.asASCIITable()}
                        |
                        |Luck
                        |${it.characterSkills.luck.asASCIITable()}
                        |
                        |Agility
                        |${it.characterSkills.agility.asASCIITable()}
                        |
                        |Major
                        |${it.characterSkills.major.asASCIITable()}
                            """.trimMargin()
                        )
                    }
            }

            if (createZenithBuild) {
                try {
                    val zenithInputParameters = ZenithInputParameters(
                        character = character.copy(characterSkills = bestCombination.characterSkills),
                        equipments = bestCombination.equipments
                    )
                    val link = zenithInputParameters.createZenithBuild()
                    println("Zenith Build Link: $link")
                } catch (exception: Exception) {
                    logger.error(exception) { "An error occurred during the creation of the Zenith build :(" }
                }
            }

            exitProcess(0)
        }
    }

    private fun List<Equipment>.asASCIITable() = with(AsciiTable()) {
        with(context) {
            setFrameLeftRightMargin(2)
            setFrameTopBottomMargin(2)
            width = 100
        }
        addRule()
        addRow("Equipment name", "equipment type", "level", "rarity")
        this@asASCIITable.forEach {
            addRule()
            addRow(it.name.en, it.itemType, it.level, it.rarity)
            addRule()
        }
        addRule()
        render()
    }
}

private fun progressBar(): ProgressBar {
    return ProgressBarBuilder()
        .hideEta()
        .setTaskName("Best build research")
        .setInitialMax(100)
        .setStyle(ProgressBarStyle.UNICODE_BLOCK)
        .setConsumer(ConsoleProgressBarConsumer(System.err))
        .build()
}

private fun Assignable<*>.asASCIITable() = with(AsciiTable()) {
    val characterSkills = this@asASCIITable
    with(context) {
        setFrameLeftRightMargin(2)
        setFrameTopBottomMargin(2)
        width = 100
    }
    characterSkills
        .getCharacteristics().forEach {
            addRule()
            addRow(
                it.name,
                "${it.pointsAssigned}/${if (it.maxPointsAssignable == Int.MAX_VALUE) "∞" else it.maxPointsAssignable}"
            )
        }
    addRule()
    render()
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
