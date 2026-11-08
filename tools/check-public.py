#!/usr/bin/env python3
"""Check tracked publication files and local Markdown links."""
from pathlib import Path
import hashlib
import re
import subprocess
import sys
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
files = [name for name in files if name]
if not files:
    raise SystemExit('Track publication files before running this check')
for name in files:
    path = Path(name)
    if name == 'rust/third_party/shairplay/airport.key':
        # This exact key is part of the public upstream AirTunes implementation.
        if hashlib.sha256((ROOT / path).read_bytes()).hexdigest() != '1a04883a6086368301382c6bae4074296929da9000b399dc616848f622293363':
            raise SystemExit('Unexpected upstream AirTunes key contents')
        continue
    if path.parts[0] in {'.local', 'dist', '.gradle', '.codex', '.agents'} or path.suffix.lower() in {'.p12', '.keystore', '.jks', '.key', '.pem', '.apk'}:
        raise SystemExit('Private or generated file tracked: ' + name)
    if '__pycache__' in path.parts or name.startswith('app/build/'):
        raise SystemExit('Build output tracked: ' + name)
    data = (ROOT / path).read_bytes()
    if b'\0' in data:
        continue
    text = data.decode('utf-8', errors='replace')
    for pattern in (r'-----BEGIN [A-Z ]*PRIVATE KEY-----', r'ghp_[A-Za-z0-9]{30,}', r'github_pat_[A-Za-z0-9_]{40,}', r'/' + r'Users/[^/\s]+/'):
        if re.search(pattern, text):
            raise SystemExit('Review possible private data in ' + name)
    if path.suffix == '.md':
        for target in re.findall(r'!?\[[^\]]*\]\(([^)]+)\)', text):
            if target.startswith(('http:', 'https:', '#', 'mailto:')):
                continue
            target = unquote(target.split('#', 1)[0].strip('<>'))
            if target and not (ROOT / path.parent / target).exists():
                raise SystemExit('Broken local link in ' + name + ': ' + target)
print('PASS: tracked files contain no signing keys, generated data or detected private paths; local Markdown links resolve')
