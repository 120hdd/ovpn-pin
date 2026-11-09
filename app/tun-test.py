#!/usr/bin/env python3
"""Read the sing-box configuration tunnel mode would run, without running it.

    python app/tun-test.py

The adapter itself needs administrator rights and a real connection, so it
is not something a test can bring up. What can be held still is the part
that decides whether the machine survives it: that the exit and the tunnel
client are routed around the adapter rather than into it, that UDP is
refused, that DNS goes where it should, and that the script handed to an
elevated PowerShell quotes what it is given.
"""
import ipaddress
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tun                                                   # noqa: E402


def rule_with(rules, key):
    return [r for r in rules if key in r]


def test_exit_is_routed_around_the_adapter():
    cfg = tun.build_config(8877, keep=['62.197.149.105', '127.0.0.1:9091',
                                       '10.1.2.3', 'not an address', None])
    excluded = cfg['inbounds'][0]['route_exclude_address']
    assert '62.197.149.105/32' in excluded, excluded
    # A tunnel's local end, a private address and junk are not routes.
    assert not [x for x in excluded if x.startswith('127.')], excluded
    assert '10.1.2.3/32' not in excluded, excluded
    for net in tun.LOCAL_NETS:
        assert net in excluded, net
    # The adapter's own subnet must not be among the things routed around.
    own = ipaddress.ip_network(tun.ADDRESS, strict=False)
    assert not any(own.overlaps(ipaddress.ip_network(n)) for n in excluded)


def test_traffic_goes_to_the_worker():
    cfg = tun.build_config(9050)
    relay = [o for o in cfg['outbounds'] if o['tag'] == 'relay'][0]
    assert relay['type'] == 'socks' and relay['server'] == '127.0.0.1'
    assert relay['server_port'] == 9050
    assert cfg['route']['final'] == 'relay'
    assert cfg['route']['auto_detect_interface'] is True
    tun_in = cfg['inbounds'][0]
    assert tun_in['auto_route'] and tun_in['strict_route']
    assert tun_in['interface_name'] == tun.ADAPTER


def test_udp_refused_after_dns():
    rules = tun.build_config(8877)['route']['rules']
    dns = rules.index([r for r in rules if r.get('action') == 'hijack-dns'][0])
    udp = rules.index([r for r in rules if r.get('network') == 'udp'][0])
    assert rules[udp]['action'] == 'reject'
    assert dns < udp, 'DNS has to be answered before UDP is refused'


def test_own_and_carriers_go_straight_out():
    me = r'C:\Program Files\Relay\Relay.exe'
    gost = r'C:\Program Files\Relay\gost.exe'
    cfg = tun.build_config(8877, own=[me], carriers=[gost, None])
    rules = cfg['route']['rules']
    straight = rule_with(rules, 'process_path_regex')
    assert len(straight) == 1 and straight[0]['outbound'] == 'direct'
    patterns = straight[0]['process_path_regex']
    assert len(patterns) == 2
    # Before UDP is refused, so this app's own UDP is not.
    assert rules.index(straight[0]) < rules.index(
        [r for r in rules if r.get('network') == 'udp'][0])
    # Case-insensitive, and anchored: Relay.exe.bak is somebody else.
    rx = re.compile(patterns[0].replace('(?i)', ''), re.I)
    assert rx.match(me.lower())
    assert not rx.match(me + '.bak')
    # gost's DNS is asked directly, or it would wait on itself.
    dns_rules = cfg['dns']['rules']
    assert len(dns_rules) == 1 and dns_rules[0]['server'] == 'dns-direct'
    assert len(dns_rules[0]['process_path_regex']) == 1
    assert cfg['dns']['final'] == 'dns-remote'
    remote = [s for s in cfg['dns']['servers'] if s['tag'] == 'dns-remote'][0]
    assert remote['detour'] == 'relay'
    assert cfg['dns']['strategy'] == 'ipv4_only'


def test_no_carriers_no_dns_rule():
    cfg = tun.build_config(8877)
    assert cfg['dns']['rules'] == []
    assert not rule_with(cfg['route']['rules'], 'process_path_regex')


def test_wrapper_quotes_what_it_is_given():
    text = tun.WRAPPER.format(
        exe=tun._ps_string(r"C:\Users\O'Brien\Relay\sing-box.exe"),
        argv=tun._ps_array(['run', '-c', r'C:\Users\A B\config.json']),
        out="'o'", err="'e'", pidf="'p'", ready="'r'", failed="'f'",
        stop="'s'", done="'d'", nic="'RelayTun'", watch=1234, startup=25)
    assert "O''Brien" in text
    assert '"C:\\Users\\A B\\config.json"' in text
    assert '$watch  = 1234' in text
    # Every brace that PowerShell needs survived str.format.
    assert text.count('{') == text.count('}')


def test_explain():
    assert 'sing-box.exe' in tun.explain('tun-missing: x')
    assert 'administrator' in tun.explain(RuntimeError('tun-refused: no'))
    assert 'boom' in tun.explain(RuntimeError('tun-failed: boom'))
    assert tun.explain(RuntimeError('all-refused')) is None


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)
