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

import accounts
import paths
import windscribe
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
#
# `ws` beside `prod` is Windscribe, whose exits arrive as a JSON list of
# hostnames rather than as downloaded configs and are written into the same
# shape on the way in - "us-dal.ws.us-central-117.totallyacdn.com_1.2.3.4".
# Everything downstream of the filename then works on either provider: the
# catalogue, the racing, the re-pinning, the one-per-city dedupe.
CONFIG = re.compile(r'^(?:(\d+\.\d+)s-)?([a-z]{2})-([a-z]{3})\.(?:prod|ws)\.',
                    re.I)

# The port the proxy sits on. 8877 rather than 8899, which is what the command
# line half of this repo uses for a second proxy beside somebody's own: the two
# are meant to be able to run at the same time, and sharing a number meant that
# starting the app while a terminal proxy was up failed with "address already
# in use" and no obvious cause.
DEFAULT_PORT = 8877

# What the last reachability test found, kept between runs: an exit that
# was refused an hour ago is worth showing as refused now, rather than
# making somebody find out again.
REACH_PATH = os.path.join(paths.STATE_DIR, 'reach.json')


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
    __slots__ = ('path', 'file', 'seconds', 'country', 'city', 'provider')

    def __init__(self, path, file, seconds, country, city):
        self.path, self.file = path, file
        self.seconds, self.country, self.city = seconds, country, city
        # Read off the filename, which is the only place it is written down -
        # and the same fact that decides which credential opens the exit and
        # whether a bare 200 from it can be believed.
        self.provider = (accounts.WINDSCRIBE if windscribe.is_windscribe(file)
                         else accounts.SURFSHARK)


class Engine:
    """One connection at a time, and always able to say what state it is in."""

    def __init__(self, sysproxy, folder=None, auth_file=None,
                 set_system_proxy=True, port=None):
        self.sysproxy = sysproxy
        self.folder = folder or paths.servers_dir()
        # Every folder worth looking in, the primary one first. A list rather
        # than a single folder because the two providers arrive by different
        # routes and land in different places - Surfshark's configs are
        # downloaded and pinned, Windscribe's are fetched and pinned - and
        # "use both at once" has to mean something without asking anyone to
        # merge two folders by hand first.
        self.folders = [self.folder]
        # Which providers to offer. None is all of them, which is what an app
        # that has only ever had one provider should keep doing.
        self.providers = None
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

    def sources(self):
        """The folders to read, primary first and without repeats."""
        out = []
        for folder in [self.folder] + list(self.folders or []):
            if folder and folder not in out:
                out.append(folder)
        return out

    def servers(self):
        """The exits on offer: everything found, less what is filtered out."""
        return [s for s in self.scan()
                if self.providers is None or s.provider in self.providers]

    def counts_by_provider(self):
        """How many exits each provider has, whatever is selected.

        Its own method rather than servers() with the filter turned off for a
        moment: the filter is read by a connect racing on another thread, and
        borrowing it to count with is a way to have that race see a provider
        it was told not to offer.
        """
        out = {}
        for s in self.scan():
            out[s.provider] = out.get(s.provider, 0) + 1
        return out

    def scan(self):
        out, seen = [], set()
        for folder in self.sources():
            try:
                names = os.listdir(folder)
            except OSError:
                continue
            for name in sorted(names):
                if not name.endswith('.ovpn') or name in seen:
                    # The same config pinned into two folders is one exit, and
                    # counting it twice would weight the race towards it.
                    continue
                m = CONFIG.match(name)
                if not m:
                    continue
                seen.add(name)
                out.append(Server(os.path.join(folder, name), name,
                                  float(m.group(1)) if m.group(1) else None,
                                  m.group(2).lower(), m.group(3).lower()))
        return out

    def catalogue(self):
        """Countries, not servers. Ninety-one endpoints is a list nobody
        reads; seventy-five countries is a thing people already have opinions
        about. Which city inside one is our problem, not theirs."""
        found = self.reach()
        notes = windscribe.meta()
        by_country = {}
        cities = {}
        for s in self.servers():
            note = notes.get(windscribe.config_stem(s.file)) or {}
            # The city is the level Windscribe's own client groups by, and it
            # is the one worth having: "Paris" is a place somebody means,
            # where "fr-030.totallyacdn.com" is an address it happens to be
            # at. The three-letter code comes out of the filename, and the
            # name and nickname out of the notes beside it - so a city still
            # groups correctly when the notes are missing, it just reads as
            # its code.
            city = cities.setdefault((s.country, s.city), {
                'code': s.city, 'country': s.country,
                'name': note.get('city') or city_name(s.city),
                'nick': note.get('nick') or '',
                'count': 0, 'tested': 0, 'ok': 0, 'ping': None,
                'load': note.get('load'), 'gbps': note.get('gbps'),
                'p2p': note.get('p2p'), 'by': {}})
            city['count'] += 1
            city['by'][s.provider] = city['by'].get(s.provider, 0) + 1
            # The least loaded of the group is the honest figure for a city
            # that has more than one: it is the one the connection would be
            # handed if it asked now.
            if note.get('load') is not None and (
                    city['load'] is None or note['load'] < city['load']):
                city['load'] = note['load']

            rec_city = found.get(s.file)
            if rec_city:
                city['tested'] += 1
                if rec_city.get('ok'):
                    city['ok'] += 1
                    ms = rec_city.get('ms')
                    if ms is not None and (city['ping'] is None
                                           or ms < city['ping']):
                        city['ping'] = ms

            c = by_country.setdefault(s.country, {'code': s.country,
                                                  'cities': set(),
                                                  'count': 0,
                                                  'best': None,
                                                  'by': {},
                                                  'tested': 0,
                                                  'ok': 0,
                                                  'ping': None,
                                                  'byOk': {},
                                                  'byPing': {}})
            c['cities'].add(s.city)
            c['count'] += 1

            # What the last test found about this exit, folded up per country
            # and per provider. Kept as separate keys rather than folded into
            # `by` so that a country nobody has tested still answers the only
            # question the list used to be able to answer - how many are here.
            rec = found.get(s.file)
            if rec:
                c['tested'] += 1
                if rec.get('ok'):
                    c['ok'] += 1
                    c['byOk'][s.provider] = c['byOk'].get(s.provider, 0) + 1
                    ms = rec.get('ms')
                    if ms is not None:
                        if c['ping'] is None or ms < c['ping']:
                            c['ping'] = ms
                        was = c['byPing'].get(s.provider)
                        if was is None or ms < was:
                            c['byPing'][s.provider] = ms
            # Per provider as well as in total, because a country backed by
            # eleven Surfshark exits and one Windscribe one is a different
            # proposition from the reverse, and the row that says only "12
            # relays" cannot tell you which you are about to get.
            c['by'][s.provider] = c['by'].get(s.provider, 0) + 1
            if s.seconds is not None and (c['best'] is None
                                          or s.seconds < c['best']):
                c['best'] = s.seconds
        out = []
        for code, c in by_country.items():
            out.append({'code': code,
                        'name': country_name(code),
                        'alias': aliases(code),
                        'cities': len(c['cities']),
                        # The cities themselves, quickest first, so that
                        # opening a country shows the one worth taking at the
                        # top rather than whichever is alphabetically first.
                        'cityList': sorted(
                            (v for k, v in cities.items() if k[0] == code),
                            key=lambda x: (x['tested'] and not x['ok'],
                                           x['ping'] is None,
                                           x['ping'] if x['ping'] is not None
                                           else 0, x['name'])),
                        'count': c['count'],
                        'by': c['by'],
                        'tested': c['tested'],
                        'ok': c['ok'],
                        'ping': c['ping'],
                        'byOk': c['byOk'],
                        'byPing': c['byPing'],
                        'best': c['best']})
        # Answering first, then quickest, then the rest.
        #
        # A country every one of whose addresses was refused belongs at the
        # bottom whatever its name: it is the one thing about a list of
        # places to connect through that is worth reordering for. Untested
        # sits between the two - not known to be blocked, not known to be
        # quick - and an unmeasured exit is still not a fast one.

        def rank(c):
            blocked = c['tested'] and not c['ok']
            return (bool(blocked),
                    c['ping'] is None,
                    c['ping'] if c['ping'] is not None else 0,
                    c['best'] is None, c['best'] or 0, c['name'])

        out.sort(key=rank)
        return out

    # -- credentials -------------------------------------------------------

    @staticmethod
    def read_auth(path):
        """The two lines of a credentials file, or a RuntimeError.

        Not px.read_auth, for the reason this file gives at the top about
        pick_live: that one is written for a command line, where the way to
        report a missing file is to print four lines about where to get one
        and exit. Behind a window it still prints them - to the console in a
        terminal run, and into the diagnostics file in a built copy - and
        then the caller catches the exception and carries on perfectly well.
        So the app was answering "not signed in" correctly while spraying

            [fail] cannot read credentials from ...\\.ovpn-auth

        every time the settings sheet opened, which reads as the thing that
        just went wrong rather than as a question that was asked and
        answered. Nothing was wrong; a provider simply had no account.

        Same two lines, same rule about blanks, no opinions about it.
        """
        try:
            with open(path, encoding='utf-8') as f:
                lines = [line.strip() for line in f if line.strip()]
        except OSError:
            raise RuntimeError('no-credentials')
        if len(lines) < 2:
            raise RuntimeError('no-credentials')
        return lines[0], lines[1]

    def credentials(self):
        return self.read_auth(self.auth_file)

    def auth_file_for(self, server):
        """Which credentials file opens this exit.

        Not a setting, because one folder can hold both providers - a sweep
        that measured Surfshark and Windscribe exits into the same site
        folder is a reasonable thing to have done - and a single answer for
        the whole folder would be wrong for half of it. The filename says
        which provider the exit came from, so the filename decides.
        """
        name = getattr(server, 'file', server)
        if windscribe.is_windscribe(name):
            return windscribe.AUTH_FILE
        return self.auth_file

    def credentials_for(self, server):
        path = self.auth_file_for(server)
        if path == self.auth_file:
            return self.credentials()
        try:
            return self.read_auth(path)
        except RuntimeError:
            # Named apart from the other one because the fix is different:
            # this credential is signed in for, not typed.
            raise RuntimeError('no-windscribe-credentials')

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

    def candidates(self, country, provider=None, only=None, city=None):
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
        # One named config beats every other rule here: it was picked off a
        # list of exits with their own measured times, and racing its
        # neighbours instead would be answering a question nobody asked.
        if only:
            return [s for s in self.servers() if s.file == only]
        pool = [s for s in self.servers()
                if (country in (None, 'auto') or s.country == country)
                and (provider is None or s.provider == provider)
                and (city is None or s.city == city)]
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

    def exits(self, country, provider=None, city=None):
        """Every individual exit in one country, with what is known about it.

        The list has always been countries, because ninety-one endpoints is
        not something anybody reads. But once each one has a measured answer
        and a time beside it, the individual exits are worth being able to
        look at - "why is this country slow" and "is this one blocked" are
        questions about a server, not about a place.
        """
        found = self.reach()
        notes = windscribe.meta()
        out = []
        for s in self.servers():
            if s.country != country:
                continue
            if provider and s.provider != provider:
                continue
            if city and s.city != city:
                continue
            rec = found.get(s.file) or {}
            try:
                ip, host = px.read_config(s.path)
            except SystemExit:
                ip, host = None, None
            note = notes.get(windscribe.config_stem(s.file)) or {}
            out.append({'file': s.file, 'city': s.city,
                        'cityName': note.get('city') or city_name(s.city),
                        'nick': note.get('nick') or '',
                        'load': note.get('load'), 'gbps': note.get('gbps'),
                        'p2p': note.get('p2p'), 'provider': s.provider,
                        'host': host or '', 'ip': ip or '',
                        'ok': rec.get('ok'), 'ms': rec.get('ms'),
                        'why': rec.get('why', ''), 'at': rec.get('at')})
        # Answering first and quickest first; untested after those, refused
        # last. The same order as the countries, for the same reason.
        out.sort(key=lambda e: (e['ok'] is False, e['ok'] is None,
                                e['ms'] if e['ms'] is not None else 0,
                                e['host']))
        return out

    def with_credentials(self, pool):
        """The exits in that pool that something can actually open, and the
        credentials to open them with.

        Read once per provider, not once per probe - and before the race
        rather than inside it, so "you have no credentials" is an answer that
        arrives immediately instead of eighty timeouts later.
        """
        creds, missing = {}, {}
        for s in list(pool):
            path = self.auth_file_for(s)
            if path in creds or path in missing:
                continue
            try:
                creds[path] = self.credentials_for(s)
            except RuntimeError as e:
                missing[path] = str(e)
        if missing:
            # Half a pool is still a pool. Only if nothing is left does the
            # missing credential become the thing that stopped the connect.
            pool = [s for s in pool if self.auth_file_for(s) in creds]
            if not pool:
                raise RuntimeError(sorted(missing.values())[0])
        return pool, creds

    def ask_exit(self, server, creds, timeout=6):
        """Whether this one exit takes the credentials, and how long it took.

        The same question the race asks, in the same way, which is the point:
        a reachability test that probed differently would be measuring
        something other than whether connecting is about to work.
        """
        ip, host = px.read_config(server.path)
        if not host:
            raise OSError('not pinned')
        user, password = creds[self.auth_file_for(server)]
        if windscribe.is_windscribe(server.file):
            # A `200` from Windscribe is not an answer. Its nghttpx says 200
            # to an unauthenticated CONNECT too and then forwards nothing, so
            # can_connect() - which is right for Surfshark, where the same
            # request is refused with 407 - would report every exit alive and
            # hand back one that silently drops everything. Ask with a whole
            # request instead.
            #
            # Twice the budget, because it is doing about twice the work:
            # can_connect stops at the CONNECT, this one tunnels, does an
            # inner handshake and fetches a page. windscribe.md measured cold
            # dials at up to 4.7s, which is inside six seconds only just -
            # and an exit failed for being slow is one the race never comes
            # back to.
            took = windscribe.verify_tunnel(
                px, ip, host, user, password, timeout * 2)['ttfb']
        else:
            took = px.can_connect(
                px.Exit(ip, 443, host, user, password), timeout)
        return took, ip, host

    # -- which of them are actually reachable -----------------------------

    def reach(self):
        """What the last test found, by config filename."""
        try:
            with open(REACH_PATH, encoding='utf-8') as f:
                got = json.load(f)
            return got if isinstance(got, dict) else {}
        except (OSError, ValueError):
            return {}

    def test_reach(self, progress, width=8, timeout=6, only=None):
        """Ask every exit on offer whether it answers, and how fast.

        This is the thing the app could never say. An exit that is filtered
        on this line looks exactly like one that is simply slow, and the only
        way to tell them apart is to ask - so the answer was always "try
        connecting and see", which spends the same time and throws the
        finding away.

        Kept between runs, because the finding is worth more than the run: a
        country whose every address was refused an hour ago is worth showing
        as blocked now, rather than making somebody discover it again.
        """
        pool = [s for s in self.servers()
                if not only or s.file in only]
        if not pool:
            raise RuntimeError('no-servers')
        pool, creds = self.with_credentials(pool)

        self.cancelled.clear()
        found = self.reach()
        done, total = 0, len(pool)
        progress({'phase': 'testing', 'done': 0, 'total': total})

        def ask(s):
            if self.cancelled.is_set():
                raise OSError('cancelled')
            took, ip, _host = self.ask_exit(s, creds, timeout)
            return s, took, ip

        ex = cf.ThreadPoolExecutor(max_workers=width)
        try:
            futures = {ex.submit(ask, s): s for s in pool}
            for fut in cf.as_completed(list(futures)):
                done += 1
                s = futures[fut]
                try:
                    _s, took, ip = fut.result()
                    found[s.file] = {'ok': True, 'ms': int(took * 1000),
                                     'ip': ip, 'at': int(time.time())}
                except Exception as e:
                    # Why it refused is worth keeping. "filtered here" and
                    # "no proxy for this account" are the same red dot and
                    # completely different problems.
                    found[s.file] = {'ok': False, 'ms': None,
                                     'why': str(e).strip()[:60] or 'no answer',
                                     'at': int(time.time())}
                if done % 4 == 0 or done == total:
                    progress({'phase': 'testing', 'done': done, 'total': total})
                if self.cancelled.is_set():
                    break
        finally:
            for f in futures:
                f.cancel()
            ex.shutdown(wait=False, cancel_futures=True)
            self._save_reach(found)

        ok = sum(1 for s in pool if found.get(s.file, {}).get('ok'))
        return {'tested': done, 'total': total, 'ok': ok,
                'cancelled': self.cancelled.is_set()}

    @staticmethod
    def _save_reach(found):
        os.makedirs(paths.STATE_DIR, exist_ok=True)
        tmp = f'{REACH_PATH}.{os.getpid()}'
        try:
            with open(tmp, 'w', encoding='utf-8') as f:
                json.dump(found, f)
            os.replace(tmp, REACH_PATH)
        except OSError:
            pass

    def find_exit(self, country, progress, width=8, timeout=6,
                  provider=None, only=None, city=None):
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
        ordered = self.candidates(country, provider, only, city)
        if not ordered:
            raise RuntimeError('no-servers')
        ordered, creds = self.with_credentials(ordered)

        self.cancelled.clear()
        winners, asked, done = [], len(ordered), 0
        progress({'phase': 'probing', 'asked': 0, 'total': asked})

        def probe(s):
            if self.cancelled.is_set():
                raise OSError('cancelled')
            took, ip, host = self.ask_exit(s, creds, timeout)
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

    def _spawn(self, ip, host, auth_file=None):
        """The proxy runs as its own process, exactly as `--detach` starts
        it. Keeping it out of this one means a wedged connection cannot take
        the window down with it, and the window closing does not have to be
        the thing that stops it.

        The credentials file is passed in rather than read off self, because
        the exit that won the race decides it: a Windscribe exit started with
        the Surfshark credential comes up, listens, and refuses everything.
        """
        out_path = os.path.join(paths.STATE_DIR, f'proxy-{self.port}.out')
        os.makedirs(paths.STATE_DIR, exist_ok=True)
        argv = paths.worker_argv(ip, host, self.port,
                                 auth_file or self.auth_file)
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

    def connect(self, country, progress, provider=None, only=None,
                city=None):
        with self.lock:
            self.disconnect(quiet=True)

            took, server, ip, host = self.find_exit(
                country, progress, provider=provider, only=only,
                city=city)
            progress({'phase': 'starting', 'country': server.country,
                      'city': server.city})

            child, out_path = self._spawn(ip, host,
                                          self.auth_file_for(server))
            self._wait_listening(child, out_path)
            self.child = child

            if self.set_system_proxy:
                progress({'phase': 'routing'})
                self.sysproxy.engage('127.0.0.1', self.port)

            self.exit_info = {'ip': ip, 'host': host, 'country': server.country,
                              'city': server.city, 'provider': server.provider,
                              'answered': round(took, 2),
                              'pid': child.pid, 'since': time.time()}
            # Deliberately NOT verified here. The connection is live the
            # moment the machine is pointed at a listening proxy; asking a
            # website to confirm it is a second network round trip, and
            # holding the word "connected" back for it made every connect a
            # second slower than it had to be. The caller confirms
            # afterwards and fills the address in when it arrives.
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

        # Reaped, and the handle let go of with it.
        #
        # Not housekeeping. On Windows a process object outlives the process
        # for as long as anyone holds a handle, and this object held one on
        # every proxy it started - so a force-killed worker stayed "running"
        # to anything that asked, its record survived, and the next connect
        # was refused with "a proxy is already up on port 8877" naming a pid
        # that had been dead for half an hour. alive() no longer answers that
        # way, and this stops the handle being held in the first place.
        child, self.child = self.child, None
        if child is not None:
            try:
                child.wait(timeout=5)
            except Exception:
                pass

        # And the record itself. Killed with /F, the worker never gets to
        # remove its own line, so the file keeps a proxy that is not there.
        # read_states() already leaves the dead ones out; writing back what
        # it returns is what takes them out of the file.
        try:
            px.put_states(px.read_states())
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
