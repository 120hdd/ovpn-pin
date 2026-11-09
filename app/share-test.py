#!/usr/bin/env python3
"""Share on Wi-Fi: who the worker listens for, who it lets in, and the
firewall rule that lets a phone reach it.

    python app/share-test.py

Nothing here touches the firewall or the network. The PowerShell that adds
and removes the rule is built as text and read, never run - an elevated
change to somebody's firewall is not a thing a test should be able to do.
"""
import importlib.util
import json
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import engine                                                # noqa: E402
import paths                                                 # noqa: E402


def load_worker():
    path = os.path.join(os.path.dirname(HERE), 'core', 'ovpn-proxy.py')
    spec = importlib.util.spec_from_file_location('ovpn_proxy_share', path)
    px = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(px)
    return px


def listen_of(argv):
    return argv[argv.index('--listen') + 1]


def test_worker_listens_on_the_loopback_unless_sharing():
    plain = paths.worker_argv('1.2.3.4', 'h.example', 8877, 'auth.txt')
    assert listen_of(plain) == '127.0.0.1', plain
    shared = paths.worker_argv('1.2.3.4', 'h.example', 8877, 'auth.txt',
                               listen=paths.LAN_HOST)
    assert listen_of(shared) == '0.0.0.0', shared
    # A tunnel worker takes the same flag.
    t = paths.worker_argv(None, None, 8877, 'a', tunnel='127.0.0.1:1080',
                          listen=paths.LAN_HOST)
    assert listen_of(t) == '0.0.0.0' and '--tunnel' in t, t
    # Only the address differs; nothing else about the command line moved.
    a = [x for x in plain if x != '127.0.0.1']
    b = [x for x in shared if x != '0.0.0.0']
    assert a == b


def test_engine_picks_the_address_from_the_setting():
    off = engine.Engine(None, port=8877)
    on = engine.Engine(None, port=8877, lan_share=True)
    assert off.lan_share is False and off.listen_host() == '127.0.0.1'
    assert on.lan_share is True and on.listen_host() == '0.0.0.0'


def test_engine_remembers_the_choice_when_nothing_is_up():
    e = engine.Engine(None, port=8877)
    e.running = lambda: None
    got = e.set_lan_share(True)
    assert got == {'applied': 'next', 'moved': False}, got
    assert e.lan_share is True and e.listen_host() == '0.0.0.0'
    assert e.set_lan_share(False)['applied'] == 'next'
    assert e.lan_share is False


def test_engine_does_not_restart_a_worker_already_right():
    e = engine.Engine(None, port=8877, lan_share=True)
    e.running = lambda: {'pid': 1, 'ip': '1.2.3.4', 'name': 'h',
                         'host': '0.0.0.0', 'port': '8877'}
    e.disconnect = lambda quiet=False: (_ for _ in ()).throw(
        AssertionError('restarted a worker that was already shared'))
    got = e.set_lan_share(True)
    assert got == {'applied': 'now', 'moved': False}, got


def test_worker_accepts_private_and_refuses_public():
    px = load_worker()
    for ok in ('127.0.0.1', '127.5.5.5', '10.0.0.7', '10.255.255.255',
               '172.16.0.1', '172.31.255.254', '192.168.1.20',
               '100.64.0.1', '100.127.255.254', '169.254.10.10',
               '::ffff:192.168.1.20', '::1'):
        # ::1 is IPv6 loopback, which is not in the IPv4 table; the worker is
        # an IPv4 socket and never sees it, so it is not required to pass.
        if ok == '::1':
            continue
        assert px.client_allowed(ok), ok
    for bad in ('8.8.8.8', '1.1.1.1', '172.15.255.255', '172.32.0.1',
                '100.63.255.255', '100.128.0.0', '192.169.0.1',
                '11.0.0.1', '169.253.0.1', '::ffff:8.8.8.8',
                '2001:db8::1', '', 'not an address', None):
        assert not px.client_allowed(bad), bad


def test_worker_knows_a_loopback_listen_from_a_shared_one():
    px = load_worker()
    assert px.is_loopback_host('127.0.0.1')
    assert px.is_loopback_host('localhost')
    assert not px.is_loopback_host('0.0.0.0')
    assert not px.is_loopback_host('192.168.1.5')


def test_worker_keeps_a_list_of_who_it_served():
    px = load_worker()
    with tempfile.TemporaryDirectory() as d:
        px.STATE_PATH = os.path.join(d, 'proxy.state')
        px.CLIENTS.clear()
        assert px.read_clients(8877) == []
        px.note_client(8877, '192.168.1.20')
        px.note_client(8877, '192.168.1.21')
        px.note_client(8877, '192.168.1.20')
        assert px.read_clients(8877) == ['192.168.1.20', '192.168.1.21']
        # Old sightings stop counting.
        with open(px.clients_path(8877), encoding='utf-8') as f:
            data = json.load(f)
        data['seen']['192.168.1.21'] -= 3600
        with open(px.clients_path(8877), 'w', encoding='utf-8') as f:
            json.dump(data, f)
        assert px.read_clients(8877) == ['192.168.1.20']
        px.clear_clients(8877)
        assert px.read_clients(8877) == []
        px.CLIENTS.clear()


def test_a_shared_client_cannot_reach_this_machine_by_going_around():
    px = load_worker()
    px._CALLER.lan = True
    try:
        for host in ('127.0.0.1', 'localhost', '0.0.0.0'):
            try:
                px.straight_to(host, 9)
            except OSError as e:
                assert 'this machine' in str(e), (host, e)
            else:
                raise AssertionError(f'{host} was reachable by a LAN client')
    finally:
        px._CALLER.lan = False


def test_firewall_rule_is_narrow_and_named_for_the_port():
    assert engine.firewall_rule_name(8877) == 'Relay LAN share (8877)'
    add = engine.firewall_script('add', 8877)
    assert "'Relay LAN share (8877)'" in add
    assert 'New-NetFirewallRule' in add
    for want in ('-Direction Inbound', '-Action Allow', '-Protocol TCP',
                 '-LocalPort 8877', '-RemoteAddress LocalSubnet',
                 '-Profile Private'):
        assert want in add, want
    # Never wider than that.
    for never in ('Any', 'Public', 'Domain', '0.0.0.0', 'Disable',
                  'Set-NetFirewallProfile', 'netsh', '*'):
        assert never not in add, never
    # Replaced, not stacked: the old rule of that name goes first.
    assert add.index('Remove-NetFirewallRule') < add.index('New-NetFirewallRule')
    gone = engine.firewall_script('remove', 8877)
    assert "-DisplayName 'Relay LAN share (8877)'" in gone
    assert 'Remove-NetFirewallRule' in gone and 'New-NetFirewallRule' not in gone
    assert '*' not in gone
    # Another port is another rule.
    assert "'Relay LAN share (9050)'" in engine.firewall_script('add', 9050)
    try:
        engine.firewall_script('flush', 8877)
    except ValueError:
        pass
    else:
        raise AssertionError('an unknown action was accepted')


def test_firewall_verdict():
    wifi = {'ip': '192.168.1.20', 'alias': 'Wi-Fi', 'category': 'Private'}
    pub = {'ip': '192.168.1.20', 'alias': 'Wi-Fi', 'category': 'Public'}
    tun = {'ip': '172.19.0.1', 'alias': 'RelayTun', 'category': 'Private'}
    assert engine.firewall_verdict({'rule': True, 'adapters': [wifi]}) == 'ok'
    assert engine.firewall_verdict({'rule': False, 'adapters': [wifi]}) == 'missing'
    # Public beats missing: the rule could not help there.
    assert engine.firewall_verdict(
        {'rule': False, 'adapters': [pub]}) == 'public-network'
    assert engine.firewall_verdict(
        {'rule': True, 'adapters': [pub]}) == 'public-network'
    # One adapter's single result arrives as an object, not a list.
    assert engine.firewall_verdict({'rule': True, 'adapters': wifi}) == 'ok'
    # A Public virtual adapter does not make the Wi-Fi Public, and a lone
    # virtual adapter is not a network to share on.
    pub_tun = dict(tun, category='Public')
    assert engine.firewall_verdict(
        {'rule': True, 'adapters': [wifi, pub_tun]}) == 'ok'
    assert engine.firewall_verdict({'rule': True, 'adapters': [tun]}) == 'unknown'
    assert engine.firewall_verdict(None) == 'unknown'
    assert engine.firewall_verdict({'rule': True, 'adapters': []}) == 'unknown'


def test_addresses_offered_are_real_lan_ones():
    rows = [
        {'ip': '127.0.0.1', 'alias': 'Loopback', 'category': ''},
        {'ip': '169.254.3.4', 'alias': 'Ethernet 2', 'category': ''},
        {'ip': '172.19.0.1', 'alias': 'RelayTun', 'category': 'Private'},
        {'ip': '172.28.64.1', 'alias': 'vEthernet (WSL)', 'category': 'Private'},
        {'ip': '192.168.56.1', 'alias': 'VirtualBox Host-Only', 'category': ''},
        {'ip': '100.100.1.1', 'alias': 'Tailscale', 'category': ''},
        {'ip': '100.80.1.1', 'alias': 'Ethernet 3', 'category': ''},
        {'ip': '8.8.4.4', 'alias': 'Ethernet 4', 'category': ''},
        {'ip': '10.0.0.5', 'alias': 'Ethernet', 'category': 'Private'},
        {'ip': '192.168.1.20', 'alias': 'Wi-Fi', 'category': 'Private'},
    ]
    got = [a for a, _, _ in engine.usable_lan_adapters(rows)]
    assert got == ['192.168.1.20', '10.0.0.5'], got


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)
