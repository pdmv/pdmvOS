#!/usr/bin/env python3
"""Upload one local image through ADB; acknowledge only after complete TV-side preparation."""
import argparse
import hashlib
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('launcher_control',ROOT/'tools/launcher-control.py')
control=importlib.util.module_from_spec(spec);spec.loader.exec_module(control)
MAX_BYTES=25*1024*1024

def validated_image(value):
    image=Path(value).expanduser().resolve(strict=True)
    if not image.is_file() or not 0<image.stat().st_size<=MAX_BYTES:
        raise ValueError('Chọn tệp ảnh có dung lượng từ 1 byte đến 25 MiB')
    return image

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image',nargs='?',help='Đường dẫn ảnh trên máy tính')
    parser.add_argument('--serial',help='ADB serial; mặc định TV đã áp dụng gần nhất')
    parser.add_argument('--reset',action='store_true',help='Quay về wallpaper.jpg đóng gói trong APK')
    args=parser.parse_args()
    if bool(args.image)==args.reset:parser.error('Truyền một đường dẫn ảnh hoặc --reset')
    image=validated_image(args.image) if args.image else None
    expected=None
    if not args.serial:
        last=control.STATE/'last-target.json'
        if not last.exists():raise RuntimeError('Chưa lưu TV; thêm --serial SERIAL')
        expected=json.loads(last.read_text());serial=expected['serial']
    else:serial=args.serial
    ready=subprocess.run(['adb','-s',serial,'get-state'],capture_output=True,text=True,timeout=15)
    if ready.returncode and ':' in serial:subprocess.run(['adb','connect',serial],capture_output=True,text=True,timeout=35,check=True)
    device=control.Device(serial)
    if expected and (device.identity!=expected['identity'] or device.user!=str(expected['user'])):
        raise RuntimeError('TV/user khác với thiết bị đã lưu; truyền --serial rõ ràng')
    if not device.shell('pm','path','--user',device.user,'dev.pdmv.os').startswith('package:'):
        raise RuntimeError('Chưa cài launcher trên TV')
    package=device.shell('dumpsys','package','dev.pdmv.os')
    version=re.search(r'\bversionCode=(\d+)',package)
    if not version or int(version.group(1))<20901:
        raise RuntimeError('Cập nhật launcher lên 2.9.0-rc2 trở lên trước khi đổi ảnh bằng lệnh')
    token=uuid.uuid4().hex
    folder='/storage/emulated/'+device.user+'/Android/data/dev.pdmv.os/files/wallpaper-import'
    staged=folder+'/'+token+'.image';status=folder+'/'+token+'.json'
    device.shell('mkdir','-p',folder)
    try:
        arguments=['am','start','-W','--user',device.user,'-n','dev.pdmv.os/.WallpaperImportActivity',
                   '-a','dev.pdmv.os.action.SET_WALLPAPER','--es','upload_id',token]
        if image:
            digest=hashlib.sha256()
            with image.open('rb') as source:
                for block in iter(lambda:source.read(65536),b''):digest.update(block)
            device.adb('push',str(image),staged,timeout=120)
            arguments+=['--es','sha256',digest.hexdigest()]
        else:arguments+=['--ez','reset','true']
        device.shell(*arguments)
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            try:result=json.loads(device.shell('cat',status))
            except (RuntimeError,ValueError):time.sleep(.5);continue
            if result.get('state')=='applied':
                print('Đã khôi phục hình nền mặc định.' if args.reset else 'Đã đổi hình nền: '+str(image));return
            if result.get('state')=='error':raise RuntimeError(result.get('message','TV không xử lý được ảnh'))
            time.sleep(.5)
        raise RuntimeError('TV chưa xác nhận trong 120 giây. Cần launcher 2.9.0-rc2 trở lên.')
    finally:
        try:device.shell('rm','-f',staged,status)
        except RuntimeError:pass

if __name__=='__main__':
    try:main()
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as error:
        print('Lỗi: '+str(error),file=sys.stderr);sys.exit(1)
