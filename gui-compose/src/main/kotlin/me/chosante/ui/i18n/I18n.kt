package me.chosante.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import me.chosante.common.Characteristic
import me.chosante.common.ItemType
import me.chosante.common.Rarity

enum class Lang(
    val label: String,
) {
    EN("EN"),
    FR("FR"),
}

/** The active UI language, provided once at the app root and read by [tr]. */
val LocalLang = staticCompositionLocalOf { Lang.EN }

/**
 * Every user-facing static string, with its English and French forms. Hand-written (no codegen)
 * so the two translations stay side by side and the keys are type-checked at the call site.
 */
enum class Tr(
    val en: String,
    val fr: String,
) {
    // Brand / top bar
    CLASS("Class", "Classe"),
    LEVEL_SHORT("Lvl", "Niv"),
    MIN_SHORT("Min", "Min"),
    LEVEL_RANGE_INVALID(
        "Min level can't be higher than the character level. Lower the min, or raise the level.",
        "Le niveau min ne peut pas dépasser le niveau du personnage. Baisse le min, ou augmente le niveau."
    ),
    PROGRESS("Progress", "Progression"),
    PRELOAD_WARMUP("Starting the engine…", "Démarrage du moteur…"),
    MATCH("Match", "Correspondance"),
    SEARCH("Search", "Rechercher"),
    STOP("Stop", "Arrêter"),

    // Zone headers
    ZONE_REQUEST("Request", "Requête"),
    ZONE_REQUEST_HINT("Input", "Entrée"),
    ZONE_BUILD("Discovered Build", "Build découvert"),
    ZONE_BUILD_IDLE("Awaiting search", "En attente"),
    ZONE_BUILD_SEARCHING("Live - best so far", "En direct - meilleur trouvé"),
    ZONE_BUILD_DONE("Result", "Résultat"),
    ZONE_STATS("Resulting Stats", "Stats résultantes"),
    ZONE_STATS_HINT("Output", "Sortie"),

    // Request panel
    SEARCH_MODE("Search Mode", "Mode de recherche"),
    MODE_MASTERIES("Most Masteries", "Max maîtrises"),
    MODE_MASTERIES_SUB("minimum constraints, max masteries", "contraintes min, max maîtrises"),
    MODE_PRECISION("Precision", "Précision"),
    MODE_PRECISION_SUB("hit every target exactly", "vise chaque cible exactement"),
    MODE_MAX_DAMAGE("Max Damage", "Dégâts max"),
    MODE_MAX_DAMAGE_SUB("maximize expected damage", "maximise les dégâts attendus"),
    DAMAGE_SCENARIO("Attack scenario", "Scénario d'attaque"),
    SCENARIO_ELEMENT("Element", "Élément"),
    SCENARIO_RANGE("Range", "Portée"),
    SCENARIO_ORIENTATION("Orientation", "Orientation"),
    SCENARIO_BERSERK("Berserk (≤50% HP)", "Berserk (≤50% PV)"),
    SCENARIO_HEALING("Healing", "Soin"),
    SCENARIO_CRIT_CAP("Crit cap %", "Plafond crit %"),
    SCENARIO_ENEMY_RES("Enemy res %", "Rés. ennemi %"),
    SCENARIO_ROLE("Role preset", "Préréglage de rôle"),
    ROLE_DISTANCE_DPS("Distance DPS", "DPS distance"),
    ROLE_MELEE_DPS("Melee DPS", "DPS mêlée"),
    ROLE_TANK("Tank", "Tank"),
    SCENARIO_SURVIVAL_FLOOR("Survivability floor", "Plancher de survie"),
    SCENARIO_MIN_EHP("Min effective HP", "PV efficaces min"),
    BOSS("Boss", "Boss"),
    BOSS_NONE_HINT(
        "Target a boss to auto-fill its elemental resistances — the search picks the best playable element.",
        "Ciblez un boss pour remplir ses résistances élémentaires — la recherche choisit le meilleur élément jouable."
    ),
    BOSS_PICK("＋ Choose a boss", "＋ Choisir un boss"),
    BOSS_CHANGE("Change", "Changer"),
    BOSS_REMOVE("Remove", "Retirer"),
    BOSS_LEVEL_SHORT("Lv", "Niv."),
    BOSS_ELEMENT("Damage element", "Élément d'attaque"),
    BOSS_ELEMENT_AUTO("Auto", "Auto"),
    BOSS_DIFFICULTY("Difficulty (HP×)", "Difficulté (PV×)"),
    CHOOSE_BOSS_TITLE("Choose a boss", "Choisir un boss"),
    SEARCH_BOSSES("Search bosses (name)", "Rechercher un boss (nom)"),
    NO_MATCHING_BOSS("No matching boss", "Aucun boss correspondant"),
    TURNS_TO_KILL("Turns to kill", "Tours pour tuer"),
    EXPECTED_DAMAGE("Expected damage", "Dégâts attendus"),
    SPELL_ROTATION("Spell Rotation", "Rotation de sorts"),
    SPELL_ROTATION_SUB("best spells for this build's AP", "meilleurs sorts pour les PA du build"),
    SPELL_ROTATION_PER_TURN("expected damage / turn", "dégâts attendus / tour"),
    SPELL_ROTATION_EMPTY(
        "No playable spells in this element for this class — try another attack element.",
        "Aucun sort jouable dans cet élément pour cette classe — essaie un autre élément d'attaque."
    ),
    SPELL_ROTATION_NOTE(
        "WP costs aren't modeled — WP-gated spells may not sustain their casts every turn (upper bound).",
        "Le coût en PW n'est pas modélisé — les sorts à PW peuvent ne pas tenir leurs lancers chaque tour (borne haute)."
    ),
    TARGET_STATS("Target Stats", "Stats cibles"),
    MAXIMIZED_MASTERIES("Maximized Masteries", "Maîtrises à maximiser"),
    NO_MASTERY_SELECTED("None selected", "Aucune sélection"),
    PRIORITY("Priority", "Priorité"),
    PRIORITY_HINT(
        "Priority (1–5): higher targets win when they can't all be met",
        "Priorité (1–5) : les cibles prioritaires l'emportent si tout n'est pas atteignable"
    ),
    ADD_TARGET_STAT("＋ Add target stat", "＋ Ajouter une stat"),
    KIND_EXACT("minimum", "minimum"),
    KIND_MAXIMIZE("maximize", "maximiser"),
    MAX_RARITY("Max rarity", "Rareté max"),
    RARITIES("Rarities", "Raretés"),
    RARITIES_SUB("tap to allow / exclude", "clic pour autoriser / exclure"),
    SEARCH_DURATION("Search duration", "Durée de recherche"),
    SEARCH_DURATION_SUB("time budget for the solver", "temps alloué au solveur"),
    SECONDS_SHORT("sec", "sec"),
    STOP_AT_MATCH("Stop at 100% match", "Arrêter à 100%"),
    SEARCH_NO_RESULT(
        "No build produced in this time window. Narrow the level range or increase the duration.",
        "Aucun build produit dans cette fenêtre. Réduis la plage de niveaux ou augmente la durée."
    ),
    FORCED_ITEMS("Forced Items", "Objets imposés"),
    REQUIRE_ITEM_CHIP("＋ require item", "＋ imposer un objet"),
    EXCLUDED_ITEMS("Excluded Items", "Objets exclus"),
    BAN_ITEM_CHIP("＋ ban item", "＋ exclure un objet"),
    SUBLIMATIONS_RUNES("Sublimations & Runes", "Sublimations & Runes"),
    SOLVER_PICKS_SUBLIMATIONS("Solver picks sublimations", "Le solveur choisit les sublimations"),
    FORCED_SUBLIMATIONS("Forced Sublimations", "Sublimations imposées"),
    ADD_SUBLIMATION_CHIP("＋ Force a sublimation", "＋ Forcer une sublimation"),
    CHOSEN_SUBLIMATIONS("Sublimations", "Sublimations"),
    REQUIRE_SUBLIMATION_TITLE("Force a sublimation", "Forcer une sublimation"),
    SEARCH_SUBLIMATIONS("Search sublimations (title / effect)…", "Rechercher des sublimations (titre / effet)…"),
    NO_MATCHING_SUBLIMATION("No matching sublimation", "Aucune sublimation correspondante"),
    FORCED_PASSIVES("Passive loadout", "Passifs équipés"),
    ADD_PASSIVE_CHIP("＋ Add a passive", "＋ Ajouter un passif"),
    CHOSEN_PASSIVES("Passives", "Passifs"),
    REQUIRE_PASSIVE_TITLE("Add a passive", "Ajouter un passif"),
    SEARCH_PASSIVES("Search passives (name / effect)…", "Rechercher des passifs (nom / effet)…"),
    NO_MATCHING_PASSIVE("No matching passive", "Aucun passif correspondant"),
    PASSIVE_SLOTS_FULL("All passive slots used", "Tous les emplacements de passifs utilisés"),
    EDIT_RUNES("Edit runes", "Modifier les runes"),
    EDIT_RUNES_TITLE("Runes", "Runes"),
    RUNE_SOCKETS_LABEL("Sockets", "Emplacements"),
    SEARCH_RUNES("Search runes…", "Rechercher des runes…"),
    NO_MATCHING_RUNE("No matching rune", "Aucune rune correspondante"),
    RUNES_PER_ITEM_HINT(
        "Runes are pinned per item — hover a slot in the build and click ◈.",
        "Les runes se définissent par objet — survole un emplacement du build et clique sur ◈."
    ),

    // Paperdoll
    PREPARING_OR_TOOLS_MODEL("Preparing OR-Tools model", "Préparation du modèle OR-Tools"),
    FIRST_RESULT_HINT(
        "The first build will appear here as soon as the solver has one.",
        "Le premier build apparaîtra ici dès que le solveur en a un."
    ),
    EMPTY("empty", "vide"),
    LEVEL_PREFIX_LONG("Level", "Niveau"),
    LEVEL_PREFIX_SHORT("Lv", "Niv"),
    DISCLAIMER(
        "Unofficial fan tool - not affiliated with Ankama - item art © Ankama (community-sourced)",
        "Outil de fan non officiel - non affilié à Ankama - visuels © Ankama (communautaires)"
    ),
    APP_VERSION_LABEL("Version", "Version"),
    GAME_DATA_LABEL("Game data", "Données de jeu"),
    SLOT_HELMET("Helmet", "Casque"),
    SLOT_AMULET("Amulet", "Amulette"),
    SLOT_EPAULETTES("Epaulettes", "Épaulettes"),
    SLOT_BREASTPLATE("Breastplate", "Plastron"),
    SLOT_CAPE("Cape", "Cape"),
    SLOT_EMBLEM("Emblem", "Emblème"),
    SLOT_BELT("Belt", "Ceinture"),
    SLOT_RING_I("Ring I", "Anneau I"),
    SLOT_RING_II("Ring II", "Anneau II"),
    SLOT_BOOTS("Boots", "Bottes"),
    SLOT_WEAPON("Weapon", "Arme"),
    SLOT_SECOND_WEAPON("Second Wpn", "2nde arme"),
    SLOT_PET("Pet", "Familier"),
    SLOT_MOUNT("Mount", "Monture"),

    // Stats panel
    BUILD_MATCH("Build Match", "Correspondance"),
    BUILD_MASTERY("Requested mastery", "Maîtrise demandée"),
    BUILD_MASTERY_HINT("specialized summed + weakest requested element", "spécialisées sommées + élément demandé le plus faible"),
    MASTERY_SHORT("Mastery", "Maîtrise"),
    OPTIMAL_PROVEN("Optimal proven", "Optimal prouvé"),
    BEST_FOUND("Best found · optimum not proven", "Meilleur trouvé · optimum non prouvé"),
    NOT_OPTIMAL_HINT(
        "Time budget reached before proving the optimum — raise the search duration to aim higher.",
        "Budget de temps atteint avant de prouver l'optimum — augmente la durée de recherche pour viser plus haut."
    ),
    NOT_OPTIMAL_STRUCTURAL_HINT(
        "Best found across resistance-debuff sequencing — that turn structure is searched heuristically, so more time won't materially change the result.",
        "Meilleur trouvé parmi les séquences de réduction de résistance — cette structure de tour est explorée heuristiquement, donc plus de temps n'y changera pas grand-chose."
    ),
    MASTERY_SUMMARY("Mastery Summary", "Cumul maîtrises"),
    MASTERY_TOTAL("Tracked total", "Total suivi"),
    BUILD_SHEET_TITLE("Other build stats", "Autres stats du build"),
    BUILD_SHEET_EMPTY("No other notable stats on this build.", "Aucune autre stat notable sur ce build."),
    MASTERY_ELEMENTALS("Elementals", "Élémentaires"),
    MASTERY_SPECIALIZED("Specialized", "Spécialisées"),
    MASTERY_INCIDENTAL("Also on the build (not requested)", "Aussi sur le build (non demandé)"),
    DESIRED_VS_ACHIEVED("Desired vs Achieved", "Désiré vs Obtenu"),
    TAG_EXACT("minimum", "minimum"),
    TAG_MAXIMIZE("maximize", "maximiser"),
    SKILL_ALLOCATION("Skill Allocation", "Répartition des aptitudes"),
    BRANCHES_COUNT("5 branches", "5 branches"),
    OPEN_IN_ZENITH("Open in Zenith ↗", "Ouvrir dans Zenith ↗"),
    OPENING("Opening...", "Ouverture..."),
    COPY_BUILD_LINK("Copy build link", "Copier le lien"),
    EXPORT_BUILD("Export build", "Exporter le build"),
    NO_BUILD_YET("No build yet", "Aucun build"),
    NO_BUILD_HINT(
        "Set your target stats & constraints on the left, then hit Search. " +
            "The paperdoll fills in live as better builds are found.",
        "Définissez vos stats cibles et contraintes à gauche, puis lancez la recherche. " +
            "L'équipement se remplit en direct à mesure que de meilleurs builds sont trouvés."
    ),
    BRANCH_INTELLIGENCE("Intelligence", "Intelligence"),
    BRANCH_STRENGTH("Strength", "Force"),
    BRANCH_AGILITY("Agility", "Agilité"),
    BRANCH_LUCK("Luck", "Chance"),
    BRANCH_MAJOR("Major", "Majeur"),

    // Modals
    ADD_TARGET_STAT_TITLE("Add target stat", "Ajouter une stat cible"),
    FILTER_STATS("Filter stats…", "Filtrer les stats…"),
    STAT_GROUP_CORE("Core", "Base"),
    STAT_GROUP_MASTERIES("Masteries", "Maîtrises"),
    STAT_GROUP_RESISTANCES("Resistances", "Résistances"),
    STAT_GROUP_SECONDARY("Secondary", "Secondaires"),
    NO_MATCHING_STAT("No matching stat", "Aucune stat correspondante"),
    REQUIRE_ITEM_TITLE("Require item", "Imposer un objet"),
    BAN_ITEM_TITLE("Ban item", "Exclure un objet"),
    SEARCH_ITEMS("Search items (FR / EN)…", "Rechercher des objets (FR / EN)…"),
    NO_MATCHING_ITEM("No matching item", "Aucun objet correspondant"),
    REQUIRE("Require", "Imposer"),
    BAN("Ban", "Exclure"),
    RUNES("Runes", "Châsses"),
    LOADING_ITEMS("Loading item database…", "Chargement de la base d'objets…"),

    // Import-build dialog
    IMPORT_DIALOG_TITLE("Import a build", "Importer un build"),
    IMPORT_DIALOG_HINT(
        "Paste a build exported from this app (input + result). It's added to your library and opened.",
        "Colle un build exporté depuis l'app (entrée + résultat). Il est ajouté à ta bibliothèque et ouvert."
    ),
    IMPORT_PLACEHOLDER("Paste the exported build here…", "Colle ici le build exporté…"),
    IMPORT_PASTE("Paste", "Coller"),
    IMPORT_INVALID("That doesn't look like an exported build.", "Cela ne ressemble pas à un build exporté."),
    IMPORT_CONFIRM("Import", "Importer"),
    IMPORTED_BUILD_NAME("Imported build", "Build importé"),

    // Toasts (read off the composition by the state holder)
    TOAST_ZENITH_COPIED("Zenith link copied", "Lien Zenith copié"),
    TOAST_ZENITH_READY("Zenith build ready", "Build Zenith prêt"),
    TOAST_BUILD_SAVED("Build saved", "Build enregistré"),
    TOAST_BUILD_DUPLICATED("Build duplicated", "Build dupliqué"),
    TOAST_BUILD_EXPORTED("Build copied to clipboard", "Build copié dans le presse-papiers"),
    TOAST_BUILD_IMPORTED("Build imported", "Build importé"),

    // Navigation / active build
    NAV_BUILDER("Builder", "Builder"),
    NAV_LIBRARY("My Builds", "Mes builds"),
    NEW_BUILD("＋ New", "＋ Nouveau"),
    ACTIVE_BUILD_EDITING("Editing", "Édition"),
    BACK("Back", "Retour"),

    // Save dialog
    SAVE_BUILD("Save build", "Enregistrer le build"),
    SAVE_DIALOG_TITLE("Save this build", "Enregistrer ce build"),
    SAVE_NAME_LABEL("Name", "Nom"),
    SAVE_NOTE_LABEL("Note (optional)", "Note (facultatif)"),
    SAVE("Save", "Enregistrer"),
    SAVE_AS_NEW("Save as new", "Nouveau build"),
    UPDATE_BUILD("Update", "Mettre à jour"),
    SAVE_NAME_TAKEN("Another build already uses this name", "Un autre build porte déjà ce nom"),
    SAVE_UPDATE_HINT("Updating the loaded build — or save it as a new one.", "Met à jour le build chargé — ou enregistre-le comme nouveau."),
    CANCEL("Cancel", "Annuler"),

    // Library
    LIBRARY_TITLE("My Builds", "Mes builds"),
    LIBRARY_SUBTITLE("Saved locally on this computer", "Enregistrés localement sur cet ordinateur"),
    IMPORT_BUILD("⤓ Import", "⤓ Importer"),
    LIBRARY_EMPTY("No saved builds yet", "Aucun build enregistré"),
    LIBRARY_EMPTY_HINT(
        "Run a search in the Builder, then hit Save — your builds show up here.",
        "Lance une recherche dans le Builder, puis Enregistre — tes builds apparaîtront ici."
    ),
    LIBRARY_SEARCH("Search builds…", "Rechercher un build…"),
    LIBRARY_NO_MATCH("No build matches your search", "Aucun build ne correspond"),
    LIBRARY_COUNT("saved", "enregistrés"),
    LIBRARY_ALL_BUILDS("All builds", "Tous les builds"),
    LIBRARY_CLASSES("Classes", "Classes"),
    LIBRARY_TAGS("Tags", "Tags"),
    LIBRARY_SORT("Sort", "Tri"),
    SORT_NEWEST("Newest", "Plus récents"),
    SORT_OLDEST("Oldest", "Plus anciens"),
    SORT_NAME("Name (A–Z)", "Nom (A–Z)"),
    SORT_LEVEL("Level", "Niveau"),
    LIBRARY_GROUP_BY_CLASS("Group by class", "Grouper par classe"),
    LIBRARY_CLEAR_FILTERS("Clear filters", "Effacer les filtres"),
    LIBRARY_FOLDERS("Folders", "Dossiers"),
    LIBRARY_UNFILED("Unfiled", "Sans dossier"),
    FOLDER_LABEL("Folder", "Dossier"),
    FOLDER_NONE("None", "Aucun"),
    FOLDER_NEW("New folder…", "Nouveau dossier…"),
    RENAME_FOLDER_TITLE("Rename folder", "Renommer le dossier"),
    DELETE_FOLDER_TITLE("Delete folder", "Supprimer le dossier"),
    DELETE_FOLDER_HINT(
        "The builds inside are kept and become unfiled.",
        "Les builds qu'il contient sont conservés et redeviennent sans dossier."
    ),
    TOAST_FOLDER_RENAMED("Folder renamed", "Dossier renommé"),
    TOAST_FOLDERS_MERGED("Folders merged", "Dossiers fusionnés"),
    TOAST_FOLDER_DELETED("Folder deleted — builds kept", "Dossier supprimé — builds conservés"),
    ACTION_LOAD("Load", "Charger"),
    ACTION_COMPARE("Compare", "Comparer"),
    ACTION_DUPLICATE("Duplicate", "Dupliquer"),
    ACTION_RENAME("Rename", "Renommer"),
    ACTION_DELETE("Delete", "Supprimer"),

    /** Suffix appended to a duplicated build's name, e.g. "Cra 110 (copy)". */
    DUPLICATE_SUFFIX("copy", "copie"),

    // Edit / delete dialogs
    EDIT_BUILD_TITLE("Edit build", "Modifier le build"),
    TAGS_LABEL("Tags", "Tags"),
    TAG_ADD_PLACEHOLDER("Type or pick a tag…", "Tape ou choisis un tag…"),
    TAG_ADD("Add", "Ajouter"),
    TAG_CREATE("Create", "Créer"),
    TAG_NONE_LEFT("No tag matches — type to create one", "Aucun tag — tape pour en créer un"),
    TAG_NEW("New tag", "Nouveau tag"),
    CREATE_TAG_TITLE("New tag", "Nouveau tag"),
    RENAME_TAG_TITLE("Rename tag", "Renommer le tag"),
    DELETE_TAG_TITLE("Delete tag", "Supprimer le tag"),
    DELETE_TAG_HINT(
        "The tag is removed from every build. The builds themselves are kept.",
        "Le tag est retiré de tous les builds. Les builds eux-mêmes sont conservés."
    ),
    TOAST_TAG_RENAMED("Tag renamed", "Tag renommé"),
    TOAST_TAGS_MERGED("Tags merged", "Tags fusionnés"),
    TOAST_TAG_DELETED("Tag deleted from all builds", "Tag supprimé de tous les builds"),
    DELETE_TITLE("Delete this build?", "Supprimer ce build ?"),
    DELETE_HINT("This permanently removes it from your local library.", "Le retire définitivement de ta bibliothèque locale."),

    // Re-search guard
    RESEARCH_TITLE("Re-optimize this build?", "Ré-optimiser ce build ?"),
    RESEARCH_HINT(
        "You're editing a saved build. Searching recomputes it from your current targets; " +
            "you can then save the result back over it.",
        "Tu édites un build enregistré. La recherche le recalcule selon tes cibles actuelles ; " +
            "tu pourras ensuite réenregistrer le résultat par-dessus."
    ),
    RESEARCH_CONFIRM("Re-optimize", "Ré-optimiser"),

    // Compare view
    COMPARE_TITLE("Compare builds", "Comparer les builds"),
    COMPARE_PICK("Pick a build", "Choisir un build"),
    COMPARE_ADD("Add build", "Ajouter un build"),
    COMPARE_BETTER("Best", "Meilleur"),
    COMPARE_EQUAL("=", "="),
    COMPARE_EMPTY("Pick at least two builds to compare them side by side.", "Choisis au moins deux builds à comparer côte à côte."),
    COMPARE_STAT("Stat", "Stat"),
    COMPARE_ENGINE_SCORE("Mastery score (engine)", "Score maîtrises (moteur)"),
    COMPARE_SPELL_DAMAGE("Spell damage", "Dégâts des sorts"),
    COMPARE_SPELLS_MIXED_CLASS(
        "Spell damage compares only builds of the same class — these are different classes.",
        "Les dégâts des sorts ne comparent que des builds de la même classe — ici les classes diffèrent."
    ),

    // What's-new dialog (once-per-version release notes)
    WHATS_NEW_TITLE("What's new in", "Nouveautés de la version"),
    WHATS_NEW_FEATURES("Features", "Fonctionnalités"),
    WHATS_NEW_FIXES("Bug Fixes", "Corrections"),
    WHATS_NEW_PERF("Performance Improvements", "Améliorations de performance"),
    WHATS_NEW_GOT_IT("Got it", "Compris"),

    // Class spells & passives tab (build-result region)
    TAB_DISCOVERED_BUILD("Discovered build", "Build découvert"),
    TAB_CLASS_SPELLS("Class spells & passives", "Sorts & passifs"),
    CLASS_SPELLS_TITLE("Class spells", "Sorts de la classe"),
    CLASS_SPELLS_PASSIVES("Passives", "Passifs"),
    PASSIVE_IN_BUILD("in build", "dans le build"),
    CLASS_SPELLS_EMPTY("This class has no damage spells in the data.", "Cette classe n'a aucun sort de dégâts dans les données."),
    CLASS_SPELLS_NO_BUILD(
        "No build yet — showing each spell's base hit at this level. Run a search to see this build's damage.",
        "Pas encore de build — dégâts de base affichés à ce niveau. Lance une recherche pour voir les dégâts de ce build."
    ),
    SPELL_EXPECTED_HIT("expected hit", "dégâts moyens"),
    SPELL_BASE_HIT("base hit", "dégâts de base"),
    SPELL_NONCRIT("non-crit", "non-crit"),
    SPELL_CRIT("crit", "crit"),
    SPELL_ALWAYS_CRITS("always crits · 100% crit", "toujours crit · 100% crit"),
    SPELL_PER_TURN_SUFFIX("turn", "tour"),
    SPELL_EXPECTED_HIT_INFO(
        "Expected hit: the average damage of one cast. It blends a non-crit and a crit hit, weighted by " +
            "this build's crit chance, from its elemental mastery, % damage inflicted and critical mastery — " +
            "against a 0%-resistance target.",
        "Dégâts moyens : la moyenne d'un lancer. Mélange un coup normal et un coup critique, pondérés par les " +
            "chances de coup critique du build, d'après sa maîtrise élémentaire, ses % de dégâts infligés et sa " +
            "maîtrise critique — contre une cible à 0% de résistance."
    ),
    SPELL_BASE_HIT_INFO(
        "Base hit at this level, before the build's masteries. Run a search to see this build's expected damage.",
        "Dégâts de base à ce niveau, avant les maîtrises du build. Lance une recherche pour voir les dégâts attendus de ce build."
    ),
    ELEMENT_FIRE("Fire", "Feu"),
    ELEMENT_WATER("Water", "Eau"),
    ELEMENT_EARTH("Earth", "Terre"),
    ELEMENT_AIR("Air", "Air"),
    ;

    fun value(lang: Lang): String =
        when (lang) {
            Lang.EN -> en
            Lang.FR -> fr
        }
}

@Composable
@ReadOnlyComposable
fun tr(key: Tr): String = key.value(LocalLang.current)

/**
 * Localized display name for **every** characteristic — exhaustive so the compiler guarantees no
 * stat shown on an item tooltip is left without a translation.
 */
fun Characteristic.label(lang: Lang): String {
    val fr = lang == Lang.FR
    return when (this) {
        Characteristic.MASTERY_ELEMENTARY -> if (fr) "Maîtrise Élémentaire" else "Elemental Mastery"
        Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT -> if (fr) "Maîtrise d'1 élément aléatoire" else "Mastery of 1 Random Element"
        Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT -> if (fr) "Maîtrise de 2 éléments aléatoires" else "Mastery of 2 Random Elements"
        Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT -> if (fr) "Maîtrise de 3 éléments aléatoires" else "Mastery of 3 Random Elements"
        Characteristic.MASTERY_ELEMENTARY_WATER -> if (fr) "Maîtrise Eau" else "Water Mastery"
        Characteristic.MASTERY_ELEMENTARY_WIND -> if (fr) "Maîtrise Air" else "Air Mastery"
        Characteristic.MASTERY_ELEMENTARY_FIRE -> if (fr) "Maîtrise Feu" else "Fire Mastery"
        Characteristic.MASTERY_ELEMENTARY_EARTH -> if (fr) "Maîtrise Terre" else "Earth Mastery"
        Characteristic.MASTERY_DISTANCE -> if (fr) "Maîtrise Distance" else "Distance Mastery"
        Characteristic.MASTERY_CRITICAL -> if (fr) "Maîtrise Critique" else "Critical Mastery"
        Characteristic.MASTERY_BACK -> if (fr) "Maîtrise Dos" else "Rear Mastery"
        Characteristic.MASTERY_MELEE -> if (fr) "Maîtrise Mêlée" else "Melee Mastery"
        Characteristic.MASTERY_BERSERK -> if (fr) "Maîtrise Berserk" else "Berserk Mastery"
        Characteristic.MASTERY_HEALING -> if (fr) "Maîtrise Soin" else "Healing Mastery"
        Characteristic.DAMAGE_INFLICTED -> if (fr) "Dommages infligés" else "Damage Inflicted"
        Characteristic.RESISTANCE_CRITICAL -> if (fr) "Résistance Critique" else "Critical Resist"
        Characteristic.RESISTANCE_BACK -> if (fr) "Résistance Dos" else "Rear Resist"
        Characteristic.RESISTANCE_ELEMENTARY -> if (fr) "Résistance Élémentaire" else "Elemental Resist"
        Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT -> if (fr) "Résistance d'1 élément aléatoire" else "Resist of 1 Random Element"
        Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT -> if (fr) "Résistance de 2 éléments aléatoires" else "Resist of 2 Random Elements"
        Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT -> if (fr) "Résistance de 3 éléments aléatoires" else "Resist of 3 Random Elements"
        Characteristic.RESISTANCE_ELEMENTARY_EARTH -> if (fr) "Résistance Terre" else "Earth Resist"
        Characteristic.RESISTANCE_ELEMENTARY_FIRE -> if (fr) "Résistance Feu" else "Fire Resist"
        Characteristic.RESISTANCE_ELEMENTARY_WATER -> if (fr) "Résistance Eau" else "Water Resist"
        Characteristic.RESISTANCE_ELEMENTARY_WIND -> if (fr) "Résistance Air" else "Air Resist"
        Characteristic.HP -> if (fr) "Points de Vie" else "Health Points"
        Characteristic.CRITICAL_HIT -> if (fr) "Coup Critique" else "Critical Hit"
        Characteristic.WAKFU_POINT -> if (fr) "PW" else "WP"
        Characteristic.MAX_WAKFU_POINTS -> if (fr) "PW max" else "Max WP"
        Characteristic.ACTION_POINT -> if (fr) "PA" else "AP"
        Characteristic.MAX_ACTION_POINT -> if (fr) "PA max" else "Max AP"
        Characteristic.RANGE -> if (fr) "Portée" else "Range"
        Characteristic.MOVEMENT_POINT -> if (fr) "PM" else "MP"
        Characteristic.MAX_MOVEMENT_POINT -> if (fr) "PM max" else "Max MP"
        Characteristic.CONTROL -> if (fr) "Contrôle" else "Control"
        Characteristic.WISDOM -> if (fr) "Sagesse" else "Wisdom"
        Characteristic.DODGE -> if (fr) "Esquive" else "Dodge"
        Characteristic.LOCK -> if (fr) "Tacle" else "Lock"
        Characteristic.PROSPECTION -> if (fr) "Prospection" else "Prospecting"
        Characteristic.INITIATIVE -> if (fr) "Initiative" else "Initiative"
        Characteristic.WILLPOWER -> if (fr) "Volonté" else "Willpower"
        Characteristic.BLOCK_PERCENTAGE -> if (fr) "Parade %" else "Block %"
        Characteristic.GIVEN_ARMOR_PERCENTAGE -> if (fr) "Armure donnée %" else "Given Armor %"
        Characteristic.RECEIVED_ARMOR_PERCENTAGE -> if (fr) "Armure reçue %" else "Received Armor %"
        Characteristic.HERBALIST_HARVEST_QUANTITY_PERCENTAGE -> if (fr) "Récolte Herboriste %" else "Herbalist Harvest %"
        Characteristic.LUMBERJACK_HARVEST_QUANTITY_PERCENTAGE -> if (fr) "Récolte Bûcheron %" else "Lumberjack Harvest %"
        Characteristic.TRAPPER_HARVEST_QUANTITY_PERCENTAGE -> if (fr) "Récolte Trappeur %" else "Trapper Harvest %"
        Characteristic.MINER_HARVEST_QUANTITY_PERCENTAGE -> if (fr) "Récolte Mineur %" else "Miner Harvest %"
        Characteristic.FARMER_HARVEST_QUANTITY_PERCENTAGE -> if (fr) "Récolte Paysan %" else "Farmer Harvest %"
        Characteristic.FISHERMAN_HARVEST_QUANTITY_PERCENTAGE -> if (fr) "Récolte Pêcheur %" else "Fisherman Harvest %"
    }
}

/** Localized display name for an item rarity. */
fun Rarity.label(lang: Lang): String =
    when (this) {
        Rarity.COMMON -> if (lang == Lang.FR) "Commun" else "Common"
        Rarity.UNCOMMON -> if (lang == Lang.FR) "Inhabituel" else "Uncommon"
        Rarity.RARE -> if (lang == Lang.FR) "Rare" else "Rare"
        Rarity.MYTHIC -> if (lang == Lang.FR) "Mythique" else "Mythic"
        Rarity.LEGENDARY -> if (lang == Lang.FR) "Légendaire" else "Legendary"
        Rarity.RELIC -> if (lang == Lang.FR) "Relique" else "Relic"
        Rarity.SOUVENIR -> if (lang == Lang.FR) "Souvenir" else "Souvenir"
        Rarity.EPIC -> if (lang == Lang.FR) "Épique" else "Epic"
    }

/**
 * Localized display name for a skill-tree line. The domain's
 * [me.chosante.common.skills.SkillCharacteristic.name] is English-only; this maps it to FR for the skill
 * tree. Unknown names fall back to the English string.
 */
fun skillLabel(
    englishName: String,
    lang: Lang,
): String {
    if (lang != Lang.FR) return englishName
    return SKILL_NAME_FR[englishName] ?: englishName
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

/** Localized display name for an equipment slot type. */
fun ItemType.label(lang: Lang): String =
    when (this) {
        ItemType.AMULET -> if (lang == Lang.FR) "Amulette" else "Amulet"
        ItemType.EMBLEM -> if (lang == Lang.FR) "Emblème" else "Emblem"
        ItemType.SHOULDER_PADS -> if (lang == Lang.FR) "Épaulettes" else "Epaulettes"
        ItemType.RING -> if (lang == Lang.FR) "Anneau" else "Ring"
        ItemType.BOOTS -> if (lang == Lang.FR) "Bottes" else "Boots"
        ItemType.ONE_HANDED_WEAPONS -> if (lang == Lang.FR) "Arme à une main" else "One-handed Weapon"
        ItemType.CHEST_PLATE -> if (lang == Lang.FR) "Plastron" else "Breastplate"
        ItemType.CAPE -> if (lang == Lang.FR) "Cape" else "Cape"
        ItemType.OFF_HAND_WEAPONS -> if (lang == Lang.FR) "Seconde main" else "Off-hand"
        ItemType.HELMET -> if (lang == Lang.FR) "Casque" else "Helmet"
        ItemType.PETS -> if (lang == Lang.FR) "Familier" else "Pet"
        ItemType.TWO_HANDED_WEAPONS -> if (lang == Lang.FR) "Arme à deux mains" else "Two-handed Weapon"
        ItemType.MOUNTS -> if (lang == Lang.FR) "Monture" else "Mount"
        ItemType.BELT -> if (lang == Lang.FR) "Ceinture" else "Belt"
    }
