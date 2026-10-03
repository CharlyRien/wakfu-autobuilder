package me.chosante.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.SublimationRarity

enum class Lang(
    val label: String,
    val resourceSuffix: String,
) {
    EN("EN", "en"),
    FR("FR", "fr"),
    ES("ES", "es"),
}

/** The active UI language, provided once at the app root and read by [tr]. */
val LocalLang = staticCompositionLocalOf { Lang.EN }

/**
 * Every user-facing static string, keyed by name. The translations themselves live in
 * `src/main/resources/i18n/strings_<lang>.properties` (one file per language, UTF-8) rather than
 * in this enum, so adding a language is "add a properties file", not "edit this file".
 */
enum class Tr {
    // Brand / top bar
    CLASS,
    LEVEL_SHORT,
    MIN_SHORT,
    LEVEL_RANGE_INVALID,
    FORCED_ITEM_NOT_EQUIPPABLE,
    FORCED_SUBLIMATION_RARITY_INVALID,
    SUBLIMATION_FORCED_AND_EXCLUDED,
    FORCED_ITEM_ALSO_EXCLUDED,
    FORCED_ITEMS_SLOT_CONFLICT,
    FORCED_WEAPONS_CONFLICT,
    FORCED_ITEM_RARITY_BUDGET,
    FORCED_SUBLIMATION_NO_CARRIER,
    FORCED_SUBLIMATIONS_EXCEED_CAPACITY,
    REQUEST_ERRORS_TITLE,
    REQUEST_ERRORS_INTRO,
    REQUEST_ERRORS_DISMISS,
    PROGRESS,
    PRELOAD_WARMUP,
    MATCH,
    SEARCH,
    STOP,

    // Zone headers
    ZONE_REQUEST,
    ZONE_REQUEST_HINT,
    ZONE_BUILD,
    ZONE_BUILD_IDLE,
    ZONE_BUILD_SEARCHING,
    ZONE_BUILD_DONE,
    ZONE_STATS,
    ZONE_STATS_HINT,

    // Request panel
    SEARCH_MODE,
    MODE_MASTERIES,
    MODE_MASTERIES_SUB,
    MODE_PRECISION,
    MODE_PRECISION_SUB,
    MODE_MAX_DAMAGE,
    MODE_MAX_DAMAGE_SUB,
    DAMAGE_SCENARIO,
    SCENARIO_ELEMENT,
    SCENARIO_RANGE,
    SCENARIO_ORIENTATION,
    SCENARIO_BERSERK,
    SCENARIO_HEALING,
    SCENARIO_CRIT_CAP,
    SCENARIO_ENEMY_RES,
    SCENARIO_ROLE,
    ROLE_DISTANCE_DPS,
    ROLE_MELEE_DPS,
    ROLE_TANK,
    SCENARIO_SURVIVAL_FLOOR,
    SCENARIO_MIN_EHP,
    BOSS,
    BOSS_NONE_HINT,
    BOSS_PICK,
    BOSS_CHANGE,
    BOSS_REMOVE,
    BOSS_LEVEL_SHORT,
    BOSS_ELEMENT,
    BOSS_ELEMENT_AUTO,
    BOSS_DIFFICULTY,
    CHOOSE_BOSS_TITLE,
    SEARCH_BOSSES,
    NO_MATCHING_BOSS,
    TURNS_TO_KILL,
    EXPECTED_DAMAGE,
    SPELL_ROTATION,
    SPELL_ROTATION_SUB,
    SPELL_ROTATION_PER_TURN,
    SPELL_ROTATION_EMPTY,
    SPELL_ROTATION_NOTE,
    TARGET_STATS,
    MAXIMIZED_MASTERIES,
    NO_MASTERY_SELECTED,
    DI_AUTOMAX_HINT,
    PRIORITY,
    PRIORITY_HINT,
    ADD_TARGET_STAT,
    KIND_EXACT,
    KIND_MAXIMIZE,
    MAX_RARITY,
    RARITIES,
    RARITIES_SUB,
    SEARCH_DURATION,
    SEARCH_DURATION_SUB,
    SECONDS_SHORT,
    STOP_AT_MATCH,
    SEARCH_NO_RESULT,
    FORCED_ITEMS,
    REQUIRE_ITEM_CHIP,
    EXCLUDED_ITEMS,
    BAN_ITEM_CHIP,
    SUBLIMATIONS_RUNES,
    SOLVER_PICKS_SUBLIMATIONS,
    SUBLIMATION_LEVEL_CAP,
    SUBLIMATION_LEVEL_ALL,
    SUBLIMATION_LEVEL_UP_TO,
    SUBLIMATION_TIER_SHORT,
    SUBLIMATION_STACK_SHORT,
    SUBLIMATION_LEVEL_CAP_HINT,
    FORCED_SUBLIMATIONS,
    ADD_SUBLIMATION_CHIP,
    CHOSEN_SUBLIMATIONS,
    REQUIRE_SUBLIMATION_TITLE,
    EXCLUDED_SUBLIMATIONS,
    BAN_SUBLIMATION_CHIP,
    EXCLUDE_SUBLIMATION_TITLE,
    SEARCH_SUBLIMATIONS,
    NO_MATCHING_SUBLIMATION,
    EXCLUDE_ALL_SUBLIMATIONS_RARITY,
    UNEXCLUDE_ALL_SUBLIMATIONS_RARITY,
    FORCED_PASSIVES,
    ADD_PASSIVE_CHIP,
    CHOSEN_PASSIVES,
    REQUIRE_PASSIVE_TITLE,
    SEARCH_PASSIVES,
    NO_MATCHING_PASSIVE,
    PASSIVE_SLOTS_FULL,
    EDIT_RUNES,
    EDIT_RUNES_TITLE,
    RUNE_SOCKETS_LABEL,
    SEARCH_RUNES,
    NO_MATCHING_RUNE,
    LOCK_CURRENT_RUNES,
    RUNES_PER_ITEM_HINT,
    RUNES_ALLGOLD_HINT,

    // Paperdoll
    PREPARING_OR_TOOLS_MODEL,
    FIRST_RESULT_HINT,
    EMPTY,

    // Empty-slot explanations ("explain the solver's choices"): %s = the sublimation's localized name.
    EMPTY_SLOT_SUB_HINT,
    EMPTY_SLOT_NO_GAIN_HINT,
    LEVEL_PREFIX_LONG,
    LEVEL_PREFIX_SHORT,
    DISCLAIMER,
    APP_VERSION_LABEL,
    GAME_DATA_LABEL,
    SLOT_HELMET,
    SLOT_AMULET,
    SLOT_EPAULETTES,
    SLOT_BREASTPLATE,
    SLOT_CAPE,
    SLOT_EMBLEM,
    SLOT_BELT,
    SLOT_RING_I,
    SLOT_RING_II,
    SLOT_BOOTS,
    SLOT_WEAPON,
    SLOT_SECOND_WEAPON,
    SLOT_PET,
    SLOT_MOUNT,

    // Stats panel
    BUILD_MATCH,
    BUILD_MASTERY,
    BUILD_MASTERY_HINT,
    MASTERY_SHORT,
    OPTIMAL_PROVEN,
    BEST_FOUND,
    NOT_OPTIMAL_HINT,
    NOT_OPTIMAL_STRUCTURAL_HINT,

    // Max-damage certificate proof state (P4.4). The %s is the elapsed time ("2 min 10 s").
    PROVING_OPTIMALITY,
    PROOF_CONSTRUCTING,
    PROVEN_WITHIN,
    PROOF_REFINING,
    PROOF_STAGE_WORLD_TREE,
    PROOF_STAGE_AFTER_RELAXED,
    PROOF_STAGE_AFTER_NO_COND,
    PROOF_STAGE_AFTER_COARSE,
    PROOF_STAGE_AFTER_REFINE,
    PROOF_STAGE_AFTER_SECONDARY,
    PROOF_STAGE_FINALIZING,
    PROOF_STAGE_CP_PROBE,
    PROOF_STAGE_CARRIER_CLOSURE,
    PROOF_UNAVAILABLE_FORCED,
    MASTERY_SUMMARY,
    MASTERY_TOTAL,
    BUILD_SHEET_TITLE,
    BUILD_SHEET_EMPTY,
    MASTERY_ELEMENTALS,
    MASTERY_SPECIALIZED,
    MASTERY_INCIDENTAL,
    DESIRED_VS_ACHIEVED,
    TAG_EXACT,
    TAG_MAXIMIZE,
    SKILL_ALLOCATION,
    BRANCHES_COUNT,
    OPEN_IN_ZENITH,
    VIEW_AS_DAMAGE,
    OPENING,
    COPY_BUILD_LINK,
    EXPORT_BUILD,
    NO_BUILD_YET,
    NO_BUILD_HINT,
    BRANCH_INTELLIGENCE,
    BRANCH_STRENGTH,
    BRANCH_AGILITY,
    BRANCH_LUCK,
    BRANCH_MAJOR,
    SKILL_LEFTOVER_WARNING,

    // Modals
    ADD_TARGET_STAT_TITLE,
    FILTER_STATS,
    STAT_GROUP_CORE,
    STAT_GROUP_MASTERIES,
    STAT_GROUP_RESISTANCES,
    STAT_GROUP_SECONDARY,
    NO_MATCHING_STAT,
    REQUIRE_ITEM_TITLE,
    BAN_ITEM_TITLE,
    SEARCH_ITEMS,
    NO_MATCHING_ITEM,
    EQUIPPABLE_ONLY,
    RARITY_ALL,
    DONE,
    REQUIRE,
    BAN,
    RUNES,
    LOADING_ITEMS,

    // Import-build dialog
    IMPORT_DIALOG_TITLE,
    IMPORT_DIALOG_HINT,
    IMPORT_PLACEHOLDER,
    IMPORT_PASTE,
    IMPORT_INVALID,
    IMPORT_CONFIRM,
    IMPORTED_BUILD_NAME,

    // Toasts (read off the composition by the state holder)
    TOAST_ZENITH_COPIED,
    TOAST_ZENITH_READY,
    TOAST_BUILD_SAVED,
    TOAST_BUILD_DUPLICATED,
    TOAST_BUILD_EXPORTED,
    TOAST_BUILD_IMPORTED,
    TOAST_RUNES_LOCKED,
    TOAST_FORCED_ITEMS_REMOVED,

    // Navigation / active build
    NAV_BUILDER,
    NAV_LIBRARY,
    NEW_BUILD,
    ACTIVE_BUILD_EDITING,
    BACK,

    // Save dialog
    SAVE_BUILD,
    SAVE_DIALOG_TITLE,
    SAVE_NAME_LABEL,
    SAVE_NOTE_LABEL,
    SAVE,
    SAVE_AS_NEW,
    UPDATE_BUILD,
    SAVE_NAME_TAKEN,
    SAVE_UPDATE_HINT,
    CANCEL,

    // Library
    LIBRARY_TITLE,
    LIBRARY_SUBTITLE,
    IMPORT_BUILD,
    LIBRARY_EMPTY,
    LIBRARY_EMPTY_HINT,
    LIBRARY_SEARCH,
    LIBRARY_NO_MATCH,
    LIBRARY_COUNT,
    LIBRARY_ALL_BUILDS,
    LIBRARY_CLASSES,
    LIBRARY_TAGS,
    LIBRARY_SORT,
    SORT_NEWEST,
    SORT_OLDEST,
    SORT_NAME,
    SORT_LEVEL,
    LIBRARY_GROUP_BY_CLASS,
    LIBRARY_CLEAR_FILTERS,
    LIBRARY_FOLDERS,
    LIBRARY_UNFILED,
    FOLDER_LABEL,
    FOLDER_NONE,
    FOLDER_NEW,
    RENAME_FOLDER_TITLE,
    DELETE_FOLDER_TITLE,
    DELETE_FOLDER_HINT,
    TOAST_FOLDER_RENAMED,
    TOAST_FOLDERS_MERGED,
    TOAST_FOLDER_DELETED,
    ACTION_LOAD,
    ACTION_COMPARE,
    ACTION_DUPLICATE,
    ACTION_RENAME,
    ACTION_DELETE,

    /** Suffix appended to a duplicated build's name, e.g. "Cra 110 (copy)". */
    DUPLICATE_SUFFIX,

    // Edit / delete dialogs
    EDIT_BUILD_TITLE,
    TAGS_LABEL,
    TAG_ADD_PLACEHOLDER,
    TAG_ADD,
    TAG_CREATE,
    TAG_NONE_LEFT,
    TAG_NEW,
    CREATE_TAG_TITLE,
    RENAME_TAG_TITLE,
    DELETE_TAG_TITLE,
    DELETE_TAG_HINT,
    TOAST_TAG_RENAMED,
    TOAST_TAGS_MERGED,
    TOAST_TAG_DELETED,
    DELETE_TITLE,
    DELETE_HINT,

    // Re-search guard
    RESEARCH_TITLE,
    RESEARCH_HINT,
    RESEARCH_CONFIRM,

    // Compare view
    COMPARE_TITLE,
    COMPARE_PICK,
    COMPARE_ADD,
    COMPARE_BETTER,
    COMPARE_EQUAL,
    COMPARE_EMPTY,
    COMPARE_STAT,
    COMPARE_ENGINE_SCORE,
    COMPARE_GROUP_DAMAGE,
    COMPARE_GROUP_OTHER,
    COMPARE_SPELL_DAMAGE,
    COMPARE_SPELLS_MIXED_CLASS,

    // What's-new dialog (once-per-version release notes)
    WHATS_NEW_TITLE,
    WHATS_NEW_FEATURES,
    WHATS_NEW_FIXES,
    WHATS_NEW_PERF,
    WHATS_NEW_GOT_IT,

    // Class spells & passives tab (build-result region)
    TAB_DISCOVERED_BUILD,
    TAB_CLASS_SPELLS,
    CLASS_SPELLS_TITLE,
    CLASS_SPELLS_PASSIVES,
    PASSIVE_IN_BUILD,
    CLASS_SPELLS_EMPTY,
    CLASS_SPELLS_NO_BUILD,
    SPELL_EXPECTED_HIT,
    SPELL_BASE_HIT,
    SPELL_NONCRIT,
    SPELL_CRIT,
    SPELL_ALWAYS_CRITS,
    SPELL_PER_TURN_SUFFIX,
    SPELL_EXPECTED_HIT_INFO,
    SPELL_DAMAGE_RANGE_NOTE,
    SPELL_DAMAGE_DIST_MELEE_HINT,
    SPELL_VARIANT_BERSERK,
    SCENARIO_DAMAGE_BREAKDOWN,
    SPELL_BASE_HIT_INFO,
    ELEMENT_FIRE,
    ELEMENT_WATER,
    ELEMENT_EARTH,
    ELEMENT_AIR,
    ;

    fun value(lang: Lang): String = Translations.lookup(lang, name)
}

/**
 * Loads every `i18n/strings_<lang>.properties` bundle from the classpath (UTF-8) once, eagerly, at
 * first touch. [TranslationBundlesTest] (`gui-compose` test sources) guarantees in CI that every
 * [Tr] key exists in every bundle with matching placeholders, so [lookup] only needs a fallback to
 * English for robustness against a corrupted/partial jar at runtime — it never falls back to the
 * raw enum name, and a bundle that fails to load (missing resource) degrades to an empty map
 * instead of crashing the app.
 */
private object Translations {
    private val bundles: Map<Lang, Map<String, String>> = Lang.entries.associateWith(::loadBundle)

    private fun loadBundle(lang: Lang): Map<String, String> {
        val path = "/i18n/strings_${lang.resourceSuffix}.properties"
        val stream = Translations::class.java.getResourceAsStream(path) ?: return emptyMap()
        val properties = java.util.Properties()
        stream.use { properties.load(it.bufferedReader(Charsets.UTF_8)) }
        return properties.entries.associate { (key, value) -> key.toString() to value.toString() }
    }

    fun lookup(
        lang: Lang,
        key: String,
    ): String = bundles[lang]?.get(key) ?: bundles[Lang.EN]?.get(key).orEmpty()
}

@Composable
@ReadOnlyComposable
fun tr(key: Tr): String = key.value(LocalLang.current)

/**
 * Localized display name for **every** characteristic — exhaustive so the compiler guarantees no
 * stat shown on an item tooltip is left without a translation.
 */
fun Characteristic.label(lang: Lang): String =
    when (this) {
        Characteristic.MASTERY_ELEMENTARY -> localized(lang, fr = "Maîtrise Élémentaire", en = "Elemental Mastery", es = "Dominio elemental")
        Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT ->
            localized(lang, fr = "Maîtrise d'1 élément aléatoire", en = "Mastery of 1 Random Element", es = "Dominio de 1 elemento aleatorio")
        Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT ->
            localized(lang, fr = "Maîtrise de 2 éléments aléatoires", en = "Mastery of 2 Random Elements", es = "Dominio de 2 elementos aleatorios")
        Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT ->
            localized(lang, fr = "Maîtrise de 3 éléments aléatoires", en = "Mastery of 3 Random Elements", es = "Dominio de 3 elementos aleatorios")
        Characteristic.MASTERY_ELEMENTARY_WATER -> localized(lang, fr = "Maîtrise Eau", en = "Water Mastery", es = "Dominio agua")
        Characteristic.MASTERY_ELEMENTARY_WIND -> localized(lang, fr = "Maîtrise Air", en = "Air Mastery", es = "Dominio aire")
        Characteristic.MASTERY_ELEMENTARY_FIRE -> localized(lang, fr = "Maîtrise Feu", en = "Fire Mastery", es = "Dominio fuego")
        Characteristic.MASTERY_ELEMENTARY_EARTH -> localized(lang, fr = "Maîtrise Terre", en = "Earth Mastery", es = "Dominio tierra")
        Characteristic.MASTERY_DISTANCE -> localized(lang, fr = "Maîtrise Distance", en = "Distance Mastery", es = "Dominio distancia")
        Characteristic.MASTERY_CRITICAL -> localized(lang, fr = "Maîtrise Critique", en = "Critical Mastery", es = "Dominio crítico")
        Characteristic.MASTERY_BACK -> localized(lang, fr = "Maîtrise Dos", en = "Rear Mastery", es = "Dominio espalda")
        Characteristic.MASTERY_MELEE -> localized(lang, fr = "Maîtrise Mêlée", en = "Melee Mastery", es = "Dominio de melé")
        Characteristic.MASTERY_BERSERK -> localized(lang, fr = "Maîtrise Berserk", en = "Berserk Mastery", es = "Dominio berserker")
        Characteristic.MASTERY_HEALING -> localized(lang, fr = "Maîtrise Soin", en = "Healing Mastery", es = "Dominio cura")
        Characteristic.DAMAGE_INFLICTED -> localized(lang, fr = "Dommages infligés", en = "Damage Inflicted", es = "Daños infligidos")
        Characteristic.RESISTANCE_CRITICAL -> localized(lang, fr = "Résistance Critique", en = "Critical Resist", es = "Resistencia Crítica")
        Characteristic.RESISTANCE_BACK -> localized(lang, fr = "Résistance Dos", en = "Rear Resist", es = "Resistencia de Espalda")
        Characteristic.RESISTANCE_ELEMENTARY -> localized(lang, fr = "Résistance Élémentaire", en = "Elemental Resist", es = "Resistencia Elemental")
        Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT ->
            localized(lang, fr = "Résistance d'1 élément aléatoire", en = "Resist of 1 Random Element", es = "Resistencia de 1 elemento aleatorio")
        Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT ->
            localized(lang, fr = "Résistance de 2 éléments aléatoires", en = "Resist of 2 Random Elements", es = "Resistencia de 2 elementos aleatorios")
        Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT ->
            localized(lang, fr = "Résistance de 3 éléments aléatoires", en = "Resist of 3 Random Elements", es = "Resistencia de 3 elementos aleatorios")
        Characteristic.RESISTANCE_ELEMENTARY_EARTH -> localized(lang, fr = "Résistance Terre", en = "Earth Resist", es = "Resistencia a la tierra")
        Characteristic.RESISTANCE_ELEMENTARY_FIRE -> localized(lang, fr = "Résistance Feu", en = "Fire Resist", es = "Resistencia al fuego")
        Characteristic.RESISTANCE_ELEMENTARY_WATER -> localized(lang, fr = "Résistance Eau", en = "Water Resist", es = "Resistencia al agua")
        Characteristic.RESISTANCE_ELEMENTARY_WIND -> localized(lang, fr = "Résistance Air", en = "Air Resist", es = "Resistencia al aire")
        Characteristic.HP -> localized(lang, fr = "Points de Vie", en = "Health Points", es = "Puntos de Vida")
        Characteristic.CRITICAL_HIT -> localized(lang, fr = "Coup Critique", en = "Critical Hit", es = "Golpe Crítico")
        Characteristic.WAKFU_POINT -> localized(lang, fr = "PW", en = "WP", es = "PW")
        Characteristic.MAX_WAKFU_POINTS -> localized(lang, fr = "PW max", en = "Max WP", es = "PW máx.")
        Characteristic.ACTION_POINT -> localized(lang, fr = "PA", en = "AP", es = "PA")
        Characteristic.MAX_ACTION_POINT -> localized(lang, fr = "PA max", en = "Max AP", es = "PA máx.")
        Characteristic.RANGE -> localized(lang, fr = "Portée", en = "Range", es = "Alcance")
        Characteristic.MOVEMENT_POINT -> localized(lang, fr = "PM", en = "MP", es = "PM")
        Characteristic.MAX_MOVEMENT_POINT -> localized(lang, fr = "PM max", en = "Max MP", es = "PM máx.")
        Characteristic.CONTROL -> localized(lang, fr = "Contrôle", en = "Control", es = "Control")
        Characteristic.WISDOM -> localized(lang, fr = "Sagesse", en = "Wisdom", es = "Sabiduría")
        Characteristic.DODGE -> localized(lang, fr = "Esquive", en = "Dodge", es = "Esquiva")
        Characteristic.LOCK -> localized(lang, fr = "Tacle", en = "Lock", es = "Placaje")
        Characteristic.PROSPECTION -> localized(lang, fr = "Prospection", en = "Prospecting", es = "Prospección")
        Characteristic.INITIATIVE -> localized(lang, fr = "Initiative", en = "Initiative", es = "Iniciativa")
        Characteristic.WILLPOWER -> localized(lang, fr = "Volonté", en = "Willpower", es = "Voluntad")
        Characteristic.BLOCK_PERCENTAGE -> localized(lang, fr = "Parade %", en = "Block %", es = "Anticipación %")
        Characteristic.GIVEN_ARMOR_PERCENTAGE -> localized(lang, fr = "Armure donnée %", en = "Given Armor %", es = "Armadura dada %")
        Characteristic.RECEIVED_ARMOR_PERCENTAGE -> localized(lang, fr = "Armure reçue %", en = "Received Armor %", es = "Armadura recibida %")
        Characteristic.HERBALIST_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, fr = "Récolte Herboriste %", en = "Herbalist Harvest %", es = "Recolección Herbolario %")
        Characteristic.LUMBERJACK_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, fr = "Récolte Bûcheron %", en = "Lumberjack Harvest %", es = "Recolección Leñador %")
        Characteristic.TRAPPER_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, fr = "Récolte Trappeur %", en = "Trapper Harvest %", es = "Recolección Trampero %")
        Characteristic.MINER_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, fr = "Récolte Mineur %", en = "Miner Harvest %", es = "Recolección Minero %")
        Characteristic.FARMER_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, fr = "Récolte Paysan %", en = "Farmer Harvest %", es = "Recolección Granjero %")
        Characteristic.FISHERMAN_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, fr = "Récolte Pêcheur %", en = "Fisherman Harvest %", es = "Recolección Pescador %")
    }

/** Picks the FR/EN/ES form for [lang]; a tiny helper so inline per-language tables stay one line per entry. */
fun localized(
    lang: Lang,
    fr: String,
    en: String,
    es: String,
): String =
    when (lang) {
        Lang.FR -> fr
        Lang.EN -> en
        Lang.ES -> es
    }

/** Localized display name for an attack's range band (the secondary mastery it credits). */
fun RangeBand.label(lang: Lang): String =
    when (this) {
        RangeBand.MELEE -> localized(lang, fr = "Mêlée", en = "Melee", es = "Melé")
        RangeBand.DISTANCE -> localized(lang, fr = "Distance", en = "Distance", es = "Distancia")
    }

/** Localized display name for an attack orientation (the positional damage multiplier). */
fun Orientation.label(lang: Lang): String =
    when (this) {
        Orientation.FACE -> localized(lang, fr = "Face", en = "Face", es = "Frente")
        Orientation.SIDE -> localized(lang, fr = "Côté", en = "Side", es = "Lado")
        Orientation.BACK -> localized(lang, fr = "Dos", en = "Back", es = "Espalda")
    }

/** Localized display name for an item rarity. */
fun Rarity.label(lang: Lang): String =
    when (this) {
        Rarity.COMMON -> localized(lang, fr = "Commun", en = "Common", es = "Común")
        Rarity.UNCOMMON -> localized(lang, fr = "Inhabituel", en = "Uncommon", es = "Poco común")
        Rarity.RARE -> localized(lang, fr = "Rare", en = "Rare", es = "Raro")
        Rarity.MYTHIC -> localized(lang, fr = "Mythique", en = "Mythic", es = "Mítico")
        Rarity.LEGENDARY -> localized(lang, fr = "Légendaire", en = "Legendary", es = "Legendario")
        Rarity.RELIC -> localized(lang, fr = "Relique", en = "Relic", es = "Reliquia")
        Rarity.SOUVENIR -> localized(lang, fr = "Souvenir", en = "Souvenir", es = "Recuerdo")
        Rarity.EPIC -> localized(lang, fr = "Épique", en = "Epic", es = "Épico")
    }

/** Localized display name for a sublimation's rarity tier (epic/relic dedicated slot, or a normal socketed one). */
fun SublimationRarity.label(lang: Lang): String =
    when (this) {
        SublimationRarity.EPIC -> localized(lang, fr = "Épique", en = "Epic", es = "Épico")
        SublimationRarity.RELIC -> localized(lang, fr = "Relique", en = "Relic", es = "Reliquia")
        SublimationRarity.NORMAL -> localized(lang, fr = "Normal", en = "Normal", es = "Normal")
    }

/**
 * Localized display name for a skill-tree line. The domain's
 * [me.chosante.common.skills.SkillCharacteristic.name] is English-only; this maps it to FR/ES for
 * the skill tree. Unknown names fall back to the English string.
 */
fun skillLabel(
    englishName: String,
    lang: Lang,
): String {
    val table =
        when (lang) {
            Lang.FR -> SKILL_NAME_FR
            Lang.ES -> SKILL_NAME_ES
            Lang.EN -> return englishName
        }
    return table[englishName] ?: englishName
}

private val SKILL_NAME_FR =
    mapOf(
        "% Block" to "% Blocage",
        "% Critical Hit" to "% Coup Critique",
        "% Damage Inflicted" to "% Dommages infligés",
        "% HP as Armor" to "% PV en Armure",
        "% HP" to "% PV",
        "% Heal Received" to "% Soins reçus",
        "% Inflicted Damage" to "% Dommages infligés",
        "% damage" to "% dommages",
        "Action Point" to "Point d'Action",
        "Control and damage" to "Contrôle et dommages",
        "Dodge and lock" to "Esquive et Tacle",
        "Dodge" to "Esquive",
        "Initiative" to "Initiative",
        "Lock" to "Tacle",
        "Mastery Back" to "Maîtrise Dos",
        "Mastery Berserk" to "Maîtrise Berserk",
        "Mastery Critical" to "Maîtrise Critique",
        "Mastery Distance" to "Maîtrise Distance",
        "Mastery Elementary" to "Maîtrise Élémentaire",
        "Mastery Healing" to "Maîtrise Soin",
        "Mastery Melee" to "Maîtrise Mêlée",
        "Movement Point and damage" to "Point de Mouvement et dommages",
        "Range and damage" to "Portée et dommages",
        "Resistance Back" to "Résistance Dos",
        "Resistance Critical" to "Résistance Critique",
        "Resistance Elementary" to "Résistance Élémentaire",
        "Shield" to "Bouclier",
        "Wakfu Points" to "Points Wakfu",
        "Willpower" to "Volonté"
    )

private val SKILL_NAME_ES =
    mapOf(
        "% Block" to "% Anticipación",
        "% Critical Hit" to "% Golpe Crítico",
        "% Damage Inflicted" to "% Daños infligidos",
        "% HP as Armor" to "% PdV como Armadura",
        "% HP" to "% PdV",
        "% Heal Received" to "% Curas recibidas",
        "% Inflicted Damage" to "% Daños infligidos",
        "% damage" to "% daño",
        "Action Point" to "Punto de Acción",
        "Control and damage" to "Control y daño",
        "Dodge and lock" to "Esquiva y Placaje",
        "Dodge" to "Esquiva",
        "Initiative" to "Iniciativa",
        "Lock" to "Placaje",
        "Mastery Back" to "Dominio espalda",
        "Mastery Berserk" to "Dominio berserker",
        "Mastery Critical" to "Dominio crítico",
        "Mastery Distance" to "Dominio distancia",
        "Mastery Elementary" to "Dominio elemental",
        "Mastery Healing" to "Dominio cura",
        "Mastery Melee" to "Dominio de melé",
        "Movement Point and damage" to "Punto de Movimiento y daño",
        "Range and damage" to "Alcance y daño",
        "Resistance Back" to "Resistencia de espalda",
        "Resistance Critical" to "Resistencia crítica",
        "Resistance Elementary" to "Resistencia elemental",
        "Shield" to "Escudo",
        "Wakfu Points" to "Puntos de Wakfu",
        "Willpower" to "Voluntad"
    )

/** Localized display name for an equipment slot type. */
fun ItemType.label(lang: Lang): String =
    when (this) {
        ItemType.AMULET -> localized(lang, fr = "Amulette", en = "Amulet", es = "Amuleto")
        ItemType.EMBLEM -> localized(lang, fr = "Emblème", en = "Emblem", es = "Emblema")
        ItemType.SHOULDER_PADS -> localized(lang, fr = "Épaulettes", en = "Epaulettes", es = "Hombreras")
        ItemType.RING -> localized(lang, fr = "Anneau", en = "Ring", es = "Anillo")
        ItemType.BOOTS -> localized(lang, fr = "Bottes", en = "Boots", es = "Botas")
        ItemType.ONE_HANDED_WEAPONS -> localized(lang, fr = "Arme à une main", en = "One-handed Weapon", es = "Arma de una mano")
        ItemType.CHEST_PLATE -> localized(lang, fr = "Plastron", en = "Breastplate", es = "Coraza")
        ItemType.CAPE -> localized(lang, fr = "Cape", en = "Cape", es = "Capa")
        ItemType.OFF_HAND_WEAPONS -> localized(lang, fr = "Seconde main", en = "Off-hand", es = "Segunda mano")
        ItemType.HELMET -> localized(lang, fr = "Casque", en = "Helmet", es = "Casco")
        ItemType.PETS -> localized(lang, fr = "Familier", en = "Pet", es = "Mascota")
        ItemType.TWO_HANDED_WEAPONS -> localized(lang, fr = "Arme à deux mains", en = "Two-handed Weapon", es = "Arma de dos manos")
        ItemType.MOUNTS -> localized(lang, fr = "Monture", en = "Mount", es = "Montura")
        ItemType.BELT -> localized(lang, fr = "Ceinture", en = "Belt", es = "Cinturón")
    }
