# TopBar Redesign — Design Handoff

> **What this is.** A self-contained design brief for rethinking the Compose-Desktop **TopBar**
> (`gui-compose/.../shell/TopBar.kt`). It is written to be fed straight into **claude.ai/design**
> to produce mockups, and to give the **implementing agent** everything it needs (real tokens,
> the content inventory, the constraints) to build the result for real.
>
> **The design language is already established** — `docs/design-reference/` is the visual source of
> truth (`styles-clean.css`, `Wakfu Autobuilder.html`). This redesign must stay inside that language;
> it changes the TopBar's **layout / responsiveness**, not the app's look.

---

## 1. TL;DR

The TopBar packs ~12 controls into **one fixed-width 64 dp row**. On anything narrower than a wide
maximised window the row overflows and Compose crushes the **last** child — the primary **Search**
button — so its label wraps/clips to `Se ar` (EN) or vanishes entirely (FR `Rechercher`). It is the
most important control and it is the one that breaks. The whole bar needs to **degrade gracefully as
width shrinks** instead of silently mangling the primary action.

This is not a one-line fix — it is an information-architecture problem (too much in one rigid row).
Hence this handoff.

---

## 2. Evidence

Two real captures, both while **editing** a build (the `Editing …` chip is present):

| Lang | Symptom |
|---|---|
| **FR** | The orange Search button is squeezed to a sliver — label not legible at all. Meters read `Progression 100%` / `Maîtrise`. |
| **EN** | The Search button wraps to two clipped lines: **`Se` / `ar`**. |

The bar looks wide in the screenshots, but the **content's natural width exceeds the window**, so the
trailing element is starved regardless of how wide the capture looks.

---

## 3. Why it happens (root cause)

**a) One rigid row, measured left-to-right, no overflow handling.**
`TopBar.kt:92` is a single `Row` (height 64 dp, `padding(horizontal = 22.dp)`). A `Spacer(weight(1f))`
(`TopBar.kt:110`) splits it into a left cluster and a right cluster. Every control is a fixed
intrinsic width. When the children's combined width exceeds the row, Compose measures the
weighted spacer to 0 and then **starves the children measured last** — the right cluster, ending in
the Search button — giving them ~0 max-width. The button falls back to its `widthIn(min = 86.dp)`
(`TopBar.kt:568`); with `padding(horizontal = 18.dp)` that leaves **~50 dp** for the label, and the
`Text` has no `maxLines`/`softWrap=false`, so `Search` (~56 dp) **wraps**.

**b) The width budget doesn't fit.** Approximate natural widths (EN, build being edited):

```
left   : pad22 + brand78 + 18 + navTabs160 + 10 + New70 + 12 + EditingChip271         ≈  641 dp
right  : Lang80 + 14 + Class179 + 10 + Lvl106 + 10 + Min106 + 22 + Meter112 + 16
         + Meter112 + 18 + Search92 + pad22                                            ≈  911 dp
                                                                              TOTAL    ≈ 1552 dp
```

Default window is **1440 dp** (`Main.kt:63`), and **no `window.minimumSize` is set** (`Main.kt`), so it
can be dragged narrower still. Deficit ≈ **110–180 dp** → overflow → Search crushed.
Drop the `Editing …` chip (no active build) and the bar fits — which is exactly why the bug shows
up *while editing* and looks fine otherwise.

**c) The i18n multiplier.** FR labels are 1.5–2.8× longer than EN, but several widths are hard-coded:

| `Tr` | EN | FR | note |
|---|---|---|---|
| `MATCH` | Match (5) | **Correspondance (14)** | meter is `width(112.dp)` fixed (`TopBar.kt:460`) → FR overflows the meter |
| `PROGRESS` | Progress (8) | Progression (11) | same fixed meter |
| `SEARCH` | Search (6) | **Rechercher (10)** | button min-width assumes the short EN word |
| `STOP` | Stop (4) | Arrêter (7) | |
| `MASTERY_SHORT` | Mastery (7) | Maîtrise (8) | |
| `CLASS` | Class | Classe | |

A design that's pixel-tuned for English silently breaks in French. **Treat FR as the worst case.**

**d) The `Editing <name>` chip is the single biggest swing** — up to ~271 dp (`TopBar.kt:217`,
name capped at `widthIn(max = 180.dp)`). It's also arguably the lowest-priority *always-on* element.

---

## 4. Current TopBar anatomy

Single row, left→right (`TopBar.kt:93-150`). "Builder-only" items disappear on the My-Builds/Compare
screens.

| # | Element | Composable | ~Width | Shown when | State variants |
|---|---|---|---|---|---|
| 1 | Brand wordmark | `Brand` | ~78 dp | always | — |
| 2 | Builder / My-Builds tabs | `NavTabs` | ~160 dp | always | selected tab |
| 3 | ＋ New | `NewBuildButton` | ~70 dp | Builder | — |
| 4 | `Editing <name> ✕` chip | `ActiveBuildChip` | ≤271 dp | Builder + active build | name truncates + tooltip |
| — | **flex spacer** | — | grows | always | — |
| 5 | EN / FR toggle | `LangToggle` | ~80 dp | always | selected lang |
| 6 | Class ⌄ | `ClassDropdown` | ~179 dp | Builder | breed icon + name |
| 7 | Lvl field | `NumberControl` | ~106 dp | Builder | — |
| 8 | Min field | `NumberControl` | ~106 dp | Builder | **red border** if `min > level` |
| 9 | Progress meter | `TopMeter` | 112 dp | Builder | **pulsing dot** while searching |
| 10 | Match **or** Mastery meter | `TopMeter` | 112 dp | Builder | precision → `Match %` (bar); most-masteries → `Mastery total` (no bar) |
| 11 | Search / Stop button | `SearchButton` | ~92 dp | Builder | idle=accent `Search`; **locked**=padlock glyph + `Search`; searching=raised `Stop` |

Behaviours that must survive any redesign:
- **Search ⇄ Stop** toggle (same button) while the engine runs (`TopBar.kt:144-148`).
- **Locked** state: a drawn padlock (`LockGlyph`, `TopBar.kt:524`) when re-searching a saved build.
- **Min > Level** error styling on the Min field (`TopBar.kt:120-125`).
- **Pulsing "alive" dot** on the Progress meter while searching (`TopBar.kt:464`).
- **Mode-dependent** second meter (precision % vs cumulated mastery) (`TopBar.kt:137-142`).
- Class dropdown with breed icons; numeric fields clamp to 3 digits and snap back to the model value.

---

## 5. Hard constraints

- **Tech:** Compose Multiplatform **Desktop**, built programmatically in Kotlin (no FXML/XML). Mockups
  are recreated as Compose, not copied structurally. JDK 25.
- **i18n:** hand-written `Tr` enum, **EN + FR**, FR strings noticeably longer (see §3c). No generated
  i18n. Any label can be ~2× wider than its English form — **design for the longest string**.
- **Window:** default 1440×900, fitted to screen then maximised once warm (`Main.kt`). **No minimum
  size today** — assume the user can make it ~1100–1280 dp wide (a sensible new `minimumSize` is a fair
  part of the proposal, but the bar should still behave at ≥ ~1180 dp).
- **Keep every function reachable** and keep the state variants in §4. Don't drop features to win space —
  relocate or collapse them.
- **Stay in the design-reference language** (colours, radii, control styling). Note the reference's
  `.topbar` is *also* a single fixed flex row (`styles-clean.css:81`, meters `min-width: 260px`) — it
  predates these controls and **does not solve overflow**, so the redesign must go beyond it.

---

## 6. Design tokens (use these exact values so mockups match the app)

**Colour** (`theme/WColor.kt`):

| token | hex | use |
|---|---|---|
| `bg` | `#17191E` | app background / topbar |
| `surface` | `#1E2128` | panels, dropdown menus |
| `raised` | `#262A32` | controls (buttons, fields, meter track) |
| `border` | `#2C313A` | control borders |
| `hairline` | `#24282F` | 1 dp separators |
| `text` | `#E7E5E0` | primary text |
| `muted` | `#969BA5` | labels |
| `faint` | `#5F656F` | tertiary |
| `accent` | `#D98A45` | **primary action / Search** (orange) |
| `accentPress` | `#C2783A` | accent border / pressed |
| `accent2` | `#45B8A6` | Progress bar (teal) |
| `success` | `#5BA86A` | Match / Mastery (green) |
| `danger` | `#D9655C` | error (Min>Level) |

**Type** (`theme/WType.kt`): UI font **Manrope**, mono **IBM Plex Mono** (used for numbers: levels,
%, mastery totals). Scale in use on the bar: `labelSmall` 10sp · `labelMedium` 12sp · `titleMedium`
13sp semibold · `labelLarge` 13sp bold (display) — the Search label.

**Spacing** (`theme/WDimens.kt`): `radius = 12 dp`, `gap = 16 dp`, `pad = 20 dp`. Controls use 9–10 dp
corner radii and 34/38 dp heights; row is 64 dp tall with 22 dp side padding.

---

## 7. Content priority (the key design input)

When width runs out, collapse from the bottom up. **Search is the anchor and never shrinks below a
fully legible label.**

- **P0 — never compromise:** Search/Stop button (full label, both states), Builder/My-Builds nav, brand.
- **P1 — keep, may shorten:** Class (icon-only acceptable when tight), Level.
- **P2 — may collapse / move:** Min field, Progress meter, Match/Mastery meter (compact numeric-only
  form acceptable — drop the bar before the number).
- **P3 — may move out of the bar entirely / hide behind affordance:** `Editing <name>` chip
  (biggest cost), EN/FR toggle (a settings-y, rarely-touched control).

---

## 8. Goals / non-goals / success criteria

**Goals**
- No control label ever wraps, clips, or truncates at any supported width — **Search above all**.
- Graceful degradation: as the window narrows, the bar reflows/collapses predictably instead of
  starving the trailing control.
- Works identically in **FR** (longest strings) and EN.
- Visually consistent with the existing app + design-reference.

**Non-goals**
- Restyling the app's colours/typography (tokens are fixed).
- Removing any feature or state.
- Solving the *body* layout — this is the header only.

**Success criteria (testable)**
- At 1180, 1280, 1440 dp window width, in **FR and EN**, while **editing a build**, in **searching**
  and **idle** states: every control is fully legible; Search shows its whole label on one line.
- The screenshot smoke test (`WAKFU_COMPOSE_SCREENSHOT=…`) renders the new bar cleanly.

---

## 9. Directions to explore (mock these up)

Three viable shapes. **Recommend mocking B and C** and comparing; A is the safe fallback.

**A — Responsive single row (priority-collapse + overflow "⋯").**
Keep one row. Pin P0 (brand, nav, Search). As width shrinks: meters → numeric-only (drop bars) →
hidden into a compact readout; Class → icon-only; Min and the Editing chip → an overflow `⋯` menu.
*Pro:* minimal vertical change. *Con:* fiddly to tune across i18n; overflow menus on desktop are
mediocre UX for frequently-used controls.

**B — Two-row / wrap-to-strip below a threshold** *(lowest-risk, recommended first)*.
Above a width threshold, today's single row. Below it, split into two rows:
row 1 = brand · nav · ＋New · Editing chip; row 2 = a full-width **search-control strip**
(Class · Lvl · Min · Progress · Match/Mastery · **Search**), each control with room to breathe.
Mirrors the pattern just shipped on the build cards (adaptive `BoxWithConstraints`). *Pro:*
guaranteed legibility, simple mental model. *Con:* +~44 dp header height when narrow.

**C — Relocate the search controls into the Builder's left column** *(cleanest IA, bigger change)*.
The Builder body already has a left **Request** column holding all the *other* search inputs (mode,
target stats, constraints, duration). Move **Class · Lvl · Min · meters · Search** to the top of that
column (a "search header"), leaving the TopBar purely global: brand · nav · ＋New · Editing · EN/FR.
The topbar stops overflowing because it carries far less. *Pro:* search inputs live together; topbar
becomes trivially responsive. *Con:* larger refactor (`AppShell.kt` + `RequestPanel`), and the
meters/Search leave the global chrome — validate that feels right.

> A pragmatic shipped answer may be **B now** (fast, robust) **+ C later** (the real IA fix).
> Whatever the shape: give **Search a fixed, non-shrinkable footprint sized to the FR label**, and add
> `maxLines = 1, softWrap = false` to every control label as a backstop.

---

## 10. Files & where to implement

| File | Role |
|---|---|
| `gui-compose/.../shell/TopBar.kt` | **primary** — the whole bar + every sub-control listed in §4 |
| `gui-compose/.../shell/AppShell.kt` | only if Direction C (moving controls into `BuilderBody`/`RequestPanel`) |
| `gui-compose/.../request/RequestPanel.kt` | Direction C target for the search-control strip |
| `gui-compose/.../theme/{WColor,WType,WDimens}.kt` | tokens — read-only reference |
| `gui-compose/.../i18n/I18n.kt` | only if new tooltip/aria strings are added (EN+FR both) |
| `gui-compose/.../Main.kt` | if proposing a `window.minimumSize` |

Build/iterate: `./gradlew :gui-compose:run` (cold start hidden behind the loading screen). Screenshot
smoke check: `WAKFU_COMPOSE_SCREENSHOT=/tmp/topbar.png ./gradlew :gui-compose:run`. Run
`./gradlew ktlintFormat` before finishing (CI style is strict).

---

## 11. Acceptance checklist

- [ ] FR + EN, editing a build, at 1180 / 1280 / 1440 dp: nothing wraps or truncates; **Search** label
      fully visible on one line.
- [ ] Search ⇄ Stop toggle, **locked** padlock state, and searching **pulse** all intact.
- [ ] Min>Level red error styling intact; numeric fields still clamp/snap.
- [ ] Both meter modes (precision % with bar, most-masteries numeric) render correctly and legibly.
- [ ] Builder-only controls still hide on My-Builds / Compare screens.
- [ ] Matches the design-reference visual language; `ktlintCheck` passes; smoke screenshot clean.

---

## 12. Optional immediate stopgap (independent of the redesign)

If a hotfix is wanted before the full redesign, in `SearchButton` (`TopBar.kt:554`):
add `maxLines = 1, softWrap = false` to the label `Text`, and stop the button shrinking
(`width(IntrinsicSize)` / a fixed FR-sized min) so it can't be starved.
**Caveat:** this only stops *Search* from wrapping — the overflow pressure just moves to whatever
control is now measured last (e.g. a meter). It buys legibility on the primary action; it does **not**
fix the overcrowded-row root cause. The redesign above is the real fix.
