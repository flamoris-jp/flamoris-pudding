#!/usr/bin/env python3
"""Create separate private human credentials and a mock key, or read an Agent fingerprint."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import stat


def private_token(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError('private regular token file required')
    with path.open('rb') as stream:
        token = stream.read(130).strip()
    if not re.fullmatch(rb'[A-Za-z0-9_-]{43,128}', token):
        raise ValueError('token format')
    return token


def create(directory, tenant, human):
    if any(not re.fullmatch(r'[A-Za-z0-9_.-]{1,80}', value) for value in (tenant, human)):
        raise ValueError('identity format')
    directory = Path(directory)
    directory.mkdir(mode=0o700)  # Never overwrite a previous identity or key.
    records = {'human.json': json.dumps([dict(tenant_id=tenant, human_id=human,
                                             token=secrets.token_urlsafe(32))]) + '\n'}
    token = json.loads(records['human.json'])[0]['token']
    records['human.token'] = token + '\n'
    records['mock.key'] = secrets.token_bytes(48)
    try:
        for name, value in records.items():
            fd = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(value.encode() if isinstance(value, str) else value)
    except Exception:
        for name in records:
            (directory / name).unlink(missing_ok=True)
        directory.rmdir()
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    make = sub.add_parser('create')
    make.add_argument('--directory', type=Path, required=True)
    make.add_argument('--tenant', required=True)
    make.add_argument('--human', required=True)
    fingerprint = sub.add_parser('fingerprint')
    fingerprint.add_argument('--token-file', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'fingerprint':
        print(hashlib.sha256(private_token(args.token_file)).hexdigest())
    else:
        create(args.directory, args.tenant, args.human)
        print('Created private sandbox credentials and mock key; no secret values printed.')


if __name__ == '__main__':
    main()
