"""Surfshark's fleet, asked for rather than downloaded a hundred times.

The folder of configs this app reads is, on inspection, one file. Every
`.ovpn` Surfshark hands out is byte-identical to every other except a single
line:

    4c4
    < remote ad-leu.prod.surfshark.com 1443
    > remote hk-hkg.prod.surfshark.com 1443

Eighty lines, the same CA, the same tls-auth key, the same cipher, and one
hostname. So a folder of 141 of them carries exactly 141 hostnames' worth of
information, and getting it by hand - sign in, pick a platform, download a
zip, unpack it into the right folder - is a lot of ceremony for a list.

Surfshark publishes the same list:

    https://api.surfshark.com/v4/server/clusters

142 clusters across 100 countries, each with the `connectionName` that *is*
the name in the remote line, plus the country, the city and how loaded it was
when asked. It is a superset of what a download gives you - when this was
written the folder was missing Ghana - and it is one request.

Why this file rather than a line in windscribe.py
-------------------------------------------------
The two providers have nothing in common except the shape of the answer.
Windscribe needs a login, a captcha and a per-client credential; Surfshark
needs nothing at all - the cluster list is public and unauthenticated, which
is why this file is a third of the size and has no notion of an account in
it.

What it will not do
-------------------
It never deletes. A config already in the folder is left exactly as it is,
whether or not the published list still mentions it: those files may be the
user's own download, may be edited, and are certainly not this module's to
throw away. It writes the ones that are missing and reports the ones that
have gone quiet.
"""

import http.client
import ipaddress
import json
import os
import re
import socket
import ssl
import time
import urllib.request

import paths

CLUSTERS_HOST = 'api.surfshark.com'
CLUSTERS_PATH = '/v4/server/clusters'

# The resolver, over HTTPS, for the one name this file needs. The whole
# premise of this repo is that the name-to-address step is the tampered one,
# and it would be odd to fetch a server list through exactly the lookup the
# rest of the app refuses to trust.
DOH = 'https://cloudflare-dns.com/dns-query'

# Where downloaded Surfshark configs already live. Written into rather than
# beside, because the app reads this folder today and the point is to stop
# needing the download, not to add a second place to look.
CONFIG_DIR = os.path.join(paths.DATA_DIR, 'configs')

META_PATH = os.path.join(paths.STATE_DIR, 'surfshark-meta.json')

TIMEOUT = 30

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'

# What the rest of the app can file: engine.py reads the country and the city
# straight out of the name, so a cluster whose name is not in this shape has
# nowhere to go and is dropped rather than written somewhere it will confuse
# the catalogue.
NAME = re.compile(r'^[a-z]{2}-[a-z]{3}\.prod\.surfshark\.com$')

# The line that differs, and the tail of it - so a folder whose configs are
# udp, or on some other port, keeps whatever it already says.
REMOTE = re.compile(r'^([ \t]*remote[ \t]+)(\S+)(.*)$', re.MULTILINE)

IS_IPV4 = re.compile(r'^\d{1,3}(?:\.\d{1,3}){3}$')


class ApiError(RuntimeError):
    """Something between here and Surfshark said no."""


# A way out that ignores whatever proxy this machine is set to. Built once:
# an opener with an empty ProxyHandler is how urllib is told "not through
# that", and it is the difference between working and not when the proxy in
# the settings is one that is no longer listening.
_DIRECT = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _routes():
    """The ways out of this machine, best first.

    Through the proxy first when there is one, because when this app is
    connected that proxy is this app, and the fetch leaves through the
    tunnel. Then around it - a proxy in the settings is not a promise that
    anything is listening on it. Windows keeps the setting whether or not
    the thing behind it is up, and this app's own proxy is down every time
    it is not connected, which is most of the time.

    With no proxy configured the two are the same request, so only one is
    made rather than the same failure twice.
    """
    return [True, False] if urllib.request.getproxies() else [False]


def _read(url, headers, timeout, proxied):
    opener = urllib.request.urlopen if proxied else _DIRECT.open
    request = urllib.request.Request(url, headers=headers)
    with opener(request, timeout=timeout) as response:
        return response.read()


def _doh(name, timeout):
    """Addresses for one name, over HTTPS, public ones only.

    Tried by every route, for the same reason the list itself is: asking the
    honest resolver through a proxy that is not running answers nothing, and
    then the fallback this exists to feed has nothing to work with.

    Failure here is not fatal and is not raised: it means the connection
    below falls back to the system resolver, where a forged answer cannot
    survive certificate validation anyway. No answer is worse than a
    possibly-dishonest one that TLS is about to check.
    """
    url = f'{DOH}?name={name}&type=A'
    headers = {'Accept': 'application/dns-json', 'User-Agent': UA}
    body = None
    for proxied in _routes():
        try:
            body = json.loads(_read(url, headers, timeout, proxied)
                              .decode('utf-8', 'replace'))
            break
        except (OSError, ValueError, ssl.SSLError):
            continue
    if body is None:
        return []

    out = []
    for answer in body.get('Answer') or []:
        if answer.get('type') != 1:            # A records, not CNAMEs
            continue
        try:
            address = ipaddress.ip_address(str(answer.get('data')))
        except ValueError:
            continue
        # The forged answers this repo exists because of are private
        # addresses on your own LAN, and none of them belong to Surfshark.
        if address.is_global:
            out.append(str(address))
    return out


def _get_json(host, path, timeout):
    """The list, by whichever route this machine actually has.

    The ordinary request goes first, and that ordering was measured rather
    than assumed. On the line this was written on, the system resolver
    answers `api.surfshark.com` with 10.10.34.35 - the forged LAN address
    this whole repo exists because of - and a direct TLS handshake to the
    real Cloudflare address behind it comes back WRONG_VERSION_NUMBER,
    because something in the middle is answering for it.

    What did work was the plain request, because urllib honours the system
    proxy, and when this app is connected the system proxy is this app. So
    the fetch leaves through the tunnel and the censored line never sees the
    name at all. Going straight to an address would have bypassed the one
    route that works.

    So there are three ways out, tried in order, and the middle one is the
    one that matters most days: a proxy in the Windows settings is not a
    promise that anything is listening on it, and this app's own proxy is
    down whenever it is not connected. Going straight out is what works
    then, and on an ordinary line it is all that is needed.

      1. through the proxy, when there is one   - the tunnel, when connected
      2. around it, straight out                - the proxy is set but dead,
                                                  or the line is not hostile
      3. straight to a DoH-resolved address     - no proxy and a resolver
                                                  that lies, which is what
                                                  this repo is about

    The DoH-resolved address is last rather than first: it was measured
    failing on this line where the plain routes worked, because something in
    the middle answers for those addresses.
    """
    url = f'https://{host}{path}'
    headers = {'Accept': 'application/json', 'User-Agent': UA}
    trouble = []

    for proxied in _routes():
        try:
            return json.loads(_read(url, headers, timeout, proxied)
                              .decode('utf-8', 'replace'))
        except (OSError, ValueError, ssl.SSLError) as e:
            trouble.append(f"{'via the proxy' if proxied else 'direct'}: {e}")

    context = ssl.create_default_context()
    for address in _doh(host, timeout):
        connection = http.client.HTTPSConnection(
            host, 443, timeout=timeout, context=context)
        try:
            # Presetting the socket is what keeps the name in the TLS
            # handshake while the connection goes to the address DoH gave
            # us: http.client only dials for itself when sock is None, and
            # everything it sends afterwards still says host.
            connection.sock = context.wrap_socket(
                socket.create_connection((address, 443), timeout),
                server_hostname=host)
            connection.request('GET', path, headers=headers)
            response = connection.getresponse()
            body = response.read()
            if response.status != 200:
                raise ApiError(f'Surfshark answered {response.status} '
                               f'{response.reason} for {path}')
            return json.loads(body.decode('utf-8', 'replace'))
        except (OSError, ValueError, ssl.SSLError) as e:
            trouble.append(f'{address}: {e}')
        finally:
            try:
                connection.close()
            except OSError:
                pass

    raise ApiError(f'Could not reach {host} - ' + '; '.join(trouble[:2]))


def servers(timeout=TIMEOUT):
    """Every cluster Surfshark publishes, in this app's own vocabulary.

    Hostnames and no addresses, which is the shape the rest of this repo is
    built for: resolving them honestly and pinning the result is machinery
    that already exists, and doing it here would be doing it twice and worse.
    """
    body = _get_json(CLUSTERS_HOST, CLUSTERS_PATH, timeout)
    if not isinstance(body, list):
        raise ApiError('Surfshark returned something that is not a list of '
                       'servers.')

    out = []
    for entry in body:
        if not isinstance(entry, dict):
            continue
        hostname = str(entry.get('connectionName') or '').strip().lower()
        if not NAME.match(hostname):
            continue
        out.append({
            'hostname': hostname,
            # Both read out of the name, because that is where the rest of
            # the app reads them from too - taking the country from the
            # JSON instead would let the two disagree, and the filename
            # would win.
            'country': hostname[:2],
            'code': hostname[3:6],
            'place': str(entry.get('location') or ''),
            'countryName': str(entry.get('country') or ''),
            # How busy it was when asked. The one field here that is stale
            # within minutes, which is why nothing is decided on it.
            'load': entry.get('load'),
        })

    if not out:
        raise ApiError('Surfshark published no servers this app can file.')
    return out


def _template(folder):
    """The eighty lines every config in this folder shares, taken from one
    that is already here.

    Lifted rather than embedded: the CA and the tls-auth key in there are
    Surfshark's to change, and a copy hard-coded in this file would be a
    quiet way to start writing configs that no longer work. An unpinned one
    is preferred - a pinned config carries a comment naming the address it
    was pinned to, and copying that into 142 new files would label every one
    of them a lie.
    """
    try:
        names = sorted(f for f in os.listdir(folder) if f.endswith('.ovpn'))
    except OSError:
        return None

    for name in names:
        try:
            with open(os.path.join(folder, name), encoding='utf-8',
                      errors='replace') as f:
                text = f.read()
        except OSError:
            continue
        found = REMOTE.search(text)
        if found and not IS_IPV4.match(found.group(2)):
            return text
    return None


def config_text(hostname, template=None):
    """One config for one exit.

    With a template it is that template with the name swapped, which is a
    real Surfshark config and stays usable by anything else that reads the
    folder. Without one it is the smallest thing that can hold an address:
    this app never runs OpenVPN against these - it talks to the proxy the
    same machines answer on 443 - so a remote line is all it needs to do its
    own job.
    """
    if template:
        return REMOTE.sub(
            lambda m: f'{m.group(1)}{hostname}{m.group(3)}', template, count=1)
    return ('# Surfshark exit, written from the published cluster list.\n'
            '# This app talks to the proxy on 443 and never runs OpenVPN\n'
            '# against it, so the address is the whole of the content.\n'
            'client\n'
            'dev tun\n'
            'proto tcp\n'
            f'remote {hostname} 1443\n')


def write_configs(dest=None, timeout=TIMEOUT):
    """Fetch the list and fill in whatever the folder is missing.

    Additive on purpose. A file already here is left alone - it may be the
    user's own download and it is already correct, since the only line that
    varies is the one its name says - and a config for a cluster that has
    since been withdrawn is reported rather than removed.
    """
    dest = dest or CONFIG_DIR
    fleet = servers(timeout)

    os.makedirs(dest, exist_ok=True)
    template = _template(dest)

    try:
        already = {f for f in os.listdir(dest) if f.endswith('.ovpn')}
    except OSError as e:
        raise ApiError(f'Could not read {dest}: {e}')

    added, failed = 0, 0
    wanted = set()
    for server in fleet:
        name = f"{server['hostname']}_tcp.ovpn"
        wanted.add(name)
        if name in already:
            continue
        try:
            with open(os.path.join(dest, name), 'w',
                      encoding='utf-8', newline='\n') as f:
                f.write(config_text(server['hostname'], template))
            added += 1
        except OSError:
            failed += 1

    # Written whole and moved into place, and never fatal: a folder with no
    # notes beside it is a list that says less, not a broken one.
    try:
        os.makedirs(paths.STATE_DIR, exist_ok=True)
        note = {'fetched': int(time.time()),
                'servers': {s['hostname']: {'place': s['place'],
                                            'country': s['countryName'],
                                            'load': s['load']}
                            for s in fleet}}
        tmp = f'{META_PATH}.{os.getpid()}'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(note, f)
        os.replace(tmp, META_PATH)
    except OSError:
        pass

    return {'folder': dest,
            'total': len(fleet),
            'countries': len({s['country'] for s in fleet}),
            'added': added,
            'kept': len(wanted & already),
            # Still on disk, no longer published. Left where they are.
            'gone': sorted(already - wanted),
            'failed': failed,
            'template': bool(template)}


def meta():
    """What the last fetch was told, for anything that wants to say more
    about an exit than its name does. Missing is normal."""
    try:
        with open(META_PATH, encoding='utf-8') as f:
            return json.load(f) or {}
    except (OSError, ValueError):
        return {}
