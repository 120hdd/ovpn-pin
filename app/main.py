"""Relay — the window, the tray icon, and the promise to put Windows back.

Three things here are not UI, and they are what separate an app from a page
in a box:

  The title bar is the real one. Going frameless on pywebview costs resize
  handles, Aero Snap, double-click-to-maximise, taskbar minimise, the
  open/close animation and correct behaviour under display scaling - each
  with an open unresolved issue - and pywebview restores none of it: there is
  no WndProc, no WM_NCHITTEST, no WS_THICKFRAME anywhere in the package. Its
  drag is a JS-to-Python round trip per mousemove, which is why frameless
  pywebview windows are reported as choppy to drag. Windows gives all of that
  away for free with a title bar, including the rounded corners.

  The identity is set before any window exists. Without an explicit
  AppUserModelID the taskbar button belongs to the Python launcher rather
  than to this app, and no icon fixes that afterwards.

  The tray icon owns the connection's lifetime. Closing the window leaves the
  proxy up, because that is what "connected" should mean; quitting from the
  tray takes it down and restores the machine.
"""

import atexit
import base64
import ctypes
# Up here rather than inside the two functions that want it. Imported in a
# function body, `ctypes` becomes a local name for that whole body - and the
# line above the import that says ctypes.windll then fails with
# UnboundLocalError, on pywebview's own thread, where the traceback is
# printed and swallowed and all you see is a window that does nothing.
import ctypes.wintypes
import json
import os
import signal
import sys
import threading
import time
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import paths                                                 # noqa: E402

# --ui-drive presses the buttons that move .ovpn files about, so it is given
# a tree of its own to move them in. Redirected here, above every other
# import: engine computes REACH_PATH from paths.STATE_DIR the moment it is
# imported, and a run whose window used a fixture while its engine read the
# real folders would prove nothing about either.
if '--ui-drive' in sys.argv:
    import uidrive
    _DRIVE_TREE = uidrive.fixture()
    paths.DATA_DIR = _DRIVE_TREE
    paths.STATE_DIR = os.path.join(_DRIVE_TREE, '.state')
    paths.AUTH_FILE = os.path.join(_DRIVE_TREE, '.ovpn-auth')
    paths.SAVED_PROXY = os.path.join(paths.STATE_DIR,
                                     'system-proxy-before.json')
    paths.DROPPED_INDEX = os.path.join(paths.STATE_DIR, 'dropped.json')

APP_ID = 'ovpnpin.relay'
APP_NAME = 'Relay'


def run_as_worker():
    """Be the proxy rather than the window.

    Frozen, sys.executable is this app, so the window cannot start a proxy by
    running a script - it would start a second window. It runs this exe again
    with a flag instead, and this branch becomes ovpn-proxy.py for that run.

    The redirection is not defensive dressing: a --noconsole build has no
    standard streams, so sys.stdout is None, and ovpn-proxy.py asks stdout
    whether it is a terminal the moment it is imported. Without somewhere to
    write, the worker dies before running a line and the window can only
    report that nothing came up.
    """
    os.makedirs(paths.STATE_DIR, exist_ok=True)
    log = os.path.join(paths.STATE_DIR, 'worker.log')
    if sys.stdout is None or sys.stderr is None:
        stream = open(log, 'a', encoding='utf-8', buffering=1)
        sys.stdout = sys.stdout or stream
        sys.stderr = sys.stderr or stream
    try:
        import engine
        px = engine.px
        sys.argv = ['ovpn-proxy.py'] + [a for a in sys.argv[1:]
                                        if a != paths.WORKER_FLAG]
        px.main()
    except SystemExit:
        raise
    except BaseException:
        with open(log, 'a', encoding='utf-8') as f:
            f.write('\n--- worker died ---\n')
            traceback.print_exc(file=f)
        raise


if paths.WORKER_FLAG in sys.argv:
    run_as_worker()
    raise SystemExit(0)


def _diagnostics_to_file(name):
    """A --noconsole build has no stdout at all, so a diagnostic that prints
    is a diagnostic nobody can read. Everything goes to a file as well, and
    the path is the answer to "send me the output"."""
    os.makedirs(paths.STATE_DIR, exist_ok=True)
    path = os.path.join(paths.STATE_DIR, name)
    stream = open(path, 'w', encoding='utf-8', buffering=1)
    if sys.stdout is None:
        sys.stdout = sys.stderr = stream
        return stream, path

    class Tee:
        def write(self, t):
            real.write(t)
            stream.write(t)

        def flush(self):
            real.flush()
            stream.flush()

        def isatty(self):
            # ovpn-proxy.py asks this the moment it is imported, to decide
            # whether to use colour. Without it, --selftest run from a
            # terminal died on an AttributeError before printing a line -
            # while the same command in the built app worked, because there
            # sys.stdout is None and this class is never used. A diagnostic
            # that only fails where you can watch it fail is the wrong way
            # round.
            return real.isatty()

    real = sys.stdout
    sys.stdout = Tee()
    return stream, path


SETTINGS = os.path.join(paths.STATE_DIR, 'settings.json')

# What the window opens at. The height is set by the front of the app
# rather than by the settings sheet, which scrolls: the card, the orb
# above it and the hint below have to sit together without the orb
# being squeezed into an oval to make room.
WINDOW_W = 400
WINDOW_H = 740


def load_settings():
    try:
        with open(SETTINGS, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_settings(data):
    try:
        os.makedirs(paths.STATE_DIR, exist_ok=True)
        tmp = SETTINGS + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2)
        os.replace(tmp, SETTINGS)
    except OSError:
        pass


def _tray_importable():
    try:
        import pystray                                        # noqa: F401
        return True
    except Exception:
        return False


def px_live_elsewhere(port):
    """Whether another copy of this app has a proxy up on a different port.

    Two of these share a data folder, so they share the record of what is
    running and the stash of what Windows looked like beforehand. Neither
    file is wrong; the mistake would be reading either as ours.
    """
    try:
        return any(str(r.get('port')) != str(port)
                   for r in engine.px.read_states())
    except Exception:
        return False


def run_selftest():
    """Say what this copy can see, without opening a window.

    Bundled apps fail by not finding things, silently, behind a window that
    only says something went wrong. This answers what support would otherwise
    have to ask over the phone, and it is how the build is checked.
    """
    import accounts
    import engine
    import pin
    import sweep
    import windscribe
    import winproxy
    out = {'frozen': paths.FROZEN, 'app': paths.APP_DIR,
           'resources': paths.RES_DIR, 'data': paths.DATA_DIR,
           'proxyScriptFound': os.path.isfile(paths.PROXY_PY),
           'uiFound': os.path.isfile(os.path.join(paths.UI_DIR, 'index.html')),
           'fuseFound': os.path.isfile(os.path.join(paths.UI_DIR, 'fuse.js')),
           'iconFound': os.path.isfile(paths.ICON),
           'trayAvailable': _tray_importable(),
           'servers': paths.servers_dir(),
           'authFound': os.path.isfile(paths.AUTH_FILE),
           # Everything "Time the servers properly" needs, named separately,
           # because "it says it cannot run" is otherwise three questions.
           'sweepScriptFound': os.path.isfile(sweep.script_path()),
           'sweepLibraryFound': os.path.isfile(sweep.library_path()),
           'openvpn': sweep.find_openvpn(),
           # And what "Pin them to real addresses" reads and writes. It runs
           # the same resolver the sweep dot-sources, so sweepLibraryFound
           # above answers for it too - these are the two folders.
           'pinSource': pin.source_dir(),
           'pinSourceCount': len(sweep.configs_in(pin.source_dir())),
           'pinOut': pin.out_dir(),
           # And the tunnel. Named here because "no tunnel client found" in
           # the window is a sentence about this machine, and this is where
           # questions about what a copy can see get answered.
           'tunnelClient': paths.gost_exe(),
           'tunnelInstaller': os.path.isfile(paths.INSTALLER)}
    try:
        saved = load_settings()
        e = engine.Engine(winproxy.SystemProxy(paths.SAVED_PROXY),
                          folder=saved.get('folder') or None,
                          port=saved.get('port'),
                          set_system_proxy=saved.get('systemProxy', True))
        # Set up the way the window sets it up, or this reports a different
        # app from the one that is running: it would read one folder and no
        # provider filter, and answer "533 servers" for a copy that is
        # actually offering 125 from the other provider. A diagnostic whose
        # numbers are not the app's numbers sends whoever reads it the wrong
        # way, which is worse than not printing them.
        if (saved.get('source') or
                ('folder:x' if saved.get('folderIsSite') else 'all')) == 'all':
            for name in paths.SERVER_DIRS:
                path = os.path.join(paths.DATA_DIR, name)
                if os.path.isdir(path) and path not in e.folders:
                    e.folders.append(path)
        chosen = saved.get('providers')
        e.providers = set(chosen) if chosen else None
        out['port'] = e.port
        out['folders'] = e.sources()
        out['providers'] = sorted(e.providers) if e.providers else None
        out['byProvider'] = e.counts_by_provider()
        out['serverCount'] = len(e.servers())
        out['countryCount'] = len(e.catalogue())
        # One line per provider, and none of them fatal. This was a single
        # call to e.credentials() back when there was one credential to have;
        # with that file gone it raised, and took the whole rest of the
        # diagnostic - the status, which is the part anyone actually wants -
        # down with it. A self-test that stops at the first missing thing
        # reports the first missing thing and nothing else.
        #
        # Whether the file matches the account, and not only whether it has
        # two lines in it. Those are different questions and the second one
        # was the only one being asked: a credential file holding an older
        # account than the roster reads as a healthy True here, while every
        # exit answers 407 and the window blames the fleet. That happened -
        # 27 of 37 Surfshark exits refused, and this line said the
        # credentials were fine throughout.
        #
        # The username only. It is not a secret - it is in the roster in
        # clear and on the provider's own setup page - and this file is
        # written to be pasted into a chat window when something is wrong.
        out['credentials'] = {}
        try:
            active = accounts.load().get('active') or {}
        except Exception:
            active = {}
        for name, path in ((accounts.SURFSHARK, paths.AUTH_FILE),
                           (accounts.WINDSCRIBE, windscribe.AUTH_FILE)):
            got = {'file': False, 'user': None,
                   'activeAccount': None, 'matches': None}
            try:
                with open(path, encoding='utf-8') as f:
                    lines = [line.strip() for line in f if line.strip()]
                got['file'] = len(lines) >= 2
                got['user'] = lines[0] if lines else None
            except OSError:
                pass
            account = accounts.find(active.get(name) or '')
            if account:
                secrets = accounts.secrets(account['id']) or {}
                want = (secrets.get('username') if name == accounts.SURFSHARK
                        else (secrets.get('proxy') or ('', ''))[0])
                got['activeAccount'] = want or None
                if want and got['user']:
                    got['matches'] = got['user'] == want
            out['credentials'][name] = got
        out['status'] = e.status()
    except Exception as exc:
        out['engineError'] = repr(exc)
    print(json.dumps(out, ensure_ascii=False, indent=2))
    os.makedirs(paths.STATE_DIR, exist_ok=True)
    with open(os.path.join(paths.STATE_DIR, 'selftest.json'), 'w',
              encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)


if '--selftest' in sys.argv:
    _diagnostics_to_file('selftest.txt')
    run_selftest()
    raise SystemExit(0)


def run_probe_test():
    """Try to reach a handful of exits and print exactly why each failed.

    Written because the built app could not connect while the same code run
    from the repo connected in three seconds, and the difference was
    invisible from outside: no log, no worker, nothing to read. A frozen app
    fails in ways source never does - a missing certificate store being the
    classic - and the only way to see it is to make the frozen binary say so
    itself.
    """
    import time
    import engine
    import winproxy
    px = engine.px

    e = engine.Engine(winproxy.SystemProxy(paths.SAVED_PROXY))
    print(f'servers folder : {e.folder}')
    print(f'servers        : {len(e.servers())}')

    try:
        import ssl
        ctx = ssl.create_default_context()
        stats = ctx.cert_store_stats()
        print(f'CA certificates: {stats}')
        if not stats.get('x509_ca'):
            print('  !! no CA certificates loaded - every TLS handshake will fail')
    except Exception as exc:
        print(f'CA certificates: FAILED {exc!r}')

    user, password = e.credentials()
    pool = sorted(e.servers(), key=lambda s: (s.seconds is None, s.seconds or 0))
    seen, tries = set(), []
    for s in pool:
        key = f'{s.country}-{s.city}'
        if key in seen:
            continue
        seen.add(key)
        tries.append(s)
        if len(tries) >= 8:
            break

    print()
    for s in tries:
        ip, host = px.read_config(s.path)
        t0 = time.monotonic()
        try:
            took = px.can_connect(px.Exit(ip, 443, host, user, password), 8)
            print(f'  OK    {host:38} {took:.2f}s')
        except Exception as exc:
            print(f'  FAIL  {host:38} {time.monotonic()-t0:.2f}s  '
                  f'{type(exc).__name__}: {exc}')
    print()


if '--probe-test' in sys.argv:
    _stream, _path = _diagnostics_to_file('probe-test.txt')
    try:
        run_probe_test()
    except Exception:
        traceback.print_exc(file=sys.stdout)
    print(f'(written to {_path})')
    raise SystemExit(0)


# Before anything draws. Microsoft's documentation is specific that this has
# to happen before the first window, and skipping it hides the taskbar button
# under whatever launched the process.
try:
    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(APP_ID)
except Exception:
    pass

# WebView2 paints its own background before the page loads, and the default
# is white - a full-window flash on every launch, which no CSS can prevent
# because it happens before there is any CSS.
os.environ.setdefault('WEBVIEW2_DEFAULT_BACKGROUND_COLOR', '00111113')

import dropped                                               # noqa: E402
import engine                                                # noqa: E402
import pin                                                   # noqa: E402
import sweep                                                 # noqa: E402
import tunnel                                                # noqa: E402
import webview                                               # noqa: E402
import winproxy                                              # noqa: E402
import accounts                                              # noqa: E402
import uishot                                                # noqa: E402
import surfshark                                             # noqa: E402
import windscribe                                            # noqa: E402
from engine import Engine                                    # noqa: E402


class Api:
    def __init__(self):
        # Underscored on purpose. pywebview walks this object to decide what
        # to expose, recursing into any public non-callable attribute - and
        # recursing into a Window reaches .NET types and throws, which kills
        # the whole bridge and leaves a page that loads and can call nothing.
        self._sysproxy = winproxy.SystemProxy(paths.SAVED_PROXY)
        self._settings_early = load_settings()
        self._engine = Engine(
            self._sysproxy,
            folder=self._settings_early.get('folder') or None,
            set_system_proxy=self._settings_early.get('systemProxy', True),
            port=self._settings_early.get('port'))
        self._window = None
        self._tray = None
        self._busy = False
        self._settings = self._settings_early
        self._since = None
        self._real_ip = None
        self._stop = threading.Event()
        self._sweep = sweep.Sweep()
        self._pin = pin.Pin()
        self._apply_sources()
        self._sync_providers()
        # Before anything can be dialled with the wrong one.
        self._reconcile_credentials()
        # Spans the two halves of a Windscribe sign-in - the name and password
        # that fetched a puzzle, waiting for the puzzle to be let go of.
        self._ws_pending = None
        # One reachability test at a time. It is eight connections wide
        # already; two of them racing would measure each other.
        self._testing = False
        self._tunnel = None
        self._tunnel_key = None

    # -- talking to the page ----------------------------------------------

    def _emit(self, name, payload=None):
        if not self._window:
            return
        try:
            self._window.evaluate_js(
                f'window.on{name}({json.dumps(payload or {}, ensure_ascii=False)})')
        except Exception:
            pass

    def _retray(self, state):
        if self._tray:
            self._tray.set_state(state)

    # -- what the page asks for -------------------------------------------

    def _provider_state(self):
        """What each provider has, and therefore whether it can be offered.

        Two halves, and both are needed. Servers without an account is a list
        of exits nothing can open - which is what removing an account used to
        leave behind, since the configs are files and stay where they are.
        An account without servers is the opposite and just as useless.

        Kept apart in the answer rather than collapsed into one flag, because
        "none pinned" and "no account" want completely different things doing
        about them, and a chooser that greys a provider out without saying
        which is a chooser nobody can act on.
        """
        counts = self._engine.counts_by_provider()
        waiting = self._unpinned_by_provider()
        roster = accounts.listing()
        out = {}
        for name in accounts.PROVIDERS:
            servers = counts.get(name, 0)
            signed = any(a['provider'] == name and a['signedIn'] for a in roster)
            out[name] = {'servers': servers, 'account': signed,
                         'waiting': waiting.get(name, 0),
                         'usable': bool(servers) and signed}
        return out

    @staticmethod
    def _unpinned_by_provider():
        """Configs fetched but not pinned yet, per provider.

        The one thing the pane could not tell apart. "Nothing to connect
        through" and "three hundred of them, one run away" both came out as
        "none pinned", so the card that had a next step and the card that had
        a problem read identically - and the next step was a folder picker in
        another screen either way.

        Counted off the filename, which is where every other part of this app
        reads a provider from, rather than by asking each provider what it
        thinks it wrote.
        """
        out = {}
        for name, folder, mark in (
                (accounts.WINDSCRIBE, windscribe.CONFIG_DIR, '.ws.'),
                (accounts.SURFSHARK, pin.source_dir(), '.prod.')):
            try:
                out[name] = sum(1 for f in os.listdir(folder)
                                if f.endswith('.ovpn') and mark in f)
            except OSError:
                out[name] = 0
        return out

    def _usable_providers(self):
        return sorted(k for k, v in self._provider_state().items()
                      if v['usable'])

    def _sync_providers(self):
        """Keep the selection inside what is actually usable.

        Called after anything that changes the roster. A provider whose
        account has just been removed has to leave the selection by itself -
        left in, its exits stay in the list and the app offers to connect
        through a credential that is not there any more.
        """
        usable = self._usable_providers()
        chosen = [p for p in (self._settings.get('providers') or usable)
                  if p in usable]
        if not chosen:
            chosen = usable
        # Nothing usable is not the same as "offer nothing". With no accounts
        # at all, an empty filter empties the country list too - and a person
        # looking at 533 pinned servers and a blank picker has been told
        # nothing. Connecting is already refused, with a message that says
        # why, so the list is left showing what is there.
        self._engine.providers = set(chosen) if chosen else None
        if self._settings.get('providers') != chosen:
            self._settings['providers'] = chosen
            save_settings(self._settings)
        return chosen

    def _reconcile_credentials(self):
        """Make the file the proxy reads match the account said to be in use.

        Nothing downstream knows about accounts - the engine opens
        .ovpn-auth, the sweep scripts read .env - so activating an account
        means writing those files, and _write_active is the only thing that
        does it. It runs when an account is added or switched to, and never
        again. That is one write against a file three other things also
        touch, and they drift:

          the build copies a fresh .ovpn-auth out of the repo and carries
          accounts.json across separately, so a rebuild can pair a new
          roster with an old credential;

          .env is edited by hand and by the shell half, and it wins - the
          next sweep rewrites .ovpn-auth from it.

        Measured, on this machine: the roster's Surfshark account was
        accepted by 11 of 11 exits and the one in .ovpn-auth by 0 of 11, 407
        every time. The window showed the working account as active and
        dialled with the other, so every exit read as "no proxy for this
        account" - a sentence about the fleet, printed because of a file.

        Checked rather than rewritten. Writing the credential out at every
        start would touch .env on machines where nothing is wrong, and the
        comparison costs one line of one file.
        """
        fixed = []
        try:
            active = accounts.load().get('active') or {}
        except Exception:
            return fixed

        for provider, path in ((accounts.SURFSHARK, paths.AUTH_FILE),
                               (accounts.WINDSCRIBE, windscribe.AUTH_FILE)):
            account = accounts.find(active.get(provider) or '')
            if not account:
                continue
            got = accounts.secrets(account['id']) or {}
            # Both files are two lines, username first - windscribe writes
            # its proxy credential in .ovpn-auth's shape precisely so that
            # one reader does for both.
            want = (got.get('username') if provider == accounts.SURFSHARK
                    else (got.get('proxy') or ('', ''))[0])
            if not want:
                continue
            try:
                with open(path, encoding='utf-8') as f:
                    on_disk = f.readline().strip()
            except OSError:
                on_disk = ''
            if on_disk == want:
                continue
            # _write_active rather than a write here: for Surfshark it goes
            # through the engine's own writer, which is what keeps .env in
            # step - and .env is the copy that would otherwise undo this on
            # the next sweep.
            if not self._write_active(account):
                fixed.append(provider)
        return fixed

    def _forget_provider(self, provider):
        """Take away what the app was using to sign in as that provider.

        Removing the last account of a provider has to remove its credential
        too. Leaving it is what "I removed Surfshark and it is still there"
        was: the roster said no account, the file on disk said otherwise, and
        the file is what actually connects.
        """
        if provider == accounts.WINDSCRIBE:
            return windscribe.forget()
        gone = []
        try:
            os.remove(paths.AUTH_FILE)
            gone.append(paths.AUTH_FILE)
        except OSError:
            pass
        return gone

    def _describe(self):
        return {'countries': self._engine.catalogue(),
                'serverCount': len(self._engine.servers()),
                'folder': self._engine.folder,
                'folders': self._engine.sources(),
                'sources': self.sources(),
                'providers': sorted(self._engine.providers)
                if self._engine.providers is not None else None,
                'providerState': self._provider_state(),
                'systemProxy': self._engine.set_system_proxy,
                'port': self._engine.port,
                'defaultPort': engine.DEFAULT_PORT,
                'picked': self._settings.get('picked', 'auto'),
                'favourites': self._settings.get('favourites') or [],
                'sortBy': self._settings.get('sortBy', 'ping'),
                'keepOnClose': bool(self._settings.get('keepOnClose')),
                'mode': self._settings.get('mode', 'surfshark'),
                'hasCredentials': self._has_credentials(),
                'username': self._engine.username(),
                'about': (f'{APP_NAME}  -  port {self._engine.port}'
                          f'\n{paths.APP_DIR}')}

    def info(self):
        """Everything the settings sheet needs, re-read rather than cached -
        the folder can change while the window is open."""
        return self._describe()

    def boot(self):
        recovered = False
        # A stash with no proxy of ours running is the wreckage of a copy
        # that was killed - unless another copy is up and using it right now,
        # in which case it is not wreckage, it is theirs.
        if (self._sysproxy.stashed() and not self._engine.running()
                and not px_live_elsewhere(self._engine.port)):
            recovered = self._sysproxy.restore()
        status = self._engine.status()
        self._retray('on' if status.get('state') == 'on' else 'off')
        out = self._describe()
        out.update({'status': status, 'recovered': recovered,
                    'hasCredentials': self._has_credentials()})
        return out

    def whoami(self):
        """Ask once, now."""
        threading.Thread(target=self._look_up_self, daemon=True).start()
        return {'ok': True}

    def _look_up_self(self):
        """The address you have when nothing of ours is in the way.

        Only meaningful while disconnected: routed, this comes back with the
        exit's address and would claim it as yours.
        """
        if self._engine.running():
            return
        try:
            info = self._engine.current_ip()
        except Exception:
            info = {}
        if info.get('ip') != self._real_ip or self._real_ip is None:
            self._real_ip = info.get('ip')
            self._emit('RealIp', info)

    def _watch_self(self):
        """Keep that address honest while it is not ours to change.

        It used to be fetched once at startup, so unplugging another VPN left
        the window showing an address that had stopped being true - and on
        this machine that is the normal case, the whole point being to watch
        one address become another.

        Only while disconnected, and only every fifteen seconds: connected
        there is nothing to learn, and a tighter loop would be a request to
        somebody else's server every few seconds forever.
        """
        while not self._stop.wait(15):
            if self._engine.running():
                continue
            try:
                self._look_up_self()
            except Exception:
                pass

    def traffic(self):
        """What the meter says right now, for a page that has just loaded.

        The push below is what keeps it moving; this exists so that a window
        reopened onto a connection that was already up does not have to sit
        with an empty meter until the next tick.
        """
        try:
            return self._engine.traffic() or {'live': False}
        except Exception:
            return {'live': False}

    def hosts(self):
        """The log sheet's list, pulled while it is open.

        Pulled rather than pushed, unlike the meter. The meter is four numbers
        and is on screen whenever a route is up; this is a hundred rows and is
        on screen only while somebody is looking at it, and pushing it every
        second into a window with the sheet closed would be several kilobytes
        of JSON a second spent on nothing.
        """
        try:
            return self._engine.hosts() or {'live': False, 'rows': []}
        except Exception:
            return {'live': False, 'rows': []}

    def _watch_traffic(self):
        """Once a second while something is up, and once more when it stops.

        A second is what the worker writes at, so asking more often would
        only re-read the same file. It is also slow enough that the page has
        to do the smoothing - which it should, because a counter that steps
        once a second looks like it is measuring in steps, and bytes do not
        arrive that way.

        Nothing is sent while disconnected. The page is told once, so it can
        put the meter away, and then left alone - an idle window should not
        be evaluating a script every second for the rest of the afternoon.
        """
        quiet = True
        while not self._stop.wait(1.0):
            try:
                reading = self._engine.traffic()
            except Exception:
                reading = None
            if reading:
                quiet = False
                self._emit('Traffic', reading)
            elif not quiet:
                quiet = True
                self._emit('Traffic', {'live': False})

    def setSystemProxy(self, on):
        """Whether connecting should move the whole machine or just serve.

        Changing it while connected takes effect immediately, in the
        direction asked for - the alternative is a switch that lies until the
        next connect.
        """
        on = bool(on)
        self._engine.set_system_proxy = on
        self._settings['systemProxy'] = on
        save_settings(self._settings)
        if self._engine.running():
            if on:
                self._sysproxy.engage('127.0.0.1', self._engine.port)
            else:
                self._sysproxy.restore()
        return {'ok': True, 'systemProxy': on}

    def setPort(self, port):
        """Where the proxy listens. 8877 unless something else has it.

        Kept between runs, because the machine that made you move off 8877
        will still have whatever moved you on the next launch.

        Not while a connect is in flight: the engine holds its lock for the
        length of the race, so this would sit on the bridge with the window
        unable to answer anything, and the port it landed on would be a
        surprise to the connection that had just finished.
        """
        if self._busy:
            return {'ok': False, 'port': self._engine.port,
                    'error': 'Not while it is connecting. Try once it settles.'}
        try:
            result = self._engine.set_port(port)
        except RuntimeError as e:
            return {'ok': False, 'port': self._engine.port, 'error': str(e)}
        except Exception as e:
            # A move that fell over mid-flight leaves nothing running, and the
            # page has to hear that rather than a port change that "worked".
            self._retray('off')
            self._emit('Failed', {'kind': 'moved-and-died', 'detail': str(e)})
            return {'ok': False, 'port': self._engine.port,
                    'error': f'Moved to {self._engine.port}, but the '
                             f'connection did not come back up: {e}'}
        self._settings['port'] = self._engine.port
        save_settings(self._settings)
        if result.get('moved'):
            # The window is showing an exit whose worker has just been
            # replaced. Same exit, same address, new pid - and the readout is
            # rebuilt from the answer rather than left to drift.
            self._emit('Connected', self._engine.status())
        return {'ok': True, **result}

    def chooseFolder(self):
        """A real Windows folder picker, not a text box to paste a path into."""
        try:
            picked = self._window.create_file_dialog(
                webview.FOLDER_DIALOG, directory=self._engine.folder)
        except Exception as e:
            return {'ok': False, 'error': f'Could not open the folder picker: {e}'}
        if not picked:
            return {'ok': False}
        folder = picked[0] if isinstance(picked, (list, tuple)) else picked
        try:
            count = len([f for f in os.listdir(folder) if f.endswith('.ovpn')])
        except OSError as e:
            return {'ok': False, 'error': f'Cannot read that folder: {e}'}
        if not count:
            return {'ok': False,
                    'error': 'No .ovpn files in that folder, so it was not used.'}
        self._engine.folder = folder
        self._settings['folder'] = folder
        save_settings(self._settings)
        return {'ok': True, 'folder': folder, 'count': count}

    def resetFolder(self):
        self._engine.folder = paths.servers_dir()
        self._settings.pop('folder', None)
        # And back to reading every folder, which is what "the servers that
        # came with the app" means once there is more than one provider.
        self._settings.pop('folderIsSite', None)
        self._settings['source'] = 'all'
        save_settings(self._settings)
        self._apply_sources()
        return {'ok': True, 'folder': self._engine.folder,
                'source': 'all',
                'folders': self._engine.sources()}

    def copy(self, text):
        """A fallback for the clipboard API, which needs a secure context and
        does not always get one from file://."""
        try:
            self._window.evaluate_js(
                'navigator.clipboard.writeText(%s)' % json.dumps(str(text)))
        except Exception:
            pass
        return {'ok': True}

    def _has_credentials(self):
        """Whether anything at all can be connected through.

        Not "is there a .ovpn-auth". That was the same question back when
        there was one provider, and it stopped being so the moment there were
        two: an app signed in to Windscribe, with a hundred and twenty-five
        of its exits pinned, answered no - and the button that asks this is
        the Connect button, so it said NOT SET UP and refused to do the thing
        it was perfectly able to do.

        A provider counts when it has both an account and exits, which is the
        same test the chooser uses. One of them is enough.
        """
        return bool(self._usable_providers())

    # saveCredentials and forgetCredentials used to live here, writing
    # .ovpn-auth straight from the old Surfshark pane. Nothing calls them any
    # more - accounts do that now, through _write_active - and leaving them
    # would have left two ways to set a credential, one of which produces the
    # state this app has already been caught by once: a credential file on
    # disk with no account behind it, so the roster says the provider is gone
    # and the file says it is not.

    # -- accounts ----------------------------------------------------------
    #
    # One roster over both providers. What differs between them is only how
    # an account is proved: Surfshark's is a service credential that can be
    # pasted, Windscribe's needs a login and a puzzle. Everything after that
    # - which one is in use, what it is called, how it is dropped - is the
    # same question, so it is the same code.
    #
    # The captcha is drawn in the page from the two images the API sends;
    # what comes back here is where the person let go of the piece and the
    # path their pointer took getting there. Neither is generated anywhere in
    # this app - a solved captcha that nobody solved is the one thing this
    # flow must not be able to produce.

    def _write_active(self, account):
        """Put an account's credential where the rest of the app reads it.

        This is the whole of what "in use" means. Nothing downstream knows
        about accounts: the engine reads .ovpn-auth and .windscribe-auth, the
        sweep scripts read .env, and all of them keep working because
        activating an account rewrites those and changes nothing else.
        """
        secrets = accounts.secrets(account['id'])
        if not secrets:
            return 'That account could not be read back.'

        if account['provider'] == accounts.SURFSHARK:
            if not (secrets['username'] and secrets['password']):
                return ('That Surfshark account has no password stored - '
                        'remove it and add it again.')
            try:
                # The engine's own writer, so that .env stays in step. The
                # PowerShell half reads that one first.
                self._engine.save_credentials(secrets['username'],
                                              secrets['password'])
            except (RuntimeError, OSError) as e:
                return str(e)
            return None

        user, password = secrets['proxy']
        if not (user and password):
            return ('That Windscribe account has no proxy credential stored '
                    '- press Refresh credential, or sign in again.')
        try:
            windscribe.save_proxy_credentials(user, password)
            if secrets['session']:
                windscribe.save_token(secrets['session'], secrets['username'])
        except OSError as e:
            return str(e)
        return None

    def accountsList(self):
        # Whatever was already signed in becomes the first entries, once.
        # Someone who has been using this app should not open the new pane
        # and be told they have no accounts while both providers are plainly
        # connected.
        accounts.adopt(self._engine)
        return {'ok': True, 'accounts': accounts.listing()}

    def accountAdd(self, provider, label, username, password):
        username = (username or '').strip()
        if provider not in accounts.PROVIDERS:
            return {'ok': False, 'error': 'Unknown provider.'}
        if not username:
            return {'ok': False, 'error': 'Enter the username.'}
        if not password:
            return {'ok': False, 'error': 'Enter the password.'}

        if provider == accounts.SURFSHARK:
            account = accounts.put(provider, label, username, password=password)
            problem = self._write_active(account)
            if problem:
                return {'ok': False, 'error': problem}
            self._sync_providers()
            return {'ok': True, 'id': account['id'], 'label': account['label']}

        # Windscribe: the puzzle stands between here and an account, so this
        # only fetches it. The account is written when the login lands.
        try:
            out = windscribe.begin_login(username, password)
        except windscribe.ApiError as e:
            return {'ok': False, 'error': str(e), 'code': e.code}
        self._ws_pending = {'username': username, 'password': password,
                            'label': (label or '').strip()}
        return {'ok': True, 'token': out['token'], 'captcha': out['captcha']}

    def accountUse(self, account_id):
        account = accounts.activate(account_id)
        if not account:
            return {'ok': False, 'error': 'No such account.'}
        problem = self._write_active(account)
        if problem:
            return {'ok': False, 'error': problem}
        return {'ok': True, 'label': account['label'],
                'provider': account['provider']}

    def accountRemove(self, account_id):
        gone = accounts.remove(account_id)
        if not gone:
            return {'ok': False, 'error': 'No such account.'}
        # Whatever took over as active for that provider has to be written
        # out, or the file on disk still holds the account just removed.
        left = accounts.active(gone['provider'])
        if left:
            self._write_active(left)
        else:
            # None left, so the credential goes too. The configs stay - they
            # are files, they cost nothing, and they are worth having if the
            # account comes back - but the provider stops being offered,
            # which is what _sync_providers does next.
            self._forget_provider(gone['provider'])
        self._sync_providers()
        return {'ok': True, 'label': gone.get('label') or '',
                'providers': sorted(self._engine.providers or [])}

    def windscribeFinish(self, token, solution,
                         trailX=None, trailY=None, code2fa=''):
        """Half two of a Windscribe sign-in, run the moment the puzzle is let
        go of.

        The name and password come from the call that fetched the puzzle
        rather than from the page, so they cross the bridge once. One
        attempt: note.md records an account blocked after about seventy
        security alerts, so a failure here is reported and left alone.
        """
        pending = getattr(self, '_ws_pending', None)
        if not pending:
            return {'ok': False,
                    'error': 'That sign-in expired. Start it again.'}
        try:
            who = windscribe.finish_login(
                pending['username'], pending['password'], token, solution,
                trailX or [], trailY or [], (code2fa or '').strip())
        except windscribe.ApiError as e:
            # Spent either way - the token is single-use and so is the
            # puzzle - so it is dropped rather than left for a retry that
            # would be refused for a reason nobody could see.
            self._ws_pending = None
            return {'ok': False, 'error': str(e), 'code': e.code,
                    'why': e.description}

        # The credential the whole login was for. Fetched here rather than
        # left for later, because this is the one moment the session is
        # certainly good - and an account saved without one is an account
        # that looks signed in and cannot connect.
        proxy, trouble = None, None
        try:
            proxy = windscribe.proxy_credentials(who['session_auth_hash'])
        except windscribe.ApiError as e:
            trouble = str(e)

        account = accounts.put(
            accounts.WINDSCRIBE, pending.get('label'), who['username'],
            # Only now, and only because it has just been proved right.
            # Saving a password before it has worked is how a wrong one gets
            # remembered and quietly reused until the account locks.
            password=pending['password'],
            session=who['session_auth_hash'], proxy=proxy)
        self._ws_pending = None

        problem = self._write_active(account) if proxy else trouble
        self._sync_providers()
        return {'ok': True, 'username': who['username'],
                'credentials': bool(proxy) and not problem,
                # From what put() just returned rather than looked up again -
                # a second read that comes back empty would crash on the way
                # out of a sign-in that had actually worked.
                'remembered': account.get('password') is not None,
                'error': problem or trouble or '',
                'premium': who['premium']}

    def windscribeRefresh(self):
        """The whole of what this app does on its own: token in, working
        proxy credential out, no captcha anywhere. Also the answer to the one
        open question in windscribe.md - if this still works in a month, the
        sign-in was a one-time thing."""
        account = accounts.active(accounts.WINDSCRIBE)
        secrets = accounts.secrets(account['id']) if account else None
        session = (secrets or {}).get('session') or (
            (windscribe.load_token() or {}).get('session_auth_hash'))
        if not session:
            return {'ok': False, 'error': 'Not signed in to Windscribe.'}
        try:
            user, password = windscribe.proxy_credentials(session)
        except windscribe.ApiError as e:
            return {'ok': False, 'error': str(e), 'code': e.code}
        windscribe.save_proxy_credentials(user, password)
        if account:
            accounts.put(accounts.WINDSCRIBE, account.get('label'),
                         account.get('username'), proxy=(user, password),
                         account_id=account['id'])
        return {'ok': True, 'proxyUser': user}

    def windscribeServers(self, freeOnly=False):
        """Fetch the published fleet and write it out as configs to pin.

        Unpinned on purpose. These are hostnames, and a hostname is the thing
        this line answers dishonestly - so they go through the same pinning
        the rest of the app uses rather than being resolved here.
        """
        try:
            out = windscribe.write_configs(free_only=bool(freeOnly))
        except windscribe.ApiError as e:
            return {'ok': False, 'error': str(e)}
        except OSError as e:
            return {'ok': False,
                    'error': f'Could not write into {windscribe.CONFIG_DIR}: {e}'}

        # And point the pinning inbox at what was just written.
        #
        # The page says "pin them next - the Servers group below does it",
        # and until this line that group was still pointed at configs/, which
        # is Surfshark's inbox. Pressing Start there re-pinned Surfshark and
        # left Windscribe with nothing pinned - so its box stayed grey saying
        # "none pinned" directly under a roster showing it signed in, and the
        # only way through was to know the folder existed and walk the picker
        # to it. The instruction and the control now agree.
        self._settings['pinFolder'] = windscribe.CONFIG_DIR
        save_settings(self._settings)

        out['ok'] = True
        out['pinFolder'] = windscribe.CONFIG_DIR
        return out

    def surfsharkServers(self):
        """Fetch the published cluster list and fill in what the folder is
        missing.

        The same shape as windscribeServers and for the same reason: these
        are hostnames, and a hostname is the thing this line answers
        dishonestly, so they go through the same pinning as everything else
        rather than being resolved here.

        Additive. A config already in the folder is left alone - it may be
        the user's own download - so this is safe to press twice, and safe
        to press on a folder somebody has curated.
        """
        try:
            out = surfshark.write_configs()
        except surfshark.ApiError as e:
            return {'ok': False, 'error': str(e)}
        except OSError as e:
            return {'ok': False,
                    'error': f'Could not write into {surfshark.CONFIG_DIR}: {e}'}
        out['ok'] = True
        return out

    # -- measuring the servers properly ------------------------------------

    def _sweep_choices(self):
        return {'sites': self._settings.get('sites', ''),
                'scope': self._settings.get('sweepScope', 'all'),
                'chosen': self._settings.get('sweepLandlords', []),
                'first': int(self._settings.get('sweepFirst', 0) or 0),
                'through': self._settings.get('sweepRoute', 'provider')}

    def sweepPlan(self, folder=None, quick=False, **over):
        """What a test would cost and what would stop it, asked before the
        person commits to either.

        quick leaves out the one check that costs a PowerShell process -
        whether another VPN holds the default route. Right when the sheet
        opens and right before Start; wrong on every click of a chip, which
        made each choice take a second and a half to show its new number.
        """
        choices = {**self._sweep_choices(), **{k: v for k, v in over.items()
                                               if v is not None}}
        return self._sweep.plan(self._engine, folder or None,
                                ask_windows=not quick, **choices)

    def setSweepRoute(self, route=None):
        """Which leg the test measures, kept between runs.

        Its own call rather than another argument to setSweepScope, because
        changing it changes what the pane is allowed to say: the route
        decides which blockers apply, and asking for them under the old route
        would show a UAC warning for a run that never elevates.
        """
        if route in sweep.ROUTES:
            self._settings['sweepRoute'] = route
            save_settings(self._settings)
        return self.sweepPlan(quick=True)

    def setSweepScope(self, scope=None, chosen=None, first=None):
        """One address each, or one per location, or one per company - and
        which companies. Kept between runs, because these are the choices that
        turn an afternoon into a quarter of an hour and nobody wants to make
        them twice.

        Each argument left out is left alone. The page used to resend the
        scope alongside a change of company, reading it out of the last answer
        it had been given - so a company clicked while that answer was still
        in flight put the scope back to whatever it had been before.
        """
        if scope in sweep.SCOPES:
            self._settings['sweepScope'] = scope
        if chosen is not None:
            self._settings['sweepLandlords'] = list(chosen)
        if first is not None:
            try:
                self._settings['sweepFirst'] = max(0, int(first))
            except (TypeError, ValueError):
                self._settings['sweepFirst'] = 0
        save_settings(self._settings)
        return self.sweepPlan(quick=True)

    def lookUpOwners(self, folder=None):
        """Ask who the untraced addresses are rented from.

        Its own button rather than something the sheet does on opening: it is
        a request to somebody else's service per hundred addresses, with a
        deliberate wait between them, and doing that because a settings sheet
        was opened would be rude to them and slow for you.
        """
        target = folder or self._engine.folder
        result = sweep.look_up_owners(target)
        if not result.get('ok'):
            return result
        return {**result, 'plan': self.sweepPlan(target)}

    def chooseSweepFolder(self):
        try:
            picked = self._window.create_file_dialog(
                webview.FOLDER_DIALOG, directory=self._engine.folder)
        except Exception as e:
            return {'ok': False, 'error': f'Could not open the folder picker: {e}'}
        if not picked:
            return {'ok': False}
        folder = picked[0] if isinstance(picked, (list, tuple)) else picked
        plan = self._sweep.plan(self._engine, folder, **self._sweep_choices())
        if not plan['total']:
            return {'ok': False,
                    'error': 'No .ovpn files in that folder, so there is '
                             'nothing to test.'}
        return {'ok': True, **plan}

    def saveSites(self, text):
        """The sites to ask each exit about, kept between runs.

        Cleaned here rather than at the last moment, so what is stored is
        what will be asked - the sweep drops anything that does not look like
        a hostname without saying so, and a site silently never tested is
        worse than one refused out loud.
        """
        named, dropped = sweep.split_sites(text)
        self._settings['sites'] = ','.join(named)
        save_settings(self._settings)
        return {'ok': True, 'sites': named, 'dropped': dropped,
                'minutes': sweep.estimate_minutes(
                    len(sweep.configs_in(self._engine.folder)), len(named))}

    def _apply_sources(self):
        """Which folders the engine reads.

        Everything, or one thing, and the setting says which. `all` is the
        chosen folder plus the other places pinned configs land: both
        providers have to be visible at once for "use both" to mean anything,
        and they do not share a folder - Surfshark's configs are downloaded
        and pinned, Windscribe's are fetched and pinned, and nobody should
        have to merge two folders by hand to have both offered.

        Anything else is one folder and nothing beside it. That used to be
        allowed only for a site folder, with a guard against narrowing onto
        one of the standard three - because back then narrowing was a side
        effect of a button called "connect through these" and never something
        anyone asked for by name. It is asked for by name now, so the guard
        would be refusing the request; what stands in its place is that the
        head of the list says which source is on at all times, so exits
        cannot go missing without the reason being on screen.
        """
        source = self._source()
        folders = [self._engine.folder]
        if source == 'all':
            for name in paths.SERVER_DIRS:
                path = os.path.join(paths.DATA_DIR, name)
                if os.path.isdir(path) and path not in folders:
                    folders.append(path)
        self._engine.folders = folders
        # Cleared before it is worked out, not after. _starred_files reads
        # the servers on offer to turn a favourite into filenames, and with
        # the old filter still on it would only ever see what the last one
        # let through - a list that could shrink on every call and never
        # grow back.
        self._engine.only_files = None
        if source == 'starred':
            self._engine.only_files = self._starred_files()
        return folders

    @staticmethod
    def _standard_source(folder):
        """Whether this is one of the folders the app reads anyway."""
        if not folder:
            return False
        here = os.path.normcase(os.path.abspath(folder))
        return any(
            here == os.path.normcase(os.path.abspath(
                os.path.join(paths.DATA_DIR, name)))
            for name in paths.SERVER_DIRS)

    def _source(self):
        """Which pool to connect out of: `all`, or one named source.

        Read through a method rather than off the settings dict so that a
        settings file written before this existed still comes up right. The
        old shape was a folder plus a folderIsSite flag, which said "narrowed"
        without saying onto what; it is mapped once, here, and then forgotten.
        """
        source = self._settings.get('source')
        if source:
            return source
        if self._settings.get('folderIsSite'):
            return f'folder:{self._engine.folder}'
        return 'all'

    def _source_folder(self, source):
        """The folder a source names, or None for the ones that are not one."""
        if source in ('all', 'starred'):
            return None
        if source.startswith('folder:'):
            return source[7:]
        if source.startswith('site:'):
            return os.path.join(sweep.sitetest_dir(), source[5:])
        return os.path.join(paths.DATA_DIR,
                            {'pinned': 'pinned',
                             'verified': self._verified_name()}.get(source, ''))

    @staticmethod
    def _verified_name():
        """What this copy calls the folder of things that connected.

        A shipped copy carries `servers/` and a repo has `success/`; they hold
        the same thing under two names, and only one of them exists at a time.
        """
        for name in ('success', 'servers'):
            path = os.path.join(paths.DATA_DIR, name)
            try:
                if any(f.endswith('.ovpn') for f in os.listdir(path)):
                    return name
            except OSError:
                continue
        return 'success'

    def _starred_files(self):
        """The config filenames behind the starred places.

        Favourites are picker codes - a country, a city, one provider's share
        of a country, a single exit - and _pool_for already turns any of those
        into servers. Resolved to filenames so the engine can filter on them
        without knowing what a favourite is.
        """
        out = set()
        for code in (self._settings.get('favourites') or []):
            for s in self._pool_for(code):
                out.add(s.file)
        return out

    def setProviders(self, providers=None):
        """Which providers to offer exits from.

        An empty choice is refused rather than obeyed. "None of them" is a
        setting whose only effect is an app that cannot connect and does not
        say why, and the button that produced it looked like a filter.
        """
        wanted = [p for p in (providers or []) if p in accounts.PROVIDERS]
        if not wanted:
            return {'ok': False,
                    'error': 'Pick at least one — with none chosen there is '
                             'nothing to connect through.'}

        state = self._provider_state()
        usable = [p for p in wanted if state[p]['usable']]
        if not usable:
            names = {accounts.SURFSHARK: 'Surfshark',
                     accounts.WINDSCRIBE: 'Windscribe'}
            # Which half is missing, because the two want opposite things
            # doing about them and "unavailable" says neither.
            why = []
            for p in wanted:
                if not state[p]['account']:
                    why.append(f'no {names.get(p, p)} account')
                elif not state[p]['servers']:
                    why.append(f'no pinned {names.get(p, p)} servers')
            return {'ok': False, 'error': (' and '.join(why) or 'nothing to '
                                           'connect through') + '.'}

        self._engine.providers = set(usable)
        self._settings['providers'] = sorted(usable)
        save_settings(self._settings)
        return {'ok': True, 'providers': sorted(usable),
                'serverCount': len(self._engine.servers()),
                'countries': self._engine.catalogue()}

    def useSiteFolder(self, folder):
        """Point the app at one site's folder.

        This is what the whole thing was for: connecting only through servers
        that were measured getting into the site you named, rather than
        through whatever is quickest and hoping.
        """
        if not sweep.configs_in(folder or ''):
            return {'ok': False,
                    'error': 'That folder has no servers in it any more.'}
        self._engine.folder = folder
        self._settings['folder'] = folder
        # Narrowed on purpose. The point of a site folder is "only the exits
        # that were measured getting into this site", and quietly reading the
        # other folders beside it would give back exactly what was excluded.
        #
        # Said as a source rather than as a flag, so that the head of the list
        # can name it. The old flag could say "narrowed" but not onto what,
        # which is how narrowing onto pinned/ - a folder that was already
        # being read - managed to look like a filter while its only effect
        # was to drop the Surfshark folder beside it.
        self._settings['source'] = self._source_for_folder(folder)
        self._settings.pop('folderIsSite', None)
        save_settings(self._settings)
        self._apply_sources()
        return {'ok': True, 'folder': folder,
                'source': self._settings['source'],
                'narrowed': self._settings['source'] != 'all',
                'count': len(sweep.configs_in(folder))}

    def _source_for_folder(self, folder):
        """The source name for a folder, so the same place is spelled one way.

        A folder picked out of the file dialog that happens to be pinned/ is
        the pinned source, not a stranger with the same path - otherwise the
        list head would say "a folder of your own" about the app's own folder,
        and switching to Pinned afterwards would look like a different place.
        """
        here = os.path.normcase(os.path.abspath(folder))

        def same(path):
            return here == os.path.normcase(os.path.abspath(path))

        if same(os.path.join(paths.DATA_DIR, 'pinned')):
            return 'pinned'
        if same(os.path.join(paths.DATA_DIR, self._verified_name())):
            return 'verified'
        site = sweep.sitetest_dir()
        if os.path.normcase(os.path.abspath(os.path.dirname(folder))) == \
                os.path.normcase(os.path.abspath(site)):
            return f'site:{os.path.basename(os.path.normpath(folder))}'
        return f'folder:{folder}'

    # -- which pool to connect out of --------------------------------------

    def _count_in(self, folders, only=None):
        """What a source would offer: how many places, and how many exits.

        Counted the way the list counts, not by listing .ovpn files: a folder
        of 352 Windscribe configs offers nothing at all while only Surfshark
        is selected, and a row promising 352 that opens onto an empty list is
        worse than no number.

        Both numbers, because the sheet needs them for different jobs. The
        line above the list has always counted places - it used to read "All
        locations, 99" - so the column in the chooser has to be places too,
        or picking Pinned would set a line saying 99 from a row that said
        1564. The exits are the size of the pool and belong in the sentence.
        """
        was = (self._engine.folder, self._engine.folders,
               self._engine.only_files)
        try:
            # The primary folder as well, not only the list. sources() is
            # [folder] + folders, so setting the list alone left whatever is
            # currently chosen on the front of every count - and Pinned and
            # Everything both came back 1601 on a tree where pinned holds
            # 1564. A count that is the same for two different answers is
            # worse than no count.
            self._engine.folder = folders[0]
            self._engine.folders = list(folders)
            self._engine.only_files = only
            got = self._engine.servers()
            return len({s.country for s in got}), len(got)
        finally:
            (self._engine.folder, self._engine.folders,
             self._engine.only_files) = was

    def sources(self):
        """The pools worth offering, with what each one holds.

        Every row is somewhere exits already are - no row is offered for a
        folder that does not exist, because a source with nothing in it is a
        way to empty the list and then wonder why.
        """
        here = self._source()
        rows = []

        def row(key, name, note, folders, only=None):
            """One choice, or nothing at all if it would offer nothing."""
            places, exits = self._count_in(folders, only)
            if not places:
                return
            # The size of the pool goes in the sentence rather than in the
            # column. Two bare numbers on a row - 99 and 1564 - is a row
            # nobody can read, and only one of them is what the line above
            # the list is about to say.
            rows.append({'key': key, 'name': name,
                         'note': f'{exits} exits · {note}',
                         'count': places, 'exits': exits})

        every = [self._engine.folder]
        for name in paths.SERVER_DIRS:
            path = os.path.join(paths.DATA_DIR, name)
            if os.path.isdir(path) and path not in every:
                every.append(path)
        row('all', 'Everything', 'every folder the app reads', every)

        verified = self._verified_name()
        for key, folder, name, note in (
                ('pinned', 'pinned', 'Pinned',
                 'each one resolved to an address'),
                ('verified', verified, 'Verified',
                 'what actually connected, with its times')):
            path = os.path.join(paths.DATA_DIR, folder)
            if os.path.isdir(path):
                row(key, name, note, [path])

        if self._settings.get('favourites'):
            starred = self._starred_files()
            if starred:
                row('starred', 'Starred', 'only the places you picked out',
                    every, starred)

        try:
            sites = sorted(os.listdir(sweep.sitetest_dir()))
        except OSError:
            sites = []
        for name in sites:
            path = os.path.join(sweep.sitetest_dir(), name)
            if os.path.isdir(path):
                # The folder is named www-reddit-com because a folder cannot
                # be called www.reddit.com on Windows without inviting
                # trouble. The row is about the site, so it says the site.
                row(f'site:{name}', name.replace('-', '.'),
                    'measured getting into this site', [path])

        # A folder of somebody's own is only a row once it is the one in use;
        # before that it is the button underneath, which opens a file dialog.
        if here.startswith('folder:'):
            folder = here[7:]
            row(here, os.path.basename(os.path.normpath(folder)) or folder,
                folder, [folder])

        keys = {r['key'] for r in rows}
        return {'ok': True, 'rows': rows,
                # A source whose folder has since gone reads as Everything
                # rather than as a name with nothing behind it.
                'source': here if here in keys else 'all'}

    def setSource(self, source=None):
        """Connect out of this pool from now on."""
        source = source or 'all'
        if source not in {r['key'] for r in self.sources()['rows']}:
            return {'ok': False, 'error': 'There is nothing in that one.'}

        folder = self._source_folder(source)
        if folder:
            if not os.path.isdir(folder):
                return {'ok': False, 'error': 'That folder is not there any '
                                              'more.'}
            self._engine.folder = folder
            self._settings['folder'] = folder
        elif source == 'all':
            # Back to the folder the app would have chosen for itself. Leaving
            # the last narrow choice as the primary would put it first in the
            # union - harmless for what is offered, misleading for what the
            # settings sheet says the folder is.
            self._engine.folder = paths.servers_dir()
            self._settings.pop('folder', None)

        self._settings['source'] = source
        self._settings.pop('folderIsSite', None)
        save_settings(self._settings)
        self._apply_sources()
        return {'ok': True, 'source': source,
                'folder': self._engine.folder,
                'folders': self._engine.sources(),
                'countries': self._engine.catalogue(),
                'serverCount': len(self._engine.servers())}

    def startSweep(self, folder=None):
        return self._sweep.start(
            self._engine, folder or self._engine.folder,
            lambda p: self._emit('Sweep', p), **self._sweep_choices())

    def cancelSweep(self):
        return self._sweep.cancel()

    # -- pinning them to real addresses ------------------------------------

    def _pin_choices(self):
        """The four choices, and what they are when nobody has made them.

        Through a proxy on 10808 is the default because it is the one that
        works on the line this app exists for: direct DoH is the first thing
        a censored resolver takes away, and a person who could resolve
        directly would not be pinning anything.
        """
        return {'route': self._settings.get('pinRoute', 'proxy'),
                'port': pin.clean_port(self._settings.get('pinPort')),
                'max_ips': pin.clean_max_ips(self._settings.get('pinMaxIps')),
                'test': bool(self._settings.get('pinTest', True))}

    def _pin_folders(self):
        return (self._settings.get('pinFolder') or pin.source_dir(),
                self._settings.get('pinOut') or pin.out_dir())

    def pinPlan(self, folder=None, out=None, quick=False):
        """What is in the inbox, where it would go, and what would stop it.

        quick leaves out the one check that touches a socket - whether the
        proxy named is really listening. Right when the sheet opens and right
        before Start; wrong on every keystroke in the port field, which would
        make typing a port cost a second a digit.
        """
        was, into = self._pin_folders()
        choices = self._pin_choices()
        test = choices.pop('test')
        plan = self._pin.plan(folder or was, out or into, quick=quick,
                              **choices)
        # Not the resolver's business - it is either passed -NoTest or it is
        # not - but the page has a switch for it and has to draw it.
        plan['test'] = test
        # Whether what comes out of a run is somewhere the app reads already.
        # It decides whether there is anything to offer afterwards: for the
        # default pinned/ the answer is no, the exits are in the list the
        # moment the run ends, and the button that used to be drawn there
        # took two other folders out of the list instead.
        plan['outIsStandard'] = self._standard_source(plan.get('out'))
        return plan

    def setPinRoute(self, route=None, port=None, maxIps=None, test=None):
        """Kept between runs. Each argument left out is left alone, for the
        same reason the sweep's choices are: the page sends one change at a
        time, and resending the others from an answer still in flight is how
        a click puts something else back to what it was."""
        if route in pin.ROUTES:
            self._settings['pinRoute'] = route
        if port is not None:
            self._settings['pinPort'] = pin.clean_port(port)
        if maxIps is not None:
            self._settings['pinMaxIps'] = pin.clean_max_ips(maxIps)
        if test is not None:
            self._settings['pinTest'] = bool(test)
        save_settings(self._settings)
        return self.pinPlan(quick=True)

    def pinForProvider(self, provider):
        """Point the pinning inbox at one provider's pile.

        What the card's Pin button presses before it moves you to the screen
        that runs it. The alternative was the folder picker, and knowing
        which of two folders to walk it to is exactly the knowledge somebody
        pressing a button called Pin does not have.
        """
        folder = (windscribe.CONFIG_DIR if provider == accounts.WINDSCRIBE
                  else pin.source_dir())
        if not sweep.configs_in(folder):
            return {'ok': False,
                    'error': f'Nothing waiting in {folder}.'}
        self._settings['pinFolder'] = folder
        save_settings(self._settings)
        return {'ok': True, **self.pinPlan()}

    def choosePinFolder(self):
        """Where the files you downloaded are."""
        was, _ = self._pin_folders()
        picked = self._ask_for_folder(was)
        if not isinstance(picked, str):
            return picked
        if not sweep.configs_in(picked):
            return {'ok': False,
                    'error': 'No .ovpn files in that folder, so there is '
                             'nothing to pin.'}
        self._settings['pinFolder'] = picked
        save_settings(self._settings)
        return {'ok': True, **self.pinPlan()}

    def choosePinOut(self):
        """And where the pinned copies should land. Allowed to be empty -
        that is the normal case, and the run is what fills it."""
        _, into = self._pin_folders()
        picked = self._ask_for_folder(into)
        if not isinstance(picked, str):
            return picked
        self._settings['pinOut'] = picked
        save_settings(self._settings)
        return {'ok': True, **self.pinPlan()}

    def _ask_for_folder(self, start):
        """The picker, and the two ways it does not give you a folder.

        Returns the path, or the answer to hand straight back to the page -
        which is what lets the two callers above be four lines each.
        """
        try:
            picked = self._window.create_file_dialog(
                webview.FOLDER_DIALOG, directory=start if os.path.isdir(start)
                else paths.DATA_DIR)
        except Exception as e:
            return {'ok': False, 'error': f'Could not open the folder picker: {e}'}
        if not picked:
            return {'ok': False}
        return picked[0] if isinstance(picked, (list, tuple)) else picked

    def startPin(self):
        was, into = self._pin_folders()

        def progress(p):
            # A finished run may have created a folder that was not there
            # when the sources were worked out. pinned/ is in SERVER_DIRS but
            # _apply_sources only lists it when it exists, and the first pin
            # run is what makes it exist - so without this the engine goes on
            # reading the same folders it read at startup, and everything
            # just written is invisible until the app is restarted. That is
            # what left Windscribe greyed out saying "none pinned" straight
            # after pinning it.
            if isinstance(p, dict) and p.get('phase') == 'finished':
                self._apply_sources()
                self._sync_providers()
            self._emit('Pin', p)

        return self._pin.start(was, into, progress, **self._pin_choices())

    def cancelPin(self):
        return self._pin.cancel()

    def usePinnedFolder(self, folder=None):
        """Connect through what was just pinned. The same move as picking a
        site's folder, and the same guard on it."""
        _, into = self._pin_folders()
        return self.useSiteFolder(folder or into)

    # -- which of them answer, and how fast --------------------------------

    def _pool_for(self, code):
        """The exits behind one row of the list.

        Same shapes exitsIn takes and the same parsing, because the row that
        opens a list of exits and the row that tests them are the same row: a
        country, a city inside one, either of those narrowed to a provider,
        or a single file.
        """
        code = code or ''
        if code.startswith('file:'):
            name = code[5:]
            return [s for s in self._engine.servers() if s.file == name]
        where, _, via = code.partition(':')
        city = None
        if '/' in where:
            where, city = where.split('/', 1)
        notes = windscribe.meta() if city else {}
        out = []
        for s in self._engine.servers():
            if where and s.country != where:
                continue
            if via and s.provider != via:
                continue
            if city and self._engine.city_of(s, notes)[0] != city:
                continue
            out.append(s)
        return out

    def testReach(self, code=None):
        """Ask the exits behind one row whether they take the credentials.

        The one thing the app could never say. An exit filtered on this line
        looks exactly like one that is merely slow, and the only way to tell
        them apart is to ask - which the connect race does every time and
        then throws away. This asks once and keeps the answer.

        One row at a time, and that is the whole of the change. Asking all
        four hundred was a single button in the header, and it was the wrong
        shape twice over: nobody wants to know about ninety countries, and a
        run that long is where the line starts dropping connections and the
        answers stop being about the exits - see _second_look. A country is
        twenty addresses and four seconds, and it is the country somebody was
        already looking at.
        """
        if self._testing:
            return {'ok': False, 'error': 'busy'}

        pool = self._pool_for(code)
        if not pool:
            return {'ok': False, 'error': 'Nothing to test.'}
        only = {s.file for s in pool}
        self._testing = True

        def work():
            try:
                out = self._engine.test_reach(
                    lambda p: self._emit('Reach', p), only=only)
                # Moved out of the way of `ok`, which every call in this
                # class uses for "the call worked" and which was being set
                # over the top of it here - so the count reached the page as
                # `true`, printed itself as "true of 37 answered", and left
                # the test that names a credential problem reading !true.
                out['answered'] = out.pop('ok', 0)
                out['ok'] = True
                out['code'] = code
                out['countries'] = self._engine.catalogue()
                self._emit('ReachDone', out)
            except Exception as e:
                self._emit('ReachDone',
                           {'ok': False, 'code': code, 'error': str(e)})
            finally:
                self._testing = False

        threading.Thread(target=work, daemon=True).start()
        return {'ok': True, 'total': len(only)}

    def cancelReach(self):
        self._engine.cancelled.set()
        return {'ok': True}

    # -- taking the dead ones out, on purpose ------------------------------

    def _dead(self):
        """The exits the last test refused, by filename.

        `ok is False` and nothing else. `ok is None` is what _second_look
        leaves on an exit it asked twice and could not judge, and treating
        "we could not tell" as "it is dead" is how one bad thirty seconds on
        this line used to cost a provider its whole fleet.
        """
        return {f: rec for f, rec in self._engine.reach().items()
                if rec.get('ok') is False}

    def deadExits(self):
        """What would go, where from, and why - without anything going.

        The header button opens this and nothing else. Every folder holding a
        copy is offered separately, including pinned/, because the folders
        mean different things: one says these connected once, one holds the
        only copy there is. Which of them to take a config out of is a
        judgement, so it is asked rather than assumed.
        """
        dead = self._dead()
        if not dead:
            return {'ok': True, 'folders': [], 'exits': [], 'shelved': [],
                    'aside': sum(s['count']
                                 for s in dropped.shelved().values())}
        by_base = {sweep.base_name(f): rec for f, rec in dead.items()}
        folders = dropped.folders_holding(set(by_base), self._engine.sources())
        notes = windscribe.meta()

        # One row per file per folder: the same exit in success\ and in
        # pinned\ is two files, and the sheet has to be able to say that one
        # of them is being taken and the other left.
        seen = {}
        for s in self._engine.scan():
            seen.setdefault(sweep.base_name(s.file), s)
        rows = []
        for folder in folders:
            for name in folder['files']:
                base = sweep.base_name(name)
                s = seen.get(base)
                # Whichever copy was the tested one carries the verdict; the
                # others are the same config under another name, and inherit
                # it through the base they share.
                rec = by_base.get(base) or {}
                rows.append({
                    'file': name,
                    'folder': folder['path'],
                    'tag': folder['tag'],
                    'country': s.country if s else '',
                    'city': (self._engine.city_of(s, notes)[1] if s else ''),
                    'provider': s.provider if s else '',
                    'ip': rec.get('ip', ''),
                    'why': rec.get('why', ''),
                    'at': rec.get('at'),
                })
        shelves = dropped.shelved()
        return {'ok': True,
                'folders': [{k: v for k, v in f.items() if k != 'files'}
                            for f in folders],
                'exits': rows,
                # Exits, not rows. The same config in two folders is two
                # files and one exit, and the header says how many stopped
                # answering - which is a fact about exits.
                'dead': len({sweep.base_name(r['file']) for r in rows}),
                'shelved': sorted(shelves.values(),
                                  key=lambda x: x['label']),
                'aside': sum(s['count'] for s in shelves.values())}

    def dropExits(self, folders=None):
        """Move the dead ones out of the folders that were ticked.

        Only folders deadExits() just offered, and only the files it named.
        A path arriving from the page that is not on that list is refused
        rather than acted on - this is the one call in here that moves
        somebody's configs about, and "whatever the page said" is not a good
        enough reason to.
        """
        wanted = {os.path.normcase(os.path.abspath(f))
                  for f in (folders or []) if f}
        if not wanted:
            return {'ok': False, 'error': 'Nothing was ticked.'}

        dead = self._dead()
        # By base name, so the reason is found for the pinned copy of an exit
        # as well as for the timed one the verdict was written against.
        why = {sweep.base_name(f): (rec.get('why') or '')
               for f, rec in dead.items()}
        offered = dropped.folders_holding(set(why), self._engine.sources())

        moved, failed, folders_done = [], [], []
        for folder in offered:
            if os.path.normcase(folder['path']) not in wanted:
                continue
            went, stuck = dropped.put_aside(folder['path'], folder['files'],
                                            why)
            moved += went
            failed += stuck
            if went:
                folders_done.append(folder['label'])
        if not moved and not failed:
            return {'ok': False, 'error': 'Those folders hold none of them '
                                          'any more.'}
        return {'ok': True, 'moved': len(moved), 'failed': len(failed),
                'folders': folders_done,
                'countries': self._engine.catalogue(),
                'serverCount': len(self._engine.servers())}

    def restoreDropped(self, tags=None):
        """Put them back where they came from."""
        back, stuck = dropped.put_back(tags or None)
        if not back and not stuck:
            return {'ok': False, 'error': 'There is nothing set aside.'}
        return {'ok': True, 'back': len(back), 'stuck': len(stuck),
                'countries': self._engine.catalogue(),
                'serverCount': len(self._engine.servers())}

    def exitsIn(self, country, provider=None):
        """The individual exits behind one country or city, with their times."""
        where, _, via = (country or '').partition(':')
        city = None
        if '/' in where:
            where, city = where.split('/', 1)
        return {'ok': True,
                'exits': self._engine.exits(where, provider or via or None,
                                            city)}

    # -- the ones worth keeping ------------------------------------------

    def favourites(self):
        return {'ok': True, 'codes': self._settings.get('favourites') or []}

    def toggleFavourite(self, code):
        """Star a place, or unstar it.

        Kept as picker codes rather than as countries, so that whatever can
        be picked can be starred: a country, a city inside it, one provider's
        share of a country, or a single exit. They are the same strings the
        connect button already understands, which is what stops a favourite
        meaning something the app cannot act on.
        """
        codes = list(self._settings.get('favourites') or [])
        if code in codes:
            codes.remove(code)
            on = False
        else:
            codes.append(code)
            on = True
        self._settings['favourites'] = codes
        save_settings(self._settings)
        return {'ok': True, 'codes': codes, 'on': on}

    def setSort(self, kind=None):
        """How the list is ordered. Answering-first stays underneath every
        one of them: a blocked exit is not a good answer to "sort by name"
        either."""
        kind = kind if kind in ('ping', 'name', 'load') else 'ping'
        self._settings['sortBy'] = kind
        save_settings(self._settings)
        return {'ok': True, 'sortBy': kind}

    def setKeepOnClose(self, on=False):
        self._settings['keepOnClose'] = bool(on)
        save_settings(self._settings)
        return {'ok': True, 'keepOnClose': bool(on)}

    def remember(self, code):
        self._settings['picked'] = code
        save_settings(self._settings)
        return {'ok': True}

    # -- the tunnel of your own -------------------------------------------

    def _tunnel_client(self):
        """The gost client, built from what the installer printed.

        Rebuilt whenever the settings change rather than held: the domain and
        the passwords are the whole of its identity, and an object still
        holding the old ones is the kind of thing that fails a long way from
        where it was caused.
        """
        st = self._settings
        exe = paths.gost_exe()
        if not exe:
            raise RuntimeError('no-client')
        if not st.get('tunnelDomain'):
            raise RuntimeError('not-set-up')
        want = (exe, st.get('tunnelDomain'), st.get('tunnelPassword', ''),
                st.get('tunnelApiPassword', ''))
        if not self._tunnel or self._tunnel_key != want:
            self._tunnel = tunnel.Tunnel(
                exe=exe, workdir=paths.TUNNEL_STATE, domain=want[1],
                password=want[2], api_password=want[3])
            self._tunnel_key = want
        return self._tunnel

    def tunnelPlan(self):
        """What the settings pane draws. The passwords go back as whether
        they are set, never as themselves - the page has no use for them and
        a screenshot of the settings sheet should not be a leak."""
        st = self._settings
        client = None
        try:
            client = self._tunnel_client()
        except RuntimeError:
            pass
        return {
            'mode': st.get('mode', 'surfshark'),
            'domain': st.get('tunnelDomain', ''),
            'hasPassword': bool(st.get('tunnelPassword')),
            'hasApiPassword': bool(st.get('tunnelApiPassword')),
            'hasClient': bool(paths.gost_exe()),
            'running': bool(client and client.listening()),
            'edges': client.edges() if client else [],
        }

    def installCommand(self, domain=None, user=None, password=None):
        """One line that puts the installer on the server and runs it.

        The pane used to show `./install-server.sh …`, which quietly assumed
        the script was already there - and it never is. Nothing hosts it, so
        the script travels inside the command: base64 in a single line, which
        an SSH session takes as one paste and a phone can manage.

        The Surfshark credentials are left as placeholders rather than filled
        in. They would otherwise sit in a clipboard and, on most machines, in
        a shell history file on a server, to save the person two words.
        """
        try:
            with open(paths.INSTALLER, 'rb') as f:
                blob = base64.b64encode(f.read()).decode()
        except OSError as e:
            return {'ok': False, 'error': f'the installer is missing: {e}'}
        host = (domain or '').strip() or 'yourdomain.com'
        return {'ok': True, 'command':
                "mkdir -p /opt/relay && echo '" + blob + "' | base64 -d "
                "> /opt/relay/install-server.sh && bash "
                f"/opt/relay/install-server.sh {host} "
                f"{user or '<surfshark-user>'} {password or '<surfshark-pass>'}"}

    def saveTunnel(self, domain=None, password=None, apiPassword=None):
        """Keep what was typed. Blank means unchanged, not cleared: the page
        never sends the passwords back, so treating empty as "erase" would
        wipe them every time the domain was edited."""
        if domain is not None:
            self._settings['tunnelDomain'] = domain.strip()
        if password:
            self._settings['tunnelPassword'] = password.strip()
        if apiPassword:
            self._settings['tunnelApiPassword'] = apiPassword.strip()
        save_settings(self._settings)
        self._tunnel = None
        return self.tunnelPlan()

    def setMode(self, mode):
        if mode not in ('surfshark', 'single', 'multi'):
            return {'ok': False, 'error': 'unknown-mode'}
        self._settings['mode'] = mode
        save_settings(self._settings)
        return {'ok': True, 'mode': mode}

    def testTunnel(self):
        """Carry something through it, rather than checking that a port is open.

        Three answers in one: the client runs, the far end is reachable and
        takes the API password, and traffic actually comes out the other side.
        The last is the one a port check misses - restart the server under a
        multiplexed session and what is left answers the connection and then
        503s everything, which looks exactly like a healthy tunnel from here.

        A wedged session is worth recovering from rather than reporting, so
        the client is restarted once and asked again. Once, and then it is
        told to you: a Test button that retries forever is a Test button that
        never finishes.

        Past that there is one more thing worth trying, because there is one
        more thing that is worth telling apart. A restart does not help when
        the CDN address the domain resolves to has been filtered, and from
        in here that failure is indistinguishable from a dead server - both
        are a 503. So the third attempt asks which it is before acting, and
        only goes looking for another address when that is the answer. The
        other verdicts are reported rather than worked around: a scan cannot
        fix a server that is down, and running one anyway would spend the
        time and then blame the wrong thing.
        """
        try:
            client = self._tunnel_client()
            client.start()
            seen, restarted, found = None, False, []
            try:
                seen = client.probe()
            except Exception:
                restarted = True
                try:
                    client.restart()
                    seen = client.probe()
                except Exception:
                    verdict, why = client.diagnose()
                    if verdict not in ('edges-blocked', 'wrong-zone'):
                        return {'ok': False, 'error': why, 'verdict': verdict,
                                'restarted': True,
                                'running': client.listening()}
                    found, tally = client.rescan()
                    if not found:
                        return {'ok': False, 'verdict': 'no-way-in',
                                'restarted': True,
                                'error': f'none of the {sum(tally.values())} '
                                         'addresses tried could reach your '
                                         'server - it looks like the domain '
                                         'is being filtered rather than the '
                                         'address',
                                'running': client.listening()}
                    try:
                        seen = client.probe()
                    except Exception as e:
                        return {'ok': False, 'verdict': 'still-down',
                                'repaired': found, 'restarted': True,
                                'error': f'found a way in at {found[0]} and '
                                         f'the tunnel still will not carry '
                                         f'anything: {str(e)[:120]}',
                                'running': client.listening()}
            return {'ok': True, 'exit': client.current_exit(),
                    'seen': seen, 'restarted': restarted,
                    'repaired': found, 'edges': client.edges(),
                    'running': client.listening()}
        except RuntimeError as e:
            return {'ok': False, 'error': str(e)}
        except Exception as e:
            return {'ok': False, 'error': str(e)[:200]}

    def rescanEdges(self):
        """Look for a way in now, whatever state the tunnel is in.

        The same repair testTunnel reaches for on its own, on a button, for
        when somebody would rather force it than argue with a symptom. It
        rewrites the configuration and restarts the client onto it, which is
        the only thing that makes a running client read the new addresses -
        the server's API rewrites the server's chains, not this end's.
        """
        try:
            client = self._tunnel_client()
            found, tally = client.rescan()
            return {'ok': bool(found), 'edges': found,
                    'answered': tally.get('ok', 0),
                    'tried': sum(tally.values()),
                    'running': client.listening(),
                    'error': '' if found else
                             'no address reached your server from this line'}
        except RuntimeError as e:
            return {'ok': False, 'error': str(e)}
        except Exception as e:
            return {'ok': False, 'error': str(e)[:200]}

    def status(self):
        return self._engine.status()

    def connect(self, country):
        if self._busy:
            return {'ok': False, 'error': 'busy'}
        self._busy = True
        self._retray('busy')

        # "fr" is France through whichever provider answers first;
        # "fr:windscribe" is France through that one. A country reached by
        # both is one place with two ways in, and which way in is a real
        # choice - they are different companies, different addresses and,
        # on this line, different odds of being filtered.
        # Three shapes, narrowing. "fr" is France through whichever provider
        # answers first; "fr:windscribe" is France through that one;
        # "file:<config>" is one named exit, picked off a list that had its
        # measured time beside it - so racing its neighbours instead would be
        # answering a question nobody asked.
        only, city = None, None
        if (country or '').startswith('file:'):
            only = country[5:]
            where, want = None, None
        else:
            where, _, want = (country or '').partition(':')
            want = want if want in accounts.PROVIDERS else None
            # "fr/par" is one city inside France. A slash rather than another
            # colon so that the two never have to be told apart by counting
            # them - a city and a provider are different kinds of narrowing
            # and reading them wrong connects somewhere else entirely.
            if '/' in where:
                where, city = where.split('/', 1)
        # A saved pick can outlive the provider it names - switched off in
        # the roster, or its account removed. Asking for it anyway is
        # guaranteed to find nothing and to say so in terms of folders, which
        # is not what went wrong. The country on its own still works.
        if want and self._engine.providers is not None \
                and want not in self._engine.providers:
            want = None

        # Which way out. Three of them now: straight to a provider's exit,
        # through a tunnel of our own, or through the tunnel and out of an
        # exit beyond it. The picker on the card sets this; the parse above
        # says which country, and is the same question either way.
        mode = self._settings.get('mode', 'surfshark')

        def work():
            try:
                if mode == 'surfshark':
                    result = self._engine.connect(
                        where, lambda p: self._emit('Progress', p),
                        provider=want, only=only, city=city)
                else:
                    result = self._through_tunnel(where, mode)
                self._since = time.time()
                self._retray('on')
                self._emit('Connected', result)
                # The independent check runs after the window already says
                # connected, because it is a round trip to somebody else's
                # server and the connection does not depend on it.
                self._confirm()
            except Exception as e:
                kind = str(e).split(':')[0] if str(e) else e.__class__.__name__
                try:
                    self._engine.disconnect(quiet=True)
                except Exception:
                    pass
                self._retray('off')
                # The port goes with it: the one failure the page has to name
                # a number in is the port being held by somebody else, and
                # the page has no other way to know which port was tried.
                self._emit('Failed', {'kind': kind, 'detail': str(e),
                                      'port': self._engine.port})
            finally:
                self._busy = False

        threading.Thread(target=work, daemon=True).start()
        return {'ok': True}

    def _through_tunnel(self, country, mode):
        """Connect by way of the user's own server.

        Multi-IP mode is the only one with a choice to make, and it makes it
        on the server: the exit is set through the API before the worker
        comes up, so the change is in place by the time anything is carried.
        Picking here rather than probing from this machine is deliberate -
        see Engine.address_for.
        """
        progress = lambda p: self._emit('Progress', p)
        client = self._tunnel_client()
        progress({'phase': 'starting', 'country': '', 'city': ''})
        client.start()

        label = 'your server'
        if mode == 'multi':
            user, password = self._engine.credentials()
            if country in (None, '', 'auto'):
                # No country asked for, so leave the server on whatever it
                # was last set to rather than picking one on its behalf.
                label = 'chosen exit'
            else:
                server, ip, _host = self._engine.address_for(country)
                client.set_exit(ip, user, password, name=country)
                label = country

        return self._engine.connect_tunnel(
            client.address('multi' if mode == 'multi' else 'single'),
            label, progress)

    def _confirm(self):
        """Ask Cloudflare, through the proxy, what address it sees.

        This is the only evidence the user has that the app did what it
        claims, so a failure here is reported as unconfirmed rather than
        hidden - but it never turns a working connection into a failed one.
        """
        def work():
            info = self._engine.exit_info
            if not info:
                return
            try:
                info['seen_as'] = self._engine.verify()
            except Exception as e:
                info['unconfirmed'] = str(e)[:120]
            if self._engine.exit_info is info:
                self._emit('Connected', self._engine.status())
        threading.Thread(target=work, daemon=True).start()

    def cancel(self):
        self._engine.cancelled.set()
        return {'ok': True}

    def disconnect(self):
        minutes = 0
        if self._since:
            minutes = max(1, int((time.time() - self._since) / 60))
        self._since = None
        try:
            self._engine.disconnect()
        except Exception as e:
            return {'ok': False, 'error': str(e)}
        self._retray('off')
        # Straight away rather than on the next tick: the address just
        # changed back and the window is still showing the exit's.
        threading.Thread(target=self._look_up_self, daemon=True).start()
        return {'ok': True, 'session': {'minutes': minutes}}

    def minimise(self):
        if self._window:
            self._window.minimize()


def install_safety(api):
    """Put the machine back on every exit path there is.

    atexit covers the ordinary ones and the signal handlers cover a terminate.
    Nothing covers being killed outright, which is what the saved-settings
    file on disk is for.
    """
    def undo(*_):
        # The proxy is a detached process on purpose, so that a wedged
        # connection cannot take the window down with it. The cost of that is
        # this: closing the window restores the Windows proxy but leaves the
        # worker listening, so the tunnel is up and nothing is using it - and
        # only the registry says so.
        #
        # Off by default, then. Somebody who wants the connection to outlive
        # the window can say so, and the setting is where they would look.
        if not api._settings.get('keepOnClose'):
            try:
                api._engine.disconnect(quiet=True)
            except Exception:
                pass
        try:
            # Same rule as disconnect: only ours to put back if the machine
            # is pointed at our port. Another copy of this app, on another
            # port, is somebody's live connection and not our business.
            if api._sysproxy.engaged_for(api._engine.port):
                api._sysproxy.restore()
        except Exception:
            pass
        # A pinning run is an ordinary child of this process rather than
        # something elevated, so closing the window would otherwise leave a
        # PowerShell writing into a folder nobody is watching any more. What
        # it has already written stays written; that is the point of one file
        # at a time rather than a batch at the end.
        try:
            api._pin.cancel()
        except Exception:
            pass

    atexit.register(undo)
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, lambda *_: (undo(), os._exit(0)))
        except (ValueError, OSError):
            pass
    return undo


class Tray:
    """A real notification-area icon.

    It carries the state in its tooltip, which is the only way to know
    whether you are routed while the window is closed, and its menu is the
    one place that can actually quit - closing the window deliberately does
    not, because a connection that dies when you tidy your desktop is not a
    connection you can rely on.
    """

    def __init__(self, api, on_quit):
        self.api = api
        self.on_quit = on_quit
        self.icon = None
        self.error = None

    def _image(self, state):
        from PIL import Image
        img = Image.open(paths.ICON_PNG).convert('RGBA')
        if state != 'on':
            # Same mark, drained of colour, so the tray reads at a glance
            # without needing two drawings kept in step.
            grey = img.convert('LA').convert('RGBA')
            img = Image.blend(grey, img, 0.25 if state == 'busy' else 0.0)
        return img.resize((64, 64), Image.LANCZOS)

    def start(self):
        """Returns whether it actually appeared. A tray icon that quietly
        fails to exist is worse than one that is absent by design - it was
        missing from the first build because pystray is imported here rather
        than at the top, and nothing said so."""
        try:
            import pystray
        except Exception as e:
            self.error = repr(e)
            return False
        menu = pystray.Menu(
            pystray.MenuItem('Show Relay', self._show, default=True),
            pystray.MenuItem('Disconnect', self._disconnect,
                             enabled=lambda _: self.api._engine.running() is not None),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem('Quit', self._quit))
        self.icon = pystray.Icon(APP_NAME, self._image('off'),
                                 f'{APP_NAME} - not connected', menu)
        threading.Thread(target=self.icon.run, daemon=True).start()
        return True

    def set_state(self, state):
        if not self.icon:
            return
        text = {'on': 'connected', 'busy': 'connecting…'}.get(state, 'not connected')
        try:
            self.icon.icon = self._image(state)
            self.icon.title = f'{APP_NAME} — {text}'
        except Exception:
            pass

    def _show(self, *_):
        try:
            self.api._window.show()
            self.api._window.restore()
        except Exception:
            pass

    def _disconnect(self, *_):
        try:
            self.api.disconnect()
            self.api._emit('Disconnected', {})
        except Exception:
            pass

    def _quit(self, *_):
        try:
            self.icon.stop()
        except Exception:
            pass
        self.on_quit()


def dress_window():
    """Paint the real title bar in this app's own colours.

    Not a frameless window. Going frameless on pywebview costs the resize
    handles, Aero Snap, double-click-to-maximise, minimise from the taskbar,
    the open and close animations and correct behaviour under display
    scaling - each with an open unresolved issue, and pywebview restores none
    of it. A custom caption drawn in HTML buys a matching colour and pays for
    it with every one of those.

    Windows 11 will recolour the caption it is already drawing instead:
    DWMWA_CAPTION_COLOR takes the bar, DWMWA_TEXT_COLOR takes the title, and
    DWMWA_BORDER_COLOR takes the hairline around the window. The minimise,
    maximise and close glyphs are the system's own and follow the caption -
    on a dark one they come out white, which is the ask - and they stay the
    system's, so they keep their hover colours, their tooltips, their snap
    layouts flyout and their hit targets at the very edge of the screen.

    Silently does nothing before Windows 11 22000, where the attributes are
    simply not there. The app looks like it did; nothing breaks.
    """
    if os.name != 'nt':
        return None

    DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    DWMWA_BORDER_COLOR = 34
    DWMWA_CAPTION_COLOR = 35
    DWMWA_TEXT_COLOR = 36

    # COLORREF is 0x00BBGGRR, which is the reverse of every hex colour in the
    # stylesheet. #111113 is the window's own background: the caption stops
    # being a lid on the app and becomes the top of it.
    def colorref(hexrgb):
        r, g, b = (int(hexrgb[i:i + 2], 16) for i in (0, 2, 4))
        return ctypes.c_int((b << 16) | (g << 8) | r)

    handle = ctypes.windll.user32.FindWindowW(None, APP_NAME)
    if not handle:
        return None

    def put(attribute, value):
        try:
            return ctypes.windll.dwmapi.DwmSetWindowAttribute(
                ctypes.wintypes.HWND(handle), ctypes.c_uint(attribute),
                ctypes.byref(value), ctypes.sizeof(value)) == 0
        except Exception:
            return False

    done = {
        'dark': put(DWMWA_USE_IMMERSIVE_DARK_MODE, ctypes.c_int(1)),
        'caption': put(DWMWA_CAPTION_COLOR, colorref('111113')),
        'text': put(DWMWA_TEXT_COLOR, colorref('EDEEF0')),
        'border': put(DWMWA_BORDER_COLOR, colorref('2A2C30')),
    }
    return done


def _flat_png(width, height, rgb):
    """A solid rectangle, built here rather than kept as a wall of base64.

    Only the Windscribe captcha check wants these, and only for its geometry:
    it needs an image with a known natural width to measure the drawn scale
    against, and a second one to move. What they look like is irrelevant, so
    a file is not worth carrying.
    """
    import struct
    import zlib
    raw = b''.join(b'\x00' + bytes(rgb) * width for _ in range(height))

    def chunk(tag, data):
        body = tag + data
        return (struct.pack('>I', len(data)) + body
                + struct.pack('>I', zlib.crc32(body) & 0xffffffff))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw))
           + chunk(b'IEND', b''))
    import base64
    return base64.b64encode(png).decode()


# The sizes a real puzzle arrives at, measured from one: a 700x400 background
# with a 240x240 piece, cut at top=56. Not round numbers picked to be easy -
# at the width the sheet draws them the scale is about 0.45, so an answer that
# forgot to convert back out of drawn pixels is wrong by more than double, and
# a piece that was not scaled with the background covers a third of it.
UI_CHECK_BG = _flat_png(700, 400, (40, 60, 90))
UI_CHECK_PIECE = _flat_png(240, 240, (200, 180, 60))
UI_CHECK_TOP = 56


def ui_check(window):
    """Open the window, photograph it, and say what the page thinks it shows.

        python app/main.py --ui-check

    A window that loads and then does nothing is this app's characteristic
    failure - a script error early on leaves a page that looks finished and
    answers nothing, and neither --selftest nor --probe-test can see it,
    because both stop before there is a window. This one runs the real page,
    reads back the errors it collected before anything else could throw, and
    leaves a picture of each sheet in .state\\ui-check\\.

    It closes the window when it is done, so it is a check rather than a way
    to start the app.
    """
    out = os.path.join(paths.STATE_DIR, 'ui-check')
    os.makedirs(out, exist_ok=True)
    said = {}

    def our_window():
        """This process's own top-level window, by process id.

        The caption is not an identity: a built Relay open beside the one
        being checked answers to the same name, and whichever Windows hands
        back first is the one that gets photographed.
        """
        mine, found = os.getpid(), []

        @ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.wintypes.HWND,
                            ctypes.wintypes.LPARAM)
        def each(handle, _):
            length = ctypes.windll.user32.GetWindowTextLengthW(handle)
            if length:
                buf = ctypes.create_unicode_buffer(length + 1)
                ctypes.windll.user32.GetWindowTextW(handle, buf, length + 1)
                if buf.value == APP_NAME:
                    owner = ctypes.wintypes.DWORD()
                    ctypes.windll.user32.GetWindowThreadProcessId(
                        handle, ctypes.byref(owner))
                    if owner.value == mine:
                        found.append(handle)
                        return False
            return True

        ctypes.windll.user32.EnumWindows(each, 0)
        return found[0] if found else 0

    def shot(name):
        """Our own window, asked to draw itself. In uishot, so that the
        layout audit takes the same photographs rather than its own.

        The process id goes with it. Finding the window by caption alone is
        what main fixed: a built Relay open beside the one being checked
        answers to the same name, and whichever Windows hands back first is
        the one that gets photographed - every picture in one such run came
        back 237x39 and said 600x1110.
        """
        return uishot.shot(name, out, APP_NAME, os.getpid())

    try:
        time.sleep(2.5)
        # In front, or the picture is of whatever is sitting on top of us.
        try:
            handle = ctypes.windll.user32.FindWindowW(None, APP_NAME)
            ctypes.windll.user32.SetForegroundWindow(handle)
            time.sleep(0.5)
        except Exception:
            pass

        said['probe'] = window.evaluate_js('window.__probe && window.__probe()')
        said['errors'] = window.evaluate_js('window.__errs')
        said['main'] = shot('main')

        # How long opening the exit picker takes, first time and second.
        # Clicked and read back in two steps on purpose: showModal() inside a
        # synchronous evaluate_js deadlocks WebView2's message loop, and the
        # window simply never comes back.
        for _ in range(2):
            window.evaluate_js("document.getElementById('pick').click()")
            time.sleep(0.8)
            window.evaluate_js("document.getElementById('picker').close()")
            time.sleep(0.4)
        said['pickerMs'] = window.evaluate_js('window.__pickerMs')

        # The connect button wears its connected look at rest, which is the
        # whole point of it and the one thing a picture of the idle window
        # cannot show. Pretended rather than connected: this is a check, and
        # opening a real tunnel to take a photograph would be rude.
        for mode, name in (('busy', 'act-busy'), ('on', 'act-on')):
            window.evaluate_js(
                "document.getElementById('act').dataset.mode = %r" % mode)
            time.sleep(0.8)
            said[name] = shot(name)
        window.evaluate_js(
            "document.getElementById('act').dataset.mode = 'off'")

        window.evaluate_js("document.getElementById('settings').click()")
        # Long enough for the sweep readout to arrive: it shells out to
        # PowerShell to ask whether another VPN holds the default route, and
        # reading it sooner reads an empty line and calls that a result.
        time.sleep(4)
        # Does the front of the app fit the window it is given? A card that
        # has grown a control since the size was chosen scrolls, or clips the
        # thing above it, and neither shows up in a screenshot of the part
        # that did fit.
        said['fits'] = window.evaluate_js(
            "JSON.stringify({needs: document.querySelector('.app').scrollHeight,"
            " has: window.innerHeight})")
        said['settingsOpen'] = window.evaluate_js(
            "document.getElementById('prefs').open")
        said['settings'] = shot('settings')
        # What the two new sections are actually saying, which is the part a
        # picture of a scrolled sheet can miss.
        # -- the settings, as a stack ---------------------------------
        #
        # A list of four, then the one you picked, then back. Driven rather
        # than looked at: the thing that can be wrong is whether the other
        # three screens are really gone or merely scrolled past, and a
        # screenshot of the right one showing proves neither.
        said['menu'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.menu__name'))"
            ".map(e => e.textContent)")
        said['screensAtRest'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.screen'))"
            ".filter(s => !s.hidden).length")
        window.evaluate_js(
            "document.querySelector('.menu__row[data-goto=servers]').click()")
        time.sleep(0.5)
        said['screenOpen'] = window.evaluate_js(
            "(document.querySelector('.screen:not([hidden])')||{}).dataset"
            " && document.querySelector('.screen:not([hidden])').dataset.screen")
        said['onlyOneScreen'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.screen'))"
            ".filter(s => !s.hidden).length === 1")
        said['menuGoneWhileIn'] = window.evaluate_js(
            "document.getElementById('prefsMenu').hidden")
        said['backOffered'] = window.evaluate_js(
            "!document.getElementById('prefsBack').hidden")
        said['titleFollows'] = window.evaluate_js(
            "document.getElementById('prefsTitle').textContent")
        window.evaluate_js("document.getElementById('prefsBack').click()")
        time.sleep(0.4)
        said['backWorks'] = window.evaluate_js(
            "!document.getElementById('prefsMenu').hidden"
            " && document.querySelectorAll('.screen:not([hidden])').length === 0"
            " && document.getElementById('prefsBack').hidden")
        said['titleBack'] = window.evaluate_js(
            "document.getElementById('prefsTitle').textContent")
        # Into the one the rest of this check needs.
        window.evaluate_js(
            "document.querySelector('.menu__row[data-goto=sign-in]').click()")
        time.sleep(0.4)

        # -- the whys, moved into (i) buttons -------------------------------
        #
        # The paragraphs are not deleted, they are relocated - so the test is
        # that the text still exists, not merely that the paragraphs are gone.
        said['infoButtons'] = window.evaluate_js(
            "document.querySelectorAll('.pane .info').length")
        said['whysLeftInline'] = window.evaluate_js(
            "document.querySelectorAll('.pane__why').length")
        said['infoKeptTheWords'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.info__bubble'))"
            ".every(b => b.textContent.trim().length > 40)")
        said['infoHiddenUntilAsked'] = window.evaluate_js(
            "getComputedStyle(document.querySelector('.info__bubble'))"
            ".visibility")

        # -- the roster -----------------------------------------------------
        said['acctPane'] = window.evaluate_js(
            "!!document.getElementById('prefAccounts')")
        said['acctPill'] = window.evaluate_js(
            "document.getElementById('acctPill').textContent")
        said['acctRows'] = window.evaluate_js(
            "document.querySelectorAll('.acct__row').length")
        said['acctSays'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.acct__row')).map("
            "r => r.dataset.provider + ':' + r.dataset.active)")
        # One form for both providers, and the provider switch changes what
        # it asks for rather than which form is shown.
        # The provider chooser, which is a different question from the
        # roster above it: an account can be signed in and still be one you
        # do not want exits from today.
        said['useBoxes'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.use__opt input'))"
            ".map(b => b.value + ':' + (b.disabled ? 'none' : b.checked))")
        said['useCounts'] = window.evaluate_js(
            "[document.getElementById('useSurfsharkN').textContent,"
            " document.getElementById('useWindscribeN').textContent]")

        window.evaluate_js("document.getElementById('acctAdd').click()")
        time.sleep(0.5)
        # A dialog over the pane, not a form unfolding inside the list - the
        # list is what "is this one already here" needs to still see.
        said['acctFormOpen'] = window.evaluate_js(
            "document.getElementById('acctDlg').open")
        said['acctCapSurfshark'] = window.evaluate_js(
            "document.getElementById('acctUserCap').textContent")
        window.evaluate_js(
            "document.querySelector('#acctWhich [data-provider=windscribe]').click()")
        time.sleep(0.3)
        said['acctCapWindscribe'] = window.evaluate_js(
            "document.getElementById('acctUserCap').textContent")
        said['acctTwoShown'] = window.evaluate_js(
            "!document.getElementById('acctTwoWrap').hidden")
        said['acctForm'] = shot('settings-add-account')
        window.evaluate_js("document.getElementById('acctCancel').click()")
        time.sleep(0.3)
        said['acctFormClosed'] = window.evaluate_js(
            "!document.getElementById('acctDlg').open")
        said['accounts'] = shot('settings-accounts')

        # -- the Windscribe puzzle ------------------------------------
        #
        # Driven rather than looked at, because the part that can be wrong is
        # arithmetic and not appearance: the answer has to be given in the
        # background image's own pixels, and what the drag produces is
        # positions in a box that is a different width. A screenshot of a
        # piece in the right place proves none of that.
        #
        # Letting go now sends the puzzle, so the bridge call is replaced
        # first with one that writes down what it was given. Without that,
        # running this test would attempt a real login against a real
        # account - and there is a rate limiter and a lockout on the other
        # end of that. Recording it is also the better test: what matters is
        # what would have been sent.
        said['wsPane'] = window.evaluate_js(
            "!!document.getElementById('acctWsTools')")
        window.evaluate_js("""
            (function () {
              window.__wsReal = window.pywebview.api.windscribeFinish;
              window.__wsSent = null;
              window.pywebview.api.windscribeFinish =
                (token, solution, trailX, trailY, code2fa) => {
                  window.__wsSent = {token, solution, trailX, trailY, code2fa};
                  return Promise.resolve(
                    {ok: false, error: 'not sent - ui-check stub'});
                };
            })()""")

        # The token comes from the call that fetched the puzzle, and this
        # test skips that call - so it is stood in for here. Without it the
        # submit refuses, correctly, and the drag looks broken for a reason
        # that only exists in the test.
        # With the add-account dialog already up, which is where a real
        # puzzle arrives from - one modal opening over another, and the drag
        # having to work in the top one. Shown on its own, this test passed
        # while the flow it stands for was never tried.
        window.evaluate_js("acctShowForm(true); acctSetProvider('windscribe')")
        time.sleep(0.5)
        window.evaluate_js(
            f"wsShowCaptcha({{kind:'slider',top:{UI_CHECK_TOP},"
            f"background:'{UI_CHECK_BG}',slider:'{UI_CHECK_PIECE}'}});"
            "ws.token = 'ui-check-token'")
        time.sleep(1.5)
        said['captchaOverForm'] = window.evaluate_js(
            "document.getElementById('acctDlg').open"
            " && document.getElementById('wsCapDlg').open")
        # The one that decides whether it can be dragged at all: the piece
        # has to be the topmost thing under the pointer where it is drawn.
        said['captchaOnTop'] = window.evaluate_js(
            "(() => { const p = document.getElementById('wsCapPc');"
            " const b = p.getBoundingClientRect();"
            " const hit = document.elementFromPoint(b.left + b.width / 2,"
            "                                       b.top + b.height / 2);"
            " return hit === p ? 'the piece' : (hit && hit.id) || 'something else'; })()")
        # A dialog, so that "is it up" is a question about the window rather
        # than about a hidden attribute somewhere down the settings sheet.
        said['wsCaptchaShown'] = window.evaluate_js(
            "document.getElementById('wsCapDlg').open")
        said['wsScale'] = window.evaluate_js('ws.scale')
        # The two widths the clamp is built out of. Reported because when a
        # drag comes back pinned at zero these are the only two numbers that
        # can be responsible, and neither is visible in a screenshot.
        said['wsStageW'] = window.evaluate_js(
            "document.getElementById('wsCapStage').getBoundingClientRect().width")
        said['wsPieceW'] = window.evaluate_js(
            "document.getElementById('wsCapPc').offsetWidth")
        # The piece is scaled with the background rather than drawn at its own
        # 240px, and sits where the cut is. Both are what make the puzzle
        # solvable at all: an unscaled piece covers a third of the picture.
        said['wsPieceTop'] = window.evaluate_js(
            "document.getElementById('wsCapPc').style.top")
        said['wsCaptcha'] = shot('settings-windscribe')

        # A drag in three moves, so the trail has to collect more than the
        # endpoint. Dispatched at the piece, which is the handle, and from a
        # cursor 7px below the top of the picture - the y values sent are
        # measured from there, so a wrong origin shows up as a trail of the
        # wrong numbers rather than as anything visible.
        window.evaluate_js("""
            (function () {
              const pc = document.getElementById('wsCapPc');
              const box = document.getElementById('wsCapStage')
                            .getBoundingClientRect();
              const y = box.top + 7;
              const at = (type, x) => pc.dispatchEvent(new PointerEvent(type, {
                clientX: x, clientY: y, pointerId: 1, bubbles: true }));
              at('pointerdown', box.left + 10);
              at('pointermove', box.left + 40);
              at('pointermove', box.left + 80);
              at('pointermove', box.left + 120);
              at('pointerup', box.left + 120);
            })()""")
        # Let go is the answer, so by now it should already have gone.
        time.sleep(1.2)

        said['wsLeft'] = window.evaluate_js('ws.left')
        said['wsSolution'] = window.evaluate_js('wsSolution()')
        # The piece must have moved, and the rail's knob with it.
        said['wsPieceMoved'] = window.evaluate_js(
            "document.getElementById('wsCapPc').style.transform")
        said['wsKnobFollowed'] = window.evaluate_js(
            "document.getElementById('wsCapKnob').style.transform")

        # What letting go actually sent - the whole point of the change, and
        # the thing that was silently not happening before.
        said['wsAutoSent'] = window.evaluate_js('!!window.__wsSent')
        said['wsSentSolution'] = window.evaluate_js(
            'window.__wsSent && window.__wsSent.solution')
        said['wsSentMatches'] = window.evaluate_js(
            'window.__wsSent && window.__wsSent.solution === wsSolution()')
        said['wsSentTrail'] = window.evaluate_js(
            'window.__wsSent && window.__wsSent.trailX.length')
        # The trail carries the piece's position, not the cursor's - so the
        # last x has to be the piece's left, and not the pointer's distance
        # across the screen.
        said['wsTrailIsPiece'] = window.evaluate_js(
            'window.__wsSent && window.__wsSent.trailX[window.__wsSent.trailX.length - 1]'
            ' === Math.round(ws.left)')
        # And y is measured from the top of the picture, so a cursor 7px down
        # reads as 7 rather than as several hundred.
        said['wsTrailY'] = window.evaluate_js(
            'window.__wsSent && window.__wsSent.trailY[0]')
        said['wsTrailWhole'] = window.evaluate_js(
            'window.__wsSent && window.__wsSent.trailX.every(Number.isInteger)'
            ' && window.__wsSent.trailY.every(Number.isInteger)')
        # An answer has to land inside what the puzzle can accept, which is
        # the background's own width less the piece - 460 for a real one.
        said['wsSolutionInRange'] = window.evaluate_js(
            'wsSolution() >= 0 && wsSolution() <= 700 - 240')
        # And a rejected puzzle takes the dialog down with it rather than
        # leaving a spent token on screen.
        said['wsClosedOnFail'] = window.evaluate_js(
            "!document.getElementById('wsCapDlg').open && ws.token === null")
        # Where the reason lands moved with the pane: the Windscribe section
        # became one row of the account roster, and a rejected puzzle now
        # reports on the add-account form it was opened from.
        said['wsToldWhy'] = window.evaluate_js(
            "document.getElementById('acctNewSaid').textContent")

        window.evaluate_js(
            'window.pywebview.api.windscribeFinish = window.__wsReal')
        window.evaluate_js('wsHideCaptcha()')
        said['wsCleared'] = window.evaluate_js(
            "!document.getElementById('wsCapDlg').open && ws.token === null")
        # And the form it opened over. Left up, it is a modal covering every
        # screenshot taken after this point - which is how the exits shot
        # came back showing the add-account dialog.
        window.evaluate_js('acctShowForm(false)')
        time.sleep(0.4)
        said['formClosedAfterCaptcha'] = window.evaluate_js(
            "!document.getElementById('acctDlg').open")
        said['sweep'] = window.evaluate_js(
            "document.getElementById('sweepSaid').textContent")
        said['sweepFolder'] = window.evaluate_js(
            "document.getElementById('sweepFolder').textContent")
        # The tunnel pane is the one with a command in it, and a command that
        # has been mangled is worth catching here rather than on the server.
        window.evaluate_js(
            "document.getElementById('prefTunnel').scrollIntoView({block:'start'})")
        time.sleep(0.6)
        said['tunnelShot'] = shot('settings-tunnel')
        said['tunnelPill'] = window.evaluate_js(
            "document.getElementById('tunnelPill').textContent")
        said['tunnelCmd'] = window.evaluate_js(
            "document.getElementById('tunnelCmd').textContent")
        said['wayNote'] = window.evaluate_js(
            "document.getElementById('wayNote').textContent")
        # And the bottom of it, where the three buttons are. Three in a row
        # is where a row stops fitting, and it fits or it does not at this
        # width - which is a thing to see rather than to reason about.
        window.evaluate_js(
            "document.getElementById('prefTunnel').scrollIntoView({block:'end'})")
        time.sleep(0.6)
        said['tunnelRowShot'] = shot('settings-tunnel-row')
        window.evaluate_js(
            "document.getElementById('prefSweep').scrollIntoView({block:'start'})")
        time.sleep(0.6)
        said['settingsSweep'] = shot('settings-sweep')
        window.evaluate_js(
            "document.getElementById('prefSweep').scrollIntoView({block:'end'})")
        time.sleep(0.6)
        said['settingsEnd'] = shot('settings-end')
        said['sites'] = window.evaluate_js(
            "document.getElementById('sweepSites').value")
        said['siteFolders'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('#siteFolders .chip'))"
            ".map(c => c.textContent)")

        # The narrowing controls, driven rather than looked at. Every one of
        # them goes out to Python and comes back with a different number, and
        # a chip that looks selected while the count has not moved is the
        # failure this cannot see any other way.
        #
        # Whatever it finds is put back at the end: these choices are kept
        # between runs, and a check that quietly rewrites a setting is a check
        # that costs more than it is worth.
        was = window.evaluate_js('window.__probe()')
        said['choices'] = {'before': {'scope': was.get('sweepScope'),
                                      'chosen': was.get('sweepChosen'),
                                      'count': was.get('sweepCount')}}

        def press(selector):
            # Missing is a result, not a crash. A folder whose addresses have
            # never been looked up has no company chips at all, and that is a
            # state worth checking rather than one that should stop the check.
            return window.evaluate_js(
                "(() => { const el = document.querySelector(%s);"
                " if (!el || el.disabled) return false;"
                " el.click(); return true; })()" % json.dumps(selector))

        def click_and_read(selector, label):
            if not press(selector):
                said['choices'][label] = 'not offered'
                return None
            time.sleep(1.2)
            now = window.evaluate_js('window.__probe()')
            said['choices'][label] = {'scope': now.get('sweepScope'),
                                      'chosen': now.get('sweepChosen'),
                                      'count': now.get('sweepCount'),
                                      'minutes': now.get('sweepMinutes')}
            return now

        def pin_and_read(selector, label):
            if not press(selector):
                said['pinChoices'][label] = 'not offered'
                return None
            time.sleep(1.2)
            now = window.evaluate_js('window.__probe()')
            said['pinChoices'][label] = {'route': now.get('pinRoute'),
                                         'port': now.get('pinPort'),
                                         'addresses': now.get('pinMaxIps'),
                                         'blocked': now.get('pinBlocked')}
            return now

        click_and_read('.scope__row[data-scope="company"]', 'onePerCompany')
        click_and_read('#sweepLords .chip', 'andOneCompany')
        said['choicesShot'] = shot('settings-choices')

        # And what Start looks like once it is running, which is a state no
        # picture of the sheet at rest can show. Pretended, not started: a
        # sweep takes an afternoon and takes the connection down with it.
        window.evaluate_js(
            "document.getElementById('sweepGo').dataset.mode = 'running'")
        time.sleep(0.9)
        said['runningShot'] = shot('settings-running')
        window.evaluate_js(
            "document.getElementById('sweepGo').dataset.mode = 'idle'")

        # The cap, stepped rather than typed - the two chevrons replaced the
        # browser's own spinner and nothing else exercises them.
        click_and_read('#sweepFirstUp', 'cappedUp')
        said['capField'] = window.evaluate_js(
            "document.getElementById('sweepFirst').value")
        click_and_read('#sweepFirstDown', 'cappedDown')
        window.evaluate_js(
            "document.getElementById('sweepFirst').value = '';"
            "document.getElementById('sweepFirst')"
            ".dispatchEvent(new Event('change'))")
        time.sleep(1.0)

        click_and_read('#sweepLords .chip', 'chipOffAgain')
        click_and_read('.scope__row[data-scope="%s"]'
                       % (was.get('sweepScope') or 'all'), 'restored')

        # -- and the section above it, which is where the files come from --
        #
        # Pinning has a folder in, a folder out and one decision in between,
        # and that decision is the only half of it that can be wrong. Driven
        # rather than photographed: a route that looks chosen on screen while
        # Python still believes the other one is exactly the failure a picture
        # cannot show.
        window.evaluate_js(
            "document.getElementById('prefPin').scrollIntoView({block:'start'})")
        time.sleep(0.9)
        said['pin'] = {
            'said': window.evaluate_js(
                "document.getElementById('pinSaid').textContent"),
            'route': window.evaluate_js(
                "document.getElementById('pinRouteSaid').textContent"),
            'from': window.evaluate_js(
                "document.getElementById('pinFolder').textContent"),
            'into': window.evaluate_js(
                "document.getElementById('pinOut').textContent"),
            'waiting': window.evaluate_js(
                "document.getElementById('pinCount').textContent"),
            # Blank until the proxy has really been asked; jade or red after.
            'proxyNode': window.evaluate_js(
                "document.getElementById('pinRouteStep').dataset.live || ''"),
            # The way out of the pane: what it wrote is worth nothing until
            # something is reading from it.
            'use': window.evaluate_js(
                "Array.from(document.querySelectorAll('#pinUse .chip'))"
                ".map(c => c.textContent)"),
        }
        said['pinShot'] = shot('settings-pin')

        was_pin = window.evaluate_js('window.__probe()')
        said['pinChoices'] = {'before': {'route': was_pin.get('pinRoute'),
                                         'port': was_pin.get('pinPort'),
                                         'addresses': was_pin.get('pinMaxIps'),
                                         'waiting': was_pin.get('pinTotal')}}
        pin_and_read('.seg__opt[data-route="direct"]', 'straightOut')
        said['pinDirectShot'] = shot('settings-pin-direct')
        pin_and_read('.seg__opt[data-route="proxy"]', 'throughAProxy')
        pin_and_read('#pinMaxUp', 'oneMoreAddress')
        pin_and_read('#pinMaxDown', 'oneFewer')
        # Put back what it found. These are kept between runs, and a check
        # that quietly rewrites a setting costs more than it is worth.
        pin_and_read('.seg__opt[data-route="%s"]'
                     % (was_pin.get('pinRoute') or 'proxy'), 'restored')

        # And what it looks like with something on it, which no picture of
        # the sheet at rest can show. The events below are the resolver's own
        # vocabulary in its own order - the same ones pin-test.py asserts the
        # reader turns its output into - replayed into the page rather than
        # earned, because earning them means writing a folder full of files
        # to take a photograph.
        window.evaluate_js(
            "window.onPin({phase:'starting',total:3,out:'',"
            " route:'proxy',port:10808});"
            "window.onPin({phase:'route',via:'http://127.0.0.1:10808'});"
            "window.onPin({phase:'result',done:1,total:3,"
            " source:'de-fra.prod.surfshark.com_tcp.ovpn',"
            " name:'de-fra.prod.surfshark.com_tcp_146.70.160.213.ovpn',"
            " outcome:'reachable',ip:'146.70.160.213',port:1443});"
            "window.onPin({phase:'forged',host:'jp-tok.prod.surfshark.com',"
            " addresses:'10.10.34.35'});"
            "window.onPin({phase:'result',done:2,total:3,"
            " source:'jp-tok.prod.surfshark.com_tcp.ovpn',"
            " name:'jp-tok.prod.surfshark.com_tcp_146.70.211.107.ovpn',"
            " outcome:'unreachable',ip:'146.70.211.107',port:1443});"
            "window.onPin({phase:'result',done:3,total:3,"
            " source:'notes.ovpn',outcome:'skipped',"
            " detail:'no remote line, skipped'})")
        # The list is at the bottom of the pane, so the picture has to be of
        # the bottom of it.
        window.evaluate_js(
            "document.getElementById('prefPin').scrollIntoView({block:'end'})")
        time.sleep(0.9)
        said['pinRunningShot'] = shot('settings-pin-running')
        said['pinRows'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('#pinTally li'))"
            ".map(li => li.dataset.outcome + ' - ' + li.textContent)")
        window.evaluate_js(
            "window.onPin({phase:'finished',read:2,total:3,written:2,"
            " reachable:1,unreachable:1,skipped:1,out:'',inFolder:0,"
            " route:'http://127.0.0.1:10808',cancelled:false,error:null})")
        time.sleep(0.7)
        said['pinFinished'] = window.evaluate_js(
            "document.getElementById('pinSaid').textContent")
        said['pinDoneShot'] = shot('settings-pin-done')
        # Back to what the pane really has to say, so the check leaves the
        # page as it found it.
        window.evaluate_js("document.getElementById('pinTally').hidden = true;"
                           "refreshPin(true)")
        time.sleep(1.2)
        # The foot of the pane with nothing running, which is where the way
        # out of it lives: a folder full of pinned configs is worth nothing
        # until something is reading from it.
        window.evaluate_js(
            "document.getElementById('prefPin').scrollIntoView({block:'end'})")
        time.sleep(0.6)
        said['pinRestShot'] = shot('settings-pin-rest')

        window.evaluate_js("document.getElementById('prefsClose').click();"
                           "document.getElementById('pick').click()")
        time.sleep(1.2)
        said['pickerOpen'] = window.evaluate_js(
            "document.getElementById('picker').open")
        said['picker'] = shot('picker')
        # Which provider each country's exits come from, said on the row. The
        # point of the tag is the rows backed by both: with one provider
        # switched on it is decoration, and with two it is the only thing
        # that says which credential is about to open the exit.
        said['rowsTagged'] = window.evaluate_js(
            "document.querySelectorAll('.row .row__tag:not(.row__tag--none)').length")
        said['rowsWithBoth'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row'))"
            ".filter(r => r.querySelectorAll("
            "'.row__tag:not(.row__tag--none)').length > 1).length")
        said['tagLetters'] = window.evaluate_js(
            "Array.from(new Set(Array.from("
            "document.querySelectorAll('.row__tag:not(.row__tag--none)'))"
            ".map(t => t.textContent))).sort()")
        said['tagSaysWhich'] = window.evaluate_js(
            "(document.querySelector('.row__tag:not(.row__tag--none)') || {}).title")

        # -- one country, two ways into it ----------------------------
        #
        # Fed synthetic countries rather than whatever is pinned today: the
        # case that matters is a country both providers reach, and whether
        # this machine happens to have one is not something a test should
        # depend on. The rows are the real ones, built by the real builder.
        window.evaluate_js("""
            (function () {
              window.__realCountries = state.countries;
              state.countries = [
                {code: 'zz', name: 'Bothland', alias: '', cities: 2, count: 12,
                 best: null, by: {surfshark: 9, windscribe: 3}},
                {code: 'zy', name: 'Onlyland', alias: '', cities: 1, count: 4,
                 best: null, by: {windscribe: 4}}
              ];
              drawList('');
            })()""")
        time.sleep(0.6)
        # One row per place now, with a sign for each provider that backs
        # it - the rows-per-provider said less than the signs do and cost a
        # line each, on a list whose whole job is to be scanned.
        said['splitRows'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row[data-code]'))"
            ".map(r => r.dataset.code).filter(c => c.startsWith('z'))")
        said['splitSigns'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row[data-code=zz]"
            " .row__tag:not(.row__tag--none)')).map(t => t.textContent + ':' + t.dataset.state)")
        said['splitOneSign'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row[data-code=zy]"
            " .row__tag:not(.row__tag--none)')).map(t => t.textContent)")
        # And the sign's colour is that provider's own tally, which is the
        # whole reason it replaced the rows: a provider nobody asked about
        # must not inherit the other one's verdict.
        said['signStates'] = window.evaluate_js(
            "(() => { const n = {};"
            " for (const t of document.querySelectorAll('.row__tag:not(.row__tag--none)'))"
            "   n[t.dataset.state] = (n[t.dataset.state] || 0) + 1;"
            " return n; })()")
        # The rows must all be one height. Tags used to sit under the name,
        # so a country that had them was half a row taller than one that did
        # not, for a reason nothing on screen explained.
        said['rowHeights'] = window.evaluate_js(
            "Array.from(new Set(Array.from("
            "document.querySelectorAll('.row:not(.row--auto)'))"
            ".map(r => Math.round(r.getBoundingClientRect().height)))).sort()")
        said['splitSignTitles'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row[data-code=zz]"
            " .row__tag:not(.row__tag--none)')).map(t => t.title)")
        # A country only one provider reaches stays a single plain row - if
        # everything split, the split would mean nothing.
        said['splitLeavesSingles'] = window.evaluate_js(
            'document.querySelectorAll(".row[data-code=zy]").length === 1'
            ' && document.querySelectorAll(".row[data-code^=\'zy:\']").length === 0')
        # And picking one of the sub-rows is what tells the app which.
        said['splitPickable'] = window.evaluate_js(
            '!!document.querySelector(".row[data-code=\'zz:windscribe\']")')
        said['splitNames'] = window.evaluate_js(
            "nameOf('zz:windscribe') + ' | ' + nameOf('zz')")
        # Indented rows must still fit. .row is width:100%, so an indent put
        # on as a margin makes every one of them overhang by exactly the
        # indent and gives the list a horizontal scrollbar.
        said['splitOverflows'] = window.evaluate_js(
            "(() => { const l = document.getElementById('list');"
            " return l.scrollWidth - l.clientWidth; })()")
        said['splitWidest'] = window.evaluate_js(
            "(() => { const l = document.getElementById('list').getBoundingClientRect();"
            " return Math.max(0, ...Array.from(document.querySelectorAll('.row'))"
            ".map(r => Math.round(r.getBoundingClientRect().right - l.right))); })()")
        said['splitShot'] = shot('picker-split')
        window.evaluate_js(
            "state.countries = window.__realCountries; drawList('')")
        # A country only one of them reaches is the other half of the same
        # claim - if every row carried both tags the tag would mean nothing.
        said['rowsOneOnly'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row'))"
            ".filter(r => r.querySelectorAll("
            "'.row__tag:not(.row__tag--none)').length === 1).length")
        # And the chosen row, which is the only one that looks different and
        # is almost never the one at the top of the list.
        said['pickedRow'] = window.evaluate_js(
            "(() => { const r = document.querySelector('.row.is-picked');"
            " if (!r) return null;"
            " r.scrollIntoView({block: 'center'});"
            " return r.dataset.code; })()")
        time.sleep(0.7)
        # -- cities, marks, stars and order ---------------------------
        #
        # The four things Windscribe's own list has that ours did not, and
        # three of them come off data we were already downloading and
        # throwing away.
        # Shut by default now, which is the point - every country's cities
        # at once was a list of cities pretending to be a list of countries.
        said['citiesShutAtRest'] = window.evaluate_js(
            "document.querySelectorAll('.row--city').length")
        said['expandersOffered'] = window.evaluate_js(
            "document.querySelectorAll('[data-expand]').length")
        # From partway down, because the bug was that opening one threw the
        # list back to the top and lost the row that had just been clicked.
        window.evaluate_js(
            "document.getElementById('list').scrollTop = 320")
        time.sleep(0.4)
        said['scrollBefore'] = window.evaluate_js(
            "document.getElementById('list').scrollTop")
        window.evaluate_js(
            "(() => { const ps = document.querySelectorAll('[data-expand]');"
            " const p = ps[Math.min(3, ps.length - 1)];"
            " if (p) p.click(); return !!p; })()")
        time.sleep(0.9)
        said['scrollAfter'] = window.evaluate_js(
            "document.getElementById('list').scrollTop")
        said['cityRows'] = window.evaluate_js(
            "document.querySelectorAll('.row--city').length")
        said['expandDidNotConnect'] = window.evaluate_js(
            "document.getElementById('picker').open && state.mode !== 'busy'")
        said['cityNicks'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row--city .row__nick'))"
            ".slice(0, 4).map(e => e.textContent)")
        said['cityMarks'] = window.evaluate_js(
            "(() => { const r = document.querySelector('.row--city');"
            " if (!r) return null;"
            " return { load: !!r.querySelector('.load'),"
            "          marks: Array.from(r.querySelectorAll('.mark'))"
            "                      .map(m => m.textContent) }; })()")
        # A star is inside a row that would otherwise take the click and
        # connect somewhere, so the one thing worth asserting is that it
        # does not - and that the row it names goes to the top.
        # From a known state, and put back afterwards. A test that toggles
        # leaves the toggle where it left it, so the next run starts from the
        # opposite of what this one assumed and asserts the reverse.
        window.evaluate_js(
            "(async () => { for (const c of state.favourites.slice())"
            "   await window.pywebview.api.toggleFavourite(c);"
            " state.favourites = []; })()")
        time.sleep(0.8)
        said['starBefore'] = window.evaluate_js(
            "(document.querySelectorAll('.row[data-code]')[1] || {}).dataset"
            " && document.querySelectorAll('.row[data-code]')[1].dataset.code")
        window.evaluate_js(
            "(() => { const rows = document.querySelectorAll('.row[data-code]');"
            " const s = rows[1] && rows[1].querySelector('[data-star]');"
            " if (s) s.click(); return !!s; })()")
        time.sleep(1.0)
        said['starDidNotConnect'] = window.evaluate_js(
            "document.getElementById('picker').open && state.mode !== 'busy'")
        # Past the "Fastest available" row, which is always first and is not
        # a place - so the starred one is the first country, not the first
        # row.
        said['starFloatsUp'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row[data-code]'))"
            ".map(r => r.dataset.code).filter(c => c !== 'auto')[0]")
        said['starKept'] = window.evaluate_js('state.favourites.slice(0, 3)')
        # And unstarred again, so the roster this check leaves behind is the
        # one it found.
        window.evaluate_js(
            "(() => { const s = document.querySelector('[data-star][data-on=true]');"
            " if (s) s.click(); })()")
        time.sleep(0.6)
        said['starCleared'] = window.evaluate_js('state.favourites.length')
        # And the order really is a choice now.
        window.evaluate_js(
            "document.querySelector('#sortBy [data-sort=name]').click()")
        time.sleep(0.8)
        said['sortedByName'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row:not(.row--city)"
            "[data-code]')).slice(0, 4).map(r => r.dataset.code)")
        # If the picker has closed by here, something in the steps above
        # picked a row - which is worth knowing about, because none of them
        # is supposed to.
        said['pickerStillOpen'] = window.evaluate_js(
            "document.getElementById('picker').open")
        said['modeNow'] = window.evaluate_js('state.mode')
        said['citiesShot'] = shot('picker-cities')

        # -- picking one connects to it -------------------------------
        #
        # Stubbed, because the assertion is that choosing calls connect with
        # the code that was chosen - not that this machine can reach Austria
        # right now. Letting it through would also leave the check having
        # moved the Windows proxy, which is not a thing a check should do.
        window.evaluate_js("""
            (function () {
              window.__realConnect = window.pywebview.api.connect;
              window.__connectedTo = null;
              window.pywebview.api.connect = (code) => {
                window.__connectedTo = code;
                return Promise.resolve({ok: true});
              };
            })()""")
        window.evaluate_js(
            "(() => { const r = Array.from("
            "document.querySelectorAll('.row[data-code]'))"
            ".find(r => r.dataset.code !== 'auto');"
            " if (r) r.click(); return r && r.dataset.code; })()")
        time.sleep(1.0)
        said['pickConnected'] = window.evaluate_js('window.__connectedTo')
        said['pickClosedSheet'] = window.evaluate_js(
            "!document.getElementById('picker').open")
        # Put it all back: the real call, the idle state, and the status
        # line, which choose() had set to CONNECTING and which nothing else
        # resets while the connection it describes never happened.
        window.evaluate_js(
            'window.pywebview.api.connect = window.__realConnect;'
            "state.mode = 'off'; render();"
            "setStatus('DISCONNECTED', 'off', '', '');"
            'openPicker()')
        time.sleep(1.0)
        # Asserted, because the rows stay in the DOM whether the sheet is up
        # or not - so every check after this one passed while the screenshots
        # showed the window behind it.
        said['pickerBackOpen'] = window.evaluate_js(
            "document.getElementById('picker').open")
        window.evaluate_js(
            "document.querySelector('#sortBy [data-sort=ping]').click()")

        # -- which answered, and how fast -----------------------------
        #
        # Read off the real list rather than a synthetic one, because the
        # point of the feature is what it says about exits that have been
        # asked - and by now some of these have been.
        # Testing is per row now, so what proves the feature is on the page
        # is a row carrying its own control, not one button in the header.
        said['reachBar'] = window.evaluate_js(
            "!!document.querySelector('.row [data-test]')")
        said['rowStates'] = window.evaluate_js(
            "(() => { const n = {};"
            " for (const r of document.querySelectorAll('.row[data-state]'))"
            "   n[r.dataset.state] = (n[r.dataset.state] || 0) + 1;"
            " return n; })()")
        said['rowsSayMs'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row__meta'))"
            ".filter(e => / ms| s$|blocked here/.test(e.textContent)).length")
        # Opening one country's exits, which is the "each server" half.
        window.evaluate_js(
            "(() => { const m = document.querySelector('[data-exits]');"
            " if (m) m.click(); return !!m; })()")
        time.sleep(1.2)
        said['exitsOpened'] = window.evaluate_js(
            "document.querySelectorAll('.exit').length")
        said['exitsPickable'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.exit'))"
            ".every(e => (e.dataset.code || '').startsWith('file:'))")
        said['exitsSay'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.exit')).slice(0, 4).map("
            "e => e.dataset.state + ' ' + e.querySelector('.exit__ping').textContent)")
        # Which provider each exit is, and that opening one did not throw
        # the reader back to the top of the list.
        said['exitSigns'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.exit .row__tag:not(.row__tag--none)'))"
            ".slice(0, 4).map(t => t.textContent + ':' + t.dataset.state)")
        said['exitNames'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.exit__name'))"
            ".slice(0, 3).map(e => e.textContent)")
        said['exitsShot'] = shot('picker-exits')
        # A country whose every address was refused. It has to read as
        # different from one nobody has asked yet - that difference is the
        # whole feature, and it is the one thing a colour alone cannot carry.
        said['blockedSays'] = window.evaluate_js(
            "(() => { const r = document.querySelector('.row[data-state=blocked]');"
            " if (!r) return null; r.scrollIntoView({block: 'center'});"
            " return r.querySelector('.row__name').textContent + ' | '"
            "      + r.querySelector('.row__meta').textContent; })()")
        time.sleep(0.7)
        said['blockedShot'] = shot('picker-blocked')
        # -- a sweep of the whole list ---------------------------------
        #
        # Asked of the rendered page rather than of the code, because every
        # one of these has been wrong at some point in a way the code read
        # correctly: a row two pixels wider than the list, a control that
        # lost its column, a name that came out as its own filename.
        said['audit'] = window.evaluate_js(r"""
            (function () {
              const list = document.getElementById('list');
              const box = list.getBoundingClientRect();
              // Only the rows actually on screen. The list uses
              // content-visibility, so anything scrolled out of view reports
              // its contain-intrinsic-size placeholder rather than a height
              // anything has measured - and comparing those against real
              // ones invents a difference that is not on the page.
              const rows = Array.from(list.querySelectorAll('.row[data-code]'))
                .filter((r) => {
                  const b = r.getBoundingClientRect();
                  return b.bottom > box.top && b.top < box.bottom;
                });
              const heights = new Set();
              const bad = [];
              let noName = 0, noChip = 0, escaped = 0, rawCode = 0;
              for (const r of rows) {
                const rb = r.getBoundingClientRect();
                heights.add(Math.round(rb.height));
                if (rb.right > box.right + 0.5 || rb.left < box.left - 0.5) {
                  bad.push(r.dataset.code);
                }
                const n = r.querySelector('.row__name');
                if (!n || !n.textContent.trim()) noName++;
                // A name that still looks like a filename or a code.
                if (n && /\.ovpn|\.prod\.|totallyacdn/.test(n.textContent)) rawCode++;
                if (!r.querySelector('.chip')) noChip++;
                if (r.querySelector('.row__meta') &&
                    r.querySelector('.row__meta').scrollWidth
                      > r.querySelector('.row__meta').clientWidth + 1) escaped++;
              }
              // The right-hand controls must all start at the same x, or the
              // column reads as ragged however tidy each row is on its own.
              const starXs = new Set(Array.from(
                list.querySelectorAll('.row > .star'))
                .map(e => Math.round(e.getBoundingClientRect().left)));
              return {
                rows: rows.length,
                heights: [...heights].sort((a, b) => a - b),
                overflowing: bad.slice(0, 4),
                // What sticks out, which is the only sideways overflow a
                // person can see. Not scrollWidth against clientWidth, which
                // differ by the list's own padding and by the width a
                // vertical scrollbar takes back after the rows were sized
                // without it; and not scrollLeft, which moves under script
                // even on a box whose overflow-x is hidden. Both of those
                // report eight pixels of nothing on a list where no row
                // overhangs by one.
                sideways: (function () {
                  let by = 0;
                  for (const el of list.querySelectorAll('.row, .exit')) {
                    const b = el.getBoundingClientRect();
                    by = Math.max(by, b.right - box.right, box.left - b.left);
                  }
                  return Math.round(by);
                })(),
                withoutName: noName,
                withoutFlag: noChip,
                nameIsAFilename: rawCode,
                metaTruncated: escaped,
                starColumns: [...starXs].sort((a, b) => a - b),
                // And if the list scrolls sideways at all, which element is
                // sticking out. "8px of overflow" is not actionable; the
                // class of the thing causing it is.
                overflowX: getComputedStyle(list).overflowX,
                padding: getComputedStyle(list).paddingLeft + '/'
                  + getComputedStyle(list).paddingRight,
                widest: (function () {
                  let worst = null, by = 0;
                  for (const el of list.querySelectorAll('*')) {
                    const over = el.getBoundingClientRect().right - box.right;
                    if (over > by) { by = over; worst = el; }
                  }
                  for (const el of list.querySelectorAll('*')) {
                    const under = box.left - el.getBoundingClientRect().left;
                    if (under > by) { by = under; worst = el; }
                  }
                  return worst
                    ? `${worst.className} ${Math.round(by)}px` : 'nothing';
                })(),
              };
            })()""")
        said['whyTall'] = window.evaluate_js("""
            (function () {
              const pick = (sel) => document.querySelector(sel);
              const shape = (r) => {
                if (!r) return null;
                const cs = getComputedStyle(r);
                const copy = r.querySelector('.row__copy');
                const meta = r.querySelector('.row__meta');
                return {
                  code: r.dataset.code,
                  h: Math.round(r.getBoundingClientRect().height),
                  pad: cs.paddingTop + '/' + cs.paddingBottom,
                  copyH: copy ? Math.round(copy.getBoundingClientRect().height) : 0,
                  metaH: meta ? Math.round(meta.getBoundingClientRect().height) : 0,
                  metaW: meta ? Math.round(meta.getBoundingClientRect().width) : 0,
                  metaScroll: meta ? meta.scrollWidth : 0,
                  kids: Array.from(r.children).map(k => k.className),
                };
              };
              const rows = Array.from(
                document.querySelectorAll('.row[data-code]'));
              const short = rows.find(r =>
                Math.round(r.getBoundingClientRect().height) < 60);
              const tall = rows.find(r =>
                Math.round(r.getBoundingClientRect().height) >= 60);
              return {short: shape(short), tall: shape(tall)};
            })()""")
        said['auditDetail'] = window.evaluate_js("""
            (function () {
              const out = {tall: [], cut: []};
              const lb = document.getElementById('list').getBoundingClientRect();
              for (const r of document.querySelectorAll('.row[data-code]')) {
                const b = r.getBoundingClientRect();
                if (b.bottom <= lb.top || b.top >= lb.bottom) continue;
                const h = Math.round(b.height);
                if (h !== 53) out.tall.push(r.dataset.code + ':' + h);
                const m = r.querySelector('.row__meta');
                if (m && m.scrollWidth > m.clientWidth + 1) {
                  out.cut.push(r.dataset.code + ' :: ' + m.textContent);
                }
              }
              return out;
            })()""")
        # Search, which is the other way into the same list.
        window.evaluate_js(
            "document.getElementById('search').value = 'net';"
            "drawList('net')")
        time.sleep(0.6)
        said['searchFound'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row[data-code]'))"
            ".map(r => r.dataset.code).slice(0, 5)")
        said['searchKeepsControls'] = window.evaluate_js(
            "Array.from(document.querySelectorAll('.row[data-code]'))"
            ".every(r => r.querySelector('.star'))")
        window.evaluate_js(
            "document.getElementById('search').value = ''; drawList('')")
        time.sleep(0.5)
        # Each sort really reorders, and none of them loses a row.
        said['sortCounts'] = window.evaluate_js("""
            (function () {
              const out = {};
              for (const k of ['ping', 'load', 'name']) {
                document.querySelector(`#sortBy [data-sort=${k}]`).click();
                const rows = Array.from(
                  document.querySelectorAll('.row[data-code]'))
                  .map(r => r.dataset.code).filter(c => c !== 'auto');
                out[k] = [rows.length, rows.slice(0, 3).join(',')];
              }
              document.querySelector('#sortBy [data-sort=ping]').click();
              return out;
            })()""")
        said['pickedShot'] = shot('picker-picked')
        said['errorsAfter'] = window.evaluate_js('window.__errs')
    except Exception as e:
        said['checkFailed'] = repr(e)

    path = os.path.join(paths.STATE_DIR, 'ui-check.json')
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(said, f, ensure_ascii=False, indent=2)
    print(json.dumps(said, ensure_ascii=False, indent=2))
    try:
        window.destroy()
    except Exception:
        pass


def main():
    api = Api()
    undo = install_safety(api)

    settings = api._settings

    # The way-out strip and its line cost the card about eighty pixels, and
    # the orb is what paid: its height is what is left over, so it went from
    # round to squeezed without the page ever overflowing. A window saved
    # before that control existed is now too short for its own contents, so
    # it is raised once here rather than left for somebody to find by
    # dragging the edge. Only upwards, and only to the new floor - a window
    # deliberately made taller than that keeps the size it was given.
    if settings.get('h') and settings['h'] < WINDOW_H:
        settings['h'] = WINDOW_H
        save_settings(settings)

    window = webview.create_window(
        APP_NAME,
        os.path.join(paths.UI_DIR, 'index.html'),
        js_api=api,
        width=settings.get('w', WINDOW_W),
        height=settings.get('h', WINDOW_H),
        x=settings.get('x'), y=settings.get('y'),
        min_size=(380, 640),
        background_color='#111113',
        resizable=True,
        text_select=False,
        zoomable=False,
    )
    api._window = window

    tray = Tray(api, on_quit=lambda: window.destroy())
    api._tray = tray

    def on_start():
        # Each of these on its own, and none able to take the others down.
        # This runs on pywebview's own thread, where an exception is printed
        # and then swallowed - so a mistake in any one of them leaves a window
        # that came up and does nothing, with no tray, no address watcher and
        # no diagnostic. Which is exactly what one of them did.
        for step in (tray.start, dress_window,
                     lambda: threading.Thread(target=api._watch_self,
                                              daemon=True).start(),
                     lambda: threading.Thread(target=api._watch_traffic,
                                              daemon=True).start()):
            try:
                step()
            except Exception:
                traceback.print_exc()
        if '--ui-check' in sys.argv:
            threading.Thread(target=ui_check, args=(window,),
                             daemon=True).start()
        if '--ui-drive' in sys.argv:
            import uidrive
            threading.Thread(target=uidrive.run, args=(window,),
                             daemon=True).start()
        if '--ui-layout' in sys.argv:
            # An optional scene name after the flag, for the loop where you
            # are fixing one sheet and do not want to sit through nine.
            import uilayout
            i = sys.argv.index('--ui-layout') + 1
            only = sys.argv[i] if len(sys.argv) > i                 and not sys.argv[i].startswith('-') else None
            threading.Thread(target=uilayout.run,
                             args=(window, only, '--notes' in sys.argv),
                             daemon=True).start()
        if '--ui-perf' in sys.argv:
            # The other question about the same window: not whether anything
            # is in the wrong place, but whether it moves without stuttering.
            import uiperf
            i = sys.argv.index('--ui-perf') + 1
            only = sys.argv[i] if len(sys.argv) > i \
                and not sys.argv[i].startswith('-') else None
            threading.Thread(target=uiperf.run, args=(window, only),
                             daemon=True).start()

    def closing():
        # Remember where it was, and leave the connection alone. Quitting is
        # the tray's job; closing the window is just closing the window.
        try:
            where = {'w': window.width, 'h': window.height,
                     'x': window.x, 'y': window.y}
            # Only if it is somewhere. A window being torn down reports
            # coordinates from the far side of nowhere - -21332 was the real
            # number - and a position like that is remembered faithfully and
            # then restored, at which point the app opens off every screen
            # there is and the person has no way to know why. Nothing is
            # written rather than something that cannot be come back from.
            if all(-8000 < where[k] < 20000 for k in ('x', 'y')) \
                    and where['w'] >= 300 and where['h'] >= 400:
                settings.update(where)
                save_settings(settings)
        except Exception:
            pass
        undo()
        return True

    window.events.closing += closing

    try:
        webview.start(on_start, gui='edgechromium',
                      icon=paths.ICON if os.path.isfile(paths.ICON) else None)
    finally:
        api._stop.set()
        try:
            if tray.icon:
                tray.icon.stop()
        except Exception:
            pass
        undo()


if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc()
        try:
            winproxy.SystemProxy(paths.SAVED_PROXY).restore()
        except Exception:
            pass
        raise
