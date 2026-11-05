#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:?Set ANDROID_HOME}"; : "${JAVA_HOME:?Set JAVA_HOME}"
BT="$SDK/build-tools/34.0.0"
OUT="$PWD/app/build/package-fixture"
mkdir -p "$OUT/classes" "$OUT/dex"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$SDK/platforms/android-35/android.jar" -d "$OUT/classes" tests/fixtures/package-lifecycle/MainActivity.java
"$JAVA_HOME/bin/jar" --create --file "$OUT/classes.jar" -C "$OUT/classes" .
"$JAVA_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --output "$OUT/dex" "$OUT/classes.jar"
"$BT/aapt2" compile --dir tests/fixtures/package-lifecycle/res -o "$OUT/resources.zip"
for revision in 1 2; do
  "$BT/aapt2" link -o "$OUT/base-$revision.apk" -I "$SDK/platforms/android-35/android.jar" -R "$OUT/resources.zip" --manifest tests/fixtures/package-lifecycle/AndroidManifest.xml --version-code "$revision" --version-name "0.0.$revision"
  python3 - "$OUT" "$revision" <<'PY'
from pathlib import Path
import sys,zipfile
p=Path(sys.argv[1])
with zipfile.ZipFile(p/f'base-{sys.argv[2]}.apk','a',zipfile.ZIP_DEFLATED) as z:
    for f in (p/'dex').glob('*.dex'):z.write(f,f.name)
PY
  "$BT/zipalign" -f 4 "$OUT/base-$revision.apk" "$OUT/aligned-$revision.apk"
  "$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" sign --ks "$PWD/.local/signing/pdmvos-release.p12" --ks-key-alias pdmvos --ks-pass "file:$PWD/.local/signing/password" --out "$OUT/fixture-$revision.apk" "$OUT/aligned-$revision.apk"
done
printf 'Fixture APKs: %s\n' "$OUT"
