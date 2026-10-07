# Settings screen

The gear beside the language toggle opens a full shell screen. The three sections and translated explanations fit a scrollable column without covering a working search. Back returns to the prior screen (builder, library or comparison). The shell navigation also stays available.

Computing has Maximum (all logical cores), Balanced (ceil(cores/2)), Low (min(2,cores)) and Custom (an integer slider from 1 through the hardware count; disabled on one-core hosts). `LibraryPreferences` persists the preset and custom count. The request captures a `ComputeBudget`; the post-search proof captures the then-current setting. Changing settings never changes an operation already running. If the allowance changed during the search, in-flight search warm-ups are cancelled before the post-search proof starts with its new allowance; finished bounds remain reusable.

The proof switch keeps its previous preference key and explanation. Maximizing request modes replace it with a clickable on/off reminder. Interface reuses the top-bar language control and shared picker preference. About reuses `WhatsNew.appVersion`, `WakfuData.VERSION` and the model’s existing browser helper. Reset uses the existing confirmation dialog and restores Maximum, proof on and hide-chosen off; it keeps language, library ordering/grouping, tags and saved builds. New build also preserves these durable app preferences.

## Added translations

Existing `VERIFY_OPTIMALITY`, `VERIFY_OPTIMALITY_SUB`, `BACK` and `CANCEL` are reused.

| Key | EN | FR | ES | PT |
| --- | --- | --- | --- | --- |
| SETTINGS | Settings | Réglages | Ajustes | Configurações |
| SETTINGS_COMPUTING | Computing | Calcul | Cálculo | Cálculo |
| SETTINGS_PROCESSOR_USE | Processor use | Utilisation du processeur | Uso del procesador | Uso do processador |
| SETTINGS_MAXIMUM | Maximum | Maximum | Máximo | Máximo |
| SETTINGS_BALANCED | Balanced | Équilibré | Equilibrado | Equilibrado |
| SETTINGS_LOW | Low | Réduit | Bajo | Baixo |
| SETTINGS_CUSTOM | Custom | Personnalisé | Personalizado | Personalizado |
| SETTINGS_MACHINE_CORES | Your computer has %d logical cores. | Ton ordinateur dispose de %d cœurs logiques. | Tu ordenador tiene %d núcleos lógicos. | O teu computador tem %d núcleos lógicos. |
| SETTINGS_PRESET_CORES | Maximum: %d cores · Balanced: %d · Low: %d | Maximum : %d cœurs · Équilibré : %d · Réduit : %d | Máximo: %d núcleos · Equilibrado: %d · Bajo: %d | Máximo: %d núcleos · Equilibrado: %d · Baixo: %d |
| SETTINGS_USED_CORES | Cores used: %d | Cœurs utilisés : %d | Núcleos utilizados: %d | Núcleos utilizados: %d |
| SETTINGS_CPU_TRADEOFF | Fewer cores mean less noise and heat. A search of the same duration may find a weaker build, and optimality proofs take longer. | Moins de cœurs, c’est moins de bruit et de chaleur. À durée égale, la recherche peut trouver un build moins performant et les preuves d’optimalité prennent plus de temps. | Menos núcleos significa menos ruido y calor. Una búsqueda de la misma duración puede encontrar un build menos potente y las pruebas de optimalidad tardan más. | Menos núcleos significa menos ruído e calor. Uma busca com a mesma duração pode encontrar uma build menos potente e as provas de optimalidade demoram mais. |
| SETTINGS_CPU_EFFECT | Applies to the next search and the next optimality proof. A running operation keeps its core count. | Ce choix s’applique à la prochaine recherche et à la prochaine preuve d’optimalité. Une opération en cours garde son nombre de cœurs. | Se aplica a la próxima búsqueda y a la próxima prueba de optimalidad. Una operación en curso mantiene su número de núcleos. | Aplica-se à próxima busca e à próxima prova de optimalidade. Uma operação em curso mantém o seu número de núcleos. |
| SETTINGS_PROOF_ON | Optimality proof: on · Settings | Preuve d’optimalité : activée · Réglages | Prueba de optimalidad: activada · Ajustes | Prova de optimalidade: ativada · Configurações |
| SETTINGS_PROOF_OFF | Optimality proof: off · Settings | Preuve d’optimalité : désactivée · Réglages | Prueba de optimalidad: desactivada · Ajustes | Prova de optimalidade: desativada · Configurações |
| SETTINGS_INTERFACE | Interface | Interface | Interfaz | Interface |
| SETTINGS_LANGUAGE | Language | Langue | Idioma | Idioma |
| SETTINGS_ABOUT | About | À propos | Acerca de | Sobre |
| SETTINGS_APP_VERSION | App version | Version de l’application | Versión de la aplicación | Versão da aplicação |
| SETTINGS_DATA_VERSION | Game data version | Version des données du jeu | Versión de los datos del juego | Versão dos dados do jogo |
| SETTINGS_REPORT_BUG | Report a bug | Signaler un bug | Informar de un error | Comunicar um erro |
| SETTINGS_RESET | Reset settings | Réinitialiser les réglages | Restablecer los ajustes | Repor as configurações |
| SETTINGS_RESET_HINT | Restore Maximum processor use, optimality proof on and hide chosen entries off? Your language, library sorting and grouping, and saved builds are kept. | Revenir au processeur en mode Maximum, à la preuve d’optimalité activée et aux entrées choisies visibles ? Tu gardes ta langue, le tri et le regroupement de ta bibliothèque, ainsi que tes builds sauvegardés. | ¿Volver al uso Máximo del procesador, activar la prueba de optimalidad y mostrar las entradas elegidas? Se conservan tu idioma, el orden y la agrupación de la biblioteca y los builds guardados. | Repor o uso Máximo do processador, ativar a prova de optimalidade e mostrar as entradas escolhidas? O teu idioma, a ordenação e o agrupamento da biblioteca e as builds guardadas são mantidos. |
| SETTINGS_BROWSER_FAILED | Could not open your browser. | Impossible d’ouvrir ton navigateur. | No se pudo abrir tu navegador. | Não foi possível abrir o teu navegador. |
| SETTINGS_HIDE_CHOSEN | Hide chosen entries in pickers | Masquer les entrées choisies dans les sélecteurs | Ocultar las entradas elegidas en los selectores | Ocultar as entradas escolhidas nos seletores |

## Validation

On 2026-10-07, after `git fetch origin` and `git rebase --autostash origin/main`, the shared `gradle-locked.sh --heavy` runner completed `ktlintFormat`, the affected engine/proof tests and the full GUI suite successfully:

| Suite | Tests | Skipped | Failures / errors |
| --- | ---: | ---: | ---: |
| Engine / certificates / proof orchestration | 331 | 61 | 0 / 0 |
| Full `gui-compose:test` | 490 | 2 | 0 / 0 |

Engine selection: `ComputeBudgetTest`, `CliThreadsTest`, `CliTargetOptionsTest`, `LongLongMaxMapTest`, `MostMasteriesBoundCacheTest`, `MostMasteriesCertificateTest`, `MaxDamageSoftCertificateTest`, `MaxDamageSearchTest`, `WakfuBuildSolverTest`, `EngineResultsVersionTest`, `E8ConstructGateTest`, `MostMasteriesBadgeOverlapTest`, `MaxDamageFlagshipCostHarnessTest`, `MaxDamageTargetAwareHarnessTest`, `RequirementSplitTimingHarnessTest` and `PerfBaselineE0Test`. The standard test task excludes slow-tagged full-pool tests; opt-in/manual harnesses kept their existing guards.

The nine new GUI/model tests cover preset mapping on 1/2/3/5/10/16-core hosts, persisted and corrupt preferences, launch restoration, immutable search/proof budgets, reset preservation, all four languages, slider endpoints, the single-core disabled control, synchronized language/picker controls and navigation. The four existing request-panel proof tests now check the reminder and Settings navigation.

`WAKFU_SETTINGS_SCREENSHOTS=/private/tmp/wakfu-settings-panel-preview` captured the actual AppShell in EN/FR/ES/PT. All four PNGs were visually inspected; every section is visible and the translated explanations fit without clipping or ellipses. The reset dialog uses the existing confirmation UI.

A final call-site audit also routed the soft proof's lazy oracle warm-up through `params.computeBudget`. Its worker formula was already covered for every allowed budget; the final engine and GUI main/test sources were formatted and compiled after that one-line follow-up. Maximum still passes the same two-worker warm-up count on this ten-core machine.
