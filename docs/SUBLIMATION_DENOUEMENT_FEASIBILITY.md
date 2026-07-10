# Étude de faisabilité — sublimation « Dénouement » (mode max-dégâts)

> **Statut : DÉJÀ SUPPORTÉE.** Contrairement à l'hypothèse de départ (« la conversion serait
> différée en P6 »), la sublimation **Dénouement** est *déjà* modélisée, choisie par le solveur
> **et** couverte par le certificat d'optimalité prouvée du mode max-dégâts. Coût pour l'activer :
> **nul** — rien à implémenter. Ce document explique l'effet exact, l'état du support `CONVERSION`,
> pourquoi le modèle est sain, et quand la sublimation gagne réellement.

Date : 2026-07-03 · Branche : `docs/denouement-feasibility`

---

## 1. Ce que fait « Dénouement »

Identité (décodée en 1ʳᵉ partie depuis le client de jeu par `bdata-extractor`, présente dans
`autobuilder/src/main/resources/sublimations.json`) :

| Champ | Valeur |
|---|---|
| `stateId` | `5077` |
| `zenithId` | `24132` |
| Nom | Dénouement / Unraveling / Desenlace |
| Rareté | **EPIC** (occupe la châsse épique dédiée du personnage — pas un socket 3-couleurs) |
| `kind` | **`CONVERSION`** |
| `solverChoosable` | **`true`** |
| Condition | `CRIT_AT_LEAST 40` — le **taux** de coup critique doit être ≥ 40 % |
| Conversion | `from = MASTERY_CRITICAL`, `to = MASTERY_ELEMENTARY`, `percent = 100` |
| Texte | *If Critical Hit ≥ 40% \| Convert 100% of mastery critical into mastery elementary* |

**Mécanique.** Dans Wakfu, la **Maîtrise Critique** n'ajoute des dégâts *que* sur les coups
critiques. Dénouement **déplace** 100 % de cette maîtrise critique vers la **Maîtrise Élémentaire**
(générique, tous éléments), qui s'applique à **tous** les coups (critiques *et* non-critiques). En
échange, la maîtrise critique tombe à 0. La conversion n'est active que si le taux de critique de la
feuille de perso est ≥ 40 %.

**Pourquoi c'est populaire / « optimal ».** À fort taux de critique, la maîtrise critique n'était
« utile » qu'une fraction du temps (≈ le taux de crit). La transformer en maîtrise élémentaire la
rend utile à *chaque* frappe. Dès qu'on possède de la maîtrise critique à déplacer et qu'on tient le
seuil de 40 % de crit, la conversion est un gain d'espérance de dégâts. C'est un choix de fin de
build très courant sur les DD à haut crit.

### Point de vérification (valeur du pourcentage)

La donnée 1ʳᵉ-partie du dépôt dit **100 % sous condition ≥ 40 % de crit**, et elle est validée par
`bdata-extractor/.../SublimationInGameValueTest` (snapshot comparé aux infobulles en jeu). Un résumé
de recherche web a mentionné « 50 % » — vraisemblablement une valeur **pré-refonte / obsolète** (les
sources communautaires wakfu.com / fandom renvoient 403/402 et n'ont pas pu être ouvertes ici). Le
décodage bdata + le test in-game priment ; à re-confirmer visuellement à la prochaine bump de version
de jeu, mais aucune raison de douter de la valeur actuelle.

---

## 2. État du support `CONVERSION` dans le code

**Le bucket `CONVERSION` est entièrement implémenté et livré** (PR #160, « Lot 3 — sublimations
choisies par le solveur », cf. `docs/FULL_DAMAGE_MODE_STATUS.md` §Sublimations). Il **n'est pas**
différé. Le seul bucket encore non-modélisable reste `COMBAT_CONDITIONAL` (forced-input uniquement).

Les quatre familles de `SublimationKind` (`common-lib/.../Sublimation.kt`) :

| Kind | Modélisé solveur ? |
|---|---|
| `FLAT` | ✅ contributions plates inconditionnelles |
| `STATIC_CONDITIONAL` | ✅ appliqué si une condition build-statique de début de combat tient |
| **`CONVERSION`** | ✅ **déplace `percent`% de `from` vers `to`** (éventuellement sous condition) |
| `COMBAT_CONDITIONAL` | ❌ dépend d'événements en combat — forced-input seulement |

**Dénouement est l'unique sublimation `CONVERSION` de tout le jeu** (1/232 subs ; 1 seule sur les 47
`solverChoosable`). Toute la machinerie de conversion ci-dessous existe donc *spécifiquement* pour
elle.

### 2.1 Modèle principal CP-SAT (`WakfuBuildSolver.buildSublimationTerms`)

La conversion entre dans le modèle comme une paire de termes gatés par la condition réifiée
(`autobuilder/.../WakfuBuildSolver.kt:7605`) :

```kotlin
val raw   = percentOf(preSubStat(conv.from), conv.percent, ...)          // percent% de la critM pré-sub
val moved = tBoolGate("subConvMoved_${sub.stateId}", raw, applies, 0..STAT_ABS_MAX) // 0 si non pris
map[effectiveStat(conv.to)]   += Term(moved, +1)   // la maîtrise élémentaire GAGNE `moved`
map[effectiveStat(conv.from)] += Term(moved, -1)   // la maîtrise critique PERD `moved`
```

- `applies` = variable booléenne réifiée qui vaut 1 ssi la condition `CRIT_AT_LEAST 40` tient
  (`appliesVar` / `reifyCondition`), lue sur le **taux** de crit (`CRITICAL_HIT`).
- `moved` lit `preSubStat(from)` = la maîtrise critique **pré-sublimations** (base + items + runes +
  skills). C'est *exactement* la quantité déplacée en jeu.
- `+moved / −moved` : transfert conservatif, dépendant du build (pas d'une constante de niveau) —
  donc parfaitement adapté à CP-SAT (variable entière, gate booléen).

### 2.2 Certificat d'optimalité prouvée (mode max-dégâts)

Le certificat par cellule d'AP gère la conversion **analytiquement** via un **max à deux passes**
(`certifyMaxPerHitAtAp`, `WakfuBuildSolver.kt:4565`) :

- **Passe A** : sub *non prise* — les termes `moved` sont retirés (couvre aussi les builds où
  `moved = 0`).
- **Passe B** : sub *forcée* — la conversion est appliquée analytiquement à **toutes** les sources
  de maîtrise critique pré-sub (base/items/runes/skills, en phase avec `preSubStat` ; les critM des
  *passives* et des *autres* subs ne sont **pas** converties).

Le certifieur ne certifie analytiquement que **la forme exacte de Dénouement** (une seule sub
`CONVERSION`, `from = MASTERY_CRITICAL`, `to` = maîtrise élémentaire / élément du scénario, `percent
∈ 1..100`) ; toute autre forme *bail* proprement vers CP-SAT (`Long.MAX_VALUE`). La conversion
partage la châsse épique : elle est mise en concurrence avec les autres épiques (DI, etc.) dans le
découpage « mondes » du certifieur.

### 2.3 Tests existants

- `WakfuBuildSolverTest.kt:2018` — « solver applies an Unraveling-style crit-mastery to elemental
  conversion when the crit condition holds » : vérifie que le solveur **choisit** Dénouement et que
  le re-scorer reflète le transfert (critM → 0, élémentaire += valeur déplacée).
- `WakfuBuildSolverTest.kt:5507` — « max-damage AP-cell certifier matches CP-SAT with a taken
  Unraveling-like conversion sub » : le certificat prouvé **égale** l'optimum CP-SAT avec la sub
  prise, avec une assertion de *strictness* (prendre la conversion doit **battre** l'optimum
  sans-conversion sur au moins une cellule).
- Autres locks adverses autour du fold, de la châsse épique et des conditions (mêmes fichiers).

---

## 3. Faisabilité de la modélisation dans l'objectif max-dégâts

**Verdict : déjà faisable et fait — sainement et de façon prouvable.** Les points délicats anticipés
par la demande sont tous couverts :

| Piège potentiel | Traitement dans le modèle |
|---|---|
| La conversion change les totaux de maîtrise de façon **dépendante du build** | Modélisée par une variable entière `moved` (= `percent%` de la critM pré-sub) gatée booléennement, pas par une constante — c'est le cas d'usage naturel de CP-SAT. |
| Interaction avec le **taux de crit** (la condition) | La condition `CRIT_AT_LEAST 40` est réifiée sur `CRITICAL_HIT` ; `applies` force `moved = 0` tant que le seuil n'est pas tenu. |
| La conversion peut rendre **le crit moins intéressant** | L'objectif max-dégâts intègre déjà le taux de crit dans l'espérance de dégâts (maîtrise critique ≈ créditée `crit%` du temps, maîtrise élémentaire créditée à 100 %). Le solveur pèse donc *exactement* le compromis « garder la critM (utile crit% du temps) vs. la déplacer en élémentaire (utile 100 % du temps) » — il ne prend Dénouement que lorsqu'elle augmente réellement l'objectif. |
| Effets de bord sur les autres modulations | La critM déplacée sort du bucket critique et entre dans le bucket élémentaire *avant* les multiplicateurs (%DI, dos/berserk, résistances) ; aucun double-comptage — le certifieur le verrouille. |
| **Provabilité** (max-dégâts) | Conservée : la conversion est traitée analytiquement en 2 passes, l'optimum reste **prouvé** (pas rétrogradé en FEASIBLE). |

Limites connues (documentées, non-bloquantes) :

- Le **certifieur analytique** ne gère qu'**une** sub `CONVERSION` de la forme de Dénouement ;
  au-delà il *bail* vers CP-SAT (correct, juste non-prouvé). Sans objet aujourd'hui : Dénouement est
  l'unique sub de ce type.
- Les critM apportées par des **passives** ou d'**autres** subs de début de combat ne sont pas
  converties (choix conservateur, conforme au timing en jeu).

---

## 4. Verdict, coût, et « est-elle vraiment optimale » ?

- **Faisable ?** Oui — **déjà réalisé et livré** (PR #160). Rien à écrire.
- **Coût ?** **Nul** côté implémentation. La seule action éventuelle est une **re-vérification de la
  valeur 100 % / seuil 40 %** vs. les infobulles en jeu (voir §1) — 10 min, faite automatiquement à
  la prochaine bump de données via `SublimationInGameValueTest`.
- **Réellement « optimale » ?** Le solveur **le décide par build**, exactement. Elle gagne quand :
  (1) le taux de crit ≥ 40 % (condition tenue), **et** (2) le build possède de la maîtrise critique à
  déplacer, **et** (3) le gain « maîtrise utile à 100 % des frappes » l'emporte sur l'usage de la
  châsse épique par un autre épique (p. ex. un bonus %DI). Comme la châsse épique est unique,
  Dénouement est en **concurrence directe** avec les autres épiques : le solveur ne la retient que
  lorsqu'elle est réellement meilleure — la revendication communautaire « souvent optimale » est donc
  automatiquement respectée *quand elle est vraie*, et écartée sinon, sans réglage manuel.

## 5. Plan proposé

Aucun développement requis pour le support. Actions optionnelles, par ordre de valeur :

1. **(Reco) Ne rien coder.** Confirmer à l'utilisateur que Dénouement est déjà choisie
   automatiquement en mode max-dégâts (et prouvable). Un lancement de contrôle suffit :
   ```sh
   ./gradlew :autobuilder:test --tests "*Unraveling*"          # 2 tests existants
   ```
   ou une recherche max-dégâts sur un build à ≥ 40 % de crit avec de la maîtrise critique, pour voir
   la sub apparaître dans le résultat.
2. **(Hygiène données)** Re-confirmer visuellement `percent = 100` / `CRIT_AT_LEAST 40` à la
   prochaine mise à jour du jeu (le test in-game s'en charge ; lever le doute « 50 % » d'une source
   web obsolète).
3. **(UX, hors périmètre)** Vérifier que l'infobulle GUI de Dénouement (`SublimationText.kt`) rend
   bien le texte de conversion « 100 % maîtrise critique → maîtrise élémentaire (si crit ≥ 40 %) ».
4. **(Future, si un jour Ankama ajoute d'autres subs `CONVERSION`)** Généraliser le certifieur
   analytique au-delà de la forme unique (aujourd'hui il *bail* proprement — donc pas de régression,
   juste une perte de « badge prouvé » sur ces cas hypothétiques).

---

### Références code

- `common-lib/src/main/kotlin/me/chosante/common/Sublimation.kt` — `SublimationKind.CONVERSION`,
  `SublimationConversion(from, to, percent)`.
- `autobuilder/src/main/resources/sublimations.json` — entrée `stateId 5077` (Dénouement).
- `autobuilder/.../WakfuBuildSolver.kt:7605` — application CP-SAT (`subConvMoved`).
- `autobuilder/.../WakfuBuildSolver.kt:4565` — certificat prouvé (max 2 passes).
- `autobuilder/.../WakfuBuildSolverTest.kt:2018`, `:5507` — tests solveur + certifieur.
- `docs/FULL_DAMAGE_MODE_STATUS.md` §Sublimations — `CONVERSION (from→to) implémenté`.
