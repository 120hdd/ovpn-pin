"""Windscribe, which ports the transport and not the credential model.

Everything in note.md about *why* the proxy - the line throttles OpenVPN and
leaves TLS alone - and everything in windscribe.md about what is different
applies here. The short version of the difference, because it is the whole
reason this file exists rather than a second folder of servers:

  Surfshark hands out one service credential. It is on their website, it
  works for the tunnel and the proxy alike, and it does not expire. So the
  app can ask for it in a text box and never think about it again.

  Windscribe hands out a different credential per client type, and only the
  *extension* one opens the proxy - the OpenVPN credential you would already
  have is refused with 407. Getting the extension credential means logging
  in, and the login is behind a captcha.

So this file is a login client. The captcha is a slider puzzle that the API
sends as two base64 images; it is drawn in the app's own page and dragged by
the person using it, and their real mouse trail is what gets submitted. That
is what Windscribe's own client does, and it is the only honest way to pass a
test whose entire purpose is to ask whether a human is there. Nothing here
tries to solve one.

Where the constants come from
-----------------------------
Both are published by Windscribe themselves, in the GPL source of their
desktop client (Windscribe/wsnet, src/settings.h) - the same shared key the
browser extension uses, which is why one number serves both. They identify
the client, and belong to no account.

The one rule this file will not bend
------------------------------------
It never retries a login. note.md records a Windscribe account blocked after
about seventy security-alert emails, and a login box that quietly tries again
is how that happens. One attempt, then say what went wrong and stop.
"""

import base64
import ctypes
import hashlib
import json
import os
import re
import ssl
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

import paths

API = 'https://api.windscribe.com'
SERVER_LIST = 'https://assets.windscribe.com/serverlist/firefox/1/1'

# src/settings.h, Windscribe/wsnet: serverSharedKey() and signatureToken().
# The second one is spelled the way they spell it.
SHARED_KEY = '952b4412f002315aa50751032fcaab03'
SIGNATURE_TOKEN = 'if_you_copy_this_you_might_die_a_painful_death'

# 2 is the browser extension. 3 is the desktop app and 1 is OpenVPN, and
# neither of those credentials is accepted by the proxy - measured, not
# assumed: one account, three different passwords, one 200 and two 407s.
SESSION_TYPE_EXTENSION = 2

# Which client the API is being told this is, and it has to be the extension's
# answer rather than the desktop's: the session type above only means anything
# alongside a platform that issues that type. These three are what the
# extension itself sends - `platform=chrome` on every URL, and the two headers
# a request from inside a Chrome extension carries whether it wants to or not.
PLATFORM = 'chrome'
ORIGIN = 'chrome-extension://hnmpcagpplmpfojmgmnngilcnanddlhb'
USER_AGENT = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
              'AppleWebKit/537.36 (KHTML, like Gecko) '
              'Chrome/131.0.0.0 Safari/537.36')

# Where the session token lives, and beside it the account password - sealed
# with the Windows account's own key, never in the clear. See the DPAPI
# section below for why that distinction is the whole of the word "encrypted"
# here, and what it does and does not survive.
TOKEN_FILE = os.path.join(paths.STATE_DIR, 'windscribe.json')

# The proxy credential, in the same two-line shape as .ovpn-auth so that
# px.read_auth() reads it without knowing which provider it came from.
AUTH_FILE = os.path.join(paths.DATA_DIR, '.windscribe-auth')

# Unpinned configs are written here, which is the folder the pinning section
# already offers to read. Pinning them is the existing machinery, unchanged.
CONFIG_DIR = os.path.join(paths.DATA_DIR, 'windscribe')

# What the published list says about an exit beyond where it is: the name
# the city is known by, how loaded it is, how fast the link is, whether P2P
# is allowed there.
#
# A sidecar rather than more filename, and that is a real departure: until
# now the filename said everything, which is what let the pinning scripts,
# the sweep and the engine all agree without being introduced. But a load
# figure goes stale in minutes and a nickname has spaces in it, and neither
# belongs in a name that a shell script has to match on. So the filename
# still says what an exit *is* - country, city, provider, address - and this
# says what it was *like* when the list was fetched. Nothing breaks if it is
# missing; the rows simply say less.
META_PATH = os.path.join(paths.STATE_DIR, 'windscribe-meta.json')

# What marks a config as Windscribe's, in the filename and nowhere else. The
# app decides two things from it - which credential to use, and whether a
# `200` can be believed - and one folder can hold either provider, so the
# answer has to travel with the file rather than with the folder.
MARK = '.ws.'

TIMEOUT = 30

# The two error codes worth telling apart from every other failure, because
# each has a different thing for the person to do about it.
RATE_LIMITED = 707        # wait; do not try again in a loop
CAPTCHA_REQUIRED = 708    # solve the slider


class ApiError(RuntimeError):
    """Something the API said no to, carrying its own numbering."""

    def __init__(self, message, code=None, description=None):
        super().__init__(message)
        self.code = code
        self.description = description


def _url(path, extra=None):
    """Where a request goes, and what identifies the client asking.

    All three of these live in the query string even on a POST, which is not
    a style choice - it is what the API checks. Leaving `platform` off gets
    "Unexpected client version. Please update your app.", which reads like a
    version problem and is really an identity one: with no platform there is
    no version for the account's client to be, and the request is refused
    before anything in the body is looked at.
    """
    now = str(int(time.time()))
    digest = hashlib.md5((SHARED_KEY + now).encode()).hexdigest()
    query = {'platform': PLATFORM, 'time': now, 'client_auth_hash': digest}
    query.update(extra or {})
    return f'{API}/{path}?{urllib.parse.urlencode(query)}'


class _NotJson(Exception):
    """A body Windscribe did not write, so nothing of theirs is in it."""

    def __init__(self, status, ctype):
        super().__init__(f'{status} {ctype}')
        self.status = status
        self.ctype = ctype


def _read(response):
    raw = response.read().decode('utf-8', 'replace')
    try:
        return json.loads(raw)
    except ValueError:
        raise _NotJson(getattr(response, 'status', None)
                       or getattr(response, 'code', None),
                       response.headers.get('Content-Type', ''))


def _blocked(e):
    """What to say about a page that came back instead of an answer.

    Always the same shape, and the thing to do about it is not guessable: a
    403 carrying HTML is Cloudflare refusing the line this app is running on,
    which is what an address in Iran gets. The API itself is fine - the same
    request, sent from an exit, answers with JSON - so the order is connect
    first and sign in second.

    "Windscribe answered with something that is not JSON" was true and sent
    people to look at their password, which is the one thing that was right.
    """
    if e.status == 403 and 'html' in (e.ctype or ''):
        return ApiError(
            'Windscribe never saw this - a block page came back instead of '
            'an answer. Their API refuses this line. Connect through '
            'Surfshark or your own tunnel first, then sign in.')
    return ApiError(
        f'Windscribe answered {e.status or "?"} with {e.ctype or "no type"} '
        'rather than an answer, so the request did not reach them.')


# Around whatever proxy this machine is set to, for the attempt that does not
# go through it. An opener with an empty ProxyHandler is how urllib is told
# "not through that".
_DIRECT = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _routes():
    """The ways out of this machine, best first.

    Through the proxy first when there is one, because when this app is
    connected that proxy is this app - so the request leaves through the
    tunnel and the censored line never sees api.windscribe.com. Then around
    it, because a proxy in the Windows settings is not a promise that
    anything is listening on it, and this app's own is down whenever it is
    not connected, which is most of the time.

    Same shape as surfshark._routes, and needed more here. That list is
    public and merely blocked; this is a login, and the line most likely to
    be running this app is the one Cloudflare turns away - measured: the
    login POST answers 403 with a Cloudflare page direct from here, and
    proper JSON through an exit.

    Neither route is the good one and the order is not a ranking. The server
    list measures the other way round on the same machine - 403 through the
    proxy, 200 direct - so what matters is that both are tried and the one
    that answers wins.
    """
    return [True, False] if urllib.request.getproxies() else [False]


def _call(url, payload=None):
    """One request, one attempt.

    The body is JSON, and deliberately not form-encoded. The extension posts
    `JSON.stringify(...)` through fetch with no content type of its own, so
    what the API actually receives is a JSON document labelled text/plain -
    and it parses the body itself rather than trusting the label. Sending
    form fields instead gets a request that arrives with none of its
    parameters.

    An HTTP error still carries a JSON body here - 403 is how both "solve the
    captcha" and "wrong password" arrive - so the body is parsed either way,
    and the error code inside it is what decides.
    """
    data = None
    headers = {'Accept': 'application/json',
               'User-Agent': USER_AGENT,
               'Origin': ORIGIN}
    if payload is not None:
        data = json.dumps(payload).encode()
        headers['Content-Type'] = 'text/plain;charset=UTF-8'

    body = None
    trouble = None
    for proxied in _routes():
        opener = urllib.request.urlopen if proxied else _DIRECT.open
        request = urllib.request.Request(url, data=data, headers=headers)
        try:
            try:
                with opener(request, timeout=TIMEOUT) as response:
                    body = _read(response)
            except urllib.error.HTTPError as e:
                body = _read(e)
        except _NotJson as e:
            # Not a retry of the login, which this file does not do. A body
            # Windscribe did not write is proof the request never reached
            # them, so there is no attempt on the account to repeat - and a
            # real refusal, wrong password included, arrives as JSON and
            # stops here on the first route.
            trouble = _blocked(e)
            continue
        except urllib.error.URLError as e:
            trouble = ApiError(f'Could not reach Windscribe: {e.reason}')
            continue
        except (OSError, ssl.SSLError) as e:
            trouble = ApiError(f'Could not reach Windscribe: {e}')
            continue
        break

    if body is None:
        raise trouble or ApiError('Could not reach Windscribe.')

    if isinstance(body, dict) and body.get('errorCode') is not None:
        code = body.get('errorCode')
        raise ApiError(body.get('errorMessage') or f'error {code}',
                       code, body.get('errorDescription'))
    return body


def _post(path, payload):
    return _call(_url(path), payload)


def _get(path, extra):
    return _call(_url(path, extra))


# -- the login, in two halves -------------------------------------------


def begin_login(username, password):
    """Ask for a secure token, and with it whatever the captcha is this time.

    The captcha does not come back attached to a failed login - a bare POST
    to /Session answers 708 with no puzzle in it, which is the wrong end to
    start from. It comes from here.

    The password goes with it. That is not what you would design, and it is
    what the extension does - and guessing differently from the client whose
    session type this is asking for is how the last three hours went. It is
    the same password the next call sends anyway; it is simply sent one step
    earlier than it looks like it needs to be.

    Returns the token and, when one is required, the puzzle: two base64 PNGs
    and the height to draw the piece at.
    """
    try:
        body = _post('AuthToken/login',
                     {'username': username, 'password': password})
    except ApiError as e:
        if e.code == RATE_LIMITED:
            raise ApiError(
                'Windscribe is rate-limiting this line. Wait a few minutes '
                'before trying again - repeated attempts escalate.',
                RATE_LIMITED)
        raise

    data = (body or {}).get('data') or {}
    token = data.get('token')
    if not token:
        raise ApiError('Windscribe returned no login token.')

    captcha = data.get('captcha')
    out = {'token': token, 'captcha': None}
    if captcha:
        if 'ascii_art' in captcha:
            out['captcha'] = {'kind': 'ascii', 'art': captcha['ascii_art']}
        else:
            out['captcha'] = {'kind': 'slider',
                              'background': captcha.get('background', ''),
                              'slider': captcha.get('slider', ''),
                              'top': int(captcha.get('top') or 0)}
    return out


def _signature(token):
    """What proves the token came from a client that knows the constant."""
    return hashlib.sha256((token + SIGNATURE_TOKEN).encode()).hexdigest()


def _trail(trail_x, trail_y):
    """The path the piece was dragged along, as the extension sends it.

    One object with two integer arrays, not a set of flattened
    `captcha_trail[x][0]` form fields - that is the desktop client's shape,
    and it is bound up with the desktop's form encoding rather than with what
    the API wants to receive.

    This is the half of the puzzle actually about being human: where the piece
    stopped is easy, how a hand got there is not. So the numbers are passed
    through from the drag that produced them and only rounded, which is what
    the extension does too.
    """
    return {'x': [int(round(float(v))) for v in (trail_x or [])],
            'y': [int(round(float(v))) for v in (trail_y or [])]}


def finish_login(username, password, token, solution,
                 trail_x=None, trail_y=None, code2fa=''):
    """The login itself, with the solved puzzle attached. One attempt.

    Returns the session_auth_hash - the long-lived half, and the only thing
    worth keeping. The password is a parameter here and then it is gone:
    nothing in this module writes it anywhere.
    """
    payload = {
        'username': username,
        'password': password,
        'session_type_id': SESSION_TYPE_EXTENSION,
        'secure_token': token,
        'secure_token_sig': _signature(token),
        'captcha_solution': str(solution),
        'captcha_trail': _trail(trail_x, trail_y),
    }
    if code2fa:
        payload['2fa_code'] = code2fa

    body = _post('Session', payload)
    data = (body or {}).get('data') or {}
    session_hash = data.get('session_auth_hash')
    if not session_hash:
        raise ApiError('Windscribe accepted the login but returned no session.')
    return {'session_auth_hash': session_hash,
            'username': data.get('username') or username,
            'premium': bool(data.get('is_premium')),
            'user_id': data.get('user_id')}


def proxy_credentials(session_auth_hash):
    """The credential the proxy actually takes.

    No captcha on this one, which is the whole reason the token is worth
    keeping: this is the call the app makes on its own, forever after the one
    login.
    """
    body = _get('ServerCredentials',
                {'session_auth_hash': session_auth_hash})
    data = (body or {}).get('data') or {}
    user, password = data.get('username'), data.get('password')
    if not user or not password:
        raise ApiError('Windscribe returned no proxy credentials.')
    # base64 in the JSON, both fields.
    return (base64.b64decode(user).decode('utf-8', 'replace'),
            base64.b64decode(password).decode('utf-8', 'replace'))


# -- what is kept on disk ------------------------------------------------


def _lock_down(path):
    """The treatment .ovpn-auth gets: take the inherited permissions off a
    file that holds a secret. chmod does nothing on NTFS."""
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


def _record():
    try:
        with open(TOKEN_FILE, encoding='utf-8') as f:
            got = json.load(f)
        return got if isinstance(got, dict) else {}
    except (OSError, ValueError):
        return {}


def _write(record):
    os.makedirs(paths.STATE_DIR, exist_ok=True)
    with open(TOKEN_FILE, 'w', encoding='utf-8') as f:
        json.dump(record, f, indent=1)
    _lock_down(TOKEN_FILE)
    return record


def save_token(session_auth_hash, username=None):
    # Merged rather than written over: the remembered password lives in this
    # same file, and a sign-in that replaced the whole of it would forget the
    # password every time it was used.
    record = _record()
    record.update({'session_auth_hash': session_auth_hash,
                   'username': username or record.get('username') or '',
                   'saved': int(time.time())})
    return _write(record)


def load_token():
    record = _record()
    return record if record.get('session_auth_hash') else None


# -- remembering the account password ------------------------------------
#
# Windows keeps a per-user key for exactly this, and it is the only kind of
# "encrypted" worth the word here. A password scrambled with a key sitting
# beside it in the same folder is not encrypted, it is encoded - anyone who
# can read the file can read the key. DPAPI's key belongs to the Windows
# account and never appears on disk, so the blob is worthless copied to
# another machine or opened by another user.
#
# The consequence is worth stating plainly: this survives a reinstall of the
# app and not a reinstall of Windows, and that is the correct trade.

class _Blob(ctypes.Structure):
    _fields_ = [('cbData', ctypes.c_ulong),
                ('pbData', ctypes.POINTER(ctypes.c_char))]


def _dpapi(raw, unprotect=False):
    """CryptProtectData / CryptUnprotectData, or None where there is none."""
    if os.name != 'nt':
        return None
    blob_in = _Blob(len(raw), ctypes.cast(ctypes.create_string_buffer(raw),
                                          ctypes.POINTER(ctypes.c_char)))
    blob_out = _Blob()
    crypt = ctypes.windll.crypt32
    call = crypt.CryptUnprotectData if unprotect else crypt.CryptProtectData
    args = ([ctypes.byref(blob_in), None, None, None, None, 0,
             ctypes.byref(blob_out)] if not unprotect else
            [ctypes.byref(blob_in), None, None, None, None, 0,
             ctypes.byref(blob_out)])
    if not call(*args):
        return None
    try:
        return ctypes.string_at(blob_out.pbData, blob_out.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(blob_out.pbData)


def save_account_password(password):
    """Keep the password only if it can be kept encrypted.

    Where DPAPI is not there, nothing is written at all. Falling back to
    plaintext because the good option was unavailable is how a file that
    looks protected turns out not to be, and the cost of not saving is that
    somebody types their password again.
    """
    if not password:
        return False
    sealed = _dpapi(password.encode('utf-8'))
    if sealed is None:
        return False
    record = _record()
    record['password'] = base64.b64encode(sealed).decode()
    _write(record)
    return True


def load_account_password():
    blob = _record().get('password')
    if not blob:
        return None
    try:
        opened = _dpapi(base64.b64decode(blob), unprotect=True)
    except (ValueError, OSError):
        return None
    return opened.decode('utf-8', 'replace') if opened else None


def have_account_password():
    return bool(_record().get('password'))


def forget():
    """Both halves. Leaving the token behind after "sign out" would mean the
    app could still fetch a credential for an account it says it has none
    for."""
    gone = []
    for path in (TOKEN_FILE, AUTH_FILE):
        try:
            os.remove(path)
            gone.append(path)
        except OSError:
            pass
    return gone


def save_proxy_credentials(user, password):
    """Two lines, the shape .ovpn-auth has, so that px.read_auth reads it
    without being told which provider wrote it."""
    os.makedirs(os.path.dirname(AUTH_FILE) or '.', exist_ok=True)
    with open(AUTH_FILE, 'w', encoding='utf-8', newline='') as f:
        f.write(f'{user}\n{password}\n')
    _lock_down(AUTH_FILE)
    return AUTH_FILE


def have_credentials():
    try:
        with open(AUTH_FILE, encoding='utf-8') as f:
            lines = [line.strip() for line in f if line.strip()]
        return len(lines) >= 2
    except OSError:
        return False


def refresh_credentials():
    """Token in, working credential out.

    The whole of what the app does on its own - and the call to make in a few
    days to answer the one open question in windscribe.md, which is how long
    the token lives.
    """
    record = load_token()
    if not record:
        raise ApiError('Not signed in to Windscribe.')
    user, password = proxy_credentials(record['session_auth_hash'])
    save_proxy_credentials(user, password)
    return user


# -- the fleet -----------------------------------------------------------


def _city_codes(pairs):
    """Three letters per city, because that is the shape the app's filenames
    already have and the country/city split is read straight out of them.

    Assigned over the whole list at once rather than per entry, which two
    things in the published list make necessary:

      The same city appears in several groups. Vienna is listed twice, New
      York four times. Handing the second one a different code would show as
      two cities in the catalogue and split "one per city" across both.

      One country arrives as several entries. The US is US Central, US East
      and US West, all with country_code US. Codes handed out per entry would
      reset between them, and two different cities could take the same one.

    Sorted, so the same fleet produces the same filenames every time - a
    second run that renamed everything would pin a duplicate of each exit.
    """
    out = {}
    taken = {}
    for country, city in sorted(pairs):
        seen = taken.setdefault(country, set())
        letters = re.sub(r'[^a-z]', '', (city or '').lower()) or 'xxx'
        code = (letters + 'xxx')[:3]
        if code in seen:
            for c in letters[3:] + 'abcdefghijklmnopqrstuvwxyz0123456789':
                alt = code[:2] + c
                if alt not in seen:
                    code = alt
                    break
        seen.add(code)
        out[(country, city)] = code
    return out


def servers(timeout=TIMEOUT):
    """Every exit Windscribe publishes, flattened.

    The list carries hostnames and no addresses, which is exactly the shape
    this repo is built for: resolving them honestly and pinning the result is
    machinery that already exists.
    """
    # Both ways out, for the reason in _routes, and measured here rather than
    # assumed: on the line this was written on the proxied request answers
    # 403 and the direct one answers 200 - the reverse of the login, which is
    # refused direct and answered through an exit. Neither route is the good
    # one; the one that answers is. Going through the system proxy alone,
    # which is all this did, is how signing in successfully was still
    # followed by "could not fetch the server list".
    #
    # A 403 arrives as HTTPError, which is an OSError, so it falls into the
    # same handler and moves on to the next route rather than ending here.
    trouble = None
    for proxied in _routes():
        opener = urllib.request.urlopen if proxied else _DIRECT.open
        request = urllib.request.Request(
            SERVER_LIST, headers={'Accept': 'application/json'})
        try:
            with opener(request, timeout=timeout) as response:
                body = json.loads(response.read().decode('utf-8', 'replace'))
            break
        except (OSError, ValueError, ssl.SSLError) as e:
            trouble = e
    else:
        raise ApiError('Could not fetch the Windscribe server list: '
                       f'{trouble}. If that is a 403, their API is refusing '
                       'this line - connect first, then fetch.')

    entries = []
    for country in body.get('data') or []:
        code = (country.get('country_code') or '').lower()
        if len(code) != 2:
            continue
        for group in country.get('groups') or []:
            entries.append((code, country, group,
                            group.get('city') or group.get('nick') or 'city'))
    codes = _city_codes({(code, city) for code, _c, _g, city in entries})

    out = []
    for code, country, group, city in entries:
        for host in group.get('hosts') or []:
            hostname = host.get('hostname')
            if not hostname:
                continue
            out.append({'country': code, 'city': city,
                        'code': codes[(code, city)],
                        'hostname': hostname,
                        # What the list says about the place rather than the
                        # address. `nick` is Windscribe's own name for it -
                        # every group has one - and `health` is how loaded
                        # it was when the list was fetched, which is the one
                        # number here that goes stale in minutes.
                        'nick': group.get('nick') or '',
                        'load': group.get('health'),
                        'gbps': 10 if str(group.get('link_speed')) == '10000'
                        else 1,
                        'p2p': bool(country.get('p2p')),
                        # The country's flag and not the group's. Every group
                        # in the list carries pro=1, including the ones in the
                        # thirteen countries a free account can reach, so
                        # reading it would mark the whole fleet premium and
                        # make free_only return nothing at all.
                        'premium': bool(country.get('premium_only')),
                        'health': host.get('health')})
    return out


def config_text(hostname):
    """The smallest thing the pinning script will accept and read a name out
    of.

    Nothing in here builds a tunnel - the app speaks to the proxy on 443
    directly and never runs OpenVPN against these - so a config is only ever
    somewhere to keep an address.
    """
    return ('# Windscribe exit, for pinning only - this app talks to the\n'
            '# proxy on 443 and never runs OpenVPN against it.\n'
            'client\n'
            'dev tun\n'
            'proto tcp\n'
            f'remote {hostname} 443\n')


def write_configs(dest=None, free_only=False, per_city=None):
    """Turn the published list into unpinned configs the rest of the app
    already understands.

    The filename is the interface. `us-dal.ws.<hostname>.ovpn` gives the
    catalogue its country and its city, and the `.ws.` in the middle is what
    later tells the app to use the Windscribe credential and to distrust a
    bare `200`. Pinning appends the address and keeps the rest.
    """
    dest = dest or CONFIG_DIR
    os.makedirs(dest, exist_ok=True)
    fleet = servers()
    if free_only:
        fleet = [s for s in fleet if not s['premium']]

    if per_city:
        capped, seen = [], {}
        for s in fleet:
            key = (s['country'], s['code'])
            if seen.get(key, 0) < per_city:
                seen[key] = seen.get(key, 0) + 1
                capped.append(s)
        fleet = capped

    written, note = [], {}
    for s in fleet:
        name = f"{s['country']}-{s['code']}{MARK}{s['hostname']}.ovpn"
        try:
            with open(os.path.join(dest, name), 'w',
                      encoding='utf-8', newline='\n') as f:
                f.write(config_text(s['hostname']))
            written.append(name)
            # Filed under the part of the name that survives pinning, so the
            # row still finds it after an address has been appended.
            note[config_stem(name)] = {
                'city': s['city'], 'nick': s['nick'], 'load': s['load'],
                'gbps': s['gbps'], 'p2p': s['p2p'],
                'premium': s['premium'], 'host': s['hostname']}
        except OSError:
            continue

    # Written whole and moved into place, and never fatal: a fleet on disk
    # with no notes beside it is a list that says less, not a broken one.
    try:
        os.makedirs(paths.STATE_DIR, exist_ok=True)
        tmp = f'{META_PATH}.{os.getpid()}'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(note, f)
        os.replace(tmp, META_PATH)
    except OSError:
        pass

    return {'folder': dest, 'written': len(written),
            'countries': len({s['country'] for s in fleet}),
            'total': len(fleet)}


# -- proving a tunnel actually carries -----------------------------------


def is_windscribe(name):
    """Whether a config filename came from here."""
    return MARK in (name or '')


# "01.8s-fr-par.ws.fr-030.totallyacdn.com_146.70.253.194.ovpn" is the same
# exit as "fr-par.ws.fr-030.totallyacdn.com.ovpn" was before it was pinned
# and before a sweep timed it. Both ends move - a measured time goes on the
# front, an address goes on the back - and what survives both is the middle.
STEM_HEAD = re.compile(r'^\d+\.\d+s-')
STEM_TAIL = re.compile(r'_\d{1,3}(?:\.\d{1,3}){3}\.ovpn$')


def config_stem(name):
    """The part of a config filename that survives pinning and sweeping.

    This is the key everything about an exit is filed under, because it is
    the only part of the name nothing rewrites.
    """
    name = STEM_HEAD.sub('', name or '')
    name = STEM_TAIL.sub('', name)
    return name[:-5] if name.endswith('.ovpn') else name


def meta():
    """What the published list said about each exit, by config stem."""
    try:
        with open(META_PATH, encoding='utf-8') as f:
            got = json.load(f)
        return got if isinstance(got, dict) else {}
    except (OSError, ValueError):
        return {}


def verify_tunnel(px, ip, host, user, password, timeout=15):
    """Whether this exit really tunnels, rather than merely answering.

    This is not the check Surfshark needs, and using that one here would be a
    quiet disaster. Surfshark refuses an unauthenticated CONNECT with 407, so
    a 200 means the credential was taken. Windscribe's nghttpx answers 200 to
    everybody and then forwards nothing for the ones it did not like - so a
    stale credential looks exactly like a working one, right up until every
    request through it hangs.

    So the question has to be asked with a whole request: connect, tunnel,
    fetch, and read an address back out. Slower, and the only version of this
    that means anything.
    """
    exit_ = px.Exit(ip, 443, host, user, password)
    status, _headers, body, ttfb = px.fetch(
        exit_, 'www.cloudflare.com', '/cdn-cgi/trace', timeout)
    if not 200 <= int(status) < 400:
        raise OSError(f'the exit answered {status} through the tunnel')
    seen = re.search(rb'^ip=(\S+)', body or b'', re.M)
    if not seen:
        raise OSError('the tunnel carried nothing back')
    where = re.search(rb'^loc=(\S+)', body or b'', re.M)
    return {'ttfb': ttfb, 'ip': seen.group(1).decode(),
            'loc': where.group(1).decode() if where else ''}
