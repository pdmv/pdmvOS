import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import release_assets


class ReleaseAssets(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.version = {'versionName': '3.1.3', 'versionCode': '30109'}
        self.pin = {'certificateSha256': 'a' * 64}
        (self.root / 'release-signing.json').write_text(json.dumps(self.pin))
        self.name = 'pdmvOS-3.1.3'
        self.content = b'APK test fixture'
        self.digest = hashlib.sha256(self.content).hexdigest()
        self.metadata = {'version': '3.1.3', 'versionCode': 30109, 'sha256': self.digest, 'signerSha256': 'a' * 64}

    def populate(self, directory):
        directory.mkdir(exist_ok=True)
        (directory / (self.name + '.apk')).write_bytes(self.content)
        (directory / (self.name + '.apk.sha256')).write_text(self.digest + '  ' + self.name + '.apk\n')
        (directory / (self.name + '.release.json')).write_text(json.dumps(self.metadata))

    def download(self, url, destination, limit):
        if url.endswith('.apk.sha256'):
            destination.write_text(self.digest + '  ' + self.name + '.apk\n')
        elif url.endswith('.release.json'):
            destination.write_text(json.dumps(self.metadata))
        else:
            destination.write_bytes(self.content)

    def test_cached_verified_release_needs_no_download(self):
        self.populate(self.root / 'dist')
        with patch.object(release_assets, 'download') as download:
            self.assertEqual(release_assets.ensure_apk(self.root, self.version).read_bytes(), self.content)
        download.assert_not_called()

    def test_corrupt_cache_is_replaced_after_verification(self):
        self.populate(self.root / 'dist')
        (self.root / 'dist' / (self.name + '.apk')).write_bytes(b'corrupt')
        with patch.object(release_assets, 'download', side_effect=self.download) as download:
            self.assertEqual(release_assets.ensure_apk(self.root, self.version).read_bytes(), self.content)
        self.assertEqual(download.call_count, 3)

    def test_untrusted_metadata_never_publishes_apk(self):
        self.metadata['signerSha256'] = 'b' * 64
        with patch.object(release_assets, 'download', side_effect=self.download), self.assertRaises(ValueError):
            release_assets.ensure_apk(self.root, self.version)
        self.assertFalse((self.root / 'dist' / (self.name + '.apk')).exists())

    def test_malformed_metadata_is_rejected(self):
        self.populate(self.root / 'dist')
        (self.root / 'dist' / (self.name + '.release.json')).write_text('[]')
        with self.assertRaises(ValueError):
            release_assets.verify_apk(self.root / 'dist', self.version, self.pin)

    def test_release_repository_rejects_urls(self):
        with patch.dict(release_assets.os.environ, {'PDMVOS_RELEASE_REPOSITORY': 'https://example.com/other'}), self.assertRaises(ValueError):
            release_assets.release_repository()


if __name__ == '__main__':
    unittest.main()
