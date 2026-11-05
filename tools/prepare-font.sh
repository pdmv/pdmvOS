#!/usr/bin/env bash
# Run locally. The generated licensed font binaries remain on the user's computer.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="${PYTHON:-python3}"
if ! "$PYTHON" -c 'import fontTools' >/dev/null 2>&1; then
  if [[ ! -x "$ROOT/.venv/bin/python" ]]; then
    "$PYTHON" -m venv "$ROOT/.venv"
  fi
  PYTHON="$ROOT/.venv/bin/python"
  "$PYTHON" -m pip install --disable-pip-version-check fonttools
fi
"$PYTHON" - "$ROOT" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
folder=root/'app/src/main/assets/fonts'
source=folder/'google_sans_flex.ttf'
if not source.is_file():
    raise SystemExit('Missing fonts/google_sans_flex.ttf; run tools/install-font.sh first.')
try:
    from fontTools.ttLib import TTFont
    from fontTools.varLib.instancer import instantiateVariableFont
except ImportError:
    raise SystemExit('Install fontTools in the local project .venv')
for name,weight in [('regular',400),('semibold',600)]:
    font=TTFont(str(source))
    if 'fvar' in font:
        locations={axis.axisTag:(min(axis.maxValue,max(axis.minValue,weight)) if axis.axisTag=='wght' else axis.defaultValue)
                   for axis in font['fvar'].axes}
        font=instantiateVariableFont(font,locations,inplace=True,updateFontNames=True)
    output=folder/f'google_sans_flex_{name}.ttf'
    font.save(str(output))
    font.close()
    print('Prepared static font instance:',output)
print('Static fonts will be preferred automatically on Android TV 9.')
PY
