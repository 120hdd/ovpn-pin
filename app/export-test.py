#!/usr/bin/env python3
"""Configs for other devices: the key's part of it, and the links.

    python app/export-test.py

The phone makes the same links in mobile/core/export.go, and its test holds
the same UUIDs: the installer derives them in Python on the server, so all
three have to agree or a link is refused with nothing to say why.
"""
import base64
import json
import os
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export                                                # noqa: E402
import tunnel                                                # noqa: E402

SS = {'method': 'chacha20-ietf-poly1305', 'password': 'p', 'port': 8388,
      'exitPort': 8389}


def key(body):
    raw = base64.urlsafe_b64encode(json.dumps(body).encode()).decode()
    return 'relay://' + raw.rstrip('=')


def test_slot_uuids_match_the_installer_and_the_phone():
    assert export.slot_uuid('abcDEF123', 0) == '5e4d9bc1-037d-5cbf-898f-07a9277c12b6'
    assert export.slot_uuid('abcDEF123', 1) == '8facc91b-c776-5882-b569-860b4b664290'
    assert export.slot_uuid('abcDEF123', 8) == 'c973411e-277a-5548-b54c-f06fa771b06a'


def test_key_carries_export():
    k = tunnel.parse_key(key({'v': 1, 'host': '203.0.113.7', 'pin': '', 'pw': 'p',
                              'apw': 'a', 'x': {'id': 'abc_DEF-1', 'n': 8}}))
    assert k['export'] == {'id': 'abc_DEF-1', 'slots': 8}
    old = tunnel.parse_key(key({'v': 1, 'host': '203.0.113.7', 'pw': 'p', 'apw': 'a'}))
    assert old['export'] is None
    for bad in ({'id': '', 'n': 8}, {'id': 'a b', 'n': 8}, {'id': 'a', 'n': 0},
                {'id': 'a', 'n': 99}, {'id': 'a', 'n': True}, 'x'):
        try:
            tunnel.parse_key(key({'v': 1, 'host': '203.0.113.7', 'pw': 'p',
                                  'apw': 'a', 'x': bad}))
        except ValueError:
            continue
        raise AssertionError(f'x {bad!r} should be refused')


def test_links_by_name():
    rows = export.links('relay.example.com', 'abcDEF123',
                        [{'k': 0, 'name': 'Relay · server'},
                         {'k': 1, 'name': 'Relay · Germany'}],
                        edge='104.16.1.2', ss=SS)
    assert [r['k'] for r in rows] == [0, 1]
    u = urllib.parse.urlsplit(rows[1]['vless'])
    q = dict(urllib.parse.parse_qsl(u.query))
    assert u.scheme == 'vless' and u.username == export.slot_uuid('abcDEF123', 1)
    assert u.hostname == '104.16.1.2' and u.port == 443
    assert q['sni'] == q['host'] == 'relay.example.com'
    assert q['type'] == 'ws' and q['path'] == '/vl' and q['security'] == 'tls'
    assert 'allowInsecure' not in q
    assert urllib.parse.unquote(u.fragment) == 'Relay · Germany'

    vm = json.loads(base64.b64decode(rows[1]['vmess'][len('vmess://'):]))
    assert vm['id'] == export.slot_uuid('abcDEF123', 1) and vm['add'] == '104.16.1.2'
    assert vm['host'] == 'relay.example.com' and vm['path'] == '/vm'
    assert vm['net'] == 'ws' and vm['tls'] == 'tls' and vm['v'] == '2'

    assert rows[0]['ss'].startswith('ss://') and '@relay.example.com:8388#' in rows[0]['ss']
    assert 'ss' not in rows[1], 'only the server itself has a Shadowsocks link'

    sub = base64.b64decode(export.subscription(rows)).decode()
    assert sub.count('vless://') == 2 and 'vmess://' not in sub


def test_links_by_address():
    rows = export.links('203.0.113.7', 'x1', [{'k': 2, 'name': 'Relay · France'}],
                        edge='104.16.1.2')
    u = urllib.parse.urlsplit(rows[0]['vless'])
    q = dict(urllib.parse.parse_qsl(u.query))
    # No Cloudflare in front of an address, and no name to vouch for it.
    assert u.hostname == '203.0.113.7' and q['allowInsecure'] == '1' and 'sni' not in q


def test_refuses_without_an_export_id():
    for host, xid, slots in (('relay.example.com', '', [{'k': 0, 'name': 'x'}]),
                             ('h', 'i', [{'k': 33, 'name': 'x'}])):
        try:
            export.links(host, xid, slots)
        except ValueError:
            continue
        raise AssertionError((host, xid, slots))


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)
