#!/usr/bin/env bash
# Dependency-free SDK build for this Java-only application. Gradle remains the primary build.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${SDK:?Set ANDROID_HOME to the Android SDK}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
MODE="${1:-debug}"
case "$MODE" in debug|release) ;; *) echo 'Use debug or release' >&2; exit 2;; esac
MIN_SDK="${PDMVOS_MIN_SDK:-26}"
VERSION_CODE=$(sed -n 's/^versionCode=//p' version.properties)
VERSION_NAME=$(sed -n 's/^versionName=//p' version.properties)
OUT="$PWD/app/build/offline-$MODE"
mkdir -p "$OUT/classes" "$OUT/dex" "app/build/outputs/apk/$MODE"
find "$OUT/classes" "$OUT/dex" -type f -delete
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 17 -target 17 -classpath "$PLATFORM" \
  -d "$OUT/classes" app/src/main/java/dev/pdmv/os/*.java
"$JAVA_HOME/bin/jar" --create --file "$OUT/classes.jar" -C "$OUT/classes" .
"$JAVA_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --"$MODE" --min-api "$MIN_SDK" --lib "$PLATFORM" --output "$OUT/dex" "$OUT/classes.jar"
python3 - "$OUT/AndroidManifest.xml" <<'PY'
import sys
from pathlib import Path
source=Path('app/src/main/AndroidManifest.xml').read_text()
Path(sys.argv[1]).write_text(source.replace('<manifest ', '<manifest package="dev.pdmv.os" ',1))
PY
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/resources.zip"
"$BT/aapt2" link --auto-add-overlay -o "$OUT/base.apk" -I "$PLATFORM" -R "$OUT/resources.zip" \
  --manifest "$OUT/AndroidManifest.xml" -A app/src/main/assets \
  --min-sdk-version "$MIN_SDK" --target-sdk-version 35 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"
python3 - "$OUT" <<'PY'
import sys, zipfile
from pathlib import Path
root=Path(sys.argv[1])
with zipfile.ZipFile(root/'base.apk','a',zipfile.ZIP_DEFLATED) as apk:
    for dex in sorted((root/'dex').glob('*.dex')):
        apk.write(dex,dex.name)
PY
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
APK="$PWD/app/build/outputs/apk/$MODE/app-$MODE.apk"
if [[ "$MODE" == release ]]; then
  KEYSTORE="${PDMVOS_RELEASE_KEYSTORE:-$PWD/.local/signing/pdmvos-release.p12}"
  PASSFILE="${PDMVOS_RELEASE_PASSWORD_FILE:-$PWD/.local/signing/password}"
  [[ -f "$KEYSTORE" && -f "$PASSFILE" ]] || { echo 'Initialize release signing with tools/init-release-key.sh' >&2; exit 1; }
  lineage_args=()
  if [[ -n "${PDMVOS_SIGNING_LINEAGE:-}" ]]; then
    [[ "$MIN_SDK" -ge 28 ]] || { echo 'Key migration APK requires Android 9+' >&2;exit 1; }
    lineage_args=(--lineage "$PDMVOS_SIGNING_LINEAGE" --rotation-min-sdk-version 28 --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true)
  fi
  "$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" sign "${lineage_args[@]}" --ks "$KEYSTORE" \
    --ks-key-alias pdmvos --ks-pass "file:$PASSFILE" \
    --out "$APK" "$OUT/aligned.apk"
else
  KEYSTORE="${PDMVOS_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
  if [[ ! -f "$KEYSTORE" ]]; then
    KEYSTORE="$PWD/app/build/offline/debug.keystore"
    [[ -f "$KEYSTORE" ]] || { echo 'Missing existing debug key; configure PDMVOS_DEBUG_KEYSTORE' >&2; exit 1; }
  fi
  "$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$OUT/aligned.apk"
fi
"$JAVA_HOME/bin/java" -jar "$BT/lib/apksigner.jar" verify --verbose "$APK"
printf 'APK: %s\n' "$APK"
