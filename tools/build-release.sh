#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./tools/validate.sh
./tools/build-offline.sh release
mkdir -p dist
version=$(sed -n 's/^versionName=//p' version.properties)
artifact="dist/pdmvOS-${version}.apk"
cp app/build/outputs/apk/release/app-release.apk "$artifact"
(cd dist && shasum -a 256 "pdmvOS-$version.apk" > "pdmvOS-$version.apk.sha256")
python3 tools/check-release.py "$artifact"
printf 'Release: %s\n' "$artifact"
