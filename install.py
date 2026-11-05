#!/usr/bin/env python3
"""Install the official pdmvOS release using Python 3 and Android Platform Tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile

REPOSITORY = 'pdmv/pdmvOS'


def download(url, destination, limit):
    request = urllib.request.Request(url, headers={'User-Agent': 'pdmvOS-installer'})
    size = 0
    with urllib.request.urlopen(request, timeout=45) as response, open(destination, 'wb') as output:
        while True:
            data = response.read(1024 * 1024)
            if not data:
                break
            size += len(data)
            if size > limit:
                raise ValueError('Tệp tải về vượt kích thước cho phép')
            output.write(data)


def extract_kit(archive, destination, directory_name):
    destination = destination.resolve()
    with zipfile.ZipFile(archive) as package:
        if sum(info.file_size for info in package.infolist()) > 128 * 1024 * 1024:
            raise ValueError('Bộ cài giải nén vượt kích thước cho phép')
        for info in package.infolist():
            path = (destination / info.filename).resolve()
            try:
                relative = path.relative_to(destination)
            except ValueError as error:
                raise ValueError('Bộ cài chứa đường dẫn không hợp lệ') from error
            if not relative.parts or relative.parts[0] != directory_name or stat.S_ISLNK(info.external_attr >> 16):
                raise ValueError('Cấu trúc bộ cài không hợp lệ')
        package.extractall(destination)


def run_tool(installed, store, args):
    environment = dict(os.environ)
    environment['PDMVOS_DEVICE_STATE_DIR'] = str(store / 'devices')
    command = [sys.executable, str(installed / 'tools/pdmvos.py'), 'restore' if args.restore else 'install', args.device]
    if args.disable_stock and not args.restore:
        command.append('--disable-stock')
    result = subprocess.call(command, env=environment)
    if not args.restore:
        interpreter = 'py' if os.name == 'nt' else 'python3'
        print('Khôi phục Home gốc bằng lệnh sau, với cùng thư mục lưu trạng thái.')
        restore = [interpreter, str(Path(__file__).resolve()), args.device, '--restore']
        print(shlex.join(restore))
        print('Bản lưu thiết bị: ' + environment['PDMVOS_DEVICE_STATE_DIR'])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('device', help='IP, IP:port hoặc USB serial của TV')
    parser.add_argument('--restore', action='store_true', help='Khôi phục Home gốc bằng bản lưu trên máy và TV')
    parser.add_argument('--version', help='Phiên bản cụ thể, ví dụ 3.1.3')
    parser.add_argument('--disable-stock', action='store_true', help='Tắt gói Home gốc sau khi lưu trạng thái khôi phục')
    args = parser.parse_args()
    if not shutil.which('adb'):
        parser.error('Cần adb trong PATH. Tải Android Platform Tools tại https://developer.android.com/tools/releases/platform-tools')
    repository = os.environ.get('PDMVOS_RELEASE_REPOSITORY', REPOSITORY)
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Tên repo release không hợp lệ')
    if os.name == 'nt':
        base = Path(os.environ.get('LOCALAPPDATA', str(Path.home() / 'AppData/Local')))
    else:
        base = Path(os.environ.get('XDG_DATA_HOME', str(Path.home() / '.local/share')))
    store = base / 'pdmvos'
    releases = store / 'releases'
    releases.mkdir(parents=True, exist_ok=True)
    if args.restore:
        candidates = []
        for candidate in releases.iterdir():
            match = re.fullmatch(r'pdmvOS-(\d+)\.(\d+)\.(\d+)', candidate.name)
            if match and (candidate / '.kit-sha256').is_file() and (candidate / 'tools/pdmvos.py').is_file():
                if not args.version or candidate.name == 'pdmvOS-' + args.version.removeprefix('v'):
                    candidates.append((tuple(map(int, match.groups())), candidate))
        if candidates:
            return run_tool(max(candidates)[1], store, args)
    if args.version:
        version = args.version.removeprefix('v')
    else:
        request = urllib.request.Request('https://api.github.com/repos/' + repository + '/releases/latest', headers={'User-Agent': 'pdmvOS-installer'})
        with urllib.request.urlopen(request, timeout=30) as response:
            version = json.loads(response.read(65536))['tag_name'].removeprefix('v')
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Phiên bản release không hợp lệ')
    directory_name = 'pdmvOS-' + version
    filename = directory_name + '-operating-kit.zip'
    base_url = 'https://github.com/' + repository + '/releases/download/v' + version + '/'
    print('Đang chuẩn bị pdmvOS ' + version, flush=True)
    with tempfile.TemporaryDirectory(prefix='.download-', dir=releases) as temporary:
        staging = Path(temporary)
        checksum = staging / (filename + '.sha256')
        download(base_url + filename + '.sha256', checksum, 4096)
        fields = checksum.read_text().strip().split()
        if len(fields) != 2 or fields[1].lstrip('*') != filename or not re.fullmatch(r'[0-9a-fA-F]{64}', fields[0]):
            raise ValueError('Tệp SHA-256 không hợp lệ')
        expected = fields[0].lower()
        installed = releases / directory_name
        marker = installed / '.kit-sha256'
        if not marker.is_file() or marker.read_text().strip() != expected:
            archive = staging / filename
            download(base_url + filename, archive, 64 * 1024 * 1024)
            if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
                raise ValueError('Bộ cài không khớp SHA-256')
            extract_kit(archive, staging, directory_name)
            prepared = staging / directory_name
            if not (prepared / 'tools/pdmvos.py').is_file():
                raise ValueError('Bộ cài thiếu công cụ pdmvOS')
            (prepared / '.kit-sha256').write_text(expected + '\n')
            if installed.exists():
                shutil.rmtree(installed)
            os.replace(prepared, installed)
    return run_tool(installed, store, args)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, urllib.error.URLError) as error:
        print('Lỗi: ' + str(error), file=sys.stderr)
        sys.exit(1)
