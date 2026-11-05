#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v gh >/dev/null
[[ -z "$(git status --porcelain)" ]] || { echo 'Commit thay đổi trước khi phát hành.' >&2; exit 1; }
version=$(sed -n 's/^versionName=//p' version.properties)
tag="v$version"
if draft=$(gh release view "$tag" --json isDraft --jq '.isDraft' 2>/dev/null); then
  [[ "$draft" == true ]] || { echo 'Phiên bản đã công bố. Tăng version trước khi phát hành bản mới.' >&2; exit 1; }
fi
./tools/build-release.sh
./tools/package-release-kit.sh
if git rev-parse "$tag" >/dev/null 2>&1; then
  [[ "$(git rev-list -n 1 "$tag")" == "$(git rev-parse HEAD)" ]] || { echo 'Tag đang trỏ tới commit khác.' >&2; exit 1; }
else
  git tag -a "$tag" -m "pdmvOS $version"
fi
git push origin HEAD
git push origin "$tag"
if ! gh release view "$tag" >/dev/null 2>&1; then
  gh release create "$tag" --verify-tag --draft --title "pdmvOS $version" --notes-file docs/release-notes.md
fi
gh release upload "$tag" \
  "dist/pdmvOS-$version.apk" \
  "dist/pdmvOS-$version.apk.sha256" \
  "dist/pdmvOS-$version.release.json" \
  "dist/pdmvOS-$version-operating-kit.zip" \
  "dist/pdmvOS-$version-operating-kit.zip.sha256" --clobber
gh release edit "$tag" --draft=false --latest
gh release view "$tag" --json url --jq '.url'
