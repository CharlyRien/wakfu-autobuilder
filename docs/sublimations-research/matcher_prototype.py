#!/usr/bin/env python3
"""
Sublimations Lot 3 — matcher prototype (research phase).

Goal: link each *distinct* game sublimation (by numeric stateId) to a curated effect.

Inputs (all under ./data):
  subs-game.json          467 game sublimation item rows (id, fr/en/es/pt, stateId, slotColorPattern, isEpic/isRelic)
                          extracted from Ankama items.json @ 1.91.1.54 (itemTypeId 812, actionId 304 -> params[0]=stateId)
  states-full.json        Ankama states.json (stateId -> state title; NO effect text)
  wakforge-state_data.json   WakForge structured per-level effect data, keyed by stateId (string `id`)
  epic/relic/sublimations.json  noredlace curated English effect text, keyed by Name (+ G/R/B sockets)

Join strategies evaluated:
  A) stateId (numeric) -> WakForge state_data        [structured, machine-readable]  PRIMARY
  B) name -> noredlace                                [free English text]             SECONDARY / human-readable
     B candidates per stateId: item title.en(s) + state title.en

Reports coverage of each + unmatched lists, written to ../matcher_report.json
"""
import json
import re
import unicodedata
from collections import defaultdict
from pathlib import Path

DATA = Path(__file__).parent / "data"
OUT = Path(__file__).parent / "matcher_report.json"


def load(name):
    return json.loads((DATA / name).read_text())


def norm(s):
    """Normalize a name for fuzzy matching: strip accents, lowercase, drop non-alnum."""
    if not s:
        return ""
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()
    s = s.lower()
    s = re.sub(r"[^a-z0-9]+", " ", s).strip()
    return s


def main():
    subs = load("subs-game.json")
    states = load("states-full.json")
    wf = load("wakforge-state_data.json")
    nored = {
        "epic": load("epic-sublimations.json"),
        "relic": load("relic-sublimations.json"),
        "normal": load("sublimations.json"),
    }

    # state title by stateId (Ankama states.json)
    state_title = {}
    for st in states:
        sid = st["definition"]["id"]
        state_title[sid] = st.get("title", {})

    # WakForge structured data keyed by int stateId
    wf_by_id = {}
    for entry in wf:
        try:
            wf_by_id[int(entry["id"])] = entry
        except (ValueError, KeyError, TypeError):
            pass

    # noredlace indexed by normalized Name -> (bucket, entry)
    nored_by_name = {}
    for bucket, rows in nored.items():
        for row in rows:
            nored_by_name[norm(row["Name"])] = (bucket, row)

    # Group game item rows by stateId -> distinct sublimation effect
    by_state = defaultdict(list)
    for s in subs:
        sid = int(s["stateId"])
        by_state[sid].append(s)

    distinct = []
    for sid, rows in by_state.items():
        r0 = rows[0]
        kind = "epic" if r0["isEpic"] else "relic" if r0["isRelic"] else "normal"
        item_names_en = sorted({r["en"] for r in rows})
        candidates = set()
        for r in rows:
            candidates.add(norm(r["en"]))
        candidates.add(norm(state_title.get(sid, {}).get("en")))
        candidates.discard("")

        # B) noredlace match
        nored_hit = None
        matched_via = None
        for c in candidates:
            if c in nored_by_name:
                nored_hit = nored_by_name[c]
                matched_via = c
                break

        distinct.append({
            "stateId": sid,
            "kind": kind,
            "item_names_en": item_names_en,
            "item_name_fr": r0["fr"],
            "state_name_en": state_title.get(sid, {}).get("en"),
            "state_name_fr": state_title.get(sid, {}).get("fr"),
            "n_item_rows": len(rows),
            "slotColorPattern": r0["slotColorPattern"],
            "wakforge_structured": sid in wf_by_id,
            "noredlace_bucket": nored_hit[0] if nored_hit else None,
            "noredlace_effect": nored_hit[1].get("Effect") if (nored_hit and "Effect" in nored_hit[1]) else (
                nored_hit[1].get("Tier1") if nored_hit else None),
            "noredlace_matched_via": matched_via,
        })

    # ---- Coverage report ----
    total = len(distinct)
    by_kind = defaultdict(lambda: {"total": 0, "wakforge": 0, "noredlace": 0})
    for d in distinct:
        k = by_kind[d["kind"]]
        k["total"] += 1
        if d["wakforge_structured"]:
            k["wakforge"] += 1
        if d["noredlace_bucket"]:
            k["noredlace"] += 1

    wf_cov = sum(1 for d in distinct if d["wakforge_structured"])
    nored_cov = sum(1 for d in distinct if d["noredlace_bucket"])
    either = sum(1 for d in distinct if d["wakforge_structured"] or d["noredlace_bucket"])
    both = sum(1 for d in distinct if d["wakforge_structured"] and d["noredlace_bucket"])

    # noredlace entries that never got matched to a game state (renamed / stale / tier-variants)
    matched_nored_names = {d["noredlace_matched_via"] for d in distinct if d["noredlace_matched_via"]}
    unmatched_nored = []
    for bucket, rows in nored.items():
        for row in rows:
            if norm(row["Name"]) not in matched_nored_names:
                unmatched_nored.append({"bucket": bucket, "name": row["Name"]})

    summary = {
        "distinct_sublimations_by_stateId": total,
        "raw_item_rows": len(subs),
        "coverage": {
            "wakforge_structured": f"{wf_cov}/{total} ({100*wf_cov//total}%)",
            "noredlace_text": f"{nored_cov}/{total} ({100*nored_cov//total}%)",
            "either_source": f"{either}/{total} ({100*either//total}%)",
            "both_sources": f"{both}/{total} ({100*both//total}%)",
        },
        "by_kind": {k: {
            "distinct": v["total"],
            "wakforge": f'{v["wakforge"]}/{v["total"]}',
            "noredlace": f'{v["noredlace"]}/{v["total"]}',
        } for k, v in by_kind.items()},
        "unmatched_by_either_source": [
            {"stateId": d["stateId"], "kind": d["kind"], "state_name_en": d["state_name_en"],
             "item_names_en": d["item_names_en"]}
            for d in distinct if not d["wakforge_structured"] and not d["noredlace_bucket"]
        ],
        "noredlace_entries_unmatched_to_game": unmatched_nored,
    }

    print(json.dumps(summary, indent=2, ensure_ascii=False))
    OUT.write_text(json.dumps({"summary": summary, "distinct": distinct}, indent=2, ensure_ascii=False))
    print(f"\nFull join table -> {OUT}")


if __name__ == "__main__":
    main()
