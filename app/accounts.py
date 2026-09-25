"""More than one account, and one place to keep them.

Two providers arrived by different routes and ended up with two sign-in
boxes that did the same job badly: Surfshark's took a service credential and
wrote it to a file, Windscribe's ran a login and wrote a different file, and
neither knew the other existed. You could hold exactly one of each, and
swapping meant retyping.

So this file holds a roster instead. An account is a provider, a name to
recognise it by, and whatever that provider needs to work - and one of them
per provider is the active one.

How the active one reaches the rest of the app
----------------------------------------------
By being written to the file that was already there. `.ovpn-auth` and
`.windscribe-auth` keep their meaning exactly: the credential in use right
now. Activating an account rewrites its provider's file and nothing else
changes - not the engine, not the sweep scripts, not the PowerShell half,
none of which need to learn what an account is.

That is the whole reason this is a hundred lines rather than a refactor. The
roster is a thing on top; the app underneath still reads one file per
provider and does not know it is being switched.

Secrets
-------
Passwords and tokens are sealed with the Windows account's own key, the same
way the remembered Windscribe password is - see windscribe._dpapi. Where that
is not available they are not stored at all, and the account is kept without
them: a roster entry you have to retype a password into is worth having, and
a roster entry that leaked one is not.
"""

import json
import os
import tempfile
import time
import uuid

import paths
import windscribe

STORE = os.path.join(paths.STATE_DIR, 'accounts.json')

SURFSHARK = 'surfshark'
WINDSCRIBE = 'windscribe'
PROVIDERS = (SURFSHARK, WINDSCRIBE)

LABELS = {SURFSHARK: 'Surfshark', WINDSCRIBE: 'Windscribe'}


def _seal(value):
    """Encrypted, or absent. Never in the clear - see the module docstring."""
    if not value:
        return None
    import base64
    blob = windscribe._dpapi(value.encode('utf-8'))
    return base64.b64encode(blob).decode() if blob else None


def _open(sealed):
    if not sealed:
        return None
    import base64
    try:
        raw = windscribe._dpapi(base64.b64decode(sealed), unprotect=True)
    except (ValueError, OSError):
        return None
    return raw.decode('utf-8', 'replace') if raw else None


def load():
    try:
        with open(STORE, encoding='utf-8') as f:
            got = json.load(f)
    except PermissionError:
        # An older release could remove inherited ACLs before successfully
        # granting the current user access. Repair that file before reading;
        # treating it as an empty roster would discard existing accounts.
        if not windscribe._grant_current_user(STORE):
            raise
        with open(STORE, encoding='utf-8') as f:
            got = json.load(f)
    except (FileNotFoundError, ValueError):
        got = {}
    if not isinstance(got, dict):
        got = {}
    got.setdefault('accounts', [])
    got.setdefault('active', {})
    return got


def save(state):
    folder = os.path.dirname(STORE)
    os.makedirs(folder, exist_ok=True)
    temporary = None
    try:
        fd, temporary = tempfile.mkstemp(prefix='.accounts-', dir=folder)
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            json.dump(state, f, indent=1)
        windscribe._lock_down(temporary)
        try:
            os.replace(temporary, STORE)
        except PermissionError:
            # Replacing a file normally needs only access to its directory,
            # but some ACLs also require access to the old file itself.
            if not windscribe._grant_current_user(STORE):
                raise
            os.replace(temporary, STORE)
        temporary = None
    finally:
        if temporary and os.path.exists(temporary):
            os.remove(temporary)
    return state


def adopt(engine_):
    """Take whatever was already signed in and make accounts of it.

    Run once, on the first look at the roster. Someone who has been using
    this app should not open the new pane and be told they have no accounts
    while both providers are plainly connected - so what is on disk becomes
    the first two entries, and stays active.
    """
    state = load()
    if state['accounts'] or state.get('adopted'):
        return state

    try:
        user = engine_.username()
    except Exception:
        user = None
    if user:
        state['accounts'].append({
            'id': uuid.uuid4().hex[:8], 'provider': SURFSHARK,
            'label': user, 'username': user, 'password': None,
            'added': int(time.time())})
        state['active'][SURFSHARK] = state['accounts'][-1]['id']

    record = windscribe.load_token()
    if record:
        state['accounts'].append({
            'id': uuid.uuid4().hex[:8], 'provider': WINDSCRIBE,
            'label': record.get('username') or 'Windscribe',
            'username': record.get('username') or '',
            'password': record.get('password'),      # already sealed
            'session': record.get('session_auth_hash'),
            'added': int(time.time())})
        state['active'][WINDSCRIBE] = state['accounts'][-1]['id']

    state['adopted'] = True
    return save(state)


def _usable(account, is_active):
    """Whether this account can actually connect something.

    Not the same as "has a password in the roster". An account adopted from
    a credential file that was here before any of this existed has no
    password stored - there was never anywhere to have stored one - and it
    works perfectly. Reading it as unusable would have the pane report
    nothing is set up while the app is plainly connected through it.
    """
    if account['provider'] == WINDSCRIBE:
        return bool(account.get('session'))
    if account.get('password'):
        return True
    return is_active and os.path.isfile(paths.AUTH_FILE)


def listing():
    """The roster, with nothing secret in it - this is what the page sees."""
    state = load()
    out = []
    for a in state['accounts']:
        is_active = state['active'].get(a['provider']) == a['id']
        out.append({
            'id': a['id'],
            'provider': a['provider'],
            'providerName': LABELS.get(a['provider'], a['provider']),
            'label': a.get('label') or a.get('username') or 'account',
            'username': a.get('username') or '',
            'active': is_active,
            'hasPassword': bool(a.get('password')),
            'signedIn': _usable(a, is_active),
            'added': a.get('added'),
        })
    out.sort(key=lambda a: (a['provider'], a['label'].lower()))
    return out


def find(account_id):
    for a in load()['accounts']:
        if a['id'] == account_id:
            return a
    return None


def _replace(account):
    state = load()
    for i, a in enumerate(state['accounts']):
        if a['id'] == account['id']:
            state['accounts'][i] = account
            break
    else:
        state['accounts'].append(account)
    return save(state)


def put(provider, label, username, password=None, session=None,
        proxy=None, account_id=None):
    """Add an account, or update the one with this id.

    Matched on username within a provider when no id is given, so signing in
    to the same account twice corrects it rather than listing it twice.
    """
    state = load()
    found = None
    for a in state['accounts']:
        if account_id and a['id'] == account_id:
            found = a
            break
        if (not account_id and a['provider'] == provider
                and (a.get('username') or '').lower() == (username or '').lower()):
            found = a
            break

    if found is None:
        found = {'id': uuid.uuid4().hex[:8], 'provider': provider,
                 'added': int(time.time())}
        state['accounts'].append(found)

    found['label'] = label or username or LABELS.get(provider, provider)
    found['username'] = username or ''
    if password is not None:
        found['password'] = _seal(password)
    if session is not None:
        found['session'] = session
    if proxy is not None:
        found['proxy'] = [_seal(proxy[0]), _seal(proxy[1])]
    state['active'][provider] = found['id']
    save(state)
    return found


def secrets(account_id):
    """The unsealed contents of one account, for the moment it is used."""
    a = find(account_id)
    if not a:
        return None
    proxy = a.get('proxy') or [None, None]
    return {'provider': a['provider'],
            'username': a.get('username') or '',
            'password': _open(a.get('password')),
            'session': a.get('session'),
            'proxy': (_open(proxy[0]), _open(proxy[1]))}


def activate(account_id):
    """Make this the account its provider uses from now on.

    Only the marker moves here. Writing the credential file is the caller's
    job, because the two providers write different files in different ways -
    and one of them keeps .env in step, which is the sweep scripts' copy.
    """
    a = find(account_id)
    if not a:
        return None
    state = load()
    state['active'][a['provider']] = a['id']
    save(state)
    return a


def remove(account_id):
    state = load()
    gone = [a for a in state['accounts'] if a['id'] == account_id]
    if not gone:
        return None
    state['accounts'] = [a for a in state['accounts'] if a['id'] != account_id]
    provider = gone[0]['provider']
    if state['active'].get(provider) == account_id:
        # Hand the marker to whatever is left of that provider rather than
        # leaving it pointing at nothing - and if nothing is left, say so by
        # dropping it, which is what "no account" already looks like.
        rest = [a for a in state['accounts'] if a['provider'] == provider]
        if rest:
            state['active'][provider] = rest[0]['id']
        else:
            state['active'].pop(provider, None)
    save(state)
    return gone[0]


def active(provider):
    state = load()
    wanted = state['active'].get(provider)
    for a in state['accounts']:
        if a['id'] == wanted:
            return a
    return None
