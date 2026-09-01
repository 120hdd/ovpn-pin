"""Where things are, which is two different questions once this is frozen.

Running from the repo, everything is in one tree and none of this matters.
Bundled, it splits in two and confusing them breaks the app in ways that only
show up on the user's machine:

  resources   the page, the fonts, ovpn-proxy.py. Read-only, and inside
              _internal/ next to the exe. PyInstaller reports this as
              sys._MEIPASS.

  data        the .ovpn files, the credentials, the state and log files.
              Beside the exe, where a person can see and replace them, and
              where writing is allowed - _internal is somewhere you should
              not be writing, and under Program Files you cannot.

The proxy worker is the other trap. Frozen, sys.executable is this app, so
launching it with a script path would start a second copy of the window
instead of a proxy. Hence WORKER_FLAG: the exe re-launches itself with it and
becomes the proxy for that run.
"""

import os
import sys

FROZEN = bool(getattr(sys, 'frozen', False))
WORKER_FLAG = '--proxy-worker'

if FROZEN:
    APP_DIR = os.path.dirname(os.path.abspath(sys.executable))
    RES_DIR = getattr(sys, '_MEIPASS', APP_DIR)
    DATA_DIR = APP_DIR
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

# The tunnel client, and the folder its configuration and log live in.
# Frozen, the build puts gost beside the exe; from the repo it is in tunnel/.
# PATH last, so a developer who already has one does not need a second copy.
TUNNEL_DIR = DATA_DIR if FROZEN else os.path.join(DATA_DIR, 'tunnel')
TUNNEL_STATE = os.path.join(STATE_DIR, 'tunnel')


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
# beside the exe rather than inside _internal - Sweep-OvpnExits.ps1 looks for
# its library next to itself and writes its results next to itself too - so
# once frozen they are simply in DATA_DIR.
SCRIPTS_DIR = DATA_DIR if FROZEN else os.path.join(DATA_DIR, 'windows')

# servers/ first, because that is what a shipped copy is meant to carry. The
# other two are what the repo calls them, so a developer running from source
# gets the same app without moving anything.
SERVER_DIRS = ('servers', 'success', 'pinned')


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
