#!/usr/bin/env python3
"""Bộ lệnh vận hành pdmvOS theo từng thiết bị. Cần Python 3 và Android platform-tools (adb)."""
import argparse
import ipaddress
import os
from pathlib import Path
import subprocess
import shutil
import urllib.error
import sys
from release_assets import ensure_apk

ROOT = Path(__file__).resolve().parents[1]


def device(value):
    # Bare IPv4 is shorthand for the conventional TCP ADB port; USB serials remain intact.
    try:
        address = ipaddress.ip_address(value)
        return f'{address}:5555' if address.version == 4 else f'[{address}]:5555'
    except ValueError:
        return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('devices', help='Liệt kê thiết bị ADB')
    for name, description in [('connect','Kết nối ADB'), ('install','Cài/cập nhật và áp dụng launcher'),
                              ('apply','Áp dụng lại launcher đã cài'), ('restore','Khôi phục launcher gốc'),
                              ('status','Kiểm tra Home và phiên bản'), ('wallpaper','Đổi hình nền'),
                              ('wallpaper-reset','Khôi phục hình nền đi kèm'), ('logs','Xem nhật ký launcher')]:
        command = commands.add_parser(name, help=description)
        command.add_argument('device', type=device, help='IP, IP:port hoặc USB serial; luôn chỉ định thiết bị')
        if name in ('install', 'apply'):
            command.add_argument('--disable-stock', action='store_true', help='Tắt gói launcher gốc sau khi lưu trạng thái khôi phục')
        if name == 'install':
            command.add_argument('--apk', type=Path, help='APK khác (mặc định bản phát hành đi kèm)')
        if name == 'wallpaper':
            command.add_argument('image', type=Path)
    args = parser.parse_args()
    if not shutil.which('adb'):
        parser.error('Cần Android Platform Tools (adb). Xem docs/installation.md')
    if args.command == 'devices':
        return subprocess.call(['adb', 'devices', '-l'])
    serial = args.device
    if args.command == 'connect':
        ready = subprocess.run(['adb','-s',serial,'get-state'],capture_output=True,text=True,timeout=15)
        if ready.returncode:
            subprocess.run(['adb','disconnect',serial],capture_output=True,text=True,timeout=15)
        result = subprocess.run(['adb','connect',serial],capture_output=True,text=True,timeout=35)
        output = (result.stdout+'\n'+result.stderr).strip()
        print(output)
        return 0 if result.returncode == 0 and 'connected to ' in output.lower() else 1
    if args.command == 'logs':
        return subprocess.call(['adb', '-s', serial, 'logcat', '-v', 'time', 'pdmvOS:I', 'AndroidRuntime:E', '*:S'])
    if args.command in ('wallpaper', 'wallpaper-reset'):
        image = str(args.image.expanduser().resolve()) if args.command == 'wallpaper' else '--reset'
        command = [sys.executable, str(ROOT/'tools/set-wallpaper.py'), image, '--serial', serial]
    else:
        command = [sys.executable, str(ROOT/'tools/launcher-control.py'),
                   {'install':'apply'}.get(args.command, args.command), serial]
        if args.command in ('install', 'apply') and args.disable_stock:
            command.append('--disable-stock')
        if args.command == 'install':
            properties = dict(line.split('=',1) for line in (ROOT/'version.properties').read_text().splitlines() if '=' in line)
            apk = args.apk.expanduser().resolve() if args.apk else ensure_apk(ROOT, properties)
            if not apk.is_file():
                parser.error('Không tìm thấy APK: '+str(apk))
            command += ['--apk',str(apk)]
    os.execv(sys.executable, command)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, urllib.error.URLError) as error:
        print('Lỗi: '+str(error), file=sys.stderr)
        sys.exit(1)
