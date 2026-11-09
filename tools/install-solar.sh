#!/usr/bin/env bash
# Official Solar static package, pinned for reproducible vector geometry.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=app/src/main/assets/solar
for entry in settings:settings check:check info:info-circle move:transfer-horizontal apps:widget-2 wifi:wi-fi input:outline/screencast-2 plus:add; do
 name=${entry%%:*}; remote=${entry#*:}
 [[ "$remote" == */* ]] || remote="linear/$remote"
 curl -fLsS --connect-timeout 10 --max-time 30 --retry 1 "https://cdn.jsdelivr.net/npm/@solar-icons/static@2.3.3/dist/icons/${remote}.svg" -o "$OUT/$name.svg"
done
cat > "$OUT/CREDITS.txt" <<'LICENSE'
Solar Icons by 480 Design, CC BY 4.0.
Original SVG geometry from official @solar-icons/static 2.3.3. The input icon is Screencast 2 Outline; the others use Linear.
https://solar-icons.dev/docs/v2/packages/static
https://github.com/saoudi-h/solar-icons
https://creativecommons.org/licenses/by/4.0/
Compiled to Android VectorDrawable without rasterization.
LICENSE
python3 tools/prepare-solar-vectors.py
