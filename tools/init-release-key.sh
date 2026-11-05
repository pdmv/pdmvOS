#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
umask 077
keydir="$PWD/.local/signing"
mkdir -p "$keydir"
if [[ -e "$keydir/pdmvos-release.p12" || -e "$keydir/password" ]]; then
  [[ -s "$keydir/pdmvos-release.p12" && -s "$keydir/password" ]] || { echo 'Signing state incomplete; restore backup, do not regenerate.' >&2;exit 1; }
  echo 'Existing release key retained.'
else
  [[ ! -f release-signing.json ]] || { echo 'Production certificate is pinned; restore .local/signing from backup, do not generate another key.' >&2;exit 1; }
python3 - "$keydir/password" <<'PY'
from pathlib import Path
import secrets,sys
Path(sys.argv[1]).write_text(secrets.token_urlsafe(48)+'\n')
PY
"$JAVA_HOME/bin/keytool" -genkeypair -storetype PKCS12 -keystore "$keydir/pdmvos-release.p12" \
  -storepass:file "$keydir/password" -keypass:file "$keydir/password" -alias pdmvos \
  -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=pdmvOS Release,O=pdmvOS' >/dev/null
chmod 600 "$keydir/password" "$keydir/pdmvos-release.p12"
echo 'Release key created in .local/signing. Back up this folder privately; it is required for future updates.'
fi
"$JAVA_HOME/bin/keytool" -exportcert -keystore "$keydir/pdmvos-release.p12" -storepass:file "$keydir/password" -alias pdmvos -file "$keydir/certificate.der" >/dev/null 2>&1
python3 - "$keydir/certificate.der" <<'PY'
import hashlib,json,sys
from pathlib import Path
fingerprint=hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest()
pin=Path('release-signing.json')
if pin.exists():
    if json.loads(pin.read_text())['certificateSha256'].lower()!=fingerprint:
        raise SystemExit('Signing certificate does not match the pinned release identity')
else:
    pin.write_text(json.dumps({'schema':1,'alias':'pdmvos','certificateSha256':fingerprint},indent=2)+'\n')
print('Public signing fingerprint verified')
PY
