"""Split tunnelling: which connections go through the exit and which go
straight out by this machine's own line.

One mode decides the direction and one list decides what it applies to:

  off     everything through the exit - what there was before this
  bypass  everything through the exit except what is listed
  only    only what is listed goes through the exit

The list is programs (by exe name), names (a domain means itself and
everything under it), addresses and prefixes, and the Iran preset - Iran's
registered address ranges and the domains of Iranian services, read from
lists/ at the top of the repo, the same files the phone embeds.

Who carries the rules depends on how Windows is routed, because the two
ways see different things:

  tun     sing-box sees every connection: its program, its address, and -
          by sniffing the first packet - its name. It gets every rule.
  proxy   the worker sees what proxy-aware programs hand it: a name or an
          address, and the program that opened the socket. It gets every
          rule too, but a program that ignores the proxy setting never
          reaches it, and a name is matched as a name - looking every one
          up to compare its address would send the whole browsing history
          to this line's resolver.

Never both at once: in tun mode the worker is told "off", so a connection
sing-box already decided is not decided again by something that can see
less of it. This module is what both are built from, so they agree.
"""

import bisect
import ipaddress
import json
import os

import paths

MODES = ('off', 'bypass', 'only')

LISTS_DIR = os.path.join(paths.RES_DIR if paths.FROZEN else paths.DATA_DIR,
                         'lists')
WORKER_FILE = os.path.join(paths.STATE_DIR, 'split.json')


class Invalid(ValueError):
    pass


def _punycode_label(label):
    if all(ord(c) < 128 for c in label):
        return label
    # Raw RFC 3492, no IDNA mapping: what the phone's core does, so the two
    # spell every name the same way.
    return 'xn--' + label.lower().encode('punycode').decode('ascii')


def _valid_label(label):
    if not label or len(label) > 63 or label[0] == '-' or label[-1] == '-':
        return False
    return all(c.isascii() and (c.isalnum() or c in '-_') for c in label)


def normalize(text):
    """What somebody typed or pasted, in the one form a rule is kept in.

    The same answers as NormalizeSplitEntry in mobile/core/split.go:

        https://WWW.Digikala.com/x?y  ->  www.digikala.com
        *.digikala.com, .digikala.com ->  digikala.com
        digikala.com:443              ->  digikala.com
        5.6.7.8/16                    ->  5.6.0.0/16

    IPv6 is refused: nothing here carries it, so a rule for it never matches.
    """
    s = str(text or '').strip().lower()
    if not s:
        raise Invalid('empty')
    if '/' in s:
        try:
            net = ipaddress.ip_network(s, strict=False)
        except ValueError:
            net = None
        if net is not None:
            if net.version != 4:
                raise Invalid(f'{text!r} is IPv6, which is not carried')
            return str(net)
    if '://' in s:
        s = s.split('://', 1)[1]
    for sep in '/?#':
        s = s.split(sep, 1)[0]
    s = s.rsplit('@', 1)[-1]
    if s.startswith('['):
        s = s[1:].split(']', 1)[0]
    elif s.count(':') == 1:
        host, port = s.split(':')
        if port.isdigit():
            s = host
    try:
        addr = ipaddress.ip_address(s)
    except ValueError:
        addr = None
    if addr is not None:
        if addr.version == 6:
            if addr.ipv4_mapped is None:
                raise Invalid(f'{text!r} is IPv6, which is not carried')
            addr = addr.ipv4_mapped
        return str(addr)
    s = s.lstrip('*').strip('.')
    if not s:
        raise Invalid(f'{text!r} is not a domain or an address')
    try:
        labels = [_punycode_label(l) for l in s.split('.')]
    except UnicodeError:
        raise Invalid(f'{text!r} is not a domain or an address')
    name = '.'.join(labels)
    if len(name) > 253:
        raise Invalid(f'{text!r} is longer than a domain can be')
    if not all(_valid_label(l) for l in labels):
        raise Invalid(f'{text!r} is not a domain or an address')
    return name


def normalize_app(text):
    """A program as a rule names it: the exe's file name, lowercased.

    A path is cut to its name, and a name without .exe gets it - Windows
    reports the image name with it, and "telegram" would never match.
    """
    name = os.path.basename(str(text or '').strip().strip('"')).lower()
    if not name or any(c in name for c in '<>:"|?*'):
        raise Invalid(f'{text!r} is not a program')
    if not name.endswith('.exe'):
        name += '.exe'
    return name


def _lines(name):
    try:
        with open(os.path.join(LISTS_DIR, name), encoding='utf-8') as f:
            text = f.read()
    except OSError:
        return []
    out = []
    for line in text.splitlines():
        line = line.split('#', 1)[0].strip()
        if line:
            out.append(line)
    return out


def iran_lists():
    """(domains, prefixes) of the Iran preset; empty when lists/ is missing."""
    return _lines('iran-domains.txt'), _lines('iran-ipv4.txt')


def iran_counts():
    d, r = iran_lists()
    return {'domains': len(d), 'ranges': len(r)}


def clean(settings):
    """The four settings as they are kept, from whatever was saved or sent.

    Returns (split, rejected): what survived, and every entry that could not
    be a rule, as it was typed - so the window can say which and why rather
    than dropping it in silence.
    """
    settings = settings or {}
    mode = settings.get('mode')
    split = {'mode': mode if mode in MODES else 'off',
             'iran': bool(settings.get('iran', True)),
             'apps': [], 'domains': []}
    rejected = []
    for value in settings.get('apps') or []:
        try:
            name = normalize_app(value)
        except Invalid:
            rejected.append(str(value))
            continue
        if name not in split['apps']:
            split['apps'].append(name)
    for value in settings.get('domains') or []:
        try:
            entry = normalize(value)
        except Invalid:
            rejected.append(str(value))
            continue
        if entry not in split['domains']:
            split['domains'].append(entry)
    return split, rejected


def rules(split):
    """Everything a carrier needs to decide, expanded: the preset folded in,
    names and prefixes apart. None when there is nothing to decide - off, or
    "only" with nothing listed, which would be a VPN that carries nothing.
    """
    split, _ = clean(split)
    if split['mode'] == 'off':
        return None
    names, nets = [], []
    entries = list(split['domains'])
    if split['iran']:
        d, r = iran_lists()
        entries = d + r + entries
    for entry in entries:
        try:
            norm = normalize(entry)
        except Invalid:
            continue
        try:
            nets.append(ipaddress.ip_network(norm, strict=False))
        except ValueError:
            names.append(norm)
    nets = [str(n) for n in ipaddress.collapse_addresses(
        n for n in nets if n.version == 4)]
    if not (names or nets or split['apps']):
        return None
    return {'mode': split['mode'], 'names': sorted(set(names)), 'nets': nets,
            'apps': list(split['apps'])}


def write_worker_file(split, path=WORKER_FILE):
    """The worker's copy of the rules, which it reads again whenever the file
    changes - so a change applies to the next connection without a restart.
    """
    r = rules(split) if split else None
    data = r or {'mode': 'off'}
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(data, f)
    os.replace(tmp, path)
    return data


class Matcher:
    """The rules as a question with a yes or no, for tests and the selftest;
    the worker carries its own copy of this logic, being a separate program.
    """

    def __init__(self, r):
        r = r or {'mode': 'off'}
        self.mode = r.get('mode', 'off')
        self.names = set(r.get('names') or ())
        self.apps = {a.lower() for a in r.get('apps') or ()}
        spans = []
        for n in r.get('nets') or ():
            net = ipaddress.ip_network(n, strict=False)
            spans.append((int(net.network_address), int(net.broadcast_address)))
        spans.sort()
        self.lo = [s[0] for s in spans]
        self.hi = [s[1] for s in spans]

    def listed(self, host, app=None):
        if app and app.lower() in self.apps:
            return True
        host = (host or '').strip('[]').rstrip('.').lower()
        try:
            v = int(ipaddress.IPv4Address(host))
        except ValueError:
            v = None
        if v is not None:
            i = bisect.bisect_right(self.lo, v)
            return i > 0 and self.hi[i - 1] >= v
        while host:
            if host in self.names:
                return True
            host = host.partition('.')[2]
        return False

    def straight(self, host, app=None):
        if self.mode == 'bypass':
            return self.listed(host, app)
        if self.mode == 'only':
            return not self.listed(host, app)
        return False
