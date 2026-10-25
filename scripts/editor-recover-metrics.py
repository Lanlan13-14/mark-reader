#!/usr/bin/env python3
"""Recover metrics when AGP uninstalls the app after instrumentation."""
import json
import pathlib
import sys
root = pathlib.Path(sys.argv[1])
rows = []
log = root / 'logcat.txt'
if log.exists():
    for line in log.read_text(errors='replace').splitlines():
        if 'EditorMetrics:' not in line:
            continue
        try:
            row = json.loads(line.split('EditorMetrics:', 1)[1].strip())
            if 'engine' in row and 'editNs' in row:
                rows.append(row)
        except (ValueError, KeyError):
            pass
# The file source is authoritative if present. Avoid counting a sample twice.
existing = set()
for name in ('native-metrics.jsonl', 'baseline-metrics.jsonl'):
    path = root / name
    if path.exists():
        for line in path.read_text(errors='replace').splitlines():
            try:
                existing.add(json.dumps(json.loads(line), sort_keys=True))
            except ValueError:
                pass
rows = [row for row in rows if json.dumps(row, sort_keys=True) not in existing]
(root / 'logcat-metrics.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in rows))
print(f'Recovered {len(rows)} real logcat samples')
