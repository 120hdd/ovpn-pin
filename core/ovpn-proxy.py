#!/usr/bin/env python3
"""
Reach the web through a Surfshark exit's HTTPS proxy instead of its tunnel.

The same machines that answer OpenVPN on 1443 also run an HTTPS proxy on
443, and on a line that throttles OpenVPN the difference is not small: the
tunnel measured 0.3 Mbit here, the proxy 99. Same server, same exit address,
same credentials. Nothing about the exit is faster - the line simply cannot
tell this apart from any other HTTPS connection, and can tell OpenVPN apart
immediately.

Two things have to stay off the wire for that to hold:

  the address   resolved over DoH and pinned into the config already, so no
                resolver gets to answer for where the exit is. This reads
                the address back out of the pinned file rather than looking
                anything up again.

  the name      not sent as SNI. A handshake that names *.prod.surfshark.com
                in the clear gets killed on the way out - which is worth
                knowing, because it is the same inspection that makes the
                tunnel unusable, just at a different layer.

Dropping SNI would normally cost you authentication, so the certificate is
checked by hand instead: the chain still has to verify against the system
CAs, and the name still has to match what the certificate says it serves.
The name is proved, it is just never announced.

What the browser sees is an ordinary proxy on 127.0.0.1 - no certificate to
approve, no password prompt, nothing to explain to it.

    core/ovpn-proxy.py uk-man              # enough of a pinned config's name
    core/ovpn-proxy.py 139.28.176.165 --host uk-man.prod.surfshark.com
    core/ovpn-proxy.py uk-man --port 8080

This carries HTTP, HTTPS and SOCKS5, all on the one port - the first byte a
client sends decides which, so 0x05 is SOCKS5 and a letter is HTTP. All three
leave as the same CONNECT at the exit.

SOCKS5 is there for clients that will not tunnel over an HTTP proxy. Telegram
is the one that matters: told its proxy is HTTP it sets useTcp false and stops
tunnelling entirely, turning every MTProto packet into its own POST to port 80
in the clear. Pointed at SOCKS5 it keeps one connection instead.

It is not a tunnel: nothing else on the machine goes through it, and neither
does UDP, ICMP, or anything that ignores the proxy setting it was given.
"""

import argparse
import base64
import collections
import concurrent.futures as cf
import json
import os
import re
import select
import shutil
import socket
import ssl
import struct
import sys
import threading
import time

# This file lives in core/, and every path built from here is one of the
# reader's own things - the credentials, the pinned configs, .state - which
# sit one level up beside the platform folders rather than inside either of
# them. So it is the parent that is wanted, not the folder this file is in.
#
# Frozen into the app none of that holds: the file is unpacked into
# _internal and the data is beside the exe. paths.point_proxy_module_at_data
# sets ROOT again for that case, which is why the name is worth having.
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUTH_DEFAULT = os.path.join(ROOT, '.ovpn-auth')

# A scripted user agent gets challenged on its own merits, and would frame a
# clean exit as a dirty one. Same reasoning, same string, as the tunnel sweep.
UA_BROWSER = ('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 '
              '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')

# The line the pinner leaves behind, which is the only place the config still
# remembers what name the address was resolved from:
#     #   uk-man.prod.surfshark.com -> 139.28.176.165
PIN_COMMENT = re.compile(r'^#\s+(\S+)\s+->\s+(\d{1,3}(?:\.\d{1,3}){3})\s*$')
REMOTE_LINE = re.compile(r'^\s*remote\s+(\S+)', re.MULTILINE)
IS_IPV4 = re.compile(r'^\d{1,3}(?:\.\d{1,3}){3}$')

# How many exits one sweep will ask about unless told otherwise. See do_sweep.
SWEEP_CAP = 40


#-------------------------------------------------------------------- output

# 37m for the asides, matching what the shell scripts here settled on. Not
# 90m: that is "bright black", and on a black terminal it is very nearly
# nothing at all - which is the worst possible fate for the lines that
# explain what just went wrong.
def _colours(stream=None):
    stream = stream or sys.stdout
    # There may be no stdout at all. A --noconsole build has none, and this
    # runs at import time - so the app, which imports this file for its
    # protocol code rather than to print anything, died on an AttributeError
    # here before its window could open. Nowhere to write is not a terminal,
    # which is the answer the plain-text branch below already wants.
    if (os.environ.get('NO_COLOR') or stream is None
            or not stream.isatty()):
        return {k: '' for k in
                ('off', 'head', 'ok', 'warn', 'fail', 'dim', 'bold')}
    if os.name == 'nt':
        # Windows 10+ needs the terminal put into ANSI mode before it will
        # interpret any of this; without it the codes print as text.
        try:
            import ctypes
            k = ctypes.windll.kernel32
            k.SetConsoleMode(k.GetStdHandle(-11), 7)
        except Exception:
            return {k: '' for k in
                    ('off', 'head', 'ok', 'warn', 'fail', 'dim', 'bold')}
    return {'off': '\033[0m', 'head': '\033[36m', 'ok': '\033[32m',
            'warn': '\033[33m', 'fail': '\033[31m', 'dim': '\033[37m',
            'bold': '\033[1m'}


C = _colours()
LOG_PATH = os.path.join(ROOT, '.state', 'proxy.log')

# Where the lines meant for a person go. stdout everywhere except `env`,
# whose stdout belongs to a shell that is about to eval it - a stray "folder:
# pinned" in among the exports would be run as a command. See do_env.
OUT = sys.stdout


# What to tell someone to type. Every message in this file is written as
# `ovpn proxy ...`, because that is what it is called on the machine it was
# written for - but `ovpn` is a bash script, and on Windows there is nothing
# by that name. Printing it there sends someone off to type a command that
# cannot exist, which is a worse failure than being wordy.
#
# The dispatcher says so itself rather than being guessed at, so a Linux box
# where the script is called directly gets the honest form too.
CMD = ('ovpn proxy' if os.environ.get('OVPN_DISPATCHER')
       else ('python core/ovpn-proxy.py' if os.name == 'nt'
             else 'python3 core/ovpn-proxy.py'))

# px comes from ovpn-shell.sh, so it exists in bash and zsh and nowhere else.
# No sense offering it to a PowerShell prompt.
HAS_PX = os.name != 'nt'


def phrase(s):
    """Say the command's name the way this machine would.

    Done here, where everything is printed, rather than at the forty-odd
    places that mention it.
    """
    return s if CMD == 'ovpn proxy' else s.replace('ovpn proxy', CMD)


def point_hint(port, host='127.0.0.1'):
    """The shortest true thing to type to send a terminal through it."""
    if HAS_PX:
        return f'px {port}'
    return f"$env:http_proxy='http://{host}:{port}'"


def talk_on(stream):
    """Send the human-facing half of the output somewhere else, and decide
    colour by that stream rather than by stdout - otherwise a run whose
    stdout is a pipe comes out plain even when the person is watching a
    terminal."""
    global OUT, C
    OUT = stream
    C = _colours(stream)


def log(level, msg):
    """Every warning and failure also goes to .state/proxy.log, because the
    interesting ones happen while a browser is using this and the terminal
    has scrolled. Best effort - a proxy that cannot write its log should
    still proxy."""
    try:
        os.makedirs(os.path.dirname(LOG_PATH), exist_ok=True)
        stamp = time.strftime('%Y-%m-%d %H:%M:%S')
        with open(LOG_PATH, 'a', encoding='utf-8') as f:
            f.write(f'{stamp}  {level:<5} {msg}\n')
    except OSError:
        pass


def head(msg):
    print(phrase(f'\n  {C["head"]}{C["bold"]}{msg}{C["off"]}'), file=OUT)


def field(label, value):
    print(phrase(f'  {C["head"]}{label:<12}{C["off"]}{value}'), file=OUT)


def ok(msg):
    print(phrase(f'  {C["ok"]}[ ok ]{C["off"]} {msg}'), file=OUT)


def warn(msg, detail=''):
    # Flushed, because the failure that usually follows goes to stderr, and
    # unflushed stdout would let it print first and read as the cause.
    sys.stdout.flush()
    print(phrase(f'  {C["warn"]}[warn]{C["off"]} {msg}'), file=OUT, flush=True)
    # Every line indented, not just the first - an unindented second line
    # reads as a separate message rather than as part of this one.
    for line in detail.splitlines():
        print(phrase(f'         {C["dim"]}{line}{C["off"]}'), file=OUT,
              flush=True)
    log('WARN', msg + (f' - {detail.replace(chr(10), " ")}' if detail else ''))


def note(msg):
    print(phrase(f'  {C["dim"]}{msg}{C["off"]}'), file=OUT)


def die(msg, detail=''):
    print(phrase(f'\n  {C["fail"]}{C["bold"]}[fail]{C["off"]} {msg}'),
          file=sys.stderr)
    for line in detail.splitlines():
        print(phrase(f'         {C["dim"]}{line}{C["off"]}'), file=sys.stderr)
    print(file=sys.stderr)
    log('FAIL', msg + (f' - {detail.replace(chr(10), " ")}' if detail else ''))
    raise SystemExit(1)


#--------------------------------------------------------------- the config

def read_config(path):
    """The address the config is pinned to, and the name it was pinned from."""
    with open(path, encoding='utf-8', errors='replace') as f:
        head = f.read(4096)

    name = None
    for line in head.splitlines():
        m = PIN_COMMENT.match(line)
        if m:
            name, _ = m.groups()
            break

    m = REMOTE_LINE.search(head)
    if not m:
        die(f'{os.path.basename(path)} has no remote line')
    addr = m.group(1)
    if not IS_IPV4.match(addr):
        die(f'{os.path.basename(path)} is not pinned',
            f'Its remote is still the name {addr!r}, which is the thing your\n'
            f'resolver lies about. Pin it first:  ovpn pin')
    return addr, name


def find_config(fragment, folder):
    """A pinned config by enough of its filename. Ambiguous is refused."""
    if not os.path.isdir(folder):
        die(f'no such folder: {folder}')
    hits = [f for f in sorted(os.listdir(folder))
            if f.endswith('.ovpn') and fragment.lower() in f.lower()]
    if not hits:
        die(f'nothing in {folder} matches {fragment!r}',
            'Check the spelling, or list what is there:  ls ' + folder)
    if len(hits) > 1:
        # One address per exit name is the usual case, and picking between
        # two addresses for the same exit is not a decision worth stopping
        # for - but two different exits is, so say which.
        exits = {h.split('_')[0] for h in hits}
        if len(exits) > 1:
            shown = '\n  '.join(hits[:8])
            more = f'\n  ... and {len(hits) - 8} more' if len(hits) > 8 else ''
            die(f'{fragment!r} is ambiguous - {len(hits)} configs across '
                f'{len(exits)} exits',
                f'{shown}{more}\nSay enough of the name to pick one of them.')
    return os.path.join(folder, hits[0])


def read_auth(path):
    try:
        with open(path, encoding='utf-8') as f:
            lines = [l.strip() for l in f.read().splitlines() if l.strip()]
    except OSError as e:
        die(f'cannot read credentials from {path}', f'{e}\n'
            'Two lines: the Surfshark *service* username, then its password.\n'
            'Not your login email - https://my.surfshark.com/vpn/manual-setup')
    if len(lines) < 2:
        die(f'{path} does not look like a credentials file',
            'It should hold the service username on one line and the password\n'
            'on the next. Nothing else.')
    return lines[0], lines[1]


#------------------------------------------------------------------- upstream

def name_matches(cert, host):
    """Hostname verification, done here because OpenSSL was not given the
    name to do it with. Same rule it would have applied: a wildcard covers
    one label and no more."""
    host = host.lower().rstrip('.')
    for kind, value in cert.get('subjectAltName', ()):
        if kind != 'DNS':
            continue
        value = value.lower().rstrip('.')
        if value.startswith('*.'):
            if host.count('.') == value.count('.') and host.endswith(value[1:]):
                return True
        elif value == host:
            return True
    return False


# How many connections to the exit are opened before anyone asks for one.
#
# Every accepted connection used to pay TCP, TLS and CONNECT to another
# country before a byte moved - measured here at 516 ms, and 1024 ms when six
# start together. That second number is the one that mattered: Telegram
# rebuilds any connection that takes longer than a second to establish, so a
# burst put the proxy the wrong side of its own client's patience. With two
# held ready the same burst measured 797-851 ms, none of them over the line.
#
# Two rather than eight, deliberately. The win is concentrated in serial
# short-lived connections - a chat client reconnecting, the first request of
# a page - where the refill finishes long before the next one arrives.
# Chasing a parallel burst would mean six or eight per exit, which is exactly
# the connection rate note.md records an account being locked for.
WARM_DEFAULT = 2
WARM_MAX = 4
# A connection held longer than this is dropped rather than offered. Measured
# on these exits: an idle one was still good after 240 s, so this is well
# inside what they tolerate.
WARM_TTL = 120
# Outbound handshakes in flight at once, pool fills included. Six tabs
# opening together must not become six simultaneous dials at the exit.
OUT_MAX = 8


class Exit:
    def __init__(self, ip, port, host, user, password, bind=None, warm=0):
        self.ip, self.port, self.host, self.bind = ip, port, host, bind
        self.auth = base64.b64encode(f'{user}:{password}'.encode()).decode()
        self.ctx = ssl.create_default_context()
        # The chain is still verified - only the name check is taken over
        # below, because doing it in OpenSSL would mean sending the name.
        self.ctx.check_hostname = False
        # Off unless a caller asks. Everything that sweeps builds one of
        # these per exit, and pre-opening connections there would multiply
        # a 500-config run by the pool size - straight into the rate limit
        # that note.md spent a week failing to characterise.
        self.warm = max(0, min(warm, WARM_MAX))
        self._pool = collections.deque()
        self._plock = threading.Lock()
        self._gate = threading.Semaphore(OUT_MAX)
        self.tally = collections.Counter()

    def connect(self):
        raw = socket.create_connection(
            (self.ip, self.port), timeout=20,
            source_address=(self.bind, 0) if self.bind else None)
        raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        try:
            tls = self.ctx.wrap_socket(raw)      # no server_hostname: no SNI
        except (socket.timeout, TimeoutError):
            # A third thing, and worth telling apart from the other two: the
            # TCP handshake completes at the normal round trip and then the
            # TLS handshake is answered by nobody. The server is not down -
            # reached through another exit, the same address completes TLS
            # immediately. Something between here and it takes the SYN and
            # then swallows the payload, so this address is filtered on this
            # line and no amount of retrying will change it. Re-pin the exit
            # and take a different address for it.
            raw.close()
            raise OSError('filtered here - TCP answers, TLS gets nothing back')
        except OSError:
            raw.close()
            raise
        if not name_matches(tls.getpeercert(), self.host):
            tls.close()
            raise ssl.SSLCertVerificationError(
                f'the certificate at {self.ip} does not serve {self.host}')
        return tls

    # ---- connections opened before anyone asked ----------------------------
    #
    # connect() above is left exactly as it was on purpose: it is the method
    # the tests stub out, and all of this is built on top of it rather than
    # inside it, so the suite keeps covering the part that reaches the exit.
    #
    # A CONNECT tunnel consumes its connection. Once the exit answers 200
    # there is no framing to go back to, so nothing can ever be handed back
    # and reused - "pooling" here means connections already opened and not
    # yet spoken on, which is a different thing and worth saying plainly.

    def _dial(self):
        """connect(), but never more than OUT_MAX at once."""
        self._gate.acquire()
        try:
            return self.connect()
        finally:
            self._gate.release()

    @staticmethod
    def _spent(tls):
        """Has the exit hung up on one that was sitting idle?

        Nothing has been asked of it, so it should have nothing to say:
        anything readable is EOF or noise and either way it is no longer
        worth offering. MSG_PEEK is not allowed on an SSLSocket, so this is
        select() and nothing finer - which catches an orderly close and
        misses an abortive one. That is why taking one still licenses a
        retry rather than being treated as proof of life.
        """
        try:
            if tls.pending():
                return True
            readable, _, failed = select.select([tls], [], [tls], 0)
        except (OSError, ValueError):
            return True
        return bool(readable or failed)

    def take(self):
        """A connection to the exit, warm if one is ready.

        Returns (tls, was_warm). was_warm is the caller's licence to try once
        more: an idle connection can have died without saying so, and at this
        point nothing has been sent and nothing has reached the client.
        """
        now = time.monotonic()
        while True:
            with self._plock:
                if not self._pool:
                    break
                tls, born = self._pool.popleft()
            if now - born > WARM_TTL or self._spent(tls):
                self.tally['stale'] += 1
                try:
                    tls.close()
                except OSError:
                    pass
                continue
            self.tally['warm'] += 1
            threading.Thread(target=self._fill, daemon=True).start()
            return tls, True
        self.tally['cold'] += 1
        return self._dial(), False

    def _fill(self):
        """Top up by one, if there is room and the exit is not already being
        dialled as hard as we allow. Never waits for the gate: under load,
        one more connection nobody asked for is the last thing it needs."""
        with self._plock:
            if len(self._pool) >= self.warm:
                return
        if not self._gate.acquire(blocking=False):
            return
        try:
            tls = self.connect()
        except (OSError, ssl.SSLError) as e:
            log('WARN', f'could not open one ahead of time: {e}')
            return
        finally:
            self._gate.release()
        with self._plock:
            if len(self._pool) < self.warm:
                self._pool.append((tls, time.monotonic()))
                return
        try:
            tls.close()                     # raced with another fill
        except OSError:
            pass

    def prewarm(self):
        for _ in range(self.warm):
            threading.Thread(target=self._fill, daemon=True).start()

    def drain(self):
        with self._plock:
            pool, self._pool = list(self._pool), collections.deque()
        for tls, _ in pool:
            try:
                tls.close()
            except OSError:
                pass


#-------------------------------------------------------------------- probing

class InnerTLS:
    """A TLS session running on top of another one.

    Past a proxy's `200`, the outer connection is a plain byte pipe and the
    real request has to raise its own TLS inside it. Python will not wrap an
    SSLSocket in a second SSLSocket - it fails with UNEXPECTED_MESSAGE, which
    reads like a network fault and is not one - so the inner handshake is
    driven through memory buffers and the ciphertext carried by hand.

    The name here does go out as SNI, and should: it is the name of the site
    being asked for, and it travels inside the outer session where the line
    cannot read it.
    """

    def __init__(self, transport, host, timeout):
        self.t = transport
        self.t.settimeout(timeout)
        self.incoming, self.outgoing = ssl.MemoryBIO(), ssl.MemoryBIO()
        ctx = ssl.create_default_context()
        self.obj = ctx.wrap_bio(self.incoming, self.outgoing, server_hostname=host)
        while True:
            try:
                self.obj.do_handshake()
                self._flush()
                return
            except ssl.SSLWantReadError:
                self._flush()
                self._fill()

    def _flush(self):
        data = self.outgoing.read()
        if data:
            self.t.sendall(data)

    def _fill(self):
        chunk = self.t.recv(65536)
        if not chunk:
            self.incoming.write_eof()
        else:
            self.incoming.write(chunk)

    def sendall(self, data):
        self.obj.write(data)
        self._flush()

    def recv(self, size=65536):
        while True:
            try:
                return self.obj.read(size)
            except ssl.SSLWantReadError:
                self._flush()
                try:
                    self._fill()
                except OSError:
                    return b''
            except (ssl.SSLZeroReturnError, ssl.SSLSyscallError):
                return b''


def fetch(exit_, host, path='/', timeout=20, cap=65536):
    """One HTTPS request through the exit. Returns status, headers, the first
    of the body, and how long until that first byte arrived."""
    started = time.monotonic()
    outer = exit_.connect()
    try:
        outer.settimeout(timeout)
        outer.sendall(
            f'CONNECT {host}:443 HTTP/1.1\r\n'
            f'Host: {host}:443\r\n'
            f'Proxy-Authorization: Basic {exit_.auth}\r\n'
            f'\r\n'.encode())
        head = b''
        while b'\r\n\r\n' not in head:
            chunk = outer.recv(4096)
            if not chunk:
                raise OSError('the exit closed the connection on CONNECT')
            head += chunk
            if len(head) > 32768:
                raise OSError('the exit answered CONNECT with nothing usable')
        status_line = head.split(b'\r\n', 1)[0].decode('latin-1', 'replace')
        code = status_line.split(' ')[1] if ' ' in status_line else '?'
        # Most of the fleet answers 407 to credentials that other exits accept
        # in the same second, and goes on doing it however slowly you ask - so
        # it is not a wrong password and not a rate limit. It means this exit
        # does not run a proxy for this account, which is worth saying in
        # those words rather than as a status line.
        if code == '407':
            raise OSError('no proxy for this account')
        if not code.startswith('2'):
            raise OSError(f'CONNECT refused: {status_line.strip()}')

        inner = InnerTLS(outer, host, timeout)
        inner.sendall(
            f'GET {path} HTTP/1.1\r\n'
            f'Host: {host}\r\n'
            f'User-Agent: {UA_BROWSER}\r\n'
            f'Accept: text/html,application/xhtml+xml,*/*\r\n'
            f'Accept-Encoding: identity\r\n'
            f'Connection: close\r\n'
            f'\r\n'.encode())

        body = b''
        ttfb = None
        while len(body) < cap:
            chunk = inner.recv(16384)
            if not chunk:
                break
            if ttfb is None:
                ttfb = time.monotonic() - started
            body += chunk

        raw_head, _, rest = body.partition(b'\r\n\r\n')
        lines = raw_head.split(b'\r\n')
        first = lines[0].decode('latin-1', 'replace') if lines else ''
        status = first.split(' ')[1] if first.count(' ') >= 1 else '000'
        headers = {}
        for line in lines[1:]:
            k, _, v = line.decode('latin-1', 'replace').partition(':')
            headers[k.strip().lower()] = v.strip()
        return status, headers, rest, (ttfb if ttfb is not None else timeout)
    finally:
        try:
            outer.close()
        except OSError:
            pass


# The same words the tunnel sweep uses, decided the same way, so that a
# verdict means one thing across both. Cloudflare's block and challenge pages
# carry their own error numbers - 1020 is a WAF rule, 1015 rate limiting - and
# the interstitials say so in the title.
CHALLENGE_MARKERS = re.compile(
    rb'Just a moment|Attention Required|Checking your browser|cf-challenge|'
    rb'__cf_chl|Error 10(20|15|09)')


def verdict_of(status, headers, body):
    if headers.get('cf-mitigated') == 'challenge' or CHALLENGE_MARKERS.search(body):
        return 'challenged'
    if status == '403':
        return 'blocked'
    if status.isdigit() and 200 <= int(status) < 400:
        return 'ok'
    return f'http {status}'


#--------------------------------------------------------------------- sweep

def fmt_tenths(seconds):
    """The prefix the tunnel sweep puts on names in success/, so that sorting
    a folder by name sorts it by how quick the exit was."""
    tenths = int(seconds * 10 + 0.5)
    return f'{tenths // 10:02d}.{tenths % 10:01d}s'


def one_per_exit(names):
    """One address per exit name. The tunnel sweep needs narrowing because
    each config costs it half a minute; this one runs them at once, so this
    is a convenience rather than the difference between an afternoon and a
    minute."""
    seen, kept = set(), []
    for n in names:
        key = n.split('_')[0]
        if key not in seen:
            seen.add(key)
            kept.append(n)
    return kept


def can_connect(exit_, timeout):
    """Whether the exit runs a proxy that takes these credentials, and how
    long it took to say so. Stops at the `200` - nothing is fetched, so this
    answers only that one question, which is the question that separates the
    exits worth trying from the rest."""
    started = time.monotonic()
    outer = exit_.connect()
    try:
        outer.settimeout(timeout)
        outer.sendall(
            f'CONNECT www.cloudflare.com:443 HTTP/1.1\r\n'
            f'Host: www.cloudflare.com:443\r\n'
            f'Proxy-Authorization: Basic {exit_.auth}\r\n'
            f'\r\n'.encode())
        head = b''
        while b'\r\n\r\n' not in head:
            chunk = outer.recv(4096)
            if not chunk:
                raise OSError('the exit closed the connection on CONNECT')
            head += chunk
            if len(head) > 32768:
                raise OSError('the exit answered CONNECT with nothing usable')
        line = head.split(b'\r\n', 1)[0].decode('latin-1', 'replace')
        code = line.split(' ')[1] if ' ' in line else '?'
        if code == '407':
            raise OSError('no proxy for this account')
        if not code.startswith('2'):
            raise OSError(f'CONNECT refused: {line.strip()}')
        return time.monotonic() - started
    finally:
        try:
            outer.close()
        except OSError:
            pass


def sweep_one(path, auth, sites, timeout, connect_only=False, bind=None):
    name = os.path.basename(path)
    try:
        ip, host = read_config(path)
    except SystemExit:
        return {'name': name, 'verdict': 'not pinned', 'exit': '', 'ttfb': None, 'sites': {}}
    if not host:
        return {'name': name, 'verdict': 'no pin comment', 'exit': '', 'ttfb': None, 'sites': {}}

    exit_ = Exit(ip, 443, host, auth[0], auth[1], bind)
    row = {'name': name, 'ip': ip, 'exit': '', 'ttfb': None, 'sites': {}}

    if connect_only:
        try:
            row['ttfb'] = can_connect(exit_, timeout)
            row['verdict'] = 'ok'
        except (OSError, ssl.SSLError) as e:
            row['verdict'] = str(e).strip() or e.__class__.__name__
        return row

    try:
        status, headers, body, ttfb = fetch(exit_, 'www.cloudflare.com',
                                            '/cdn-cgi/trace', timeout)
    except (OSError, ssl.SSLError) as e:
        row['verdict'] = str(e).strip() or e.__class__.__name__
        return row

    row['ttfb'] = ttfb
    row['verdict'] = verdict_of(status, headers, body)
    seen_ip = re.search(rb'^ip=(\S+)', body, re.M)
    seen_loc = re.search(rb'^loc=(\S+)', body, re.M)
    if seen_ip:
        row['exit'] = seen_ip.group(1).decode()
        if seen_loc:
            row['exit'] += ' ' + seen_loc.group(1).decode()

    if row['verdict'] == 'ok':
        for site in sites:
            try:
                s, h, b, _ = fetch(exit_, site, '/', timeout)
                row['sites'][site] = verdict_of(s, h, b)
            except (OSError, ssl.SSLError) as e:
                row['sites'][site] = str(e).strip()[:40] or 'unreachable'
    return row


def do_sweep(folder, out_dir, sites, timeout, jobs, first, one_per,
             state_dir, auth_file, connect_only=False, bind=None):
    if not os.path.isdir(folder):
        die(f'no such folder: {folder}')
    names = sorted(f for f in os.listdir(folder) if f.endswith('.ovpn'))
    if one_per:
        names = one_per_exit(names)
    # A big sweep does not measure what it looks like it measures. The same
    # 17 configs, the same command, minutes apart, have come back 16/17 and
    # then 3/17 - the difference being only how much sweeping came before.
    # Whatever the mechanism at Surfshark's end, past a few dozen exits the
    # refusals stop being about the exits, so the answer to a 528-config sweep
    # is not a truer version of the answer to a 40-config one. It is a wrong
    # one that took longer.
    if first:
        names = names[:first]
    elif len(names) > SWEEP_CAP:
        print(f'\n  {len(names)} configs here, asking the first {SWEEP_CAP}.')
        print('  Sweeping much more than that stops measuring the exits and')
        print('  starts measuring how hard you have been sweeping - the same')
        print('  configs come back 16/17 rested and 3/17 after a long run.')
        print(f'  --first {len(names)} overrides this if you want it anyway.')
        names = names[:SWEEP_CAP]
    if not names:
        die(f'no .ovpn files in {folder}',
            'Point --dir somewhere else, or pin some configs first:  ovpn pin')

    auth = read_auth(auth_file)
    print(f'\n  {len(names)} configs from {folder}')
    if sites:
        print(f'  also asking each exit for: {", ".join(sites)}')
    print(f'  {jobs} at a time - nothing is connected, so they do not queue '
          f'behind each other\n', flush=True)

    rows = []
    done = 0
    with cf.ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = {pool.submit(sweep_one, os.path.join(folder, n),
                               auth, sites, timeout, connect_only, bind): n
                   for n in names}
        for fut in cf.as_completed(list(futures)):
            done += 1
            row = fut.result()
            rows.append(row)
            mark = ' ok ' if row['verdict'] == 'ok' else 'fail'
            bad = [s for s, v in row['sites'].items() if v != 'ok']
            if row['verdict'] == 'ok' and bad:
                mark = 'part'
            timing = f"{row['ttfb']:.2f}s" if row['ttfb'] else '     '
            detail = row['verdict'] if row['verdict'] != 'ok' else row['exit']
            if bad:
                detail += '  refused by ' + ','.join(bad)
            print(f'  [{mark}] {done:>4}/{len(names)}  {timing:>6}  '
                  f'{row["name"][:44]:<44}  {detail[:44]}', flush=True)

    keepers = [r for r in rows
               if r['verdict'] == 'ok'
               and all(v == 'ok' for v in r['sites'].values())]
    keepers.sort(key=lambda r: r['ttfb'])

    if not connect_only:
        os.makedirs(out_dir, exist_ok=True)
        for stale in os.listdir(out_dir):
            if stale.endswith('.ovpn'):
                os.remove(os.path.join(out_dir, stale))
        for r in keepers:
            target = os.path.join(out_dir, f"{fmt_tenths(r['ttfb'])}-{r['name']}")
            shutil.copyfile(os.path.join(folder, r['name']), target)

    os.makedirs(state_dir, exist_ok=True)
    tsv = os.path.join(state_dir,
                       'proxy-connect.tsv' if connect_only else 'proxy-exits.tsv')
    with open(tsv, 'w', encoding='utf-8', newline='') as f:
        f.write('config\taddress\tverdict\tttfb\texit\t' + '\t'.join(sites) + '\n')
        for r in sorted(rows, key=lambda r: (r['ttfb'] is None, r['ttfb'] or 0)):
            ttfb = f"{r['ttfb']:.3f}" if r['ttfb'] else ''
            f.write('\t'.join([r['name'], r.get('ip', ''), r['verdict'], ttfb,
                               r['exit']] + [r['sites'].get(s, '') for s in sites]) + '\n')

    if connect_only:
        print(f'\n  {len(keepers)} of {len(names)} would take the credentials '
              f'just now')
        print(f'  nothing was copied - this asked a smaller question than '
              f'{os.path.basename(out_dir)}/ is named for')
    else:
        print(f'\n  {len(keepers)} of {len(names)} served everything asked of them')
        print(f'  copied into {out_dir}, quickest first by name')
    print(f'  every result, including the failures, in {tsv}\n')
    if keepers:
        print('  the three quickest:')
        for r in keepers[:3]:
            print(f"      {fmt_tenths(r['ttfb'])}  {r['name']}  ->  {r['exit']}")
        # Naming one is a coin toss by the time you type it - which exits are
        # willing moves - so point at the form that finds a live one instead.
        print('\n  ovpn proxy\n')


#---------------------------------------------------------------- the meter

# How much has gone each way since this proxy came up, in bytes.
#
# Counted here rather than read off a network adapter, because this process
# is the only thing on the path that sees both directions and can tell them
# apart. An adapter counter would fold in every other program on the machine,
# and the exit's own numbers - if it published any - would be the sum of
# everyone using it.
#
# "Up" is what left this machine towards the exit and "down" is what came
# back. The credentials this proxy adds to every plain-HTTP request are
# counted up with the request they ride on: they did cross the wire, and the
# honest number here is what was actually sent, not what the browser handed
# us to send.
METER = {'up': 0, 'down': 0}

# A lock on a hot path is worth a word. It is taken once per 64KB chunk -
# a few hundred times a second on a fast line - and += on a dict value is
# a read and a write with a bytecode boundary in between, so two relay
# threads finishing at once really can lose a chunk. A meter that quietly
# undercounts is worse than one that costs a lock.
METER_LOCK = threading.Lock()


def meter(direction, n):
    if n:
        with METER_LOCK:
            METER[direction] += n


#--------------------------------------------------------------- the ledger

# The same bytes, split by where they went and who asked for them.
#
# METER above answers "how much"; this answers "what is using it". They are
# counted at the same moment and under the same lock, so the two can never
# disagree about a chunk - a ledger that summed to a different number than
# the meter beside it would be worse than no ledger.
#
# Keyed by destination and program together, not by one or the other. Grouped
# only by host, a browser and a backup client both talking to the same CDN
# become one line and the question "what is using my connection" has no
# answer. Grouped only by program, "where did the requests go" has none.
# Keeping the pair lets the window group it either way, which is what it does.


class Ledger:
    # Enough that nothing anyone actually runs hits it - a browser with fifty
    # tabs open touches a few hundred hosts in an hour - and small enough
    # that a machine talking to a different name every second cannot grow
    # this without limit. Over the line, the quietest and oldest rows go.
    LIMIT = 500

    def __init__(self):
        self.rows = {}

    def find(self, host, app, pid):
        """The row for this pair, opening one if it is new. Called under
        METER_LOCK by the caller, which is also what makes the counting in
        `Carried.moved` a single atomic step."""
        key = (host, app)
        row = self.rows.get(key)
        if row is None:
            if len(self.rows) >= self.LIMIT:
                self.prune()
            now = time.time()
            row = self.rows[key] = {'host': host, 'app': app, 'pid': pid,
                                    'up': 0, 'down': 0, 'hits': 0, 'live': 0,
                                    'first': now, 'last': now}
        return row

    def prune(self):
        """A quarter of the table, cheapest first: nothing open, least moved,
        longest ago. Dropping the single oldest row each time would spend the
        rest of the run doing this on every new host."""
        cold = sorted(self.rows.items(),
                      key=lambda kv: (kv[1]['live'] > 0,
                                      kv[1]['up'] + kv[1]['down'],
                                      kv[1]['last']))
        for key, _ in cold[:max(1, self.LIMIT // 4)]:
            del self.rows[key]

    def snapshot(self, top):
        """The busiest rows, newest reading of each, for whatever is drawing
        them. Copied under the lock - handing the live dicts out would let a
        writer mutate them halfway through being serialised."""
        with METER_LOCK:
            rows = [dict(r) for r in self.rows.values()]
            total = len(self.rows)
        rows.sort(key=lambda r: r['up'] + r['down'], reverse=True)
        return rows[:top], total


LEDGER = Ledger()


class Carried:
    """One client connection: who opened it, where it asked to go, and how
    much crossed it.

    A connection can change its mind about the destination - a kept-alive
    plain-HTTP connection carries requests to whatever host each one names -
    so the row is looked up again whenever the target changes rather than
    fixed when the connection was accepted.
    """

    __slots__ = ('app', 'pid', 'host', 'row')

    def __init__(self, app=None, pid=0):
        self.app, self.pid = app, pid
        self.host, self.row = None, None

    def going_to(self, target):
        """`example.com:443`, `[::1]:80`, or a bare host. The port is dropped:
        a list that says example.com twice because one request went to 80 and
        the next to 443 is a list nobody can read."""
        if not target:
            return
        host = target.rsplit(':', 1)[0] if ':' in target.rsplit(']', 1)[-1] \
            else target
        host = host.strip('[]').lower() or target.lower()
        if host == self.host:
            return
        self.leave()
        self.host = host
        with METER_LOCK:
            self.row = LEDGER.find(host, self.app, self.pid)
            self.row['hits'] += 1
            self.row['live'] += 1
            self.row['last'] = time.time()

    def moved(self, direction, n):
        """The one place bytes are counted. Both totals move together under
        one lock, so the ledger always adds up to the meter."""
        if not n:
            return
        with METER_LOCK:
            METER[direction] += n
            if self.row is not None:
                self.row[direction] += n
                self.row['last'] = time.time()

    def leave(self):
        """This connection is no longer on that host. `live` is what puts the
        dot beside a row in the window, and a count that is only ever
        incremented would light every row that had ever been used."""
        if self.row is None:
            return
        with METER_LOCK:
            self.row['live'] = max(0, self.row['live'] - 1)
            self.row['last'] = time.time()
        self.row = None


#------------------------------------------------------------ who asked for it

# Which program opened the connection, on Windows, by looking its source port
# up in the machine's own TCP table.
#
# Worth the ctypes because it is the difference between "something on this
# machine sent 400MB to a CDN" and "your backup client did". Nothing else the
# proxy can see says which program it is talking to: everything arrives from
# 127.0.0.1 and the only thing telling one client apart from another is the
# port it came from.
#
# Best effort, everywhere. A connection can be gone from the table before it
# is asked about, the process can be one this one is not allowed to open, and
# on anything but Windows there is no table here at all - all of which end as
# a row with no program against it rather than as a failure.

_OWNERS = {'at': 0.0, 'ports': {}}
_NAMES = {}

# One reader at a time. A browser opening thirty connections at once puts
# thirty threads through opened_by() in the same millisecond, and without
# this every one of them finds the table stale and goes off to read it -
# thirty passes over the machine's whole TCP table to answer one question.
_OWNERS_LOCK = threading.Lock()

# How stale the table may be. A connection accepted within this of the last
# read is looked up in what is already in hand; anything older reads it
# again. Short enough that a program starting up is named correctly, long
# enough that a burst of fifty connections reads the table once.
OWNERS_FRESH = 0.7


def _read_tcp_table():
    """Every IPv4 TCP endpoint on the machine and the pid that owns it."""
    import ctypes
    from ctypes import wintypes

    class ROW(ctypes.Structure):
        _fields_ = [('state', wintypes.DWORD), ('local_addr', wintypes.DWORD),
                    ('local_port', wintypes.DWORD), ('remote_addr', wintypes.DWORD),
                    ('remote_port', wintypes.DWORD), ('pid', wintypes.DWORD)]

    size = wintypes.DWORD(0)
    iphlpapi = ctypes.windll.iphlpapi
    # AF_INET, TCP_TABLE_OWNER_PID_ALL. Asked once for the size it wants and
    # once for the table, which is the documented shape of this call.
    iphlpapi.GetExtendedTcpTable(None, ctypes.byref(size), False, 2, 5, 0)
    buf = ctypes.create_string_buffer(size.value)
    if iphlpapi.GetExtendedTcpTable(buf, ctypes.byref(size), False, 2, 5, 0) != 0:
        return {}
    count = ctypes.cast(buf, ctypes.POINTER(wintypes.DWORD))[0]
    rows = ctypes.cast(ctypes.byref(buf, ctypes.sizeof(wintypes.DWORD)),
                       ctypes.POINTER(ROW))
    out = {}
    for i in range(count):
        r = rows[i]
        # The port sits in the low half in network order, which is the one
        # detail that makes this call look like it is returning nonsense.
        out[socket.ntohs(r.local_port & 0xFFFF)] = r.pid
    return out


def _process_name(pid):
    """chrome.exe, Telegram.exe. The full path is not wanted: it is long
    enough to push everything else off a 400px row and says nothing the
    filename does not."""
    if pid in _NAMES:
        return _NAMES[pid]
    name = None
    try:
        import ctypes
        from ctypes import wintypes
        # PROCESS_QUERY_LIMITED_INFORMATION: the one right that works against
        # a process running as somebody else without being an administrator.
        h = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
        if h:
            try:
                buf = ctypes.create_unicode_buffer(512)
                n = wintypes.DWORD(512)
                if ctypes.windll.kernel32.QueryFullProcessImageNameW(
                        h, 0, buf, ctypes.byref(n)):
                    name = os.path.basename(buf.value)
            finally:
                ctypes.windll.kernel32.CloseHandle(h)
    except Exception:
        name = None
    if len(_NAMES) > 512:
        _NAMES.clear()
    _NAMES[pid] = name
    return name


def opened_by(source_port):
    """(program, pid) for whatever holds that local port, or (None, 0)."""
    if os.name != 'nt' or not source_port:
        return None, 0
    pid = _OWNERS['ports'].get(source_port)
    if pid is None:
        with _OWNERS_LOCK:
            # Asked again inside the lock: whoever held it may have been
            # reading the table this call was about to read.
            pid = _OWNERS['ports'].get(source_port)
            if pid is None and time.time() - _OWNERS['at'] > OWNERS_FRESH:
                try:
                    _OWNERS['ports'] = _read_tcp_table()
                except Exception:
                    _OWNERS['ports'] = {}
                _OWNERS['at'] = time.time()
                pid = _OWNERS['ports'].get(source_port)
    if not pid:
        return None, 0
    return _process_name(pid), pid


def relay(a, b, note=None):
    """Bytes both ways until either end is done. Past the proxy's 200 this
    connection is opaque - the browser's own TLS to the site it asked for
    runs inside it, and neither we nor the exit can read that."""
    ends = [a, b]
    note = note or Carried()
    try:
        while True:
            # Anything OpenSSL has already decrypted is invisible to
            # select(): it lives in the SSL buffer, not the kernel queue. Ask
            # first, or a short read anywhere upstream of here strands bytes
            # until the next record happens to arrive - and when both ends
            # are waiting on each other, that is never.
            readable = [s for s in ends if getattr(s, 'pending', lambda: 0)()]
            if not readable:
                readable, _, failed = select.select(ends, [], ends, IDLE)
                if failed:
                    log('WARN', 'tunnel reported failed by select')
                    return
                if not readable:
                    log('WARN', f'tunnel idle {IDLE}s - closing')
                    return
            for s in readable:
                chunk = s.recv(65536)
                if not chunk:
                    return
                (b if s is a else a).sendall(chunk)
                # a is the client, b is the exit: read from a is on its way
                # out, read from b is on its way back.
                note.moved('up' if s is a else 'down', len(chunk))
    except (OSError, ssl.SSLError) as e:
        log('WARN', f'tunnel ended: {e.__class__.__name__}: {e}')
    finally:
        note.leave()
        for s in ends:
            try:
                s.close()
            except OSError:
                pass


HEAD_END = b'\r\n\r\n'

# Longer than any request head has business being, and the point at which a
# client that is never going to send a blank line is dropped rather than read
# forever.
MAX_HEAD = 65536

# How long a kept-alive connection may sit with nothing on it before both ends
# are let go. The same 300 seconds relay() waits with, and it has to be set by
# hand on both sockets here: the one to the exit comes back from
# create_connection with a 20-second timeout, which is the right budget for
# reaching a server and much too short a leash for a connection that has been
# opened and is waiting for something to happen on it. relay() never noticed
# because select() does not consult the socket timeout; reading straight from
# the socket does, and a client long-polling - which is exactly what a chat
# client does between messages - would have been cut off every 20 seconds.
IDLE = 300

# body_size's third answer, told apart from a real length and from the None
# that means chunked. A sentinel object rather than -1, so that a caller who
# forgets to check it fails loudly instead of slicing by a negative number.
CANNOT_TELL = object()


def read_head(sock, buf=b'', who='client'):
    """Everything up to the blank line, plus whatever was read past it.

    Returns (head, rest), or (None, rest) when the connection ended before a
    head arrived or the head grew past MAX_HEAD.

    All three of those failures used to leave by the same silent return, so a
    reset, a timeout and a client that simply finished were indistinguishable
    to the caller and invisible afterwards. They are still one return - the
    caller has nothing different to do about them - but each says which it
    was on the way out. `who` is what to call this end in the log.
    """
    while HEAD_END not in buf:
        try:
            chunk = sock.recv(4096)
        except (OSError, ssl.SSLError) as e:
            log('WARN', f'{who}: {e.__class__.__name__}: {e}')
            return None, buf
        if not chunk:
            # An empty buffer means it finished between requests, which is
            # how keep-alive is supposed to end. Anything else is a head cut
            # in half.
            if buf:
                log('WARN', f'{who}: ended part-way through a request head')
            return None, buf
        buf += chunk
        if len(buf) > MAX_HEAD:
            log('WARN', f'{who}: head grew past {MAX_HEAD} bytes')
            return None, buf
    head, _, rest = buf.partition(HEAD_END)
    return head, rest


def with_our_auth(head, exit_):
    """Whatever the client had in mind for credentials, ours are the ones the
    exit will accept. Right after the request line, where a proxy looks."""
    lines = [l for l in head.split(b'\r\n')
             if not l.lower().startswith(b'proxy-authorization:')]
    # After the request line, wherever that turns out to be. A client is
    # allowed to send a blank line before it, and inserting at 1 regardless
    # would put the credentials where the request line should be.
    at = next((i for i, l in enumerate(lines) if l.strip()), 0) + 1
    lines.insert(at, b'Proxy-Authorization: Basic ' + exit_.auth.encode())
    return b'\r\n'.join(lines) + HEAD_END


def body_size(head):
    """How many bytes of body follow this request head.

    None means chunked, which the caller forwards frame by frame. CANNOT_TELL
    means the framing does not parse and there is nothing safe to do but
    stop - two Content-Lengths that disagree are refused rather than guessed
    at, both because RFC 7230 says so and because guessing is what makes a
    proxy a request-smuggling primitive.
    """
    size, seen = 0, False
    for line in head.split(b'\r\n')[1:]:
        name, _, value = line.partition(b':')
        name, value = name.strip().lower(), value.strip().lower()
        if name == b'transfer-encoding' and value not in (b'', b'identity'):
            return None
        if name == b'content-length':
            try:
                got = int(value)
            except ValueError:
                return CANNOT_TELL
            if seen and got != size:
                return CANNOT_TELL
            size, seen = got, True
    return size


def head_host(head):
    """Where a plain-HTTP request is addressed.

    The request line first, because a request to a proxy carries the whole
    URL - `GET http://example.com/x HTTP/1.1` - and that is the one the exit
    will act on. The Host header second, for the rare client that sends a
    relative path anyway. Nothing is trusted from either beyond being written
    into a list: whatever a program puts in a header is that program's text,
    not ours.
    """
    try:
        line = head.split(b'\r\n', 1)[0].decode('latin-1', 'replace')
        target = line.split(' ')[1]
    except (IndexError, AttributeError):
        target = ''
    if '://' in target:
        rest = target.split('://', 1)[1]
        return rest.split('/', 1)[0]
    for raw in head.split(b'\r\n')[1:]:
        if raw[:5].lower() == b'host:':
            return raw[5:].decode('latin-1', 'replace').strip()
    return target.split('/', 1)[0] if target and not target.startswith('/') else ''


def pump_bytes(src, dst, direction=None, watch_for_407=False, note=None):
    """One direction, untouched, until the source is done.

    With watch_for_407 the bytes are still forwarded verbatim, but the status
    lines going past are looked at. A 407 in the response direction is the
    exit refusing us, not the client refusing anything - and a client pointed
    at a local proxy it was given no credentials for can only drop the
    connection and open another. It is the one failure that must never leave
    looking like "the connection just closed".
    """
    tail = b''
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                return
            dst.sendall(chunk)              # forward first, look afterwards
            if direction and note:
                note.moved(direction, len(chunk))
            if watch_for_407:
                tail = (tail + chunk)[-2048:]
                for line in tail.split(b'\r\n'):
                    if line.startswith(b'HTTP/1.') and b' 407 ' in line:
                        log('WARN', 'the exit answered 407 mid-connection')
                        tail = b''
                        break
    except (OSError, ssl.SSLError) as e:
        log('WARN', f'relay stopped: {e.__class__.__name__}: {e}')
    finally:
        # The other half of this connection is blocked on a read. Without
        # this it stays blocked with nothing left at the far end to answer it.
        #
        # SHUT_WR, never SHUT_RDWR. Shutting the read half of a socket the
        # other thread is still reading throws away the reply in flight; and
        # a peer that keeps sending into a half we have stopped acknowledging
        # gets the connection reset, which loses whatever was still queued to
        # go out. Closing our writing end says the same thing without either.
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def forward_chunked(client, upstream, pending, note):
    """A chunked body, frame by frame, stopping after the zero chunk.

    Nothing is rewritten - the frames go across exactly as they arrive. The
    only reason to read them at all is to know where the body ends, so that
    the next request head can be found and given credentials of its own.

    Returns whatever was read past the end of the body, or None if the
    framing ran out before the zero chunk did.
    """
    while True:
        while b'\r\n' not in pending:
            chunk = client.recv(65536)
            if not chunk:
                return None
            pending += chunk
        line, _, pending = pending.partition(b'\r\n')
        upstream.sendall(line + b'\r\n')
        note.moved('up', len(line) + 2)
        try:
            n = int(line.split(b';')[0].strip(), 16)
        except ValueError:
            log('WARN', f'chunk length {line[:32]!r} is not a number')
            return None
        need = n + 2                        # the chunk, and then its CRLF
        while need:
            if not pending:
                pending = client.recv(65536)
                if not pending:
                    return None
            take, pending = pending[:need], pending[need:]
            upstream.sendall(take)
            note.moved('up', len(take))
            need -= len(take)
        if n == 0:
            return pending                  # trailers, then the next head


def pump_requests(client, upstream, exit_, head, pending, note):
    """Every request this connection carries, each one with our credentials -
    not just the first.

    This is the difference between a browser and a chat client, and it is why
    Telegram dropped and reconnected about once a second while a browser on
    the same proxy was fine. A browser asking for HTTPS sends one CONNECT and
    everything after it is an opaque tunnel, so the credentials go out once
    and the question never comes up again. Something speaking plain HTTP down
    a kept-alive connection asks again and again - and an exit that
    authenticates every request, which is every real proxy, answers 407 to
    the second one. The client's only move then is to drop the connection and
    open another, which buys it exactly one more request.

    Returns when the client is done or the framing runs out; the caller closes
    both ends.
    """
    while True:
        # Each request names its own host on a kept-alive connection, so
        # where this is going is asked again rather than remembered.
        note.going_to(head_host(head))
        size = body_size(head)
        if size is CANNOT_TELL:
            log('WARN', 'refusing a request whose framing does not parse')
            return

        if size is None:
            # Chunked. This used to hand the rest of the connection over as a
            # plain pipe, which is right for this request and wrong for every
            # one after it: none of those gets a Proxy-Authorization again,
            # the exit answers 407 to the next, and the connection is back to
            # the behaviour the per-request auth above exists to prevent.
            # Following the frames costs a few lines and keeps the loop.
            authed = with_our_auth(head, exit_)
            upstream.sendall(authed)
            note.moved('up', len(authed))
            pending = forward_chunked(client, upstream, pending, note)
            if pending is None:
                return
        else:
            body, pending = pending[:size], pending[size:]
            # One write, so one TLS record and one segment. A chat client's
            # messages are a few hundred bytes each, and sending the head and
            # the body separately doubled the packets on the hot path for no
            # gain - and handed the line's DPI two small flights to look at
            # where there could have been one.
            out = with_our_auth(head, exit_) + body
            upstream.sendall(out)
            note.moved('up', len(out))
            left = size - len(body)
            while left > 0:
                chunk = client.recv(min(65536, left))
                if not chunk:
                    return
                upstream.sendall(chunk)
                note.moved('up', len(chunk))
                left -= len(chunk)

        head, pending = read_head(client, pending)
        if head is None:
            return


def recv_exactly(sock, n):
    buf = b''
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise OSError('the client went away part-way through SOCKS5')
        buf += chunk
    return buf


def socks_target(client):
    """The SOCKS5 greeting and request, as far as the address being asked for.

    No authentication is offered or wanted: this listens on the loopback, and
    a password between a program and a proxy on the same machine protects
    nothing that the machine itself does not already.

    A name stays a name. Whatever the client sends goes into the CONNECT
    untouched and the exit resolves it - which is what the socks5h form of
    the scheme means, and is the only behaviour on offer here. Nothing is
    ever resolved locally, which is the whole point on a line whose resolver
    lies.
    """
    ver, nmethods = struct.unpack('!BB', recv_exactly(client, 2))
    if ver != 5:
        raise OSError(f'not SOCKS5 (version byte {ver:#04x})')
    recv_exactly(client, nmethods)
    client.sendall(b'\x05\x00')

    ver, cmd, _, atyp = struct.unpack('!BBBB', recv_exactly(client, 4))
    if ver != 5:
        raise OSError(f'bad request version {ver:#04x}')
    if cmd != 1:
        # BIND and UDP ASSOCIATE cannot be carried over a CONNECT, and
        # nothing that reaches this proxy asks for them.
        socks_refuse(client, 7)
        raise OSError(f'only CONNECT is supported, got command {cmd}')

    if atyp == 1:
        host = socket.inet_ntoa(recv_exactly(client, 4))
    elif atyp == 3:
        n = recv_exactly(client, 1)[0]
        host = recv_exactly(client, n).decode('idna')
    elif atyp == 4:
        host = '[' + socket.inet_ntop(socket.AF_INET6,
                                      recv_exactly(client, 16)) + ']'
    else:
        socks_refuse(client, 8)
        raise OSError(f'address type {atyp} is not supported')
    port = struct.unpack('!H', recv_exactly(client, 2))[0]
    return host, port


def socks_refuse(client, code=5):
    """A SOCKS5 failure reply. 5 is "connection refused", which is the
    honest answer for every way the exit can let us down."""
    try:
        client.sendall(bytes([5, code, 0, 1]) + b'\x00' * 6)
    except OSError:
        pass


def shut(*socks):
    for s in socks:
        try:
            s.close()
        except OSError:
            pass


def open_tunnel(exit_, target, head=None):
    """A tunnel to `target` through the exit, from the pool where one is warm.

    Returns (upstream, reply, spare). One retry, and only for a connection
    that came out of the pool: it may have died while it sat there, nothing
    has reached the client yet, and a CONNECT that was never answered left no
    state behind to confuse. That is the same idempotent-retry rule keep-alive
    has always relied on.
    """
    if head is None:
        head = (f'CONNECT {target} HTTP/1.1\r\nHost: {target}\r\n'
                f'Proxy-Authorization: Basic {exit_.auth}\r\n\r\n').encode()

    upstream, warm = exit_.take()
    for attempt in (1, 2):
        try:
            upstream.sendall(head)
            reply, spare = read_head(upstream, who='exit')
            if reply is None:
                raise OSError('the exit closed the connection on CONNECT')
            return upstream, reply, spare
        except (OSError, ssl.SSLError) as e:
            shut(upstream)
            if attempt == 2 or not warm:
                raise
            log('WARN', f'a connection held ready had died ({e}) - dialling')
            upstream, warm = exit_._dial(), False


def serve_socks(client, exit_, quiet, note):
    """SOCKS5 in, CONNECT out.

    Here because of what Telegram does with the alternative. tdesktop sets
    useTcp = (proxyType != Type::Http), so choosing HTTP in its settings
    turns the TCP transport off altogether and every MTProto packet becomes
    its own POST to port 80 in the clear. Choosing SOCKS5 leaves MTProto over
    TCP intact, which is one long-lived connection instead of a request per
    message - and the exit still only ever sees a CONNECT.
    """
    try:
        host, port = socks_target(client)
    except (OSError, ValueError, UnicodeError) as e:
        log('FAIL', f'socks5 handshake: {e}')
        if not quiet:
            print(f'  socks5   {e}', file=sys.stderr, flush=True)
        client.close()
        return

    target = f'{host}:{port}'
    note.going_to(target)
    try:
        upstream, reply, spare = open_tunnel(exit_, target)
    except (OSError, ssl.SSLError) as e:
        log('FAIL', f'socks5 {target}: {e}')
        if not quiet:
            print(f'  socks5   {target}  {e}', file=sys.stderr, flush=True)
        socks_refuse(client)
        client.close()
        return

    said = reply.split(b'\r\n', 1)[0].decode('latin-1', 'replace')
    if not (said.split(' ') + [''])[1].startswith('2'):
        log('FAIL', f'socks5 {target}: the exit said {said}')
        if not quiet:
            print(f'  socks5   {target}  the exit said {said}',
                  file=sys.stderr, flush=True)
        socks_refuse(client)
        shut(client, upstream)
        return

    # The bound address in the reply is a formality - nothing reads it.
    client.sendall(b'\x05\x00\x00\x01' + b'\x00' * 6)
    if spare:
        # Anything the exit pipelined behind its 200 is tunnel content.
        client.sendall(spare)
    relay(client, upstream, note)


def serve_http(client, exit_, quiet, first, note):
    head, rest = read_head(client, first)
    if head is None:
        client.close()
        return
    verb = head.split(b'\r\n', 1)[0].decode('latin-1', 'replace').split(' ')[0].upper()

    if verb == 'CONNECT':
        target = head.split(b'\r\n', 1)[0].decode('latin-1', 'replace').split(' ')[1]
        note.going_to(target)
        # The exit's answer is read here rather than relayed blind, because
        # anything the client pipelined behind its CONNECT belongs inside the
        # tunnel and not to the proxy. Sent before the 200 arrives, a refusal
        # would leave those bytes in front of the exit's parser to be read as
        # a second request.
        try:
            upstream, reply, spare = open_tunnel(
                exit_, target, with_our_auth(head, exit_))
        except (OSError, ssl.SSLError) as e:
            log('FAIL', f'CONNECT {target}: {e}')
            if not quiet:
                print(f'  CONNECT  {e}', file=sys.stderr, flush=True)
            try:
                client.sendall(b'HTTP/1.1 502 Bad Gateway\r\n'
                               b'Connection: close\r\n'
                               b'Content-Length: 0\r\n\r\n')
            except OSError:
                pass
            client.close()
            return
        try:
            client.sendall(reply + HEAD_END + spare)
            said = reply.split(b'\r\n', 1)[0].decode('latin-1', 'replace')
            if not (said.split(' ') + [''])[1].startswith('2'):
                log('FAIL', f'CONNECT {target}: the exit said {said}')
                if not quiet:
                    print(f'  CONNECT  the exit said {said}',
                          file=sys.stderr, flush=True)
                shut(client, upstream)
                return
            if rest:
                upstream.sendall(rest)
                note.moved('up', len(rest))
        except (OSError, ssl.SSLError) as e:
            log('FAIL', f'CONNECT {target}: {e}')
            if not quiet:
                print(f'  CONNECT  {e}', file=sys.stderr, flush=True)
            shut(client, upstream)
            return
        relay(client, upstream, note)
        return

    try:
        upstream, _ = exit_.take()
    except (OSError, ssl.SSLError) as e:
        log('FAIL', f'{verb}: {e}')
        if not quiet:
            print(f'  {verb:<8} {e}', file=sys.stderr, flush=True)
        try:
            client.sendall(b'HTTP/1.1 502 Bad Gateway\r\n'
                           b'Connection: close\r\n'
                           b'Content-Length: 0\r\n\r\n')
        except OSError:
            pass
        client.close()
        return

    for sock in (client, upstream):
        try:
            sock.settimeout(IDLE)
        except OSError:
            pass

    back = threading.Thread(target=pump_bytes,
                            args=(upstream, client, 'down', True, note),
                            daemon=True)
    back.start()
    try:
        pump_requests(client, upstream, exit_, head, rest, note)
    except (OSError, ssl.SSLError) as e:
        log('WARN', f'{verb}: {e.__class__.__name__}: {e}')
        if not quiet:
            print(f'  {verb:<8} {e}', file=sys.stderr, flush=True)

    # The client has stopped sending. The exit may still owe a reply, and
    # MTProto's HTTP transport parks a poll for up to 25 seconds - so wait on
    # the response side itself rather than on a stopwatch. This was two
    # seconds, which is shorter than a long poll is allowed to be and threw
    # away the answer whenever one was outstanding. The socket timeouts set
    # above already bound how long the wait can be.
    try:
        client.shutdown(socket.SHUT_WR)
    except OSError:
        pass
    back.join(IDLE)
    note.leave()
    shut(client, upstream)


class Pushback:
    """A socket with one byte put back in front of it.

    Reading one byte is how the protocol is decided, and whichever parser
    wins then wants that byte. MSG_PEEK would avoid the wrapper but is not
    allowed on every socket this may be handed, so the byte is simply
    returned by the first read and the wrapper is transparent after that.
    """

    def __init__(self, sock, head):
        self._sock, self._head = sock, head

    def recv(self, n, *a, **kw):
        if self._head:
            out, self._head = self._head[:n], self._head[n:]
            return out
        return self._sock.recv(n, *a, **kw)

    def __getattr__(self, name):
        return getattr(self._sock, name)


def serve_one(client, exit_, quiet, source_port=0):
    # Asked once, here, and never again for this connection. The machine's
    # TCP table still has the row while the connection is being set up; by
    # the time it closes the row may be gone, and a program that is named at
    # the end of a download is named too late to have been worth asking.
    note = Carried(*opened_by(source_port))
    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    # Before the first read, not after it. This used to be set only on the
    # plain-HTTP path, below the branch that returns, so a client that
    # connected and then said nothing held a thread for as long as the proxy
    # ran - not for IDLE, but forever.
    try:
        client.settimeout(IDLE)
    except OSError:
        pass

    # One byte decides which protocol this is. SOCKS5 opens with its version,
    # 0x05; every HTTP verb opens with a letter. So both fit on one port and
    # a client can be pointed at either without anything being reconfigured.
    try:
        first = client.recv(1)
    except (OSError, ssl.SSLError) as e:
        log('WARN', f'client: {e.__class__.__name__}: {e}')
        client.close()
        return
    if not first:
        client.close()
        return

    try:
        if first[0] == 5:
            serve_socks(Pushback(client, first), exit_, quiet, note)
        else:
            serve_http(client, exit_, quiet, first, note)
    finally:
        # Whatever happened, this connection is no longer on that host. Left
        # undone by any path out of here, the row keeps its dot for the rest
        # of the run and the window shows a connection that closed hours ago
        # as live.
        note.leave()


#--------------------------------------------------------------- what is up

# One line of plain text per proxy rather than a pid file and a lock: this
# changes only when a proxy starts or stops, and being able to read it with
# cat is worth more here than being able to parse it.
#
# Several lines, because several at once is the ordinary case rather than an
# edge: one exit a browser points at and you change whenever you like, and a
# second on its own port that a terminal and a chat client have settled on
# and that nothing should disturb. Keyed by port, since a port is what a
# client is actually pointed at - the pid is bookkeeping, the port is the
# address someone typed into a settings box.
STATE_PATH = os.path.join(ROOT, '.state', 'proxy.state')


def read_states():
    """Every proxy the file claims, minus the ones no longer there.

    A record left behind by something that was killed is worse than no
    record, so each is checked against the process table rather than
    trusted. Sorted by port, so two readings agree on the order.
    """
    out = []
    try:
        with open(STATE_PATH, encoding='utf-8') as f:
            lines = f.read().splitlines()
    except OSError:
        return out
    for line in lines:
        parts = line.split('\t')
        if len(parts) < 6 or not parts[0].isdigit() or not alive(int(parts[0])):
            continue
        out.append({'pid': int(parts[0]), 'host': parts[1], 'port': parts[2],
                    'ip': parts[3], 'name': parts[4], 'since': parts[5]})
    out.sort(key=lambda r: int(r['port']) if r['port'].isdigit() else 0)
    return out


def read_state(port=None):
    """The proxy on a given port - or, with no port named, the only one
    running. Deliberately nothing when there are several: every caller of
    this wants a proxy it can name, and picking one of two for them is how
    you end up stopping the wrong one."""
    live = read_states()
    if port is not None:
        return next((r for r in live if str(r['port']) == str(port)), None)
    return live[0] if len(live) == 1 else None


def put_states(records):
    """Write the file whole, to one side and then moved into place, so that
    a reader never catches it half-written. Two proxies starting in the same
    second is unlikely; a truncated file would outlast the second."""
    try:
        os.makedirs(os.path.dirname(STATE_PATH), exist_ok=True)
        if not records:
            try:
                os.remove(STATE_PATH)
            except OSError:
                pass
            return
        tmp = f'{STATE_PATH}.{os.getpid()}'
        with open(tmp, 'w', encoding='utf-8') as f:
            for r in records:
                f.write('\t'.join([str(r['pid']), r['host'], str(r['port']),
                                   r['ip'], r['name'], r['since']]) + '\n')
        os.replace(tmp, STATE_PATH)
    except OSError as e:
        warn('could not record what is running', f'{STATE_PATH}: {e}')


def write_state(listen_host, listen_port, exit_):
    keep = [r for r in read_states() if str(r['port']) != str(listen_port)]
    keep.append({'pid': os.getpid(), 'host': listen_host,
                 'port': str(listen_port), 'ip': exit_.ip, 'name': exit_.host,
                 'since': time.strftime('%Y-%m-%d %H:%M:%S')})
    put_states(keep)


def clear_state(port=None):
    """Take out one line and leave the rest. A proxy shutting down used to
    delete the whole file, which with a second one running meant the
    survivor disappeared from `status` while it was still serving."""
    me = os.getpid()
    put_states([r for r in read_states()
                if r['pid'] != me
                and (port is None or str(r['port']) != str(port))])


# -- and how much has gone through it -----------------------------------

# Beside the state file rather than in it. The state file changes twice in a
# proxy's life, when it starts and when it stops, and is read by a person
# with `cat`; this changes every second and is read by a program. Putting a
# number that moves into a file that does not would mean rewriting every
# proxy's line once a second to keep one of them current.
#
# One file per port, keyed the same way as the state lines, so two proxies
# running at once each have their own and neither has to parse past the
# other's.


def traffic_path(port):
    return os.path.join(os.path.dirname(STATE_PATH), f'traffic-{port}.json')


def read_traffic(port):
    """What the proxy on that port has moved, or nothing if it is not saying.

    The reading carries the time it was taken, deliberately. A proxy that has
    been killed leaves its last file behind, and a caller that trusts it
    blindly paints a number that has stopped moving as if it were live - so
    the caller is given `at` and is expected to decide how old is too old.
    """
    try:
        with open(traffic_path(port), encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def write_traffic(port, up, down, up_bps, down_bps, since):
    """To one side and then moved into place, like the state file - a reader
    that catches this half-written gets a JSON error rather than a number."""
    path = traffic_path(port)
    tmp = f'{path}.{os.getpid()}'
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump({'pid': os.getpid(), 'port': int(port),
                       'up': up, 'down': down,
                       'up_bps': round(up_bps, 1),
                       'down_bps': round(down_bps, 1),
                       'since': since, 'at': time.time()}, f)
        os.replace(tmp, path)
    except (OSError, ValueError):
        try:
            os.remove(tmp)
        except OSError:
            pass


def clear_traffic(port):
    try:
        os.remove(traffic_path(port))
    except OSError:
        pass


# How many rows the file carries. The window shows a list somebody scrolls,
# not a database - past a hundred lines nobody is reading, they are searching,
# and the busiest hundred is what a search would have found anyway.
LEDGER_ROWS = 100

# Rewritten every other second rather than every second. It is twenty times
# the size of the meter file and it is only read while a window has the log
# open, so half the writes for the same answer.
LEDGER_EVERY = 2.0


def hosts_path(port):
    return os.path.join(os.path.dirname(STATE_PATH), f'hosts-{port}.json')


def read_hosts(port):
    try:
        with open(hosts_path(port), encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def write_hosts(port, rows, total, since):
    path = hosts_path(port)
    tmp = f'{path}.{os.getpid()}'
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump({'pid': os.getpid(), 'port': int(port), 'rows': rows,
                       'total': total, 'since': since, 'at': time.time()},
                      f, ensure_ascii=False)
        os.replace(tmp, path)
    except (OSError, ValueError):
        try:
            os.remove(tmp)
        except OSError:
            pass


def clear_hosts(port):
    try:
        os.remove(hosts_path(port))
    except OSError:
        pass


def meter_writer(port, stop):
    """A reading a second, on disk, for whatever wants to draw it.

    A file rather than a socket or a pipe, because the reader is a separate
    process that comes and goes. The window can be closed and reopened while
    this carries on serving, and when it comes back it should find the
    numbers where it left them rather than have to arrange a handshake for
    them - and `--detach` means there is often no parent left to pipe to at
    all.

    Both the running totals and the rate over the last second are written.
    The rate could be worked out by a reader differencing two readings, but
    only if it reads on a reliable clock; this thread already has one, and a
    window that polls when it feels like it should not have to own the
    arithmetic that decides whether the line looks smooth.
    """
    since = time.time()
    was_up = was_down = 0
    was_at = since
    listed = 0.0
    write_traffic(port, 0, 0, 0, 0, since)
    write_hosts(port, [], 0, since)
    while not stop.wait(1.0):
        now = time.time()
        with METER_LOCK:
            up, down = METER['up'], METER['down']
        span = max(now - was_at, 1e-3)
        write_traffic(port, up, down,
                      (up - was_up) / span, (down - was_down) / span, since)
        was_up, was_down, was_at = up, down, now
        if now - listed >= LEDGER_EVERY:
            listed = now
            rows, total = LEDGER.snapshot(LEDGER_ROWS)
            write_hosts(port, rows, total, since)


def alive(pid):
    if not pid:
        return False
    if os.name == 'nt':
        try:
            import ctypes
            h = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
            if h:
                ctypes.windll.kernel32.CloseHandle(h)
                return True
            return False
        except Exception:
            return False
    try:
        os.kill(pid, 0)
        return True
    except (OSError, ProcessLookupError):
        return False


def strays():
    """Any copy of this script running that no record accounts for.

    Recorded ones are left out deliberately: they are not strays, they are
    the proxies `status` already lists by port. Including them made the
    "cannot listen" message name a proxy that was serving a different port
    perfectly well, under a sentence saying these were unrecorded.

    The state file only knows about proxies this version started. One left
    over from an older copy, or from a shell that was closed, holds its port
    just as firmly and appears in no record - and then `stop` says there is
    nothing to stop while `connect` says the address is in use, which is a
    maddening pair of answers to get. So: ask the process table instead.

    Matched on the shape of the command line, not on the filename appearing
    anywhere in it: a shell running a script that merely mentions this file -
    a grep, an editor, the very command that asked the question - would
    otherwise be reported as a proxy, and `stop` would then kill it.

    Returns (pid, command line) pairs, never raising - not being able to look
    is a reason to say less, not to fail.
    """

    def is_ours(argv):
        if len(argv) < 2:
            return False
        if not os.path.basename(argv[0]).lower().startswith('python'):
            return False
        return any(a.replace('\\', '/').endswith('/ovpn-proxy.py')
                   or a == 'ovpn-proxy.py' for a in argv[1:])

    # Our own parent is excluded along with ourselves. The only thing that
    # ever starts one of these from another is --detach, and that parent
    # never binds anything - so listing it under "cannot listen" would name
    # an innocent process as the suspect, and the obvious next move would be
    # to stop a working proxy to free a port it was never holding. Any other
    # parent is a shell, which does not match is_ours anyway.
    me, parent = os.getpid(), os.getppid()
    known = {r['pid'] for r in read_states()}
    found = []
    if os.name == 'nt':
        try:
            import subprocess
            out = subprocess.run(
                ['powershell', '-NoProfile', '-Command',
                 "Get-CimInstance Win32_Process | Where-Object "
                 "{ $_.CommandLine -like '*ovpn-proxy.py*' } | "
                 "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }"],
                capture_output=True, text=True, timeout=15)
            for line in out.stdout.splitlines():
                pid, _, cmd = line.partition('\t')
                cmd = cmd.strip()
                if not (pid.strip().isdigit()
                        and int(pid) not in known
                        and int(pid) not in (me, parent)):
                    continue
                # Windows hands back one string; splitting on spaces is
                # crude but the pieces we test never contain any.
                if is_ours(cmd.replace('"', '').split()):
                    found.append((int(pid), cmd))
        except Exception:
            pass
        return found

    try:
        for entry in os.listdir('/proc'):
            if (not entry.isdigit() or int(entry) in (me, parent)
                    or int(entry) in known):
                continue
            try:
                with open(f'/proc/{entry}/cmdline', 'rb') as f:
                    raw = f.read()
            except OSError:
                continue
            argv = [a.decode('utf-8', 'replace')
                    for a in raw.split(b'\0') if a]
            if is_ours(argv):
                found.append((int(entry), ' '.join(argv)))
    except OSError:
        pass
    return found


def do_status():
    live = read_states()
    head('Proxy')
    if not live:
        loose = strays()
        if loose:
            warn(f'no record of a proxy, but {len(loose)} copy of this is '
                 f'running' if len(loose) == 1 else
                 f'no record of a proxy, but {len(loose)} copies of this are '
                 f'running')
            for pid, cmd in loose:
                note(f'    pid {pid}  {cmd[:90]}')
            note('Started by an older copy, or by a shell since closed.')
            note('    ovpn proxy stop   will end them')
            print()
            return 0
        note('nothing running.')
        note('    ovpn proxy connect          pick a live exit and serve it')
        note('    ovpn proxy connect uk-lon   or name one')
        print()
        return 0
    for st in live:
        ok(f'{C["bold"]}http://{st["host"]}:{st["port"]}{C["off"]}')
        field('exit', f'{st["ip"]}   {st["name"]}')
        field('pid', st['pid'])
        field('since', st['since'])
        print()
    # Asked for even when there are records, now that strays() reports only
    # what no record accounts for. It used to be consulted only when there
    # were none at all, so a forgotten copy sat there holding a port while
    # `status` listed the others and never mentioned it - which is the pair
    # of answers this was supposed to have put an end to.
    loose = strays()
    if loose:
        warn(f'and {len(loose)} copy running that no record accounts for'
             if len(loose) == 1 else
             f'and {len(loose)} copies running that no record accounts for')
        for pid, cmd in loose:
            note(f'    pid {pid}  {cmd[:90]}')
        note('Started by an older copy, or by a shell since closed.')
        note('    ovpn proxy stop --all   ends those too')
        print()
    if len(live) == 1 and not loose:
        note('ovpn proxy stop   ends it')
    else:
        note('ovpn proxy stop --port N   ends that one')
        note('ovpn proxy stop --all      ends all of them')
    print()
    return 0


def kill(pid):
    if os.name == 'nt':
        import subprocess
        subprocess.run(['taskkill', '/PID', str(pid), '/F'],
                       capture_output=True, check=True)
    else:
        import signal
        os.kill(pid, signal.SIGTERM)


def do_stop(port=None, every=False):
    live = read_states()
    head('Proxy')

    if port is not None:
        wanted = [r for r in live if str(r['port']) == str(port)]
        if not wanted:
            note(f'nothing of ours on port {port} - nothing to stop.')
            for r in live:
                note(f'    port {r["port"]}  {r["name"]}  pid {r["pid"]}')
            print()
            return 0
        live = wanted

    if len(live) > 1 and not every:
        # Refuse rather than guess. The browser's proxy and the one a chat
        # client has been sitting on for a week are one keystroke apart, and
        # only one of the two is easy to notice the loss of.
        warn(f'{len(live)} proxies are running - say which one')
        for r in live:
            note(f'    port {r["port"]}  {r["name"]}  pid {r["pid"]}  '
                 f'since {r["since"]}')
        note('    ovpn proxy stop --port N   ends that one')
        note('    ovpn proxy stop --all      ends all of them')
        print()
        return 1

    if not live:
        # No record does not mean nothing is there. Look for ourselves before
        # claiming otherwise, or `stop` and `connect` end up contradicting
        # each other over the same port.
        loose = strays()
        if not loose:
            note('nothing running - nothing to stop.')
            print()
            return 0
        warn(f'no record of a proxy, but {len(loose)} other copy of this is '
             f'running' if len(loose) == 1 else
             f'no record of a proxy, but {len(loose)} other copies of this '
             f'are running')
        stopped = 0
        for pid, cmd in loose:
            try:
                kill(pid)
                stopped += 1
                ok(f'stopped pid {pid}   {cmd[:74]}')
            except Exception as e:
                warn(f'could not stop pid {pid}', str(e))
        clear_state()
        print()
        return 0 if stopped else 1

    # Read before anything is killed. Afterwards the ones just stopped would
    # be mid-death and briefly indistinguishable from a stray.
    loose = strays() if (every and port is None) else []

    # One that will not die is not a reason to leave the others running, so
    # this reports and carries on rather than stopping at the first failure.
    stopped = 0
    for st in live:
        try:
            kill(st['pid'])
        except Exception as e:
            warn(f'could not stop pid {st["pid"]}', f'{e}\n'
                 'It may have gone already. Clear the record by hand if it '
                 f'sticks:\n    rm {STATE_PATH}')
            continue
        clear_state(st['port'])
        stopped += 1
        ok(f'stopped - {st["name"]} on port {st["port"]} (pid {st["pid"]})')

    # --all has to mean all, or a forgotten copy survives the one command
    # whose whole promise is that nothing is left behind.
    for pid, cmd in loose:
        try:
            kill(pid)
        except Exception as e:
            warn(f'could not stop pid {pid}', str(e))
            continue
        stopped += 1
        ok(f'stopped pid {pid} - no record of it   {cmd[:56]}')

    print()
    return 0 if stopped else 1


#------------------------------------------------------- pointing a terminal

# The lower-case pair is what curl, git, pip and npm read. The upper-case
# pair is for the ones that only read those - curl deliberately ignores an
# upper-case HTTP_PROXY, because a CGI script's environment can be poisoned
# through it, so the lower-case one does the work and the other is manners.
#
# no_proxy earns its place: without it a request to something listening on
# this machine gets sent abroad and back, if it arrives at all.
PROXY_VARS = ('http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY',
              'no_proxy', 'NO_PROXY', 'OVPN_PROXY')
NEVER_PROXY = ('localhost', '127.0.0.1', '::1')

# What may appear in a no_proxy entry. Theirs is merged with ours rather than
# replaced, and theirs is not ours to trust: it is about to be pasted into a
# shell command. Anything with a character that could end the quoting is
# dropped rather than escaped, because no legitimate entry needs one.
SAFE_HOST = re.compile(r'^[A-Za-z0-9_.:*/\[\]-]+$')


def env_lines(rec, powershell=False):
    """The exports, in the dialect of whichever shell is going to eval them.

    Two dialects rather than one, because the alternative on Windows was
    printing `export` lines at a PowerShell prompt, which are not wrong so
    much as meaningless there.
    """
    url = f'http://{rec["host"]}:{rec["port"]}'
    skip = []
    for part in re.split(r'[,\s]+', os.environ.get('no_proxy', '')):
        if part and SAFE_HOST.match(part) and part not in skip:
            skip.append(part)
    for part in NEVER_PROXY:
        if part not in skip:
            skip.append(part)
    skip = ','.join(skip)

    # A marker on the end, so that `px status` can tell a proxy this set
    # from one that was already in the environment when the shell started.
    values = [(v, url) for v in
              ('http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY')]
    values += [('no_proxy', skip), ('NO_PROXY', skip),
               ('OVPN_PROXY', f'{rec["host"]}:{rec["port"]}')]

    if powershell:
        return [f"$env:{v} = '{val}'" for v, val in values]
    return [f"export {v}='{val}'" for v, val in values]


def env_port_in(url):
    """The port out of an http_proxy value, or None if it is not one of ours
    to reason about."""
    m = re.search(r':(\d+)/?$', url or '')
    return m.group(1) if m else None


def do_env(port=None, off=False, show=False, powershell=None):
    """Shell lines that point a terminal at a running proxy.

    Printed rather than applied. A child process cannot change its parent's
    environment, which is the whole reason this is a subcommand here and a
    function in the shell rather than one thing: this says what to do, the
    shell does it.

    So stdout carries the exports and nothing else, ever, and every word
    meant for a person goes to stderr - `eval` of the output has to get
    either the exports or nothing at all. A sentence in among them would be
    run as a command.
    """
    # PowerShell unless told otherwise on Windows, sh unless told otherwise
    # anywhere else. Git Bash on Windows is the case that needs --sh.
    ps = powershell if powershell is not None else (os.name == 'nt')

    if off:
        for v in PROXY_VARS:
            print(f'Remove-Item -ErrorAction SilentlyContinue Env:\\{v}'
                  if ps else f'unset {v}')
        head('Terminal')
        ok('direct again - no proxy set for this shell')
        note(f'    {"px" if HAS_PX else "the env line above"}'
             '      to send it back through one')
        print(file=OUT)
        return 0

    if show:
        return do_env_show()

    live = read_states()
    if port is None:
        # Set once in the rc file by someone who keeps a proxy on a fixed
        # port for exactly this. Without it, a single running proxy is
        # unambiguous and several are not.
        port = os.environ.get('OVPN_PROXY_PORT') or None

    if port is not None:
        rec = read_state(port)
        if not rec:
            die(f'no proxy of ours is listening on port {port}',
                (f'These are:\n' +
                 '\n'.join(f'    port {r["port"]}  {r["name"]}' for r in live)
                 if live else 'Nothing is running at all.') + '\n'
                f'    ovpn proxy connect --port {port}   starts one there')
    elif len(live) == 1:
        rec = live[0]
    elif not live:
        die('no proxy is running, so there is nothing to point the terminal at',
            'ovpn proxy connect --port 8899   starts one\n'
            'px                               then sends this shell through it')
    else:
        die(f'{len(live)} proxies are running - say which one',
            '\n'.join(f'    px {r["port"]}   {r["name"]}' for r in live) + '\n'
            'Or put the one you always want in your rc file:\n'
            "    export OVPN_PROXY_PORT=8899")

    for line in env_lines(rec, ps):
        print(line)

    head('Terminal')
    ok(f'{C["bold"]}http://{rec["host"]}:{rec["port"]}{C["off"]}')
    field('exit', f'{rec["ip"]}   {rec["name"]}')
    note('    curl, git, npm, pip and wget read this. The name you ask for is')
    note('    resolved at the exit, so no local resolver is given a chance.')
    note(f'    {"px off" if HAS_PX else "ovpn proxy env --off | iex"}'
         '    to stop')
    print(file=OUT)
    return 0


def do_detach(args, exit_):
    """Start the proxy in a process of its own and come straight back.

    Everything that can fail slowly has already failed by the time this
    runs, here, where there is someone to tell: the config has been read,
    the address resolved, and the exit has answered. So the child is handed
    a bare address and does no choosing. What is left can only fail at the
    bind, which takes no time at all.

    Which is why this waits for the child to record itself rather than
    assuming it did, and reads its output when it does not. A detached
    process that dies quietly is worse than no detaching: `stop` would find
    nothing, `connect` would find the port taken, and the pair of answers
    would make no sense together.
    """
    import subprocess

    out_path = os.path.join(ROOT, '.state', f'proxy-{args.port}.out')
    try:
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        out = open(out_path, 'w', encoding='utf-8')
    except OSError as e:
        die('nowhere to put the detached proxy\'s output', f'{out_path}: {e}')

    argv = [sys.executable, os.path.abspath(__file__), exit_.ip,
            '--host', exit_.host, '--port', str(args.port),
            '--exit-port', str(exit_.port), '--listen', args.listen,
            '--auth', args.auth, '--warm', str(exit_.warm)]
    if exit_.bind:
        argv += ['--bind', exit_.bind]
    if args.quiet:
        argv.append('--quiet')

    kw = {}
    if os.name == 'nt':
        # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP: no console of ours for
        # it to inherit, and no Ctrl-C in this one reaching it.
        kw['creationflags'] = 0x00000008 | 0x00000200
    else:
        # Its own session, so closing the terminal hangs up on this shell's
        # process group without it being in that group.
        kw['start_new_session'] = True

    try:
        with out:
            child = subprocess.Popen(argv, stdin=subprocess.DEVNULL,
                                     stdout=out, stderr=subprocess.STDOUT,
                                     **kw)
    except OSError as e:
        die('could not start a proxy of its own', str(e))

    # Binding a socket is instant; thirty seconds is generous only so that a
    # loaded machine cannot turn into a false alarm.
    for _ in range(60):
        rec = read_state(args.port)
        if rec and rec['pid'] == child.pid:
            head('Detached')
            ok(f'{C["bold"]}http://{args.listen}:{args.port}{C["off"]}')
            field('exit', f'{exit_.ip}   {exit_.host}')
            field('pid', child.pid)
            field('output', out_path)
            print(file=OUT)
            note('It has no terminal of its own, so closing this one - or')
            note('logging out altogether - leaves it running.')
            note(f'    {point_hint(args.port, args.listen)}'
                 '   point a terminal at it')
            note(f'    ovpn proxy stop --port {args.port}   end it')
            print(file=OUT)
            return 0
        if child.poll() is not None:
            break
        time.sleep(0.5)

    try:
        with open(out_path, encoding='utf-8') as f:
            said = f.read().strip()
    except OSError:
        said = ''

    if child.poll() is None:
        # Alive, but it never recorded itself, so nothing can find it while
        # it goes on holding the port. That is the exact pair of answers
        # this repo has already been bitten by once.
        try:
            kill(child.pid)
            said += '\n\nIt was still running without having recorded itself, '
            said += 'so it was stopped.'
        except Exception:
            said += f'\n\nIt is still running as pid {child.pid} and could '
            said += 'not be stopped. End it by hand.'

    die('the detached proxy did not come up',
        (said or 'It printed nothing at all.') + f'\n\nIn full: {out_path}')


def do_env_show():
    url = os.environ.get('http_proxy') or os.environ.get('HTTP_PROXY') or ''
    head('Terminal')
    if not url:
        note('nothing set - this shell goes out directly.')
        live = read_states()
        for r in live:
            note(f'    {point_hint(r["port"], r["host"])}   through {r["name"]}')
        if not live:
            note('    ovpn proxy connect --port 8899   starts one to use')
        print(file=OUT)
        return 0

    port = env_port_in(url)
    rec = read_state(port) if port else None
    if rec:
        ok(f'{C["bold"]}{url}{C["off"]}')
        field('exit', f'{rec["ip"]}   {rec["name"]}')
        field('since', rec['since'])
    else:
        # The expensive mistake this whole subcommand exists to prevent: the
        # variables outlive the proxy, so every request fails at once and
        # nothing says why. Worth being blunt about.
        warn(f'set to {url}, but nothing of ours is listening there',
             'Every request from this shell will fail until that is one or\n'
             'the other. The proxy was probably stopped after it was set.')
        live = read_states()
        for r in live:
            note(f'    px {r["port"]}   through {r["name"]}, which is up')
        note('    px off      go back to direct')
    if not os.environ.get('OVPN_PROXY'):
        note('Set by something other than px - it left no marker.')
    print(file=OUT)
    return 0


def pick_live(folder, auth, jobs, timeout, bind=None, limit=140):
    """The quickest exit in the folder that will take the credentials now.

    Which exits do is not a property of the exit - it moves. A server that
    served 130 Mbit this afternoon answers 407 twenty times out of twenty this
    evening, and one that refused all day answers twenty out of twenty. About
    one in twenty is willing at any moment, so naming one in advance is a
    coin toss and asking a hundred at once is not.
    """
    names = [f for f in sorted(os.listdir(folder)) if f.endswith('.ovpn')]
    names = one_per_exit(names)[:limit]
    if not names:
        die(f'no .ovpn files in {folder}',
            'Point --dir somewhere else, or pin some configs first:  ovpn pin')
    head('Finding an exit')
    note(f'asking {len(names)} of them which will take the credentials now,')
    note('and taking the first that answers. A good many will not.')

    def probe(name):
        ip, host = read_config(os.path.join(folder, name))
        if not host:
            raise OSError('no pin comment')
        return can_connect(Exit(ip, 443, host, auth[0], auth[1], bind), timeout), name, ip, host

    winners = []
    with cf.ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = [pool.submit(probe, n) for n in names]
        for fut in cf.as_completed(futures):
            try:
                winners.append(fut.result())
            except Exception:
                pass
            # Two is enough to pick a quickest from without waiting out the
            # long tail of refusals, which is most of what is being waited on.
            if len(winners) >= 2:
                for f in futures:
                    f.cancel()
                break

    if not winners:
        die(f'none of the {len(names)} exits asked would take the credentials',
            'A good many refuse at any one time, and asking harder makes that\n'
            'worse rather than better - wait a minute or two and try again.\n'
            'If it keeps up, check you are leaving from the line you think:\n'
            "    curl -s https://www.cloudflare.com/cdn-cgi/trace | grep '^ip='")
    winners.sort()
    took, name, ip, host = winners[0]
    ok(f'{name}  answered in {took:.2f}s')
    return ip, host, name


def serve(listen_host, listen_port, exit_, quiet):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    # Not on Windows. There SO_REUSEADDR does not mean "reuse the port I just
    # stopped listening on" - it means "bind it even though someone else is
    # already listening", and both processes then get some of the connections.
    # A second one started by mistake would look like it worked and answer
    # every other request from the wrong exit.
    if os.name != 'nt':
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind((listen_host, listen_port))
    except OSError as e:
        # Naming what holds it, because "address already in use" plus a
        # `stop` that reports nothing running is a pair of answers that
        # cannot both be acted on.
        mine = read_state(listen_port)
        if mine:
            die(f'cannot listen on {listen_host}:{listen_port}', f'{e}\n'
                f'{mine["name"]} has had that port since {mine["since"]} '
                f'(pid {mine["pid"]}).\n'
                f'ovpn proxy stop --port {listen_port}  ends it, or --port '
                f'gives this one somewhere else to sit.')
        held = '\n'.join(f'    pid {pid}  {cmd[:80]}' for pid, cmd in strays())
        die(f'cannot listen on {listen_host}:{listen_port}', f'{e}\n' +
            ('Nothing of ours is recorded on that port. These are running\n'
             f'with no record at all, so it may be one of them:\n{held}\n'
             if held else 'Every proxy of ours is accounted for and none has '
                          'that port, so\nwhatever holds it is something '
                          'else.\n') +
            'ovpn proxy stop  ends ours, or --port picks a different one.')
    srv.listen(128)

    head('Up')
    ok(f'{C["bold"]}http://{listen_host}:{listen_port}{C["off"]}')
    field('exit', f'{exit_.ip}:{exit_.port}   {exit_.host}')
    field('certificate', f'checked against {exit_.host}, and the name is not sent')
    if exit_.bind:
        field('leaving via', exit_.bind)
    # Said plainly, because the whole point of a second port is that the
    # first one carries on undisturbed - and "undisturbed" is easier to
    # believe when you can see it listed.
    for r in read_states():
        if str(r['port']) != str(listen_port):
            field('also up', f'port {r["port"]}   {r["name"]}   left alone')
    if exit_.warm:
        field('held ready', f'{exit_.warm} connection'
                            f'{"" if exit_.warm == 1 else "s"} to the exit, '
                            f'opened before they are asked for')
    print()
    print(f'  {C["head"]}In the browser, set BOTH the HTTP and the HTTPS '
          f'proxy to{C["off"]}')
    print(f'      {C["bold"]}{listen_host}{C["off"]}   port '
          f'{C["bold"]}{listen_port}{C["off"]}')
    print()
    # Worth its own line rather than a footnote. Telegram over an HTTP proxy
    # does not tunnel at all - it turns every message into its own request to
    # port 80 - and the only way to tell it not to is to offer it SOCKS5.
    print(f'  {C["head"]}In Telegram, choose SOCKS5 rather than HTTP{C["off"]}')
    print(f'      {C["bold"]}{listen_host}{C["off"]}   port '
          f'{C["bold"]}{listen_port}{C["off"]}   no username, no password')
    print(f'      {C["dim"]}Same port. On HTTP it sends a request per message;'
          f'{C["off"]}')
    print(f'      {C["dim"]}on SOCKS5 it keeps one connection and is quicker.'
          f'{C["off"]}')
    print()
    note('Ctrl-C ends it. Nothing on this machine is changed while it runs -')
    note('no routes, no DNS, no system proxy setting, nothing to put back.')
    note(f'Failures while it runs are logged to {LOG_PATH}')
    print(flush=True)

    write_state(listen_host, listen_port, exit_)
    log('START', f'{listen_host}:{listen_port} -> {exit_.ip} ({exit_.host})')
    exit_.prewarm()

    # Started before the first connection is accepted, so the file exists
    # from the moment the proxy is up. A window that connects and then finds
    # nothing to read has to guess whether the meter is broken or the line
    # is simply quiet, and those two should never look alike.
    metering = threading.Event()
    threading.Thread(target=meter_writer, args=(listen_port, metering),
                     daemon=True).start()

    # `ovpn proxy stop` sends a TERM, which would otherwise take the process
    # down without running any of the tidying below - no line in the log, and
    # a state file left claiming a proxy that is gone. Turning it into the
    # same interrupt Ctrl-C raises means one exit path instead of two.
    if os.name != 'nt':
        import signal
        signal.signal(signal.SIGTERM,
                      lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))

    served = 0
    try:
        while True:
            try:
                client, who = srv.accept()
            except KeyboardInterrupt:
                raise
            except OSError as e:
                # One bad accept is not a reason to take the proxy down while
                # a browser is pointed at it.
                warn('could not accept a connection', str(e))
                continue
            served += 1
            threading.Thread(target=serve_one,
                             args=(client, exit_, quiet, who[1]),
                             daemon=True).start()
    except KeyboardInterrupt:
        print()
        ok(f'stopped after {served} connection{"" if served == 1 else "s"}')
        print()
    finally:
        t = exit_.tally
        log('STOP', f'after {served} connections; {t["warm"]} started from one '
                    f'held ready, {t["cold"]} dialled, {t["stale"]} discarded')
        log('MOVED', f'{METER["up"]} bytes out, {METER["down"]} bytes back')
        exit_.drain()
        # The file goes with the proxy. Leaving the last reading behind is
        # how a window comes back up and paints a meter for a connection
        # that ended hours ago.
        metering.set()
        clear_traffic(listen_port)
        clear_hosts(listen_port)
        clear_state()
        try:
            srv.close()
        except OSError:
            pass


#----------------------------------------------------------------------- main

# connect / sweep / status / stop, read off the front of the command line and
# turned into the flags the parser already knows. A verb rather than a flag
# because that is how the rest of the repo is typed - `ovpn connect`, `ovpn
# stop` - and `ovpn proxy connect uk-lon` should not be the odd one out.
VERBS = {'connect': [], 'sweep': ['--sweep'], 'env': ['--env'],
         'status': ['--status'], 'stop': ['--stop'], 'help': ['--usage']}


def do_help():
    h, d, b, o = C['head'], C['dim'], C['bold'], C['off']
    print(phrase(f"""
  {h}{b}ovpn proxy{o} - reach the web through an exit's HTTPS proxy on 443,
  {d}instead of through an OpenVPN tunnel the line throttles.{o}

  {h}{b}The commands{o}

    {b}ovpn proxy{o}
        What is running, and through which exit. Starts nothing.

    {b}ovpn proxy connect{o} [name]
        Serve a proxy on 127.0.0.1:8888. {d}HTTP and SOCKS5 both, on the one
        port - the first byte a client sends decides which.{o}

        With no name it asks around 140 exits which will take the
        credentials and serves the quickest that will. {d}That is the way to
        use it: a good many refuse at any one time and nothing in the
        config says which.{o} Naming one works when you want a particular
        country - it asks before it listens, rather than coming up looking
        healthy and answering 502 to everything.

  {h}{b}Telegram wants SOCKS5, not HTTP{o}

    Told its proxy is HTTP, Telegram Desktop stops tunnelling altogether -
    {d}useTcp = (proxyType != Type::Http){o} in its own source - and turns
    every MTProto packet into its own POST to port 80 in the clear. No
    setting changes that. On SOCKS5 it keeps one connection instead, which
    is what the Windows system proxy was quietly doing all along.

        {d}Connection type - Custom - SOCKS5{o}
        {b}127.0.0.1{o}   the port this is on   {d}no username, no password{o}

    The exit sees none of it: a SOCKS5 request becomes the same CONNECT it
    was always sent. Names are forwarded rather than resolved here, so no
    local resolver is given the chance to answer for one.

    {b}ovpn proxy sweep{o}
        Ask a folder of exits what they serve, and rank them.
        {d}Forty at a time. Sweeping several hundred stops measuring the
        exits and starts measuring how hard you have been sweeping.{o}

    {b}ovpn proxy stop{o}
        Ends it, and says which one it ended. With more than one running it
        will not guess: {d}--port N{o} for one, {d}--all{o} for all of them.

  {h}{b}More than one at a time{o}

    Each proxy is a port and an exit, and they know nothing of each other.
    A second one is a second port:

        {b}ovpn proxy connect{o}                    {d}browser, port 8888{o}
        {b}ovpn proxy connect de-ber --port 8899 --detach{o}

    {b}--detach{o} is what makes the second one worth having: it comes back
    rather than holding the terminal, and having no terminal of its own it
    survives that one closing, and logging out. Without it the proxy lives
    exactly as long as the window you started it in.

    The exit is chosen, checked and proved before anything is detached, so
    a failure is reported here rather than disappearing into a log. What is
    left can only fail at the bind, and that is waited for too - a detached
    proxy that died quietly would leave `stop` finding nothing while
    `connect` found the port taken.

    Which is worth doing when something wants an exit that does not move
    under it - a terminal, a chat client - while the browser's keeps being
    changed. Starting, stopping or reconnecting one leaves the other alone;
    they share no routes, no DNS and no system setting, because there are
    none to share.

    For the terminal there is {b}px{o}, which does the exporting for you:

        {b}px{o}            send this shell through the proxy that is running
        {b}px 8899{o}       through the one on that port
        {b}px off{o}        stop
        {b}px status{o}     what this shell is set to, and whether it still works

    curl, git, npm, pip and wget read what it sets. The name you asked for
    is resolved at the exit, not here, so a poisoned resolver never sees it.

    {b}px{o} is a shell function, and has to be: it changes the shell you typed
    it in, and nothing run as a child can do that to its parent. {d}ovpn
    install{o} adds the line that defines it - or add it by hand:

        {b}. /path/to/ovpn-pin/linux/ovpn-shell.sh{o}

    With more than one proxy up, say which port, or name it once in the rc
    file and stop thinking about it:

        {b}export OVPN_PROXY_PORT=8899{o}

    {b}ovpn proxy env{o} is what {b}px{o} calls. It prints the exports rather than
    applying them, for the same reason - so its stdout is shell and nothing
    else, and everything for you to read goes to stderr.

  {h}{b}Flags{o}

    {b}--port{o} N          listen somewhere other than 8888 {d}(and, with stop,
                      which of several to end){o}
    {b}--detach{o}          leave it running and come back {d}(output goes to
                      .state/proxy-N.out){o}
    {b}--all{o}             with stop, end every proxy rather than naming one
    {b}--dir{o} DIR         which folder of configs to use {d}(pinned/){o}
    {b}--bind{o} ADDR       which address to leave from. {d}Needed when a tunnel
                      owns the default route, or this goes out through the
                      very thing it exists to avoid{o}
    {b}--host{o} NAME       the name the certificate must serve {d}(read from
                      the config's pin comment when you do not say){o}
    {b}--auth{o} FILE       credentials, one per line {d}(.ovpn-auth){o}
    {b}--warm{o} N          connections to the exit opened before they are
                      asked for {d}(2, at most 4, 0 turns it off). Takes two
                      round trips off every new connection: 343 ms down to
                      140 ms, one at a time. Every sweep runs at 0{o}
    {b}--quiet{o}           do not report failed connections

    {d}sweep only:{o}
    {b}--connect-only{o}    stop at the proxy, fetch nothing. {d}Fast, and says
                      only that an exit will talk to you{o}
    {b}--site{o} a.com,b.com  also ask each exit for those
    {b}--one-per{o}         one address per exit rather than all of them
    {b}--first{o} N         stop after N {d}(overrides the forty-at-a-time cap){o}
    {b}--jobs{o} N          how many at once {d}(12){o}

  {h}{b}What it does and does not carry{o}

    HTTP, HTTPS and SOCKS5, on the one port. {d}The first byte a client
    sends decides: 0x05 is SOCKS5, a letter is HTTP.{o} All three end up as
    the same CONNECT at the exit, and CONNECT carries whatever the two ends
    put in it, so anything speaking TCP travels the same way. Not UDP, not
    ICMP, and nothing that ignores the proxy setting it was given; for
    those you would still want a tunnel.

    SOCKS5 is here for one reason: a client that will not tunnel over an
    HTTP proxy. {d}Telegram is the one that matters - see above.{o} It is
    offered without authentication because it listens on the loopback, and
    a password between two programs on one machine protects nothing the
    machine does not already.

    Nothing else on the machine goes through it. That is the point rather
    than a shortcoming: it is why a second one on another port can serve a
    different exit without the first one noticing.

    It changes no routes, no DNS and no system proxy setting, so there is
    nothing to put back. Failures are logged to {d}.state/proxy.log{o}.

  {h}{b}Two things stay off the wire{o}

    The {b}address{o} is read out of the pinned config rather than looked up,
    so no resolver answers for where the exit is. The {b}name{o} is never sent
    as SNI - a handshake naming *.prod.surfshark.com in the clear is killed
    on the way out. The certificate is checked by hand instead, against the
    system CAs and against the name the config was pinned from, so the name
    is proved without being announced.
"""))
    return 0


def read_verb(argv):
    # Ahead of argparse, so that the written page is what people see rather
    # than a wall of generated flags.
    if argv and argv[0] in ('-h', '--help', 'help'):
        raise SystemExit(do_help())
    if argv and argv[0] in VERBS:
        return VERBS[argv[0]] + argv[1:]
    # A bare word that is nearly a verb is a typo worth catching, not a config
    # name to go hunting for.
    if argv and argv[0].isalpha() and len(argv[0]) > 3:
        near = [v for v in VERBS if v.startswith(argv[0][:3])]
        if near and argv[0] not in near:
            die(f'no such thing as  ovpn proxy {argv[0]}',
                f'Did you mean  ovpn proxy {near[0]} ?\n'
                f'The verbs are: {", ".join(sorted(VERBS))}')
    return argv


def main():
    p = argparse.ArgumentParser(
        prog='ovpn-proxy.py', add_help=True,
        description='Serve a local proxy that reaches the web through a '
                    'pinned Surfshark exit, over HTTPS rather than OpenVPN.')
    p.add_argument('target', nargs='?', help='a pinned .ovpn file, enough of '
                                             'its name to be unambiguous, or a '
                                             'bare address. Not used by --sweep')
    p.add_argument('--sweep', action='store_true',
                   help='ask every config in the folder what its exit serves, '
                        'all at once, and rank them by how quickly it answered')
    p.add_argument('--out', default=os.path.join(ROOT, 'proxy-ok'),
                   help='where --sweep copies what served (proxy-ok/)')
    p.add_argument('--site', action='append', default=[],
                   help='also ask each exit for this host. Repeatable, or comma '
                        'separated. An exit has to serve all of them to be kept')
    p.add_argument('--jobs', type=int, default=12,
                   help='how many exits to ask at once during --sweep (12)')
    p.add_argument('--first', type=int, help='stop --sweep after this many')
    p.add_argument('--connect-only', action='store_true',
                   help='--sweep only far enough to see whether the exit runs '
                        'a proxy at all. Fetches nothing')
    p.add_argument('--one-per', action='store_true',
                   help='--sweep one address per exit rather than all of them')
    p.add_argument('--timeout', type=int, default=20,
                   help='seconds to wait on an exit during --sweep (20)')
    # No default here on purpose: `stop` has to be able to tell "port 8888"
    # from "you did not say a port", and it cannot if the parser has already
    # filled one in. The 8888 goes on further down, where it means listening.
    p.add_argument('--port', type=int, help='listen port (8888). With stop, '
                                            'which of several to end')
    p.add_argument('--host', help='name the certificate must serve. Read from '
                                  'the config when not given')
    p.add_argument('--exit-port', type=int, default=443,
                   help="the exit's proxy port (443)")
    p.add_argument('--auth', default=os.path.join(ROOT, '.ovpn-auth'),
                   help='file holding username and password, one per line')
    # OVPN_OUT_DIR is how the rest of the repo is told which folder to act on
    # - ovpn-connect.sh and resolve-ovpn-remote.sh both read it, falling back
    # to pinned/. This script did not, which made it the odd one out anywhere
    # the dispatcher was not there to translate: on Windows there is no
    # dispatcher, so setting it had no effect at all.
    #
    # What it still will not do is look at the folder you are standing in.
    # That convenience lives in the `ovpn` wrapper on purpose - a script that
    # quietly acts on wherever you happen to be is a bad script, and this one
    # is also what the wrapper calls.
    p.add_argument('--dir',
                   default=os.environ.get('OVPN_OUT_DIR')
                   or os.path.join(ROOT, 'pinned'),
                   help='where to look for configs by name. Defaults to '
                        '$OVPN_OUT_DIR, or pinned/')
    p.add_argument('--bind', help='source address for the connection out. Only '
                                  'needed when a tunnel owns the default route '
                                  'and this would otherwise go through it')
    p.add_argument('--listen', default='127.0.0.1',
                   help='address to listen on (127.0.0.1)')
    p.add_argument('--warm', type=int, default=WARM_DEFAULT,
                   help=f'connections to the exit opened before they are '
                        f'asked for ({WARM_DEFAULT}, at most {WARM_MAX}). '
                        f'0 turns it off, which is what every sweep uses')
    p.add_argument('--quiet', action='store_true', help='do not report failed connections')
    p.add_argument('--status', action='store_true',
                   help='say whether a proxy is running, and through what')
    p.add_argument('--stop', action='store_true', help='stop the running proxy')
    p.add_argument('--all', action='store_true',
                   help='with stop, end every proxy rather than naming one')
    p.add_argument('--detach', action='store_true',
                   help='leave it running in a process of its own and come '
                        'back, rather than holding this terminal')
    p.add_argument('--env', action='store_true',
                   help='print the exports that send a terminal through a '
                        'running proxy. Meant to be eval-ed, not read')
    p.add_argument('--off', action='store_true',
                   help='with env, print the unsets instead')
    p.add_argument('--show', action='store_true',
                   help='with env, say what this shell is set to now')
    p.add_argument('--powershell', action='store_true',
                   help='with env, print $env: lines (the default on Windows)')
    p.add_argument('--sh', action='store_true',
                   help='with env, print export lines. Needed for Git Bash '
                        'on Windows, where the default guesses PowerShell')
    p.add_argument('--usage', action='store_true',
                   help='the commands and flags, in full')
    args = p.parse_args(read_verb(sys.argv[1:]))

    if args.usage:
        raise SystemExit(do_help())
    if args.status:
        raise SystemExit(do_status())
    if args.stop:
        raise SystemExit(do_stop(args.port, args.all))
    if args.env:
        # Everything a person reads moves to stderr for the rest of this run,
        # so that stdout is the shell's alone.
        talk_on(sys.stderr)
        raise SystemExit(do_env(args.port, args.off, args.show,
                                True if args.powershell else
                                False if args.sh else None))

    # Everything past here is about listening, so the default belongs here
    # rather than in the parser, where it would have blunted `stop --port`.
    if args.port is None:
        args.port = 8888

    # Both forms take --site the same way the other scripts do: a comma or a
    # space separated list, or the flag more than once.
    sites = [h for chunk in args.site for h in re.split(r'[,\s]+', chunk) if h]

    if args.sweep:
        if args.target:
            die(f'sweep acts on a whole folder, so {args.target!r} means '
                f'nothing to it',
                'Narrow it with --one-per or --first, or point --dir elsewhere.')
        if sites and args.connect_only:
            die('--connect-only fetches nothing, so it cannot ask an exit '
                'for a site',
                'Drop one of the two: --connect-only for speed, --site for the\n'
                'question of whether that exit is served the page.')
        do_sweep(args.dir, args.out, sites, args.timeout, args.jobs,
                 args.first, args.one_per, os.path.join(ROOT, '.state'),
                 args.auth, args.connect_only, args.bind)
        return

    user, password = read_auth(args.auth)

    # Said before the work rather than after the port clash, because the fix
    # is usually "you already have one" and not "pick another port".
    running = read_state(args.port)
    if running:
        die(f'a proxy is already up on port {args.port}',
            f'{running["name"]} since {running["since"]}, pid '
            f'{running["pid"]}.\n'
            f'    ovpn proxy stop --port {args.port}   ends it\n'
            f'    ovpn proxy connect --port 8899  puts this one beside it')

    if not args.target:
        ip, name, chosen = pick_live(args.dir, (user, password), args.jobs,
                                     args.timeout, args.bind)
        field('config', chosen)
    elif IS_IPV4.match(args.target):
        ip, name = args.target, None
    else:
        path = args.target if os.path.isfile(args.target) else find_config(args.target, args.dir)
        ip, name = read_config(path)
        head('Exit')
        field('config', os.path.basename(path))

    host = args.host or name
    if not host:
        die('no name to check the certificate against',
            'A pinned config carries one in its pin comment; a bare address\n'
            'does not. Pass --host uk-man.prod.surfshark.com or similar.')

    exit_ = Exit(ip, args.exit_port, host, user, password, args.bind,
                 warm=args.warm)

    # Ask once before listening. Without this the proxy comes up looking
    # healthy and answers 502 to everything, which sends you hunting through
    # browser settings for a fault that is at the other end.
    if args.target:
        try:
            can_connect(exit_, args.timeout)
        except (OSError, ssl.SSLError) as e:
            die(f'{host} will not take the credentials just now', f'{e}\n'
                'A good many exits refuse at any one time. Either try one of\n'
                'its other addresses, or let it choose:  ovpn proxy connect')

    # Below this line the exit is known good, which is the point to hand it
    # to a process that will outlive this one.
    if args.detach:
        raise SystemExit(do_detach(args, exit_))

    serve(args.listen, args.port, exit_, args.quiet)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\n  stopped')
