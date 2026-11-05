#!/usr/bin/env bash
set -euo pipefail
# Non-mutating diagnostics: launch pdmvOS first, then run this to examine artwork readiness.
if ! command -v adb >/dev/null 2>&1; then echo 'adb was not found' >&2; exit 1; fi
printf 'pdmvOS PID: '
adb shell pidof dev.pdmv.os || true
printf '\nRelevant pdmvOS artwork messages (most recent):\n'
adb logcat -d -v time 2>/dev/null | grep -E 'artwork\.(queue|ready)|Artwork failed|Cannot render drawable|Icon fallback unavailable|AndroidRuntime.*FATAL' | tail -n 100 || true
printf '\nTo capture a pixel-accurate screenshot:\n'
printf '  adb exec-out screencap -p > pdmvOS-screen.png\n'
