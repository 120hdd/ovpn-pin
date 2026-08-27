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


def run_selftest():
    """Say what this copy can see, without opening a window.

    Bundled apps fail by not finding things, silently, behind a window that
    only says something went wrong. This answers what support would otherwise
    have to ask over the phone, and it is how the build is checked.
    """
    import engine
    import pin
    import sweep
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
           'pinOut': pin.out_dir()}
    try:
        saved = load_settings()
        e = engine.Engine(winproxy.SystemProxy(paths.SAVED_PROXY),
                          folder=saved.get('folder') or None,
                          port=saved.get('port'),
                          set_system_proxy=saved.get('systemProxy', True))
        out['port'] = e.port
        out['serverCount'] = len(e.servers())
        out['countryCount'] = len(e.catalogue())
        out['credentialsReadable'] = bool(e.credentials())
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

import engine                                                # noqa: E402
import pin                                                   # noqa: E402
import sweep                                                 # noqa: E402
import webview                                               # noqa: E402
import winproxy                                              # noqa: E402
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

    def _describe(self):
        return {'countries': self._engine.catalogue(),
                'serverCount': len(self._engine.servers()),
                'folder': self._engine.folder,
                'systemProxy': self._engine.set_system_proxy,
                'port': self._engine.port,
                'defaultPort': engine.DEFAULT_PORT,
                'picked': self._settings.get('picked', 'auto'),
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
        if self._sysproxy.stashed() and not self._engine.running():
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
        save_settings(self._settings)
        return {'ok': True, 'folder': self._engine.folder}

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
        try:
            self._engine.credentials()
            return True
        except Exception:
            return False

    def saveCredentials(self, user, password):
        """The service username and password, set from the window.

        Until now the only way to give this app credentials was to put a file
        beside it by hand, which is a reasonable thing to ask of the person
        who wrote it and not of anyone else - and the app's answer to not
        having one was to disable its only button and say so.
        """
        # An empty password field with credentials already on file means "the
        # one I have", not "wipe it" - the field says as much, and a person
        # correcting a typo in the username should not have to fetch their
        # password again to do it.
        if not (password or '').strip():
            try:
                password = self._engine.credentials()[1]
            except Exception:
                pass
        try:
            note = self._engine.save_credentials(user, password)
        except RuntimeError as e:
            return {'ok': False, 'error': str(e)}
        except OSError as e:
            return {'ok': False,
                    'error': f'Could not write {paths.AUTH_FILE}: {e}'}
        if not self._has_credentials():
            return {'ok': False,
                    'error': 'Saved, but it cannot be read back. Check whether '
                             f'something else owns {paths.AUTH_FILE}.'}
        return {'ok': True, 'username': self._engine.username(),
                'path': paths.AUTH_FILE, 'env': note.get('env', False)}

    def forgetCredentials(self):
        try:
            os.remove(paths.AUTH_FILE)
        except FileNotFoundError:
            pass
        except OSError as e:
            return {'ok': False, 'error': str(e)}
        return {'ok': True}

    # -- measuring the servers properly ------------------------------------

    def _sweep_choices(self):
        return {'sites': self._settings.get('sites', ''),
                'scope': self._settings.get('sweepScope', 'all'),
                'chosen': self._settings.get('sweepLandlords', []),
                'first': int(self._settings.get('sweepFirst', 0) or 0)}

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
        save_settings(self._settings)
        return {'ok': True, 'folder': folder,
                'count': len(sweep.configs_in(folder))}

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
        return self._pin.start(was, into, lambda p: self._emit('Pin', p),
                               **self._pin_choices())

    def cancelPin(self):
        return self._pin.cancel()

    def usePinnedFolder(self, folder=None):
        """Connect through what was just pinned. The same move as picking a
        site's folder, and the same guard on it."""
        _, into = self._pin_folders()
        return self.useSiteFolder(folder or into)

    def remember(self, code):
        self._settings['picked'] = code
        save_settings(self._settings)
        return {'ok': True}

    def status(self):
        return self._engine.status()

    def connect(self, country):
        if self._busy:
            return {'ok': False, 'error': 'busy'}
        self._busy = True
        self._retray('busy')

        def work():
            try:
                result = self._engine.connect(
                    country, lambda p: self._emit('Progress', p))
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
                self._emit('Failed', {'kind': kind, 'detail': str(e)})
            finally:
                self._busy = False

        threading.Thread(target=work, daemon=True).start()
        return {'ok': True}

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
        try:
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

    def shot(name):
        """Our own window, asked to draw itself.

        PrintWindow rather than a screen grab, and the difference is not a
        detail. A grab of the rectangle our window occupies captures whatever
        is actually on those pixels - and this runs while the person's own
        windows still have focus, so the first two versions of this wrote
        somebody's chat window to disk under our name. Asking the window to
        render itself can only ever produce the window. It also works while it
        is behind something, which a check running in the background always is.

        PW_RENDERFULLCONTENT, because WebView2 draws through DWM and the plain
        call comes back with the page missing.
        """
        try:
            from PIL import Image
            user32, gdi32 = ctypes.windll.user32, ctypes.windll.gdi32
            handle = user32.FindWindowW(None, APP_NAME)
            if not handle:
                return 'no picture: our window was not found by title'
            rect = ctypes.wintypes.RECT()
            user32.GetWindowRect(handle, ctypes.byref(rect))
            w, h = rect.right - rect.left, rect.bottom - rect.top

            screen = user32.GetDC(0)
            dc = gdi32.CreateCompatibleDC(screen)
            bitmap = gdi32.CreateCompatibleBitmap(screen, w, h)
            gdi32.SelectObject(dc, bitmap)
            drawn = user32.PrintWindow(handle, dc, 2)

            buf = ctypes.create_string_buffer(w * h * 4)
            info = ctypes.create_string_buffer(40)
            ctypes.memmove(info, int(40).to_bytes(4, 'little')
                           + w.to_bytes(4, 'little')
                           + (-h & 0xFFFFFFFF).to_bytes(4, 'little')
                           + int(1).to_bytes(2, 'little')
                           + int(32).to_bytes(2, 'little')
                           + bytes(20), 40)
            gdi32.GetDIBits(dc, bitmap, 0, h, buf, info, 0)
            image = Image.frombuffer('RGBA', (w, h), buf, 'raw', 'BGRA', 0, 1)

            gdi32.DeleteObject(bitmap)
            gdi32.DeleteDC(dc)
            user32.ReleaseDC(0, screen)

            path = os.path.join(out, f'{name}.png')
            image.convert('RGB').save(path)
            size = f'{w}x{h}'
            if h < 300:
                # Worth saying rather than leaving as a picture of a title
                # bar: a window this short is a window that was not ready,
                # and the shot is of nothing.
                return f'{path} ({size} - too short to be the window)'
            return f'{path} ({size})' if drawn else f'{path} ({size}, PrintWindow said no)'
        except Exception as e:
            return f'no picture: {e!r}'

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
        said['settingsOpen'] = window.evaluate_js(
            "document.getElementById('prefs').open")
        said['settings'] = shot('settings')
        # What the two new sections are actually saying, which is the part a
        # picture of a scrolled sheet can miss.
        said['signIn'] = window.evaluate_js(
            "document.getElementById('authSaid').textContent")
        said['sweep'] = window.evaluate_js(
            "document.getElementById('sweepSaid').textContent")
        said['sweepFolder'] = window.evaluate_js(
            "document.getElementById('sweepFolder').textContent")
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
        # And the chosen row, which is the only one that looks different and
        # is almost never the one at the top of the list.
        said['pickedRow'] = window.evaluate_js(
            "(() => { const r = document.querySelector('.row.is-picked');"
            " if (!r) return null;"
            " r.scrollIntoView({block: 'center'});"
            " return r.dataset.code; })()")
        time.sleep(0.7)
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
    window = webview.create_window(
        APP_NAME,
        os.path.join(paths.UI_DIR, 'index.html'),
        js_api=api,
        width=settings.get('w', 400),
        height=settings.get('h', 660),
        x=settings.get('x'), y=settings.get('y'),
        min_size=(380, 560),
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
