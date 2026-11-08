#!/usr/bin/env python3
"""Create local private credentials and client token files without printing tokens."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets


def generate(agents, output: Path, clients: Path):
    if not agents or len(agents) > 64 or len(set(agents)) != len(agents) or any(
            not re.fullmatch(r'[A-Za-z0-9_.-]{1,80}', a) for a in agents):
        raise ValueError('invalid or duplicate Agent ID')
    # Refuse replacement; credential rotation is an explicit operator action.
    if output.exists() or clients.exists() or output.is_symlink() or clients.is_symlink():
        raise ValueError('choose new private output paths')
    records = [dict(agent_id=a, token=secrets.token_urlsafe(32)) for a in agents]
    clients.mkdir(mode=0o700)
    files = []
    try:
        for entry in records:
            path = clients / (entry['agent_id'] + '.token')
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as stream:
                stream.write(entry['token'] + '\n')
            files.append(path)
        fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as stream:
            stream.write(json.dumps(records, separators=(',', ':')) + '\n')
    except Exception:
        for path in files:
            path.unlink(missing_ok=True)
        clients.rmdir()
        raise


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--agent', action='append', required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--client-directory', type=Path, required=True)
    a = p.parse_args()
    generate(a.agent, a.output, a.client_directory)
    print('Created private credential files. Token values were not printed.')


if __name__ == '__main__':
    main()
