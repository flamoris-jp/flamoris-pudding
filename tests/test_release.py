import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import tarfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


release = module('build_pudding_release')
credentials = module('create_agent_credentials')


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.archive = self.root / 'image.tar'
        self.revision = '1' * 40

    def fixture(self, *, release_value='0.3.0', user='10001:10001', architecture='amd64', duplicate=False):
        config = {'os': 'linux', 'architecture': architecture, 'config': {
            'User': user, 'Entrypoint': ['java', 'net.flamoris.pudding.ServiceMain'],
            'Labels': {'org.opencontainers.image.title': 'flamoris-pudding',
                       'org.opencontainers.image.version': release_value,
                       'org.opencontainers.image.revision': self.revision}}}
        data = json.dumps(config).encode()
        digest = hashlib.sha256(data).hexdigest()
        with tarfile.open(self.archive, 'w') as archive:
            for name, body in [(digest + '.json', data), ('manifest.json', json.dumps([{'Config': digest + '.json'}]).encode())]:
                item = tarfile.TarInfo(name); item.size = len(body); archive.addfile(item, io.BytesIO(body))
            if duplicate:
                body = b'[]'; item = tarfile.TarInfo('manifest.json'); item.size = len(body); archive.addfile(item, io.BytesIO(body))
        return 'sha256:' + digest

    def catalog(self, image, **kwargs):
        values = dict(archive=self.archive, image_id=image, release='0.3.0', revision=self.revision,
                      platform='linux/amd64', base_url='https://github.com/flamoris-jp/flamoris-pudding/releases/download/v0.3.0')
        values.update(kwargs)
        return release.make_catalog(**values)[0]

    def test_candidate_binds_digest_and_operator_settings_without_secrets(self):
        image = self.fixture(); catalog = self.catalog(image); recipe = catalog['recipes'][0]
        self.assertEqual(recipe['application']['image_id'], image)
        self.assertEqual(recipe['application']['network'], 'bridge')
        self.assertEqual(recipe['application']['health_url'], 'http://127.0.0.1:${PORT}/health/ready')
        self.assertEqual(recipe['compatible_from'], [])
        self.assertTrue(next(s for s in recipe['settings'] if s['key'] == 'AGENT_TOKEN')['generate'])
        self.assertTrue(all(not s['default'] for s in recipe['settings'] if s['secret']))
        self.assertEqual(next(iter(recipe['downloads'].values()))['digest'], 'sha256:' + hashlib.sha256(self.archive.read_bytes()).hexdigest())
        output = self.root / 'candidate'
        with patch('sys.argv', ['build_pudding_release.py', '--image-tar', str(self.archive),
                               '--release', '0.3.0', '--revision', self.revision,
                               '--platform', 'linux/amd64', '--base-url',
                               'https://github.com/flamoris-jp/flamoris-pudding/releases/download/v0.3.0',
                               '--output', str(output)]), patch('sys.stdout', new_callable=io.StringIO):
            release.main()
        candidate = json.loads((output / 'candidate.json').read_text())
        self.assertEqual(candidate['image_id'], image)
        self.assertEqual(json.loads((output / 'catalog.json').read_text())['recipes'][0]['application']['image_id'], image)
        self.assertEqual((output / 'linux-amd64--image.tar').read_bytes(), self.archive.read_bytes())
        self.assertFalse(candidate['published'])

    def test_runtime_and_archive_identity_rejections(self):
        for values in ({'user': '0'}, {'architecture': 'arm64'}, {'release_value': '0.3.1'}, {'duplicate': True}):
            with self.subTest(values=values), self.assertRaises(ValueError):
                self.catalog(self.fixture(**values))
        image = self.fixture()
        for options in ({'image_id': 'sha256:' + '2' * 64}, {'base_url': 'http://github.com/flamoris-jp/flamoris-pudding/releases/download/v0.3.0'},
                        {'base_url': 'https://github.com/other/repo/releases/download/v0.3.0'}, {'revision': 'main'}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                self.catalog(image, **options)

    def test_credentials_are_private_unique_and_never_overwritten(self):
        path = self.root / 'server.json'; clients = self.root / 'clients'
        credentials.generate(['fixture-a', 'fixture-b'], path, clients)
        records = json.loads(path.read_text())
        self.assertEqual(len({r['token'] for r in records}), 2)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(clients.stat().st_mode & 0o777, 0o700)
        for record in records:
            exported = clients / (record['agent_id'] + '.token')
            self.assertEqual(exported.read_text().strip(), record['token'])
            self.assertEqual(exported.stat().st_mode & 0o777, 0o600)
        before = path.read_bytes()
        with self.assertRaises(ValueError):
            credentials.generate(['fixture-c'], path, clients)
        self.assertEqual(path.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
