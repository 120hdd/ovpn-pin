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
import json
import os
import socket
import subprocess
import time
import urllib.error
import urllib.request

# The local ports the three modes listen on. Above the range the command line
# half of the repo uses for its own proxies, so a tunnel and a hand-started
# `ovpn proxy` can be up together without either having to move.
PORTS = {'single': 9090, 'multi': 9091, 'bulk': 9092}

# Which server path each mode asks for. These are what install-server.sh
# publishes; changing one means changing it there too.
PATHS = {'single': '/gw', 'multi': '/ex', 'bulk': '/gwb'}

# Destinations that skip the tunnel entirely. Iranian traffic reaches its
# destination faster and cheaper on the direct line, and sending it abroad
# and back would spend the server's transfer allowance to make it slower.
# Domain rules only match domains - gost does not resolve a name to see
# whether its address falls in a range - so the well-known Iranian services
# that are not on .ir have to be named.
BYPASS = [
    '127.0.0.1', 'localhost',
    '10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16',
    '*.ir', '.ir',
    '*.digikala.com', '*.aparat.com', '*.filimo.com',
    '*.varzesh3.com', '*.telewebion.com', '*.blogfa.com',
]


def _hop(domain, password, path, multiplexed=True):
    return f"""      - name: t
        bypass: go-direct
        nodes:
          - name: server
            addr: {domain}:443
            connector:
              type: http
              auth: {{username: relay, password: {password}}}
            dialer:
              type: {'mwss' if multiplexed else 'wss'}
              metadata: {{path: {path}, mux.keepaliveInterval: 10s}}
"""


def config_text(domain, password):
    """The whole client configuration, as gost wants it.

    Written from here rather than shipped as a file the user edits: the
    domain and the password are the only things that vary, and a config the
    app owns is one that cannot drift out of step with the server it was
    installed against.
    """
    services, chains = [], []
    for mode, port in PORTS.items():
        services.append(
            f'  - name: {mode}\n'
            f'    addr: "127.0.0.1:{port}"\n'
            f'    handler: {{type: http, chain: ch-{mode}}}\n'
            f'    listener: {{type: tcp}}\n')
        chains.append(
            f'  - name: ch-{mode}\n'
            f'    hops:\n' +
            _hop(domain, password, PATHS[mode], multiplexed=(mode != 'bulk')))
    matchers = ', '.join(
        m if not m.startswith('*') else f"'{m}'" for m in BYPASS)
    return ('services:\n' + ''.join(services) +
            'chains:\n' + ''.join(chains) +
            'bypasses:\n'
            '  - name: go-direct\n'
            f'    matchers: [{matchers}]\n'
            'log:\n  level: info\n')


class Tunnel:
    """The gost client process, and the server's API behind it."""

    def __init__(self, exe, workdir, domain, password, api_password):
        self.exe = exe
        self.dir = workdir
        self.domain = domain
        self.password = password
        self.api_password = api_password
        self.child = None

    # -- the child ---------------------------------------------------------

    @property
    def config_path(self):
        return os.path.join(self.dir, 'config.yaml')

    def write_config(self):
        os.makedirs(self.dir, exist_ok=True)
        text = config_text(self.domain, self.password)
        with open(self.config_path, 'w', encoding='utf-8') as f:
            f.write(text)
        return self.config_path

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

    @staticmethod
    def _owner_pid(port):
        try:
            if os.name == 'nt':
                out = subprocess.run(['netstat', '-ano'], capture_output=True,
                                     text=True, timeout=10).stdout
                for line in out.splitlines():
                    bits = line.split()
                    if (len(bits) >= 5 and bits[0] == 'TCP'
                            and bits[1].endswith(f':{port}')
                            and bits[3] == 'LISTENING'):
                        return int(bits[4])
            else:
                out = subprocess.run(['ss', '-lntp'], capture_output=True,
                                     text=True, timeout=10).stdout
                for line in out.splitlines():
                    if f':{port} ' in line and 'pid=' in line:
                        return int(line.split('pid=')[1].split(',')[0])
        except Exception:
            pass
        return None

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

    def address(self, mode='single'):
        """What to hand the worker as --tunnel."""
        return f'127.0.0.1:{PORTS[mode]}'

    # -- the server's API --------------------------------------------------
    #
    # Reached over the public name rather than through the tunnel, because
    # 127.0.0.1 is in the bypass list above: asked for through the tunnel it
    # would be sent straight back out to this machine's own loopback, where
    # nothing is listening. Basic auth over the same TLS the tunnel uses.

    def _api(self, path, payload=None, method='GET', timeout=20):
        url = f'https://{self.domain}/api/{path}'
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
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
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
        self._api('config/chains/exit-chain', method='PUT', payload={
            'name': 'exit-chain',
            'hops': [{'name': 'exit', 'nodes': [{
                'name': name,
                'addr': f'{ip}:443',
                'connector': {'type': 'http',
                              'auth': {'username': user, 'password': password}},
                'dialer': {'type': 'tls', 'tls': {'secure': False}},
            }]}],
        })
        return True
