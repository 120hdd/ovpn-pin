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

    real = sys.stdout
    sys.stdout = Tee()
    return stream, path


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
    import winproxy
    out = {'frozen': paths.FROZEN, 'app': paths.APP_DIR,
           'resources': paths.RES_DIR, 'data': paths.DATA_DIR,
           'proxyScriptFound': os.path.isfile(paths.PROXY_PY),
           'uiFound': os.path.isfile(os.path.join(paths.UI_DIR, 'index.html')),
           'fuseFound': os.path.isfile(os.path.join(paths.UI_DIR, 'fuse.js')),
           'iconFound': os.path.isfile(paths.ICON),
           'trayAvailable': _tray_importable(),
           'servers': paths.servers_dir(),
           'authFound': os.path.isfile(paths.AUTH_FILE)}
    try:
        e = engine.Engine(winproxy.SystemProxy(paths.SAVED_PROXY))
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

import webview                                               # noqa: E402
import winproxy                                              # noqa: E402
from engine import Engine                                    # noqa: E402

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
            set_system_proxy=self._settings_early.get('systemProxy', True))
        self._window = None
        self._tray = None
        self._busy = False
        self._settings = self._settings_early
        self._since = None
        self._real_ip = None
        self._stop = threading.Event()

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
                'picked': self._settings.get('picked', 'auto'),
                'about': (f'{APP_NAME}  -  port {self._engine.PORT}'
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
                self._sysproxy.engage('127.0.0.1', self._engine.PORT)
            else:
                self._sysproxy.restore()
        return {'ok': True, 'systemProxy': on}

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
        tray.start()
        threading.Thread(target=api._watch_self, daemon=True).start()

    def closing():
        # Remember where it was, and leave the connection alone. Quitting is
        # the tray's job; closing the window is just closing the window.
        try:
            settings.update({'w': window.width, 'h': window.height,
                             'x': window.x, 'y': window.y})
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
