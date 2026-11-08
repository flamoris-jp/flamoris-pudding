import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
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
with patch('sys.path', [str(ROOT / 'scripts'), *sys.path]):
    promotion = module('prepare_pudding_release')


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

    def promotion_candidate(self):
        directory = Path(tempfile.mkdtemp(prefix='candidate-', dir=self.root))
        image = self.fixture()
        catalog = self.catalog(image)
        (directory / promotion.IMAGE).write_bytes(self.archive.read_bytes())
        (directory / 'catalog.json').write_text(json.dumps(catalog))
        (directory / 'candidate.json').write_text(json.dumps({
            'application_id': 'flamoris-pudding', 'release': '0.3.0',
            'source_revision': self.revision, 'platform': 'linux/amd64',
            'image_id': image, 'published': False,
            'artifacts': catalog['recipes'][0]['downloads']}))
        return directory

    def successful_runs(self):
        return {'workflow_runs': [dict(id=index, run_attempt=1, path=path, status='completed',
                                      conclusion='success', head_sha=self.revision,
                                      head_branch='main', event='push',
                                      repository={'full_name': promotion.REPOSITORY},
                                      head_repository={'full_name': promotion.REPOSITORY})
                                  for index, path in enumerate(sorted(promotion.WORKFLOWS), 1)]}

    def test_release_preserves_tested_bytes_and_checksums(self):
        candidate = self.promotion_candidate(); output = self.root / 'release'
        promotion.prepare(candidate, output, self.revision, '0.3.0')
        for name in promotion.ASSETS:
            self.assertEqual((candidate / name).read_bytes(), (output / name).read_bytes())
        manifest = (output / 'SHA256SUMS').read_text()
        self.assertEqual(manifest, promotion.verify_candidate(output, self.revision, '0.3.0'))
        self.assertIn(self.revision, (output / 'release-notes.md').read_text())
        with self.assertRaises(ValueError):
            promotion.prepare(candidate, output, self.revision, '0.3.0')

    def test_release_rejects_provenance_recipe_and_image_changes(self):
        for key, value in [('source_revision', '2' * 40), ('release', '0.3.1'),
                           ('platform', 'linux/arm64'), ('image_id', 'sha256:' + '2' * 64),
                           ('published', True)]:
            directory = self.promotion_candidate()
            path = directory / 'candidate.json'; data = json.loads(path.read_text()); data[key] = value
            path.write_text(json.dumps(data))
            with self.subTest(key=key), self.assertRaises(ValueError):
                promotion.verify_candidate(directory, self.revision, '0.3.0')
        directory = self.promotion_candidate(); path = directory / 'catalog.json'
        data = json.loads(path.read_text()); data['recipes'][0]['settings'][0]['default'] = '9999'
        path.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            promotion.verify_candidate(directory, self.revision, '0.3.0')
        directory = self.promotion_candidate()
        with (directory / promotion.IMAGE).open('ab') as stream:
            stream.write(b'changed archive bytes')
        with self.assertRaises(ValueError):
            promotion.verify_candidate(directory, self.revision, '0.3.0')

    def test_release_rejects_extra_files_symlinks_duplicate_json_and_forged_manifest(self):
        for change in ('extra', 'symlink', 'duplicate', 'manifest'):
            directory = self.promotion_candidate()
            if change == 'extra':
                (directory / 'runtime.env').write_text('private fixture')
            elif change == 'symlink':
                (directory / promotion.IMAGE).unlink()
                (directory / promotion.IMAGE).symlink_to(self.archive)
            elif change == 'duplicate':
                path = directory / 'candidate.json'
                path.write_text(path.read_text()[:-1] + ', "published": false}')
            else:
                (directory / 'SHA256SUMS').write_text('0' * 64 + '  catalog.json\n')
            with self.subTest(change=change), self.assertRaises(ValueError):
                promotion.verify_candidate(directory, self.revision, '0.3.0')

    def test_release_selects_successful_main_workflows(self):
        runs = self.successful_runs()
        expected = next(r['id'] for r in runs['workflow_runs'] if r['path'].endswith('standalone.yml'))
        self.assertEqual(promotion.select_tested_run(runs, self.revision), expected)
        missing = self.successful_runs(); missing['workflow_runs'].pop()
        with self.assertRaises(ValueError):
            promotion.select_tested_run(missing, self.revision)
        for key, value in [('event', 'pull_request'), ('head_branch', 'other'),
                           ('head_sha', '2' * 40), ('status', 'in_progress'),
                           ('conclusion', 'failure'), ('repository', {'full_name': 'other/repo'}),
                           ('head_repository', {'full_name': 'other/repo'})]:
            invalid = self.successful_runs(); invalid['workflow_runs'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                promotion.select_tested_run(invalid, self.revision)

    def test_release_rejects_newer_failed_attempt_even_if_older_succeeded(self):
        for newer_run in (False, True):
            runs = self.successful_runs()
            failed = dict(runs['workflow_runs'][0], conclusion='failure', run_attempt=2)
            if newer_run:
                failed['id'] += 100
            runs['workflow_runs'].append(failed)
            with self.subTest(newer_run=newer_run), self.assertRaises(ValueError):
                promotion.select_tested_run(runs, self.revision)

    def test_release_cli_binds_tag_to_application_version(self):
        runs = self.root / 'runs.json'; runs.write_text(json.dumps(self.successful_runs()))
        version = self.root / 'version.txt'; version.write_text('0.3.0\n')
        output = self.root / 'outputs'
        arguments = ['prepare_pudding_release.py', 'select', '--runs-json', str(runs),
                     '--source-sha', self.revision, '--version-file', str(version),
                     '--tag', 'v0.3.0', '--github-output', str(output)]
        with patch('sys.argv', arguments), patch('sys.stdout', new_callable=io.StringIO):
            promotion.main()
        self.assertIn('release=0.3.0\n', output.read_text())
        before = output.read_bytes(); arguments[arguments.index('v0.3.0')] = 'v0.3.1'
        with patch('sys.argv', arguments), self.assertRaises(ValueError):
            promotion.main()
        self.assertEqual(output.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
