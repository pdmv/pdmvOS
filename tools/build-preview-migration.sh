#!/usr/bin/env bash
# One-time bridge from the owned preview debug key to the production key; Android 9+.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME}"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
old_key="${PDMVOS_DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"
[[ -f "$old_key" ]] || old_key="$PWD/app/build/offline/debug.keystore"
[[ -f "$old_key" ]] || { echo 'Missing the original preview signing key' >&2;exit 1; }
lineage="$PWD/.local/signing/preview-to-release.lineage"
if [[ ! -f "$lineage" ]]; then
  "$JAVA_HOME/bin/java" -jar "$ANDROID_HOME/build-tools/34.0.0/lib/apksigner.jar" rotate --out "$lineage" \
    --old-signer --ks "$old_key" --ks-key-alias androiddebugkey --ks-pass pass:android \
    --set-installed-data true --set-rollback false --set-shared-uid false --set-permission false --set-auth false \
    --new-signer --ks "$PWD/.local/signing/pdmvos-release.p12" --ks-key-alias pdmvos \
    --ks-pass "file:$PWD/.local/signing/password"
fi
PDMVOS_MIN_SDK=28 PDMVOS_SIGNING_LINEAGE="$lineage" ./tools/build-offline.sh release
version=$(sed -n 's/^versionName=//p' version.properties)
artifact="dist/pdmvOS-${version}-preview-upgrade-android9.apk"
cp app/build/outputs/apk/release/app-release.apk "$artifact"
shasum -a 256 "$artifact" > "$artifact.sha256"
python3 tools/check-release.py "$artifact"
echo "Preview migration: $artifact"
