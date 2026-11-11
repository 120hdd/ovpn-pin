"""Configs for other devices: an iPhone, a friend's laptop - anything with a
V2Ray client and no Relay - reaching the user's own server, and through it a
provider location picked here.

The server keeps a handful of numbered locations ("slots"). Slot k is a
chain on its gost, loc-<k>, which this app points at the location's exit
that uploads best from the server, and a user on its sing-box, slot-<k>,
routed to that chain. Slot 0 is the server itself. A link is one slot as a
V2Ray client reads it: VLESS or VMess over WebSocket and TLS, through the
same nginx - and the same Cloudflare - the apps use.

The users' UUIDs are derived from one id in the key, the way the installer
derived them, so the key stays short. The phone makes the same links in
mobile/core/export.go; export-test.py holds the two to the same answers.
"""

import base64
import json
import urllib.parse
import uuid

import tunnel

VLESS_PATH = '/vl'
VMESS_PATH = '/vm'
MAX_SLOTS = 32


def slot_uuid(export_id, k):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f'relay-export:{export_id}:{k}'))


def _vless(uid, addr, host, by_ip, name, port=443):
    q = {'encryption': 'none', 'security': 'tls', 'type': 'ws',
         'path': VLESS_PATH}
    if by_ip:
        # Its own certificate, which no device trusts by itself and these
        # clients cannot pin. The apps pin it; a link can only accept it.
        q['allowInsecure'] = '1'
    else:
        q['sni'] = host
        q['host'] = host
    q['fp'] = 'chrome'
    query = urllib.parse.urlencode(sorted(q.items()))
    return (f'vless://{uid}@{addr}:{int(port)}?{query}#'
            + urllib.parse.quote(name, safe=''))


def _vmess(uid, addr, host, by_ip, name, port=443):
    m = {'v': '2', 'ps': name, 'add': addr, 'port': str(int(port)), 'id': uid,
         'aid': '0', 'scy': 'auto', 'net': 'ws', 'type': 'none',
         'path': VMESS_PATH, 'tls': 'tls', 'fp': 'chrome'}
    if by_ip:
        m['allowInsecure'] = '1'
    else:
        m['host'] = host
        m['sni'] = host
    raw = json.dumps(m, ensure_ascii=False, separators=(',', ':'),
                     sort_keys=True).encode()
    return 'vmess://' + base64.b64encode(raw).decode()


def _ss(host, ss, name):
    user = base64.urlsafe_b64encode(
        f'{ss["method"]}:{ss["password"]}'.encode()).decode().rstrip('=')
    return f'ss://{user}@{host}:{ss["port"]}#' + urllib.parse.quote(name, safe='')


def links(host, export_id, slots, edge=None, ss=None, port=443):
    """Every slot's links: [{k, name, vless, vmess, ss?}].

    host  the server as the key has it; edge a Cloudflare address measured
    to carry its name from here, used as the address for a server by name so
    the device does not have to look the name up on a line that lies about
    it. ss is the key's Shadowsocks, for the server's own slot only - its
    other port follows whatever this app last connected to. port is where
    the server's TLS answers, from the key - 443 unless it said otherwise.
    """
    if not host or not export_id:
        raise ValueError("this server's key has no exported locations - run "
                         'the installer from this version again and paste '
                         'its new key')
    by_ip = tunnel.is_address(host)
    addr = edge if (edge and not by_ip and tunnel.is_address(edge)) else host
    out = []
    for slot in slots:
        k, name = int(slot['k']), str(slot['name'])
        if not 0 <= k <= MAX_SLOTS:
            raise ValueError(f'slot {k} is out of range')
        uid = slot_uuid(export_id, k)
        row = {'k': k, 'name': name,
               'vless': _vless(uid, addr, host, by_ip, name, port),
               'vmess': _vmess(uid, addr, host, by_ip, name, port)}
        if k == 0 and ss:
            row['ss'] = _ss(host, ss, name)
        out.append(row)
    return out


def subscription(rows, kind='vless'):
    """Every link of one kind, a line each, base64'd: what a client asks a
    subscription URL for, and what most of them also take pasted."""
    lines = [r[kind] for r in rows if r.get(kind)]
    return base64.b64encode('\n'.join(lines).encode()).decode()
