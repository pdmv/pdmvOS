#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB=(adb)
if [[ -n "${1:-}" ]]; then ADB+=(-s "$1"); fi
APK=app/build/outputs/apk/debug/app-debug.apk
[[ -s "$APK" ]] || { echo 'Build the APK first.' >&2; exit 1; }
"${ADB[@]}" get-state >/dev/null
OUT=app/build/tv-test
mkdir -p "$OUT"
"${ADB[@]}" install -r "$APK"
"${ADB[@]}" shell am force-stop dev.pdmv.os
"${ADB[@]}" shell am start -W -n dev.pdmv.os/.MainActivity
sleep 2
"${ADB[@]}" shell dumpsys gfxinfo dev.pdmv.os reset > /dev/null
# Exercise dock focus, drawer navigation, selection reversal and Home return.
for key in 22 22 21 19 20 22 20 21 4 3; do
  "${ADB[@]}" shell input keyevent "$key"
  sleep 0.25
done
"${ADB[@]}" shell am start -W -n dev.pdmv.os/.MainActivity
sleep 2
"${ADB[@]}" shell dumpsys display > "$OUT/display.txt"
"${ADB[@]}" shell dumpsys SurfaceFlinger > "$OUT/surfaces.txt"
"${ADB[@]}" shell dumpsys gfxinfo dev.pdmv.os framestats > "$OUT/frames.txt"
"${ADB[@]}" shell dumpsys meminfo dev.pdmv.os > "$OUT/memory.txt"
PID=$("${ADB[@]}" shell pidof dev.pdmv.os | tr -d '\r')
[[ -n "$PID" ]] || { echo 'FAIL: launcher process exited.' >&2; exit 1; }
"${ADB[@]}" logcat -d --pid="$PID" -v time > "$OUT/logcat.txt"
if rg -n 'render.failed|startup.failed|FATAL EXCEPTION' "$OUT/logcat.txt"; then
  echo 'FAIL: inspect TV runtime logs.' >&2; exit 1
fi
echo "PASS: launch and D-pad smoke test. Reports: $OUT"
echo 'Review HWUI window dimensions and frame diagnostics; HDMI and perceived smoothness need manual checks.'
