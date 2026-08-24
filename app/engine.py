"""What the window actually drives: find an exit, hold it, put the machine back.

Everything protocol-shaped is borrowed from ovpn-proxy.py rather than
reimplemented - the certificate check, the missing SNI, the CONNECT probe.
That file is the thing that was measured and argued over; this one only
decides which exit to ask and what to tell the user while it waits.

The one thing deliberately not reused is pick_live(). It prints to stdout and
calls die(), which exits the process - correct for a command line, useless
behind a window that has to stay up and say what went wrong. So the choosing
is done here, over the same primitives, with progress going to a callback
instead of a terminal.
"""

import concurrent.futures as cf
import importlib.util
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
import urllib.request

import paths
from countries import aliases, city_name, country_name

HERE = paths.APP_DIR
ROOT = paths.DATA_DIR


def load_proxy_module():
    """ovpn-proxy.py has a dash in its name, so it cannot be imported the
    ordinary way. Loaded rather than reimplemented: it is the file that was
    measured and argued over, and a second copy of that logic would drift."""
    if not os.path.isfile(paths.PROXY_PY):
        raise FileNotFoundError(paths.PROXY_PY)
    spec = importlib.util.spec_from_file_location('ovpn_proxy', paths.PROXY_PY)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    paths.point_proxy_module_at_data(mod)
    return mod


px = load_proxy_module()

# Pulled out of the filename, which is where the tunnel sweep recorded how
# long that exit took to answer: "01.8s-no-osl.prod.surfshark.com_tcp_1.2.3.4"
CONFIG = re.compile(r'^(?:(\d+\.\d+)s-)?([a-z]{2})-([a-z]{3})\.prod\.', re.I)


class Server:
    __slots__ = ('path', 'file', 'seconds', 'country', 'city')

    def __init__(self, path, file, seconds, country, city):
        self.path, self.file = path, file
        self.seconds, self.country, self.city = seconds, country, city


class Engine:
    """One connection at a time, and always able to say what state it is in."""

    PORT = 8899

    def __init__(self, sysproxy, folder=None, auth_file=None):
        self.sysproxy = sysproxy
        self.folder = folder or paths.servers_dir()
        self.auth_file = auth_file or paths.AUTH_FILE
        self.child = None
        self.exit_info = None
        self.lock = threading.Lock()
        self.cancelled = threading.Event()

    # -- what there is to connect to --------------------------------------

    def servers(self):
        out = []
        try:
            names = os.listdir(self.folder)
        except OSError:
            return out
        for name in sorted(names):
            if not name.endswith('.ovpn'):
                continue
            m = CONFIG.match(name)
            if not m:
                continue
            out.append(Server(os.path.join(self.folder, name), name,
                              float(m.group(1)) if m.group(1) else None,
                              m.group(2).lower(), m.group(3).lower()))
        return out

    def catalogue(self):
        """Countries, not servers. Ninety-one endpoints is a list nobody
        reads; seventy-five countries is a thing people already have opinions
        about. Which city inside one is our problem, not theirs."""
        by_country = {}
        for s in self.servers():
            c = by_country.setdefault(s.country, {'code': s.country,
                                                  'cities': set(),
                                                  'count': 0,
                                                  'best': None})
            c['cities'].add(s.city)
            c['count'] += 1
            if s.seconds is not None and (c['best'] is None
                                          or s.seconds < c['best']):
                c['best'] = s.seconds
        out = []
        for code, c in by_country.items():
            out.append({'code': code,
                        'name': country_name(code),
                        'alias': aliases(code),
                        'cities': len(c['cities']),
                        'count': c['count'],
                        'best': c['best']})
        # Quickest first, and anything with no recorded time last rather than
        # first - an unmeasured exit is not a fast one.
        out.sort(key=lambda c: (c['best'] is None, c['best'] or 0, c['name']))
        return out

    # -- credentials -------------------------------------------------------

    def credentials(self):
        try:
            return px.read_auth(self.auth_file)
        except SystemExit:
            raise RuntimeError('no-credentials')
        except OSError:
            raise RuntimeError('no-credentials')

    # -- choosing one ------------------------------------------------------

    def find_exit(self, country, progress, want=2, width=16, timeout=8):
        """Ask a lot of exits at once and take the quickest that says yes.

        Which exits will take the credentials is not a property of the exit -
        it moves through the day, and about one in twenty is willing at any
        moment. So this is a race rather than a lookup, and the honest thing
        to show while it runs is how many have been asked, not a percentage
        of anything.
        """
        user, password = self.credentials()
        pool = [s for s in self.servers()
                if country in (None, 'auto') or s.country == country]
        if not pool:
            raise RuntimeError('no-servers')

        # One address per exit host, quickest recorded first. Trying four
        # addresses of the same refusing server is four times nothing.
        seen, ordered = set(), []
        for s in sorted(pool, key=lambda s: (s.seconds is None, s.seconds or 0)):
            key = f'{s.country}-{s.city}'
            if key in seen:
                continue
            seen.add(key)
            ordered.append(s)
        ordered = ordered[:140]

        self.cancelled.clear()
        winners, asked, done = [], len(ordered), 0
        progress({'phase': 'probing', 'asked': 0, 'total': asked})

        def probe(s):
            ip, host = px.read_config(s.path)
            if not host:
                raise OSError('not pinned')
            took = px.can_connect(
                px.Exit(ip, 443, host, user, password), timeout)
            return took, s, ip, host

        with cf.ThreadPoolExecutor(max_workers=width) as ex:
            futures = {ex.submit(probe, s): s for s in ordered}
            for fut in cf.as_completed(futures):
                done += 1
                if done % 4 == 0 or done == asked:
                    progress({'phase': 'probing', 'asked': done,
                              'total': asked, 'found': len(winners)})
                try:
                    winners.append(fut.result())
                except Exception:
                    pass
                if len(winners) >= want or self.cancelled.is_set():
                    for f in futures:
                        f.cancel()
                    break

        if self.cancelled.is_set():
            raise RuntimeError('cancelled')
        if not winners:
            raise RuntimeError('all-refused')
        winners.sort(key=lambda w: w[0])
        return winners[0]

    # -- holding it --------------------------------------------------------

    def _spawn(self, ip, host):
        """The proxy runs as its own process, exactly as `--detach` starts
        it. Keeping it out of this one means a wedged connection cannot take
        the window down with it, and the window closing does not have to be
        the thing that stops it."""
        out_path = os.path.join(paths.STATE_DIR, f'proxy-{self.PORT}.out')
        os.makedirs(paths.STATE_DIR, exist_ok=True)
        argv = paths.worker_argv(ip, host, self.PORT, self.auth_file)
        flags = 0x08 | 0x200 if os.name == 'nt' else 0   # DETACHED, NEW_GROUP
        with open(out_path, 'w', encoding='utf-8') as out:
            return subprocess.Popen(
                argv, stdin=subprocess.DEVNULL, stdout=out,
                stderr=subprocess.STDOUT, creationflags=flags), out_path

    def _wait_listening(self, child, out_path, seconds=20):
        for _ in range(seconds * 4):
            rec = px.read_state(self.PORT)
            if rec and rec['pid'] == child.pid:
                return rec
            if child.poll() is not None:
                break
            time.sleep(0.25)
        try:
            with open(out_path, encoding='utf-8') as f:
                said = f.read().strip()
        except OSError:
            said = ''
        if child.poll() is None:
            try:
                px.kill(child.pid)
            except Exception:
                pass
        raise RuntimeError(f'did-not-start: {said[-400:]}')

    def connect(self, country, progress):
        with self.lock:
            self.disconnect(quiet=True)

            took, server, ip, host = self.find_exit(country, progress)
            progress({'phase': 'starting', 'country': server.country,
                      'city': server.city})

            child, out_path = self._spawn(ip, host)
            self._wait_listening(child, out_path)
            self.child = child

            progress({'phase': 'routing'})
            self.sysproxy.engage('127.0.0.1', self.PORT)

            self.exit_info = {'ip': ip, 'host': host, 'country': server.country,
                              'city': server.city, 'answered': round(took, 2),
                              'pid': child.pid, 'since': time.time()}
            # A confirmation that fails is not a connection that failed. The
            # proxy is up and the machine is pointed at it; all that is
            # missing is the independent second opinion. Tearing the whole
            # thing down here would throw away a working connection because
            # one website was in a mood.
            progress({'phase': 'verifying'})
            try:
                self.exit_info['seen_as'] = self.verify()
            except Exception as e:
                self.exit_info['unconfirmed'] = str(e)[:120]
            return self.status()

    def disconnect(self, quiet=False):
        """The machine goes back first, then the proxy stops.

        That order matters and is the only one that is safe: stopping the
        proxy while the machine still points at it leaves every request
        failing for as long as the restore takes, and if anything goes wrong
        in between, it leaves it failing for good.
        """
        self.sysproxy.restore()
        rec = px.read_state(self.PORT)
        if rec:
            try:
                px.kill(rec['pid'])
            except Exception:
                pass
        self.child = None
        self.exit_info = None
        if not quiet:
            return self.status()

    # -- is it actually working -------------------------------------------

    def verify(self, timeout=15):
        """Ask something on the other side who it thinks we are.

        Through the local proxy rather than around it, so this exercises the
        whole chain the browser will use rather than just the far end.

        Cloudflare's trace endpoint rather than one of the ip-lookup
        services: it is plain key=value, it is not rate limited, and it does
        not decide to answer 406 to a request it does not like the shape of.
        This readout is the only evidence the user has that the app is doing
        what it claims, so it cannot be built on something that says no when
        it is busy.
        """
        proxy = f'http://127.0.0.1:{self.PORT}'
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({'http': proxy, 'https': proxy}))
        req = urllib.request.Request(
            'https://www.cloudflare.com/cdn-cgi/trace',
            headers={'User-Agent': px.UA_BROWSER})
        with opener.open(req, timeout=timeout) as r:
            body = r.read(4096).decode('utf-8', 'replace')
        seen = dict(line.split('=', 1) for line in body.splitlines()
                    if '=' in line)
        return {'ip': seen.get('ip'),
                'country': (seen.get('loc') or '').lower(),
                'colo': seen.get('colo')}

    # -- what state are we in ---------------------------------------------

    def running(self):
        rec = px.read_state(self.PORT)
        return rec if rec and px.alive(rec['pid']) else None

    def status(self):
        rec = self.running()
        engaged = self.sysproxy.engaged_for(self.PORT)
        state = 'off'
        if rec and engaged:
            state = 'on'
        elif rec or engaged:
            # One without the other is not a working connection, and saying
            # "connected" here would be a lie the user pays for in failed
            # page loads.
            state = 'broken'
        out = {'state': state, 'port': self.PORT,
               'folder': os.path.basename(self.folder),
               'owed_restore': bool(self.sysproxy.stashed())}
        if rec:
            out['exit'] = {'ip': rec['ip'], 'host': rec['name'],
                           'since': rec['since']}
        if self.exit_info:
            out['exit'] = {**out.get('exit', {}), **self.exit_info}
            out['exit'].pop('since', None)
        return out
