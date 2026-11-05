import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('wallpaper_tool',Path(__file__).resolve().parents[1]/'tools/set-wallpaper.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)

class TV:
    user='0';identity='hardware';version=20901;result='applied'
    def __init__(self):self.calls=[]
    def shell(self,*args):
        self.calls.append(args)
        if args[:2]==('pm','path'):return 'package:/data/app/dev.pdmv.os/base.apk'
        if args[:2]==('dumpsys','package'):return 'versionCode='+str(self.version)
        if args[0]=='cat':return json.dumps({'state':self.result,'message':'Invalid image'})
        return ''
    def adb(self,*args,**kwargs):self.calls.append(args);return ''

class ToolTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.image=Path(self.temp.name)/'ảnh có khoảng trắng.png';self.image.write_bytes(b'fixture')
        self.tv=TV()
    def tearDown(self):self.temp.cleanup()
    def run_tool(self,*args):
        with patch.object(sys,'argv',['set-wallpaper.py',*args]),patch.object(tool.control,'Device',return_value=self.tv),patch.object(tool.subprocess,'run',return_value=subprocess.CompletedProcess([],0,'device','')),contextlib.redirect_stdout(io.StringIO()):tool.main()
    def test_path_and_ack(self):
        self.run_tool(str(self.image),'--serial','qa-tv')
        push=next(c for c in self.tv.calls if c[0]=='push');self.assertEqual(push[1],str(self.image.resolve()))
        self.assertTrue(any(c[:2]==('rm','-f') for c in self.tv.calls))
    def test_device_error_is_not_success(self):
        self.tv.result='error'
        with self.assertRaisesRegex(RuntimeError,'Invalid image'):self.run_tool(str(self.image),'--serial','qa-tv')
    def test_old_launcher_fails_before_upload(self):
        self.tv.version=20900
        with self.assertRaisesRegex(RuntimeError,'Cập nhật'):self.run_tool(str(self.image),'--serial','qa-tv')
        self.assertFalse(any(c[0]=='push' for c in self.tv.calls))
    def test_reset_has_no_upload(self):
        self.run_tool('--reset','--serial','qa-tv');self.assertFalse(any(c[0]=='push' for c in self.tv.calls))
    def test_invalid_local_size(self):
        self.image.write_bytes(b'')
        with self.assertRaises(ValueError):tool.validated_image(str(self.image))
        with self.image.open('wb') as f:f.truncate(tool.MAX_BYTES+1)
        with self.assertRaises(ValueError):tool.validated_image(str(self.image))

if __name__=='__main__':unittest.main()
