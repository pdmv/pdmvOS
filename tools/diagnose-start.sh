#!/usr/bin/env bash
set -euo pipefail
PACKAGE='dev.pdmv.os'
ACTIVITY='dev.pdmv.os/.MainActivity'

echo '== pdmvOS startup diagnostic =='
adb get-state >/dev/null
adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
adb logcat -c || true

echo '-- launching activity --'
set +e
START_OUTPUT=$(adb shell am start -W -n "$ACTIVITY" 2>&1)
START_STATUS=$?
set -e
printf '%s\n' "$START_OUTPUT"
sleep 2

PID=$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
if [[ $START_STATUS -eq 0 && -n "$PID" ]]; then
  echo "PASS: pdmvOS is running (pid $PID)"
  adb shell dumpsys meminfo "$PACKAGE" 2>/dev/null | sed -n '1,35p' || true
  exit 0
fi

echo 'FAIL: pdmvOS is not alive after launch.' >&2
echo '-- relevant logcat --' >&2
adb logcat -d -v time 2>/dev/null \
  | grep -E 'pdmvOS|dev\.pdmv\.os|AndroidRuntime|FATAL EXCEPTION|OutOfMemoryError|Resources\$NotFoundException|VerifyError|NoSuchMethodError' \
  | tail -n 220 >&2 || true
exit 1
