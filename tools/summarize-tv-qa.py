#!/usr/bin/env python3
"""Summarize local TV evidence, keeping VSYNC, total duration and presentation distinct.

AOSP FrameTracker::dumpStats prints desiredPresentTime, actualPresentTime,
frameReadyTime. Reference:
https://android.googlesource.com/platform/frameworks/native/+/51de481/services/surfaceflinger/FrameTracker.cpp

Snapshots retain only recent frames. Phases can intentionally stop painting
after a spring settles; only near-consecutive desired times are used for
presentation pairs.
"""
import json
import statistics
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'app/build/tv-qa')
report = json.loads((root / 'report.json').read_text())
phases = {
    'app_list': 'app-list', 'menu': 'menu', 'hold_release': 'hold-release',
    'menu_press_release': 'menu-press', 'header_controls': 'header-controls', 'focus': 'focus',
    'toast_slide': 'toast-slide', 'dialog_reveal': 'dialog-reveal'
}
limit = (1 << 63) - 1
metadata = json.loads((root / 'device.json').read_text()) if (root / 'device.json').exists() else {}
summary = {**metadata, 'phases': {}}
for name, stem in phases.items():
    if name not in report:
        continue
    data = dict(report[name])
    rows = []
    source = root / f'{stem}-presentation.txt'
    for line in source.read_text().splitlines()[1:]:
        values = line.split()
        if len(values) != 3:
            continue
        desired, actual, ready = map(int, values)
        if 0 < desired < limit and 0 < actual < limit:
            rows.append((desired, actual, ready))
    pairs = []
    for a, b in zip(rows, rows[1:]):
        desired_gap = b[0] - a[0]
        actual_gap = b[1] - a[1]
        if actual_gap <= 0:
            continue
        if 0 < desired_gap <= 25_000_000:
            pairs.append(actual_gap / 1_000_000)
    ordered = sorted(pairs)
    data['presentation_pairs_in_snapshot'] = len(pairs)
    data['presentation_gaps_over_25ms'] = sum(t > 25 for t in pairs)
    if pairs:
        data['presentation_p50_ms'] = statistics.median(pairs)
        data['presentation_p95_ms'] = ordered[min(len(ordered)-1, int(len(ordered)*.95))]
        data['presentation_mean_interval_ms'] = statistics.mean(pairs)
    summary['phases'][name] = data
summary['motion_samples'] = sum(d['motion_vsync_samples'] for d in summary['phases'].values())
summary['motion_gaps_over_25ms'] = sum(d['motion_vsync_gaps_over_25ms'] for d in summary['phases'].values())
summary['features'] = {k: v for k, v in report.items() if k not in phases}
(root / 'summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False)+'\n')
for name, data in summary['phases'].items():
    print(f"{name}: motion p95={data['motion_interval_p95_ms']:.3f} ms; "
          f"motion gaps={data['motion_vsync_gaps_over_25ms']}/{data['motion_vsync_samples']}; "
          f"presentation p95={data.get('presentation_p95_ms', 0):.3f} ms; "
          f"presentation gaps={data['presentation_gaps_over_25ms']}/{data['presentation_pairs_in_snapshot']}")
print('Feature status:', report.get('status'))
