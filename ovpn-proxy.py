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

    ovpn-proxy.py uk-man              # enough of a pinned config's name
    ovpn-proxy.py 139.28.176.165 --host uk-man.prod.surfshark.com
    ovpn-proxy.py uk-man --port 8080

This carries HTTP and HTTPS, which is what a browser asks of a proxy. It is
not a tunnel: nothing else on the machine goes through it, and neither does
anything that is not HTTP.
"""

import argparse
import base64
import concurrent.futures as cf
import os
import re
import select
import shutil
import socket
import ssl
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
AUTH_DEFAULT = os.path.join(HERE, '.ovpn-auth')

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
    if os.environ.get('NO_COLOR') or not stream.isatty():
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
LOG_PATH = os.path.join(HERE, '.state', 'proxy.log')

# Where the lines meant for a person go. stdout everywhere except `env`,
# whose stdout belongs to a shell that is about to eval it - a stray "folder:
# pinned" in among the exports would be run as a command. See do_env.
OUT = sys.stdout


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
    print(f'\n  {C["head"]}{C["bold"]}{msg}{C["off"]}', file=OUT)


def field(label, value):
    print(f'  {C["head"]}{label:<12}{C["off"]}{value}', file=OUT)


def ok(msg):
    print(f'  {C["ok"]}[ ok ]{C["off"]} {msg}', file=OUT)


def warn(msg, detail=''):
    # Flushed, because the failure that usually follows goes to stderr, and
    # unflushed stdout would let it print first and read as the cause.
    sys.stdout.flush()
    print(f'  {C["warn"]}[warn]{C["off"]} {msg}', file=OUT, flush=True)
    # Every line indented, not just the first - an unindented second line
    # reads as a separate message rather than as part of this one.
    for line in detail.splitlines():
        print(f'         {C["dim"]}{line}{C["off"]}', file=OUT, flush=True)
    log('WARN', msg + (f' - {detail.replace(chr(10), " ")}' if detail else ''))


def note(msg):
    print(f'  {C["dim"]}{msg}{C["off"]}', file=OUT)


def die(msg, detail=''):
    print(f'\n  {C["fail"]}{C["bold"]}[fail]{C["off"]} {msg}', file=sys.stderr)
    for line in detail.splitlines():
        print(f'         {C["dim"]}{line}{C["off"]}', file=sys.stderr)
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


class Exit:
    def __init__(self, ip, port, host, user, password, bind=None):
        self.ip, self.port, self.host, self.bind = ip, port, host, bind
        self.auth = base64.b64encode(f'{user}:{password}'.encode()).decode()
        self.ctx = ssl.create_default_context()
        # The chain is still verified - only the name check is taken over
        # below, because doing it in OpenSSL would mean sending the name.
        self.ctx.check_hostname = False

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


#-------------------------------------------------------------------- serving

def relay(a, b):
    """Bytes both ways until either end is done. Past the proxy's 200 this
    connection is opaque - the browser's own TLS to the site it asked for
    runs inside it, and neither we nor the exit can read that."""
    ends = [a, b]
    try:
        while True:
            readable, _, failed = select.select(ends, [], ends, 300)
            if failed or not readable:
                return
            for s in readable:
                chunk = s.recv(65536)
                if not chunk:
                    return
                (b if s is a else a).sendall(chunk)
    except OSError:
        pass
    finally:
        for s in ends:
            try:
                s.close()
            except OSError:
                pass


def serve_one(client, exit_, quiet):
    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

    head = b''
    try:
        while b'\r\n\r\n' not in head:
            chunk = client.recv(4096)
            if not chunk:
                client.close()
                return
            head += chunk
            if len(head) > 65536:
                client.close()
                return
    except OSError:
        client.close()
        return

    headers, _, body = head.partition(b'\r\n\r\n')
    lines = headers.split(b'\r\n')
    request = lines[0].decode('latin-1', 'replace')

    # Whatever the client had in mind for credentials, ours are the ones the
    # exit will accept. Right after the request line, where a proxy looks.
    lines = [l for l in lines if not l.lower().startswith(b'proxy-authorization:')]
    lines.insert(1, b'Proxy-Authorization: Basic ' + exit_.auth.encode())

    try:
        upstream = exit_.connect()
        upstream.sendall(b'\r\n'.join(lines) + b'\r\n\r\n' + body)
    except (OSError, ssl.SSLError) as e:
        if not quiet:
            print(f'  {request.split(chr(32))[0]:<8} {e}', file=sys.stderr, flush=True)
        try:
            client.sendall(b'HTTP/1.1 502 Bad Gateway\r\n'
                           b'Content-Length: 0\r\n\r\n')
        except OSError:
            pass
        client.close()
        return

    relay(client, upstream)


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
STATE_PATH = os.path.join(HERE, '.state', 'proxy.state')


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
    """Any other copy of this script running, whatever started it.

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

    me = os.getpid()
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
                if not (pid.strip().isdigit() and int(pid) != me):
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
            if not entry.isdigit() or int(entry) == me:
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
    if len(live) == 1:
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


def env_lines(rec):
    """The exports, as a shell would want them."""
    url = f'http://{rec["host"]}:{rec["port"]}'
    skip = []
    for part in re.split(r'[,\s]+', os.environ.get('no_proxy', '')):
        if part and SAFE_HOST.match(part) and part not in skip:
            skip.append(part)
    for part in NEVER_PROXY:
        if part not in skip:
            skip.append(part)
    skip = ','.join(skip)

    out = [f"export {v}='{url}'"
           for v in ('http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY')]
    out.append(f"export no_proxy='{skip}'")
    out.append(f"export NO_PROXY='{skip}'")
    # A marker, so that `px status` can tell a proxy this set from one that
    # was already in the environment when the shell started.
    out.append(f"export OVPN_PROXY='{rec['host']}:{rec['port']}'")
    return out


def env_port_in(url):
    """The port out of an http_proxy value, or None if it is not one of ours
    to reason about."""
    m = re.search(r':(\d+)/?$', url or '')
    return m.group(1) if m else None


def do_env(port=None, off=False, show=False):
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
    if off:
        for v in PROXY_VARS:
            print(f'unset {v}')
        head('Terminal')
        ok('direct again - no proxy set for this shell')
        note('    px      to send it back through one')
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

    for line in env_lines(rec):
        print(line)

    head('Terminal')
    ok(f'{C["bold"]}http://{rec["host"]}:{rec["port"]}{C["off"]}')
    field('exit', f'{rec["ip"]}   {rec["name"]}')
    note('    curl, git, npm, pip and wget read this. The name you ask for is')
    note('    resolved at the exit, so no local resolver is given a chance.')
    note('    px off    to stop')
    print(file=OUT)
    return 0


def do_env_show():
    url = os.environ.get('http_proxy') or os.environ.get('HTTP_PROXY') or ''
    head('Terminal')
    if not url:
        note('nothing set - this shell goes out directly.')
        live = read_states()
        for r in live:
            note(f'    px {r["port"]}   through {r["name"]}')
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
            (f'These copies of this script are running:\n{held}\n'
             if held else 'Nothing of ours is running, so it is something '
                          'else on that port.\n') +
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
    print()
    print(f'  {C["head"]}In the browser, set BOTH the HTTP and the HTTPS '
          f'proxy to{C["off"]}')
    print(f'      {C["bold"]}{listen_host}{C["off"]}   port '
          f'{C["bold"]}{listen_port}{C["off"]}')
    print()
    note('Ctrl-C ends it. Nothing on this machine is changed while it runs -')
    note('no routes, no DNS, no system proxy setting, nothing to put back.')
    note(f'Failures while it runs are logged to {LOG_PATH}')
    print(flush=True)

    write_state(listen_host, listen_port, exit_)
    log('START', f'{listen_host}:{listen_port} -> {exit_.ip} ({exit_.host})')

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
                client, _ = srv.accept()
            except KeyboardInterrupt:
                raise
            except OSError as e:
                # One bad accept is not a reason to take the proxy down while
                # a browser is pointed at it.
                warn('could not accept a connection', str(e))
                continue
            served += 1
            threading.Thread(target=serve_one, args=(client, exit_, quiet),
                             daemon=True).start()
    except KeyboardInterrupt:
        print()
        ok(f'stopped after {served} connection{"" if served == 1 else "s"}')
        print()
    finally:
        log('STOP', f'after {served} connections')
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
    print(f"""
  {h}{b}ovpn proxy{o} - reach the web through an exit's HTTPS proxy on 443,
  {d}instead of through an OpenVPN tunnel the line throttles.{o}

  {h}{b}The commands{o}

    {b}ovpn proxy{o}
        What is running, and through which exit. Starts nothing.

    {b}ovpn proxy connect{o} [name]
        Serve a proxy on 127.0.0.1:8888 for the browser to point at.

        With no name it asks around 140 exits which will take the
        credentials and serves the quickest that will. {d}That is the way to
        use it: a good many refuse at any one time and nothing in the
        config says which.{o} Naming one works when you want a particular
        country - it asks before it listens, rather than coming up looking
        healthy and answering 502 to everything.

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
        {b}ovpn proxy connect de-ber --port 8899{o} {d}and one to settle on{o}

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

        {b}. /path/to/ovpn-pin/ovpn-shell.sh{o}

    With more than one proxy up, say which port, or name it once in the rc
    file and stop thinking about it:

        {b}export OVPN_PROXY_PORT=8899{o}

    {b}ovpn proxy env{o} is what {b}px{o} calls. It prints the exports rather than
    applying them, for the same reason - so its stdout is shell and nothing
    else, and everything for you to read goes to stderr.

  {h}{b}Flags{o}

    {b}--port{o} N          listen somewhere other than 8888 {d}(and, with stop,
                      which of several to end){o}
    {b}--all{o}             with stop, end every proxy rather than naming one
    {b}--dir{o} DIR         which folder of configs to use {d}(pinned/){o}
    {b}--bind{o} ADDR       which address to leave from. {d}Needed when a tunnel
                      owns the default route, or this goes out through the
                      very thing it exists to avoid{o}
    {b}--host{o} NAME       the name the certificate must serve {d}(read from
                      the config's pin comment when you do not say){o}
    {b}--auth{o} FILE       credentials, one per line {d}(.ovpn-auth){o}
    {b}--quiet{o}           do not report failed connections

    {d}sweep only:{o}
    {b}--connect-only{o}    stop at the proxy, fetch nothing. {d}Fast, and says
                      only that an exit will talk to you{o}
    {b}--site{o} a.com,b.com  also ask each exit for those
    {b}--one-per{o}         one address per exit rather than all of them
    {b}--first{o} N         stop after N {d}(overrides the forty-at-a-time cap){o}
    {b}--jobs{o} N          how many at once {d}(12){o}

  {h}{b}What it does and does not carry{o}

    HTTP and HTTPS, which is what a browser asks of a proxy. HTTPS goes
    through as CONNECT, and CONNECT carries whatever the two ends put in
    it, so anything speaking TCP on 443 - a chat client, say - travels the
    same way. Not UDP, not ICMP, and nothing that ignores the proxy setting
    it was given; for those you would still want a tunnel.

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
""")
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
    p.add_argument('--out', default=os.path.join(HERE, 'proxy-ok'),
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
    p.add_argument('--auth', default=os.path.join(HERE, '.ovpn-auth'),
                   help='file holding username and password, one per line')
    p.add_argument('--dir', default=os.path.join(HERE, 'pinned'),
                   help='where to look for configs by name (pinned/)')
    p.add_argument('--bind', help='source address for the connection out. Only '
                                  'needed when a tunnel owns the default route '
                                  'and this would otherwise go through it')
    p.add_argument('--listen', default='127.0.0.1',
                   help='address to listen on (127.0.0.1)')
    p.add_argument('--quiet', action='store_true', help='do not report failed connections')
    p.add_argument('--status', action='store_true',
                   help='say whether a proxy is running, and through what')
    p.add_argument('--stop', action='store_true', help='stop the running proxy')
    p.add_argument('--all', action='store_true',
                   help='with stop, end every proxy rather than naming one')
    p.add_argument('--env', action='store_true',
                   help='print the exports that send a terminal through a '
                        'running proxy. Meant to be eval-ed, not read')
    p.add_argument('--off', action='store_true',
                   help='with env, print the unsets instead')
    p.add_argument('--show', action='store_true',
                   help='with env, say what this shell is set to now')
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
        raise SystemExit(do_env(args.port, args.off, args.show))

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
                 args.first, args.one_per, os.path.join(HERE, '.state'),
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

    exit_ = Exit(ip, args.exit_port, host, user, password, args.bind)

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
    serve(args.listen, args.port, exit_, args.quiet)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\n  stopped')
