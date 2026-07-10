# Full-damage mode — état des lieux (audit, 2026-06-20)

> **Mise à jour (2026-07-09) :** la PR #167 (fondation data, noms fixes, sources 1ʳᵉ-partie) est
> **fusionnée** — la §4 « merger #167 en premier » est donc obsolète. Depuis : badge « optimal prouvé »
> (certificat AP-cell, `CERTIFICATE_PROD_PLAN.md`), sublimations élargies (Critical Secret, DI
> par-élément Gel/Brûlure, familles HP), exclusion de sublimations, warm-start max-maîtrises. Le
> **reste à faire** est toujours la liste §3 (modèle WP, Light/Stasis, k≥3, débuffs par-élément
> bloqués par la donnée, ramp hors-scope) + les tickets data résiduels de
> `non-official-sources-audit.md`.

État de l'avancement du **« full damage mode »** (mode max-dégâts complet) : meilleur build *plus la rotation
de sorts jouée*, contre un boss donné, en mono / bi / multi-élément. Document de **synthèse/audit** — aucune
implémentation, aucun code modifié.

> **Sources lues :** l'ancienne feuille de route « Lots » `docs/FULL_DAMAGE_PLAN.md` (**retirée** lors du
> nettoyage docs — ce document en reprend le rôle de suivi), `docs/SPELL_ROTATION.md`,
> `docs/BOSS_MODE_RESEARCH.md`, `docs/non-official-sources-audit.md`, l'historique git (`main`, les branches
> `feat/*`), et l'état réel des PR (`gh pr list`).

---

## ⚠️ Constat de départ : la prémisse « branches non mergées » est en grande partie périmée

La consigne décrivait le full-damage mode comme s'étalant sur des **branches dépendantes non encore mergées**.
**Ce n'est plus le cas.** L'essentiel a été **redécoupé en « Lots » et mergé sur `main`**. Réconciliation :

| Branche citée dans la consigne | Réalité aujourd'hui |
|---|---|
| `feat/max-damage-mode` | **N'existe plus.** Contenu re-landé via PR [#153](https://github.com/CharlyRien/wakfu-autobuilder/pull/153) (objectif max-dégâts spell-aware) — **sur `main`**. |
| `feat/spells-dataset` | **N'existe plus.** Dataset de sorts + helpers `SpellDamage`/`BuildSpellDamage`/`SpellCatalog` re-landés via #153 — **sur `main`**. |
| `feat/spell-rotation` | **Remote-only, SUPERSEDED — NE PAS MERGER.** Ses 22 commits (optimiseur de rotation, boucle externe AP, debuffs de résistance + séquencement) ont été **re-implémentés et mergés** via #153 + Lots 1-2. Le merger réintroduirait des doublons / conflits de noms de fichiers. (cf. mémoire « feat-spell-rotation-superseded ».) |
| `feat/boss-mode` | **Local-only, SUPERSEDED.** Boss CLI mergé via [#157](https://github.com/CharlyRien/wakfu-autobuilder/pull/157), GUI picker via [#161](https://github.com/CharlyRien/wakfu-autobuilder/pull/161). |
| `feat/data-pipeline-and-damage-fixes` | **PR [#167](https://github.com/CharlyRien/wakfu-autobuilder/pull/167) — OUVERTE.** Le **seul** gros chantier encore en vol. Refonte data (noms de fichiers fixes + sources 100 % officielles bdata/CDN), inclut des correctifs dégâts (damage level-aware, sublimations 1ʳᵉ-partie). |
| `fix/enchant-level-by-item-level` | **PR [#151](https://github.com/CharlyRien/wakfu-autobuilder/pull/151) — MERGÉE.** Plafonne le niveau de rune/shard par le niveau de l'**item**, pas du personnage. |

**Lots mergés sur `main`** (ex-feuille de route `FULL_DAMAGE_PLAN.md`, retirée) : #153 (objectif spell/boss-aware + dataset),
#157/#161 (Lot 0 boss CLI+GUI), #162 (Lot 1 cast limits), #160 (Lot 3 sublimations), #163/#164 (Lot 2 bi-élément),
#165 (Lot 4 passifs), #166 (Lot 5 coherence floor).

> La branche courante (`docs/full-damage-mode-status`, partie de `feat/paperdoll-type-and-rarity-icons`) est
> **en aval de #167** : son `AGENTS.md` décrit déjà le monde post-#167 (fichiers à nom fixe, `spell-damage.json`,
> `sublimations.json` 1ʳᵉ-partie). Les symboles/chemins cités ci-dessous reflètent **cet** arbre.

---

## 1. CE QUI EST FAIT (mergé sur `main`)

### Objectif solveur max-dégâts (CP-SAT, spell-aware + boss-aware) — ✅
- `WakfuBuildSolver.StatBuilder.perTurnDamageScore` : le solveur choisit **équipement + élément + rotation**
  conjointement, **en mode max-dégâts uniquement** (les modes most-masteries / précision sont intouchés).
- **Le truc anti-explosion** : la sélection de sorts se réduit à une table de débit **précalculée et
  indépendante du build** `T_e[ap]` (`SpellRotationOptimizer.baseThroughputTable`) ; le seul produit
  variable×variable restant est `throughput_e × perHit_e` (déjà linéarisé). Aucune variable « nombre de sorts »
  ne rencontre jamais une variable de maîtrise dans CP-SAT (la « règle d'or »).
- **Lockstep scorer** : `scoreFor` (max-dégâts) ré-évalue via `SpellRotationOptimizer.bestAcrossElements`, donc
  le build maximisé == le build émis avec le `matchPercentage` rapporté.

### Modulations de dégâts (couverture) — ✅
- **11 modulations vivantes** couvertes (Lot 2 / commit `174b0d9` reverté pour single-target & area). Les
  modulations *single-target* et *area* ont été **retirées par Ankama en 2023** → hors-jeu par conception
  (cf. mémoire « damage-modulation-coverage », doc `docs/damage-modulation-research`).

### Dataset de sorts — ✅
- `spells.json` (~710 sorts, 18 classes : nom/élément/PA/portée/icône + le hit de base **niveau max**), scrape
  encyclopédie (`spells-extractor`, résumable). Helpers `Spell`, `SpellDamage`, `BuildSpellDamage`,
  `SpellCatalog`, `DamageScenario`.
- **Dégâts level-aware** (#167 / `spell-damage.json` via `bdata-extractor`) : la formule par niveau
  `floor(base + inc·niveau)` est décodée des tables bdata Spell(66)→StaticEffect(68), **ancrée** sur la valeur
  encyclopédie niveau-max. `SpellCatalog` met chaque hit à l'échelle du **niveau du caster** (~86 % obtiennent
  la pente bdata exacte, le reste une approximation linéaire — jamais une régression au niveau max).

### Rotation de sorts (budget PA) + boucle externe — ✅
- `SpellRotationOptimizer` : knapsack DP exact sur le budget PA (`dp[a]` = meilleurs dégâts attendus ≤ `a` PA).
- **Cast limits dans la table** (Lot 1, #162) : `Spell.maxCastsThisTurn` plie per-turn + cooldown + per-target ;
  `baseThroughputTable` est un knapsack borné par sort. WP **cost** extrait (`Spell.wpCost`, affichage) mais **WP
  pas encore modélisé dans la rotation** (cf. §2).
- **Boucle externe `MaxDamageSearch`** (rotation-aware + boss-aware), structure à **phases gatées** :
  - **Phase 1** : solve non contraint (élément naturel A₀), streamé, re-scoré debuff-aware.
  - **Phase 2** : sondes PA-épinglées autour de A₀ — **seulement** si la classe a un debuff de résistance qui
    peut décaler le point de rupture PA.
  - **Phase 3** : bi-élément — **seulement** si la *requête* met >1 élément en jeu (`pair × PA × split`).
  - `proven` (optimalité prouvée) **uniquement** quand `activePhases == 1 && phase1Optimal` (cf. mémoire
    « maxdamage-phase-gating »).

### Debuffs de résistance + séquencement — ✅
- `Spell.targetResistanceReductionFlat` : **8 sorts** debuff de résistance ennemie actifs, sur 8 classes (Sram
  Assassinat −100, Ouginak Sidekick −100, Sadida Toxines/Sudden Chill −50, Iop Focus −50, Pandawa Bambou −50,
  Osamodas Cri Affaiblissant −50, Ecaflip Trois Cartes −30).
- `SpellRotationOptimizer.bestSequencedRotation` : applique le debuff en **flat** (courbe `res%` concave,
  `Resistances`), puis knapsack le PA **restant** à la résistance post-debuff → le séquencement (debuff d'abord)
  change le PA optimal, capté par le sondage PA de la boucle externe (oracle de test Sram vs boss 55 %).

### Boss mode (Lot 0) — ✅
- **CLI** (#157) : `--boss` / `--boss-element` / `--boss-difficulty`, auto-élément via l'objectif boss-aware
  (`DamageScenario.againstAllElements`, `candidateElements`, `max` sur éléments), `--boss-resistances` en override
  manuel, `turnsToKill` (difficulté = multiplicateur HP).
- **GUI** (#161) : picker boss cherchable + profil de résistance + difficulté + hits-to-kill + icônes boss.
- **Données bestiaire** : `monsters.json` (62 k lignes) + `monster-overlay.json` (rang boss) — désormais
  **1ʳᵉ-partie via `bdata-extractor`** (table Monster 42, résistances flat par élément), conversion
  `Resistances.flatToPercent` partagée scoring↔affichage.

### Bi-élément (Lot 2) — ✅
- Objectif bi-élément linéaire `perTurnDamageScoreBiElement` (M1, #163), scale-identique au mono, sans
  double-comptage du « +tous éléments » / random-element (un **seul** appel `elementVars([e1,e2])`).
- Recherche M2 (#164) : `MaxDamageSearch` énumère `(pair × PA × split)` en parallèle, **pruning** dead-pair +
  frontière de Pareto des splits ; debuff partagé payé **une fois** ; affichage CLI+GUI du tour fusionné.

### Sublimations (Lot 3, #160) — ✅
- `solverChoosable` plié dans les stats résolues lues par l'objectif ; `ScenarioGate` évalué vs le
  `DamageScenario` ; `CONVERSION` (from→to) implémenté ; export Zenith. **`COMBAT_CONDITIONAL` reste
  forced-input.** Ensemble choisissable conservateur (~18/232) verrouillé par test (cf. §2).

### Passifs (Lot 4, #165) — ✅
- Loadout de passifs joueur : icônes réelles (`gfxId`), `PassiveCatalog` (slots 1→6 par niveau) ; les stats
  flat **déclaratives** des passifs choisis se plient dans le solve + re-scorer ; CLI `--passives` ; GUI picker.

### Coherence floor (Lot 5, #166) — ✅
- Plancher de survie EHP opt-in (pénalité power-2) + `RolePreset` (DPS/Melee/Backstab/Tank) ; CLI `--role` /
  `--survival-floor` / `--min-ehp` ; ligne de presets GUI. Remplace le défaut silencieux
  « BACK + DISTANCE + crit non capé ».

### Correctif enchantements (#151) — ✅
- Niveau de rune/shard capé par le niveau de l'**item** porteur, pas le niveau du personnage.

---

## 2. CE QUI EST PARTIEL / APPROXIMÉ

| Sujet | État | Détail |
|---|---|---|
| **Coût WP des sorts** | extrait, **pas modélisé** | `Spell.wpCost` porté pour l'affichage (103/715 sorts coûtent du WP). Pas plié dans la rotation : le WP est un pool **par combat** → il faut un modèle amortisé `WP_pool / n_turns` avec le pool gardé **constant** (un cap dépendant du build ré-introduirait le terme bilinéaire). Sorts 0-PA pur-WP = terme **additif** à part. Question ouverte : d'où vient `n_turns`. Cas Xelor mal capté (WP doublé + régén en combat scriptée). |
| **Auto-choix des passifs par le solveur** | **différé** | Seuls 6/332 passifs sont des stats flat utilitaires ; les passifs choisis sont **forced-input** côté joueur. Passifs *triggered / stacking / build-up* qui changent le multiplicateur en cours de combat = hors du modèle « tour parfait unique » (modélisés sous l'hypothèse « state assumed up » ou laissés forced-input). |
| **Sublimations** | **conservateur par conception** | ~18/232 `solverChoosable` (le max sûrement modélisable ; élargir inventerait des dégâts — audité & confirmé, cf. mémoire « sublimation-choosable-conservative »). `COMBAT_CONDITIONAL` reste forced-input. Verrouillé par `SublimationReproductionTest`. |
| **Debuffs de résistance** | **flat all-element seulement** | Cette version du jeu n'expose **pas** de réduction par-élément ; 8 sorts ennemis actifs captés, self/ally **droppés**, 6/8 ont une cible *non confirmée* (assumée ennemie, flaggée). Aucun debuff par-élément modélisé. |
| **Bi-élément capé à k=2** | **délibéré** | `(pair × PA × split)`. Multi (**k≥3**, Huppermage) hors-scope (rarement optimal ; explosion du nombre de splits) — à gater derrière un flag avec pruning k-dim si jamais nécessaire. |
| **Light / Stasis (G7)** | **hors modèle 4-éléments** | `SpellElement ∈ {FIRE,WATER,EARTH,AIR}`. **Xelor (stasis)** et porteurs de Lumière ne peuvent pas être modélisés vs la résistance light/stasis d'un boss. |
| **Single-target vs area mastery (G6)** | **non modélisé** | Aucune source de donnée quantifiable (actionId-400 = marqueur sans valeur ; WakForge ne le modélise pas non plus). Caveat documenté. |
| **Couverture boss (rang)** | **overlay curé** | `monster-overlay.json` = rang boss **hand-curated** (226 entrées, rang ≥ 1). Les résistances viennent désormais de bdata pour **tout** le bestiaire (l'ancien problème « 110 boss MethodWakfu manquants » est largement **résolu** par la migration bdata) ; mais le **rang** éditorial reste manuel (id absent → rang 0, masqué du picker). |
| **Réalisme « hits-to-kill »** | **affichage seulement** | Gaps boss G4/G5/G10 : HP / barrier / armor / block / niveau ne changent **pas** le classement (build-indépendants), n'affectent que le nombre affiché. Pas de barrier/armor/block modélisés. |
| **Tickets data en attente (#167)** | **ouverts** | Audit `docs/non-official-sources-audit.md` : `rune-level-requirements` (table hardcodée), `spell-cast-limit-names` / `spell-passive-names` (noms depuis l'encyclopédie — touchent la lisibilité, pas le calcul), `spells-damage-anchor` (ancre niveau-max encyclopédie), et **🟡 gardés** : `rune-value-tables` (valeurs **toujours** transcrites de WakForge), `sublimation-action-overlay` (overlay hand-authored). |

---

## 3. CE QUI MANQUE pour un full-damage mode *vraiment* complet

1. **Modèle WP-aware de la rotation** — pool amortisé `WP_pool / n_turns`, politique **par classe**
   (`amortized-pool` par défaut ; `renewable`/quasi-illimité pour Xelor, Eni-Tridelta, Foggernaut-stasis), terme
   additif pour les sorts 0-PA pur-WP. *Donnée déjà présente (`Spell.wpCost`), seul le modèle manque.*
2. **Debuffs de résistance par-élément** — bloqué par la **donnée** (cette version n'expose que du flat
   all-element). Rien à coder tant que la donnée n'existe pas.
3. **Support Light / Stasis** — étendre `SpellElement` + les maîtrises correspondantes ; indispensable pour
   modéliser **Xelor** et les porteurs de Lumière vs les boss à résistance light/stasis.
4. **Multi-élément k≥3** — machinerie identique (k-subset + composition de `A` en k parts) mais explosion des
   splits ; délibérément hors-scope, à gater derrière un flag.
5. **Auto-choix solveur des passifs + élargissement des sublimations** — limité par la part *modélisable* des
   effets (déclaratifs flat), pas par l'architecture.
6. **Simulation multi-tours / ramp** — build-up, régén WP inter-tours, stacking d'états, montée en puissance.
   **Hors-scope fondamental** : c'est la frontière *découvreur de build* vs *simulateur de combat*. Aucun
   optimiseur statique ne le prouve ; énoncé honnêtement dans CLI/GUI/docs (« meilleur build pour un tour parfait
   représentatif, sous hypothèses explicites »).
7. **Barrier / armor / block / fixed-damage dans hits-to-kill** — purement réalisme d'affichage (build-indépendant,
   ne change pas le classement).
8. **Finalisation des tickets data #167** — pour la *précision* : ancrer les noms cast-limit/passifs, sourcer la
   table de niveau-requis des runes ; **valeurs de runes** encore WakForge (exception documentée, pas de source
   officielle).

---

## 4. DÉPENDANCES + ordre de merge/implémentation suggéré

### Ce qui est déjà sur `main` (rien à ordonner)
Lots 0-5, dataset de sorts, rotation+boucle externe, debuffs, sublimations, passifs, coherence floor,
dégâts level-aware. Le full-damage mode **fonctionne de bout en bout** sur `main` (mono + bi-élément, boss-aware).

### Le seul gros chantier ouvert : PR #167 (data-pipeline)
```
#167  (fichiers à nom fixe + 100 % sources officielles bdata/CDN + dégâts level-aware + subs 1ʳᵉ-partie)
   └─►  branche courante feat/paperdoll-* (icônes)  ── EST DÉJÀ EN AVAL de #167
```
- **Merger #167 en premier.** C'est la fondation data ; tout le reste sur `main` suppose encore les noms
  *versionnés* (`*-v1.91.1.54.json`) — #167 est la migration vers les noms fixes. La branche courante en dépend.
- Ensuite, les **tickets data résiduels** (§3.8) sont indépendants et parallélisables (chacun = un finding de
  l'audit), à faire après #167 car ils s'appuient sur les sources 1ʳᵉ-partie qu'il introduit.

### Ordre d'implémentation suggéré pour le *reste* du full-damage (post-#167)
1. **Modèle WP** (§3.1) — donnée prête, gain le plus net, isolé dans `baseThroughputTable` + une politique par
   classe. **Indépendant.**
2. **Light/Stasis** (§3.3) — élargit `SpellElement` ; touche maîtrises + scénarios ; débloque Xelor. **Indépendant**
   mais transverse (toucher l'enum élément a un rayon large).
3. **Auto-choix passifs / sublimations élargies** (§3.5) — incréments sur les Lots 3/4 existants.
4. **Multi-élément k≥3** (§3.4) — extension de la Phase 3 bi-élément ; le plus coûteux pour le moins de gain →
   **dernier**, derrière un flag.
5. **Réalisme hits-to-kill** (§3.7) — cosmétique, à tout moment.
6. **Multi-tours** (§3.6) — **non recommandé** (change la nature du produit).

---

## 5. État de cohérence — conflits potentiels & ce qui doit être unifié

### Branches « fantômes » à NE PAS merger (sinon doublons / conflits)
- `feat/spell-rotation` (remote, 22 commits), `feat/max-damage-mode` & `feat/spells-dataset` (disparues),
  `feat/boss-mode` (local) : **toutes superseded**. Leur contenu est re-landé sur `main` via #153 + Lots. Les
  merger réintroduirait des `WakfuBuildSolver`/`SpellCatalog`/`MaxDamageSearch` divergents.
- **Collision de noms de fichiers** : ces branches référencent les noms **versionnés** (`spells-v1.91.1.54.json`,
  `monsters-v1.91.1.54.json`, `sublimations-v1.91.1.54.json`) que **#167 supprime** au profit des noms fixes →
  conflit garanti avec #167. C'est une raison de plus de ne pas les ressusciter.
- **Données boss** : `feat/boss-mode` portait les données **MethodWakfu** ; #167 les remplace par **bdata +
  CDN**. Source unifiée = bdata (`monsters.json` + `monster-overlay.json`). Ne pas réintroduire MethodWakfu.

### Divergence à surveiller : `MaxDamageSearch`
- #167 fait évoluer `MaxDamageSearch` (+240 lignes vs `main`) en plus de la migration data. Donc la version de
  `main` et celle de #167 **diffèrent** ; l'arbre courant (en aval de #167) est **l'autorité**. Toute reprise du
  moteur doit partir de #167, pas de `main`.

### Source de vérité unique de la version data
- Après #167 : `WakfuData.VERSION` (common-lib) est l'**unique** constante ; noms de ressources **fixes**
  (`equipments.json`, `spells.json`, …). Un bump = toucher cette seule constante (cf. mémoire
  « data-pipeline-fixed-filenames »). Toute branche encore sur les noms versionnés est à rebaser sur #167.

### Frontière d'optimalité — à énoncer partout de la même façon
- Le moteur **prouve** : meilleur build pour **un tour parfait représentatif**, par élément/paire + rôle +
  résistances boss + budget de ressources, avec chaque solve interne OPTIMAL et l'énumération
  `(pair × A × split 0..A)` exhaustive. Il **ne prouve pas** la dynamique multi-tours. CLI, GUI et docs doivent
  porter **le même** disclaimer (aujourd'hui dans `SPELL_ROTATION.md` et ce document, après le retrait de
  `FULL_DAMAGE_PLAN.md`).

---

## Annexe — récap PR

| PR | Titre | État |
|---|---|---|
| [#167](https://github.com/CharlyRien/wakfu-autobuilder/pull/167) | Data pipeline → 100 % sources officielles (+ damage & GUI fixes) | **OUVERTE** |
| [#166](https://github.com/CharlyRien/wakfu-autobuilder/pull/166) | Lot 5 — coherence floor | mergée |
| [#165](https://github.com/CharlyRien/wakfu-autobuilder/pull/165) | Lot 4 — passifs joueur | mergée |
| [#164](https://github.com/CharlyRien/wakfu-autobuilder/pull/164) | Lot 2 M2 — recherche bi-élément | mergée |
| [#163](https://github.com/CharlyRien/wakfu-autobuilder/pull/163) | Lot 2 M1 — objectif bi-élément CP-SAT | mergée |
| [#162](https://github.com/CharlyRien/wakfu-autobuilder/pull/162) | Lot 1 — cast limits + WP cost | mergée |
| [#161](https://github.com/CharlyRien/wakfu-autobuilder/pull/161) | Lot 0 GUI — boss picker + icônes | mergée |
| [#160](https://github.com/CharlyRien/wakfu-autobuilder/pull/160) | Lot 3 — sublimations choisies par le solveur | mergée |
| [#157](https://github.com/CharlyRien/wakfu-autobuilder/pull/157) | Boss CLI — auto-élément | mergée |
| [#153](https://github.com/CharlyRien/wakfu-autobuilder/pull/153) | Max-dégâts spell-aware + extraction self-hostée | mergée |
| [#151](https://github.com/CharlyRien/wakfu-autobuilder/pull/151) | enchant : cap niveau par item | mergée |

*Branches superseded (ne pas merger) : `feat/spell-rotation` (remote), `feat/boss-mode` (local),
`feat/max-damage-mode` & `feat/spells-dataset` (disparues).*
