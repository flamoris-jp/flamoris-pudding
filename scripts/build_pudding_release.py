#!/usr/bin/env python3
"""Build an Updater catalog from one actual Docker save archive, never guessed IDs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tarfile
from urllib.parse import urlsplit

APP = 'flamoris-pudding'
VERSION = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[A-Za-z0-9.-]+)?\Z')


def image_config(archive: Path, image_id: str | None):
    if archive.stat().st_size > 4 * 1024**3:
        raise ValueError('image archive exceeds Updater budget')
    if image_id is not None and not re.fullmatch(r'sha256:[0-9a-f]{64}', image_id):
        raise ValueError('invalid image digest')
    manifest, configs = None, {}
    with tarfile.open(archive, 'r:*') as stream:
        for count, member in enumerate(stream):
            if count >= 4096:
                raise ValueError('archive member limit')
            if not member.isfile() or member.size > 1024 * 1024:
                continue
            if member.name == 'manifest.json':
                if manifest is not None:
                    raise ValueError('duplicate manifest')
                manifest = json.loads(stream.extractfile(member).read())
            elif re.fullmatch(r'(?:[0-9a-f]{64}\.json|blobs/sha256/[0-9a-f]{64})', member.name):
                if member.name in configs:
                    raise ValueError('duplicate image config')
                configs[member.name] = member
        if not isinstance(manifest, list) or len(manifest) != 1 or not isinstance(manifest[0], dict):
            raise ValueError('one runnable Docker image required')
        selected = configs.get(manifest[0].get('Config'))
        if selected is None:
            raise ValueError('image digest does not match selected image')
        raw = stream.extractfile(selected).read()
    actual = 'sha256:' + hashlib.sha256(raw).hexdigest()
    if image_id is not None and image_id != actual:
        raise ValueError('selected config digest mismatch')
    config = json.loads(raw)
    if not isinstance(config, dict):
        raise ValueError('invalid image config')
    return config, actual


def make_catalog(archive: Path, image_id: str | None, release: str, revision: str,
                 platform: str, base_url: str, compatible_from=()):
    if not VERSION.fullmatch(release) or not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('invalid release/revision')
    if platform not in ('linux/amd64', 'linux/arm64'):
        raise ValueError('unsupported platform')
    if len(set(compatible_from)) != len(compatible_from) or any(not VERSION.fullmatch(v) for v in compatible_from):
        raise ValueError('invalid compatible predecessors')
    url = urlsplit(base_url)
    if url.scheme != 'https' or url.netloc != 'github.com' or url.query or url.fragment or not re.fullmatch(
            rf'/flamoris-jp/flamoris-pudding/releases/download/v{re.escape(release)}', url.path):
        raise ValueError('release URL must match the repository and exact version')
    config, image_id = image_config(archive, image_id)
    runtime = config.get('config', {})
    labels = runtime.get('Labels', {})
    if (config.get('os') != 'linux' or config.get('architecture') != platform.split('/')[1]
            or runtime.get('User') != '10001:10001' or runtime.get('Volumes')
            or labels.get('org.opencontainers.image.title') != APP
            or labels.get('org.opencontainers.image.version') != release
            or labels.get('org.opencontainers.image.revision') != revision
            or not isinstance(runtime.get('Entrypoint'), list)
            or runtime['Entrypoint'][-1] != 'net.flamoris.pudding.ServiceMain'):
        raise ValueError('image identity/runtime contract mismatch')
    h = hashlib.sha256()
    with archive.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    digest = 'sha256:' + h.hexdigest()
    asset = platform.replace('/', '-') + '--image.tar'

    def setting(key, label, *, default='', secret=False, required=False, generate=False, kind='text'):
        return dict(key=key, label=label, default=default, secret=secret,
                    required=required, generate=generate, kind=kind)

    settings = [
        setting('PORT', 'アプリの未使用ローカルポート', default='8789', kind='port'),
        setting('JDBC_URL', '既存のPudding専用DBのJDBC URL（TLS等を指定）', required=True),
        setting('DB_USER', 'Pudding専用runtime DBロール', required=True, kind='name'),
        setting('DB_PASSWORD', 'Pudding専用runtime DBパスワード', required=True, secret=True),
        setting('ALLOWED_HOSTS', 'MCP接続先のHost（複数はカンマ区切り）', required=True),
        setting('ALLOWED_ORIGINS', '許可するHTTPS Origin（空欄ならブラウザーOriginを拒否）'),
        setting('AGENT_ID', '初期Agentの安定したID', default='agent_one', kind='name'),
        setting('AGENT_TOKEN', '初期Agent専用トークン（空欄で生成、手動指定は43文字以上）', secret=True, generate=True, kind='url_password'),
    ]
    recipe = {
        'application_id': APP, 'release': release, 'platform': platform,
        'compatible_from': list(compatible_from), 'dependencies': {}, 'settings': settings,
        'downloads': {asset: {'url': base_url + '/' + asset, 'digest': digest}},
        'generated': {'runtime.env': '\n'.join([
            'PUDDING_HTTP_BIND=0.0.0.0', 'PUDDING_HTTP_PORT=8080',
            'PUDDING_JDBC_URL=${JDBC_URL}', 'PUDDING_DB_USER=${DB_USER}',
            'PUDDING_DB_PASSWORD=${DB_PASSWORD}', 'PUDDING_ALLOWED_HOSTS=${ALLOWED_HOSTS},127.0.0.1:${PORT}',
            'PUDDING_ALLOWED_ORIGINS=${ALLOWED_ORIGINS}',
            'PUDDING_AGENT_CREDENTIALS=[{"agent_id":"${AGENT_ID}","token":"${AGENT_TOKEN}"}]', ''])},
        'application': {
            'application_id': APP, 'release': release, 'kind': 'docker',
            'directories': [{'path': '${root}/config'}],
            'files': [{'source': {'path': '${package}/runtime.env', 'digest': 'generated', 'private': True},
                       'destination': '${root}/config/runtime.env'}],
            'environment_file': '${root}/config/runtime.env',
            'image_archive': {'path': '${package}/' + asset, 'digest': digest, 'private': False},
            'image_id': image_id, 'container_name': APP, 'network': 'bridge',
            'ports': [{'host': {'setting_int': 'PORT'}, 'container': 8080}], 'mounts': [],
            'health_url': 'http://127.0.0.1:${PORT}/health/ready',
        },
    }
    return {'catalog_version': 1, 'recipes': [recipe]}, asset


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image-tar', type=Path, required=True)
    parser.add_argument('--image-id', help='optional exact image config digest; omitted derives it from the archive')
    parser.add_argument('--release', required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--platform', required=True)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--compatible-from', action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    a = parser.parse_args()
    catalog, asset = make_catalog(a.image_tar, a.image_id, a.release, a.revision,
                                 a.platform, a.base_url, a.compatible_from)
    a.output.mkdir(parents=True, exist_ok=True)
    destination = a.output / asset
    if a.image_tar.resolve() != destination.resolve():
        shutil.copyfile(a.image_tar, destination)
    (a.output / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n')
    (a.output / 'candidate.json').write_text(json.dumps({
        'application_id': APP, 'release': a.release, 'source_revision': a.revision,
        'platform': a.platform, 'image_id': catalog['recipes'][0]['application']['image_id'], 'published': False,
        'artifacts': catalog['recipes'][0]['downloads'],
    }, indent=2) + '\n')
    print('Built candidate catalog and verified image archive; no release published.')


if __name__ == '__main__':
    main()
