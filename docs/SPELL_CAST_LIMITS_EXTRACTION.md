# Spell Cast-Limit Extraction — Handoff

## 1. TL;DR / recommended path

**Use `jac3km4/wakfu-bdata` (Rust crate `wakfudecrypt`) directly.** It is the single best path: it already ships a working descrambler *and* a generated `Spell` decoder bound to `TYPE_ID = 66` (the confirmed spell table on this install), exposing every field we need (`cast_max_per_turn`, `cast_max_per_turn_incr`, `cast_max_per_target`, `cast_min_interval`, `breed_id`, `element`, `pa_base`, `range_*`). The descrambler math was **independently replayed against this machine's real `66.bin` header bytes and produced a coherent entry count (~4079 spell records)**, confirming the format layer matches the current `1.91.1.54` install. It was last pushed `2026-04-30`, contemporaneous with the install.

Exact steps:

```sh
git clone https://github.com/jac3km4/wakfu-bdata
cd wakfu-bdata
# Edit src/bin/main.rs:
#   add:  use wakfudecrypt::types::spell::Spell;
#   add a line in main():  dump_doc::<Spell>(path);
#   (main.rs already dumps Item/ItemSet/Pet/StaticEffect; Spell is implemented but NOT wired in)
cargo run --bin main -- /Applications/Ankama/Wakfu
# -> writes Spell.json (array of Spell records) to the cwd.
# Document::<Spell>::load() resolves contents/bdata/66.jar itself, unzips 66.bin, descrambles.
```

Then post-process `Spell.json` → key by `id`, filter `breed_id` to the 18 class breed ids, emit the output contract in §6.

**Two things to verify before baking the JSON (do not skip):**
1. `cast_max_per_turn` / `cast_max_per_turn_incr` are read as **raw little-endian `f32`** and the `f32` `Decode` impl does **not** subtract the descrambler seed (only integer reads do). If those values are actually scrambled int-like quantities, the field *offset* is correct but the decoded *number* could be wrong. **Spot-check against in-game tooltips / a known spell before trusting (see §5).**
2. The `Spell` struct layout is **positional** (no field tags in the binary). It is machine-generated from a specific client and is not provably pinned to `1.91.1.54`. The cast-limit fields sit early (6th–9th decoded), so they are low shift-risk, but still sanity-check that `cast_max_per_turn` values are small positive numbers for known spells.

**Backup if that crate misbehaves:** `Ninhache/Wakfudecrypt` is a byte-identical fork of the same parser (same 82 positional `decode()` calls) but is library-only — you'd add a `src/bin/dump.rs` driver and `#[derive(Serialize)]` yourself. Prefer the parent repo, which already has the `serde_json` dump pattern. If **both** Rust crates produce garbage cast-limit values, fall back to the RE plan in §4, using `hussein-aitlahcen/wakfu-src`'s `SpellBinaryData.java` as the authoritative field-order spec.

**Do not** clone `jac3km4/wakfu-bdata-gen` expecting to dump data — it only *regenerates* decoders from `lib/wakfu-client.jar` bytecode and emits no JSON. Its only use here is the recovery path: if the layout has drifted, re-run it against this install's client jar to regenerate `spell.rs`.

---

## 2. Where the data is

**Install root (macOS):** `/Applications/Ankama/Wakfu`
- Client version `6.0_1.91.1.4963.295`, gamedata version `1.91.1.54`.

**`config.properties`:**
```
contentStaticDataStorageDirectory=./contents/bdata/
binaryDataFile=jar:file:./contents/bdata/%s.jar!/%s.bin
```
So each static-data table `<id>` lives at `contents/bdata/<id>.jar`, and **inside** that jar is a single `<id>.bin` (proprietary scrambled binary). The `jar:file:…!/…bin` indirection means: open the `.jar` as a zip, read the one `.bin` entry, then descramble.

**`contents/bdata/` contains 120 numbered `.jar` files.** Table **66 = the SPELL table**:
- `contents/bdata/66.jar` → `66.bin` (~958 KB).
- Confirmed: it contains spell cast-condition scripts (`GetCharacteristic("ap","caster")>4`, `HasState(...)`, `IsEnnemy()`) and `breed_id`s. All verified candidate parsers bind the spell decoder to `TYPE_ID = 66`.
- Other tables exist via the same pattern (e.g. `elements.lib`, `buildings.lib`, and per the references: file `64` = `Skill` / spell-level table, file `86` = `AvatarBreed`).

**`66.bin` header (hex, little-endian):**
```
dd7a f4ff 810f 0000 7f16 0000 0000 0000 0e00 0000 6400 0000 90b8 1500 ...
```
This is an **offset/index table header**, not spell records yet. Interpretation, corroborated by decoding it two independent ways:

- The container format is the Ankama `SimpleBinaryStorage` index: a header giving a record count, then an index of `{ long id, int offset, int size, byte unk }` entries, then the record bodies.
- Decoding with the **2017 WakBox logic**: raw size int `-754979`, `+756423` (the de-obfuscation magic) `= 1444`; **rows = 3969**; row 0 = `{ id=5759, offset=14, size=100, byte unk }` — a clean, self-consistent index entry.
- Decoding with the **jac3km4/Rust descrambler** (`DecodeState` seed/mul/add, LE, `version = getInt + 756423`): **entry_count ≈ 4079** — also coherent. (The two counts differ because the readers seed/skip the index slightly differently; both land in the ~4000-spell range, which is sane.)

**Key takeaways from the header:**
- The de-obfuscation magic constant `+756423` is current (matches on the real file).
- Index entries are little-endian for the record buffer; note the historical Ankama gotcha that the *index* stream is big-endian `DataInputStream` while the *record* `ByteBuffer` is little-endian — relevant only if you hand-write a reader (§4).
- The spell **records** are positional, untagged structs. Field order is the entire risk surface.

---

## 3. The candidate repos (ranked)

| Repo | Lang | Parses bin? | Cast limits? | Spell fields (target ones) | How to run | Stale risk | Verdict |
|---|---|---|---|---|---|---|---|
| **`jac3km4/wakfu-bdata`** (`wakfudecrypt`) | Rust | ✅ | ✅ | `cast_max_per_turn` (f32), `cast_max_per_turn_incr` (f32), `cast_max_per_target`, `cast_min_interval`, `breed_id`, `element`, `pa_base`, `range_*` | clone → add `use …::Spell;` + `dump_doc::<Spell>(path);` to `src/bin/main.rs` → `cargo run --bin main -- /Applications/Ankama/Wakfu` → `Spell.json` | Moderate (positional layout, machine-gen; cast fields early = lower risk). Header math replayed → ~4079 entries, descrambler current. | **use-directly** (0.88) |
| **`jac3km4/wakfu-bdata-gen`** (+ companion above) | Rust | ✅ *(via companion)* | ✅ | same set, snake_case | gen tool only **regenerates decoders** from `lib/wakfu-client.jar`; to dump, use the companion `wakfu-bdata`. Run gen only if layout drifted: `cargo run --release -- /Applications/Ankama/Wakfu ./generated_bdata` | Low-moderate; same-week push, `TYPE_ID=66` matches. Fix path: re-run gen against this client. | **use-directly** as a *system* (0.90); gen alone emits no JSON |
| **`Ninhache/Wakfudecrypt`** | Rust | ✅ | ✅ | byte-identical to jac3km4 (`cast_max_per_turn` f32, `cast_min_interval` i16, …) | library-only: add `src/bin/dump.rs` calling `Document::<…spell::Spell>::load(...)`, add `#[derive(Serialize)]` + serde_json. Trait is at crate root `wakfudecrypt::BinaryData`, not `::data::BinaryData`. | Moderate; identical parser to actively-maintained jac3km4 → format correct *now*. | **adapt** (0.90) — prefer parent |
| **`Duperopope/wakfu-optimizer`** | Python (parser) | ✅ | ✅ | `castMaxPerTurn`, `castMaxPerTurnIncr`, `castMaxPerTarget`, `castMinInterval`, full field set | `archive/scripts_legacy/bdata_reader_final.py` (deprecated, not in live app). Edit hardcoded Windows paths → `python3`. Also `parse_breeds.py` on `86.jar` → breed→spell map. | Moderate-high; header decode → 4079 rows ✓ but committed output has **garbage rows** (null/denorm-float/absurd breedId). Offsets need revalidation. | **adapt** (0.83) — definitive Python reference to port+fix |
| **`hussein-aitlahcen/wakfu-src`** | Java | ✅ (source-of-truth) | ✅ | `m_castMaxPerTarget` (short), `m_castMaxPerTurn` (float), `m_castMaxPerTurnIncr` (float), `m_castMinInterval` (short), `m_breedId` | **No runnable tool** — decompiled client. Read `…/binaryStorage/SpellBinaryData.java` for authoritative field order; `ReadOnlyIndexEntry.java` + `AbstractSimpleBinaryStorage.java` for index format. Port it. | High on byte-exactness (~2015 client) but **field SET stable**. Validate offsets. | **reference-only** (0.90) — canonical format spec |
| **`WakBox/WakfuBDataReader`** | C++/Qt (+PHP gen) | ✅ | ✅ | `m_castMaxPerTurn` (float), `m_castMaxPerTurnIncr`, `m_castMaxPerTarget` (qint16), `m_castMinInterval` (qint16) | Qt GUI, no export/CLI. Reads **raw `.bin`** (unzip the jar first). Header decoded clean on local file. Add JSON export in `UpdateTreeData()`. PHP `translator.php` regenerates `Spell.h` from decompiled client. | Moderate; container format verified intact on local `66.bin`. 9-yr-old positional layout is the risk. Has a built-in `m_size+offset==position` self-check guard to add. | **adapt** (0.86) — best non-Rust prior art |
| **`re.wakfu/wakfudecrypt`** (GitLab, Scala) | Scala | ✅ | ✅ | `castMaxPerTarget` (Short), `castMaxPerTurn` (Float), `castMaxPerTurnIncr`, `castMinInterval` (Short), `breedId` | `BinaryDocument.readAll[Spell](path)`; bound to `dataId=66`. Toolchain dead (bintray resolver), path hardcoded `game/contents/bdata` (symlink `game -> .`). | **HIGH** — pinned to game `1.58.5` (2018). Positional layout almost certainly drifted. | **adapt** (0.88), high RE cost — the lineage ancestor of the Rust crates |
| **`Duperopope/Wakfuassistant`** | Python + Java | ✅ | ❌ | only `spellId → gfxId` (+ guessed breed); column-index parse incomplete (`pass`) | `tools/extract_spells.py` (edit `BDATA_PATH`). Yields icon mapping only. Hand-curated `sram-spells.json` cooldowns are **manual transcription, not from binary**. | Moderate-high; author flags unsolved column-index + sub-map. | **reference-only** (0.85) — cleanest `ScrambleReader` scaffold to copy, but no cast limits |

**Prior-art corroboration (field existence):** `kokokobana/wakfuthesaurus` (Scala) models `Spell` with `maxCastPerTurn: RelValue` and `maxCastPerTarget: Byte` — independent proof the fields exist in the binary. Note the type discrepancy: the live binary reads `cast_max_per_turn` as `f32` and `cast_max_per_target` as `i16`, vs `RelValue`/`Byte` in the Scala model. Same underlying fields, different widths — **a reason to sanity-check decoded magnitudes** (§5).

---

## 4. If no repo works: the RE plan

If both Rust crates emit garbage (e.g. layout drifted in `1.91.1.54`), decode `66.bin` by hand. Use `hussein-aitlahcen/wakfu-src`'s `SpellBinaryData.read()` as the authoritative field order and validate every offset against real bytes. **Never invent a value; mark every unconfirmed field `null`.**

**Step 0 — extract the raw bin.** `unzip /Applications/Ankama/Wakfu/contents/bdata/66.jar` → `66.bin`. (Some readers want the raw `.bin`, not the jar.)

**Step 1 — parse the index/header.** Reproduce the container layout: read the de-obfuscated record count and the index of `{ long id, int offset, int size, byte unk }` entries. Cross-validate: WakBox logic on this file gives 3969 rows / row0 `{id=5759, offset=14, size=100}`; the Rust descrambler gives ~4079. Confirm you land in that range. Remember the **endianness split**: index stream big-endian, record `ByteBuffer` little-endian (set `ByteOrder.LITTLE_ENDIAN` on the record buffer).

**Step 2 — anchor on `spellId`.** Each record begins with its `id` (matching the index `id`). Pick a few spells whose ids you can confirm externally (encyclopedia / Zenith / Gearfu give the spell id). The first decoded int of a record should equal the index id — your first alignment check.

**Step 3 — anchor on `ap_cost` (`pa_base`).** This is the strongest cross-checkable early field. Known oracle values to hunt for:
- **Cra — Blazing Arrow (Flèche Enflammée): ap = 2.**
- Pull more known AP costs per class from Zenith/Gearfu/encyclopedia (each class has spells with distinctive 2/3/4/5/6 AP costs).
Walk the record from the id anchor, decoding ints/shorts/floats per `SpellBinaryData`'s order, until the field that lands on the known AP value is at the expected offset. Once `pa_base` lines up, the early-field order is validated up to that point.

**Step 4 — locate the small-int cast-limit fields.** Per every reference, the order near the front is roughly:
`id, scriptId, gfxId, maxLevel, breedId, castMaxPerTarget, castMaxPerTurn, castMaxPerTurnIncr, castMinInterval, … (then targetFilter/castCriterion/PA_base…)`.
So `castMaxPerTarget` / `castMaxPerTurn` / `castMaxPerTurnIncr` / `castMinInterval` sit **before** `pa_base`. Once you've anchored `breedId` (small class id, 1–20ish) and `pa_base`, the four cast fields are the bytes **between** them:
- `castMaxPerTarget` — small positive int/short (often 1–3, or 0/`unlimited`).
- `castMaxPerTurn` — small positive number; **watch the f32-vs-int ambiguity** — if you read it as `f32` and get a denormalized/absurd value (e.g. `4.6e-41`), it's an int read as float; try decoding as int.
- `castMaxPerTurnIncr` — per-level increment (often 0 or a small float).
- `castMinInterval` (= cooldown, turns between casts) — small short, often 0.

**Step 5 — validate per record with the size guard.** After decoding a full record, assert `start_offset + index_size == buffer.position()` (WakBox's `Taille de donnee incorrecte` check). A mismatch = layout drift at some field; binary-search which field by checking where the running position diverges from the expected size. This turns silent garbage into a hard, locatable error.

**Step 6 — if drift is confirmed,** regenerate the decoder rather than hand-patch: run `jac3km4/wakfu-bdata-gen` (or WakBox's `translator.php`) against this install's client jar (`lib/wakfu-client.jar` / `lib/*.jar`) to emit a fresh, version-correct `Spell` layout, then re-dump.

**Step 7 — note on per-level resolution.** `66.bin` holds spell **definitions** (base + increment), not per-level resolved values. `castMaxPerTurn = base + level*castMaxPerTurnIncr`. For the optimizer's purposes the **base** cast caps are what we want; resolve per-level only if the optimizer later needs it (would require the `Skill`/spell-level table, file `64`). For now: emit base values, carry `maxCastPerTurnIncr` so per-level resolution stays possible.

**Per-class coverage:** `breed_id` on each spell maps it to one of the 18 classes — dumping the whole table covers all classes at once. A small number of generic/shared spells may need filtering. For an authoritative class→player-spell list, join against the `AvatarBreed` table (file `86`, the `ehx` arrays map breedId → real player spell ids) — useful to drop non-player/internal spells.

---

## 5. Cross-check oracle

Validate the binary decode against independent sources before baking — **the decode is only trustworthy once it matches these:**

- **In-game tooltip (ground truth for cast limits):** the Wakfu spell tooltip literally shows **"X casts per turn"** and **"X cast(s) per target"** (and a cooldown if any). Pick 3–5 spells across different classes, read their tooltips in-client, and confirm the decoded `castMaxPerTurn` / `castMaxPerTarget` / `castMinInterval` match exactly. This is the only oracle that confirms the cast-limit numbers themselves.
- **AP cost anchor:** **Cra Blazing Arrow = 2 AP** (and other known AP costs). Confirms `pa_base` offset → validates the field ordering *around* the cast fields.
- **Zenith (`zenithwakfu.com`) / Gearfu / Ankama encyclopedia:** expose spell **element, AP, range, damage** per spell id — use these to confirm `id`, `element`, `pa_base`, `range_*` decode correctly. They do **NOT** expose cast limits (that's the whole reason for this extraction), so they validate *alignment* but not the target values.
- **Magnitude sanity:** `castMaxPerTurn`/`castMaxPerTarget`/`castMinInterval` should all be small non-negative numbers (typically 0–6). Floats like `4.6e-41`, negatives, or `breedId` outside 1–20 mean the offsets/types are wrong — do not bake; revalidate.

---

## 6. Output contract

Emit a single JSON file: an **array of spell objects, one per spell id**. Use `null` for anything not confirmed from the binary (or not yet validated against the oracle) — **never guess, never default to 0 to "look complete."**

```json
[
  {
    "spellId": 5759,
    "name": "Flèche Enflammée",
    "breedId": 1,
    "maxCastPerTurn": 2,
    "maxCastPerTurnIncr": 0,
    "maxCastPerTarget": 1,
    "cooldown": 0
  }
]
```

Field semantics:
- `spellId` (int, required) — the binary `id`, the JSON key for the optimizer.
- `name` (string|null) — French name if resolvable (`equipment`-style French matching is the project convention), else `null`.
- `breedId` (int|null) — class id (1–18ish); use to filter to player classes.
- `maxCastPerTurn` (int|null) — `cast_max_per_turn`. The value `SpellRotationOptimizer.maxCastsPerSpell` consumes. `null` = unconfirmed/unlimited-unknown.
- `maxCastPerTurnIncr` (number|null, optional) — per-level increment; carry it so per-level resolution stays possible.
- `maxCastPerTarget` (int|null) — `cast_max_per_target`.
- `cooldown` (int|null, optional) — `cast_min_interval` (turns between casts). Omit or `null` if 0/absent.

Notes:
- A `castMaxPerTurn` of 0/absent in the binary commonly means **unlimited per turn** — decide and document the convention; do **not** silently coerce to a finite number.
- Key the final baked artifact by `spellId` (object map or array — match what `SpellRotationOptimizer`'s `maxCastsPerSpell` hook expects on the Kotlin side; the optimizer already has the hook, so this JSON only has to feed it).
- This is an **offline, baked** artifact (parallel to the existing baked `equipments-v<version>.json` and class-spells JSON) — the Rust tool runs once to produce it; the Kotlin app does not parse the binary at runtime.

---

## 7. Ready-to-paste PROMPT for the next session

```
GOAL
Produce a JSON file of per-spell cast limits for Wakfu, keyed by spell id, to feed an existing
Kotlin build-optimizer that already has a `maxCastsPerSpell` hook in `SpellRotationOptimizer`.
Target fields per spell: maxCastPerTurn, maxCastPerTurnIncr (optional), maxCastPerTarget,
cooldown (= min interval between casts, optional), plus breedId (class) and name. This is an
OFFLINE, baked artifact — produce the JSON once; the Kotlin app reads the JSON, never the binary.

WHERE THE DATA IS (macOS, already verified)
- Install: /Applications/Ankama/Wakfu  (gamedata 1.91.1.54, client 6.0_1.91.1.4963.295)
- config.properties: binaryDataFile = jar:file:./contents/bdata/%s.jar!/%s.bin
- contents/bdata/66.jar -> 66.bin (~958KB) is the SPELL table (TYPE_ID 66).
- 66.bin is a scrambled binary with an offset/index-table header
  (hex: dd7a f4ff 810f 0000 7f16 0000 ...). Index entries: {long id, int offset, int size, byte}.
  De-obfuscation magic +756423 is current. Decoding the header yields ~3969-4079 spell records.
- Cast limits live in the BASE spell record (no separate spell-level table needed for them).
- breedId on each spell maps it to one of 18 classes; dumping the table covers all classes.

CHOSEN APPROACH (do this first)
Use jac3km4/wakfu-bdata (Rust crate `wakfudecrypt`). It already has a working descrambler and a
generated Spell decoder (TYPE_ID=66) with cast_max_per_turn (f32), cast_max_per_turn_incr (f32),
cast_max_per_target, cast_min_interval, breed_id, element, pa_base, range_*. Steps:
  1. git clone https://github.com/jac3km4/wakfu-bdata
  2. Edit src/bin/main.rs: add `use wakfudecrypt::types::spell::Spell;` and a line
     `dump_doc::<Spell>(path);` (Spell is implemented but NOT wired into the shipped main.rs).
  3. cargo run --bin main -- /Applications/Ankama/Wakfu   -> writes Spell.json to cwd.
  4. Post-process: key by `id`, filter breed_id to the 18 player classes, emit the output contract.

VERIFY BEFORE BAKING (mandatory — the decode is positional & untagged):
  - cast_max_per_turn / _incr are read as RAW little-endian f32 with NO seed subtraction. If you see
    denormalized/absurd floats (e.g. 4.6e-41), they're scrambled ints read as float — try int decode.
  - Spot-check decoded cast limits against IN-GAME TOOLTIPS (the tooltip shows "X casts per turn" /
    "per target") for 3-5 spells across classes. Tooltips are the ONLY oracle for the cast numbers.
  - Cross-check id/element/AP/range against Zenith/Gearfu/encyclopedia (they have these but NOT cast
    limits). Known anchor: Cra "Flèche Enflammée" (Blazing Arrow) = 2 AP.
  - Sanity: cast limits are small non-negative ints (0-6); breedId in 1-20. Anything else = bad offsets.

FALLBACKS (in order)
  - Ninhache/Wakfudecrypt: byte-identical Rust fork, library-only (add a bin + #[derive(Serialize)]).
  - Duperopope/wakfu-optimizer: archive/scripts_legacy/bdata_reader_final.py (Python; edit hardcoded
    Windows paths; committed output has garbage rows — revalidate offsets).
  - Hand RE: use hussein-aitlahcen/wakfu-src `SpellBinaryData.java` as the authoritative field order.
    Anchor on spellId, then pa_base (Blazing Arrow=2 AP), then the small-int cast fields that sit
    BETWEEN breedId and pa_base. Assert (record_start + index_size == buffer.position()) per record
    to catch layout drift as a hard error. Endianness: index stream big-endian, record buffer LE.
  - If layout drifted: regenerate the decoder with jac3km4/wakfu-bdata-gen (or WakBox translator.php)
    against this install's lib/wakfu-client.jar, then re-dump.

OUTPUT CONTRACT
Array (or id-keyed map) of:
  { "spellId": int, "name": string|null, "breedId": int|null,
    "maxCastPerTurn": int|null, "maxCastPerTurnIncr": number|null,
    "maxCastPerTarget": int|null, "cooldown": int|null }
Rules: NEVER invent a value. Use null for anything unconfirmed or not yet oracle-validated. Do NOT
coerce missing/0 to a finite cap — a 0/absent maxCastPerTurn likely means "unlimited"; document the
convention rather than guessing. Match the key shape SpellRotationOptimizer.maxCastsPerSpell expects.

DELIVERABLE
The baked JSON file (offline artifact, parallel to the existing equipments-v<version>.json), plus a
short note on which path produced it and which spells you oracle-validated.
```

---

## 8. Extraction results (run 2026-06-15, Wakfu data `1.91.1.54`)

**Produced:** `autobuilder/src/main/resources/spell-cast-limits-v1.91.1.54.json`
(715 spells, ~126 KB, JSON array sorted by `breedId` then `spellId`). This is the baked, offline
artifact — parallel to `equipments-v1.91.1.54.json` / `spells-v1.91.1.54.json`. **Not** wired into
the Kotlin engine; the `SpellRotationOptimizer.maxCastsPerSpell` integration is a separate step.

> **Canonical reproduction is now in-repo and pure-JVM:** `./gradlew :bdata-extractor:run`. That Kotlin
> module (clean-room decoder, `bdata-extractor/`) decodes the same binaries and **regenerates this exact
> artifact** — it self-verifies by diffing its output against the committed JSON (semantically identical,
> 715 entries) and by a per-record size guard. The Rust path below was the original bootstrap that first
> produced the data; it is no longer needed for regeneration.

### Path used — the recommended one (§1), no fallback needed
`jac3km4/wakfu-bdata` (Rust crate `wakfudecrypt`). The Rust toolchain was not installed; it was
added via `rustup` (minimal profile) into `~/.cargo` / `~/.rustup` (non-destructive, user-local).

Reproduce:
```sh
git clone --depth 1 https://github.com/jac3km4/wakfu-bdata && cd wakfu-bdata
# src/bin/main.rs — replace the four shipped dump_doc::<…> lines with:
#   use wakfudecrypt::types::spell::Spell;
#   dump_doc::<Spell>(path);
cargo run --release --bin main -- /Applications/Ankama/Wakfu   # -> Spell.json (4079 records, exit 0)
```
The decode ran clean (no panic) over all 4079 records — the positional `String` fields
(`cast_criterion`, `learn_criteria`) parse as valid UTF-8, which a layout drift would have broken.
Then post-process: filter `breed_id` to the 18 player-class ids, key by `id`, French names joined
from `spells-v1.91.1.54.json`, emit the §6 contract.

### Field mapping (binary → output)
`cast_max_per_turn` (f32) → `maxCastPerTurn`; `cast_max_per_turn_incr` (f32) → `maxCastPerTurnIncr`;
`cast_max_per_target` (i16) → `maxCastPerTarget`; `cast_min_interval` (i16) → `cooldown`;
`breed_id` (i16) → `breedId`; `id` → `spellId`. `name` = French name from the scraped catalog.

### Convention (decided & applied)
Values are the **raw decoded integers, verbatim** (per-turn floats are all exact integers; `…Incr`
is `0` for every spell in this data version). **`0` means "no limit" / "no cooldown" (unlimited)** —
*not* a finite cap of zero. This was confirmed empirically: where the wiki tooltip says a limit is
"not specified" (e.g. Xelor *Hand* / Sram *Lethal Attack* per-target), the binary reads `0`.
We preserve `0` (rather than coercing to `null`) so "confirmed unlimited" stays distinct from
"unknown"; every spell in the file has a real binary record, so there are **no `null` cast fields**.
A future consumer that wants "no cap" = `null` maps `0 → null` trivially. `name` is `null` only for
the few binary spells with no matching encyclopedia entry (sub-spells).

### breed_id → class (derived from the id-join against the encyclopedia, authoritative)
`1` Feca · `2` Osamodas · `3` Enutrof · `4` Sram · `5` Xelor · `6` Ecaflip · `7` Eniripsa ·
`8` Iop · `9` Cra · `10` Sadida · `11` Sacrier · `12` Pandawa · `13` Rogue (Roublard) ·
`14` Masqueraider (Zobal) · `15` Ouginak · `16` Foggernaut (Steamer) · `18` Eliotrope ·
`19` Huppermage. **Breed `17` is unused; Huppermage is `19`.** Non-player spells carry breed
`0` / `-1` / `-2` and are filtered out.

### Validation
**1 — Field alignment (automated, 708 spells).** Joined the binary dump to `spells-v1.91.1.54.json`
by `id`: **708/710** scraped spells found in the binary (the binary `id` space **==** the encyclopedia
`id` space). Agreement on independently-known fields: **icon `gfx_id` 702/708 (99.2%)**, element maps
cleanly (`1`=Fire `2`=Water `3`=Earth `4`=Air), **`pa_base` == encyclopedia AP wherever AP lives in
the base record** (the mismatches are all spells whose AP is stored outside the base record — they
read `pa_base=0` yet still have a correct icon, i.e. semantic, not a desync). This proves the cast
fields are at the right offsets and correctly typed. Crucially it also resolves the handoff's #1
worry: **`pa_base` is read by the *same* raw-`f32` impl as `cast_max_per_turn`**, and it decodes
exactly (Blazing Arrow `pa_base = 2.0`), so the raw-`f32` read needs no seed subtraction here.

**2 — Magnitude sanity.** All cast fields are small non-negative integers
(`maxCastPerTurn` ∈ {0,1,2,3,4,6}, `maxCastPerTarget` ∈ {0,1,2,3}, `cooldown` ∈ {0,1,2,3,4,5}),
no denormalized floats / negatives / out-of-range breed ids. Distribution is realistic: 374/715
spells carry ≥1 limit, 341 are fully unlimited. Cooldowns land exactly on the iconic cooldown-gated
utility spells (Feca *Immunity*, Sacrier *Sanguine Armor*, Iop *Focus*, Sadida *Force of Nature*…).

**3 — Cast-number oracle (in-game tooltip text, via `wakfu.wiki.gg` which renders it verbatim).**
Spot-checked across 4 classes — every decoded field matches:

| Spell (id) | Class | Decoded turn / target / cooldown / AP | Wiki tooltip |
|---|---|---|---|
| Flèche ardente / Blazing Arrow (4769) | Cra | 3 / 2 / 0 / 2 | "3 uses per turn", "2 uses per target", AP 2, no cd ✓ |
| Aiguille / Hand (754) | Xelor | 2 / 0 / 0 / 4 | "2 uses per turn", AP 4, per-target unspecified (=0) ✓ |
| Attaque létale / Lethal Attack (4583) | Sram | 2 / 0 / 0 / 4 | "2 uses per turn", AP 4, per-target unspecified (=0) ✓ |
| Armure sanguine / Sanguine Armor (5047) | Sacrier | – / – / 3 / – | "3-turn cooldown" ✓ |

(A `wakfu.com` forum thread independently states Blazing Arrow is "2/target", also matching.
Note: a fuzzy web-search *summary* claimed Hand costs 6 AP — that was a model hallucination; the
actual wiki page shows AP 4, matching the decode. Fandom wiki blocks automated fetches, 403.)

### Coverage gaps (documented, not errors)
4 Sram spells in the scraped catalog have no entry here, all safe (a missing lookup → no cap):
- *Double* (4603) and *Invisibility* (4604) — present in the binary but under the special
  non-player breed `-1`, so excluded by the "18 player classes" filter (both are non-damage Sram
  mechanics the damage-rotation optimizer would not cast anyway).
- *Crazy Scheme* (5089) and *Bloody Blade* (5123) — **absent from the binary** entirely (removed or
  renamed since the encyclopedia page was scraped); no cast-limit data exists to extract.
