#!/usr/bin/env python3
"""Summarize real samples only; no guessed speedup or absolute emulator latency gate."""
import collections
import json
import pathlib
import statistics
import sys
root = pathlib.Path(sys.argv[1])
groups = collections.defaultdict(list)
for name in ('native-metrics.jsonl', 'baseline-metrics.jsonl'):
    path = root / name
    if not path.exists():
        continue
    for line in path.read_text().splitlines():
        try:
            row = json.loads(line)
            key = (row['engine'], row['chars'], row['singleParagraph'], row['position'],
                   'frame' if row['operation'].startswith('frame-') else 'sync')
            groups[key].append(row['editNs'] / 1e6)
        except (ValueError, KeyError):
            continue
lines = ['# Observed editor input timings (ms)', '',
         'Sync = InputConnection call only, excludes deferred layout/draw. Frame = input to second Choreographer callback; scheduling + layout/draw opportunity, NOT GPU present latency.',
         'Debug builds on emulator; no absolute performance gate. Full-text test oracle and JSON serialization excluded from frame samples.', '',
         '| Engine | chars | single paragraph | position | timing | N | median ms | p95 ms | max ms |',
         '|---|---:|---|---|---|---:|---:|---:|---:|']
for key, values in sorted(groups.items()):
    values.sort()
    p95 = values[min(len(values)-1, int((len(values)-1)*0.95 + 0.5))]
    lines.append('| ' + ' | '.join(map(str, key)) + f' | {len(values)} | {statistics.median(values):.3f} | {p95:.3f} | {max(values):.3f} |')
if not groups:
    lines += ['', '**No valid timing samples collected. This is not performance acceptance.**']
(root / 'timing-summary.md').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))
