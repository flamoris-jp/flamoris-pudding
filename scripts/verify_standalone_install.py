#!/usr/bin/env python3
"""Isolated CI only: real Docker + PostgreSQL + Updater install/update + HTTPS MCP.

Requires an empty disposable PostgreSQL service and root-owned /var/lib storage.
Release downloads are substituted by local candidate bytes; installer/runtime are real.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from build_pudding_release import make_catalog


def command(argv, *, output=False):
    result = subprocess.run(argv, check=True, capture_output=True, text=True, timeout=600)
    return result.stdout.strip() if output else None


def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class Client:
    def __init__(self, url, token, context=None):
        self.url, self.token, self.context = url, token, context
        self.session = None
        self.rpc_id = 0

    def request(self, method, params=None, *, notification=False, token=None, extra=None):
        self.rpc_id += 1
        body = {'jsonrpc': '2.0', 'method': method}
        if not notification:
            body['id'] = self.rpc_id
        if params is not None:
            body['params'] = params
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
                   'Authorization': 'Bearer ' + (self.token if token is None else token)}
        if self.session:
            headers.update({'Mcp-Session-Id': self.session, 'MCP-Protocol-Version': '2025-11-25'})
        headers.update(extra or {})
        request = Request(self.url, json.dumps(body).encode(), headers, method='POST')
        try:
            with urlopen(request, timeout=25, context=self.context) as response:
                if sid := response.headers.get('Mcp-Session-Id'):
                    self.session = sid
                data = response.read()
                return response.status, json.loads(data) if data else None
        except HTTPError as e:
            e.read(); e.close()
            return e.code, None

    def initialize(self, expected_version):
        status, data = self.request('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
                                                  'clientInfo': {'name': 'pudding-isolated-ci', 'version': '1'}})
        assert status == 200 and data['result']['serverInfo']['version'] == expected_version
        assert self.session
        assert self.request('notifications/initialized', notification=True) == (202, None)

    def tool(self, name, args):
        status, data = self.request('tools/call', {'name': name, 'arguments': args})
        assert status == 200 and 'result' in data, (status, data)
        result = data['result']
        assert json.loads(result['content'][0]['text']) == result['structuredContent']
        return result['structuredContent']


def wait_ready(url):
    until = time.monotonic() + 60
    while time.monotonic() < until:
        try:
            with urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except (OSError, HTTPError):
            time.sleep(0.25)
    raise AssertionError('isolated service did not become ready')


def run(args):
    if os.geteuid() != 0 or os.environ.get('PUDDING_TEST_ALLOW_SCHEMA') != 'true':
        raise ValueError('requires root in disposable CI with explicit schema opt-in')
    import psycopg
    from psycopg import sql
    from flamoris_updater_adapters.managed import Manager, Catalog, Start
    from flamoris_update_core.wire import dumps
    from flamoris_update_core.errors import UpdateError

    gateway = command(['/usr/bin/docker', 'network', 'inspect', 'bridge', '--format', '{{(index .IPAM.Config 0).Gateway}}'], output=True)
    host_port, tls_port = port(), port()
    image_ids = {}
    for version, image in [('0.3.0', args.image), ('0.3.1', args.next_image)]:
        image_ids[version] = command(['/usr/bin/docker', 'image', 'inspect', image, '--format', '{{.Id}}'], output=True)
    runtime_password = secrets.token_urlsafe(32)
    dsn = os.environ['PUDDING_TEST_ADMIN_DSN']
    created = []
    with tempfile.TemporaryDirectory(prefix='pudding-standalone-ci-', dir='/var/lib') as temp:
        base = Path(temp); state = base / 'updater-state'; root = base / 'apps'
        downloads = {}
        recipes = []
        for version in ('0.3.0', '0.3.1'):
            archive = base / (version + '.tar')
            command(['/usr/bin/docker', 'image', 'save', '--output', str(archive), image_ids[version]])
            catalog, asset = make_catalog(archive, None, version, args.revision,
                'linux/amd64', f'https://github.com/flamoris-jp/flamoris-pudding/releases/download/v{version}',
                ['0.3.0'] if version == '0.3.1' else [])
            recipes.extend(catalog['recipes'])
            downloads[catalog['recipes'][0]['downloads'][asset]['url']] = archive
        catalog = Catalog.model_validate({'catalog_version': 1, 'recipes': recipes})

        # Apply the app-owned migration inside the actual candidate image.
        owner_env = base / 'owner.env'
        owner_env.write_text('\n'.join([
            f'PUDDING_JDBC_URL=jdbc:postgresql://{gateway}:5432/pudding_test?sslmode=disable',
            'PUDDING_DB_USER=pudding_test', 'PUDDING_DB_PASSWORD=synthetic-ci-only',
            'PUDDING_RUNTIME_ROLE=pudding_runtime', '']))
        owner_env.chmod(0o600)
        with psycopg.connect(dsn, autocommit=True) as db:
            db.execute(sql.SQL('CREATE ROLE {} LOGIN PASSWORD {}').format(sql.Identifier('pudding_runtime'), sql.Literal(runtime_password)))
        command(['/usr/bin/docker', 'run', '--rm', '--network', 'bridge', '--env-file', str(owner_env), args.image, 'migrate-postgres'])
        command(['/usr/bin/docker', 'run', '--rm', '--network', 'bridge', '--env-file', str(owner_env), args.image, 'grant-runtime-postgres'])

        manager = Manager(state, root)
        catalog_url = 'https://candidate.example/catalog.json'
        def download(url, limit, destination=None, expected=None):
            if url == catalog_url:
                raw = dumps(catalog)
                assert destination is None
                return raw
            source = downloads[url]
            assert source.stat().st_size <= limit
            digest = 'sha256:' + hashlib.sha256(source.read_bytes()).hexdigest()
            if expected != digest:
                raise UpdateError('artifact_mismatch')
            with destination.open('xb') as stream, source.open('rb') as input:
                shutil.copyfileobj(input, stream)
        manager._download = download
        manager.configure(catalog_url)
        settings = {
            'PORT': str(host_port), 'JDBC_URL': f'jdbc:postgresql://{gateway}:5432/pudding_test?sslmode=disable',
            'DB_USER': 'pudding_runtime', 'DB_PASSWORD': runtime_password,
            'ALLOWED_HOSTS': f'127.0.0.1:{tls_port}', 'ALLOWED_ORIGINS': f'https://127.0.0.1:{tls_port}',
            'AGENT_ID': 'fixture-a',
        }
        def job_done(job, phase):
            result = manager.journal.get('managed_job', job['job_id'])
            assert result['phase'] == phase, {k: result.get(k) for k in ('phase', 'step', 'error')}

        nginx = 'pudding-edge-' + secrets.token_hex(6)
        try:
            job = manager.start('install', Start(application_id='flamoris-pudding', release='0.3.0', request_key='install-fixture', settings=settings))
            manager.run_job(job['job_id']); job_done(job, 'awaiting_setup')
            manager.start_setup('flamoris-pudding')
            wait_ready(f'http://127.0.0.1:{host_port}/health/ready')
            manager.complete('flamoris-pudding'); job_done(job, 'succeeded')
            values = json.loads((state / 'settings/flamoris-pudding/values.json').read_text())
            token = values['AGENT_TOKEN']
            env_path = root / 'flamoris-pudding/config/runtime.env'
            original_env = env_path.read_bytes()
            assert env_path.stat().st_mode & 0o777 == 0o600
            print('PASS actual Updater Docker install and runtime-role DB readiness')

            tls = base / 'tls'; tls.mkdir()
            command(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                     '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1',
                     '-keyout', str(tls / 'key.pem'), '-out', str(tls / 'cert.pem')])
            nginx_conf = base / 'nginx.conf'
            nginx_conf.write_text(f'''events {{}}
http {{ server {{ listen {tls_port} ssl; ssl_certificate /tls/cert.pem; ssl_certificate_key /tls/key.pem;
 location = /mcp {{ proxy_pass http://127.0.0.1:{host_port}; proxy_http_version 1.1; proxy_set_header Host $http_host; proxy_set_header Connection close; proxy_buffering off; }}
 location / {{ return 404; }} }} }}
''')
            command(['/usr/bin/docker', 'run', '--detach', '--name', nginx, '--network', 'host',
                     '--mount', f'type=bind,src={nginx_conf},dst=/etc/nginx/nginx.conf,readonly',
                     '--mount', f'type=bind,src={tls},dst=/tls,readonly', 'nginx:stable-alpine'])
            context = ssl.create_default_context(cafile=str(tls / 'cert.pem'))
            client = Client(f'https://127.0.0.1:{tls_port}/mcp', token, context)
            until = time.monotonic() + 20
            while True:
                try:
                    client.initialize('0.3.0'); break
                except OSError:
                    if time.monotonic() >= until:
                        raise
                    time.sleep(0.2)
            assert client.request('tools/list')[1]['result']['tools']
            assert client.request('ping', token='invalid')[0] == 401
            assert client.request('ping', extra={'Origin': 'https://untrusted.example'})[0] == 403
            import uuid
            request_id = str(uuid.uuid4())
            first = client.tool('pudding.sessions.create', {'request_id': request_id})
            assert first['ok']
            session = first['session']
            def action(op, **extra):
                nonlocal session
                params = {'session_id': session['id'], 'expected_revision': session['revision'], 'request_id': str(uuid.uuid4()), **extra}
                result = client.tool(op, params)
                assert result['ok'], result.get('error')
                session = result['session']
            action('pudding.lid.peel', amount_q=1000)
            action('pudding.lid.dispose')
            action('pudding.spoon.scoop', body_mg=8000, sauce_mg=2000)
            action('pudding.spoon.taste')
            assert session['last_taste']['features']['sweetness_q'] == 666
            snapshot = session
            with psycopg.connect(dsn, autocommit=True) as db:
                before = db.execute('SELECT count(*) FROM pudding_events').fetchone()[0]
            print('PASS HTTPS MCP initialization, authenticated tools, origin rejection and taste')

            # Retain DB, token, settings and serving across a real managed image update.
            update = manager.start('update', Start(application_id='flamoris-pudding', release='0.3.1', request_key='update-fixture'))
            manager.run_job(update['job_id']); job_done(update, 'succeeded')
            assert env_path.read_bytes() == original_env
            client = Client(f'https://127.0.0.1:{tls_port}/mcp', token, context)
            client.initialize('0.3.1')
            assert client.tool('pudding.sessions.create', {'request_id': request_id}) == first
            read = client.tool('pudding.sessions.get', {'session_id': session['id']})['session']
            effective = read.pop('effective_age')
            assert effective['status'] == 'not_applicable' and read == snapshot
            with psycopg.connect(dsn, autocommit=True) as db:
                assert db.execute('SELECT count(*) FROM pudding_events').fetchone()[0] == before
            action('pudding.sessions.finish')
            action('pudding.sessions.cleanup')
            assert session['session_stage'] == 'closed'
            print('PASS actual Updater compatible image update retains DB, credentials, replay and cleanup')
            # An interrupted caller can retry the same business request through a new MCP session.
            assert client.tool('pudding.sessions.create', {'request_id': request_id}) == first
            print('Standalone install integration: 4 scenarios passed')
        finally:
            # Isolated CI-only cleanup: no production names/configuration are used.
            subprocess.run(['/usr/bin/docker', 'rm', '-f', nginx], capture_output=True)
            names = command(['/usr/bin/docker', 'ps', '-a', '--format', '{{.Names}}'], output=True).splitlines()
            for name in names:
                if name.startswith('flamoris-pudding'):
                    subprocess.run(['/usr/bin/docker', 'rm', '-f', name], capture_output=True)
            manager.client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--next-image', required=True)
    parser.add_argument('--revision', required=True)
    run(parser.parse_args())


if __name__ == '__main__':
    main()
