#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME}"
SDK="${ANDROID_HOME:?Set ANDROID_HOME}"
BT="$SDK/build-tools/34.0.0"
OUT="$PWD/app/build/tv-instrumentation"
mkdir -p "$OUT/classes" "$OUT/dex"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -cp "$SDK/platforms/android-35/android.jar" -d "$OUT/classes" tests/device/TvAcceptance.java
"$JAVA_HOME/bin/jar" --create --file "$OUT/classes.jar" -C "$OUT/classes" .
"$JAVA_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --output "$OUT/dex" "$OUT/classes.jar"
cat > "$OUT/AndroidManifest.xml" <<'MANIFEST'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.pdmv.os.tests">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
  <application android:label="pdmvOS TV acceptance tests" />
  <instrumentation android:name="dev.pdmv.os.tests.TvAcceptance" android:targetPackage="dev.pdmv.os" />
</manifest>
MANIFEST
"$BT/aapt2" link -o "$OUT/base.apk" -I "$SDK/platforms/android-35/android.jar" --manifest "$OUT/AndroidManifest.xml"
python3 - "$OUT" <<'PY'
from pathlib import Path
import sys,zipfile
p=Path(sys.argv[1])
with zipfile.ZipFile(p/'base.apk','a',zipfile.ZIP_DEFLATED) as z:
    for f in (p/'dex').glob('*.dex'):z.write(f,f.name)
PY
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
if [[ "${PDMVOS_TEST_SIGNING:-debug}" == release ]]; then
  KEY="$PWD/.local/signing/pdmvos-release.p12"
  "$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" sign --ks "$KEY" --ks-key-alias pdmvos \
    --ks-pass "file:$PWD/.local/signing/password" --out "$OUT/tv-tests.apk" "$OUT/aligned.apk"
else
  KEY="${PDMVOS_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
  if [[ ! -f "$KEY" ]]; then KEY="$PWD/app/build/offline/debug.keystore"; fi
  "$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" sign --ks "$KEY" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$OUT/tv-tests.apk" "$OUT/aligned.apk"
fi
printf 'TV instrumentation: %s\n' "$OUT/tv-tests.apk"
