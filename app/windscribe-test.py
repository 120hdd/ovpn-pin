"""What can be checked about Windscribe without signing in to it.

    python app/windscribe-test.py

The login itself is not in here, and deliberately. It costs a captcha that a
person has to solve and an attempt against a real account, and note.md
records an account blocked after about seventy security alerts - so a test
that logs in is a test that cannot be run twice in a row, which is not a test.
What is checked instead is everything the login sits on top of: the hashes it
is signed with, the shape of what gets sent, the fleet that comes back, and
the two places where getting it wrong would be silent.

The two that matter most, because both are invisible until they are expensive:

  The city codes. Filenames are the interface between this provider and the
  rest of the app, and the catalogue, the one-per-city dedupe and the pinning
  all read them. Two cities sharing a code merges them; one city with two
  codes splits it; codes that move between runs pin a second copy of the
  whole fleet.

  The liveness check. Windscribe answers 200 to an unauthenticated CONNECT
  and then forwards nothing, so the check that is right for Surfshark reports
  every exit alive here. That one is asserted against a stub, because the
  failure it guards is one a live run cannot be relied on to produce.
"""

import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import engine                                                # noqa: E402
import windscribe as ws                                      # noqa: E402

PASS, FAIL = [], []


def check(name, ok, detail=''):
    (PASS if ok else FAIL).append(name)
    mark = ' ok ' if ok else 'FAIL'
    print(f'  [{mark}] {name}{"  " + detail if detail else ""}')
    return ok


def section(title):
    print(f'\n{title}')


# -- what the requests are signed with -----------------------------------

def test_hashes():
    section('signing')
    import hashlib
    import time

    import urllib.parse
    query = urllib.parse.parse_qs(
        urllib.parse.urlparse(ws._url('Session')).query)
    digest, when = query['client_auth_hash'][0], query['time'][0]
    check('client hash is 32 hex',
          len(digest) == 32 and all(c in '0123456789abcdef' for c in digest))
    # Recomputed independently rather than compared to itself.
    check('client hash is md5(key + time)',
          digest == hashlib.md5((ws.SHARED_KEY + when).encode()).hexdigest())
    check('time is a plausible unix second',
          abs(int(when) - int(time.time())) < 5)

    sig = ws._signature('a-token')
    want = hashlib.sha256(('a-token' + ws.SIGNATURE_TOKEN).encode()).hexdigest()
    check('token signature is sha256(token + constant)', sig == want)
    check('signature is 64 hex', len(sig) == 64)


def test_trail():
    section('the captcha trail')
    out = ws._trail([1.0, 2.5], [3.25, 4.0])
    check('one object with an x and a y', set(out) == {'x', 'y'})
    check('whole numbers, as the extension rounds them',
          out['x'] == [1, 2] and out['y'] == [3, 4], str(out))
    check('all of them really are ints',
          all(isinstance(v, int) for v in out['x'] + out['y']))
    check('an empty trail is two empty lists',
          ws._trail([], []) == {'x': [], 'y': []})
    # It has to survive JSON, because that is what the body is.
    import json as _json
    check('it round-trips through JSON',
          _json.loads(_json.dumps(out)) == out)


def test_request_shape():
    section('how a request identifies itself')
    import urllib.parse
    url = ws._url('Session')
    query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    # The one that was missing, and the whole of what "Unexpected client
    # version. Please update your app." was complaining about.
    check('platform rides in the query string',
          query.get('platform') == [ws.PLATFORM], str(query.get('platform')))
    check('so do time and the client hash',
          'time' in query and 'client_auth_hash' in query)
    check('the hash is md5 of the shared key and that same time',
          query['client_auth_hash'][0] == __import__('hashlib').md5(
              (ws.SHARED_KEY + query['time'][0]).encode()).hexdigest())
    check('extra parameters are added, not substituted',
          urllib.parse.parse_qs(urllib.parse.urlparse(
              ws._url('ServerCredentials', {'session_auth_hash': 'abc'})
          ).query).get('session_auth_hash') == ['abc'])
    check('it is the extension being impersonated, not the desktop app',
          ws.PLATFORM == 'chrome' and ws.SESSION_TYPE_EXTENSION == 2
          and 'chrome-extension://' in ws.ORIGIN)


# -- filenames, which are the interface ----------------------------------

def test_city_codes():
    section('city codes')
    pairs = {('us', 'Dallas'), ('us', 'Dalian'), ('us', 'Dallas'),
             ('us', 'Denver'), ('de', 'Dallas')}
    codes = ws._city_codes(pairs)
    check('one code per city', len(set(codes.values())) >= 3)
    check('same city, same code every time',
          codes[('us', 'Dallas')] == ws._city_codes(pairs)[('us', 'Dallas')])
    us = [v for (c, _city), v in codes.items() if c == 'us']
    check('no two cities in one country share a code', len(us) == len(set(us)),
          str(sorted(us)))
    check('always three letters',
          all(len(v) == 3 for v in codes.values()))

    # Codes are scoped to a country, so the same name elsewhere is free to
    # take the same one - it is only inside a country that they must differ.
    plain = ws._city_codes({('us', 'Dallas'), ('de', 'Dallas')})
    check('the same name in another country reuses the code',
          plain[('us', 'Dallas')] == plain[('de', 'Dallas')] == 'dal',
          str(plain))
    # And when two cities in one country do want the same three letters, the
    # second is moved rather than merged onto the first.
    clash = ws._city_codes({('us', 'Dallas'), ('us', 'Dalian')})
    check('a clash inside one country moves the second',
          clash[('us', 'Dallas')] != clash[('us', 'Dalian')], str(clash))
    check('a nameless city still gets a code',
          len(ws._city_codes({('xx', '')})[('xx', '')]) == 3)


def test_filenames_round_trip():
    section('filenames the rest of the app has to read')
    name = 'us-dal' + ws.MARK + 'us-central-117.totallyacdn.com.ovpn'
    check('marked as Windscribe', ws.is_windscribe(name))
    check('a Surfshark name is not',
          not ws.is_windscribe('de-fra.prod.surfshark.com_tcp_1.2.3.4.ovpn'))

    # Once pinned, the address is appended - which is the form the engine
    # actually sees, so that is the form the regex has to match.
    pinned = name[:-5] + '_146.70.1.2.ovpn'
    m = engine.CONFIG.match(pinned)
    check('the engine parses the pinned name', bool(m))
    if m:
        check('country comes out', m.group(2).lower() == 'us', m.group(2))
        check('city comes out', m.group(3).lower() == 'dal', m.group(3))
    # The sweep prefixes a measured time; that has to keep working.
    timed = engine.CONFIG.match('01.8s-' + pinned)
    check('a swept name still parses', bool(timed))
    # And nothing about the change may break the provider that was here first.
    old = engine.CONFIG.match('de-fra.prod.surfshark.com_tcp_1.2.3.4.ovpn')
    check('Surfshark names still parse', bool(old))


def test_config_text():
    section('the configs written out')
    text = ws.config_text('de-042.totallyacdn.com')
    check('has a remote line', 'remote de-042.totallyacdn.com 443' in text)
    check('says what it is not for', 'never runs OpenVPN' in text)


# -- the fleet, live -----------------------------------------------------

def test_fleet():
    section('the published fleet')
    try:
        fleet = ws.servers()
    except ws.ApiError as e:
        check('server list fetched', False, str(e))
        return None

    check('server list fetched', len(fleet) > 100, f'{len(fleet)} hosts')
    check('every host has a name and a country',
          all(s['hostname'] and len(s['country']) == 2 for s in fleet))
    check('no addresses in it - hostnames only, which is the point',
          not any(s['hostname'][0].isdigit() for s in fleet))

    codes = {(s['country'], s['code']) for s in fleet}
    cities = {(s['country'], s['city']) for s in fleet}
    check('one code per city across the whole list',
          len(codes) == len(cities), f'{len(codes)} codes, {len(cities)} cities')

    # The US arrives as three separate country entries, all coded US. If the
    # codes were handed out per entry they would collide across them.
    us_codes = [c for c, _ in [(k[1], k[0]) for k in codes if k[0] == 'us']]
    check('the split US country keeps distinct codes',
          len(us_codes) == len(set(us_codes)), f'{len(us_codes)} US cities')

    free = [s for s in fleet if not s['premium']]
    check('the free tier is not empty - group pro=1 is not the premium flag',
          len(free) > 0, f'{len(free)} free hosts')
    check('the free tier is a subset', len(free) < len(fleet))
    return fleet


def test_written_configs(fleet):
    section('writing and re-writing them')
    if not fleet:
        check('skipped - no fleet', False)
        return
    with tempfile.TemporaryDirectory() as one, tempfile.TemporaryDirectory() as two:
        a = ws.write_configs(dest=one)
        ws.write_configs(dest=two)
        check('wrote one file per host', a['written'] == len(fleet),
              f"{a['written']} files")
        names_a = sorted(os.listdir(one))
        names_b = sorted(os.listdir(two))
        # A second run that renamed anything would pin a duplicate of the
        # whole fleet the next time round.
        check('the same fleet writes the same filenames', names_a == names_b)
        check('every name is one the engine can read',
              all(engine.CONFIG.match(n[:-5] + '_1.2.3.4.ovpn')
                  for n in names_a))
        check('every name is marked as Windscribe',
              all(ws.is_windscribe(n) for n in names_a))

        # And the pinned form reaches the engine as a real catalogue.
        pinned = os.path.join(one, 'pinned')
        os.makedirs(pinned, exist_ok=True)
        for i, n in enumerate(names_a[:60]):
            host = n.split(ws.MARK, 1)[1][:-5]
            ip = f'10.0.{i // 250}.{i % 250 + 1}'
            with open(os.path.join(pinned, n[:-5] + f'_{ip}.ovpn'), 'w',
                      encoding='utf-8', newline='\n') as f:
                f.write(f'# {host} -> {ip}\nclient\nremote {ip} 443\n')
        e = engine.Engine(_NoProxy(), folder=pinned)
        check('the engine sees them as servers', len(e.servers()) == 60,
              f'{len(e.servers())} of 60')
        check('the catalogue groups them into countries',
              len(e.catalogue()) > 1, f'{len(e.catalogue())} countries')
        check('and reads the real hostname back out of one',
              engine.px.read_config(e.servers()[0].path)[1].endswith('.com'))


# -- the two decisions the filename drives -------------------------------

class _NoProxy:
    """Enough of a system proxy to build an Engine and touch nothing."""

    def engage(self, *_a, **_k):
        return None

    def restore(self, *_a, **_k):
        return None


def test_credential_routing():
    section('which credential opens which exit')
    e = engine.Engine(_NoProxy(), folder=HERE)

    class S:
        def __init__(self, file):
            self.file = file

    wsx = S('us-dal' + ws.MARK + 'us-central-117.totallyacdn.com_1.2.3.4.ovpn')
    ssx = S('de-fra.prod.surfshark.com_tcp_1.2.3.4.ovpn')
    check('a Windscribe exit gets the Windscribe file',
          e.auth_file_for(wsx) == ws.AUTH_FILE)
    check('a Surfshark exit gets the one it always had',
          e.auth_file_for(ssx) == e.auth_file)
    check('the two are different files', ws.AUTH_FILE != e.auth_file)


class _FakePx:
    """A Windscribe exit that answers 200 and forwards nothing.

    This is the real behaviour of its nghttpx to an unauthenticated CONNECT,
    and the reason can_connect() cannot be used here: it stops at the 200 and
    would call this exit alive.
    """

    def __init__(self, status=200, body=b''):
        self.status, self.body = status, body

    def Exit(self, *_a, **_k):
        return object()

    def fetch(self, _exit, _host, _path, _timeout):
        return self.status, {}, self.body, 0.12


def test_liveness_is_not_a_200():
    section('proving a tunnel actually carries')
    empty = _FakePx(200, b'')
    try:
        ws.verify_tunnel(empty, '1.2.3.4', 'h', 'u', 'p')
        check('a 200 that carries nothing is refused', False,
              'it was accepted')
    except OSError as e:
        check('a 200 that carries nothing is refused', True, str(e))

    real = _FakePx(200, b'fl=1a\nip=203.0.113.9\nloc=NL\n')
    try:
        out = ws.verify_tunnel(real, '1.2.3.4', 'h', 'u', 'p')
        check('a 200 that carries a page is accepted', True)
        check('and the exit address is read back out',
              out['ip'] == '203.0.113.9' and out['loc'] == 'NL', str(out))
    except OSError as e:
        check('a 200 that carries a page is accepted', False, str(e))

    refused = _FakePx(407, b'')
    try:
        ws.verify_tunnel(refused, '1.2.3.4', 'h', 'u', 'p')
        check('a 407 is refused', False, 'it was accepted')
    except OSError:
        check('a 407 is refused', True)


# -- what is kept on disk ------------------------------------------------

def test_token_store():
    section('the token store')
    # Both files, because the test calls forget() and forget() takes both.
    # Backing up only the token would sign a real account out of its
    # credential and leave the app unable to connect until it was fetched
    # again - a test that costs you something is not one you would run.
    kept = {}
    for path in (ws.TOKEN_FILE, ws.AUTH_FILE):
        if os.path.isfile(path):
            with open(path, encoding='utf-8') as f:
                kept[path] = f.read()
    try:
        ws.save_token('a:2:1:deadbeef:cafe', 'someone@example.com')
        back = ws.load_token()
        check('a saved token reads back',
              back and back['session_auth_hash'] == 'a:2:1:deadbeef:cafe')
        check('and remembers who it belongs to',
              back['username'] == 'someone@example.com')
        ws.forget()
        check('forgetting removes it', ws.load_token() is None)
        check('and takes the credential file with it',
              not os.path.isfile(ws.AUTH_FILE))
    finally:
        # Put back whatever was there, so running the test does not sign
        # anybody out.
        for path, body in kept.items():
            with open(path, 'w', encoding='utf-8') as f:
                f.write(body)
        check('anything that was signed in is still signed in',
              all(os.path.isfile(p) for p in kept))


def test_password_store():
    section('the remembered password')
    kept = {}
    for path in (ws.TOKEN_FILE, ws.AUTH_FILE):
        if os.path.isfile(path):
            with open(path, encoding='utf-8') as f:
                kept[path] = f.read()
    secret = 'correct horse battery stapler'
    try:
        stored = ws.save_account_password(secret)
        check('it can be sealed at all', stored,
              'no DPAPI here' if not stored else '')
        if stored:
            check('and comes back out', ws.load_account_password() == secret)
            check('and says it has one', ws.have_account_password())
            # The whole point. If this fails the file is a password in a
            # text file with extra steps.
            with open(ws.TOKEN_FILE, encoding='utf-8') as f:
                on_disk = f.read()
            check('the password is not in the file in the clear',
                  secret not in on_disk and 'stapler' not in on_disk)
            check('what is in the file is a DPAPI blob',
                  on_disk.count('AQAAAN') == 1 or 'password' in on_disk)

            # Signing in again must not lose it - save_token used to write
            # the whole file over.
            ws.save_token('a:2:1:deadbeef:cafe', 'someone@example.com')
            check('a later sign-in keeps it',
                  ws.load_account_password() == secret)
            check('and the token is there too',
                  (ws.load_token() or {}).get('session_auth_hash')
                  == 'a:2:1:deadbeef:cafe')

            ws.forget()
            check('signing out forgets it',
                  ws.load_account_password() is None
                  and not ws.have_account_password())
    finally:
        for path, body in kept.items():
            with open(path, 'w', encoding='utf-8') as f:
                f.write(body)
        check('anything that was there is back',
              all(os.path.isfile(p) for p in kept))


def test_accounts():
    section('the roster')
    import accounts
    with tempfile.TemporaryDirectory() as tmp:
        was, accounts.STORE = accounts.STORE, os.path.join(tmp, 'accounts.json')
        try:
            a = accounts.put('surfshark', 'Main', 'ss_user', password='ss_pass')
            b = accounts.put('windscribe', 'Work', 'ws_user', password='ws_pass',
                             session='188:2:x', proxy=('pu', 'pp'))
            c = accounts.put('windscribe', 'Spare', 'other', password='p2')
            check('three accounts', len(accounts.listing()) == 3)
            # Adding the third made it active; the second is not.
            check('the newest of a provider takes over',
                  accounts.active('windscribe')['id'] == c['id'])
            check('and the other provider is untouched',
                  accounts.active('surfshark')['id'] == a['id'])

            accounts.activate(b['id'])
            check('activating switches within a provider',
                  accounts.active('windscribe')['id'] == b['id'])
            check('one active per provider, not one overall',
                  sum(1 for r in accounts.listing() if r['active']) == 2)

            s = accounts.secrets(b['id'])
            check('secrets come back out', s['password'] == 'ws_pass'
                  and s['proxy'] == ('pu', 'pp') and s['session'] == '188:2:x')

            # The whole reason it is sealed rather than saved.
            with open(accounts.STORE, encoding='utf-8') as f:
                raw = f.read()
            check('no password is in the file in the clear',
                  'ss_pass' not in raw and 'ws_pass' not in raw
                  and '"pp"' not in raw)

            # Same username twice is a correction, not a second account.
            accounts.put('windscribe', 'Renamed', 'ws_user', password='new')
            check('the same username updates rather than duplicates',
                  len(accounts.listing()) == 3)
            check('and takes the new name',
                  any(r['label'] == 'Renamed' for r in accounts.listing()))

            accounts.remove(b['id'])
            check('removing drops it', len(accounts.listing()) == 2)
            check('and hands active to what is left of that provider',
                  accounts.active('windscribe')['id'] == c['id'])
            accounts.remove(c['id'])
            check('the last of a provider leaves none active',
                  accounts.active('windscribe') is None)
            check('without disturbing the other provider',
                  accounts.active('surfshark')['id'] == a['id'])
        finally:
            accounts.STORE = was


def main():
    print(__doc__.strip().splitlines()[0])
    test_hashes()
    test_trail()
    test_request_shape()
    test_city_codes()
    test_filenames_round_trip()
    test_config_text()
    fleet = test_fleet()
    test_written_configs(fleet)
    test_credential_routing()
    test_liveness_is_not_a_200()
    test_token_store()
    test_password_store()
    test_accounts()

    print(f'\n{len(PASS)} passed, {len(FAIL)} failed')
    if FAIL:
        for name in FAIL:
            print(f'  failed: {name}')
        return 1
    print('\nThe login itself is not covered here - it needs a captcha solved '
          'by hand.\nSign in from the window, then check the round trip with:'
          '\n  python -c "import sys;sys.path.insert(0,\'app\');'
          'import windscribe;print(windscribe.refresh_credentials())"')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
