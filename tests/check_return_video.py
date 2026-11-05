#!/usr/bin/env python3
"""Pixel regression for the controlled Play Store -> pdmvOS recording.

Requires ffmpeg. This checks captured composition, not onDraw callback counts.
Usage: python3 tests/check_return_video.py BEFORE.mp4 AFTER.mp4
"""
import json
import subprocess
import sys


def white_coverage(path):
    width, height = 160, 90
    raw = subprocess.check_output([
        'ffmpeg', '-hide_banner', '-loglevel', 'error', '-i', path,
        '-vf', f'fps=20,scale={width}:{height}', '-pix_fmt', 'rgb24',
        '-f', 'rawvideo', '-'
    ])
    size = width * height * 3
    values = []
    for offset in range(0, len(raw) - size + 1, size):
        frame = raw[offset:offset + size]
        white = sum(
            1 for i in range(0, size, 3)
            if min(frame[i:i + 3]) > 125
            and max(frame[i:i + 3]) - min(frame[i:i + 3]) < 22
        )
        values.append(white / (width * height))
    if not values:
        raise AssertionError('No decoded video frames')
    return values


before, after = map(white_coverage, sys.argv[1:3])
assert max(before) < .2, 'Baseline unexpectedly includes a large app card'
assert max(after) > .2, 'No large app card visible in captured output'
assert after[-1] < max(after) * .7, 'App card did not shrink back before recording ended'
print(json.dumps({
    'status': 'PASS',
    'scenario': 'controlled Play Store return: large card visible, then reduced to Home',
    'baseline_peak_light_card_coverage': max(before),
    'fixed_peak_light_card_coverage': max(after),
    'fixed_final_light_card_coverage': after[-1],
}, indent=2))
