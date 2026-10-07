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
 */
const val ENGINE_RESULTS_VERSION: Int = 3
