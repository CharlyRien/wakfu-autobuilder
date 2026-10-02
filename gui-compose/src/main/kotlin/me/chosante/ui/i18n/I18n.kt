package me.chosante.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity

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
 * Loads `i18n/strings_<lang>.properties` from the classpath (UTF-8) and caches the result per
 * [Lang]. A key missing from a non-English file falls back to the English value so a partial
 * translation never renders blank text.
 */
private object Translations {
    private val cache = mutableMapOf<Lang, Map<String, String>>()

    private fun load(lang: Lang): Map<String, String> {
        val path = "/i18n/strings_${lang.resourceSuffix}.properties"
        val stream =
            requireNotNull(javaClass.getResourceAsStream(path)) {
                "Missing i18n resource: $path"
            }
        val properties = java.util.Properties()
        stream.use { properties.load(it.bufferedReader(Charsets.UTF_8)) }
        return properties.entries.associate { (key, value) -> key.toString() to value.toString() }
    }

    private fun forLang(lang: Lang): Map<String, String> = cache.getOrPut(lang) { load(lang) }

    fun lookup(
        lang: Lang,
        key: String,
    ): String =
        forLang(lang)[key]
            ?: forLang(Lang.EN)[key]
            ?: key
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
        Characteristic.MASTERY_ELEMENTARY -> localized(lang, "Maîtrise Élémentaire", "Elemental Mastery", "Maestría Elemental")
        Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT ->
            localized(lang, "Maîtrise d'1 élément aléatoire", "Mastery of 1 Random Element", "Maestría de 1 elemento aleatorio")
        Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT ->
            localized(lang, "Maîtrise de 2 éléments aléatoires", "Mastery of 2 Random Elements", "Maestría de 2 elementos aleatorios")
        Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT ->
            localized(lang, "Maîtrise de 3 éléments aléatoires", "Mastery of 3 Random Elements", "Maestría de 3 elementos aleatorios")
        Characteristic.MASTERY_ELEMENTARY_WATER -> localized(lang, "Maîtrise Eau", "Water Mastery", "Maestría Agua")
        Characteristic.MASTERY_ELEMENTARY_WIND -> localized(lang, "Maîtrise Air", "Air Mastery", "Maestría Aire")
        Characteristic.MASTERY_ELEMENTARY_FIRE -> localized(lang, "Maîtrise Feu", "Fire Mastery", "Maestría Fuego")
        Characteristic.MASTERY_ELEMENTARY_EARTH -> localized(lang, "Maîtrise Terre", "Earth Mastery", "Maestría Tierra")
        Characteristic.MASTERY_DISTANCE -> localized(lang, "Maîtrise Distance", "Distance Mastery", "Maestría a Distancia")
        Characteristic.MASTERY_CRITICAL -> localized(lang, "Maîtrise Critique", "Critical Mastery", "Maestría Crítica")
        Characteristic.MASTERY_BACK -> localized(lang, "Maîtrise Dos", "Rear Mastery", "Maestría de Espalda")
        Characteristic.MASTERY_MELEE -> localized(lang, "Maîtrise Mêlée", "Melee Mastery", "Maestría Cuerpo a Cuerpo")
        Characteristic.MASTERY_BERSERK -> localized(lang, "Maîtrise Berserk", "Berserk Mastery", "Maestría Berserker")
        Characteristic.MASTERY_HEALING -> localized(lang, "Maîtrise Soin", "Healing Mastery", "Maestría de Curación")
        Characteristic.DAMAGE_INFLICTED -> localized(lang, "Dommages infligés", "Damage Inflicted", "Daño Infligido")
        Characteristic.RESISTANCE_CRITICAL -> localized(lang, "Résistance Critique", "Critical Resist", "Resistencia Crítica")
        Characteristic.RESISTANCE_BACK -> localized(lang, "Résistance Dos", "Rear Resist", "Resistencia de Espalda")
        Characteristic.RESISTANCE_ELEMENTARY -> localized(lang, "Résistance Élémentaire", "Elemental Resist", "Resistencia Elemental")
        Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT ->
            localized(lang, "Résistance d'1 élément aléatoire", "Resist of 1 Random Element", "Resistencia de 1 elemento aleatorio")
        Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT ->
            localized(lang, "Résistance de 2 éléments aléatoires", "Resist of 2 Random Elements", "Resistencia de 2 elementos aleatorios")
        Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT ->
            localized(lang, "Résistance de 3 éléments aléatoires", "Resist of 3 Random Elements", "Resistencia de 3 elementos aleatorios")
        Characteristic.RESISTANCE_ELEMENTARY_EARTH -> localized(lang, "Résistance Terre", "Earth Resist", "Resistencia Tierra")
        Characteristic.RESISTANCE_ELEMENTARY_FIRE -> localized(lang, "Résistance Feu", "Fire Resist", "Resistencia Fuego")
        Characteristic.RESISTANCE_ELEMENTARY_WATER -> localized(lang, "Résistance Eau", "Water Resist", "Resistencia Agua")
        Characteristic.RESISTANCE_ELEMENTARY_WIND -> localized(lang, "Résistance Air", "Air Resist", "Resistencia Aire")
        Characteristic.HP -> localized(lang, "Points de Vie", "Health Points", "Puntos de Vida")
        Characteristic.CRITICAL_HIT -> localized(lang, "Coup Critique", "Critical Hit", "Golpe Crítico")
        Characteristic.WAKFU_POINT -> localized(lang, "PW", "WP", "PW")
        Characteristic.MAX_WAKFU_POINTS -> localized(lang, "PW max", "Max WP", "PW máx.")
        Characteristic.ACTION_POINT -> localized(lang, "PA", "AP", "PA")
        Characteristic.MAX_ACTION_POINT -> localized(lang, "PA max", "Max AP", "PA máx.")
        Characteristic.RANGE -> localized(lang, "Portée", "Range", "Alcance")
        Characteristic.MOVEMENT_POINT -> localized(lang, "PM", "MP", "PM")
        Characteristic.MAX_MOVEMENT_POINT -> localized(lang, "PM max", "Max MP", "PM máx.")
        Characteristic.CONTROL -> localized(lang, "Contrôle", "Control", "Control")
        Characteristic.WISDOM -> localized(lang, "Sagesse", "Wisdom", "Sabiduría")
        Characteristic.DODGE -> localized(lang, "Esquive", "Dodge", "Esquiva")
        Characteristic.LOCK -> localized(lang, "Tacle", "Lock", "Tacleo")
        Characteristic.PROSPECTION -> localized(lang, "Prospection", "Prospecting", "Prospección")
        Characteristic.INITIATIVE -> localized(lang, "Initiative", "Initiative", "Iniciativa")
        Characteristic.WILLPOWER -> localized(lang, "Volonté", "Willpower", "Voluntad")
        Characteristic.BLOCK_PERCENTAGE -> localized(lang, "Parade %", "Block %", "Bloqueo %")
        Characteristic.GIVEN_ARMOR_PERCENTAGE -> localized(lang, "Armure donnée %", "Given Armor %", "Armadura dada %")
        Characteristic.RECEIVED_ARMOR_PERCENTAGE -> localized(lang, "Armure reçue %", "Received Armor %", "Armadura recibida %")
        Characteristic.HERBALIST_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, "Récolte Herboriste %", "Herbalist Harvest %", "Recolección Herbolario %")
        Characteristic.LUMBERJACK_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, "Récolte Bûcheron %", "Lumberjack Harvest %", "Recolección Leñador %")
        Characteristic.TRAPPER_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, "Récolte Trappeur %", "Trapper Harvest %", "Recolección Trampero %")
        Characteristic.MINER_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, "Récolte Mineur %", "Miner Harvest %", "Recolección Minero %")
        Characteristic.FARMER_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, "Récolte Paysan %", "Farmer Harvest %", "Recolección Granjero %")
        Characteristic.FISHERMAN_HARVEST_QUANTITY_PERCENTAGE ->
            localized(lang, "Récolte Pêcheur %", "Fisherman Harvest %", "Recolección Pescador %")
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
        RangeBand.MELEE -> localized(lang, "Mêlée", "Melee", "Cuerpo a cuerpo")
        RangeBand.DISTANCE -> localized(lang, "Distance", "Distance", "Distancia")
    }

/** Localized display name for an attack orientation (the positional damage multiplier). */
fun Orientation.label(lang: Lang): String =
    when (this) {
        Orientation.FACE -> localized(lang, "Face", "Face", "Frente")
        Orientation.SIDE -> localized(lang, "Côté", "Side", "Lado")
        Orientation.BACK -> localized(lang, "Dos", "Back", "Espalda")
    }

/** Localized display name for an item rarity. */
fun Rarity.label(lang: Lang): String =
    when (this) {
        Rarity.COMMON -> localized(lang, "Commun", "Common", "Común")
        Rarity.UNCOMMON -> localized(lang, "Inhabituel", "Uncommon", "Poco común")
        Rarity.RARE -> localized(lang, "Rare", "Rare", "Raro")
        Rarity.MYTHIC -> localized(lang, "Mythique", "Mythic", "Mítico")
        Rarity.LEGENDARY -> localized(lang, "Légendaire", "Legendary", "Legendario")
        Rarity.RELIC -> localized(lang, "Relique", "Relic", "Reliquia")
        Rarity.SOUVENIR -> localized(lang, "Souvenir", "Souvenir", "Recuerdo")
        Rarity.EPIC -> localized(lang, "Épique", "Epic", "Épico")
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
        "% Block" to "% Bloqueo",
        "% Critical Hit" to "% Golpe Crítico",
        "% Damage Inflicted" to "% Daño infligido",
        "% HP as Armor" to "% PV como Armadura",
        "% HP" to "% PV",
        "% Heal Received" to "% Curación recibida",
        "% Inflicted Damage" to "% Daño infligido",
        "% damage" to "% daño",
        "Action Point" to "Punto de Acción",
        "Control and damage" to "Control y daño",
        "Dodge and lock" to "Esquiva y Tacleo",
        "Dodge" to "Esquiva",
        "Initiative" to "Iniciativa",
        "Lock" to "Tacleo",
        "Mastery Back" to "Maestría de Espalda",
        "Mastery Berserk" to "Maestría Berserker",
        "Mastery Critical" to "Maestría Crítica",
        "Mastery Distance" to "Maestría a Distancia",
        "Mastery Elementary" to "Maestría Elemental",
        "Mastery Healing" to "Maestría de Curación",
        "Mastery Melee" to "Maestría Cuerpo a Cuerpo",
        "Movement Point and damage" to "Punto de Movimiento y daño",
        "Range and damage" to "Alcance y daño",
        "Resistance Back" to "Resistencia de Espalda",
        "Resistance Critical" to "Resistencia Crítica",
        "Resistance Elementary" to "Resistencia Elemental",
        "Shield" to "Escudo",
        "Wakfu Points" to "Puntos de Wakfu",
        "Willpower" to "Voluntad"
    )

/** Localized display name for an equipment slot type. */
fun ItemType.label(lang: Lang): String =
    when (this) {
        ItemType.AMULET -> localized(lang, "Amulette", "Amulet", "Amuleto")
        ItemType.EMBLEM -> localized(lang, "Emblème", "Emblem", "Emblema")
        ItemType.SHOULDER_PADS -> localized(lang, "Épaulettes", "Epaulettes", "Hombreras")
        ItemType.RING -> localized(lang, "Anneau", "Ring", "Anillo")
        ItemType.BOOTS -> localized(lang, "Bottes", "Boots", "Botas")
        ItemType.ONE_HANDED_WEAPONS -> localized(lang, "Arme à une main", "One-handed Weapon", "Arma de una mano")
        ItemType.CHEST_PLATE -> localized(lang, "Plastron", "Breastplate", "Coraza")
        ItemType.CAPE -> localized(lang, "Cape", "Cape", "Capa")
        ItemType.OFF_HAND_WEAPONS -> localized(lang, "Seconde main", "Off-hand", "Segunda mano")
        ItemType.HELMET -> localized(lang, "Casque", "Helmet", "Casco")
        ItemType.PETS -> localized(lang, "Familier", "Pet", "Mascota")
        ItemType.TWO_HANDED_WEAPONS -> localized(lang, "Arme à deux mains", "Two-handed Weapon", "Arma de dos manos")
        ItemType.MOUNTS -> localized(lang, "Monture", "Mount", "Montura")
        ItemType.BELT -> localized(lang, "Ceinture", "Belt", "Cinturón")
    }
