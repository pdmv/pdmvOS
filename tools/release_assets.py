"""Download versioned official APKs and verify release metadata and SHA-256."""
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.request

DEFAULT_REPOSITORY = 'pdmv/pdmvOS'


def release_repository():
    repository = os.environ.get('PDMVOS_RELEASE_REPOSITORY', DEFAULT_REPOSITORY)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Tên repo release không hợp lệ')
    return repository


def download(url, destination, limit):
    request = urllib.request.Request(url, headers={'User-Agent': 'pdmvOS-installer'})
    size = 0
    with urllib.request.urlopen(request, timeout=45) as response, open(destination, 'wb') as output:
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            size += len(block)
            if size > limit:
                raise ValueError('Tệp tải về vượt kích thước cho phép')
            output.write(block)


def verify_apk(directory, version, signing):
    filename = 'pdmvOS-' + version['versionName'] + '.apk'
    apk = directory / filename
    fields = (directory / (filename + '.sha256')).read_text().strip().split()
    if len(fields) != 2 or fields[1].lstrip('*') != filename or not re.fullmatch(r'[0-9a-fA-F]{64}', fields[0]):
        raise ValueError('Tệp SHA-256 không hợp lệ')
    metadata = json.loads((directory / apk.with_suffix('.release.json').name).read_text())
    if not isinstance(metadata, dict) or not isinstance(metadata.get('signerSha256'), str):
        raise ValueError('Metadata release không hợp lệ')
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    if digest != fields[0].lower() or digest != metadata.get('sha256'):
        raise ValueError('APK không khớp SHA-256 của bản phát hành')
    if metadata.get('version') != version['versionName'] or metadata.get('versionCode') != int(version['versionCode']):
        raise ValueError('Thông tin phiên bản APK không khớp')
    if metadata.get('signerSha256', '').lower() != signing['certificateSha256'].lower():
        raise ValueError('Metadata không khớp chứng chỉ phát hành của dự án')
    return apk


def ensure_apk(root, version):
    directory = root / 'dist'
    signing = json.loads((root / 'release-signing.json').read_text())
    try:
        return verify_apk(directory, version, signing)
    except (OSError, ValueError, KeyError):
        pass
    directory.mkdir(parents=True, exist_ok=True)
    repository = release_repository()
    name = 'pdmvOS-' + version['versionName']
    base = 'https://github.com/' + repository + '/releases/download/v' + version['versionName'] + '/'
    print('Đang tải pdmvOS ' + version['versionName'] + ' từ ' + repository, flush=True)
    with tempfile.TemporaryDirectory(prefix='.download-', dir=directory) as temporary:
        staging = Path(temporary)
        for filename, limit in [(name + '.apk.sha256', 4096), (name + '.release.json', 65536), (name + '.apk', 64 * 1024 * 1024)]:
            download(base + filename, staging / filename, limit)
        verify_apk(staging, version, signing)
        for filename in (name + '.apk.sha256', name + '.release.json', name + '.apk'):
            os.replace(staging / filename, directory / filename)
    return directory / (name + '.apk')
