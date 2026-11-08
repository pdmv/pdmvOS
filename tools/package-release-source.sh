#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -z "$(git status --porcelain)" ]] || { echo 'Commit thay đổi trước khi đóng gói mã nguồn.' >&2; exit 1; }
export PATH="/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH"
version=$(sed -n 's/^versionName=//p' version.properties)
name="pdmvOS-$version-source"
bundle=$(mktemp -d)
trap 'rm -rf "$bundle"' EXIT
root="$bundle/$name"
mkdir -p "$root/.cargo" dist
git archive HEAD | tar -xf - -C "$root"
(
  cd "$root"
  cargo vendor --locked --manifest-path rust/Cargo.toml vendor > .cargo/config.toml
)
tar -czf "$bundle/$name.tar.gz" -C "$bundle" "$name"
mv "$bundle/$name.tar.gz" "dist/$name.tar.gz"
(cd dist && shasum -a 256 "$name.tar.gz" > "$name.tar.gz.sha256")
