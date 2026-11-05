#!/usr/bin/env bash
# Install the bundled or official release and select Home.
set -euo pipefail
cd "$(dirname "$0")/.."
serial=${1:?Usage: tools/install.sh SERIAL [RELEASE_APK]}
if [[ $# -gt 1 ]]; then exec python3 tools/pdmvos.py install "$serial" --apk "$2"; fi
exec python3 tools/pdmvos.py install "$serial"
