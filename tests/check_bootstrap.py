import importlib.util
from pathlib import Path
import stat
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('bootstrap', ROOT / 'install.py')
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class BootstrapArchive(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.archive = self.root / 'kit.zip'
        self.destination = self.root / 'extracted'

    def test_extracts_valid_kit(self):
        with zipfile.ZipFile(self.archive, 'w') as archive:
            archive.writestr('pdmvOS-3.1.3/tools/pdmvos.py', 'print("ready")\n')
        bootstrap.extract_kit(self.archive, self.destination, 'pdmvOS-3.1.3')
        self.assertTrue((self.destination / 'pdmvOS-3.1.3/tools/pdmvos.py').is_file())

    def test_rejects_parent_traversal(self):
        with zipfile.ZipFile(self.archive, 'w') as archive:
            archive.writestr('../escaped.py', 'bad')
        with self.assertRaises(ValueError):
            bootstrap.extract_kit(self.archive, self.destination, 'pdmvOS-3.1.3')
        self.assertFalse((self.root / 'escaped.py').exists())

    def test_rejects_symlinks(self):
        entry = zipfile.ZipInfo('pdmvOS-3.1.3/link')
        entry.create_system = 3
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        with zipfile.ZipFile(self.archive, 'w') as archive:
            archive.writestr(entry, '/outside')
        with self.assertRaises(ValueError):
            bootstrap.extract_kit(self.archive, self.destination, 'pdmvOS-3.1.3')


if __name__ == '__main__':
    unittest.main()
