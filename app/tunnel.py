"""The tunnel to your own server: the client half, run as a child process.

Relay does not speak WebSocket or multiplexing itself. It runs `gost`, which
does, and points its own proxy at the local port that comes up - the same
arrangement it already uses for openvpn.exe during a sweep, and for the same
reason: the thing that was measured is the thing that should carry the bytes.
Reimplementing a multiplexed WebSocket in Python would mean re-earning
numbers that already exist.

Three modes come out of one client process, because they differ only in
which path on the server they ask for:

    single   exits at your own server. One steady address, lowest latency.
    multi    exits at a provider node, and which one is changed live through
             the server's API - no restart at either end.
    bulk     no multiplexing, so one heavy transfer cannot stall the rest.

What Relay has to know is small and comes from the installer's summary: the
domain, the tunnel password and the API password. Everything else - the
paths, the ports, the bypass list - is written from here.
"""

import base64
import hashlib
import json
import os
import socket
import ssl
import subprocess
import time
import urllib.error
import urllib.request

import engine

px = engine.px

# Borrowed, not restated. The ports, the paths and the file written from
# them have to be agreed on by this, by the command line and by the server's
# installer, and three copies of that agreement drift the first time one of
# them learns something. engine has already loaded the module; asking it
# again would only parse the same file twice.
PORTS = px.TUNNEL_PORTS
PATHS = px.TUNNEL_PATHS
BYPASS = px.TUNNEL_BYPASS
config_text = px.tunnel_config_text

# And the way in, for the same reason. Which Cloudflare addresses carry the
# domain is measured, not configured, and the measuring belongs next to the
# file it is written into rather than in the window.
edges_for = px.tunnel_edges
edge_scan = px.edge_scan
edge_cache_load = px.edge_cache_load
edge_cache_save = px.edge_cache_save
diagnose = px.tunnel_diagnose
mirror = px.tunnel_mirror
EDGE_KEEP = px.EDGE_KEEP
is_address = px.is_address


class PinChanged(RuntimeError):
    """A server by address showing a certificate other than the one kept."""


KEY_PREFIX = 'relay://'
SS_METHODS = ('chacha20-ietf-poly1305', 'aes-256-gcm', 'aes-128-gcm')


def parse_key(text):
    """What the installer's key says, checked, as a dict - or ValueError.

    The key is relay:// and then base64url of a JSON object: the server, its
    certificate's pin when it is reached by address, the two passwords and,
    when the server offers it, Shadowsocks. Checked field by field rather
    than trusted, because it arrives pasted out of a chat message, and a
    value that is not what it claims would otherwise turn up much later as a
    gost config that will not parse or a password that is a number.
    """
    raw = (text or '').strip()
    if raw.lower().startswith(KEY_PREFIX):
        raw = raw[len(KEY_PREFIX):]
    # Chat apps wrap long lines; a key is one token, so whitespace is noise.
    raw = ''.join(raw.split())
    if not raw:
        raise ValueError('that is empty')
    try:
        body = json.loads(base64.urlsafe_b64decode(raw + '=' * (-len(raw) % 4)))
    except (ValueError, TypeError):
        raise ValueError('that is not a Relay key - it should start with relay://')
    if not isinstance(body, dict) or body.get('v') != 1:
        raise ValueError('that key is from a newer Relay than this one')

    def text_field(name, required=True):
        value = body.get(name, '')
        if not isinstance(value, str) or (required and not value.strip()):
            raise ValueError(f'the key has no {name}')
        return value.strip()

    out = {'host': text_field('host'), 'pin': text_field('pin', False).lower(),
           'password': text_field('pw'), 'api_password': text_field('apw'),
           'ss': None, 'export': None}
    host = out['host']
    if not (is_address(host) or
            all(part and len(part) < 64 and part.replace('-', '').isalnum()
                for part in host.split('.'))):
        raise ValueError('the server in that key is not an address or a name')
    if out['pin'] and (len(out['pin']) != 64 or
                       any(c not in '0123456789abcdef' for c in out['pin'])):
        raise ValueError('the certificate pin in that key is malformed')
    # The TLS door, when the server keeps it somewhere other than 443 -
    # because an Xray or another site already answers there. Absent is 443,
    # which is every key made before the installer could say otherwise.
    port = body.get('port', 443)
    if (not isinstance(port, int) or isinstance(port, bool)
            or not 0 < port < 65536):
        raise ValueError('the port in that key is malformed')
    out['port'] = port
    ss = body.get('ss')
    if ss is not None:
        ok = (isinstance(ss, dict) and ss.get('method') in SS_METHODS
              and isinstance(ss.get('password'), str) and ss['password']
              and all(isinstance(ss.get(p), int) and 0 < ss[p] < 65536
                      for p in ('port', 'exitPort')))
        if not ok:
            raise ValueError("the key's Shadowsocks settings are malformed")
        out['ss'] = {'method': ss['method'], 'password': ss['password'],
                     'port': ss['port'], 'exitPort': ss['exitPort']}
    # The locations the server keeps for other devices (export.py): an id
    # the users' UUIDs are derived from, and how many there are.
    x = body.get('x')
    if x is not None:
        xid = x.get('id') if isinstance(x, dict) else None
        n = x.get('n') if isinstance(x, dict) else None
        ok = (isinstance(xid, str) and 0 < len(xid) <= 64
              and all(c.isascii() and (c.isalnum() or c in '-_') for c in xid)
              and isinstance(n, int) and not isinstance(n, bool) and 1 <= n <= 32)
        if not ok:
            raise ValueError("the key's exported locations are malformed")
        out['export'] = {'id': xid, 'slots': n}
    return out


class Tunnel:
    """The gost client process, and the server's API behind it."""

    def __init__(self, exe, workdir, domain, password, api_password,
                 ss=None, expected_pin='', port=443):
        self.exe = exe
        self.dir = workdir
        self.domain = domain
        self.password = password
        self.api_password = api_password
        # Where the server's TLS answers: the WebSocket paths, the API and
        # the certificate all sit behind the one nginx on this port.
        self.port = int(port or 443)
        # The server's Shadowsocks settings when that is the way in, else None
        # and the WebSocket through Cloudflare (or the address) is.
        self.ss = ss
        # The pin a key came with. Without one the first certificate seen is
        # taken on trust, SSH-style; with one, even the first must match.
        self.expected_pin = (expected_pin or '').lower()
        self.child = None

    # -- the child ---------------------------------------------------------

    @property
    def config_path(self):
        return os.path.join(self.dir, 'config.yaml')

    def write_config(self, edges=None):
        """The client's configuration, with the addresses it should dial.

        The addresses come from what was found last time rather than from a
        fresh scan: a scan before every start would put seconds onto the
        case where nothing is wrong to save them in the case where something
        is, and the second case is already asking somebody to wait.
        """
        os.makedirs(self.dir, exist_ok=True)
        if self.ss:
            # Straight to the address over Shadowsocks: no edges to measure
            # and no certificate in the way in. The API still has one, which
            # is fetched when the API is first asked for something.
            text = config_text(self.domain, self.password, ss=self.ss)
            with open(self.config_path, 'w', encoding='utf-8') as f:
                f.write(text)
            mirror(text)
            return self.config_path
        if edges is None:
            edges = edges_for(self.dir, self.domain, quiet=True)
        ca = self.ensure_cert() if is_address(self.domain) else None
        text = config_text(self.domain, self.password, edges, ca_file=ca,
                           port=self.port)
        with open(self.config_path, 'w', encoding='utf-8') as f:
            f.write(text)
        # And into any other client folder this machine has been told about,
        # so the copy in Startup is not left dialling last month's addresses.
        mirror(text)
        return self.config_path

    # -- a server by address, and its own certificate ---------------------
    #
    # No CA vouches for an address, so the installer makes the server its own
    # certificate. It is taken the first time it is seen and kept beside the
    # client's configuration; from then on gost and the API trust that one
    # certificate and nothing else - SSH's rule for a host met for the first
    # time.

    @property
    def cert_path(self):
        return os.path.join(self.dir, f'server-{self.domain}.pem')

    def pin(self):
        """The kept certificate's SHA-256, in hex, or '' before first contact."""
        try:
            with open(self.cert_path, encoding='ascii') as f:
                der = ssl.PEM_cert_to_DER_cert(f.read())
        except (OSError, ValueError):
            return ''
        return hashlib.sha256(der).hexdigest()

    def ensure_cert(self, timeout=15):
        """The kept certificate's path, fetching it on first contact.

        With a pin from a key, the first contact is checked too: the key was
        made on the server, so it knows which certificate is the real one,
        and taking whatever answers first would waste that."""
        if not os.path.exists(self.cert_path):
            pem = ssl.get_server_certificate((self.domain, self.port),
                                             timeout=timeout)
            if self.expected_pin:
                got = hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest()
                if got != self.expected_pin:
                    raise PinChanged(
                        f'{self.domain} is showing a certificate that is not '
                        f'the one in its key (sha256 {got[:16]}…, key says '
                        f'{self.expected_pin[:16]}…). Something between here '
                        'and the server is answering in its place.')
            os.makedirs(self.dir, exist_ok=True)
            with open(self.cert_path, 'w', encoding='ascii') as f:
                f.write(pem)
        return self.cert_path

    def check_cert(self, timeout=15):
        """Whether the server still shows the kept certificate."""
        if not is_address(self.domain) or not os.path.exists(self.cert_path):
            return True
        pem = ssl.get_server_certificate((self.domain, self.port),
                                         timeout=timeout)
        got = hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest()
        if got != self.pin():
            raise PinChanged(
                f'{self.domain} is showing a different certificate than the '
                f'one kept the first time (sha256 {got[:16]}…, kept '
                f'{self.pin()[:16]}…). If you reinstalled the server, save '
                'its address again to forget the old one.')
        return True

    def forget_cert(self):
        try:
            os.remove(self.cert_path)
        except OSError:
            pass

    def edges(self):
        """The addresses this client is dialling, as the file has them."""
        return edge_cache_load(self.dir, self.domain)

    def diagnose(self):
        """Which of the several failures that all look like 503 this is."""
        return diagnose(self.domain, self.edges())

    def rescan(self):
        """Find a way in again, and put the client on it.

        Returns the addresses found, newest first, or an empty list if the
        line has none - which is its own answer and not a failure to report
        as one: it means the domain is being filtered rather than the
        address, and no address will help.
        """
        found, tally = edge_scan(self.domain)
        if not found:
            return [], tally
        found = found[:EDGE_KEEP]
        edge_cache_save(self.dir, self.domain, found)
        self.write_config(found)
        if self.listening():
            self.restart()
        return found, tally

    def listening(self, mode='single'):
        """Whether anything holds that port. Asked rather than remembered:
        a tunnel left running by a previous window is still a good tunnel,
        and starting a second one on top would only fail on the port."""
        s = socket.socket()
        s.settimeout(0.4)
        try:
            return s.connect_ex(('127.0.0.1', PORTS[mode])) == 0
        finally:
            s.close()

    def start(self):
        """Bring the client up, unless it already is.

        Returns True if this call started it. Nothing here waits on the far
        end: gost listens immediately and dials when the first connection
        arrives, so a server that is down shows up as a failed request rather
        than as a client that will not start - which is the more honest of
        the two, and the one the user can act on.
        """
        if self.listening():
            return False
        self.write_config()
        flags = 0x08 | 0x200 if os.name == 'nt' else 0   # DETACHED, NEW_GROUP
        out = open(os.path.join(self.dir, 'gost.out'), 'w', encoding='utf-8')
        self.child = subprocess.Popen(
            [self.exe, '-C', self.config_path],
            cwd=self.dir, stdin=subprocess.DEVNULL, stdout=out,
            stderr=subprocess.STDOUT, creationflags=flags)
        for _ in range(40):
            if self.listening():
                return True
            if self.child.poll() is not None:
                break
            time.sleep(0.25)
        raise RuntimeError(self._why_it_died())

    def _why_it_died(self):
        try:
            with open(os.path.join(self.dir, 'gost.out'), encoding='utf-8') as f:
                said = f.read().strip()
        except OSError:
            said = ''
        return f'the tunnel client did not come up: {said[-400:]}'

    def stop(self):
        if self.child and self.child.poll() is None:
            self.child.terminate()
            try:
                self.child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.child.kill()
            self.child = None
            return
        self.child = None
        # Left running by a previous window, or by the shortcut in Startup.
        # Found by which process holds the port rather than by name: killing
        # every gost on the machine would take somebody else's with it.
        pid = self._owner_pid(PORTS['single'])
        if pid:
            try:
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(pid), '/F'],
                                   capture_output=True, timeout=10)
                else:
                    os.kill(pid, 15)
            except Exception:
                pass

    _owner_pid = staticmethod(px.tunnel_owner_pid)

    def restart(self):
        self.stop()
        for _ in range(20):
            if not self.listening():
                break
            time.sleep(0.25)
        return self.start()

    def probe(self, mode='single', timeout=30):
        """What the internet sees when it is asked through that mode.

        A listening port is not a working tunnel. The far end can have been
        restarted underneath a multiplexed session, and what is left answers
        the connection and then 503s everything - so the only honest test is
        to carry something through it and see what comes back.
        """
        opener = urllib.request.build_opener(urllib.request.ProxyHandler(
            {'http': f'http://127.0.0.1:{PORTS[mode]}'}))
        req = urllib.request.Request('http://api.ipify.org',
                                     headers={'User-Agent': 'Relay'})
        return opener.open(req, timeout=timeout).read().decode().strip()

    def upload_rate(self, mode='multi', size=256 << 10, timeout=4):
        """How fast that mode carries an upload, in bytes a second; 0 if not
        at all within timeout.

        From a server in Iran, upload is throttled per foreign exit IP: most
        exits carry 10-20 KB/s up while downloading at megabytes, and a few in
        the same city carry the full rate. Timed to Cloudflare's answer, which
        only comes once every byte has arrived, so buffers on the way cannot
        flatter a slow exit.
        """
        opener = urllib.request.build_opener(urllib.request.ProxyHandler(
            {'https': f'http://127.0.0.1:{PORTS[mode]}'}))
        req = urllib.request.Request('https://speed.cloudflare.com/__up',
                                     data=os.urandom(size), method='POST',
                                     headers={'User-Agent': 'Relay',
                                              'Content-Type': 'application/octet-stream'})
        start = time.monotonic()
        try:
            with opener.open(req, timeout=timeout) as r:
                r.read(4096)
        except Exception:
            return 0
        took = time.monotonic() - start
        return size / took if took < timeout else 0

    def address(self, mode='single'):
        """What to hand the worker as --tunnel."""
        return f'127.0.0.1:{PORTS[mode]}'

    # -- the server's API --------------------------------------------------
    #
    # Reached over the public name rather than through the tunnel, because
    # 127.0.0.1 is in the bypass list above: asked for through the tunnel it
    # would be sent straight back out to this machine's own loopback, where
    # nothing is listening. Basic auth over the same TLS the tunnel uses.

    @property
    def origin(self):
        """`host`, or `host:port` when the server's TLS is not on 443."""
        return self.domain if self.port == 443 else f'{self.domain}:{self.port}'

    def _api(self, path, payload=None, method='GET', timeout=20):
        url = f'https://{self.origin}/api/{path}'
        data = json.dumps(payload).encode() if payload is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        token = base64.b64encode(f'relay:{self.api_password}'.encode()).decode()
        req.add_header('Authorization', f'Basic {token}')
        # Named, because the default is not. Cloudflare answers 403 to
        # `Python-urllib/3.12` before the request ever reaches the server,
        # which reads exactly like the API refusing us and is not.
        req.add_header('User-Agent', 'Relay (ovpn-pin desktop)')
        if data:
            req.add_header('Content-Type', 'application/json')
        ctx = None
        if is_address(self.domain):
            # The kept certificate, and only it: Python checks an address
            # against the certificate's IP entry the way it checks a name.
            ctx = ssl.create_default_context(cafile=self.ensure_cert())
        # Straight to the server, never through a proxy. urlopen follows the
        # Windows proxy setting, which on this machine is often some other
        # client's (v2rayN on 10809) - or this app's own port, when it is
        # routing Windows and the exit is being changed while connected - and
        # the API answered through either, slowly or not at all.
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPSHandler(context=ctx) if ctx else urllib.request.HTTPSHandler())
        try:
            with opener.open(req, timeout=timeout) as r:
                body = r.read()
            return json.loads(body) if body else {}
        except urllib.error.HTTPError as e:
            if e.code == 401:
                raise RuntimeError('the server refused the API password')
            raise RuntimeError(f'the server answered {e.code} to {method} {path}')

    def current_exit(self):
        """The address multi-IP mode is leaving by, as the server has it."""
        chain = self._api('config/chains/exit-chain').get('data') or {}
        try:
            return chain['hops'][0]['nodes'][0]['addr']
        except (KeyError, IndexError):
            return ''

    def set_slot(self, k, ip, user, password, name='current'):
        """Point one exported location - loc-<k>, which links handed to
        other devices are routed to - at a provider exit. The same node
        set_exit writes, under another chain's name."""
        if not 1 <= int(k) <= 32:
            raise RuntimeError(f'slot {k} is not one the server has')
        try:
            self._put_exit(f'loc-{int(k)}', ip, user, password, name)
        except RuntimeError as e:
            if '404' in str(e):
                raise RuntimeError('the server has no exported locations - run '
                                   'the installer from this version again')
            raise
        return True

    def chain(self, name):
        """One of the server's chains as it is now, for putting back later."""
        return (self._api(f'config/chains/{name}') or {}).get('data')

    def put_chain(self, name, data):
        self._api(f'config/chains/{name}', method='PUT', payload=data)

    def persist(self):
        """Write the server's running configuration to the file gost starts
        from. Without it, everything set through the API - the exported
        locations above all - goes back to the installer's placeholder the
        next time the server restarts."""
        self._api('config?format=yaml&path=/etc/gost/config.yaml',
                  method='POST')

    def _put_exit(self, chain, ip, user, password, name):
        self._api(f'config/chains/{chain}', method='PUT', payload={
            'name': chain,
            'hops': [{'name': 'exit', 'nodes': [{
                'name': name,
                'addr': f'{ip}:443',
                'connector': {'type': 'http',
                              'auth': {'username': user, 'password': password}},
                'dialer': {'type': 'tls', 'tls': {'secure': False}},
            }]}],
        })

    def set_exit(self, ip, user, password, name='current'):
        """Point multi-IP mode at a different node, live.

        This is the whole of what changing country costs: one request. The
        change takes effect on the next connection through that mode, with
        nothing restarted at either end and nothing to edit on the server.

        It lands in the running configuration only. gost reads its file again
        when it restarts, so a server that reboots comes back on whatever was
        installed - the caller is the one that has to decide whether a choice
        is worth persisting.
        """
        self._put_exit('exit-chain', ip, user, password, name)
        return True
