"""The window.

Nothing here decides anything. It hands the engine a request, forwards what
the engine says back to the page, and makes very sure that whatever happens
to this process, the machine's proxy settings do not stay changed.

That last part is the only reason this file is careful rather than short. A
connection that fails is a bad afternoon; a machine left pointing at a proxy
that is not running is a person who thinks their internet is broken and has
no way to find out otherwise.
"""

import atexit
import json
import os
import signal
import sys
import threading
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import paths                                                 # noqa: E402


def run_as_worker():
    """Be the proxy rather than the window.

    Frozen, sys.executable is this app, so the window cannot start a proxy by
    running a script - it would start a second window. Instead it runs this
    exe again with a flag, and this branch turns into ovpn-proxy.py for the
    life of that process. Same file, same code path, no second copy of it.

    The redirection at the top is not defensive dressing. A --noconsole build
    has no standard streams at all, so sys.stdout is None, and ovpn-proxy.py
    asks stdout whether it is a terminal the moment it is imported - which
    throws before a single line of it runs. The worker then dies instantly
    and silently, and the window reports only that the proxy did not come up.
    Giving it somewhere to write turns that into a sentence in a file.
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
        argv = [a for a in sys.argv[1:] if a != paths.WORKER_FLAG]
        sys.argv = ['ovpn-proxy.py'] + argv
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


def run_selftest():
    """Say what this copy can see, without opening a window.

    Bundled apps fail by not finding things, and they do it silently behind a
    window that just says something went wrong. This answers the questions
    support would otherwise have to ask over the phone - where it is looking,
    whether the credentials are there, how many servers it found - and it is
    also how the build itself is checked, since a frozen window cannot easily
    be interrogated any other way.
    """
    import engine
    out = {'frozen': paths.FROZEN, 'app': paths.APP_DIR,
           'resources': paths.RES_DIR, 'data': paths.DATA_DIR,
           'proxyScript': paths.PROXY_PY,
           'proxyScriptFound': os.path.isfile(paths.PROXY_PY),
           'ui': os.path.join(paths.UI_DIR, 'index.html'),
           'uiFound': os.path.isfile(os.path.join(paths.UI_DIR, 'index.html')),
           'fontFound': os.path.isfile(
               os.path.join(paths.UI_DIR, 'fonts', 'Estedad-Variable.woff2')),
           'servers': paths.servers_dir(),
           'authFile': paths.AUTH_FILE,
           'authFound': os.path.isfile(paths.AUTH_FILE)}
    try:
        import winproxy
        e = engine.Engine(winproxy.SystemProxy(paths.SAVED_PROXY))
        out['serverCount'] = len(e.servers())
        out['countryCount'] = len(e.catalogue())
        out['credentialsReadable'] = bool(e.credentials())
        out['status'] = e.status()
    except Exception as exc:
        out['engineError'] = repr(exc)
    print(json.dumps(out, ensure_ascii=False, indent=2))
    report = os.path.join(paths.STATE_DIR, 'selftest.json')
    os.makedirs(paths.STATE_DIR, exist_ok=True)
    with open(report, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)


if '--selftest' in sys.argv:
    run_selftest()
    raise SystemExit(0)

import webview                                               # noqa: E402
import winproxy                                              # noqa: E402
from engine import Engine                                    # noqa: E402

SAVE_PATH = paths.SAVED_PROXY


class Api:
    def __init__(self):
        # Every attribute here is underscored on purpose. pywebview walks
        # this object to decide what to expose to the page, recursing into
        # any public non-callable attribute - and recursing into a Window
        # reaches .NET types and throws, which kills the whole bridge and
        # leaves a page that loads perfectly and can call nothing.
        self._sysproxy = winproxy.SystemProxy(SAVE_PATH)
        self._engine = Engine(self._sysproxy)
        self._window = None
        self._busy = False

    # -- talking to the page ----------------------------------------------

    def _emit(self, name, payload=None):
        """Events go to the page rather than return values, because the
        interesting part of connecting is what happens during it."""
        if not self._window:
            return
        try:
            self._window.evaluate_js(
                f'window.on{name}({json.dumps(payload or {}, ensure_ascii=False)})')
        except Exception:
            pass

    # -- what the page asks for -------------------------------------------

    def boot(self):
        """First call from the page. Also where a crash is cleaned up.

        If the previous run was killed without restoring the machine, the
        stash is still on disk. Nothing of ours is listening, so the only
        honest thing to do is put the settings back and say so - the
        alternative is a machine that cannot load a page and gives no clue
        why.
        """
        recovered = False
        if self._sysproxy.stashed() and not self._engine.running():
            recovered = self._sysproxy.restore()
        return {'status': self._engine.status(),
                'countries': self._engine.catalogue(),
                'recovered': recovered,
                'folder': self._engine.folder,
                'hasCredentials': self._has_credentials()}

    def _has_credentials(self):
        try:
            self._engine.credentials()
            return True
        except Exception:
            return False

    def status(self):
        return self._engine.status()

    def connect(self, country):
        if self._busy:
            return {'ok': False, 'error': 'busy'}
        self._busy = True

        def work():
            try:
                result = self._engine.connect(country, lambda p: self._emit('Progress', p))
                self._emit('Connected', result)
            except Exception as e:
                kind = str(e).split(':')[0] if str(e) else e.__class__.__name__
                self._emit('Failed', {'kind': kind, 'detail': str(e)})
                # Never leave the machine pointed somewhere that did not work.
                try:
                    self._engine.disconnect(quiet=True)
                except Exception:
                    pass
            finally:
                self._busy = False

        threading.Thread(target=work, daemon=True).start()
        return {'ok': True}

    def cancel(self):
        self._engine.cancelled.set()
        return {'ok': True}

    def disconnect(self):
        try:
            return {'ok': True, 'status': self._engine.disconnect()}
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def recheck(self):
        try:
            return {'ok': True, 'seen': self._engine.verify()}
        except Exception as e:
            return {'ok': False, 'error': str(e)}

    def minimise(self):
        if self._window:
            self._window.minimize()

    def close(self):
        if self._window:
            self._window.destroy()


def install_safety(api):
    """Put the machine back on every exit path there is.

    atexit covers the ordinary ones. The signal handlers cover a console
    Ctrl-C and a polite terminate. Nothing covers being killed outright,
    which is what the stash file on disk is for - see Api.boot.
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


def main():
    api = Api()
    undo = install_safety(api)

    window = webview.create_window(
        'اتصال',
        os.path.join(paths.UI_DIR, 'index.html'),
        js_api=api,
        width=430, height=700,
        min_size=(400, 600),
        background_color='#12100E',
        frameless=True,
        easy_drag=False,
        resizable=True,
    )
    api._window = window

    def closing():
        # Returning True lets it close. The restore happens first either way,
        # and is idempotent, so atexit repeating it costs nothing.
        undo()
        return True

    window.events.closing += closing

    try:
        webview.start(gui='edgechromium')
    finally:
        undo()


if __name__ == '__main__':
    try:
        main()
    except Exception:
        traceback.print_exc()
        try:
            winproxy.SystemProxy(SAVE_PATH).restore()
        except Exception:
            pass
        raise
