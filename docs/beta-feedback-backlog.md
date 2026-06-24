# Backlog — retours bêta-test (Ticlem, 23–24 juin 2026)

Document de suivi des bugs / features / QoL issus du retour bêta-test Discord, **grounded dans le code**
(chaque item a été confirmé/localisé). Pensé pour être travaillé en parallèle par plusieurs agents :
chaque fiche est autonome (symptôme, cause racine, fichiers, plan, critères d'acceptation, risques).

> Source : conversation Discord du 23–24/06/2026 + investigation code (HEAD `bc3ed8f0`, données jeu `1.92.1.58`).

---

## Comment utiliser ce document (agents)

1. **Choisir un item** par son ID stable (ex. `ENG-1`). Vérifier la **carte des conflits** plus bas avant
   de démarrer — plusieurs items touchent `Modals.kt` / `BuildSearchModel.kt` / `RequestPanel.kt`.
2. **Réserver** l'item : passer son `Statut` de `🔲 TODO` à `🏗️ WIP (nom)` dans la fiche **et** dans le
   tableau récap. Le repasser à `✅ Done (PR #…)` une fois mergé.
3. **Brancher depuis `main`** (`git switch -c fix/eng-1-emblem-force`). Ne pas push/commit sans accord
   explicite du mainteneur. Un commit = un item user-facing (cf. convention de commits du repo).
4. **Avant de finir** : `./gradlew ktlintFormat` (style strict), puis les tests du/des module(s) touché(s).

### Rappels techniques transverses (lire avant tout)

- **Moteur natif (OR-Tools).** Tout module qui lance le solveur (`autobuilder`, `gui-compose`) a déjà
  les JVM args natifs câblés dans son `build.gradle.kts` pour `run`/`test`. Premier solve = cold start.
- **Matching des items/sublis imposés/exclus = nom FRANÇAIS** (`equipment.name.fr`, lowercased), quelle que
  soit la langue d'UI. Le picker de sublis matche aussi `name.en`. Toute nouvelle validation/abstraction
  doit rester cohérente avec ça.
- **Commandes** :
  - `./gradlew :autobuilder:test` — tests moteur
  - `./gradlew :gui-compose:run` — lancer la GUI
  - `WAKFU_COMPOSE_SCREENSHOT=/tmp/out.png ./gradlew :gui-compose:run` — smoke screenshot (skip warm-up)
  - `./gradlew ktlintFormat` — formatage obligatoire
- **i18n** = enum `Tr` écrit à la main (EN/FR) dans `gui-compose/.../i18n/I18n.kt`. Pas de code généré.

### Légende

`Type` : 🐞 Bug · ✨ Feature · 🧹 QoL · 🏗️ Refactor — `Prio` : P0 (urgent) → P3 (confort) —
`Effort` : S (≤½ j) · M (1–2 j) · L (>2 j) — `Statut` : 🔲 TODO · 🏗️ WIP · ✅ Done · 🔵 Déjà fait · 💬 Décision/clarif.

---

## Tableau récapitulatif

| ID | Titre | Type | Prio | Effort | Statut | Dépend / Conflits |
|----|-------|------|------|--------|--------|-------------------|
| **ENG-1** | Validation requête + **pop-up listant toutes les erreurs** (item non équipable, force-equip) | 🐞 | P0 | M | ✅ `fix/eng-1-emblem-force-equip` | inclut ENG-2 ; → PICK-1/PICK-2 |
| **ENG-2** | Forcer 2 sublis épique/relique = recherche « sans résultat » | 🐞 | P1 | M | ✅ **fusionné dans ENG-1** | voir ENG-1 |
| **ENG-3** | Sublimations conditionnelles (croisées + forcée) | 🐞 | P2 | M | 🔲 TODO | re-baseline tests |
| **RUNE-1** | Runes builder ≠ Zenith : expliciter « all gold » | 🐞 | P2 | S | ✅ mergé (#181) | — |
| **RUNE-2** | Badge « ×2 » rune doublée dans le picker | 🧹 | P3 | S | 🔲 TODO | REF-1/REF-2 (Modals) |
| **RUNE-3** | Bouton « verrouiller les runes actuelles » | ✨ | P3 | S | 🔲 TODO | forçage par-item déjà fait |
| **SUB-1** | Sublis : filtre/tri par rareté + couleur sur chips | ✨ | P2 | M | 🔲 TODO | REF-1/REF-2, ENG-2 |
| **SUB-2** | Cap niveau de subli (filtre « prix » lvl 1/2/3) | ✨ | P2 | M | 🔲 TODO | ⚠️ investigation moteur |
| **FLOW-1** | Charger un build dans un autre mode (comparer dégâts) | ✨ | P1 | M | 🔲 TODO | — |
| **FLOW-2** | DI sélectionnable en mode max-maîtrises | ✨ | P2 | S | 🔲 TODO | 💬 décision prise |
| **FLOW-3** | Avertir des points d'aptitude non distribués | 🧹 | P2 | S | 🔲 TODO | — |
| **PICK-1** | Picker : checkbox « équipables uniquement » (cochée par défaut) | ✨ | P2 | M | 🔲 TODO | s'appuie sur REF-1 |
| **PICK-2** | Nettoyer les forced hors-niveau au changement de niveau (garder excluded) | 🧹 | P2 | S | 🔲 TODO | sœur d'ENG-1 |
| **REF-1** | Abstraction partagée des pickers add/suppr (corrige #3 partout) | 🏗️ | P1 | M | 🔲 TODO | base de RUNE-2/SUB-1 |
| **REF-2** | Tri alphabétique localisé de TOUTES les modales catalogue | 🏗️ | P2 | M | 🔲 TODO | base de SUB-1, hotspot Modals |
| **QOL-1** | Tooltip sur libellés de stats cibles tronqués | 🧹 | P3 | S | 🔲 TODO | — |
| **QOL-2** | Durée vide → 10 minutes | 🧹 | P2 | S | ✅ mergé (#182) | — |
| **I18N-1** | Sublis/passifs en EN sous UI FR | 🔵 | — | — | 🔵 Déjà fait (1.8.0) | résidu effect-text |
| **CLAR-*** | Clarifications / non-bugs | 💬 | — | — | 💬 | voir section |

### Carte des conflits de fichiers (à lire avant de prendre 2 items en parallèle)

- **`gui-compose/.../components/Modals.kt`** = HOTSPOT : REF-1, REF-2, SUB-1, ENG-2, RUNE-2, RUNE-3.
  → **Faire REF-1 + REF-2 en premier**, puis SUB-1 / RUNE-2 par-dessus la structure unifiée. Sinon
  coordonner étroitement (un seul agent sur Modals à la fois).
- **`gui-compose/.../state/BuildSearchModel.kt`** : ENG-2, REF-1, RUNE-3, FLOW-1, FLOW-2, QOL-2.
  Méthodes distinctes → collision faible, mais rebaser souvent.
- **`gui-compose/.../request/RequestPanel.kt`** : SUB-1 (chips), QOL-1 (TargetStatRow), FLOW-2 (sections cibles).
- **`autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt`** : ENG-1, ENG-2, ENG-3, FLOW-3 (régions distinctes).
- **`gui-compose/.../stats/StatsPanel.kt`** : FLOW-1, FLOW-3, RUNE-1 (disclaimer).

**Ordre conseillé** : `ENG-1` (rapide & impactant) → `REF-1` + `REF-2` (débloquent la GUI) →
`SUB-1`, `RUNE-2`, `FLOW-*` → le reste.

---

# A. Moteur — correctness (bugs)

## ENG-1 — Validation de requête + pop-up d'erreurs (inclut ENG-2)  🐞 P0 · Effort M
**Statut : ✅ Implémenté — branche `fix/eng-1-emblem-force-equip`** · **💬 Décisions (24/06) : rejeter (pas équiper) + pop-up listant TOUTES les erreurs.**

**Symptôme (testeur).** « Imposer un emblème n'a pas l'air de fonctionner. J'ai *Emblème de Frigost I* en
imposé mais il me met *Archiemblème de Craqnoix*, et quand enlevé *Archiemblème des Racines*. Pour les
autres (monture, familier) l'imposé marche. » Reproduit sur tous les modes.

**Confirmé. Cause racine.** Le « forçage » est implémenté comme une **restriction de pool** (réduire la
liste de candidats du slot à l'item imposé), **pas** comme une contrainte « cet item doit être équipé ».
Cette restriction ne s'applique qu'aux items ayant déjà passé les filtres d'éligibilité (rareté, **niveau
`minLevel ≤ itemLevel ≤ level`**, exclusions). Les items imposés **ne sont pas exemptés** de ces filtres —
alors que **PETS/MOUNTS le sont** pour le filtre de niveau. Donc si l'emblème imposé est hors de la fenêtre
de niveau (ou hors `maxRarity`), il est silencieusement retiré du pool ; la garde de narrowing
`value.any { it.name.fr.lowercase() in itemsToForce }` devient fausse ; le groupe EMBLEM passe **non
filtré** ; le solveur choisit alors n'importe quel Archiemblème dans la fourchette de niveau.
→ C'est exactement pourquoi monture/familier marchent et pas l'emblème (les Archiemblèmes partagent
`ItemType.EMBLEM`). Le matching de nom n'est **pas** en cause (vérifié byte-for-byte, accents inclus).

**Fichiers.**
- `autobuilder/.../genetic/wakfu/WakfuBestBuildFinderAlgorithm.kt:122-146` — `groupAndFilterEquipments` :
  filtre rareté (`:126`), filtre niveau (`:127-129`, exempte PETS/MOUNTS), exclusions (`:130`), puis
  narrowing imposés (`:140-146`).
- `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt:1004-1023` — `addBuildValidityConstraints` : EMBLEM
  n'a qu'une contrainte `sum ≤ 1`, aucune contrainte force-equip.
- `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt:1062-1072` — cap anneaux même nom (≤1).
- `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt:258-280` — `prefilterRelevantEquipments` (préserve déjà
  les imposés ; à re-vérifier après fix).
- `common-lib/.../Equipment.kt:22` — `EMBLEM(646)` partagé emblèmes + Archiemblèmes.

**Solution implémentée (consolide ENG-1 + ENG-2).** Un item imposé hors niveau est **impossible à équiper en
jeu** → on le **refuse** (pas équiper). Et l'ancien affichage (un `ErrorBanner` enfoui dans le panneau de droite,
une seule erreur) n'était **pas clair** → remplacé par une **pop-up listant TOUTES les erreurs** au moment de la
recherche.
1. **Validation agrégée (moteur)** — `WakfuBestBuildFinderAlgorithm.validateRequest(params): List<RequestValidationProblem>`
   retourne **tous** les problèmes d'un coup : bornes de niveau incohérentes (`minLevel > level`), item imposé non
   équipable (niveau hors `[minLevel, level]` — **PETS/MOUNTS exemptés** — ou rareté > `maxRarity` / exclue), et
   **> 1 sublimation épique ou relique imposée** (ex-ENG-2). `run()` lève `InvalidRequestException(problems)` si
   non vide (plancher CLI). Un nom imposé qui ne matche rien = ignoré.
2. **Force-equip conservé** — `WakfuBuildSolver.addForcedItemsEquippedConstraints` (`Σ même-nom ≥ 1`) : un item
   imposé **éligible** est bien **équipé**. RING géré par nom (2 slots → 2 anneaux ok).
3. **Pop-up GUI** — `BuildSearchModel.search()` appelle `validateRequest` avant de lancer ; si problèmes →
   `UiState.requestErrors` → **`RequestErrorsDialog`** (modèle `WhatsNewDialog` : `Scrim`+`ModalCard`+`DialogButton`)
   liste chaque problème **localisé** (item/sublis nommés) ; bloque la recherche. `dismissRequestErrors()` ferme.

**Fichiers touchés.** `WakfuBestBuildFinderAlgorithm.kt` (`validateRequest` + `RequestValidationProblem` +
`InvalidRequestException`), `WakfuBuildSolver.kt` (force-equip), `UiState.kt` (`requestErrors`),
`BuildSearchModel.kt` (pré-validation + `dismissRequestErrors`), `components/RequestErrorsDialog.kt` (nouveau),
`Main.kt` (montage), `I18n.kt` (Tr), `WakfuBuildSolverTest.kt` (tests `validateRequest`).

**Tests (verts).** Rejet hors-niveau / hors-rareté ; in-range + monture lvl 1 OK ; 2 épiques / 2 reliques
rejetées ; **agrégation de plusieurs problèmes à la fois** ; item imposé équipé devant un rival meilleur.
`:autobuilder:test` = 67 tests, 0 échec ; `:gui-compose:compileKotlin` OK.

**Suite = prévention côté GUI (nouveaux tickets).** **PICK-1** (filtre « équipables » coché par défaut) empêche
de choisir un item hors niveau ; **PICK-2** nettoie les forced devenus invalides au changement de niveau. Le cas
« 2 imposés dans un slot à 1 place » : à ajouter à `validateRequest` (même pop-up) — TODO.

---

## PICK-1 — Picker « équipables uniquement » (coché par défaut)  ✨ P2 · Effort M
**Statut : 🔲 TODO** · *(prévention GUI issue d'ENG-1 ; à implémenter dans REF-1)*

**Origine.** Décision dev (24/06) : « une checkbox cochée par défaut pour n'afficher que les items possibles à
équiper pour le build, sinon c'est la porte ouverte à n'importe quoi. »

**État actuel.** Le picker d'items liste **tout le catalogue** (`equipmentCatalog` =
`WakfuBestBuildFinderAlgorithm.equipments`, non filtré) — tous niveaux, toutes raretés. C'est ce qui laisse
forcer un item injouable (cf. ENG-1).

**Plan.** Checkbox « équipables uniquement » (**cochée par défaut**) filtrant sur le **pool éligible du moteur**
(décision retenue : niveau dans `[minLevel, level]` — PETS/MOUNTS exemptés — ET rareté ≤ `maxRarity` / non
exclue). Réutiliser la même éligibilité que `validateForcedItemsEquippable` (ENG-1) pour rester cohérent.
À faire **dans REF-1** (abstraction partagée des pickers) pour couvrir tous les pickers d'items. `Tr` EN/FR.

**Critères.** Par défaut le picker ne montre que les items équipables pour le build courant ; décocher montre
tout ; changer niveau/rareté recompute la liste.

---

## PICK-2 — Nettoyer les forced hors-niveau au changement de niveau  🧹 P2 · Effort S
**Statut : 🔲 TODO** · *(sœur d'ENG-1)*

**Origine.** Décision dev (24/06) : « si jamais il change de level sur un build, il faut virer dans les forced
les items qui n'ont plus de sens (on peut garder les excluded). »

**Plan.** Quand `level`/`minLevel`/`maxRarity`/raretés exclues changent dans `BuildSearchModel`, retirer de
`forcedItems` les items devenus **non éligibles** (même test que `validateForcedItemsEquippable`) + toast « N
objet(s) imposé(s) retiré(s) — hors niveau ». **Garder `excludedItems`** (exclure un item hors niveau est
inoffensif). Évite d'attendre le rejet d'ENG-1 au moment de la recherche.

**Critères.** Baisser le niveau sous celui d'un item imposé le retire automatiquement des forced (+ toast) ; les
excluded restent intacts.

---

## ENG-2 — Forcer 2 sublis épique/relique = recherche « sans résultat »  🐞 P1 · Effort M
**Statut : 🔲 TODO** · *(partage fichiers GUI avec SUB-1)*

**Symptôme (testeur).** « Je peux forcer 2 sublimations épique/relique, et rechercher me donnera toujours
une erreur. → limiter à 1 les épique/relique pour éviter les problèmes. »

**Confirmé.** Ce n'est pas une vraie erreur : le modèle CP-SAT devient **infaisable** (chaque subli épique
forcée exige ≥ 1 item épique via le *carrier gate*, mais le build est capé à 1 item épique) → 0 build émis →
la GUI tombe dans la branche générique `Tr.SEARCH_NO_RESULT`. **Aucune validation** des sublis imposées
n'existe (ni à l'ajout, ni avant recherche). `BuildCombination.hasLegalSublimations()` ne valide qu'un build
de sortie, jamais l'input.

**Fichiers.**
- `gui-compose/.../state/BuildSearchModel.kt:541` (`addForcedSublimation`, dédoublonne par nom seulement),
  `:553` (`pickSublimation`), `:685`+`:692` (point d'insertion de la validation pré-recherche, à côté du garde
  `minLevel > level`), `:864` (branche no-result qui masque l'infaisabilité).
- `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt:936-938` (subli forcée pin `addEquality(v,1)`), `:951`
  (carrier gate épique/relique), `:955` (`MAX_SUBLIMATIONS = 10`), `:1059` (cap item épique ≤ 1), `:926`
  (match nom fr **et** en), `:1430-1454` (statut INFEASIBLE → rien émis).
- `autobuilder/.../domain/BuildCombination.kt:70` (`hasLegalSublimations`, règle à refléter).
- `gui-compose/.../i18n/I18n.kt:34` (voisinage `LEVEL_RANGE_INVALID`).

**Plan.**
1. **Validation pré-recherche** dans `search()` (après le garde `minLevel > level`) : résoudre chaque nom de
   subli imposée vers son `Sublimation` (match insensible à la casse sur `name.fr` **et** `name.en`, comme le
   moteur), compter EPIC et RELIC **indépendamment** ; si l'un > 1 → `error = Tr.FORCED_SUBLIMATION_RARITY_INVALID`
   et `return`.
2. **Mieux** : empêcher l'état invalide dès `addForcedSublimation`/`pickSublimation` — refuser un 2ᵉ
   épique/relique (drop + toast/erreur).
3. Ajouter le `Tr` EN/FR.

**Critères d'acceptation.** Forcer 2 épiques (ou 2 reliques) → message clair *avant* le solve, pas « aucun
résultat ». Forcer 1 épique + 1 relique → autorisé. Forcer 1 épique seul (qui force un item épique via carrier
gate) → **autorisé** (ne pas le flaguer). Test : input 2 épiques → erreur typée.

**Risques.** Cap **par rareté** (≤1 EPIC ET ≤1 RELIC), pas un ≤2 global. `distinctBy stateId` comme le picker.
Le moteur n'a pas à changer (l'infaisabilité est correcte) — on rejette l'input proprement.

---

## ENG-3 — Sublimations conditionnelles : interactions croisées + subli forcée  🐞 P2 · Effort M
**Statut : 🔲 TODO** · *(re-baseline `SublimationReproductionTest` / `SublimationConditionTest`)*

**Symptôme (dev).** « Ça prend en compte que les sublis s'activent en même temps, ce qui est peut-être faux. »
« Si je force une subli conditionnelle… j'ai peut-être pas géré ce cas. »

**Confirmé (likely), deux problèmes distincts.**
- **(a) Interactions croisées.** Chaque condition build-static est évaluée sur le stat **hors-sublimations**
  (`preSubStat`), donc une subli co-choisie qui ajoute une stat ne « voit » pas qu'elle casse/active la
  condition d'une autre. Le cas précis « maîtrises secondaires ≤ 0 » est aujourd'hui *inatteignable* (aucune
  subli choisissable ne donne de maîtrise secondaire positive). **Mais** d'autres types sont atteignables dans
  le set choisissable : Ambition +25 CC → Mesure III/Steadfast (`CC ≤ N`) ; Vivacité +1 PA → Inflexibilité
  (`PA ≤ 10`)/Herculéen (PA impair) ; Outrage/Visibilité ±range → Outrage (`range ≥ 4`)/Volonté de fer
  (`range ≤ 3`) ; Prétention +25 block → Mesure (`block ≥ 40`). Une subli peut donc « activer à tort » la
  condition d'une autre sur la valeur pré-sub.
- **(b) Subli conditionnelle FORCÉE.** Le solveur l'applique **inconditionnellement** (`appliesVar` saute la
  réification pour les forcées), **mais** le re-scorer ré-évalue la condition pour *toutes* les sublis
  (`sublimationFixedContributions` n'a pas de flag « forcée ») → une subli forcée dont la condition échoue sur
  preSub est **créditée dans l'objectif mais retirée du score recalculé**. Divergence silencieuse, aucun warning.

**Fichiers.**
- `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt:2705` (`preSubStat`), `:2767` (`appliesVar`, forcées
  sautent la réif.), `:2810` (`reifyCondition`), `:2840` (`SECONDARY_MASTERIES_AT_MOST`).
- `autobuilder/.../genetic/wakfu/FindClosestBuildFromInputScoring.kt:690` (`subConditionHolds`), `:737`
  (`sublimationFixedContributions`, pas de flag forcée), `:209` (`scoreFor`).
- `bdata-extractor/.../SublimationBuilder.kt:218` (drop multi-maîtrise — garde le set choisissable propre).

**Plan.**
- **(a)** Évaluer chaque condition sur `preSubStat + termes FLAT (inconditionnels) des sublis + termes des
  sublis forcées` (pas sur preSub seul). Limiter la base aux contributions **inconditionnelles** évite tout
  point-fixe entre sublis mutuellement conditionnelles. **Refléter exactement la même base** dans
  `subConditionHolds` (solveur et re-scorer en lockstep).
- **(b)** Propager le set des sublis forcées jusqu'à `sublimationFixedContributions` et les appliquer
  inconditionnellement côté score aussi (aligné sur `appliesVar`). Alternative/complément : **warning** GUI/CLI
  quand la condition d'une subli forcée n'est pas remplie par le build final.

**Critères d'acceptation.** Tests : (1) deux sublis conditionnelles choisissables où l'une active/casse la
condition de l'autre → solveur et re-scorer concordent ; (2) une subli conditionnelle forcée à condition non
remplie → objectif et stats recalculées concordent (ou warning explicite).

**Risques.** Change quelles sublis sont co-sélectionnées → l'optimum prouvé et les tests verrouillés bougent
(re-baseline soigneux). Même folding (`foldedForSublimation`/`effectiveStat`, set des maîtrises secondaires)
des deux côtés sinon `matchPercentage` diverge.

---

# B. Runes

## RUNE-1 — Runes builder ≠ Zenith : expliciter l'hypothèse « all gold »  🐞 P2 · Effort S
**Statut : 🔲 TODO**

**Symptôme (testeur).** « Différence de rune entre le builder et l'ouverture sur Zenith. » + « +1000 maîtrise
critique mais 1 % CC ? » (vu sur Zenith).

**Confirmé (likely).** L'export envoie **déjà la bonne rune** (1 seul id coloré par stat — il **n'existe pas**
de rune « dorée » neutre dans les données Ankama). Le souci : le moteur suppose « toutes les sockets sont
re-roll → runes **doublées / all gold** » (hypothèse BiS optimiste), ce que Zenith ne peut pas reproduire
(`/shard/add` n'a pas de champ couleur et ne re-roll pas les sockets natives). D'où couleurs/doublage affichés
différents. Ce n'est **pas** un mauvais id de rune.

**Fichiers.**
- `zenith-builder/.../ZenithBuilder.kt:51-59`, `zenith-builder/.../ZenithShard.kt:20-41` (envoi `id_shard` +
  position + side + level, sans couleur).
- `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt:1497-1511` (`runes = List(socketCount){bestRune}`),
  `common-lib/.../RuneType.kt:63-70` (`valueOn` crédite le doublage WakForge).
- `gui-compose/.../paperdoll/PaperdollPanel.kt:733-745` (`socketLayout` peint la couleur de socket assortie).
- `gui-compose/.../state/BuildSearchModel.kt:954-958`, `autobuilder/.../Main.kt:828-833` (passe `build.runes`).
- `docs/ENCHANTMENTS_PLAN.md:38-49` (hypothèse documentée).

**Plan.**
1. **Disclaimer UI explicite** (nouveau `Tr` EN/FR près de `RUNES_PER_ITEM_HINT`, `I18n.kt:145-152`) : « Runes
   affichées au niveau max et doublées (BiS : couleurs de sockets supposées re-roll / “all gold”) » — l'afficher
   près de la section runes du paperdoll **et** des actions Zenith (`StatsPanel.kt`).
2. **Décision** : arrêter (ou pas) de peindre la couleur de socket assortie dans `socketLayout` pour ne pas
   promettre un pattern que Zenith ne reproduit pas. (Impacte `SocketLayoutTest`, `PaperdollRenderTest`.)
3. **Ne pas** changer quelle rune est exportée (déjà déterministe et correct).

**Critères d'acceptation.** L'utilisateur comprend pourquoi le build et le lien Zenith peuvent différer.
Disclaimer visible aux deux endroits. (La parité couleur réelle = gros chantier hors scope : modéliser les
couleurs de sockets natives, absentes des données.)

**Risques.** Non reproduit contre l'API live. Le vrai livrable est le disclaimer + la décision socketLayout.
`forcedRunes`/`forcedRunesByItem` partagent la même hypothèse.

---

## RUNE-2 — Badge « ×2 » (rune doublée) dans le picker de runes  🧹 P3 · Effort S
**Statut : 🔲 TODO** · *(construire sur REF-1/REF-2 si déjà mergés)*

**Symptôme (testeur).** « Peut-être ajouter un indicateur pour les runes ×2 selon l'équipement (dans le petit
bouton bleu). »

**Confirmé.** Le modèle porte déjà `doubleBonusPosition` et l'applique (`valueOn`), mais l'UI ne le montre
jamais. **⚠️ Reformuler avec le testeur** : le ×2 est une propriété du **slot d'équipement** (assorti par
couleur), **pas** d'une socket précise. Donc l'indicateur correct = badge « ×2 sur cet item » par rune, pas un
marqueur par case de socket.

**Fichiers.**
- `gui-compose/.../components/Modals.kt:893` (`RuneOptionRow` — y mettre le badge), `:817`
  (`ItemRunePickerModal` — passer `carrier.itemType`).
- `common-lib/.../RuneType.kt:63` (`valueOn`), `:116` (`slotRawIds`, helper public).
- `gui-compose/.../paperdoll/PaperdollPanel.kt:471` (le bouton ◈ « bleu »), `:799` (`TooltipRuneRow`, surface
  secondaire).

**Plan.**
1. Ajouter `RuneType.isDoubledOn(itemType): Boolean = slotRawIds(itemType).any { it in doubleBonusPosition }`
   (source unique ; `valueOn` peut l'appeler).
2. Dans `RuneOptionRow`, calculer `rune.isDoubledOn(carrierItemType)` et afficher une pastille « ×2 »
   (style teal du ◈ ou `WColor.success`) + tooltip/`Tr`.
3. Optionnel : idem dans `TooltipRuneRow` du paperdoll.

**Critères d'acceptation.** Dans le picker d'un item, les runes doublées sur ce slot affichent « ×2 ». Pas de
marqueur par socket. Slots sans socket (off-hand/emblème/pet/monture, `maxShardSlots==0`) n'atteignent pas le
picker → rien à garder.

**Risques.** RING : `slotRawIds` renvoie les 2 ids d'anneau → ×2 affiché selon l'hypothèse optimiste BiS
(cohérent moteur, peut surprendre). `slotRawIds`/`doubleBonusPosition` déjà publics → faisable 100 % côté GUI
si on préfère éviter de toucher `common-lib`.

---

## RUNE-3 — Bouton « verrouiller les runes actuelles »  ✨ P3 · Effort S
**Statut : 🔲 TODO**

**Symptôme (testeur).** « J'imaginais pouvoir forcer des runes. Même forcer des items avec un set de runes
particulier si t'as zéro thunes pour changer l'enchantement. »

**🔵 La moitié de la demande est DÉJÀ FAITE** (commit `c047809f`) : forçage de runes **par item** end-to-end
(picker ◈ par item + `--forced-runes` CLI + persistance historique + solveur qui force le set exact et désactive
le fold booléen). → **Problème de découvrabilité** : le testeur ne l'a pas trouvé.
**Reste à faire** : un bouton « verrouiller les runes actuelles » (one-click) qui copie le set de runes affiché
dans `forcedRunesByItem`.

**Fichiers.**
- `gui-compose/.../state/BuildSearchModel.kt:579-599` (`openItemRunePicker`/`setForcedRunesForItem`), `:533-535`
  (`runeOptions`).
- `gui-compose/.../components/Modals.kt:817-890` (`ItemRunePickerModal`).
- `gui-compose/.../paperdoll/PaperdollPanel.kt:345-355,372,451-471` (item « Éditer les runes » + ◈),
  `gui-compose/.../shell/AppShell.kt:168-174,279` (câblage).
- `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt:669-788` (`createRuneModel`, fold désactivé si forcé `:733`).

**Plan.** `lockCurrentRunes(itemName)` : lit `ui.build?.runes` pour l'`Equipment` dont `name.fr == itemName`,
mappe vers ids (`runes.map { it.id }`), appelle `setForcedRunesForItem`. Surfacer en item de menu « Verrouiller
les runes actuelles » à côté de « Éditer les runes » (gate : `canEditRunes` && runes présentes). `Tr` EN/FR.
**Améliorer la découvrabilité** du ◈ existant au passage (label/affordance plus clairs).

**Critères d'acceptation.** Un clic copie le set affiché en runes forcées de cet item ; relancer la recherche
respecte ces runes. Clé = `name.fr` (pas le nom localisé).

**Risques.** Forcer un set mixte garde le modèle de comptage (pas le fold) → peut ralentir le badge « optimum
prouvé » en max-dégâts (attendu, cf. mémoire fold-runes). Seulement items socketés + résultat présent.

---

# C. Sublimations (GUI)

## SUB-1 — Filtre/tri des sublis par rareté + couleur de rareté sur les chips  ✨ P2 · Effort M
**Statut : 🔲 TODO** · *(s'appuie sur REF-1/REF-2 ; partage Modals/RequestPanel avec ENG-2)*

**Symptôme (testeur).** « Filtrer/trier les sublimations par raretés. » « Avoir une indication visuelle de la
rareté dans les sublimations imposées (rose/violet). »

**Confirmé.** Le picker n'a qu'une recherche texte ; les chips imposés sont des noms nus à accent vert fixe.
Tout est dispo côté modèle (`Sublimation.rarity` = EPIC/RELIC/NORMAL). **⚠️ Palette réelle** : dans cette app
épique = **violet** (`WRarityColor.epic`), relique = **orange** (`WRarityColor.relic`) — pas « rose/violet ».

**Fichiers.**
- `gui-compose/.../components/Modals.kt:556` (`SublimationPickerModal` — ajouter filtre/tri), `:599`
  (`SublimationResultRow`), `:627` (label rareté).
- `gui-compose/.../request/RequestPanel.kt:1297-1347` (`ForcedNameChips`), `:1228` (`SublimationsRunesCard`).
- `common-lib/.../Sublimation.kt:107` (`rarity`), `gui-compose/.../state/UiState.kt:186`
  (`forcedSublimations: List<String>` — noms seulement), `gui-compose/.../theme/WRarityColor.kt:11/13`.

**Plan.**
1. Filtre rareté dans `SublimationPickerModal` (chips segmentés Tous/Épique/Relique/Normal — réutiliser le
   pattern `RarityToggleChip`), appliqué dans le bloc `filtered` ; tri par `rarity` puis nom.
2. Couleur par chip : résoudre `nom → Sublimation.rarity` (via `WakfuBestBuildFinderAlgorithm.sublimations`,
   même source que le picker) et teinter bordure/fond ; mapper EPIC→`WRarityColor.epic`,
   RELIC→`WRarityColor.relic`, NORMAL→vert actuel. Colorer aussi le label rareté du picker (`:627`).

**Critères d'acceptation.** Le picker filtre/trie par rareté ; les chips imposés reflètent la couleur de rareté.

**Risques.** Si `forcedSublimations` reste `List<String>`, résoudre la couleur au render contre la source déjà
chargée (évite le flash pendant le parse paresseux du JSON).

---

## SUB-2 — Cap niveau de subli (filtre « prix » officieux lvl 1/2/3)  ✨ P2 · Effort M
**Statut : 🔲 TODO** · *(⚠️ investigation moteur requise avant chiffrage ferme)*

**Symptôme (testeur).** « Une option pour bloquer les sublis et prendre que celles lvl 1 pourrait être
intéressant. Les lvl 1 sont pas chères, lvl 2 ça va (30K–300K), lvl 3 hors de prix (millions). Ça ferait
office de filtre prix officieux. »

**À investiguer (non couvert par l'analyse initiale).** Comprendre comment le **niveau** d'une subli est
modélisé/choisi par le solveur (le solveur choisit-il un niveau, ou prend-il `max_level` ?). Données :
`sublimation-stacking.json` (`max_level` + `is_cumulable`), `sublimations.json`. Le param d'entrée
`WakfuBestBuildParams` + `WakfuBuildSolver.createSublimationModel` sont les points d'entrée.

**Plan (esquisse, à confirmer après investigation).** Ajouter un paramètre `maxSublimationLevel` (1/2/3) à
`WakfuBestBuildParams`, contraindre le niveau des sublis choisies à `≤ N`, exposer un sélecteur dans la carte
Sublimations & Runes de `RequestPanel`. `Tr` EN/FR.

**Critères d'acceptation.** Régler « niveau max de subli = 1 » → aucune subli de niveau > 1 dans le build.

**Risques.** Si le niveau de subli n'est aujourd'hui pas une variable de décision (ex. valeur figée à
`max_level`), c'est un changement moteur plus profond → re-chiffrer. Démarrer par une **note d'investigation**
de 1–2 h avant de coder.

---

# D. Modes & flux

## FLOW-1 — Charger un build dans un autre mode (comparer les dégâts)  ✨ P1 · Effort M
**Statut : 🔲 TODO**

**Symptôme (testeur, tout premier retour).** « Est-ce que c'est prévu de charger un build fait en mode
“maîtrises” dans le mode “dégâts” ? Pour comparer les dégâts et pas que les maîtrises. »

**Confirmé, faisable SANS relancer le solveur.** `HistoryEntry.toBuildCombination()` reconstruit le build, et
`BuildSpellDamage.expectedDamage` + `SpellRotationOptimizer.bestSequencedRotation/scenarioBreakdown` scorent un
build figé **sans OR-Tools** (c'est déjà ce que fait la vue *Comparer*, `CompareScreen.computeSpellDamage`).
Aujourd'hui `loadBuild()` restaure le mode sauvegardé tel quel et ne recalcule la rotation que si le mode
sauvé était déjà max-dégâts.

**Fichiers.**
- `gui-compose/.../state/BuildSearchModel.kt:1152` (`loadBuild`, le bloc recompute max-dégâts `~:1158-1216` est
  exactement le code à réutiliser), `:301` (`setMode` **vide** `ui.build` → **ne pas réutiliser tel quel**).
- `gui-compose/.../history/HistoryMapping.kt:130/158/182`, `gui-compose/.../history/CompareScreen.kt:492`.
- `autobuilder/.../domain/SpellRotation.kt:273`, `autobuilder/.../domain/BuildSpellDamage.kt:24`,
  `autobuilder/.../domain/DamageScenario.kt:60`.
- `gui-compose/.../stats/StatsPanel.kt:106` (gate `SpellRotationCard` sur `ui.mode` → la carte apparaît dès le
  flip), `gui-compose/.../state/UiState.kt:161/163/168/208/211`.

**Plan.**
1. `viewLoadedBuildAs(mode, scenario)` : garder `ui.build`/`achieved`/`equipments` intacts, mettre
   `ui.mode`/`ui.scenario`, **garder `searchLocked=true`** et `activeBuildId`, puis recalculer off-thread la
   rotation (`bestSequencedRotation` → `ui.spellRotation`) et `scenarioBreakdown` → `ui.scenarioDamages`
   (guardé par `if (ui.activeBuildId == id)` comme `loadBuild`). Boss : réutiliser
   `scenario.against(...)/againstAllElements(...)`.
2. UI : action « Voir en dégâts » dans `StatsPanel` (visible si `ui.build != null && ui.mode != MAX_DAMAGE`).
3. Sélecteur de `DamageScenario` par défaut (élément/portée/orientation/boss) — pré-rempli depuis le scénario
   sauvé (`restoredScenario()`, défaut FIRE/DISTANCE/BACK). `Tr` EN/FR.
4. Optionnel : « Sauver comme nouveau build » (`saveBuild(asNew=true)`).

**Critères d'acceptation.** Un build sauvé en max-maîtrises peut s'afficher en mode dégâts avec sa rotation,
sans relancer de recherche. Le cadrage UI précise « dégâts de CE build », pas « meilleur build dégâts ».

**Risques.** `setMode()`/`pickBoss()` **vident `ui.build`** → ajouter un chemin préservant le build. Mauvais
élément choisi → 0 dégât silencieux pour une classe sans sort dans cet élément. Le build est optimisé maîtrises,
pas dégâts → dégâts légitimement plus bas (cadrer). Aucun changement backend (APIs sans OR-Tools déjà
existantes ; tests `HistoryMappingTest`, `CompareScreenUiTest`, `SpellRotationTest`).

---

## FLOW-2 — DI sélectionnable en mode max-maîtrises  ✨ P2 · Effort S
**Statut : 🔲 TODO** · **💬 Décision dev prise**

**Symptôme (testeur).** « Dans le mode “Max maîtrises”, pas possible de choisir la stat “Dommages Infligés”,
alors qu'elle est possible dans le mode Boss. »

**💬 Décision (dev) : NE PAS exposer DI comme maîtrise À MAXIMISER (« mauvaise idée »).** DI est déjà maximisé
en interne dans ce mode (fold `mastery×(1+DI/100)`). Si on l'expose, ce sera comme **cible minimale / requise**
(plancher), jamais comme item de la grille « à maximiser ». **Ne pas** l'ajouter à `isMaximizableMastery`.

**Détail confirmé.** DI est absent du `statCatalog` (donc de *tous* les modes en réalité). Les cibles non
maximisables sont traitées par le moteur comme **cibles requises exactes/plancher** (pénalité puissance-6).

**Fichiers.**
- `gui-compose/.../state/UiState.kt:429` (`statCatalog` — DI manquant), `:451` (`addTarget` no-op si absent).
- `gui-compose/.../request/RequestPanel.kt:1488-1548` (sections cibles), `gui-compose/.../shell/AppShell.kt:67-76`
  (le picker exclut les maximisables en max-maîtrises).
- `autobuilder/.../genetic/wakfu/ScoreComputationMode.kt:35-73` (`isMaximizableMastery` /
  `isRequiredMostMasteriesTarget` — DI **pas** maximisable), `WakfuBuildSolver.kt:1086-1122`/`:2154-2165`
  (fold DI déjà présent), `FindMostMasteriesFromInputScoring.kt:49/108-113`.
- `I18n.kt:459` (label DI existe déjà), `StatIcons.kt:78` (glyphe `di` existe déjà).

**Plan (livrable retenu).**
1. **Badge/note** dans la section max-maîtrises : « Les Dommages Infligés sont déjà maximisés dans ce mode »
   (clarifie le besoin du testeur sans piège sémantique). — *minimum requis.*
2. **Optionnel** : exposer DI comme **cible minimale** en ajoutant
   `StatDef(Characteristic.DAMAGE_INFLICTED, "di", …)` au `statCatalog`, **clairement libellé « DI minimum /
   requis »** (kind requis, défaut 0). Label + glyphe existent déjà.

**Critères d'acceptation.** L'utilisateur comprend que DI est auto-maximisé. Si on ajoute le plancher : taper
« DI ≥ 50 » force ≥50 % sans casser le fold ; DI n'apparaît jamais dans la grille « à maximiser ».

**Risques.** Le piège est sémantique : un DI exposé est un **plancher requis** (puissance-6), pas un
« maximise ». DI peut être négatif sur certains gear/subs (machinerie cible clampe à `[-target, target]`,
coerce ≥ 0). Garder le fold inchangé ; test dédié.

---

## FLOW-3 — Avertir des points d'aptitude non distribués (mode boss/dégâts)  🧹 P2 · Effort S
**Statut : 🔲 TODO**

**Symptôme (testeur).** « La répartition des aptitudes n'est pas faite. Des branches entières (Intelligence,
Agilité) non distribuées. Le builder pourrait prévenir, ou remplir lui-même, ou demander d'ajouter au moins une
stat de la branche dans les cibles. »

**Confirmé — mais PAS un bug** : optimum prouvé. En max-dégâts le solveur ne dépense que les points qui montent
l'objectif ; les branches sans impact dégâts (Intelligence = HP%/résists/heal ; Agilité = tacle/esquive/initi/
volonté ; lignes défensives de Chance) restent à 0. Contrainte par branche = `sum ≤ max` (pas `==`), donc points
non dépensés structurellement possibles. Même mécanique que les slots monture/pet vides (AGENTS.md §4). La GUI
affiche déjà `points/max` par branche.

**Fichiers.**
- `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt:630-654` (`createSkillVariables`, `addLessOrEqual`),
  `:1134-1157` (`buildMaxDamageObjective`, pas de tie-breaker — commentaire `:1131-1132`), `:1109-1121`
  (tie-breaker max-maîtrises existant), `:1473-1494` (`solutionToBuild`).
- `common-lib/.../skills/{Intelligence,Agility,Luck}.kt`, `CharacterSkills.kt:205-234` (`assignRandomPoints`,
  helper réutilisable pour un auto-fill).
- `gui-compose/.../stats/StatsPanel.kt:774-800` (`SkillTree`), `:1205-1216` (`branch` : points/max),
  `I18n.kt:210-211` (voisinage clés skill).

**Plan.**
- **Option A (recommandée, GUI-only, risk-free)** : dans `SkillTree`, `leftover = branches.filter { points <
  max }` ; si non vide, afficher un avertissement (`WColor.warning`) : « X points d'aptitude non assignés — sans
  effet sur les dégâts de ce build ; à répartir en jeu (Vie / résistances / esquive) ». `Tr` EN/FR. Mirrorer en
  CLI si la CLI affiche l'allocation.
- **Option B (M)** : auto-remplir les points restants vers un défaut survivabilité par branche (post-step
  neutre pour l'objectif ; respecter `PairedCharacteristic`/caps ; **ne pas** toucher les branches qui nourrissent
  les dégâts).

**Critères d'acceptation.** Un build max-dégâts avec branches vides affiche l'avertissement (Option A). Le
libellé doit clarifier que ces stats sont sans effet dégâts (sinon « solveur cassé » perçu).

**Risques.** Pas une correctness. Option A quasi sans risque. Option B : changer l'export Zenith ; ne pas
re-remplir les branches déjà optimales.

---

# E. Refactors transverses (pickers / modales)

## REF-1 — Abstraction partagée des pickers add/suppression  🏗️ P1 · Effort M
**Statut : 🔲 TODO** · **Décision dev : « corriger un bug quelque part le corrige pour tout le monde — partageons
et abstrayons la logique. »**

**Origine.** Bug #3 (testeur) : « Quand j'exclus un objet, il s'enlève pas de la liste des objets ! J'appuie sur
le même depuis 2 min. » + QoL : « taper directement dans la boîte sans cliquer d'abord » + « sélectionner
plusieurs items sans que la fenêtre se ferme ».

**Confirmé.** Les pickers ne partagent pas leur logique : `ItemPickerModal` ignore `excludedItems`/`forcedItems`
(les items déjà épinglés restent listés et re-cliquables → no-op qui referme la modale). À l'inverse,
`AddStatModal` fait déjà bien (`def.characteristic !in excluded`). Le `SearchField` n'auto-focus pas.
`pickItem()` met toujours `modal = null` (pas de multi-sélection).

**Objectif : une couche partagée pour TOUS les pickers add/suppr** — `AddStatModal`, `ItemPickerModal`
(forcés **et** exclus), `SublimationPickerModal`, `PassivePickerModal`, `BossPickerModal`,
`ItemRunePickerModal` — de sorte qu'un correctif profite à tous :
- **(a) Filtrer les entrées déjà sélectionnées** (épinglées/forcées/exclues) — corrige #3 *partout*.
- **(b) Auto-focus** du champ de recherche à l'ouverture.
- **(c) Multi-sélection** sans fermeture + bouton « Terminé » explicite.
- *(le tri alphabétique localisé est REF-2, à coordonner.)*

**Fichiers.**
- `gui-compose/.../components/Modals.kt:72-121` (`ModalHost` — router), `:294` (`AddStatModal`, **le bon
  pattern** `!in excluded`), `:445-463` (`ItemPickerModal.results`), `:466/477` (fermeture), `:556`
  (`SublimationPickerModal`), `:817` (`ItemRunePickerModal`), `:998` (`SearchField` — point d'auto-focus
  partagé), + `PassivePickerModal`/`BossPickerModal`.
- `gui-compose/.../shell/AppShell.kt:156-159` (câblage `equipmentCatalog`, passer les sets épinglés).
- `gui-compose/.../state/BuildSearchModel.kt:621-636` (`pinExcluded`/`pinForced` dédoublonnent par `matchName`),
  `:649-656` (`pickItem` → `modal = null`).

**Plan.**
1. Extraire un composable/contrat commun `CatalogPickerModal<T>` (ou un `PickerScaffold`) paramétré par :
   liste catalogue, requête, `alreadySelected: Set<key>` (filtré via `filterNot`), callback `onPick`,
   `multiSelect: Boolean`, label/empty-state. `SearchField` y intègre l'auto-focus (`FocusRequester` +
   `LaunchedEffect(Unit){ requestFocus() }`).
2. Passer les sets épinglés (`forcedItems`/`excludedItems` → leurs `matchName = name.fr`) à `ModalHost`, puis
   aux pickers concernés ; filtrer (un item dans l'un OU l'autre = retiré du catalogue, comme `pinForced`/
   `pinExcluded` sont mutuellement exclusifs). **Appliquer le `.take(60/120)` APRÈS le filtre.**
3. Multi-sélection : `pickItem`/`pickSublimation`/… n'auto-ferment plus ; bouton « Terminé » + compteur ;
   garder le clic-scrim comme fermeture.
4. Migrer les 6 pickers sur le scaffold.

**Critères d'acceptation.** Exclure/forcer un item le retire **immédiatement** de la liste du picker (tous
pickers). Ouvrir n'importe quelle modale d'ajout met le focus dans le champ (taper directement). Le picker
d'items permet d'enchaîner plusieurs sélections puis « Terminé ». Un seul correctif futur sur le scaffold se
propage partout. (Idéalement : un test « le picker exclut un item déjà épinglé ».)

**Risques.** `ItemPicker` était conçu mono-pick → besoin d'une affordance « Terminé »/compteur visible.
Matcher sur `name.fr` (jamais `name.en`). Hotspot `Modals.kt` → coordonner avec REF-2/SUB-1/RUNE-2.
Plusieurs items partagent un même `name.fr` (variantes rareté) → filtrer par nom les masque ensemble :
acceptable (le filtrage moteur est lui-même par nom), à revoir si on veut une granularité par id un jour.

---

## REF-2 — Tri alphabétique localisé de TOUTES les modales catalogue  🏗️ P2 · Effort M
**Statut : 🔲 TODO** · **Décision dev : « par ordre alphabétique PAR LANGUE, et toutes les modales avec catalog
de la même manière : boss, items, runes, sublimations, etc. »**

**Origine.** Testeur : « Choix de classe par ordre alphabétique ? » → généralisé par le dev à **toutes** les
modales catalogue, **trié selon la langue d'affichage courante**.

**Confirmé.** La liste de classes itère `CharacterClass.entries` en ordre d'enum (breedId 1-19), sans tri. Les
pickers catalogue ne trient pas par nom localisé.

**Cibles.** Classe (`ClassDropdown`), boss (`BossPickerModal`), items (`ItemPickerModal`), runes
(`ItemRunePickerModal`/options de runes), sublimations (`SublimationPickerModal`), passifs
(`PassivePickerModal`).

**Fichiers.**
- `gui-compose/.../shell/TopBar.kt:545` (`ClassDropdown`, `filter { it != UNKNOWN }` sans `sortedBy`).
- `gui-compose/.../components/Modals.kt` — tous les pickers catalogue (item/boss/subli/passif/rune).
- Helpers de nom localisé : `CharacterClass.displayName()`, `I18nText.localized(lang)`
  (`gui-compose/.../components/SpellVisuals.kt:51`).

**Plan.**
1. Helper partagé `fun <T> List<T>.sortedByLocalizedName(lang, selector): List<T>` utilisant un
   **`java.text.Collator`** de la locale courante (tri accent-insensible correct par langue), pas un `compareTo`
   brut. Le `selector` extrait le nom localisé de chaque type (classe/boss/item/subli/passif/rune).
2. L'appliquer à chaque liste de catalogue **après** filtrage requête, **avant** `.take(...)`. Le tri suit
   `LocalLang` (recompose au changement de langue).

**Critères d'acceptation.** Toutes les modales catalogue listent leurs entrées par ordre alphabétique du **nom
affiché dans la langue courante** ; basculer EN↔FR re-trie. « É/È/À » triés correctement (Collator).

**Risques.** Hotspot `Modals.kt` partagé avec REF-1/SUB-1 → faire REF-1 d'abord ou coordonner. Le tri ne doit
pas casser un éventuel ordre métier voulu (ex. boss par tier ?) — vérifier qu'aucun picker ne dépend d'un ordre
sémantique ; sinon, tri alpha en option secondaire. Perf : tri d'un gros catalogue items à chaque frappe →
`remember`/`derivedStateOf` sur (query, lang).

---

# F. QoL ponctuels

## QOL-1 — Tooltip sur les libellés de stats cibles tronqués  🧹 P3 · Effort S
**Statut : 🔲 TODO**

**Symptôme (testeur).** « Sur les stats cibles : permettre de hover quand le texte n'est pas affiché en
entier ? »

**Confirmé.** Le libellé de `TargetStatRow` tronque (ellipsis) sans `TooltipArea`.

**Fichiers.** `gui-compose/.../request/RequestPanel.kt:942` (`TargetStatRow`). Pattern déjà utilisé :
`TopBar.kt:405` (`ActiveBuildChip`), `RequestPanel.kt:1024` (`PriorityMeter`).

**Plan.** Envelopper le `Text` du libellé dans `TooltipArea(tooltip = { /* nom complet */ })`
(`@OptIn(ExperimentalFoundationApi::class)`).

**Critères d'acceptation.** Survoler un libellé tronqué affiche le nom complet.

---

## QOL-2 — Durée de recherche vide → 10 minutes  🧹 P2 · Effort S
**Statut : 🔲 TODO** · **Décision dev : « pour la durée vide, on peut mettre 10 minutes. »**

**Symptôme (testeur).** « Si je laisse vide la durée de recherche, il continue jusqu'à l'optimum ? » →
aujourd'hui un champ vide retombe sur **20 s en dur**, pas « illimité » ni 10 min.

**Confirmé.** `(snapshot.duration.toIntOrNull() ?: 20).coerceAtLeast(1).seconds`.

**Fichiers.** `gui-compose/.../state/BuildSearchModel.kt:711` (le `?: 20`), `:756` (timer de progression divise
par `searchDurationMs` — OK pour 600 s), `gui-compose/.../state/UiState.kt:179` (champ durée).

**Plan.** Remplacer le fallback `?: 20` par **`?: 600`** (10 min). Mettre à jour le placeholder/i18n du champ
pour le communiquer (« vide = 10 min »).

**Critères d'acceptation.** Champ durée vide → recherche de 600 s ; la barre de progression reste cohérente.

**Risques.** Ne pas mettre `Int.MAX_VALUE` (barre toujours à 0 % + risque de gel si l'optimum n'est jamais
prouvé). 600 s est un cap sûr.

---

# G. Clarifications, non-bugs & déjà fait (pas d'action / juste vérifier)

### I18N-1 — Sublis & passifs en anglais sous UI FR  🔵 Déjà fait
Corrigé sur `main` par le commit `968916dd` (« translated spell/passive/sublimation text », 24/06/2026) →
livré en **1.8.0**. Le testeur était sur la **1.6**. Données : FR rempli pour 232/232 sublis et 332/332 passifs.
→ **Action : confirmer au testeur de passer en 1.8.0.**
*Résidu mineur (toujours présent)* : le **texte d'effet** des ~75 sublis sans effet structuré reste EN
(`gui-compose/.../components/SublimationText.kt:35`, fallback `rawText` anglais). À traiter plus tard
(idéalement `bdata-extractor` émet un `rawText` en `I18nText`). *Petit ticket potentiel si on veut le finir.*

### CLAR-1 — 11 PA « mieux » que 12 PA  💬
Normal : on troque 1 PA contre des dégâts ; plus de PA ≠ plus de dégâts. Suggestion UX (optionnelle) : une
*hint* « lance d'abord sans contrainte de PA pour voir ce que le moteur propose ».

### CLAR-2 — CC % vs maîtrise pure  💬
Déjà géré : c'est la formule de dégâts complète (crits inclus) qui est maximisée ; l'affichage des sorts montre
le dégât moyen attendu + lignes non-crit/crit.

### CLAR-3 — 689 (autobuilder) vs 682 (WCA), ~1 %  💬
Parité quasi parfaite après ajustements du testeur. Si recherche de perfection : vérifier le **multiplicateur de
dos** (le dev pense qu'il est déjà là — l'écart de 1 % est dans le bruit). Basse priorité.

### CLAR-4 — Auto-update Windows lent / changelog non affiché  💬
Connu (Conveyor + délai de propagation Windows). Pas un bug code. Le changelog apparaît après MAJ manuelle.

### CLAR-5 — Bi/multi-élément lent ; « tous » = uniquement avec un boss précis  💬
Limitation connue et documentée (le bi-élément « marche mais très long »). Pas d'action court terme.

### CLAR-6 — Seulement les passifs de classe  💬
Normal pour l'instant (confirmé par le dev).

---

## Annexe — provenance

Investigation menée par fan-out multi-agents sur le code (HEAD `bc3ed8f0`), 12 items vérifiés. Les numéros
d'origine (#1–#13) du premier triage Discord correspondent à : #1→ENG-1, #2→ENG-2 (+SUB-1), #3→REF-1, #4→RUNE-1,
#5→ENG-3, #6→FLOW-1, #7→FLOW-2, #8→FLOW-3, #9→SUB-1, #10→SUB-2, #11→RUNE-2, #12→RUNE-3, #13→REF-2 + QOL-1/QOL-2.
