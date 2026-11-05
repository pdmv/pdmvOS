#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/^versionName=//p' version.properties)
python3 tools/check-release.py "dist/pdmvOS-$version.apk"
kit=$(mktemp -d)
trap 'rm -rf "$kit"' EXIT
root="$kit/pdmvOS-$version"
mkdir -p "$root/tools" "$root/dist"
cp tools/{pdmvos.py,release_assets.py,launcher-control.py,set-wallpaper.py} "$root/tools/"
cp pdmvos install.py README.md LICENSE NOTICE.md ASSETS_LICENSE.md CHANGELOG.md release-signing.json version.properties "$root/"
cp -R docs "$root/docs"
cp "dist/pdmvOS-$version.apk" "dist/pdmvOS-$version.apk.sha256" "dist/pdmvOS-$version.release.json" "$root/dist/"
archive="pdmvOS-$version-operating-kit.zip"
(cd "$kit" && zip -qr "$archive" "pdmvOS-$version")
mv "$kit/$archive" "dist/$archive"
python3 - "dist/$archive" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name in archive.namelist():
        assert '.local/' not in name and not name.endswith(('.p12', '.keystore', '.jks', '.key', '.pem'))
        assert '/password' not in name and '/qa/' not in name
PY
(cd dist && shasum -a 256 "$archive" > "$archive.sha256")
echo "Operating kit: dist/$archive"
