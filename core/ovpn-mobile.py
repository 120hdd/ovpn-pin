#!/usr/bin/env python3
"""Turn pinned configs into ones a phone can hold.

Point it at a .ovpn file or a folder of them and it writes the phone's
version beside your other state:

    core/ovpn-mobile.py pinned
    core/ovpn-mobile.py success --count 12
    core/ovpn-mobile.py pinned/de-fra.prod.surfshark.com_tcp_138.199.19.157.ovpn
    core/ovpn-mobile.py pinned success/02.0s-fr-par*.ovpn --out phone

Two formats come out, because the phone clients disagree about which they
read. Clash (mihomo) YAML by default: Karing, Streisand, Hiddify, Shadowrocket
and Stash all take it, and it is what to reach for when an app says it cannot
parse a config. sing-box JSON with --format singbox, for the clients that run
a config verbatim rather than rebuilding it from their own settings screens.

The connect half of this repo is, on the wire, an ordinary HTTPS proxy client
with four habits no proxy field in any GUI asks for: it dials a pinned address
rather than a name, it sends no SNI, it verifies the certificate against the
real name anyway, and it puts Basic credentials on every request.

sing-box does all four - `disable_sni` next to `server_name` is the rare pair
that keeps the verification while dropping the announcement. Clash does three:
its `sni` sets the name that is sent as well as the one that is checked, so
naming the exit there gets the handshake killed on the way out, and the only
way to keep the name off the wire is `skip-cert-verify: true`. The address is
still pinned, so what is given up is proof of who answers at that address, not
knowledge of which address is asked. Said out loud in the file itself.

Neither is a port of ovpn-proxy.py. Both are the same conversation described
in somebody else's vocabulary, so the phone can hold it.

Nothing protocol-shaped is reimplemented. The address and the name are read
back out of each config by ovpn-proxy.py's own read_config, and the
credentials by its read_auth, for the same reason engine.py borrows rather
than copies: those are the functions that were argued over, and a second copy
of them drifts.

The one thing it adds is what the desktop does by asking and a phone cannot:
most exits refuse these credentials at any given moment - see note.md - so a
single exit in a phone config is a config that works on Tuesday. Several
behind a `urltest` group is the sweep's answer without the sweep. sing-box
tests them itself, a 407 fails the test, and it settles on one that answers.
Kept short and slow-polling on purpose, because that same note records an
account locked for too many logins an hour.

The addresses have to be in the file because on a censored line the phone
cannot find them for itself: the system resolver answers 10.10.34.35, DoT and
DNS-over-TCP are both dead on 853 and 53, and sing-box's own DoH to 1.1.1.1
times out even when all it has to resolve is example.com - measured, five
config shapes, including one wearing a Chrome TLS fingerprint. Pinning
happens here, once, and the phone is on its own afterwards.
"""

import argparse
import glob
import importlib.util
import json
import os
import re
import sys

# Two different questions, and answering both with one name is how a tool
# ends up writing into core/ and reporting success. HERE is core/, where
# ovpn-proxy.py is a sibling; ROOT is the folder above it, where the
# reader's pinned configs, credentials and .state actually are.
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

# 01.8s-no-osl.prod.surfshark.com_tcp_185.253.97.117.ovpn -> the exit's name.
# The leading seconds are what the tunnel sweep recorded; they are part of the
# filename and no part of the exit.
FROM_FILE = re.compile(r'^(?:\d+\.\d+s-)?(.+?)_(?:tcp|udp)_', re.I)


def load_proxy_module():
    """ovpn-proxy.py has a dash in its name and cannot be imported the
    ordinary way."""
    path = os.path.join(HERE, 'ovpn-proxy.py')
    spec = importlib.util.spec_from_file_location('ovpn_proxy', path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


px = load_proxy_module()


def configs_under(path):
    """Every .ovpn a path stands for.

    A file is itself, a folder is what is in it, and anything with a * in it
    is left to glob - which is what a Windows shell hands over unexpanded and
    a Unix one has usually expanded already. Sorted, because the sweep names
    its output by how long the exit took to answer, so sorted order is
    quickest-first and that is the order worth keeping.
    """
    if os.path.isfile(path):
        return [os.path.abspath(path)]
    if os.path.isdir(path):
        return sorted(os.path.abspath(p) for p in
                      glob.glob(os.path.join(path, '*.ovpn')))
    hits = sorted(os.path.abspath(p) for p in glob.glob(path)
                  if p.lower().endswith('.ovpn'))
    if hits:
        return hits
    px.die(f'nothing to read at {path}',
           'Give it a .ovpn file, or a folder with some in it. If you have\n'
           'not pinned anything yet:  ovpn pin')


def exit_name(filename, from_comment):
    """The name the certificate has to serve.

    The pin comment is the honest source - it is what the address was
    actually resolved from - and the filename is the fallback for a config
    pinned before that line existed.
    """
    if from_comment:
        return from_comment
    m = FROM_FILE.match(filename)
    if not m:
        px.die(f'cannot tell what name {filename} belongs to',
               'Re-pin it so it carries the "# name -> address" line: ovpn pin')
    return m.group(1)


def answers(ip, name, user, password, bind, timeout=8):
    """Whether that address will take these credentials right now.

    ovpn-proxy's own can_connect, which stops at the 200 and fetches nothing.
    Worth having because the two ways an address fails here are invisible in
    the file that names it: an exit can refuse the account (407), and an
    address can be filtered on this line - TCP answers, TLS gets nothing back,
    and the phone shows a node that simply never connects.

    Both are per-address. no-osl has twelve of them; four are swallowed on
    this line and eight answer in under a second, and the folder ordering
    cannot tell you which is which because it sorts by how fast the *tunnel*
    was, a different question about a different port.
    """
    try:
        return px.can_connect(px.Exit(ip, 443, name, user, password, bind),
                              timeout)
    except Exception:
        return None


def gather(paths, limit, check=None):
    """The exits to put in the file, as (address, name, tag) triples.

    One address per exit, and the first one wins. Deliberately not
    ovpn-proxy's one_per_exit: that keys on everything before the first
    underscore, which in the sweep's own output includes the seconds, so two
    addresses for one exit measured at different speeds read as two exits.
    Here the name is derived first and the deduplication happens on that.

    With check set, an exit is not finished at its first address - the rest
    are tried until one answers, and only then does the exit count as taken.
    Without it, nothing here touches the network.
    """
    seen, out, read = set(), [], 0
    for path in paths:
        read += 1
        ip, from_comment = px.read_config(path)
        name = exit_name(os.path.basename(path), from_comment)
        if name in seen:
            continue
        if check is not None:
            took = answers(ip, name, *check)
            if took is None:
                continue        # another address for this exit may still do
            print(f'    {name:<30}{ip:<17}{took:.2f}s')
        seen.add(name)
        # de-fra.prod.surfshark.com + 138.199.19.157 -> de-fra-138.199.19.157.
        # The address is in the tag on purpose: two addresses for one exit are
        # two different verdicts, and a tag that hides which one answered is a
        # tag you cannot act on.
        out.append((ip, name, name.split('.')[0] + '-' + ip))
        if limit and len(out) >= limit:
            break
    if not out:
        px.die('none of those paths held a pinned config')
    return out, read


def outbounds_for(exits, user, password, exit_port, interval):
    """One http outbound per exit, and the group that chooses between them."""
    outs = [{
        'type': 'http',
        'tag': tag,
        'server': ip,
        'server_port': exit_port,
        'username': user,
        'password': password,
        'tls': {
            'enabled': True,
            # Verified against, never sent. This is the whole trick.
            'server_name': name,
            'disable_sni': True,
        },
    } for ip, name, tag in exits]

    outs.append({
        'type': 'urltest',
        'tag': 'exit',
        'outbounds': [tag for _, _, tag in exits],
        # An address, not the usual gstatic name, and this is not taste. The
        # DNS server below is detoured through this group, so a group whose
        # own test needs a name resolved cannot start: the test waits on DNS,
        # DNS waits on the test, and the phone sits there connected to
        # nothing with no error to show for it. Measured - with generate_204
        # here a fetch hung until it was killed; with this it answers in under
        # a second.
        'url': 'https://1.1.1.1/cdn-cgi/trace',
        'interval': interval,
        'tolerance': 100,
    })
    return outs


def build(exits, user, password, exit_port, interval, nodes_only=False):
    """The config itself.

    Everything that is not an outbound is here for one reason each: the tun
    inbound because a phone has no system proxy worth setting, the DoH server
    detoured through the exit because a resolver that can be asked can be
    lied to, and the udp reject because CONNECT carries TCP and nothing else -
    without it QUIC waits out a timeout instead of falling back to TCP in the
    same second.

    strict_route is deliberately absent: the Apple client documents it as not
    implemented, and a phone that refuses to start over an option it cannot
    honour is worse than one without it.

    --nodes-only exists for the clients that read a config for its outbounds
    and build their own tun, DNS and routing from their own settings screens.
    Handing those the whole thing is not wrong, it is ignored - and a file
    that says only what that client will read is easier to argue with when it
    does not work.
    """
    outs = outbounds_for(exits, user, password, exit_port, interval)
    if nodes_only:
        return {'outbounds': outs}

    return {
        'log': {'level': 'warn'},
        'dns': {
            'servers': [{
                'type': 'https',
                'tag': 'doh',
                # An address, not a name: a name here would have to be
                # resolved by the thing this exists to stop using.
                'server': '1.1.1.1',
                'detour': 'exit',
            }],
            # A records only. The exit is reached over v4 and CONNECT to a v6
            # literal is a question this fleet has not been asked here - an
            # app handed an AAAA would be the one asking, and would fail at it
            # while every v4 site worked, which is the worst shape a fault can
            # have.
            'strategy': 'ipv4_only',
        },
        'inbounds': [{
            'type': 'tun',
            'tag': 'tun-in',
            'address': ['172.19.0.1/30'],
            'mtu': 9000,
            'auto_route': True,
        }],
        'outbounds': outs,
        'route': {
            'final': 'exit',
            'auto_detect_interface': True,
            'rules': [
                {'action': 'sniff'},
                {'protocol': 'dns', 'action': 'hijack-dns'},
                {'network': 'udp', 'action': 'reject'},
            ],
        },
    }


def yaml_string(value):
    """Quoted, because a password is not required to be YAML-shaped.

    A service password beginning with a digit, or holding a colon, a #, or a
    leading zero, is read by YAML as a number or a comment or a mapping and
    the client then authenticates with something the user never typed. Double
    quotes and two escapes are all that is needed for the values here, and
    they cost nothing on values that would have been fine bare.
    """
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"') + '"'


def clash_yaml(exits, user, password, exit_port, interval, nodes_only=False):
    """The same exits in Clash's vocabulary.

    Written by hand rather than dumped, because pyyaml is not in the standard
    library and this file is worth more than the dependency: the comments
    below survive into the phone's copy, where somebody reading it later will
    want to know why the certificate is not being checked.

    Only the parts a phone client reads from an imported profile - the exits,
    the group that picks between them, and a rule sending everything there.
    The tunnel, the DNS and the ports belong to the app's own settings, and a
    file that argues with those is a file that gets half-applied.

    Which is also why there is no `NETWORK,udp,REJECT` here, tempting as it is:
    Karing's published Clash rule support is IP-CIDR, DOMAIN and friends, and
    NETWORK is not among them. A rule type a client does not know is not a
    feature it ignores - it is a profile that fails to load, and it fails
    without saying which line did it. QUIC costs a timeout instead. That is
    the cheaper of the two.

    --nodes-only strips it back to the exits alone, which is the shape every
    client understands, for the ones that treat an imported file as a list of
    nodes and do their own grouping and routing from their own screens.
    """
    seconds = interval_seconds(interval)
    L = [
        '# Written by ovpn-mobile.py. The addresses are pinned - resolved over',
        '# DoH on a machine that could still do it - so nothing here depends on',
        '# a resolver answering honestly.',
        '#',
        '# skip-cert-verify is on, and it is not laziness. Clash sends whatever',
        '# `sni` says, so naming the exit there puts *.prod.surfshark.com in the',
        '# clear and the handshake dies on the way out; leaving it off means the',
        '# certificate is checked against an IP it was never issued for. The',
        '# address is still pinned either way. What is given up is proof of who',
        '# answers at that address - on a client that can keep both, sing-box',
        '# holds the name back and verifies against it anyway.',
        '',
        'proxies:',
    ]
    for ip, name, tag in exits:
        L += [
            f'  - name: {yaml_string(tag)}',
            '    type: http',
            f'    server: {ip}',
            f'    port: {exit_port}',
            f'    username: {yaml_string(user)}',
            f'    password: {yaml_string(password)}',
            '    tls: true',
            '    skip-cert-verify: true',
            f'    # {name}',
        ]

    if nodes_only:
        return '\n'.join(L) + '\n'

    L += ['', 'proxy-groups:', '  - name: "exit"', '    type: url-test',
          '    proxies:']
    L += [f'      - {yaml_string(tag)}' for _, _, tag in exits]
    # An address, not a name: the group has to be able to test itself before
    # anything has resolved anything.
    L += [f'    url: "https://1.1.1.1/cdn-cgi/trace"',
          f'    interval: {seconds}',
          '    tolerance: 100',
          '',
          'rules:',
          '  - MATCH,exit']
    return '\n'.join(L) + '\n'


def interval_seconds(interval):
    """Clash counts in seconds and sing-box reads durations, so 30m has to
    become 1800 on the way past."""
    text = str(interval).strip().lower()
    units = {'s': 1, 'm': 60, 'h': 3600}
    if text and text[-1] in units:
        try:
            return int(float(text[:-1]) * units[text[-1]])
        except ValueError:
            pass
    try:
        return int(text)
    except ValueError:
        px.die(f'{interval!r} is not a length of time',
               'Say it as 45s, 30m or 2h.')


def main():
    p = argparse.ArgumentParser(
        prog='core/ovpn-mobile.py',
        description='write the phone version of a pinned config')
    p.add_argument('paths', nargs='*',
                   help='a .ovpn file, or a folder of them. Several are fine. '
                        'Left out, pinned/ is used.')
    p.add_argument('--format', choices=('clash', 'singbox', 'both'),
                   default='both',
                   help='which vocabulary to write it in. clash is the one '
                        'every phone client reads; singbox keeps the '
                        'certificate check. Default: both')
    p.add_argument('--out', default=os.path.join(ROOT, '.state', 'mobile'),
                   help='where to write it, with or without an extension '
                        '(.state/mobile). .state/ is not tracked by git, '
                        'which matters - these files hold your password')
    p.add_argument('--count', type=int, default=10,
                   help='how many exits to take from a folder (10). More is '
                        'more chances and more logins an hour; see note.md')
    p.add_argument('--all', action='store_true',
                   help='take every config found, however many that is')
    p.add_argument('--check', action='store_true',
                   help='ask each address whether it takes the credentials '
                        'now, and skip the ones that do not. Slower, and the '
                        'only way to catch an address filtered on this line')
    p.add_argument('--bind', metavar='ADDRESS',
                   help='source address for the checks. Needed when a tunnel '
                        'owns the default route, or --check answers for the '
                        'tunnel rather than for the line the phone will use')
    p.add_argument('--nodes-only', action='store_true',
                   help='the exits alone, no group and no rules, for clients '
                        'that read an imported file as a list of nodes and do '
                        'their own grouping. Applies to both formats.')
    p.add_argument('--auth', default=os.path.join(ROOT, '.ovpn-auth'),
                   help='the credentials file (.ovpn-auth)')
    p.add_argument('--exit-port', type=int, default=443,
                   help="the exit's proxy port (443)")
    p.add_argument('--interval', default='30m',
                   help='how often the client re-tests the exits (30m)')
    args = p.parse_args()

    paths = args.paths or [os.path.join(ROOT, 'pinned')]
    found = []
    for path in paths:
        found.extend(configs_under(path))

    user, password = px.read_auth(args.auth)
    check = (user, password, args.bind) if args.check else None
    if check:
        print('\n  asking each address whether it answers'
              + (f', leaving from {args.bind}' if args.bind else '') + ':')
    exits, read = gather(found, 0 if args.all else max(1, args.count), check)

    # An --out that names an extension has already said which format it wants,
    # and honouring that beats writing phone.yaml.json.
    stem, ext = os.path.splitext(os.path.abspath(args.out))
    wanted = {'.yaml': ('clash',), '.yml': ('clash',), '.json': ('singbox',)}
    formats = wanted.get(ext.lower())
    if formats is None:
        stem = os.path.abspath(args.out)
        formats = ('clash', 'singbox') if args.format == 'both' else (args.format,)
    os.makedirs(os.path.dirname(stem) or '.', exist_ok=True)

    written = []
    for fmt in formats:
        if fmt == 'clash':
            path = stem + (ext if ext else '.yaml')
            body = clash_yaml(exits, user, password, args.exit_port,
                              args.interval, args.nodes_only)
        else:
            path = stem + (ext if ext else '.json')
            body = json.dumps(build(exits, user, password, args.exit_port,
                                    args.interval, args.nodes_only),
                              indent=2) + '\n'
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(body)
        written.append((fmt, path))

    print()
    for fmt, path in written:
        print(f'  wrote {path}   ({fmt})')
    print(f'  {len(exits)} exit{"" if len(exits) == 1 else "s"}, read from '
          f'{read} of the {len(found)} config'
          f'{"" if len(found) == 1 else "s"} those paths hold, '
          f're-tested every {args.interval}:')
    for _, name, tag in exits:
        print(f'    {tag:<34}{name}')
    print()
    print('  Import the .yaml first - it is the one every phone client reads.')
    print('  Both hold your service password in clear text. Nowhere a URL')
    print('  alone would reach them - no gists, no pastebins.')
    print()


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
