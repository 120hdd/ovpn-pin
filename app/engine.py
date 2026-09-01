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

# The port the proxy sits on. 8877 rather than 8899, which is what the command
# line half of this repo uses for a second proxy beside somebody's own: the two
# are meant to be able to run at the same time, and sharing a number meant that
# starting the app while a terminal proxy was up failed with "address already
# in use" and no obvious cause.
DEFAULT_PORT = 8877


def clean_port(value):
    """A port or a sentence saying why not.

    Refused out loud rather than snapped quietly back to 8877: this number is
    typed by a person who has a reason for it - something else already holds
    the default - and a field that accepts 88999 and silently keeps 8877 is a
    field that lies about where the app is listening.
    """
    text = str(value if value is not None else '').strip()
    if not text:
        # An empty field means "whatever it was meant to be", not "port
        # nothing" - which is what makes clearing it the way back to 8877.
        return DEFAULT_PORT
    try:
        port = int(text)
    except ValueError:
        raise RuntimeError('That is not a port. Type a number '
                           'between 1024 and 65535.')
    if port > 65535:
        raise RuntimeError('Ports stop at 65535.')
    if port < 1:
        raise RuntimeError('That is not a port. Type a number '
                           'between 1024 and 65535.')
    if port < 1024:
        # Not forbidden by Windows, but every one of them belongs to something
        # that expects to be found there, and a browser pointed at a proxy on
        # 80 is a confusion nobody debugs on the first day.
        raise RuntimeError('Anything below 1024 already belongs to something '
                           'else. Pick a port from 1024 up.')
    return port


def port_holder(port, host='127.0.0.1'):
    """Whether anything already has that port, and our own record of it if
    the thing holding it is one of ours.

    A bind rather than a connect: something that has the port but is not
    accepting - a half-dead worker, a socket in the wrong state - refuses a
    connect and would read as free, and then the move onto it fails with
    nothing on screen to explain it. SO_REUSEADDR is deliberately not set;
    on Windows it makes a bind succeed over a port already in use, which is
    the one answer this must never give.
    """
    sock = socket.socket()
    try:
        sock.bind((host, int(port)))
        return None
    except OSError:
        return px.read_state(port) or {}
    finally:
        sock.close()


class Server:
    __slots__ = ('path', 'file', 'seconds', 'country', 'city')

    def __init__(self, path, file, seconds, country, city):
        self.path, self.file = path, file
        self.seconds, self.country, self.city = seconds, country, city


class Engine:
    """One connection at a time, and always able to say what state it is in."""

    def __init__(self, sysproxy, folder=None, auth_file=None,
                 set_system_proxy=True, port=None):
        self.sysproxy = sysproxy
        self.folder = folder or paths.servers_dir()
        self.auth_file = auth_file or paths.AUTH_FILE
        # Where it listens. Settable because 8877 is only free until it is
        # not - a second copy of this, a proxy the person already runs, a
        # corporate agent - and a fixed port turns that into an app that
        # cannot connect and cannot say why.
        try:
            self.port = clean_port(port)
        except RuntimeError:
            self.port = DEFAULT_PORT
        # Off, this serves the proxy and leaves Windows alone - for someone
        # who would rather point one browser at the port by hand than have
        # every program on the machine moved at once.
        self.set_system_proxy = set_system_proxy
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

    def username(self):
        """The name on file, for showing back. The password is never returned
        anywhere - a field that arrives pre-filled with a password is a
        password on screen, and the only thing that buys is the ability to
        read it over somebody's shoulder."""
        try:
            return self.credentials()[0]
        except Exception:
            return None

    def save_credentials(self, user, password):
        """Two lines, written where every half of this repo already looks.

        Not the login email. Surfshark issues a separate service username and
        password for manual setups, and the account one is refused by every
        exit with the same silence as a wrong password - which reads as "no
        server accepted just now" and sends people looking at their servers
        folder. Hence the shape check below: an address in this field is
        wrong often enough to be worth naming.
        """
        user = (user or '').strip()
        password = (password or '').strip()
        if not user or not password:
            raise RuntimeError('Both the username and the password are needed.')
        if '\n' in user or '\n' in password:
            raise RuntimeError('One line each - no line breaks.')
        if '@' in user:
            raise RuntimeError(
                'That looks like your login email. Surfshark issues a separate '
                'service username for manual setups - it is on the same page '
                'as the config files.')

        os.makedirs(os.path.dirname(self.auth_file) or '.', exist_ok=True)
        tmp = self.auth_file + '.tmp'
        # Written and moved into place rather than truncated and filled: a
        # crash between the two would otherwise leave an empty credentials
        # file, and the app cannot tell that apart from never having had one.
        with open(tmp, 'w', encoding='utf-8', newline='\n') as f:
            f.write(f'{user}\n{password}\n')
        os.replace(tmp, self.auth_file)
        self._lock_down(self.auth_file)

        # .env is the side the shell and PowerShell halves edit, and where
        # they disagree with .ovpn-auth, .env wins - Sync-CachedAuthFile
        # rewrites the auth file from it on the next sweep. So a password set
        # here and not set there would come back changed, hours later,
        # blaming the server. Only touched when it already carries a value:
        # an empty OVPN_USER is a file nobody is using.
        return {'env': self._sync_env(user, password)}

    ENV_LINE = re.compile(r'^(\s*)(OVPN_USER|OVPN_PASS)(\s*=\s*)(.*)$')

    def _sync_env(self, user, password):
        """Only when .env already carries a value, and only when that value
        has stopped being true. An OVPN_USER nobody has filled in is a file
        nobody is using, and rewriting it would be this app leaving marks on
        something it was not asked about."""
        path = os.path.join(ROOT, '.env')
        try:
            with open(path, 'rb') as f:
                raw = f.read()
        except OSError:
            return False
        # Whatever this file already ends its lines with, it keeps. The shell
        # half of the repo is read by bash, where a stray \r turns a password
        # into a different password.
        ending = '\r\n' if b'\r\n' in raw else '\n'
        text = raw.decode('utf-8', 'replace')

        changed, out = False, []
        for line in text.split('\n'):
            bare = line.rstrip('\r')
            m = self.ENV_LINE.match(bare)
            if not m or not m.group(4).strip():
                out.append(bare)
                continue
            want = user if m.group(2) == 'OVPN_USER' else password
            if m.group(4).strip().strip('\'"') != want:
                changed = True
            out.append(f'{m.group(1)}{m.group(2)}{m.group(3)}{want}')
        if not changed:
            return False

        while out and not out[-1]:
            out.pop()
        try:
            with open(path, 'w', encoding='utf-8', newline='') as f:
                f.write(ending.join(out) + ending)
        except OSError:
            return False
        return True

    @staticmethod
    def _lock_down(path):
        """Take the inherited permissions off a file holding a password.

        chmod is not a thing here - on NTFS it returns success and changes
        nothing, which is worse than failing. The repo has already been caught
        out by this once: a group had Read on the folder with the inherit
        flags set, so every file underneath picked it up and nothing said so.
        Best effort; a file that could not be locked down is still better than
        no credentials at all.
        """
        if os.name != 'nt':
            return False
        me = os.environ.get('USERNAME')
        if not me:
            return False
        try:
            subprocess.run(['icacls', path, '/inheritance:r',
                            '/grant:r', f'{me}:F',
                            '/grant:r', 'SYSTEM:F',
                            '/grant:r', 'Administrators:F'],
                           capture_output=True, timeout=20,
                           creationflags=0x08000000)
            return True
        except (OSError, subprocess.SubprocessError):
            return False

    # -- choosing one ------------------------------------------------------

    def candidates(self, country):
        """The addresses worth asking, in the order worth asking them.

        The rule differs by what was asked for, and getting this wrong made
        the app unusable once already.

        For a named country, EVERY address it has is a candidate. Roughly one
        exit in twenty will take the credentials at any moment, so trying one
        address per city meant picking Switzerland - twelve servers, one city
        - staked the whole connection on a single coin flip, and picking
        Albania staked twenty-one servers' worth of chances on one. Across
        the whole set only 91 of 533 addresses were ever tried.

        For "fastest available" the dedupe is right: one per city already
        gives ninety-odd independent chances, and asking all 533 at once
        measures how hard you are hammering the provider rather than which
        exit is quick.
        """
        pool = [s for s in self.servers()
                if country in (None, 'auto') or s.country == country]
        pool.sort(key=lambda s: (s.seconds is None, s.seconds or 0))
        if country in (None, 'auto'):
            seen, out = set(), []
            for s in pool:
                key = f'{s.country}-{s.city}'
                if key not in seen:
                    seen.add(key)
                    out.append(s)
            return out[:140]
        return pool[:80]

    def address_for(self, country):
        """One exit address for that country, without asking it anything.

        find_exit() races the candidates because it is about to carry traffic
        through one and wants a live one. Here the connection is made from
        the tunnel server, not from this machine, so a probe from here would
        measure the wrong leg - and fail on exits that are only blocked on
        this line. The list is already ordered by what the sweep timed, so
        the first entry is the best answer available without asking.
        """
        ordered = self.candidates(country)
        if not ordered:
            raise RuntimeError('no-servers')
        server = ordered[0]
        ip, host = px.read_config(server.path)
        return server, ip, host

    def find_exit(self, country, progress, width=8, timeout=6):
        """Race the candidates and take the first that says yes.

        First rather than best. An earlier version waited for two so it could
        pick the quicker, and paid the difference between them on every
        connect - which is not worth a second of somebody's time.

        Width 8 rather than something impressive. Measured on this line,
        racing wider makes it slower, because the handshakes compete for the
        same upstream:

            width  6   0.86s  1.02s  1.14s
            width 10   1.11s  1.16s  1.02s
            width 16   1.39s  1.38s  1.36s
            width 24   1.89s  2.25s  2.14s

        Eight also stages itself: the pool keeps feeding candidates in, so a
        bad moment when only one exit in twenty is accepting still works
        through the list, just over a few more rounds.
        """
        user, password = self.credentials()
        ordered = self.candidates(country)
        if not ordered:
            raise RuntimeError('no-servers')

        self.cancelled.clear()
        winners, asked, done = [], len(ordered), 0
        progress({'phase': 'probing', 'asked': 0, 'total': asked})

        def probe(s):
            if self.cancelled.is_set():
                raise OSError('cancelled')
            ip, host = px.read_config(s.path)
            if not host:
                raise OSError('not pinned')
            took = px.can_connect(
                px.Exit(ip, 443, host, user, password), timeout)
            return took, s, ip, host

        ex = cf.ThreadPoolExecutor(max_workers=width)
        try:
            futures = [ex.submit(probe, s) for s in ordered]
            for fut in cf.as_completed(futures):
                done += 1
                if done % 4 == 0 or done == asked:
                    progress({'phase': 'probing', 'asked': done,
                              'total': asked})
                try:
                    winners.append(fut.result())
                except Exception:
                    pass
                if winners or self.cancelled.is_set():
                    break
        finally:
            # wait=False, or Cancel takes as long as the slowest probe still
            # in flight - up to the full timeout, with the window frozen on
            # "connecting" the entire time. The threads are daemons and the
            # cancelled flag stops them doing anything further.
            for f in futures:
                f.cancel()
            ex.shutdown(wait=False, cancel_futures=True)

        if self.cancelled.is_set():
            raise RuntimeError('cancelled')
        if not winners:
            raise RuntimeError('all-refused')
        return winners[0]

    # -- holding it --------------------------------------------------------

    def _spawn(self, ip, host, tunnel=None):
        """The proxy runs as its own process, exactly as `--detach` starts
        it. Keeping it out of this one means a wedged connection cannot take
        the window down with it, and the window closing does not have to be
        the thing that stops it."""
        out_path = os.path.join(paths.STATE_DIR, f'proxy-{self.port}.out')
        os.makedirs(paths.STATE_DIR, exist_ok=True)
        argv = paths.worker_argv(ip, host, self.port, self.auth_file, tunnel)
        flags = 0x08 | 0x200 if os.name == 'nt' else 0   # DETACHED, NEW_GROUP
        with open(out_path, 'w', encoding='utf-8') as out:
            return subprocess.Popen(
                argv, stdin=subprocess.DEVNULL, stdout=out,
                stderr=subprocess.STDOUT, creationflags=flags), out_path

    def _wait_listening(self, child, out_path, seconds=20):
        for _ in range(seconds * 4):
            rec = px.read_state(self.port)
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

            if self.set_system_proxy:
                progress({'phase': 'routing'})
                self.sysproxy.engage('127.0.0.1', self.port)

            self.exit_info = {'ip': ip, 'host': host, 'country': server.country,
                              'city': server.city, 'answered': round(took, 2),
                              'pid': child.pid, 'since': time.time()}
            # Deliberately NOT verified here. The connection is live the
            # moment the machine is pointed at a listening proxy; asking a
            # website to confirm it is a second network round trip, and
            # holding the word "connected" back for it made every connect a
            # second slower than it had to be. The caller confirms
            # afterwards and fills the address in when it arrives.
            return self.status()

    def connect_tunnel(self, address, label, progress):
        """Come up on a tunnel whose local end is already listening.

        The half of connect() that chooses an exit has nothing to do here:
        the tunnel is already abroad, and which address the far end leaves by
        is its business, not ours. What is left is the same in both - spawn
        the worker, wait for it to listen, point the machine at it - so the
        window, the meter and the host list cannot tell the difference.
        """
        with self.lock:
            self.disconnect(quiet=True)
            progress({'phase': 'starting', 'country': label, 'city': ''})

            child, out_path = self._spawn(None, None, tunnel=address)
            self._wait_listening(child, out_path)
            self.child = child

            if self.set_system_proxy:
                progress({'phase': 'routing'})
                self.sysproxy.engage('127.0.0.1', self.port)

            self.exit_info = {'ip': address, 'host': '', 'country': label,
                              'city': '', 'answered': 0.0,
                              'pid': child.pid, 'since': time.time(),
                              'tunnel': True}
            return self.status()

    def disconnect(self, quiet=False):
        """The machine goes back first, then the proxy stops.

        That order matters and is the only one that is safe: stopping the
        proxy while the machine still points at it leaves every request
        failing for as long as the restore takes, and if anything goes wrong
        in between, it leaves it failing for good.
        """
        self.sysproxy.restore()
        rec = px.read_state(self.port)
        if rec:
            try:
                px.kill(rec['pid'])
            except Exception:
                pass
        # The worker tidies its own meter file when it is interrupted, but a
        # kill on Windows gives it no chance to. Removing it here rather than
        # leaving it to be aged out means the next connection on this port
        # cannot be handed the last one's totals for a second and a half.
        try:
            px.clear_traffic(self.port)
            px.clear_hosts(self.port)
        except Exception:
            pass
        self.child = None
        self.exit_info = None
        if not quiet:
            return self.status()

    # -- moving it ---------------------------------------------------------

    def set_port(self, port):
        """Listen somewhere else, and take a live connection with you.

        Applied now rather than at the next connect. The alternative is a
        field reading 9050 while Windows is still pointed at 8877, and the
        one thing this window cannot do is show a number that has stopped
        being true - it is the only evidence anyone has that the app is doing
        what it says.

        So while something is up, this is a move: the machine goes back, the
        worker is stopped, and the same exit is dialled again on the new port.
        The exit is not re-chosen. It was raced for and accepted the
        credentials, and throwing that away to change a port number would
        cost the several seconds of probing that finding it took.
        """
        port = clean_port(port)
        if port == self.port:
            return {'moved': False, 'busy': False, **self.status()}

        with self.lock:
            rec = self.running()
            if not rec:
                # Nothing is up, so this is only a number to remember. A port
                # somebody else holds is still allowed: whatever has it may
                # well be gone by the time anyone presses Connect. Said, not
                # refused - the caller passes the warning on.
                self.port = port
                return {'moved': False, 'busy': port_holder(port) is not None,
                        **self.status()}

            # exit_info is empty when this window did not start the proxy -
            # closing it leaves the connection up on purpose, and the next
            # launch adopts it. The state file knows which exit it is, and a
            # move that skipped this case would change the number while
            # leaving the old worker running and the machine pointed at it.
            info = self.exit_info or {'ip': rec['ip'], 'host': rec['name']}

            # Connected, the same tolerance would be a working connection
            # traded for one that cannot come up. Checked before anything is
            # torn down, so a refusal costs nothing.
            held = port_holder(port)
            if held is not None:
                raise RuntimeError(
                    f'Something is already on 127.0.0.1:{port}'
                    + (f' - our own proxy to {held["name"]}, pid {held["pid"]}.'
                       if held.get('pid') else
                       ', and it is not ours. Pick another port.'))

            self.disconnect(quiet=True)
            self.port = port
            child, out_path = self._spawn(info['ip'], info['host'])
            self._wait_listening(child, out_path)
            self.child = child
            if self.set_system_proxy:
                self.sysproxy.engage('127.0.0.1', self.port)
            # Everything already known about this exit survives the move -
            # the country, how fast it answered, and the address Cloudflare
            # confirmed. Only the process is new.
            self.exit_info = {**info, 'pid': child.pid}
            return {'moved': True, 'busy': False, **self.status()}

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
        proxy = f'http://127.0.0.1:{self.port}'
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

    def current_ip(self, timeout=8):
        """The address you present right now, asked without the proxy.

        Deliberately not through any opener that might inherit the system
        proxy setting - if this went through our own proxy it would report
        the exit and quietly claim nothing had changed.
        """
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}))
        req = urllib.request.Request(
            'https://www.cloudflare.com/cdn-cgi/trace',
            headers={'User-Agent': px.UA_BROWSER})
        with opener.open(req, timeout=timeout) as r:
            body = r.read(4096).decode('utf-8', 'replace')
        seen = dict(line.split('=', 1) for line in body.splitlines()
                    if '=' in line)
        return {'ip': seen.get('ip'), 'country': (seen.get('loc') or '').lower()}

    # -- what state are we in ---------------------------------------------

    def running(self):
        rec = px.read_state(self.port)
        return rec if rec and px.alive(rec['pid']) else None

    # How stale a reading may be before it stops counting as one. The worker
    # writes every second, so three is a couple of missed writes - a busy
    # machine, a slow disk - rather than a proxy that has gone.
    STALE_AFTER = 3.5

    def traffic(self):
        """How much has gone each way, or nothing if nobody is counting.

        Nothing, rather than zeroes, when there is no proxy: zeroes are a
        real reading of a quiet line and the window draws them as one. A
        connection that has ended has no reading at all, and saying so is
        what lets the meter be put away instead of frozen at its last value.

        The pid is checked against the state file as well as the timestamp.
        A worker killed outright never gets to delete its file, so the one
        left on disk can belong to a proxy that stopped and a new proxy on
        the same port would otherwise inherit its totals.
        """
        rec = self.running()
        if not rec:
            return None
        got = px.read_traffic(self.port)
        if not got or got.get('pid') != rec['pid']:
            return None
        stale = (time.time() - float(got.get('at') or 0)) > self.STALE_AFTER
        return {'live': True,
                'up': int(got.get('up') or 0),
                'down': int(got.get('down') or 0),
                # A stale file's rate is the rate a second before whatever
                # went wrong, which on screen is a line still moving for a
                # connection that may not be. The totals are the last true
                # thing it said, so those are kept and the rates are not.
                'upRate': 0.0 if stale else float(got.get('up_bps') or 0),
                'downRate': 0.0 if stale else float(got.get('down_bps') or 0),
                'since': float(got.get('since') or 0),
                'stale': stale}

    def hosts(self):
        """What went where, and which program asked for it.

        Checked the same way as traffic(): the pid on the file against the pid
        in the state file, so a list left behind by a proxy that was killed is
        not shown as this one's. Nothing is trusted from the rows themselves -
        a hostname in here is whatever a program on this machine asked for,
        and it reaches the page as text to be put in a list.
        """
        rec = self.running()
        if not rec:
            return None
        got = px.read_hosts(self.port)
        if not got or got.get('pid') != rec['pid']:
            return None
        rows = [{'host': str(r.get('host') or '')[:120],
                 'app': (str(r['app'])[:60] if r.get('app') else None),
                 'pid': int(r.get('pid') or 0),
                 'up': int(r.get('up') or 0),
                 'down': int(r.get('down') or 0),
                 'hits': int(r.get('hits') or 0),
                 'live': int(r.get('live') or 0),
                 'first': float(r.get('first') or 0),
                 'last': float(r.get('last') or 0)}
                for r in (got.get('rows') or [])]
        return {'live': True, 'rows': rows,
                'total': int(got.get('total') or len(rows)),
                'since': float(got.get('since') or 0),
                'at': float(got.get('at') or 0),
                # The list is written every other second, so a page that
                # polls faster than that is looking at the same answer twice.
                # Said out loud rather than left to be worked out from `at`.
                'every': px.LEDGER_EVERY}

    def status(self):
        rec = self.running()
        engaged = self.sysproxy.engaged_for(self.port)
        state = 'off'
        if rec and (engaged or not self.set_system_proxy):
            state = 'on'
        elif rec or engaged:
            # One without the other is not a working connection, and saying
            # "connected" here would be a lie the user pays for in failed
            # page loads.
            state = 'broken'
        out = {'state': state, 'port': self.port,
               'folder': self.folder,
               'systemProxy': self.set_system_proxy,
               'owed_restore': bool(self.sysproxy.stashed())}
        if rec:
            out['exit'] = {'ip': rec['ip'], 'host': rec['name'],
                           'since': rec['since']}
        if self.exit_info:
            out['exit'] = {**out.get('exit', {}), **self.exit_info}
            out['exit'].pop('since', None)
        return out
