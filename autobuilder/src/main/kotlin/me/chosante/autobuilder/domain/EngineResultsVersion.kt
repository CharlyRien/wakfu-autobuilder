package me.chosante.autobuilder.domain

/**
 * The version of what a search RETURNS: bumped on any engine change that can alter which build a search finds or the score a
 * build gets — the solver, the scorers, the item pre-filter, the rune or sublimation modelling, the certificates. A
 * `CERTIFIER_VERSION` bump (`WakfuBuildSolver.kt`) implies a bump here; a pure speed-up that returns the same builds does not.
 *
 * Every saved build records it ([me.chosante.common.history.HistoryEntry.engineResultsVersion]); a build saved under a lower
 * one (or before the field existed) is flagged as possibly improvable by a re-run in "My Builds". It lives here, a plain
 * constant beside the domain types, rather than in `WakfuBuildSolver` — whose initialisation loads the native OR-Tools
 * library — so the GUI can read it at any time for free. See AGENTS.md §4.
 *
 * 2: the item EQUIP conditions (`item-criteria.json`) — REQUIRES / FORBIDS / class-only / never, the exclusivity groups, and
 * the item STAT GATES (an item inactive on the build's out-of-combat sheet is never worn). Still 2: no release shipped it.
 * 3: CERTIFIER_VERSION 58 — the domination pre-filter ignores the stat gates that can never fail and the gates of the items it
 * evicts. Same optima, a smaller pool: a time-limited search of 1.15 / 1.16 (slowed by the gates) may now find a better build,
 * and among equally good builds the one returned can differ.
 * 4: CERTIFIER_VERSION 59 — spell AP costs/ranges now come from the client; 0-AP damage spells remain excluded
 * from the AP-only rotations and certificate throughput tables. Saved searches need a re-run.
 * 5: CERTIFIER_VERSION 60 — sublimation families reach their level cap with partial shards and shared conditional gates.
 * 6: CERTIFIER_VERSION 61 — shard tiers never overshoot the cap; derived conditional debits retain an optimistic zero.
 * Still 6 (61 never shipped; both land in 1.18.0): CERTIFIER_VERSION 62 — max-damage runes can MIX on an item: a carrier offering a rune type a threshold reads (a
 * secondary cap with a budget, a required row or floor, the survivability floor, an item stat gate) keeps per-type counts
 * instead of one type per item, and an item stat gate's stat no longer lets the rune choice collapse prune the choice the
 * gated item needs. The search itself runs on the fast single-type fold where it is inexact, then re-solves the count
 * model on the fold's items (and verifies a proven fold optimum on the full count model): a build is proven optimal only by
 * the count model. Every count model the search builds groups interchangeable carriers into exact count classes (one count
 * per rune type per class; the certificates keep per-carrier counts). The fold the search runs prunes its rune choices without
 * the item stat gates, as v61 did (the count model decides every carrier that changes), and keeps 2 s of the budget for the
 * polish instead of 6. On a large fold (the GUI-default rows from level ~170) its interchangeable single-type carriers share
 * exact pick classes (one var per rune type per class), so the search reaches a build meeting the rows much sooner. A re-run
 * may find a better build.
 * 7: the fold's pick classes are built on EVERY fold (no size rule) and posted literally — exactly-ones, two ring-slot layers
 * for rings — instead of one linear equation per class, so CP-SAT's feasibility pump keeps finding solutions (it found none on
 * the linear form). Same solutions, a different search trajectory: a time-limited search may return another build.
 * Still 7 (unreleased): the hard leg of a small fold (≤ 18,000 pick-class vars: the GUI-default rows up to about level 120)
 * searches "LNS first" — no greedy CP-SAT hint, one full CP-SAT subsolver — so its search no longer settles in the greedy build's
 * basin (level 110: 1,431,545 instead of 1,447,330). Search-only: the same model, optimum and proofs.
 * 8: CERTIFIER_VERSION 63 — a conversion lands before a start-of-turn condition is tested, so Dénouement × Neutralité / Ambition /
 * Prétention is legal and a build carrying it can score higher.
 */
const val ENGINE_RESULTS_VERSION: Int = 8
