# Points d'aptitude imposés : maquettes et rendu final (issue #284)

**Retenu : A + B**, en valeur exacte (réponse du joueur sur l'issue). Rendus hors écran FR des composants câblés :
`final-carte-4-lignes.png` (carte vide puis 4 lignes imposées), `final-selecteur.png` (sélecteur, branche Agilité pleine),
`final-arbre-cadenas.png` (arbre du résultat, 2 lignes verrouillées, points non placés signalés), `final-contexte.png` (les trois
côte à côte). Régénérer : `WAKFU_SKILL_POINTS_SCREENSHOTS=<dossier> ./gradlew :gui-compose:test --tests "*SkillPointsCardUiTest"`.

Les captures `a*`, `b*`, `c*` et `ab-contexte` ci-dessous sont les maquettes de la revue : à l'époque, les points imposés étaient
des minimums (d'où les mentions « minimum » et « min »). Leur code de rendu a été retiré une fois le choix fait.

## Captures

| Fichier | Contenu |
|---|---|
| `a1-puces-vide.png` | A : la carte sans aucun point imposé. |
| `a2-puces-4-lignes.png` | A : 4 lignes imposées en puces (Esquive 57, Coup critique 20, Maîtrise critique 37, Point d'action 1), puis les points laissés à la recherche, par branche. |
| `a2b-puces-edition.png` | A : une puce ouverte pour l'éditer (−/+, « Max », plafond de la ligne et reste de la branche). |
| `a3-selecteur-ouvert.png` | A : le sélecteur « Ajouter une aptitude » : un onglet par branche avec son reste, et par ligne son plafond, « +1 » et « Tout ici ». |
| `a4-selecteur-branche-pleine.png` | A : le sélecteur sur une branche pleine (Agilité 57/57) : un bandeau d'explication, les autres lignes sont inactives. |
| `b1-resultat-sans-verrou.png` | B : l'arbre d'aptitudes du résultat, avec un cadenas ouvert sur chaque ligne dépensée et le bouton « Imposer cette répartition ». |
| `b2-resultat-3-verrous.png` | B : 3 lignes verrouillées (cadenas fermé, valeur en orange, compteur en tête de carte). |
| `b3-resultat-tout-verrouille.png` | B : tout est verrouillé ; le bouton devient « Retirer tous les verrous ». |
| `ab-contexte.png` | A+B en contexte : panneau de requête (carte « Mode de recherche » réelle, puis la carte A avec les 3 puces verrouillées) à côté du panneau de stats (arbre B). |
| `c1-resume.png` | C : la carte de requête résume les points imposés, une ligne par branche, avec le bouton « Répartir les points… ». |
| `c2-fenetre-ouverte.png` | C : la fenêtre dédiée en 5 colonnes, à la manière de la fenêtre d'aptitudes du jeu (reste et jauge par branche, plafond, −/+ et « max » par ligne). |

## Défauts relevés sur la carte actuelle, et ce que chaque variante en fait

| Défaut | A | B | C |
|---|---|---|---|
| Les 30 lignes de l'arbre sont dupliquées | résolu | résolu (l'arbre existant sert d'éditeur) | résolu dans la carte, mais l'arbre est recopié dans la fenêtre |
| ~1 100 px de haut | 1 à 3 rangées de puces | 0 px de plus dans la carte (un bouton sous l'arbre) | ~120 px |
| Un champ vide et 2 boutons par ligne | seulement dans la puce ouverte | aucun : un cadenas par ligne | 2 boutons + « max » par ligne, mais dans une fenêtre |
| Plafond de ligne absent | dans le sélecteur et l'éditeur de puce | déjà dans l'arbre (« 0/20 ») | « /20 » par ligne |
| « 57/57 » et « 115 » ambigus | « Points laissés à la recherche : Agilité 0 · … » ; onglets « reste N » | compteur « N imposée(s) » | « reste N » par colonne, « 57 sur 57 » dans le résumé |
| Libellés tronqués | sur une ligne entière dans le sélecteur | sur une ligne entière dans l'arbre | sur 2 lignes dans les colonnes |
| Lignes sans icône décalées | une tuile vide marquée du point de la branche aligne tout (A, B et C) | idem | idem |
| « + » désactivé peu lisible | l'action disparaît (« — ») ou un bandeau explique la branche pleine | sans objet | estompé, avec le reste « 0 » affiché en tête de colonne |
| Pas de « tout mettre ici » | « Tout ici · N » | « Imposer cette répartition » | « max » par ligne |

## Variantes

**A. Puces.**
- Avantages : même logique que les objets forcés (puce + « Ajouter ») ; la carte reste petite et ne montre que ce que le joueur a choisi ; le sélecteur rend visibles le plafond et le reste de chaque branche.
- Limites : pour imposer toute une répartition, il faut ouvrir le sélecteur plusieurs fois. L'éditeur de puce ajoute une interaction (cliquer sur la puce).
- Coût de câblage : environ 1 jour.
  - Remplacer le corps de `SkillPointsCard` par les puces.
  - Créer un `Modal.SkillPicker` dans `ModalHost` (même gabarit que les sélecteurs d'objets et de sublimations).
  - Ajouter l'éditeur de puce en place.
  - Environ 8 clés `Tr`.
  - L'état `ImposedSkillPoints` est inchangé.

**B. Épingler depuis le résultat.**
- Avantages : correspond au parcours réel (on lance une recherche, on regarde l'arbre, on garde ce qui convient, on relance) ; aucune surface nouvelle ; réutilise l'arbre déjà affiché, avec ses plafonds et ses icônes.
- Limites : il faut un résultat (on ne peut rien imposer avant la première recherche). Avec la règle MINIMUM, une ligne verrouillée peut encore grossir ; c'est neutre quand la branche est pleine, mais à expliquer.
- Coût de câblage : environ 0,5 jour en plus de A.
  - `toggleSkillLock(line)`, qui impose la valeur du résultat ou la retire.
  - `imposeResultAllocation()`.
  - L'état des cadenas se lit dans `ui.imposedSkillPoints`.
  - Le badge « min N » actuel disparaît, remplacé par le cadenas.

**C. Fenêtre dédiée.**
- Avantages : ressemble à la fenêtre du jeu ; tout est visible d'un coup, avec plafonds, restes et « max » ; adaptée pour composer une répartition complète avant toute recherche.
- Limites : elle est large (1 080 dp) et ne tient pas dans une fenêtre étroite sans adaptation ; elle recopie l'arbre dans une fenêtre ; elle ajoute un clic d'ouverture et un de validation pour chaque retouche.
- Coût de câblage : environ 1 jour : une modale large avec une mise en page de repli sous ~1 100 dp, plus la carte résumé. Mêmes actions que l'implémentation actuelle.

**D. Recommandation : A + B.**
- B est la voie principale : verrouiller ce que le résultat fait déjà bien, puis relancer.
- A sert à imposer avant une première recherche et à retoucher une valeur (sélecteur avec « Tout ici », éditeur de puce).
- Les deux écrivent dans le même `ImposedSkillPoints` : une ligne verrouillée dans l'arbre devient une puce, et retirer la puce rouvre le cadenas (`ab-contexte.png`).
- C n'apporte pas assez pour justifier une fenêtre de plus : A + B couvrent les mêmes besoins sans dupliquer l'arbre.
- Coût total : environ 1,5 jour, plus une passe sur le texte d'aide si la question « minimum / valeur exacte » posée sur l'issue est tranchée en faveur de la valeur exacte. Le verrou de B est alors plus naturel, et seule la règle change (`SkillPointsRule`).
