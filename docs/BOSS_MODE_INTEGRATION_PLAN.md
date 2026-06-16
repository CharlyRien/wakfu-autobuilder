# Boss-mode integration plan (onto `main` / #153)

Bring `feat/boss-mode`'s value onto the post-#153 `main` line (spell-aware fused max-damage objective +
external loop + cast-limits + passives), so the engine can return **the best build _and_ the spells
played, against a chosen boss**, with the boss's per-element resistances driving the existing
boss-aware element choice. This is **Lot 0** of `docs/FULL_DAMAGE_PLAN.md`.

Branch: `feat/boss-mode-integration` (worktree, off `main` `93a9318b`).

---

## Mechanism — port, NOT rebase (decided)

`main` is the squash-merge **#153**; `feat/boss-mode` still carries the *original un-squashed* max-damage
commits (`e8f3ca8c`, `c716bc39`) that #153 redid differently. A `git rebase` (even `--onto main c716bc39`)
would replay/conflict on exactly the files #153 rewrote. So we **port the boss-specific pieces** onto a
fresh branch off `main` and hand-adapt the few files #153 also touched.

---

## What `main` already provides (no work needed)

- Fused, boss-aware max-damage objective (`WakfuBuildSolver.perTurnDamageScore`) with `max` over
  `DamageScenario.candidateElements()` — **the element choice is already in the engine**.
- External AP-probe loop (`MaxDamageSearch`) + debuff-aware sequenced rotation.
- `DamageScenario.elementResistances` + `candidateElements()` — the seam boss data plugs into.
- `--boss-resistances` manual CLI override already parsed in `Main.kt`.
- Data: spells, **cast-limits**, **passives** (`bdata-extractor` → `spell-cast-limits-v*`,
  `spell-passives-v*`). So `BossSearch`'s per-element orchestration is **subsumed** — drop it.

---

## Status

### Done (compiles: `:common-lib:compileKotlin :monsters-extractor:compileKotlin` green)
Ported as clean additive files (no conflict — absent from `main`):
- `common-lib/.../Monster.kt`
- `autobuilder/.../domain/BossScenario.kt` (+ `BossScenarioTest.kt`)
- `autobuilder/src/main/resources/monsters-v1.91.1.54.json` (715 monsters, 226 bosses)
- `monsters-extractor/` module + `include("monsters-extractor")` in `settings.gradle.kts`
- `docs/BOSS_MODE_RESEARCH.md`

### CLI path (`autobuilder`) — ✅ DONE (compiles, `:autobuilder:test` + ktlint + e2e all green)
- `BossScenario.kt`: added `DamageScenario.againstAllElements(monster)` (fills `elementResistances` for all
  4 → the fused objective auto-picks the element) + kept single-element `against`. `flatResistanceToPercent`
  now **delegates to `common-lib Resistances.flatToPercent`** (single source of truth, so the displayed boss
  resistance matches the one the rotation is scored at, incl. weaknesses).
- `BossDisplay` (in `BossScenario.kt`): `effectiveHp` + `turnsToKill` migrated from the dropped `BossSearch`.
  **Semantics:** per-turn rotation damage → "turns to kill" (not per-hit "hits to kill").
- `WakfuBestBuildFinderAlgorithm.kt`: `monsters` lazy-loader (mirrors equipments/runes) + `findMonster(query)`
  (exact-then-substring French/English match).
- `Main.kt`: `--boss <name>` (auto-switches to max-damage) / `--boss-element <auto|fire|…>` (eagerly
  validated) / `--boss-difficulty <x>` (`> 0`). `--boss` ⇒ `againstAllElements` (auto) or `against` (forced
  element, clears manual resistances); precedence `--boss` > `--boss-resistances` > `--enemy-resistance`.
  `bossSummary` report: HP, the 4-element profile (▶ chosen), per-turn damage + turns-to-kill.
- **Dropped** `BossSearch` (`run`/`chooseElement`/`expectedDamage` subsumed by `MaxDamageSearch` /
  `bestAcrossElements`). `BossScenarioTest` updated (`turnsToKill`, `againstAllElements` coverage).

Verified e2e: Cra vs *Riktus Elite Dominant* auto-picks an element jointly with the kit; forced `--boss-element`
pins a single element and degrades gracefully ("no playable damage rotation") when the class lacks it; a
typo'd `--boss-element` is rejected up front. Adversarial review (2 agents): no blocking bug.

### Remaining — GUI path (`gui-compose`) — *the bulk; next*
- Re-apply the boss picker (searchable dropdown + resistance-profile card + difficulty stepper +
  turns-to-kill) against `main`'s current `RequestPanel` / `AppShell` / `BuildSearchModel` / `UiState` /
  `StatsPanel`, with EN/FR strings in `i18n/I18n.kt`.
- **Boss icons:** add `gfx: Int? = null` to `Monster` + parse `entry["gfx"]` in `MethodWakfuBestiary`
  (nullable ⇒ the current json still deserializes); re-bake `monsters-v*.json`; add one
  `copyAssetDir("monsters")` line to `gui-compose:generateAssets` (key by `gfx`, source
  `Vertylo/wakassets/monsters/<gfx>.png`); render in the picker via `rememberClasspathBitmap`. Ankama
  `static.ankama.com/wakfu/portal/game/monster/115/<gfx>.png` is the fallback. (`bossIllustrations/` is
  keyed by a dungeon-boss id, not `gfx` — not used.)

---

## Decisions (resolved)
1. **Port** onto `feat/boss-mode-integration`, not rebase.
2. **Drop** `BossSearch.run/chooseElement/expectedDamage`; keep only difficulty-aware turns-to-kill display.
3. Boss icons via `monsters/<gfx>.png` (square 200×200), captured from the dropped `gfx` API field.

## Verification
- `:common-lib:test :autobuilder:test` (engine tests use `SolverTuning` det-time/seed/workers).
- Manual CLI smoke: `--class <c> --boss "<nom fr>" --computation-mode max-damage` → build + chosen element + turns-to-kill.
- `ktlintFormat` before finishing.
