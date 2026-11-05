#!/usr/bin/env bash
# Compatibility entry point; prefer apply-launcher.sh / restore-launcher.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: tools/default-launcher.sh SERIAL status|apply|restore}
exec python3 tools/launcher-control.py "${2:-status}" "$serial"
