#!/usr/bin/env python3
"""
DRAFT auto-seed of the curated effect table for EPIC + RELIC subs, proving the §4 pipeline:
  game stateId -> WakForge structured templates+values -> schema entry {kind, condition, effects}.

Output: ../sublimation-effects.DRAFT.json  (DRAFT — values/buckets need human verification before commit).
This is a research prototype, not the committed Lot-3 resource.
"""
import json
import re
from collections import defaultdict
from pathlib import Path

DATA = Path(__file__).parent / "data"
OUT = Path(__file__).parent.parent / "sublimations-research" / "sublimation-effects.DRAFT.json"

# effect-text -> engine Characteristic (extend in Lot 3)
EFFECT_CHAR = [
    (r"damage inflicted", "DAMAGE_INFLICTED"),
    (r"\bforce of will\b", "FORCE_OF_WILL"),
    (r"critical resistance", "RESISTANCE_CRITICAL"),
    (r"rear mastery", "MASTERY_BACK"),
    (r"elemental mastery", "MASTERY_ELEMENTARY"),
    (r"critical mastery", "MASTERY_CRITICAL"),
    (r"\bcritical hit\b", "CRITICAL_HIT"),
    (r"\block\b", "LOCK"),
    (r"\bdodge\b", "DODGE"),
    (r"\brange\b", "RANGE"),
]

# static start-of-combat condition templates -> condition spec (the bucket-b vocabulary)
COND_PATTERNS = [
    (r"has (\d+) ap or less", lambda m: {"type": "AP_AT_MOST", "value": int(m.group(1))}),
    (r"has (\d+)%? critical hit or less", lambda m: {"type": "CRIT_AT_MOST", "value": int(m.group(1))}),
    (r"has (\d+)%? block or greater", lambda m: {"type": "BLOCK_AT_LEAST", "value": int(m.group(1))}),
    (r"has (\d+) range or greater", lambda m: {"type": "RANGE_AT_LEAST", "value": int(m.group(1))}),
    (r"has (\d+) range or less", lambda m: {"type": "RANGE_AT_MOST", "value": int(m.group(1))}),
    (r"has exactly (\d+) range", lambda m: {"type": "RANGE_EXACT", "value": int(m.group(1))}),
    (r"odd number of ap", lambda m: {"type": "AP_ODD"}),
    (r"less than (\d+)% of their level as dodge", lambda m: {"type": "DODGE_LT_PCT_OF_LEVEL", "value": int(m.group(1))}),
    (r"secondary masteries are <= (\d+)", lambda m: {"type": "SECONDARY_MASTERIES_AT_MOST", "value": int(m.group(1))}),
    (r"has a (dagger|shield) equipped", lambda m: {"type": "WEAPON_TYPE_EQUIPPED", "value": m.group(1)}),
]
COMBAT_MARKERS = ["start of turn", "end of turn", "per turn", "each time", "when ", "after suffering",
                  "every ", "spells cast", "this turn", "next spell", "previous turn", "suffers",
                  "during their turn", "permanent", "round", "for each condition"]


def load(n):
    return json.loads((DATA / n).read_text())


def main():
    subs = load("subs-game.json")
    states = load("states-full.json")
    wf = load("wakforge-state_data.json")
    en = load("wakforge-en_states.json")
    state_title = {s["definition"]["id"]: s.get("title", {}) for s in states}
    wf_by_id = {}
    for e in wf:
        try:
            wf_by_id[int(e["id"])] = e
        except Exception:
            pass

    by_state = defaultdict(list)
    for s in subs:
        by_state[int(s["stateId"])].append(s)

    out = []
    for sid, rows in sorted(by_state.items()):
        r0 = rows[0]
        if not (r0["isEpic"] or r0["isRelic"]):
            continue
        rarity = "EPIC" if r0["isEpic"] else "RELIC"
        wfe = wf_by_id.get(sid)
        names = {"fr": r0["fr"], "en": r0["en"], "state": state_title.get(sid, {}).get("en")}
        if not wfe:
            out.append({"stateId": sid, "names": names, "rarity": rarity, "kind": "UNKNOWN_NEEDS_CURATION",
                        "solverChoosable": False, "source": "manual:TODO"})
            continue

        cond = None
        kind = "FLAT"
        effects = []
        raw_lines = []
        combat = False
        for ln in wfe.get("descriptionData", []):
            tmpl = en.get(ln.get("text"), ln.get("text") or "")
            nums = {k: v.get("level_1") for k, v in ln.items() if k.startswith("num_")}
            def fill(t):
                return re.sub(r"\{(num_\d+)\}", lambda m: str(nums.get(m.group(1), "?")), t)
            resolved = fill(tmpl)
            raw_lines.append(resolved)
            low = resolved.lower()
            if any(mk in low for mk in COMBAT_MARKERS):
                combat = True
            # condition?
            if cond is None:
                for pat, fn in COND_PATTERNS:
                    m = re.search(pat, low)
                    if m:
                        cond = fn(m)
                        kind = "STATIC_CONDITIONAL"
                        break
            # effect line (indented, has a numeric value + maps to a characteristic)
            if ln.get("indented") and "num_0" in nums:
                for pat, ch in EFFECT_CHAR:
                    if re.search(pat, low):
                        effects.append({"characteristic": ch, "value": nums.get("num_0"), "scenarioGate": None})
                        break
        if "convert" in " ".join(raw_lines).lower():
            kind = "CONVERSION"
        elif combat and kind != "STATIC_CONDITIONAL":
            kind = "COMBAT_CONDITIONAL"
        solver_choosable = kind in ("FLAT", "STATIC_CONDITIONAL")

        out.append({
            "stateId": sid, "names": names, "rarity": rarity,
            "slotColorPattern": r0["slotColorPattern"], "maxLevel": 1,
            "kind": kind, "solverChoosable": solver_choosable,
            "condition": cond, "effects": effects,
            "source": f"wakforge:{sid}", "rawText": {"en": " | ".join(raw_lines)},
        })

    OUT.write_text(json.dumps(out, indent=2, ensure_ascii=False))
    by_kind = defaultdict(int)
    parsed_effect = 0
    for e in out:
        by_kind[e["kind"]] += 1
        if e.get("effects"):
            parsed_effect += 1
    print(f"DRAFT epic/relic entries: {len(out)} -> {OUT.name}")
    print("by kind:", dict(by_kind))
    print(f"entries with at least one auto-parsed effect: {parsed_effect}/{len(out)}")
    print("\nsample STATIC_CONDITIONAL entries:")
    for e in out:
        if e["kind"] == "STATIC_CONDITIONAL" and e.get("effects"):
            print(f'  {e["stateId"]} {e["names"]["en"]:<22} cond={e["condition"]} eff={e["effects"]}')


if __name__ == "__main__":
    main()
