#!/usr/bin/env python3
"""Dump/restore only the explicitly opted-in disposable PostgreSQL CI service."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


def main():
    env = os.environ.copy()
    if env.get('PUDDING_TEST_ALLOW_SCHEMA') != 'true':
        raise ValueError('disposable database opt-in required')
    container = env['PUDDING_TEST_POSTGRES_CONTAINER']
    if not re.fullmatch(r'[a-f0-9]{12,64}', container):
        raise ValueError('explicit CI service container ID required')
    url = env['PUDDING_TEST_JDBC_URL']
    match = re.fullmatch(r'(jdbc:postgresql://[^/]+/)([a-z_][a-z0-9_]*)', url)
    user = env['PUDDING_TEST_DB_USER']
    if not match or not re.fullmatch(r'[a-z_][a-z0-9_]*', user):
        raise ValueError('isolated test JDBC target required')
    restored = 'pudding_restore_' + uuid4().hex
    command = ['docker', 'exec', '-i', container]

    def run(args, **kwargs):
        return subprocess.run(command + args, check=True, timeout=90,
                              stderr=subprocess.PIPE, **kwargs)

    created = False
    try:
        run(['createdb', '-U', user, restored], stdout=subprocess.DEVNULL)
        created = True
        with tempfile.TemporaryFile() as archive:
            run(['pg_dump', '-U', user, '-d', match[2], '--format=custom',
                 '--no-owner', '--no-acl'], stdout=archive)
            archive.seek(0)
            run(['pg_restore', '-U', user, '-d', restored, '--exit-on-error',
                 '--no-owner', '--no-acl'], stdin=archive, stdout=subprocess.DEVNULL)
        env['PUDDING_RESTORED_JDBC_URL'] = match[1] + restored
        cp = os.pathsep.join([str(ROOT / 'java/target/verification'),
                              str(ROOT / 'java/target/dependency/*')])
        subprocess.run(['java', '-cp', cp, 'net.flamoris.pudding.RecoveryTest'],
                       check=True, env=env, timeout=60)
    finally:
        if created:
            run(['dropdb', '-U', user, restored], stdout=subprocess.DEVNULL)


if __name__ == '__main__':
    main()
