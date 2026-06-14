#!/usr/bin/env python3
"""
Classify the 232 distinct sublimations into the three engine buckets and report scope numbers:
  (a) flat        — unconditional flat stat (always-on)
  (b) static-cond — start-of-combat conditional on a BUILD-STATIC predicate (CP-SAT modelable)
  (c) combat-cond — temporal / in-combat conditional (out of solver scope -> forced input only)
Also flags whether the effect is damage-relevant (mentions "damage inflicted" / a damage mastery),
since only damage-relevant a+b subs become solver-*choosable value* in max-damage mode.
"""
import json
import re
from collections import Counter
from pathlib import Path

DATA = Path(__file__).parent / "data"


def load(n):
    return json.loads((DATA / n).read_text())


def resolve(entry, en, level=1):
    out = []
    for ln in entry.get("descriptionData", []):
        tmpl = en.get(ln.get("text"), ln.get("text") or "")
        out.append((ln.get("indented", False), tmpl))
    return out


# combat-temporal markers => bucket (c)
COMBAT_MARKERS = [
    "start of turn", "end of turn", "at start of fight", "per turn", "each time", "each turn",
    "when ", "after suffering", "every ", "spells cast", "is reapplied", "this turn", "next spell",
    "next turn", "previous turn", "flaming", "round", "for each condition", "turn 1", "turn 2",
    "during their turn", "once per", "permanent", "suffers", "cast:", "if the bearer inflict",
    "if the bearer uses", "if the bearer has only", "loses", "gains",
]
# build-static start-of-combat condition => bucket (b)
STATIC_COND = "at the start of combat, if"
STATIC_COND2 = "at the start of the first turn, if"
DMG_RELEVANT = ["damage inflicted", "rear mastery", "melee mastery", "elemental mastery",
                "critical mastery", "berserk", "damage", "mastery"]


def classify(lines):
    cond_lines = [t for ind, t in lines if not ind and t.strip()]
    all_text = " ".join(t for _, t in lines).lower()
    has_static = any(STATIC_COND in t.lower() or STATIC_COND2 in t.lower() for t in cond_lines)
    has_combat = any(any(m in t.lower() for m in COMBAT_MARKERS) for t in cond_lines)
    has_any_cond = bool(cond_lines)

    if has_combat and not has_static:
        return "c_combat"
    if has_static:
        # static start-of-combat condition: modelable — but downgrade if it ALSO has combat markers
        return "b_static" if not has_combat else "b_static_mixed"
    if not has_any_cond:
        return "a_flat"
    # has condition lines but neither clearly static-combat nor temporal -> treat as flat-ish/manual
    return "a_flat_or_manual"


def main():
    subs = load("subs-game.json")
    wf = load("wakforge-state_data.json")
    en = load("wakforge-en_states.json")
    wf_by_id = {}
    for e in wf:
        try:
            wf_by_id[int(e["id"])] = e
        except Exception:
            pass

    from collections import defaultdict
    by_state = defaultdict(list)
    for s in subs:
        by_state[int(s["stateId"])].append(s)

    buckets = Counter()
    buckets_dmg = Counter()
    kind_bucket = Counter()
    missing_wf = []
    examples = defaultdict(list)

    for sid, rows in by_state.items():
        kind = "epic" if rows[0]["isEpic"] else "relic" if rows[0]["isRelic"] else "normal"
        wfe = wf_by_id.get(sid)
        if not wfe:
            missing_wf.append((sid, kind, rows[0]["en"]))
            continue
        lines = resolve(wfe, en)
        b = classify(lines)
        all_text = " ".join(t for _, t in lines).lower()
        dmg = any(k in all_text for k in DMG_RELEVANT)
        buckets[b] += 1
        kind_bucket[(kind, b)] += 1
        if dmg:
            buckets_dmg[b] += 1
        if len(examples[b]) < 4:
            examples[b].append(f'{sid} [{kind}] {rows[0]["en"]}')

    print("=== BUCKET COUNTS (of 222 with WakForge data) ===")
    for b, c in buckets.most_common():
        print(f"  {b:>18}: {c:>4}   (damage-relevant: {buckets_dmg[b]})")
    print(f"  {'MISSING WakForge':>18}: {len(missing_wf):>4}  (need hand-curation)")
    print("\n=== BY KIND x BUCKET ===")
    for (kind, b), c in sorted(kind_bucket.items()):
        print(f"  {kind:>7} / {b:<18}: {c}")
    print("\n=== EXAMPLES PER BUCKET ===")
    for b, ex in examples.items():
        print(f"  {b}:")
        for e in ex:
            print(f"      {e}")

    solver_modelable = buckets["a_flat"] + buckets["b_static"]
    print("\n=== SCOPE SUMMARY ===")
    print(f"  Solver-choosable (a_flat + b_static):           {solver_modelable}")
    print(f"  Solver-choosable & damage-relevant:             {buckets_dmg['a_flat'] + buckets_dmg['b_static']}")
    print(f"  b_static_mixed (static gate + combat tail):     {buckets['b_static_mixed']}  (model static part, ignore combat tail)")
    print(f"  Forced-input only (c_combat):                   {buckets['c_combat']}")
    print(f"  a_flat_or_manual (review):                       {buckets['a_flat_or_manual']}")
    print(f"  Missing WakForge (hand-curate):                 {len(missing_wf)}")


if __name__ == "__main__":
    main()
