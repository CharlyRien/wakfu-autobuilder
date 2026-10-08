#!/usr/bin/env python3
"""Offline investigation only. Never writes spells.json or spell-damage.json."""
import argparse
import collections
import csv
import io
import json
import math
from pathlib import Path

ACTIONS = {1, 2, 3, 4, 5, 917, 1083}
STRUCTURAL_KEYS = {'depth', 'order', 'path'}


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def pair(effect):
    return effect['params'][:2] if effect else None


def normal(effect):
    return effect['critical_state'].strip() != 'CRITICAL'


def expected(row, critical=False):
    oracle = row['oracle']
    return [oracle['critBase'], oracle['critInc']] if critical else [oracle['base'], oracle['inc']]


def hit(effect, cap):
    return math.floor(effect['params'][0] + effect['params'][1] * cap + 1e-6)


def choose(effects, rule, cap, branch):
    # Only table metadata/order/formulas participate. No encyclopedia element or damage number.
    if rule.startswith('displayed'):
        effects = [effect for effect in effects if effect['display_in_spell_description']]
    if rule.endswith('no-criterion'):
        effects = [effect for effect in effects if not effect['effect_criterion']]
    if rule == 'minimum-depth':
        effects = sorted(effects, key=lambda effect: effect['depth'])
    if rule in ('minimum-hit', 'maximum-hit'):
        effects = sorted(effects, key=lambda effect: hit(effect, cap), reverse=rule == 'maximum-hit')
    if rule == 'branch-first':
        action = {0: 1, 1: 2, 2: 4, 3: 3, 4: 5, 5: 917, 6: 1083}.get(branch)
        effects = sorted(effects, key=lambda effect: effect['action_id'] != action)
    return next(iter(effects), None)


def csv_text(rows):
    buffer = io.StringIO(newline='')
    writer = csv.DictWriter(buffer, fieldnames=list(rows[0]), lineterminator='\n')
    writer.writeheader()
    writer.writerows(rows)
    return buffer.getvalue()


def reason(effects, selected, oracle_pair, row, critical):
    matches = [effect for effect in effects if pair(effect) == oracle_pair]
    if not matches:
        if critical and row['regenerated']['critBase'] == 0 and expected(row, True) == [row['regenerated']['critBase'], row['regenerated']['critInc']]:
            return 'The oracle critical formula is an anchored linear approximation, not an available damage effect.'
        return 'The exact oracle damage formula is absent in the installed client; selecting another effect changes it.'
    if selected is None:
        return 'All candidates were removed by the display flag; the oracle formula is present on a hidden effect.'
    if not any(effect['display_in_spell_description'] for effect in matches):
        return 'The oracle formula exists only on hidden effects; the display flag selects a different formula.'
    if any(effect['params'][:2] == [0.0, 1.0] for effect in [selected]):
        return 'The first displayed effect has identity params [0,1]; a later effect carries the oracle formula.'
    return 'Several displayed variants have different formulas; table traversal order selects a different variant.'


def analyze(dump):
    rows = [json.loads(line) for line in (dump / 'spell-graphs.jsonl').read_text().splitlines()]
    states = [json.loads(line) for line in (dump / 'state-graphs.jsonl').read_text().splitlines()]
    assert len({row['spell']['id'] for row in rows}) == len(rows), 'Duplicate spell ids'
    rules = ['first', 'displayed', 'no-criterion', 'displayed-no-criterion', 'minimum-depth', 'minimum-hit', 'maximum-hit', 'branch-first']
    stats = {rule: collections.Counter() for rule in rules}
    comparisons, mismatches, candidates, graphs = [], [], [], []
    unique_effects = {}
    for row in rows:
        spell, oracle, record = row['spell'], row['oracle'], row['record']
        cap = record['max_level'] if record else None
        damage = [effect for effect in row['effects'] if effect['action_id'] in ACTIONS and len(effect['params']) >= 2]
        ns = [effect for effect in damage if normal(effect)]
        cs = [effect for effect in damage if not normal(effect)]
        comparison = dict(spellId=spell['id'], name=spell['name']['en'], oracleMatched=oracle['matched'], clientPresent=record is not None,
                          normalFormulaPresent=any(pair(effect) == expected(row) for effect in ns),
                          criticalFormulaPresent=any(pair(effect) == expected(row, True) for effect in cs),
                          capExact=cap == oracle['levelCap'], anchoredRegenerationExact=row['regenerated'] == oracle)
        graphs.append(dict(spellId=spell['id'], rootEffectIds=encoded(record['effect_ids'] if record else []),
                           traversalEffectIds=encoded([effect['effect_id'] for effect in row['effects']])))
        for rule in rules:
            selected_n = choose(ns, rule, cap, record['spell_branch']) if record else None
            selected_c = choose(cs, rule, cap, record['spell_branch']) if record else None
            n_exact = pair(selected_n) == expected(row) and cap == oracle['levelCap']
            c_exact = pair(selected_c) == expected(row, True) and cap == oracle['levelCap']
            comparison[rule + 'NormalExact'] = n_exact
            comparison[rule + 'BothExact'] = n_exact and c_exact
            if oracle['matched']:
                stats[rule]['normalExact'] += n_exact
                stats[rule]['bothExact'] += n_exact and c_exact
                stats[rule]['normalMismatched'] += not n_exact
                stats[rule]['eitherMismatched'] += not (n_exact and c_exact)
                if rule == 'displayed' and not (n_exact and c_exact):
                    for critical, exact, effects, selected in [(False, n_exact, ns, selected_n), (True, c_exact, cs, selected_c)]:
                        if not exact:
                            mismatches.append(dict(spellId=spell['id'], name=spell['name']['en'], hit='critical' if critical else 'normal',
                                                   oraclePair=encoded(expected(row, critical)), selectedEffectId=selected['effect_id'] if selected else '',
                                                   selectedPair=encoded(pair(selected)),
                                                   oracleEffectIds=encoded([effect['effect_id'] for effect in effects if pair(effect) == expected(row, critical)]),
                                                   reason=reason(effects, selected, expected(row, critical), row, critical)))
        comparisons.append(comparison)
        for effect in row['effects']:
            fields = {key: value for key, value in effect.items() if key not in STRUCTURAL_KEYS}
            old = unique_effects.setdefault(effect['effect_id'], fields)
            assert old == fields, 'Conflicting effect rows'
        for effect in damage:
            candidates.append(dict(spellId=spell['id'], name=spell['name']['en'], order=effect['order'], depth=effect['depth'],
                                   path=encoded(effect['path']), effectId=effect['effect_id'], parentId=effect['parent_id'], actionId=effect['action_id'],
                                   criticalState=effect['critical_state'].strip(), params=encoded(effect['params']), hitAtCap=hit(effect, oracle['levelCap']),
                                   oracleNormalPair=normal(effect) and pair(effect) == expected(row),
                                   oracleCriticalPair=not normal(effect) and pair(effect) == expected(row, True)))
    # Lossless compact metadata: every field is defaulted explicitly, then overridden per unique effect.
    # No field is guessed/omitted from reconstruction. Raw full rows remain in the reproducible build dump.
    all_fields = sorted(next(iter(unique_effects.values())))
    assert all(set(effect) == set(all_fields) for effect in unique_effects.values())
    defaults = {key: json.loads(collections.Counter(encoded(effect[key]) for effect in unique_effects.values()).most_common(1)[0][0]) for key in all_fields}
    compact = []
    for effect_id, effect in sorted(unique_effects.items()):
        override = {key: value for key, value in effect.items() if defaults[key] != value or key == 'effect_id'}
        assert defaults | override == effect
        compact.append(encoded(override))
    matched = [row for row in rows if row['oracle']['matched']]
    light = [row for row in rows if 'element(LIGHT)' in row['spell'].get('missingFields', [])]
    summary = dict(spells=len(rows), oracleMatched=len(matched), oracleFallback=len(rows) - len(matched),
                   clientPresent=sum(row['record'] is not None for row in rows), damageCandidates=len(candidates),
                   uniqueReachableEffects=len(unique_effects), staticEffectFields=len(all_fields),
                   normalOracleFormulaPresent=sum(comparison['normalFormulaPresent'] for comparison in comparisons if comparison['oracleMatched']),
                   anchoredRegenerationExact=sum(comparison['anchoredRegenerationExact'] for comparison in comparisons),
                   referencedFallbackStates=len(states),
                   light=dict(spells=len(light), matched=sum(row['oracle']['matched'] for row in light),
                              firstNormalExact=sum(pair(choose([effect for effect in row['effects'] if effect['action_id'] in ACTIONS and normal(effect) and len(effect['params']) >= 2], 'first', row['oracle']['levelCap'], None)) == expected(row) for row in light)),
                   rules=stats)
    return {'summary.json': json.dumps(summary, indent=2, sort_keys=True) + '\n',
            'comparison.csv': csv_text(comparisons), 'displayed-mismatches.csv': csv_text(mismatches),
            'candidates.csv': csv_text(candidates), 'graphs.csv': csv_text(graphs),
            'effect-defaults.json': json.dumps(defaults, indent=2, sort_keys=True) + '\n',
            'effect-metadata.jsonl': '\n'.join(compact) + '\n',
            'fingerprints.txt': (dump / 'fingerprints.txt').read_text()}


# Regenerable from the probe dump; too large to commit.
RAW_ARTIFACTS = ('effect-defaults.json', 'effect-metadata.jsonl')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dump', type=Path, default=Path('bdata-extractor/build/spell-damage-client'))
    parser.add_argument('--out', type=Path, default=Path('docs/spell-damage-client/evidence'))
    parser.add_argument('--check', action='store_true', help='Compare the snapshot without rewriting it')
    parser.add_argument('--raw', action='store_true',
                        help='Also emit the ~2 MB raw effect dump (effect-metadata.jsonl + effect-defaults.json); not committed')
    args = parser.parse_args()
    artifacts = analyze(args.dump)
    if not args.raw:
        for name in RAW_ARTIFACTS:
            artifacts.pop(name)
    if args.check:
        for name, content in artifacts.items():
            assert (args.out / name).read_text() == content, f'Research evidence drift: {name}'
        print('All research evidence reproduces exactly')
    else:
        args.out.mkdir(parents=True, exist_ok=True)
        for name, content in artifacts.items():
            (args.out / name).write_text(content)
    print(artifacts['summary.json'])


if __name__ == '__main__':
    main()
