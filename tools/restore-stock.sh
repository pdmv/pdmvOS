#!/usr/bin/env bash
# Legacy command now routes through device-specific saved state.
set -euo pipefail
exec "$(dirname "$0")/restore-launcher.sh" "$@"
