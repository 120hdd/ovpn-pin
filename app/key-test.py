#!/usr/bin/env python3
"""The server's key, read the way the window reads it, and the gost config
written from it.

    python app/key-test.py

The key is made by tunnel/install-server.sh and read here and in the phone's
Go core (mobile/core/relaykey.go). Both readers refuse the same keys; this
holds the desktop's half to that, and checks that a key with Shadowsocks in
it becomes a gost config that dials the server's address on the right port.
"""
import base64
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel                                                # noqa: E402


def key(body, pad=False):
    raw = base64.urlsafe_b64encode(json.dumps(body).encode()).decode()
    return 'relay://' + (raw if pad else raw.rstrip('='))


PIN = 'ab' * 32
SS = {'method': 'chacha20-ietf-poly1305', 'password': "s'3", 'port': 8388,
      'exitPort': 8389}


def test_reads_a_full_key():
    k = tunnel.parse_key(key({'v': 1, 'host': '203.0.113.7', 'pin': PIN.upper(),
                              'pw': 'p1', 'apw': 'a2', 'ss': SS}))
    assert k['host'] == '203.0.113.7' and k['pin'] == PIN
    assert k['password'] == 'p1' and k['api_password'] == 'a2'
    assert k['ss'] == SS


def test_reads_a_wrapped_padded_key():
    text = key({'v': 1, 'host': 'relay.example.com', 'pin': '', 'pw': 'p',
                'apw': 'a'}, pad=True)
    wrapped = text[:20] + '\n   ' + text[20:]
    k = tunnel.parse_key(wrapped)
    assert k['host'] == 'relay.example.com' and k['ss'] is None


def test_refuses_what_the_phone_refuses():
    bad = {
        'empty': '  ',
        'not base64': 'relay://!!!',
        'not json': 'relay://' + base64.urlsafe_b64encode(b'hello').decode(),
        'newer': key({'v': 2, 'host': 'h', 'pw': 'p', 'apw': 'a'}),
        'no host': key({'v': 1, 'pw': 'p', 'apw': 'a'}),
        'no pw': key({'v': 1, 'host': 'h', 'apw': 'a'}),
        'bad host': key({'v': 1, 'host': 'h/../x', 'pw': 'p', 'apw': 'a'}),
        'bad pin': key({'v': 1, 'host': 'h', 'pin': 'xyz', 'pw': 'p', 'apw': 'a'}),
        'bad method': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a',
                           'ss': {**SS, 'method': 'rc4-md5'}}),
        'bad port': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a',
                         'ss': {**SS, 'port': 0}}),
        'no ss secret': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a',
                             'ss': {**SS, 'password': ''}}),
    }
    for name, text in bad.items():
        try:
            tunnel.parse_key(text)
        except ValueError:
            continue
        raise AssertionError(f'{name}: accepted')


def test_ss_config_dials_the_address():
    text = tunnel.config_text('203.0.113.7', 'tunnelpw', ss=SS)
    assert 'mwss' not in text and 'edge-' not in text
    try:
        import yaml
    except ImportError:
        # Without PyYAML, the shape is checked by hand.
        assert "addr: 203.0.113.7:8388" in text and "addr: 203.0.113.7:8389" in text
        assert "password: 's''3'" in text
        return
    cfg = yaml.safe_load(text)
    chains = {c['name']: c for c in cfg['chains']}
    for mode, port in (('single', 8388), ('bulk', 8388), ('multi', 8389)):
        node = chains[f'ch-{mode}']['hops'][0]['nodes'][0]
        assert node['addr'] == f'203.0.113.7:{port}', node
        assert node['connector']['type'] == 'ss'
        assert node['connector']['auth'] == {'username': SS['method'],
                                             'password': SS['password']}
        assert node['connector']['metadata'] == {'nodelay': True}
        assert node['dialer']['type'] == 'tcp'
    # The local side is unchanged: the window still talks HTTP to 127.0.0.1.
    ports = {s['name']: s['addr'] for s in cfg['services']}
    assert ports == {m: f'127.0.0.1:{p}' for m, p in tunnel.PORTS.items()}


def test_ws_config_is_unchanged():
    text = tunnel.config_text('relay.example.com', 'pw', ['1.2.3.4'])
    assert 'type: ss' not in text and 'mwss' in text


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)
