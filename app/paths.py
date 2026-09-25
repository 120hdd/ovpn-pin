"""Where things are, which is two different questions once this is frozen.

Running from the repo, everything is in one tree and none of this matters.
Bundled, it splits in two and confusing them breaks the app in ways that only
show up on the user's machine:

  resources   the page, the fonts, ovpn-proxy.py. Read-only, and inside
              _internal/ next to the exe. PyInstaller reports this as
              sys._MEIPASS.

  data        the .ovpn files, credentials, state and logs. In a frozen build
              these belong to the Windows user under LocalAppData. The exe
              may be under Program Files, where a normal user cannot write.

The proxy worker is the other trap. Frozen, sys.executable is this app, so
launching it with a script path would start a second copy of the window
instead of a proxy. Hence WORKER_FLAG: the exe re-launches itself with it and
becomes the proxy for that run.
"""

import json
import os
import shutil
import sys
import tempfile

FROZEN = bool(getattr(sys, 'frozen', False))
WORKER_FLAG = '--proxy-worker'

if FROZEN:
    APP_DIR = os.path.dirname(os.path.abspath(sys.executable))
    RES_DIR = getattr(sys, '_MEIPASS', APP_DIR)
    DATA_DIR = os.path.join(
        os.environ.get('LOCALAPPDATA') or
        os.path.join(os.path.expanduser('~'), 'AppData', 'Local'), 'Relay')
    PROXY_PY = os.path.join(RES_DIR, 'ovpn-proxy.py')
else:
    APP_DIR = os.path.dirname(os.path.abspath(__file__))
    RES_DIR = APP_DIR
    DATA_DIR = os.path.dirname(APP_DIR)
    PROXY_PY = os.path.join(DATA_DIR, 'core', 'ovpn-proxy.py')

UI_DIR = os.path.join(RES_DIR, 'ui')
ICON = os.path.join(RES_DIR, 'assets', 'app.ico')
ICON_PNG = os.path.join(RES_DIR, 'assets', 'app.png')
STATE_DIR = os.path.join(DATA_DIR, '.state')
AUTH_FILE = os.path.join(DATA_DIR, '.ovpn-auth')
SAVED_PROXY = os.path.join(STATE_DIR, 'system-proxy-before.json')

# The tunnel client and installer are shipped beside the exe. Its mutable
# configuration and log live under TUNNEL_STATE, in the user's data folder.
# PATH last, so a developer who already has one does not need a second copy.
TUNNEL_DIR = APP_DIR if FROZEN else os.path.join(DATA_DIR, 'tunnel')
TUNNEL_STATE = os.path.join(STATE_DIR, 'tunnel')


INSTALLER = os.path.join(TUNNEL_DIR, 'install-server.sh')


def gost_exe():
    """The tunnel client binary, or None if there is not one to run."""
    name = 'gost.exe' if os.name == 'nt' else 'gost'
    for folder in (DATA_DIR, TUNNEL_DIR):
        path = os.path.join(folder, name)
        if os.path.isfile(path):
            return path
    for folder in os.environ.get('PATH', '').split(os.pathsep):
        path = os.path.join(folder.strip('"'), name)
        if os.path.isfile(path):
            return path
    return None


# The PowerShell half. In the repo it is in windows/, with the rest of the
# Windows side. The build copies both scripts out of there and puts them
# beside the exe rather than inside _internal. Both scripts are read-only;
# the app passes their writable folders and state path explicitly.
SCRIPTS_DIR = APP_DIR if FROZEN else os.path.join(DATA_DIR, 'windows')

# servers/ first, because that is what a shipped copy is meant to carry. The
# other two are what the repo calls them, so a developer running from source
# gets the same app without moving anything.
SERVER_DIRS = ('servers', 'success', 'pinned')

# Where an exit goes when it is taken out of the list, rather than nowhere.
# The sweep used to delete these outright, which is a fine thing to do to a
# copy and the wrong thing to do to the only one - and either way it left the
# person with no way of disagreeing after the fact. One folder per source
# folder inside it, so putting one back is a move to a path that was written
# down rather than a guess at where it came from.
DROPPED = 'dropped'


def dropped_dir():
    return os.path.join(DATA_DIR, DROPPED)


DROPPED_INDEX = os.path.join(STATE_DIR, 'dropped.json')


def prepare_data():
    """Seed per-user storage from an older portable build without overwriting it.

    The release ZIP holds starter configs under the executable. Older Relay
    versions also wrote user state there. Both are copied on first use, and
    new starter names can be added by later releases. A user's existing files
    always win, including DPAPI-sealed accounts from this Windows account.
    """
    if not FROZEN:
        return DATA_DIR

    def copy_missing(source, target):
        if not os.path.isfile(source) or os.path.exists(target):
            return False
        parent = os.path.dirname(target)
        os.makedirs(parent, exist_ok=True)
        # Complete the copy before its final name becomes visible. A crash or
        # second instance must not leave a half-written accounts.json that
        # the next launch mistakes for an already migrated one.
        tmp = None
        try:
            with open(source, 'rb') as src:
                fd, tmp = tempfile.mkstemp(prefix='.relay-import-', dir=parent)
                with os.fdopen(fd, 'wb') as dst:
                    shutil.copyfileobj(src, dst)
            try:
                os.rename(tmp, target)  # On Windows this refuses an existing file.
            except FileExistsError:
                return False
        finally:
            if tmp and os.path.exists(tmp):
                os.remove(tmp)
        return True

    def moved_path(value):
        if not isinstance(value, str) or not os.path.isabs(value):
            return value
        old = os.path.normcase(os.path.abspath(APP_DIR))
        here = os.path.normcase(os.path.abspath(value))
        try:
            inside = os.path.commonpath((old, here)) == old
        except ValueError:
            inside = False
        if not inside:
            return value
        return os.path.join(DATA_DIR, os.path.relpath(value, APP_DIR))

    def rewrite_json(name, change):
        target = os.path.join(STATE_DIR, name)
        try:
            with open(target, encoding='utf-8') as f:
                record = json.load(f)
            if not change(record):
                return
            tmp = target + '.migrating'
            with open(tmp, 'w', encoding='utf-8') as f:
                json.dump(record, f, indent=2)
            os.replace(tmp, target)
        except (OSError, ValueError, TypeError):
            # The original is still available beside the exe for recovery.
            return

    def move_settings(record):
        if not isinstance(record, dict):
            return False
        changed = False
        for key in ('folder', 'pinFolder'):
            before = record.get(key)
            after = moved_path(before)
            if after != before:
                record[key] = after
                changed = True
        before = record.get('source')
        if isinstance(before, str) and before.startswith('folder:'):
            after = 'folder:' + moved_path(before[7:])
            if after != before:
                record['source'] = after
                changed = True
        return changed

    def move_dropped(record):
        if not isinstance(record, dict):
            return False
        changed = False
        for row in record.values():
            if isinstance(row, dict) and 'from' in row:
                before = row['from']
                after = moved_path(before)
                if after != before:
                    row['from'] = after
                    changed = True
        return changed

    def copy_tree_missing(name):
        source = os.path.join(APP_DIR, name)
        if not os.path.isdir(source):
            return
        for root, dirs, files in os.walk(source):
            dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(root, d))]
            target_dir = os.path.join(DATA_DIR, os.path.relpath(root, APP_DIR))
            os.makedirs(target_dir, exist_ok=True)
            for file in files:
                if os.path.islink(os.path.join(root, file)):
                    continue
                copy_missing(os.path.join(root, file), os.path.join(target_dir, file))

    os.makedirs(STATE_DIR, exist_ok=True)
    for name in ('.ovpn-auth', '.windscribe-auth', '.env'):
        copy_missing(os.path.join(APP_DIR, name), os.path.join(DATA_DIR, name))
    for name in ('servers', 'configs', 'windscribe', 'pinned', 'success',
                 'dropped', 'sitetest'):
        copy_tree_missing(name)
    # The old state directory can contain settings and encrypted passwords.
    # Process and system-proxy recovery records refer to the old installation
    # and must not be imported as if they belonged to this new data root.
    old_state = os.path.join(APP_DIR, '.state')
    if os.path.isdir(old_state):
        for root, dirs, files in os.walk(old_state):
            dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(root, d))]
            target_dir = os.path.join(STATE_DIR, os.path.relpath(root, old_state))
            os.makedirs(target_dir, exist_ok=True)
            for file in files:
                if root == old_state and file in (
                        'proxy.state', 'system-proxy-before.json'):
                    continue
                if os.path.islink(os.path.join(root, file)):
                    continue
                copied = copy_missing(os.path.join(root, file),
                                      os.path.join(target_dir, file))
                if copied and root == old_state and file == 'settings.json':
                    rewrite_json(file, move_settings)
                if copied and root == old_state and file == 'dropped.json':
                    rewrite_json(file, move_dropped)
    for name in ('servers', 'configs'):
        os.makedirs(os.path.join(DATA_DIR, name), exist_ok=True)
    return DATA_DIR


def servers_dir():
    for name in SERVER_DIRS:
        path = os.path.join(DATA_DIR, name)
        try:
            if any(f.endswith('.ovpn') for f in os.listdir(path)):
                return path
        except OSError:
            continue
    return os.path.join(DATA_DIR, SERVER_DIRS[0])


def worker_argv(ip, host, port, auth, tunnel=None):
    """What to run to get a proxy process, on either side of freezing.

    With `tunnel` set the worker carries traffic through a proxy already
    running on this machine instead of dialling an exit, so the address is
    the whole of what it needs: no config, no certificate name, no account.
    """
    head = [sys.executable] + ([WORKER_FLAG] if FROZEN else [PROXY_PY])
    if tunnel:
        return head + ['--tunnel', tunnel, '--port', str(port), '--quiet']
    return head + [ip, '--host', host,
                   '--port', str(port), '--auth', auth, '--quiet']


def point_proxy_module_at_data(px):
    """ovpn-proxy.py resolves everything relative to its own file, which is
    right in a repo and wrong in a bundle - there, "beside itself" is inside
    _internal, which is read-only in spirit and unwritable under Program
    Files, and holds none of the user's things.

    Its ROOT is what its argument defaults are built from, so moving that
    fixes the credentials file and the servers folder together. The state and
    log paths were already computed when the module was imported, so those
    are set again by hand.

    Both the window and the worker call this, which is the point: if they
    disagreed about where the state file lives, the window could not find the
    proxy it had just started, and would report that nothing came up.
    """
    os.makedirs(STATE_DIR, exist_ok=True)
    px.ROOT = DATA_DIR
    px.AUTH_DEFAULT = AUTH_FILE
    px.STATE_PATH = os.path.join(STATE_DIR, 'proxy.state')
    px.LOG_PATH = os.path.join(STATE_DIR, 'proxy.log')
    px.TUNNEL_SETTINGS = os.path.join(STATE_DIR, 'tunnel.json')
    px.TUNNEL_STATE = TUNNEL_STATE
