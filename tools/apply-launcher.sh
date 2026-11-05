#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: tools/apply-launcher.sh SERIAL [APK]}
if [[ $# -gt 1 ]]; then exec python3 tools/launcher-control.py apply "$serial" --apk "$2";fi
exec python3 tools/launcher-control.py apply "$serial"
