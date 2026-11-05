#!/usr/bin/env python3
"""Transactional, per-device Android Home deployment. Requires authorized ADB."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
STATE = Path(os.environ.get('PDMVOS_DEVICE_STATE_DIR', ROOT / '.local/devices'))
PACKAGE = 'dev.pdmv.os'
HOME = PACKAGE + '/.MainActivity'
PROTECTED = {'android', 'com.android.systemui', 'com.android.settings', 'com.android.tv.settings',
             'com.android.permissioncontroller', 'com.google.android.permissioncontroller', PACKAGE}
COMPONENT = re.compile(r'^[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+$')


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_suffix('.tmp')
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), 'w') as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
        out.flush()
        os.fsync(out.fileno())
    temporary.replace(path)


def canonical(component):
    if not COMPONENT.fullmatch(component):
        raise RuntimeError('Thành phần Home không hợp lệ: ' + component)
    package, name = component.split('/', 1)
    return package + '/' + (package + name if name.startswith('.') else name)


class Device:
    def __init__(self, serial, runner=subprocess.run):
        self.serial, self.runner = serial, runner
        self.user = self.shell('am', 'get-current-user').strip()
        if not self.user.isdecimal():
            raise RuntimeError('Không xác định được Android user')
        self.identity = self.shell('getprop', 'ro.serialno').strip()
        if not self.identity or self.identity.lower() in ('unknown', 'null'):
            raise RuntimeError('TV không cung cấp mã định danh ổn định; dừng để tránh khôi phục nhầm thiết bị')
        identifier = hashlib.sha256((self.identity + ':' + self.user).encode()).hexdigest()[:24]
        self.path = STATE / (identifier + '.json')
        self.remote = '/data/local/tmp/pdmvos-home-' + self.user + '.json'

    def adb(self, *args, timeout=35):
        result = self.runner(['adb', '-s', self.serial, *args], capture_output=True, text=True, timeout=timeout)
        output = (result.stdout + '\n' + result.stderr).strip()
        if result.returncode or re.search(r'(?im)^(?:error:|failure|exception|securityexception|java\.lang\.)', output):
            raise RuntimeError(output or 'Lệnh ADB thất bại')
        return output.replace('\r', '')

    def shell(self, *args):
        return self.adb('shell', shlex.join(args))

    def current(self):
        output = self.shell('cmd', 'package', 'resolve-activity', '--brief', '--user', self.user,
                            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
        return next((line.strip() for line in reversed(output.splitlines()) if COMPONENT.fullmatch(line.strip())), '')

    def packages(self, flag):
        output = self.shell('pm', 'list', 'packages', flag, '--user', self.user)
        return {line[8:].strip() for line in output.splitlines() if line.startswith('package:')}

    def enabled_state(self, package):
        dump = self.shell('dumpsys', 'package', package)
        block = re.search(r'(?m)^\s*User ' + re.escape(self.user) + r':(.*?)(?=\n\s*User \d+:|\Z)', dump, re.S)
        match = re.search(r'\benabled=(\d+)\b', block.group(1)) if block else None
        if not match or match.group(1) not in ('0', '1'):
            raise RuntimeError('Không xác định được trạng thái bật gốc của ' + package)
        return int(match.group(1))

    def load(self):
        if self.path.exists():
            record = json.loads(self.path.read_text())
        else:
            try:
                record = json.loads(self.shell('cat', self.remote))
            except (RuntimeError, ValueError):
                return None
        if record.get('schema') != 1 or record.get('identity') != self.identity or str(record.get('user')) != self.user:
            raise RuntimeError('Bản lưu không khớp TV/user này')
        original = record.get('original_home', '')
        canonical(original)
        stock = record.get('stock_package', '')
        if stock != original.split('/')[0] or stock in PROTECTED or record.get('stock_enabled') not in (0, 1):
            raise RuntimeError('Bản lưu launcher gốc không hợp lệ')
        return record

    def save(self, record):
        record['transport'] = self.serial
        atomic_json(self.path, record)
        atomic_json(STATE / 'last-target.json', {'serial': self.serial, 'identity': self.identity, 'user': self.user})
        self.adb('push', str(self.path), self.remote)
        self.shell('chmod', '600', self.remote)

    def set_home(self, component, verify=True):
        canonical(component)
        self.shell('cmd', 'package', 'set-home-activity', '--user', self.user, component)
        if not verify:
            return
        actual = self.current()
        if not actual or canonical(actual) != canonical(component):
            raise RuntimeError('Home chưa nhận lựa chọn: ' + actual)

    def start(self, component):
        canonical(component)
        self.shell('am', 'start', '-W', '--user', self.user, '-n', component)
        self.wait_foreground(component.split('/')[0])

    def wait_foreground(self, package):
        for _ in range(16):
            text = self.shell('dumpsys', 'activity', 'activities')
            if any(package + '/' in line and ('mResumedActivity' in line or 'topResumedActivity' in line) for line in text.splitlines()):
                return
            time.sleep(.5)
        raise RuntimeError('Launcher chưa vào foreground: ' + package)

    def restore_original(self, record):
        package = record['stock_package']
        self.shell('pm', 'enable', '--user', self.user, package)
        if package in self.packages('-d'):
            raise RuntimeError('Chưa bật lại được launcher hệ thống')
        self.set_home(record['original_home'])
        if record['stock_enabled'] == 0:
            self.shell('pm', 'default-state', '--user', self.user, package)
        self.start(record['original_home'])
        self.shell('am', 'force-stop', '--user', self.user, PACKAGE)
        record['phase'] = 'restored'
        self.save(record)

    def apply(self, apk=None, disable_stock=False):
        if apk:
            apk = Path(apk).resolve(strict=True)
            self.adb('install', '--no-streaming', '--user', self.user, '-r', str(apk), timeout=120)
        if not self.shell('pm', 'path', '--user', self.user, PACKAGE).startswith('package:'):
            raise RuntimeError('Cài pdmvOS trước khi áp dụng Home')
        if PACKAGE in self.packages('-d'):
            raise RuntimeError('pdmvOS đang bị tắt; bật lại ứng dụng trước')
        record = self.load()
        if record is None:
            original = self.current()
            canonical(original)
            package = original.split('/')[0]
            if package in PROTECTED or package not in self.packages('-s'):
                raise RuntimeError('Home hiện tại không phải launcher hệ thống riêng biệt; chọn lại launcher gốc trước')
            candidates = self.shell('cmd', 'package', 'query-activities', '--brief', '--components', '--user', self.user,
                                    '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
            if not any(COMPONENT.fullmatch(line.strip()) and canonical(line.strip()) == canonical(original) for line in candidates.splitlines()):
                raise RuntimeError('Launcher gốc không được đăng ký cho nút Home')
            record = {'schema': 1, 'identity': self.identity, 'user': self.user,
                      'original_home': original, 'stock_package': package,
                      'stock_enabled': self.enabled_state(package), 'phase': 'saved'}
        self.save(record)  # Recovery exists on both host and TV before any Home mutation.
        try:
            self.start(HOME)
            self.set_home(HOME, verify=False)
            record['phase'] = 'home-requested'
            self.save(record)
            if disable_stock:
                dump = self.shell('dumpsys', 'package', record['stock_package'])
                uid = re.search(r'\buserId=(\d+)\b', dump)
                if not uid or int(uid.group(1)) < 10000 or re.search(r'\bsharedUser=', dump):
                    raise RuntimeError('Không thể tắt an toàn gói Home dùng UID hệ thống hoặc UID chia sẻ')
                self.shell('pm', 'disable-user', '--user', self.user, record['stock_package'])
                if record['stock_package'] not in self.packages('-d'):
                    raise RuntimeError('Hệ thống không cho vô hiệu hóa launcher gốc')
            self.set_home(HOME)
            self.shell('input', 'keyevent', '3')
            self.wait_foreground(PACKAGE)
            record['phase'] = 'active'
            self.save(record)
        except Exception as failure:
            try:
                self.restore_original(record)
                recovery = 'Đã hoàn tác về launcher gốc.'
            except Exception as rollback:
                recovery = 'Hoàn tác chưa hoàn tất: ' + str(rollback) + '\nChạy ./tools/restore-launcher.sh ' + self.serial
            raise RuntimeError(str(failure) + '\n' + recovery) from failure
        print('Đã đặt pdmvOS làm Home.')
        if record['stock_package'] in self.packages('-d'):
            print('Launcher gốc đang tắt: ' + record['stock_package'])
        print('Khôi phục: ./pdmvos restore ' + self.serial)

    def restore(self):
        record = self.load()
        if not record:
            raise RuntimeError('Không tìm thấy bản lưu launcher gốc cho TV/user này')
        self.restore_original(record)
        print('Đã khôi phục launcher gốc: ' + record['original_home'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['status', 'apply', 'restore'])
    parser.add_argument('serial', nargs='?')
    parser.add_argument('--apk')
    parser.add_argument('--disable-stock', action='store_true')
    args = parser.parse_args()
    serial = args.serial
    if not serial:
        if args.mode == 'apply':
            parser.error('apply cần SERIAL rõ ràng')
        last = STATE / 'last-target.json'
        if not last.exists():
            parser.error('Chưa có TV đã lưu; truyền SERIAL của TV')
        saved = json.loads(last.read_text())
        serial = saved['serial']
    ready = subprocess.run(['adb','-s',serial,'get-state'],capture_output=True,text=True,timeout=15)
    if 'unauthorized' in (ready.stdout + ready.stderr).lower():
        raise RuntimeError('Chấp nhận yêu cầu gỡ lỗi trên TV rồi chạy lại lệnh')
    if (ready.returncode or ready.stdout.strip() != 'device') and re.fullmatch(r'(?:[A-Za-z0-9_.-]+|\[[0-9a-fA-F:]+\]):[0-9]+',serial):
        subprocess.run(['adb','disconnect',serial],capture_output=True,text=True,timeout=15)
        subprocess.run(['adb','connect',serial],capture_output=True,text=True,timeout=35,check=True)
    device = Device(serial)
    if not args.serial and (device.identity != saved['identity'] or device.user != str(saved['user'])):
        raise RuntimeError('Địa chỉ ADB đang trỏ sang TV/user khác; truyền đúng SERIAL')
    if args.mode == 'apply':
        device.apply(args.apk, disable_stock=args.disable_stock)
    elif args.mode == 'restore':
        device.restore()
    else:
        record = device.load()
        print('Thiết bị: ' + serial + ' · ' + device.shell('getprop','ro.product.model'))
        print('Android: ' + device.shell('getprop','ro.build.version.release'))
        print('Home hiện tại: ' + device.current())
        dump = device.shell('dumpsys','package',PACKAGE)
        for line in dump.splitlines():
            if 'versionName=' in line or 'versionCode=' in line:
                print(line.strip())
        print('Bản lưu: ' + (str(device.path) if record else 'chưa có'))
        if record:
            print('Launcher gốc: ' + record['stock_package'] + ' · ' + ('đã tắt' if record['stock_package'] in device.packages('-d') else 'đang bật'))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print('Lỗi: ' + str(error), file=sys.stderr)
        sys.exit(1)
