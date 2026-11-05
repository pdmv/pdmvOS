import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
spec=importlib.util.spec_from_file_location('commands',ROOT/'tools/pdmvos.py')
cli=importlib.util.module_from_spec(spec);spec.loader.exec_module(cli)

class Commands(unittest.TestCase):
    def setUp(self):
        self.adb=patch.object(cli.shutil,'which',return_value='/test/adb');self.adb.start();self.addCleanup(self.adb.stop)
    def test_addresses(self):
        self.assertEqual(cli.device('10.0.0.113'),'10.0.0.113:5555')
        self.assertEqual(cli.device('10.0.0.113:1234'),'10.0.0.113:1234')
        self.assertEqual(cli.device('USB_SERIAL'),'USB_SERIAL')
    def test_target_required(self):
        with patch.object(sys,'argv',['pdmvos','restore']),self.assertRaises(SystemExit) as e:cli.main()
        self.assertEqual(e.exception.code,2)
    def test_explicit_routes(self):
        for action,mode in [('restore','restore'),('status','status'),('apply','apply'),('install','apply')]:
            with patch.object(sys,'argv',['pdmvos',action,'10.0.0.113']),patch.object(cli.os,'execv') as call,patch.object(Path,'is_file',return_value=True),patch.object(cli,'ensure_apk',return_value=ROOT/'dist/test.apk'):
                cli.main();args=call.call_args.args[1]
                self.assertEqual(args[2:4],[mode,'10.0.0.113:5555'])
    def test_connect_refreshes_only_target(self):
        import subprocess
        responses=[subprocess.CompletedProcess([],1,'','offline'),subprocess.CompletedProcess([],0,'',''),subprocess.CompletedProcess([],0,'connected to 10.0.0.113:5555','')]
        with patch.object(sys,'argv',['pdmvos','connect','10.0.0.113']),patch.object(cli.subprocess,'run',side_effect=responses) as call:
            self.assertEqual(cli.main(),0)
            self.assertEqual(call.call_args_list[1].args[0],['adb','disconnect','10.0.0.113:5555'])
    def test_wallpaper_path(self):
        with patch.object(sys,'argv',['pdmvos','wallpaper','10.0.0.113','ảnh có dấu.png']),patch.object(cli.os,'execv') as call:
            cli.main();args=call.call_args.args[1]
            self.assertEqual(args[-2:],['--serial','10.0.0.113:5555'])
            self.assertTrue(args[2].endswith('ảnh có dấu.png'))

if __name__=='__main__':unittest.main()
