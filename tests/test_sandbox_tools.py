import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('sandbox_credentials', ROOT / 'scripts/create_sandbox_credentials.py')
credentials = importlib.util.module_from_spec(spec)
spec.loader.exec_module(credentials)


class SandboxToolTests(unittest.TestCase):
    def test_human_identity_and_mock_key_are_private_separate_and_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'private'
            credentials.create(path, 'fixture-tenant', 'fixture-human')
            records = json.loads((path / 'human.json').read_text())
            self.assertEqual(records[0]['human_id'], 'fixture-human')
            self.assertEqual(credentials.private_token(path / 'human.token').decode(), records[0]['token'])
            self.assertGreaterEqual(len((path / 'mock.key').read_bytes()), 32)
            self.assertNotEqual((path / 'mock.key').read_bytes(), (path / 'human.token').read_bytes())
            for name in ('human.json', 'human.token', 'mock.key'):
                self.assertEqual(stat.S_IMODE((path / name).stat().st_mode), 0o600)
            before = (path / 'human.json').read_bytes()
            with self.assertRaises(FileExistsError):
                credentials.create(path, 'fixture-tenant', 'fixture-human')
            self.assertEqual(before, (path / 'human.json').read_bytes())

    def test_public_symlink_or_malformed_client_key_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'token'
            path.write_text('x' * 43 + '\n')
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                credentials.private_token(path)
            path.chmod(0o600)
            link = Path(temp) / 'link'
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                credentials.private_token(link)
            path.write_text('short')
            with self.assertRaises(ValueError):
                credentials.private_token(path)


if __name__ == '__main__':
    unittest.main()
