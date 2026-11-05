#!/usr/bin/env bash
# User-run fetch of upstream font: font data is intentionally NOT redistributed in this source ZIP.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import json, pathlib, sys, tempfile, urllib.request, zipfile, shutil
root=pathlib.Path(sys.argv[1]); dest=root/'app/src/main/assets/fonts/google_sans_flex.ttf'
api='https://api.github.com/repos/googlefonts/googlesans-flex/releases/tags/v5.000'
req=urllib.request.Request(api,headers={'User-Agent':'pdmvOS-font-setup','Accept':'application/vnd.github+json'})
try:
    with urllib.request.urlopen(req,timeout=25) as response:
        release=json.load(response)
    assets=[a for a in release.get('assets',[]) if a.get('name','').lower().endswith(('.zip','.ttf'))]
    if not assets:
        raise RuntimeError('No font assets found on the official release.')
    assets.sort(key=lambda a:(('android' not in a['name'].lower()),('.zip' not in a['name'].lower())))
    choice=assets[0]
    print('Official source:',choice['browser_download_url'])
    with tempfile.TemporaryDirectory(prefix='pdmvos-font-') as temp:
        temp=pathlib.Path(temp); src=temp/choice['name']
        req=urllib.request.Request(choice['browser_download_url'],headers={'User-Agent':'pdmvOS-font-setup'})
        with urllib.request.urlopen(req,timeout=90) as response, open(src,'wb') as file:
            shutil.copyfileobj(response,file)
        if src.suffix.lower()=='.zip':
            with zipfile.ZipFile(src) as z:
                # Extract only TTFs, ignore paths in ZIP to avoid traversal.
                fonts=[n for n in z.namelist() if n.lower().endswith('.ttf') and not n.endswith('/')]
                fonts.sort(key=lambda name:(('googlesansflex' not in name.replace(' ','').lower()),('italic' in name.lower()),len(name)))
                if not fonts:raise RuntimeError('Release archive contains no TTF file.')
                with z.open(fonts[0]) as input, open(temp/'chosen.ttf','wb') as output:
                    shutil.copyfileobj(input,output)
                src=temp/'chosen.ttf'
        dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(src,dest)
    print('Installed:',dest)
    print('License: SIL OFL 1.1 — https://github.com/googlefonts/googlesans-flex/blob/main/OFL.txt')
except Exception as e:
    print('Cannot auto-fetch Google Sans Flex:',e,file=sys.stderr)
    print('Manual: download the TTF from https://github.com/googlefonts/googlesans-flex/releases',file=sys.stderr)
    sys.exit(1)
PY

# Static instances avoid some vendor Android 9 variable-font shaping regressions.
"$ROOT/tools/prepare-font.sh" || echo 'Static font conversion failed; variable font remains available.' >&2
