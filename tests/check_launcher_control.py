#!/usr/bin/env python3
import contextlib
import io
import importlib.util
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('launcher_control',Path(__file__).resolve().parents[1]/'tools/launcher-control.py')
control=importlib.util.module_from_spec(spec);spec.loader.exec_module(control)

class Android:
    def __init__(self):
        self.home='stock.home/.Home';self.disabled=set();self.enabled=0;self.foreground=self.home
        self.remote=None;self.commands=[];self.deny=False;self.identity='hardware-001';self.start_fail=False;self.priority_lock=False
    def run(self,args,**kwargs):
        if args[:3]!=['adb','-s','qa-tv']:raise AssertionError('Wrong device')
        if args[3]=='push':self.remote=json.loads(Path(args[4]).read_text());return subprocess.CompletedProcess(args,0,'','')
        cmd=shlex.split(args[4]);self.commands.append(cmd);out='';rc=0
        if cmd==['am','get-current-user']:out='0'
        elif cmd==['getprop','ro.serialno']:out=self.identity
        elif cmd[:3]==['cmd','package','resolve-activity']:out=self.home
        elif cmd[:3]==['cmd','package','query-activities']:out='stock.home/.Home\ndev.pdmv.os/.MainActivity'
        elif cmd[:3]==['cmd','package','set-home-activity']:
            if not (self.priority_lock and cmd[-1]==control.HOME and 'stock.home' not in self.disabled):self.home=cmd[-1]
        elif cmd[:3]==['pm','list','packages']:
            out='\n'.join('package:'+p for p in (self.disabled if '-d' in cmd else ['stock.home','android','com.android.systemui']))
        elif cmd[:2]==['pm','path']:out='package:/data/app/dev.pdmv.os/base.apk'
        elif cmd[:2]==['dumpsys','package']:out='userId=10001\nUser 0: installed=true enabled='+str(self.enabled)
        elif cmd[:2]==['pm','disable-user']:
            if self.deny:rc=1;out='SecurityException: denied'
            else:self.disabled.add(cmd[-1]);self.enabled=3
        elif cmd[:2]==['pm','enable']:self.disabled.discard(cmd[-1]);self.enabled=1
        elif cmd[:2]==['pm','default-state']:self.disabled.discard(cmd[-1]);self.enabled=0
        elif cmd[:2]==['am','start']:
            if self.start_fail:rc=1;out='Error: Activity not found'
            else:self.foreground=cmd[-1]
        elif cmd==['dumpsys','activity','activities']:out='mResumedActivity: ActivityRecord{ '+self.foreground+' }'
        elif cmd[0]=='cat':
            if self.remote:out=json.dumps(self.remote)
            else:rc=1;out='No such file'
        elif cmd[:2]==['input','keyevent']:self.foreground=self.home
        elif cmd[0]=='chmod' or cmd[:2]==['am','force-stop']:pass
        else:raise AssertionError(cmd)
        return subprocess.CompletedProcess(args,rc,out,'')

class ControlTests(unittest.TestCase):
    def setUp(self):
        self.quiet=contextlib.redirect_stdout(io.StringIO());self.quiet.__enter__()
        self.temp=tempfile.TemporaryDirectory();control.STATE=Path(self.temp.name);self.android=Android()
        self.device=control.Device('qa-tv',self.android.run)
    def tearDown(self):self.temp.cleanup();self.quiet.__exit__(None,None,None)
    def test_apply_restore_and_repeat(self):
        self.device.apply(disable_stock=True);self.assertIn('stock.home',self.android.disabled)
        self.assertEqual(self.android.home,control.HOME)
        self.device.apply();self.assertEqual(self.device.load()['original_home'],'stock.home/.Home')
        self.device.restore();self.assertEqual(self.android.home,'stock.home/.Home')
        self.assertNotIn('stock.home',self.android.disabled);self.assertEqual(self.android.enabled,0)
    def test_default_keeps_stock_available(self):
        self.device.apply();self.assertEqual(self.android.home,control.HOME)
        self.assertFalse(any(c[:2]==['pm','disable-user'] for c in self.android.commands))
        self.device.restore();self.assertEqual(self.android.home,'stock.home/.Home')
    def test_priority_lock_without_opt_in_rolls_back(self):
        self.android.priority_lock=True
        with self.assertRaises(RuntimeError):self.device.apply()
        self.assertEqual(self.android.home,'stock.home/.Home')
        self.assertNotIn('stock.home',self.android.disabled)
    def test_stock_priority_can_override_until_disabled(self):
        self.android.priority_lock=True;self.device.apply(disable_stock=True)
        self.assertEqual(self.android.home,control.HOME)
        self.device.restore();self.assertEqual(self.android.home,'stock.home/.Home')
    def test_denied_disable_rolls_back(self):
        self.android.deny=True
        with self.assertRaises(RuntimeError):self.device.apply(disable_stock=True)
        self.assertEqual(self.android.home,'stock.home/.Home');self.assertNotIn('stock.home',self.android.disabled)
        self.assertEqual(self.device.load()['phase'],'restored')
    def test_device_backup_survives_host_loss(self):
        self.device.apply();self.device.path.unlink();self.device.restore()
        self.assertEqual(self.android.home,'stock.home/.Home')
    def test_wrong_hardware_rejected(self):
        self.device.apply();self.android.identity='other-hardware'
        other=control.Device('qa-tv',self.android.run)
        with self.assertRaises(RuntimeError):other.restore()
    def test_protected_home_never_disabled(self):
        self.android.home='com.android.systemui/.Home'
        with self.assertRaises(RuntimeError):self.device.apply()
        self.assertFalse(any(c[:2]==['pm','disable-user'] for c in self.android.commands))
    def test_missing_restore_backup_does_not_mutate(self):
        with self.assertRaises(RuntimeError):self.device.restore()
        self.assertFalse(any(c[:2] in (['pm','enable'],['pm','disable-user']) for c in self.android.commands))
    def test_explicit_enabled_state_restored(self):
        self.android.enabled=1;self.device.apply();self.device.restore();self.assertEqual(self.android.enabled,1)

if __name__=='__main__':unittest.main()
