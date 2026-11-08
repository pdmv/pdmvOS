#!/usr/bin/env python3
"""Fail closed on mismatched version, debug flags, debug signer or bundled secrets."""
import hashlib,json,os,re,subprocess,sys,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[1]
apk=Path(sys.argv[1]).resolve()
sdk=Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','')))
bt=sdk/'build-tools/34.0.0'
version=dict(line.split('=',1) for line in (root/'version.properties').read_text().splitlines() if '=' in line)
def command(args):return subprocess.check_output(list(map(str,args)),text=True)
def require(condition,message):
    if not condition:raise RuntimeError(message)
badging=command([bt/'aapt2','dump','badging',apk])
require("name='dev.pdmv.os'" in badging and "versionCode='"+version['versionCode']+"'" in badging and "versionName='"+version['versionName']+"'" in badging,'Version/package mismatch')
require("targetSdkVersion:'35'" in badging,'Target SDK must be 35')
require('application-debuggable' not in badging and 'testOnly' not in badging,'Debug/test-only APK')
cert=command([Path(os.environ['JAVA_HOME'])/'bin/java','-jar',bt/'lib/apksigner.jar','verify','--print-certs',apk])
require('Android Debug' not in cert,'Release uses Android debug certificate')
fingerprint=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)',cert)
require(fingerprint,'Missing signer fingerprint')
pin=root/'release-signing.json'
require(pin.exists(),'Production certificate pin missing')
require(json.loads(pin.read_text())['certificateSha256'].lower()==fingerprint.group(1).lower(),'Production certificate does not match pinned release identity')
with zipfile.ZipFile(apk) as archive:
    names=archive.namelist()
    require(not any(re.search(r'(?:\.p12|\.jks|\.keystore|password|\.local/|tv-qa)',name,re.I) for name in names),'Bundled private/development data')
    require('assets/wallpapers/wallpaper.jpg' in names,'Wallpaper missing')
    require('assets/solar/CREDITS.txt' in names,'Solar attribution missing')
    require(len([n for n in names if n.endswith('.dex')])>0,'No application code')
    for abi, elf_class, machine in [('arm64-v8a', 2, 183), ('armeabi-v7a', 1, 40), ('x86_64', 2, 62)]:
        library = f'lib/{abi}/libpdmvos_core.so'
        require(names.count(library) == 1, f'Missing or duplicate Rust core for {abi}')
        with archive.open(library) as binary:
            header = binary.read(20)
        require(header[:6] == b'\x7fELF' + bytes([elf_class, 1])
                and int.from_bytes(header[18:20], 'little') == machine,
                f'Invalid Rust core architecture for {abi}')
report={'version':version['versionName'],'versionCode':int(version['versionCode']),
        'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'signerSha256':fingerprint.group(1),
        'bytes':apk.stat().st_size,'minSdk':int(re.search(r"sdkVersion:'([0-9]+)'",badging).group(1)),'targetSdk':int(re.search(r"targetSdkVersion:'([0-9]+)'",badging).group(1)),'debuggable':False,'releaseSignatureVerified':True,
        'privateDataBundled':False,'distribution':'Direct APK / ADB; target SDK 35; not Google Play submission'}
if '--verify' in sys.argv[2:]:
    require(json.loads(apk.with_suffix('.release.json').read_text())==report,'Published release metadata mismatch')
else:
    apk.with_suffix('.release.json').write_text(json.dumps(report,indent=2)+'\n')
print('PASS: release package, version, non-debug certificate, resources, no bundled signing key')
