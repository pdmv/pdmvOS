#!/usr/bin/env bash
# Preview device only. Does not replace production signing or disable the stock launcher.
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: tools/dev.sh PREVIEW_TV_SERIAL}
./tools/build-offline.sh debug
if ! adb -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk; then
  echo 'Production TVs require the release key. Use tools/install.sh; do not uninstall user data to bypass signing.' >&2
  exit 1
fi
adb -s "$serial" shell am start -W -n dev.pdmv.os/.MainActivity
