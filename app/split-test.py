#!/usr/bin/env python3
"""Split tunnelling on Windows: the rules, the adapter's config built from
them, and the worker deciding a connection by them.

    python app/split-test.py

The phone's core (mobile/core/split.go) keeps the same rules; the entries
below are the ones its test normalises, so the two cannot drift apart
without one of them failing.
"""
import importlib.util
import json
import os
import socket
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import split                                                 # noqa: E402
import tun                                                   # noqa: E402

NORMALISED = {
    'digikala.com': 'digikala.com',
    '  https://WWW.Digikala.com/x?y': 'www.digikala.com',
    '*.digikala.com': 'digikala.com',
    '.digikala.com': 'digikala.com',
    'digikala.com.': 'digikala.com',
    'digikala.com:443': 'digikala.com',
    'user@digikala.com': 'digikala.com',
    'ir': 'ir',
    'ایران': 'xn--mgba3a4f16a',
    'مثال.ایران': 'xn--mgbh0fb.xn--mgba3a4f16a',
    'bücher.de': 'xn--bcher-kva.de',
    '5.6.7.8': '5.6.7.8',
    '5.6.7.8:80': '5.6.7.8',
    '5.6.7.8/16': '5.6.0.0/16',
    '::ffff:5.6.7.8': '5.6.7.8',
}
REFUSED = ['', '  ', '2001:db8::/32', '2001:db8::1', 'a b.com', '-bad.com',
           'bad-.com', '*.', 'http://', 'x..com']


def test_normalises_like_the_phone():
    for text, want in NORMALISED.items():
        got = split.normalize(text)
        assert got == want, (text, got, want)
    for text in REFUSED:
        try:
            got = split.normalize(text)
        except split.Invalid:
            continue
        raise AssertionError(f'{text!r} should be refused, got {got!r}')


def test_programs_are_exe_names():
    assert split.normalize_app('C:\\Program Files\\Telegram\\Telegram.exe') == 'telegram.exe'
    assert split.normalize_app('chrome') == 'chrome.exe'
    for bad in ('', 'a|b.exe'):
        try:
            split.normalize_app(bad)
        except split.Invalid:
            continue
        raise AssertionError(bad)


def test_clean_keeps_what_it_can_and_says_what_it_could_not():
    cleaned, rejected = split.clean({
        'mode': 'bypass', 'iran': False,
        'apps': ['Telegram', 'telegram.exe'],
        'domains': ['https://Aparat.com/v', 'aparat.com', 'no such thing', '1.2.3.4/8']})
    assert cleaned == {'mode': 'bypass', 'iran': False, 'apps': ['telegram.exe'],
                       'domains': ['aparat.com', '1.0.0.0/8']}, cleaned
    assert rejected == ['no such thing'], rejected
    assert split.clean({'mode': 'sometimes'})[0]['mode'] == 'off'
    assert split.clean(None)[0] == {'mode': 'off', 'iran': True, 'apps': [], 'domains': []}


def test_nothing_to_decide_is_off():
    assert split.rules({'mode': 'off', 'iran': True}) is None
    # "Only" an empty list would carry nothing at all; it carries everything.
    assert split.rules({'mode': 'only', 'iran': False}) is None
    r = split.rules({'mode': 'only', 'iran': False, 'apps': ['a.exe']})
    assert r == {'mode': 'only', 'names': [], 'nets': [], 'apps': ['a.exe']}, r


def test_iran_preset():
    counts = split.iran_counts()
    assert counts['domains'] >= 20 and counts['ranges'] >= 1000, counts
    m = split.Matcher(split.rules({'mode': 'bypass', 'iran': True}))
    for host in ('shaparak.ir', 'www.digikala.com', 'aparat.com', '2.144.1.1'):
        assert m.straight(host), host
    for host in ('google.com', 'telegram.org', 'irish.com', '1.1.1.1'):
        assert not m.straight(host), host


def test_direction():
    by = split.Matcher(split.rules({'mode': 'bypass', 'iran': False,
                                    'domains': ['a.com'], 'apps': ['x.exe']}))
    only = split.Matcher(split.rules({'mode': 'only', 'iran': False,
                                      'domains': ['a.com'], 'apps': ['x.exe']}))
    assert by.straight('w.a.com') and not by.straight('b.com')
    assert by.straight('b.com', 'X.exe')
    assert not only.straight('w.a.com') and only.straight('b.com')
    assert not only.straight('b.com', 'x.exe')


def rule_with(rules, key):
    return [r for r in rules if key in r]


def test_adapter_bypass():
    r = split.rules({'mode': 'bypass', 'iran': True, 'apps': ['telegram.exe'],
                     'domains': ['example.org']})
    c = tun.build_config(9090, ['5.5.5.5'], ['C:/x/python.exe'], ['C:/x/gost.exe'],
                         split_rules=r, line_resolver='192.168.1.1')
    rules = c['route']['rules']
    assert rules[0] == {'action': 'sniff'}, 'names need sniffing, first'
    assert rules[1]['action'] == 'hijack-dns'
    apps = rule_with(rules, 'process_name')[0]
    assert apps['process_name'] == ['telegram.exe'] and apps['outbound'] == 'direct'
    dest = rule_with(rules, 'domain_suffix')[0]
    assert 'example.org' in dest['domain_suffix'] and 'ir' in dest['domain_suffix']
    assert len(dest['ip_cidr']) > 1000 and dest['outbound'] == 'direct'
    # Listed UDP goes straight out; everything else UDP is still refused,
    # and only after the split rules have had their say.
    assert rules[-1] == {'network': 'udp', 'action': 'reject'}
    assert rules.index(dest) < len(rules) - 1
    assert c['route']['final'] == 'relay'
    line = [s for s in c['dns']['servers'] if s['tag'] == 'dns-line']
    assert line == [{'type': 'udp', 'tag': 'dns-line', 'server': '192.168.1.1'}]
    assert rule_with(c['dns']['rules'], 'domain_suffix')[0]['server'] == 'dns-line'


def test_adapter_only():
    r = split.rules({'mode': 'only', 'iran': False, 'apps': ['chrome.exe'],
                     'domains': ['example.org']})
    c = tun.build_config(9090, split_rules=r, line_resolver='192.168.1.1')
    rules = c['route']['rules']
    assert c['route']['final'] == 'direct'
    # A listed flow is refused over UDP and carried over TCP, for programs
    # and destinations alike; nothing else UDP is refused.
    for key in ('process_name', 'domain_suffix'):
        pair = rule_with(rules, key)
        assert [p.get('network') for p in pair] == ['udp', None], pair
        assert pair[0]['action'] == 'reject' and pair[1]['outbound'] == 'relay'
    assert {'network': 'udp', 'action': 'reject'} not in rules
    # Every lookup still goes through the exit in "only": an unlisted name
    # may belong to a listed program.
    assert not any(s['tag'] == 'dns-line' for s in c['dns']['servers'])


def test_adapter_without_split_is_unchanged():
    c = tun.build_config(9090)
    assert c['route']['rules'][0] == {'port': 53, 'action': 'hijack-dns'}
    assert c['route']['final'] == 'relay'
    assert [s['tag'] for s in c['dns']['servers']] == ['dns-remote', 'dns-direct']


def load_worker():
    path = os.path.join(os.path.dirname(HERE), 'core', 'ovpn-proxy.py')
    spec = importlib.util.spec_from_file_location('ovpn_proxy_split', path)
    px = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(px)
    return px


def test_worker_reads_the_file_and_rereads_it():
    px = load_worker()
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, 'split.json')
        rules = px.SplitRules(path)
        assert not rules.straight('aparat.com'), 'no file is off'
        split.write_worker_file({'mode': 'bypass', 'iran': True}, path)
        rules.checked = 0
        assert rules.straight('www.aparat.com') and rules.straight('2.144.1.1')
        assert not rules.straight('google.com')
        split.write_worker_file(None, path)
        rules.checked = 0
        assert not rules.straight('www.aparat.com'), 'the adapter is deciding'
        with open(path, 'w') as f:
            f.write('{half')
        rules.checked = 0
        assert not rules.straight('www.aparat.com'), 'unreadable is off'


def test_worker_carries_a_listed_connect_straight():
    """A CONNECT to a listed name never reaches the exit: the worker dials it
    itself. The exit here is a socket that fails the test if it is asked."""
    px = load_worker()
    site = socket.socket()
    site.bind(('127.0.0.1', 0))
    site.listen(1)
    site_port = site.getsockname()[1]

    def answer():
        conn, _ = site.accept()
        conn.sendall(b'hello from the site')
        conn.close()
    threading.Thread(target=answer, daemon=True).start()

    class NoExit:
        auth = ''

        def take(self):
            raise AssertionError('the exit was asked for a listed site')

    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, 'split.json')
        split.write_worker_file({'mode': 'bypass', 'iran': False,
                                 'domains': ['localhost', '127.0.0.1']}, path)
        px.SPLIT.path = path
        px.SPLIT.checked = 0
        a, b = socket.socketpair()
        note = px.Carried()
        t = threading.Thread(target=px.serve_http, args=(
            b, NoExit(), True, b'C', note), daemon=True)
        t.start()
        a.sendall(f'ONNECT 127.0.0.1:{site_port} HTTP/1.1\r\n'
                  f'Host: 127.0.0.1:{site_port}\r\n\r\n'.encode())
        a.settimeout(5)
        got = b''
        deadline = time.time() + 5
        while b'hello from the site' not in got and time.time() < deadline:
            chunk = a.recv(4096)
            if not chunk:
                break
            got += chunk
        a.close()
        t.join(5)
    site.close()
    assert got.startswith(b'HTTP/1.1 200'), got
    assert b'hello from the site' in got, got


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)
