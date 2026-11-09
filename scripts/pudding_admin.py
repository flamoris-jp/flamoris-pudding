#!/usr/bin/env python3
"""Call the separate human sandbox API with a private token file. Never follow redirects."""
import argparse
import json
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from create_sandbox_credentials import private_token


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('management redirects are refused')


def call(base_url, token_file, operation, payload):
    url = urlsplit(base_url)
    if (url.scheme != 'https' or not url.hostname or url.username or url.password
            or url.path not in ('', '/') or url.query or url.fragment):
        raise ValueError('HTTPS origin required')
    if not re.fullmatch(r'[a-z]+\.[a-z]+', operation):
        raise ValueError('operation format')
    if not isinstance(payload, dict):
        raise ValueError('JSON object required')
    raw = json.dumps(payload, ensure_ascii=False).encode()
    if len(raw) > 32768:
        raise ValueError('input size')
    token = private_token(token_file).decode('ascii')
    request = Request(base_url.rstrip('/') + '/manage/v1/' + operation, data=raw,
                      headers={'Authorization': 'Bearer ' + token,
                               'Content-Type': 'application/json', 'Accept': 'application/json'})
    with build_opener(NoRedirects()).open(request, timeout=20) as response:
        content = response.read(1048577)
        if len(content) > 1048576:
            raise ValueError('response size')
    return json.loads(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--token-file', type=Path, required=True)
    parser.add_argument('operation')
    parser.add_argument('--input', type=Path)
    args = parser.parse_args()
    try:
        payload = {} if args.input is None else json.loads(args.input.read_text())
        print(json.dumps(call(args.url, args.token_file, args.operation, payload), ensure_ascii=False, indent=2))
    except Exception:
        print('Management request failed. Check protected inputs, target and service status.', file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
