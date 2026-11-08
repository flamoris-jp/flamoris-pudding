#!/usr/bin/env python3
"""Promote the exact tested candidate; this script never contacts GitHub or publishes."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil

from build_pudding_release import APP, VERSION, make_catalog

REPOSITORY = 'flamoris-jp/flamoris-pudding'
WORKFLOWS = {'.github/workflows/ci.yml', '.github/workflows/java.yml',
             '.github/workflows/standalone.yml'}
IMAGE = 'linux-amd64--image.tar'
ASSETS = ('catalog.json', 'candidate.json', IMAGE)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON key')
        result[key] = value
    return result


def read_json(path, maximum=128 * 1024):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > maximum:
        raise ValueError('invalid JSON file')
    result = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_object)
    if not isinstance(result, dict):
        raise ValueError('JSON object required')
    return result


def identity(source_sha, release):
    if not isinstance(source_sha, str) or not re.fullmatch(r'[0-9a-f]{40}', source_sha):
        raise ValueError('full source commit required')
    if not isinstance(release, str) or not VERSION.fullmatch(release):
        raise ValueError('release version required')


def select_tested_run(payload, source_sha):
    """Require the newest main push run of each source workflow to have succeeded."""
    identity(source_sha, '0.0.0')
    runs = payload.get('workflow_runs')
    if not isinstance(runs, list) or len(runs) > 100:
        raise ValueError('bounded workflow runs required')
    selected = {}
    for run in runs:
        if not isinstance(run, dict) or run.get('path') not in WORKFLOWS:
            continue
        if (run.get('repository', {}).get('full_name') != REPOSITORY
                or run.get('head_repository', {}).get('full_name') != REPOSITORY
                or run.get('head_sha') != source_sha or run.get('head_branch') != 'main'
                or run.get('event') != 'push'):
            continue
        run_id, attempt = run.get('id'), run.get('run_attempt')
        if type(run_id) is not int or run_id < 1 or type(attempt) is not int or attempt < 1:
            raise ValueError('invalid workflow identity')
        previous = selected.get(run['path'])
        if previous is None or (run_id, attempt) > (previous['id'], previous['run_attempt']):
            selected[run['path']] = run
    if set(selected) != WORKFLOWS or any(
            run.get('status') != 'completed' or run.get('conclusion') != 'success'
            for run in selected.values()):
        raise ValueError('all three latest main workflows must have succeeded')
    return selected['.github/workflows/standalone.yml']['id']


def verify_candidate(directory, source_sha, release):
    identity(source_sha, release)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError('candidate directory required')
    names = {path.name for path in directory.iterdir()}
    if not set(ASSETS).issubset(names) or names - set(ASSETS) - {'SHA256SUMS', 'release-notes.md'}:
        raise ValueError('candidate contains missing or unexpected files')
    for path in directory.iterdir():
        if path.is_symlink() or not path.is_file():
            raise ValueError('regular candidate files required')
    metadata = read_json(directory / 'candidate.json')
    catalog = read_json(directory / 'catalog.json')
    if (set(metadata) != {'application_id', 'release', 'source_revision', 'platform',
                          'image_id', 'published', 'artifacts'}
            or metadata['application_id'] != APP or metadata['release'] != release
            or metadata['source_revision'] != source_sha or metadata['platform'] != 'linux/amd64'
            or metadata['published'] is not False
            or not isinstance(metadata['image_id'], str)):
        raise ValueError('candidate provenance mismatch')
    recipes = catalog.get('recipes')
    if not isinstance(recipes, list) or len(recipes) != 1 or not isinstance(recipes[0], dict):
        raise ValueError('one tested recipe required')
    predecessors = recipes[0].get('compatible_from')
    if not isinstance(predecessors, list) or any(not isinstance(v, str) for v in predecessors):
        raise ValueError('invalid compatible predecessors')
    expected, asset = make_catalog(
        directory / IMAGE, metadata['image_id'], release, source_sha, 'linux/amd64',
        f'https://github.com/{REPOSITORY}/releases/download/v{release}', predecessors)
    if asset != IMAGE or catalog != expected or metadata['artifacts'] != expected['recipes'][0]['downloads']:
        raise ValueError('catalog does not describe the actual candidate image')
    checksums = ''.join(f'{digest(directory / name)}  {name}\n' for name in sorted(ASSETS))
    manifest = directory / 'SHA256SUMS'
    if manifest.exists() and (manifest.stat().st_size > 4096 or manifest.read_text() != checksums):
        raise ValueError('candidate checksum manifest mismatch')
    return checksums


def prepare(directory, output, source_sha, release):
    checksums = verify_candidate(directory, source_sha, release)
    if output.exists():
        raise ValueError('release output must be new')
    output.mkdir(parents=True)
    for name in ASSETS:
        shutil.copyfile(directory / name, output / name)
    (output / 'SHA256SUMS').write_text(checksums, encoding='utf-8')
    verify_candidate(output, source_sha, release)
    notes = f'''# FLAMORIS Pudding {release}

Development prerelease for Linux amd64. Source commit: `{source_sha}`.

The image is the unchanged candidate from successful main CI, including real
PostgreSQL, Docker/Updater install and update, and HTTPS MCP validation.
`catalog.json` is for the standalone service; connect directly without a Hub.
The image archive, catalog and candidate provenance are covered by `SHA256SUMS`.
`candidate.json` records the original build candidate, not live deployment state.

Research servings and manually provisioned Agent Bearer credentials only.
Human accounts, premium approvals, OAuth, payments and production deployment
are not included. A dedicated PostgreSQL 18 DB/runtime role and HTTPS ingress
must be configured separately. Read the standalone service guide before setup:
https://github.com/{REPOSITORY}/blob/{source_sha}/docs/STANDALONE_SERVICE.md
'''
    (output / 'release-notes.md').write_text(notes, encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    select = commands.add_parser('select')
    select.add_argument('--runs-json', type=Path, required=True)
    select.add_argument('--source-sha', required=True)
    select.add_argument('--version-file', type=Path, required=True)
    select.add_argument('--tag')
    select.add_argument('--github-output', type=Path, required=True)
    package = commands.add_parser('prepare')
    package.add_argument('--candidate', type=Path, required=True)
    package.add_argument('--output', type=Path, required=True)
    package.add_argument('--source-sha', required=True)
    package.add_argument('--release', required=True)
    args = parser.parse_args()
    if args.command == 'select':
        release = args.version_file.read_text(encoding='utf-8').strip()
        identity(args.source_sha, release)
        if args.tag is not None and args.tag != 'v' + release:
            raise ValueError('tag must match the checked-out application version')
        run_id = select_tested_run(read_json(args.runs_json, 8 * 1024 * 1024), args.source_sha)
        with args.github_output.open('a', encoding='utf-8') as stream:
            stream.write(f'run_id={run_id}\nrelease={release}\n')
        print('Selected successful main candidate and application version.')
    else:
        prepare(args.candidate, args.output, args.source_sha, args.release)
        print('Verified unchanged candidate and prepared release checksums; nothing published.')


if __name__ == '__main__':
    main()
