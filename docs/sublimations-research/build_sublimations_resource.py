#!/usr/bin/env python3
"""
Build the engine resource autobuilder/src/main/resources/sublimations-v<VERSION>.json
(a List<Sublimation>, see common-lib/.../Sublimation.kt) from:
  - data/subs-game.json        (Ankama metadata: stateId, names, rarity, slotColorPattern)
  - data/wakforge-state_data.json + en_states.json  (structured per-level effects)
  - data/{epic,relic,sublimations}.json (noredlace, for maxLevel + cross-check)
  - manual_curation.json       (the 10 newest subs, hand/agent-curated)  [optional]

Deduplicated by stateId (467 item rows -> 232 distinct sublimations).
Best-achievable model: a sub contributes its MAX-level value (the player can level it).
"""
import json
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).parent
DATA = ROOT / "data"
# Resource files have fixed (version-free) names; the data version lives once in common-lib WakfuData.VERSION.
OUT = ROOT.parent.parent / "autobuilder" / "src" / "main" / "resources" / "sublimations.json"


def load(p):
    return json.loads(Path(p).read_text())


# ---- effect text -> Characteristic enum name (project's common-lib Characteristic) ----
# Order matters: most specific first. Used to map an effect phrase to a modelable stat.
EFFECT_CHAR = [
    (r"damage inflicted|\bdamage\b", "DAMAGE_INFLICTED"),
    (r"\bforce of will\b", "WILLPOWER"),
    (r"critical resistance", "RESISTANCE_CRITICAL"),
    (r"rear resistance|resistance from behind", "RESISTANCE_BACK"),
    (r"rear mastery", "MASTERY_BACK"),
    (r"melee mastery", "MASTERY_MELEE"),
    (r"distance mastery|ranged mastery", "MASTERY_DISTANCE"),
    (r"berserk mastery", "MASTERY_BERSERK"),
    (r"healing mastery|heals performed", "MASTERY_HEALING"),
    (r"critical mastery", "MASTERY_CRITICAL"),
    (r"elemental mastery|masteries", "MASTERY_ELEMENTARY"),
    (r"elemental resistance|resistances", "RESISTANCE_ELEMENTARY"),
    (r"critical hit", "CRITICAL_HIT"),
    (r"\block\b", "LOCK"),
    (r"\bdodge\b", "DODGE"),
    (r"\bcontrol\b", "CONTROL"),
    (r"\binitiative\b", "INITIATIVE"),
    (r"\bwisdom\b", "WISDOM"),
    (r"\bblock\b", "BLOCK_PERCENTAGE"),
    (r"health points|\bhp\b", "HP"),
    (r"max wp|max ?wp|wakfu points? max", "MAX_WAKFU_POINTS"),
    (r"\bwp\b|wakfu points?", "WAKFU_POINT"),
    (r"max ?ap", "MAX_ACTION_POINT"),
    (r"max ?mp", "MAX_MOVEMENT_POINT"),
    (r"\bap\b|action points?", "ACTION_POINT"),
    (r"\bmp\b|movement points?", "MOVEMENT_POINT"),
    (r"\brange\b", "RANGE"),
]

# Phrases that make a line NOT statically modelable by the solver -> forces the whole sub to
# forced-only (recorded but not solver-choosable). Positional / temporal / unmodeled mechanics.
NON_MODELABLE_PHRASES = [
    "cell", "straight line", "in line", "even turn", "odd turn", "aligned", "ally", "allies",
    "first turn", "non-regenerable", "armor", "steal", "indirect", "received", "preparation",
    "flaming", "light weapon", "barrier", "to area", "per turn", "this turn", "rounded", "weapon:",
]

# static start-of-combat condition templates -> condition spec
COND_PATTERNS = [
    (r"has (\d+) ap or less", lambda m: {"type": "AP_AT_MOST", "value": int(m.group(1))}),
    (r"has (\d+) ap or (more|greater)", lambda m: {"type": "AP_AT_LEAST", "value": int(m.group(1))}),
    (r"odd number of ap", lambda m: {"type": "AP_ODD"}),
    (r"has (\d+)%? critical hit or less", lambda m: {"type": "CRIT_AT_MOST", "value": int(m.group(1))}),
    (r"has (\d+)%? critical hit or (more|greater)", lambda m: {"type": "CRIT_AT_LEAST", "value": int(m.group(1))}),
    (r"has at least (\d+)%? critical hit", lambda m: {"type": "CRIT_AT_LEAST", "value": int(m.group(1))}),
    (r"has (\d+)%? block or (greater|more)", lambda m: {"type": "BLOCK_AT_LEAST", "value": int(m.group(1))}),
    (r"has exactly (\d+) range", lambda m: {"type": "RANGE_EXACT", "value": int(m.group(1))}),
    (r"has (\d+) range or (more|greater)", lambda m: {"type": "RANGE_AT_LEAST", "value": int(m.group(1))}),
    (r"has (\d+) range or less", lambda m: {"type": "RANGE_AT_MOST", "value": int(m.group(1))}),
    (r"less than (\d+)% of their level as dodge", lambda m: {"type": "DODGE_LT_PCT_OF_LEVEL", "value": int(m.group(1))}),
    (r"secondary masteries are <= (\d+)", lambda m: {"type": "SECONDARY_MASTERIES_AT_MOST", "value": int(m.group(1))}),
    (r"has a (dagger) equipped", lambda m: {"type": "WEAPON_TYPE_EQUIPPED", "text": "dagger"}),
    (r"has a (shield) equipped", lambda m: {"type": "WEAPON_TYPE_EQUIPPED", "text": "shield"}),
    (r"highest elemental mastery is greater than (the )?rear mastery", lambda m: {"type": "HIGHEST_ELEM_MASTERY_GT_REAR"}),
    (r"highest elemental mastery is greater than (the )?healing mastery", lambda m: {"type": "HIGHEST_ELEM_MASTERY_GT_HEALING"}),
]

COMBAT_MARKERS = ["start of turn", "end of turn", "at start of fight", "per turn", "each time", "each turn",
                  "when ", "after suffering", "every ", "spells cast", "is reapplied", "this turn", "next spell",
                  "next turn", "previous turn", "round", "for each condition", "during their turn", "once per",
                  "suffers", "cast:", "inflicts damage", "inflicted melee", "uses a spell", "causes an enemy",
                  "two-handed weapon", "gains", "loses"]
STATIC_MARKERS = ["at the start of combat", "at the start of the first turn"]


def scenario_gate(text):
    """Modelable scenario gate from an effect phrase, or None. Returns (gate, modelable_bool)."""
    t = text.lower()
    g = {}
    if "from behind" in t or "rear damage" in t:
        g["orientation"] = "BACK"
    elif "from the front" in t or "frontal" in t:
        g["orientation"] = "FRONT"
    elif "from the side" in t:
        g["orientation"] = "SIDE"
    if re.search(r"\bin melee\b|close combat|in close", t):
        g["rangeBand"] = "MELEE"
    if "berserk" in t:
        g["berserk"] = True
    if "ranged" in t or "at a distance" in t:
        g["ranged"] = True
    if "area" in t:
        g["area"] = True
    m = re.search(r"level (\d+) or greater|character is at level (\d+)", t)
    if m:
        g["minCharacterLevel"] = int(m.group(1) or m.group(2))
    return g or None


def map_char(text):
    low = text.lower()
    for pat, ch in EFFECT_CHAR:
        if re.search(pat, low):
            return ch
    return None


def parse_effect_line(resolved):
    """Parse '-20% damage inflicted' / '1 MP' / '15 Force of Will' -> (effect_dict, ok).
    ok=False means the line is not a cleanly modelable stat line."""
    low = resolved.lower().strip()
    if any(p in low for p in NON_MODELABLE_PHRASES):
        return None, False
    m = re.match(r"^(-?\d+)\s*%?\s*(.*)$", resolved.strip())
    if not m:
        return None, False
    value = int(m.group(1))
    phrase = m.group(2)
    ch = map_char(phrase)
    if ch is None:
        return None, False
    eff = {"characteristic": ch, "value": value}
    g = scenario_gate(phrase)
    if g is not None:
        eff["scenarioGate"] = g
    return eff, True


def build_entry_from_wakforge(sid, rows, wfe, en, maxlevel):
    r0 = rows[0]
    rarity = "EPIC" if r0["isEpic"] else "RELIC" if r0["isRelic"] else "NORMAL"
    name = {"fr": r0["fr"], "en": r0["en"], "es": r0.get("es", r0["en"]), "pt": r0.get("pt", r0["en"])}

    cond = None
    effects = []
    raw_lines = []
    has_combat = False
    has_static = False
    conversion = None
    all_lines_clean = True  # every line is a recognized condition/effect/conversion/benign header

    BENIGN = ["at the start of combat, for the state bearer", "at the start of combat:",
              "the value is always rounded down", "at start of fight:"]

    for ln in wfe.get("descriptionData", []):
        tmpl = en.get(ln.get("text"), ln.get("text") or "") or ""
        def val_at_max(key):
            d = ln.get(key, {})
            for lvl in range(6, 0, -1):
                if f"level_{lvl}" in d:
                    return d[f"level_{lvl}"]
            return None
        nums = {k: val_at_max(k) for k in ln if k.startswith("num_")}
        resolved = re.sub(r"\{(num_\d+)\}", lambda m: str(nums.get(m.group(1), "?")), tmpl).strip()
        if not resolved:
            continue
        raw_lines.append(resolved)
        low = resolved.lower()
        if any(m in low for m in STATIC_MARKERS):
            has_static = True
        if any(m in low for m in COMBAT_MARKERS):
            has_combat = True

        line_recognized = False
        # conversion line?
        cm = re.search(r"converts (\d+)% of (.+?) (?:to|into) (.+?)(?:\s|$|\.|at the)", low)
        if cm:
            frm, to = map_char(cm.group(2)), map_char(cm.group(3))
            if frm and to:
                conversion = {"from": frm, "to": to, "percent": int(cm.group(1))}
                line_recognized = True
        # condition line?
        if not line_recognized:
            for pat, fn in COND_PATTERNS:
                m = re.search(pat, low)
                if m:
                    if cond is None:
                        cond = fn(m)
                    line_recognized = True
                    break
        # effect line?
        if not line_recognized and ln.get("indented") and nums.get("num_0") is not None:
            eff, ok = parse_effect_line(resolved)
            if ok:
                effects.append(eff)
                line_recognized = True
        # benign header?
        if not line_recognized and any(b in low for b in BENIGN):
            line_recognized = True
        if not line_recognized:
            all_lines_clean = False

    # bucket
    if conversion is not None:
        kind = "CONVERSION"
    elif cond is not None and not has_combat:
        kind = "STATIC_CONDITIONAL"
    elif has_combat:
        kind = "COMBAT_CONDITIONAL"
    elif cond is not None:
        kind = "STATIC_CONDITIONAL"
    else:
        kind = "FLAT"

    # Conservative: choosable only if the bucket is static/flat/conversion, every line parsed cleanly,
    # and there is at least one modelable effect (or a conversion).
    solver_choosable = (
        kind in ("FLAT", "STATIC_CONDITIONAL", "CONVERSION")
        and all_lines_clean
        and (len(effects) > 0 or conversion is not None)
    )

    entry = {
        "stateId": sid,
        "zenithId": int(r0["id"]),  # the sublimation's item id = Zenith /shard/add id_shard
        "name": name,
        "rarity": rarity,
        "slotColorPattern": r0["slotColorPattern"],
        "maxLevel": maxlevel,
        "kind": kind,
        "solverChoosable": solver_choosable,
        "rawText": " | ".join(raw_lines),
    }
    if cond is not None:
        entry["condition"] = cond
    if effects:
        entry["effects"] = effects
    if conversion is not None:
        entry["conversion"] = conversion
    return entry


def main():
    subs = load(DATA / "subs-game.json")
    states = load(DATA / "states-full.json")
    wf = load(DATA / "wakforge-state_data.json")
    en = load(DATA / "wakforge-en_states.json")
    nored = load(DATA / "epic-sublimations.json") + load(DATA / "relic-sublimations.json") + load(DATA / "sublimations.json")
    manual = {}
    mpath = ROOT / "manual_curation.json"
    if mpath.exists():
        for e in load(mpath):
            manual[int(e["stateId"])] = e

    wf_by_id = {}
    for e in wf:
        try:
            wf_by_id[int(e["id"])] = e
        except Exception:
            pass

    # maxLevel from noredlace by normalized name
    def norm(s):
        import unicodedata
        s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
        return re.sub(r"[^a-z0-9]+", " ", s).strip()
    maxlevel_by_name = {}
    for row in nored:
        ml = row.get("MaxLevel", "")
        m = re.search(r"\d+", ml or "")
        if m:
            maxlevel_by_name[norm(row["Name"])] = int(m.group())

    by_state = defaultdict(list)
    for s in subs:
        by_state[int(s["stateId"])].append(s)

    out = []
    stats = defaultdict(int)
    for sid, rows in sorted(by_state.items()):
        r0 = rows[0]
        is_normal = not (r0["isEpic"] or r0["isRelic"])
        # maxLevel
        if is_normal:
            ml = maxlevel_by_name.get(norm(r0["en"]), maxlevel_by_name.get(norm(state_title_en(states, sid)), 6))
        else:
            ml = 1

        if sid in manual:
            entry = manual_to_entry(manual[sid], rows, ml)
            stats["manual"] += 1
        elif sid in wf_by_id:
            entry = build_entry_from_wakforge(sid, rows, wf_by_id[sid], en, ml)
            stats["wakforge"] += 1
        else:
            # unmatched and not manually curated: record metadata only, forced-only, inert
            entry = {
                "stateId": sid,
                "zenithId": int(r0["id"]),  # the sublimation's item id = Zenith /shard/add id_shard
                "name": {"fr": r0["fr"], "en": r0["en"], "es": r0.get("es", r0["en"]), "pt": r0.get("pt", r0["en"])},
                "rarity": "EPIC" if r0["isEpic"] else "RELIC" if r0["isRelic"] else "NORMAL",
                "slotColorPattern": r0["slotColorPattern"],
                "maxLevel": ml,
                "kind": "COMBAT_CONDITIONAL",
                "solverChoosable": False,
                "rawText": "(effect not curated)",
            }
            stats["uncurated"] += 1
        out.append(entry)
        stats[f'kind:{entry["kind"]}'] += 1
        stats[f'rarity:{entry["rarity"]}'] += 1

    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1))
    print(f"wrote {len(out)} sublimations -> {OUT.relative_to(ROOT.parent.parent)}")
    for k in sorted(stats):
        print(f"  {k}: {stats[k]}")
    choosable = sum(1 for e in out if e["solverChoosable"])
    dmg = sum(1 for e in out if e["solverChoosable"] and any(
        x.get("characteristic") == "DAMAGE_INFLICTED" for x in e.get("effects", [])))
    print(f"  solverChoosable: {choosable}   (with DAMAGE_INFLICTED effect: {dmg})")


def state_title_en(states, sid):
    for s in states:
        if s["definition"]["id"] == sid:
            return s.get("title", {}).get("en", "")
    return ""


def manual_to_entry(m, rows, ml):
    r0 = rows[0]
    rarity = m.get("rarity") or ("EPIC" if r0["isEpic"] else "RELIC" if r0["isRelic"] else "NORMAL")
    entry = {
        "stateId": int(m["stateId"]),
        "zenithId": int(r0["id"]),  # the sublimation's item id = Zenith /shard/add id_shard
        "name": {"fr": r0["fr"], "en": r0["en"], "es": r0.get("es", r0["en"]), "pt": r0.get("pt", r0["en"])},
        "rarity": rarity,
        "slotColorPattern": r0["slotColorPattern"],
        "maxLevel": ml,
        "kind": m.get("kind", "COMBAT_CONDITIONAL"),
        "solverChoosable": m.get("kind") in ("FLAT", "STATIC_CONDITIONAL", "CONVERSION"),
        "rawText": m.get("effect_text_en", ""),
    }
    if m.get("condition"):
        entry["condition"] = m["condition"]
    if m.get("effects"):
        allowed = {"characteristic", "value", "scenarioGate"}
        entry["effects"] = [{k: v for k, v in e.items() if k in allowed and v is not None} for e in m["effects"]]
    if m.get("conversion"):
        entry["conversion"] = m["conversion"]
    return entry


if __name__ == "__main__":
    main()
