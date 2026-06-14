#!/usr/bin/env python3
"""
Resolve WakForge structured sublimation effects into human text + a structured parse, then:
  1. Trace ~10 high-value DI epic/relic subs end-to-end (game id -> curated effect -> proposed structure).
  2. Enumerate the distinct *condition* templates across all subs (to size bucket b — the
     static start-of-combat conditionals the solver can model).
"""
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

DATA = Path(__file__).parent / "data"


def load(n):
    return json.loads((DATA / n).read_text())


def resolve_template(tmpl, num_map, level=1):
    """Fill {num_0}, {num_1}... in a template with its level-`level` values."""
    def repl(m):
        key = m.group(1)
        vals = num_map.get(key, {})
        return str(vals.get(f"level_{level}", "?"))
    return re.sub(r"\{(num_\d+)\}", repl, tmpl)


def resolve_state(entry, en, level=1):
    """Return list of (indented, resolved_text, raw_template, nums) lines for a WakForge state entry."""
    lines = []
    for ln in entry.get("descriptionData", []):
        tmpl_key = ln.get("text")
        tmpl = en.get(tmpl_key, tmpl_key)
        nums = {k: v for k, v in ln.items() if k.startswith("num_")}
        resolved = resolve_template(tmpl, nums, level)
        lines.append({"indented": ln.get("indented", False), "text": resolved,
                      "template": tmpl, "nums": {k: v.get("level_1") for k, v in nums.items()}})
    return lines


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
        except (ValueError, KeyError, TypeError):
            pass

    by_state = defaultdict(list)
    for s in subs:
        by_state[int(s["stateId"])].append(s)

    # ---- 1. Trace high-value DI epic/relic subs ----
    print("=" * 90)
    print("TRACE: high-value DAMAGE-INFLICTED epic/relic sublimations (end-to-end)")
    print("=" * 90)
    traced = 0
    for sid, rows in sorted(by_state.items()):
        r0 = rows[0]
        if not (r0["isEpic"] or r0["isRelic"]):
            continue
        wfe = wf_by_id.get(sid)
        if not wfe:
            continue
        lines = resolve_state(wfe, en)
        text_join = " | ".join(l["text"] for l in lines)
        if "damage inflicted" not in text_join.lower():
            continue
        traced += 1
        if traced > 12:
            break
        kind = "EPIC" if r0["isEpic"] else "RELIC"
        print(f"\n[{kind}] stateId={sid}  item.en={sorted({r['en'] for r in rows})}  state.en={state_title.get(sid,{}).get('en')}")
        for l in lines:
            pad = "    -> " if l["indented"] else "  IF: "
            print(f'{pad}{l["text"]}    {l["nums"]}')

    # ---- 2. Condition vocabulary (non-indented lines = conditions / context) ----
    print("\n" + "=" * 90)
    print("CONDITION-LINE TEMPLATE VOCABULARY (non-indented lines across ALL subs w/ WakForge data)")
    print("=" * 90)
    cond_counter = Counter()
    for sid, rows in by_state.items():
        wfe = wf_by_id.get(sid)
        if not wfe:
            continue
        for ln in wfe.get("descriptionData", []):
            if not ln.get("indented", False):
                tmpl = en.get(ln.get("text"), ln.get("text"))
                # generalize: drop numbers to cluster templates
                generalized = re.sub(r"\{num_\d+\}", "{n}", tmpl)
                cond_counter[generalized] += 1
    for tmpl, cnt in cond_counter.most_common():
        print(f"  {cnt:>4}x  {tmpl}")


if __name__ == "__main__":
    main()
