#!/usr/bin/env python3
"""Compile Java without external build dependencies, then compare to Python."""
from pathlib import Path
import argparse
import json
import os
import shutil
import subprocess
import sys
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'src'))
from flamoris_pudding.sensory import evaluate_bite, get_preset
from flamoris_pudding.lifecycle import deadlines, age, parse_time
from flamoris_pudding.accounting import expiry_candidate
from flamoris_pudding.core import Store


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--postgres', action='store_true', help='require isolated PostgreSQL integration tests')
    args = parser.parse_args()
    build = ROOT / 'java' / 'target' / 'verification'
    build.mkdir(parents=True, exist_ok=True)
    sources = sorted((ROOT / 'java/src').glob('**/*.java'))
    # Some research hosts contain jdk.compiler but omit the javac launcher/ct.sym.
    compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', 'com.sun.tools.javac.Main']
    subprocess.run([*compiler, '-source', '21', '-target', '21', '-encoding', 'UTF-8', '-d', str(build), *map(str, sources)], check=True)
    shutil.copytree(ROOT / 'java/src/main/resources', build, dirs_exist_ok=True)
    cp = os.pathsep.join([str(build), str(ROOT / 'java/target/dependency/*')])
    subprocess.run(['java', '-cp', cp, 'net.flamoris.pudding.CoreTest'], check=True)
    result = subprocess.run(['java', '-cp', cp, 'net.flamoris.pudding.ParityVectors'], check=True, capture_output=True, text=True)
    data = json.loads(result.stdout)
    for v in data['sensory']:
        expected = evaluate_bite(get_preset(v['id']), {'body': v['body'], 'sauce': v['sauce']},
                                 model='blend-interaction-v0' if v['interaction'] else 'blend-linear-v0')
        assert v['features'] == expected['features'], v
    for v in data['lifecycle']:
        d = deadlines(parse_time(v['purchase']))
        assert parse_time(d['best_before_at']) == parse_time(v['best'])
        assert parse_time(d['dispose_after_at']) == parse_time(v['hard'])
        a = age(d, parse_time(v['now']))
        assert (a['status'], a['quality_q'], a['rotten_egg_like_q']) == (v['status'], v['quality'], v['rotten'])
    candidate = expiry_candidate('asset🍮\n"', 'purchase', 1100, 200, 100,
                                 '2026-11-08T01:00:00Z', '2026-11-08T01:00:00Z', True)
    assert candidate['candidate_id'] == data['candidate_id']
    rows = []
    with_store = Store(':memory:')
    state = None
    def execute(name, **extra):
        nonlocal state
        request = {'request_id': str(uuid4())}
        if state is not None:
            request.update(session_id=state['id'], expected_revision=state['revision'])
        request.update(extra)
        response = with_store.call(name, request)
        if response['ok']:
            state = response['session']
        summary = {'ok': response['ok'], 'error': response.get('error'),
                   'violations': response['policy']['violations'], 'policy_status': response['policy']['status']}
        if response['ok']:
            s = response['session']
            summary.update({k: s[k] for k in ('revision', 'food_stage', 'session_stage', 'peel_q', 'lid', 'bites')})
            for k in ('body', 'sauce'):
                summary[k] = [s['components'][k][key] for key in ('cup_mg', 'spoon_mg', 'consumed_mg', 'discarded_mg')]
            if s['last_taste']:
                summary['features'] = s['last_taste']['features']
        rows.append(summary)
        return request
    execute('pudding.sessions.create')
    execute('pudding.lid.peel', amount_q=250)
    execute('pudding.lid.peel', amount_q=750)
    execute('pudding.spoon.taste')
    execute('pudding.spoon.scoop', body_mg=90001, sauce_mg=0)
    execute('pudding.spoon.scoop', body_mg=8000, sauce_mg=2000)
    execute('pudding.lid.dispose')
    execute('pudding.spoon.scoop', body_mg=8000, sauce_mg=2000)
    execute('pudding.spoon.scoop', body_mg=100, sauce_mg=0)
    execute('pudding.spoon.taste', expected_revision=0)
    request = execute('pudding.spoon.taste')
    execute('pudding.spoon.taste', **request)
    execute('pudding.sessions.finish')
    execute('pudding.sessions.cleanup')
    with_store.close()
    assert data['core'] == rows, (data['core'], rows)
    print(f"Parity: {len(data['sensory'])} sensory, {len(data['lifecycle'])} lifecycle, {len(rows)} state responses, candidate ID")
    if args.postgres:
        subprocess.run(['java', '-cp', cp, 'net.flamoris.pudding.PostgresTest'], check=True)


if __name__ == '__main__':
    main()
